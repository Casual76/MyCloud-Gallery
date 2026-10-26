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

  /** Revisione A (R2): errori locali isolati nella replica `ai` (la share resta incompleta, il resto del giro va avanti). */
  val syncErrors = AtomicInteger()

  /** Revisione A (R14): compattazioni delle proprie teste `ai` e voci tolte (non piu' di un file della share, o non piu' sostenute). */
  val compactions = AtomicInteger()
  val entriesDropped = AtomicInteger()

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
  /** Le voci in attesa del pubblicatore di adesso (il motore del NAS puo' ripartire): null = nessun pubblicatore. */
  private val pending: suspend () -> AiPendingCounts?,
  private val titleOf: (DiagCode) -> Int,
  /** Altri numeri dello stato dell'indice `ai` (per esempio i tetti dell'indice locale, R1 e R3): solo numeri. */
  private val extra: () -> Map<String, Long> = { emptyMap() },
) : DiagnosticsContributor {

  /** Come prima (P2): le voci in attesa da un flusso fisso. */
  constructor(counters: AiIndexCounters, pending: StateFlow<AiPendingCounts>?, titleOf: (DiagCode) -> Int) :
    this(counters, { pending?.value }, titleOf)

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
    // Come nel catalogo: un'informazione (E-A14: vince comunque la regola deterministica).
    if (disagree > 0) add(item(AiIndexDiag.AI_VECTOR_DISAGREE, DiagSeverity.INFO, mapOf("count" to ExportValue.Num(disagree.toLong()))))
    val waiting = runCatching { pending() }.getOrNull() ?: AiPendingCounts.EMPTY
    val export = LinkedHashMap<String, ExportValue>()
    export["published"] = ExportValue.Num(counters.published.get().toLong())
    export["staged"] = ExportValue.Num(waiting.staged.toLong())
    export["waitingForShare"] = ExportValue.Num(waiting.waitingForShare.toLong())
    export["foreign"] = ExportValue.Num(counters.foreign.get().toLong())
    export["unpublishable"] = ExportValue.Num(counters.unpublishable.get().toLong())
    export["devicesForgotten"] = ExportValue.Num(counters.devicesForgotten.get().toLong())
    export["segmentsMissing"] = ExportValue.Num(counters.segmentsMissing.get().toLong())
    export["rollbacks"] = ExportValue.Num(counters.rollbacks.get().toLong())
    export["syncErrors"] = ExportValue.Num(counters.syncErrors.get().toLong())
    export["compactions"] = ExportValue.Num(counters.compactions.get().toLong())
    export["entriesDropped"] = ExportValue.Num(counters.entriesDropped.get().toLong())
    runCatching { extra() }.getOrNull()?.forEach { (name, n) -> export[name] = ExportValue.Num(n) }
    val severity = if (counters.syncErrors.get() > 0) DiagSeverity.WARNING else DiagSeverity.INFO
    add(item(AiIndexDiag.AI_INDEX_STATE, severity, export))
  }

  private fun item(code: DiagCode, severity: DiagSeverity, export: Map<String, ExportValue>): DiagItem =
    DiagItem(code, section, severity, titleOf(code), export = export)
}
