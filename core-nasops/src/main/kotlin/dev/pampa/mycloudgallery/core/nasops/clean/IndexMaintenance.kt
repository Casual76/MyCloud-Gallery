package dev.pampa.mycloudgallery.core.nasops.clean

import dev.pampa.mycloudgallery.core.index.compact.CompactOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.CleanReport
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.nasops.contract.OpsGate
import dev.pampa.mycloudgallery.core.nasops.contract.OwnFilesCleaning
import dev.pampa.mycloudgallery.core.nasops.contract.PurgeStop
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Com'e' andato un giro di manutenzione dell'indice. */
data class MaintenanceReport(
  /** Le share in cui la compattazione ha pubblicato una testa nuova. */
  val compacted: Int,
  /** Le share saltate (bloccate, troppo presto, gia' fatte oggi). */
  val skipped: Int,
  val clean: CleanReport,
)

/**
 * La manutenzione dell'indice proprio (docs/fase4-progetto.md §1.2 "Compattazione", §2.7 ultima regola, M6; D32): **accende** la
 * compattazione (scritta in P3, `Compactor` di core-index) e la pulizia dei propri file ([OwnFilesCleaner]), una dopo l'altra per share:
 * prima la testa nuova con i segmenti riscritti, poi (al giro dopo il periodo di grazia) i segmenti che nessuna delle ultime teste elenca.
 *
 * Stesse condizioni della pulizia del cestino: niente con congelamento, salto dell'ora o emergenza ([OpsGate.statusOf] `blocksScheduled`),
 * niente senza ora del NAS, **al massimo un giro al giorno per share** (ora del NAS; la memoria dell'ultimo giro e' del processo: dopo un
 * riavvio si rifa' al massimo una volta in piu', innocuo). Il lavoro e' `INDEX_SYNC` (§2.8): lo pianifica `:app`.
 *
 * @param compact `Compactor.compactIfNeeded` (costruito da `:app` con `enabled = true`: la 4B lo accende).
 */
class IndexMaintenance(
  private val compact: suspend (String) -> CompactOutcome,
  private val cleaner: OwnFilesCleaning,
  private val gate: OpsGate,
  private val clocks: OpClocks,
  private val minIntervalMs: Long = DAY_MS,
) {
  private val lastRun = HashMap<String, Long>()

  suspend fun run(shares: List<String>, budget: Duration): MaintenanceReport {
    val nasNow = clocks.nasNowMs() ?: return MaintenanceReport(0, shares.size, CleanReport(0, emptyMap(), 0, PurgeStop.NO_NAS_TIME))
    val deadline = clocks.monotonicMs() + budget.inWholeMilliseconds
    var compacted = 0
    var skipped = 0
    val due = ArrayList<String>()
    for (share in shares) {
      val key = share.lowercase(Locale.ROOT)
      val last = synchronized(lastRun) { lastRun[key] }
      if (gate.statusOf(share).blocksScheduled || (last != null && nasNow in last until last + minIntervalMs)) {
        skipped++
        continue
      }
      // La compattazione ha le sue regole di scrittura (D31, congelamento, salto dell'ora, una testa ogni 5 minuti).
      when (compact(share)) {
        is CompactOutcome.Compacted -> compacted++
        else -> Unit
      }
      due += share
    }
    val remaining = (deadline - clocks.monotonicMs()).coerceAtLeast(0).milliseconds
    val clean = if (due.isEmpty()) CleanReport(0, emptyMap(), 0, PurgeStop.COMPLETE) else cleaner.clean(due, remaining)
    if (clean.stoppedBy == PurgeStop.COMPLETE) synchronized(lastRun) { due.forEach { lastRun[it.lowercase(Locale.ROOT)] = nasNow } }
    return MaintenanceReport(compacted, skipped, clean)
  }

  private companion object {
    const val DAY_MS = 24L * 60 * 60 * 1000
  }
}
