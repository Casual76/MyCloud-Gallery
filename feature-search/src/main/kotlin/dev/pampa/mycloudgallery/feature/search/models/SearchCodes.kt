package dev.pampa.mycloudgallery.feature.search.models

import android.content.res.Resources
import androidx.annotation.StringRes
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.feature.search.R

/** Cosa si puo' fare davanti a un problema della ricerca (§2.6): ogni azione e' una richiesta, mai un lavoro nascosto. */
enum class CodeAction(@StringRes val label: Int) {
  /** "Scarica ora": il modello (con la richiesta dell'utente, D17). */
  DOWNLOAD_NOW(R.string.code_action_download_now),

  /** "Riprova": rimette in coda il lavoro che non e' riuscito. */
  RETRY(R.string.code_action_retry),

  /** "Diagnostica": apre il codice nella Diagnostica (solo numeri e codici, mai testo, D40). */
  DIAGNOSTICS(R.string.code_action_diagnostics),

  /** "Libera spazio". */
  FREE_SPACE(R.string.code_action_free_space),

  /** "Attiva su questo dispositivo": il ruolo Indicizzatore (E-I12). */
  ENABLE_INDEXER(R.string.code_action_enable_indexer),
}

/**
 * Un problema della ricerca: il codice stabile, il suo messaggio e le sue azioni (zero o piu': "—" nella tabella del §2.6 sono i codici che si
 * risolvono da soli o che si riprovano da un'altra sorgente). [message] puo' avere un numero (`%1$s`: "N media", "serve X").
 */
class CodeEntry(val code: String, @StringRes val message: Int, val actions: List<CodeAction>, val takesAmount: Boolean = false) {
  fun text(resources: Resources, amount: String = ""): String =
    if (takesAmount) resources.getString(message, amount) else resources.getString(message)
}

/**
 * Il catalogo dei codici della ricerca (docs/fase6-progetto.md §2.6, "Ogni problema ha un codice e un'azione"). Un test li confronta con i
 * contratti (`AiDiag`, `ModelDiag`, `SearchDiag`) e con la tabella del §2.6: un codice nuovo senza messaggio non passa.
 *
 * I codici dei dati AI dell'indice (`AI_SEGMENT_REJECTED`, `AI_VECTOR_DISAGREE`, di core-index) e `ACT_LOCAL_BUFFER_ONLY` (della Diagnostica)
 * sono qui **per nome**: feature-search non vede core-index ne' feature-diagnostics (Phase6ArchitectureTest).
 */
object SearchCodes {

  val ENTRIES: List<CodeEntry> = listOf(
    CodeEntry("AI_MODEL_MISSING", R.string.code_ai_model_missing, listOf(CodeAction.DOWNLOAD_NOW)),
    CodeEntry("AI_MODEL_FETCH_FAILED", R.string.code_ai_model_fetch_failed, listOf(CodeAction.RETRY, CodeAction.DIAGNOSTICS)),
    CodeEntry("AI_MODEL_MISMATCH", R.string.code_ai_model_mismatch, emptyList()),
    CodeEntry("AI_MODEL_NO_SPACE", R.string.code_ai_model_no_space, listOf(CodeAction.FREE_SPACE), takesAmount = true),
    CodeEntry("AI_ENGINE_INIT_FAILED", R.string.code_ai_engine_init_failed, listOf(CodeAction.DIAGNOSTICS)),
    CodeEntry("AI_GPU_FALLBACK", R.string.code_ai_gpu_fallback, emptyList()),
    CodeEntry("AI_VECTOR_INVALID", R.string.code_ai_vector_invalid, listOf(CodeAction.RETRY), takesAmount = true),
    CodeEntry("AI_NO_INDEXER_AREA", R.string.code_ai_no_indexer_area, listOf(CodeAction.ENABLE_INDEXER)),
    CodeEntry("ACT_LOCAL_BUFFER_ONLY", R.string.code_act_local_buffer_only, emptyList()),
    CodeEntry("AI_STORAGE_LOW", R.string.code_ai_storage_low, listOf(CodeAction.FREE_SPACE)),
    CodeEntry("AI_KILLED_MEMORY", R.string.code_ai_killed_memory, emptyList()),
    CodeEntry("AI_SEGMENT_REJECTED", R.string.code_ai_segment_rejected, listOf(CodeAction.DIAGNOSTICS)),
    CodeEntry("AI_VECTOR_DISAGREE", R.string.code_ai_vector_disagree, listOf(CodeAction.DIAGNOSTICS)),
    CodeEntry("AI_PENDING_PUBLISH", R.string.code_ai_pending_publish, emptyList(), takesAmount = true),
    CodeEntry("OCR_FAILED", R.string.code_ocr_failed, listOf(CodeAction.RETRY), takesAmount = true),
    CodeEntry("SEARCH_INDEX_REBUILT", R.string.code_search_index_rebuilt, emptyList()),
    CodeEntry("TEXT_READING_OFF_CLEANUP", R.string.code_text_reading_off_cleanup, emptyList()),
    CodeEntry("CUTOUT_FAILED", R.string.code_cutout_failed, listOf(CodeAction.RETRY, CodeAction.DIAGNOSTICS)),
    CodeEntry("CUTOUT_SAVE_FAILED", R.string.code_cutout_save_failed, listOf(CodeAction.FREE_SPACE, CodeAction.RETRY)),
  )

  private val byCode: Map<String, CodeEntry> = ENTRIES.associateBy { it.code }

  fun entryOf(code: DiagCode): CodeEntry? = byCode[code.id]

  /** Il messaggio di [code], o la frase generica se il codice e' piu' nuovo dell'app (mai un'eccezione, mai il codice grezzo). */
  fun message(resources: Resources, code: DiagCode, amount: String = ""): String =
    entryOf(code)?.text(resources, amount) ?: resources.getString(R.string.code_unknown)

  /** Le azioni di [code]; un codice sconosciuto porta alla Diagnostica (dove si legge). */
  fun actions(code: DiagCode): List<CodeAction> = entryOf(code)?.actions ?: listOf(CodeAction.DIAGNOSTICS)
}
