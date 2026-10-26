package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.ai.contract.AiDiag
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.index.ai.AiIndexDiag
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.search.contract.SearchDiag
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes

/**
 * La sezione "Ricerca" della Diagnostica (Fase 6, §2.6, P11): stato (modelli, ruolo, lettura del testo, indice), contatori del calcolo,
 * dei dati AI dell'indice e dell'indice locale. **Solo numeri e codici** (D40): mai query, testo letto, luoghi, vettori, chiavi, share.
 */
class AiDiagnostics(private val stack: AiStack) : DiagnosticsContributor {

  override val section: DiagSection = DiagSection.SEARCH

  override suspend fun collect(): List<DiagItem> {
    val items = ArrayList<DiagItem>()
    val ai = stack.ai
    val states = stack.localModels.manager.states.value
    val (vectors, texts) = runCatching { stack.search.index.sizes }.getOrDefault(0 to 0)
    items += item(
      DiagCodes.SEARCH_STATE,
      DiagSeverity.INFO,
      mapOf(
        "queryModel" to ExportValue.Flag(states[ModelCatalog.QUERY_270M.id] is ModelState.Ready),
        "indexModel" to ExportValue.Flag(states[ModelCatalog.INDEX_440M.id] is ModelState.Ready),
        "indexer" to ExportValue.Flag(ai.indexer.value),
        "textReadingHere" to ExportValue.Flag(ai.textReadingHere.value),
        "vectors" to ExportValue.Num(vectors.toLong()),
        "texts" to ExportValue.Num(texts.toLong()),
        "bufferBytes" to ExportValue.Num(ai.buffer.usedBytes.value),
        // DG-5: quanto pesano i modelli (con i programmi compilati) e le pagine della ricerca.
        "modelsBytes" to ExportValue.Num(runCatching { stack.localModels.manager.usage().totalBytes }.getOrDefault(0L)),
        "status" to ExportValue.Enum.of(statusCode(ai.status.value)),
      ),
    )
    // DG-5: solo i modelli che servono a questo dispositivo (il 270M sempre, il 440M agli indicizzatori): il ritaglio sta nell'APK e il 440M di un
    // tablet che non analizza non manca a nessuno (prima "models=2" per sempre).
    val wanted = listOfNotNull(ModelCatalog.QUERY_270M.id, ModelCatalog.INDEX_440M.id.takeIf { ai.indexer.value })
    val missing = wanted.count { states[it] !is ModelState.Ready }
    if (missing > 0) items += item(DiagCodes.AI_MODEL_MISSING, DiagSeverity.INFO, mapOf("models" to ExportValue.Num(missing.toLong())))
    // DG-2: gli esiti dello scaricamento dei modelli (solo conteggi del processo: i codici veri arrivano anche dagli eventi dei lavori).
    val models = stack.localModels.counters
    if (models.mismatch + models.verifyFailed > 0) items += count(DiagCodes.AI_MODEL_MISMATCH, models.mismatch + models.verifyFailed, DiagSeverity.WARNING)
    if (models.fetchFailed > 0) items += count(DiagCodes.AI_MODEL_FETCH_FAILED, models.fetchFailed, DiagSeverity.WARNING)
    if (models.noSpace > 0) items += count(DiagCodes.AI_MODEL_NO_SPACE, models.noSpace, DiagSeverity.WARNING)
    ai.computeCounters()?.let { c ->
      if (c.gpuFallbacks.get() > 0) items += count(DiagCodes.AI_GPU_FALLBACK, c.gpuFallbacks.get(), DiagSeverity.INFO)
      if (c.vectorsInvalid.get() > 0) items += count(DiagCodes.AI_VECTOR_INVALID, c.vectorsInvalid.get(), DiagSeverity.INFO)
      if (c.storageLow.get() > 0) items += count(DiagCodes.AI_STORAGE_LOW, c.storageLow.get(), DiagSeverity.WARNING)
      if (c.ocrFailed.get() > 0) items += count(DiagCodes.OCR_FAILED, c.ocrFailed.get(), DiagSeverity.INFO)
    }
    val pending = runCatching { ai.publishStore.pending() }.getOrNull()
    if (pending != null && pending.waitingForShare > 0) {
      items += item(
        DiagCodes.AI_PENDING_PUBLISH,
        DiagSeverity.INFO,
        mapOf("outbox" to ExportValue.Num(pending.staged.toLong()), "held" to ExportValue.Num(pending.waitingForShare.toLong())),
      )
    }
    val rebuilt = stack.search.index.counters.rebuilt.get()
    if (rebuilt > 0) items += count(DiagCodes.SEARCH_INDEX_REBUILT, rebuilt, DiagSeverity.INFO)
    if (TextReadingStartup.cleanedAtStartup) items += item(DiagCodes.TEXT_READING_OFF_CLEANUP, DiagSeverity.INFO, emptyMap())
    return items
  }

  private fun statusCode(status: AiStatus): StatusCode = when (status) {
    AiStatus.Off -> StatusCode.OFF
    AiStatus.ModelMissing -> StatusCode.MODEL_MISSING
    is AiStatus.Idle -> StatusCode.IDLE
    is AiStatus.Running -> StatusCode.RUNNING
    is AiStatus.Waiting -> StatusCode.WAITING
    is AiStatus.BufferOnlyDone -> StatusCode.BUFFER_ONLY
    is AiStatus.Problem -> StatusCode.PROBLEM
  }

  private enum class StatusCode { OFF, MODEL_MISSING, IDLE, RUNNING, WAITING, BUFFER_ONLY, PROBLEM }

  private fun count(code: DiagCode, n: Int, severity: DiagSeverity) = item(code, severity, mapOf("count" to ExportValue.Num(n.toLong())))

  private fun item(code: DiagCode, severity: DiagSeverity, export: Map<String, ExportValue>): DiagItem =
    DiagItem(code, section, severity, DiagCatalog.titleOf(code), null, null, export)

  companion object {
    /** Tutti i codici della Fase 6 che i contratti dichiarano (§2.6): il catalogo li deve avere tutti (`Phase6DiagCatalogTest`). */
    val CONTRACT_CODES: List<DiagCode> = AiDiag.ALL + dev.pampa.mycloudgallery.core.models.contract.ModelDiag.ALL + SearchDiag.ALL + AiIndexDiag.ALL
  }
}
