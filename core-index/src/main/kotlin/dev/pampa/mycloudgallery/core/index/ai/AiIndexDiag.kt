package dev.pampa.mycloudgallery.core.index.ai

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.trust.RejectReason
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.StateFlow

/**
 * I codici di Diagnostica dei dati AI dell'indice condiviso (§2.6 del progetto della Fase 6; `AiDiag` di core-ai ha quelli del calcolo).
 * Solo numeri ed enumerati nell'esportazione: mai share, chiavi, testo letto, vettori, id di dispositivo (D40).
 */
object AiIndexDiag {
  /** Teste o segmenti `ai` rifiutati, per motivo (E-I20, C9): il segmento intero, gli altri valgono. */
  val AI_SEGMENT_REJECTED = DiagCode("AI_SEGMENT_REJECTED", setOf("E-I20", "C9"))

  /** Due vettori della stessa ricetta che non concordano (coseno < 0,95, D7, E-A14): vince comunque la regola deterministica. */
  val AI_VECTOR_DISAGREE = DiagCode("AI_VECTOR_DISAGREE", setOf("E-A14"))

  /** Stato della pubblicazione `ai`: voci pubblicate, in attesa nell'outbox e in attesa di una share pubblicabile (D8, E-A15). */
  val AI_INDEX_STATE = DiagCode("AI_INDEX_STATE", setOf("E-A15"))

  val ALL: List<DiagCode> = listOf(AI_SEGMENT_REJECTED, AI_VECTOR_DISAGREE, AI_INDEX_STATE)
}

/** I contatori dei dati AI dell'indice (solo numeri): li scrivono pubblicatore e replica, li legge [AiIndexDiagnostics]. */
class AiIndexCounters {
  private val rejected = ConcurrentHashMap<RejectReason, AtomicInteger>()

  /** Voci pubblicate in una testa `ai` propria. */
  val published = AtomicInteger()

  /** M3: voci per chiavi che non sono (piu') di un file di quella share con timbro fresco: mai pubblicate. */
  val foreign = AtomicInteger()

  /** Voci che chi legge rifiuterebbe (componenti, norma, testo, tetti): mai pubblicate. */
  val unpublishable = AtomicInteger()

  /** Voci identiche a quella gia' pubblicata (stesso corpo): non si ripubblicano. */
  val unchanged = AtomicInteger()

  /** D7, E-A14: vettori della stessa ricetta in disaccordo. */
  val disagree = AtomicInteger()

  /** Dispositivi i cui dati `ai` sono stati tolti (revocati, persi, sconosciuti). */
  val devicesForgotten = AtomicInteger()

  /** E-I17: segmenti `ai` elencati ma assenti. */
  val segmentsMissing = AtomicInteger()

  /** Teste `ai` piu' vecchie di quella accettata o equivoche (E-I5). */
  val rollbacks = AtomicInteger()

  fun reject(reason: RejectReason) {
    rejected.getOrPut(reason) { AtomicInteger() }.incrementAndGet()
  }

  fun rejected(): Map<RejectReason, Int> = rejected.mapValues { it.value.get() }.filterValues { it > 0 }
}

/**
 * Il contributo dei dati AI dell'indice alla Diagnostica (sezione `SEARCH`). I titoli sono risorse di :app (P11): arrivano da
 * [titleOf], cosi' questo modulo non ha stringhe proprie.
 */
class AiIndexDiagnostics(
  private val counters: AiIndexCounters,
  private val pending: StateFlow<AiPendingCounts>?,
  private val titleOf: (DiagCode) -> Int,
) : DiagnosticsContributor {

  override val section: DiagSection = DiagSection.SEARCH

  override suspend fun collect(): List<DiagItem> = buildList {
    val rejected = counters.rejected()
    if (rejected.isNotEmpty()) {
      val export = HashMap<String, ExportValue>()
      export["count"] = ExportValue.Num(rejected.values.sum().toLong())
      rejected.forEach { (reason, n) -> export["reason.${reason.name}"] = ExportValue.Num(n.toLong()) }
      add(item(AiIndexDiag.AI_SEGMENT_REJECTED, DiagSeverity.WARNING, export))
    }
    val disagree = counters.disagree.get()
    if (disagree > 0) add(item(AiIndexDiag.AI_VECTOR_DISAGREE, DiagSeverity.WARNING, mapOf("count" to ExportValue.Num(disagree.toLong()))))
    val waiting = pending?.value ?: AiPendingCounts.EMPTY
    val export = mapOf(
      "published" to ExportValue.Num(counters.published.get().toLong()),
      "staged" to ExportValue.Num(waiting.staged.toLong()),
      "waitingForShare" to ExportValue.Num(waiting.waitingForShare.toLong()),
      "foreign" to ExportValue.Num(counters.foreign.get().toLong()),
      "unpublishable" to ExportValue.Num(counters.unpublishable.get().toLong()),
    )
    add(item(AiIndexDiag.AI_INDEX_STATE, DiagSeverity.INFO, export))
  }

  private fun item(code: DiagCode, severity: DiagSeverity, export: Map<String, ExportValue>): DiagItem =
    DiagItem(code, section, severity, titleOf(code), export = export)
}
