package dev.pampa.mycloudgallery.core.ai.contract

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.VecBody
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

// Il CALCOLO AI (docs/fase6-progetto.md §1.2 "core-ai", §2.2, D9-D11, D14, D21, D22). Contratti di P0; il calcolo vero (unita' con
// prenotazioni, buffer, ripresa, pubblicazione) e' di P5 (`compute/`). L'esecutore di riferimento `FakeAiCompute` (testing/) gira
// sugli stessi contratti con i motori finti: P9 e P10 partono da li'.

/** Dove lavora una tappa del calcolo (D14): una share (area privata propria o comune) o i media del solo telefono. */
sealed interface AiScope {
  data class Share(val share: String) : AiScope

  data object PhoneOnly : AiScope
}

/** Com'e' finita una tappa del calcolo. Solo numeri e codici. */
data class AiRunResult(
  /** Media calcolati e salvati in questa tappa (vettore, testo o entrambi). */
  val computed: Int,
  /** Media saltati perche' gia' fatti da un altro dispositivo (D9) o gia' salvati qui (ripresa, D22). */
  val skipped: Int,
  /** Media in attesa di un'anteprima (preparazione non ancora fatta, E-A15). */
  val waitingForPreparation: Int,
  /** Media falliti (vettore non valido due volte, formato non decodificabile): `AI_VECTOR_INVALID`, "non supportato". */
  val failed: Int,
  /** Perche' la tappa si e' fermata prima di finire, o null se ha finito. */
  val stoppedBy: AiStop?,
  val backend: AiBackend?,
) {
  init {
    require(computed >= 0 && skipped >= 0 && waitingForPreparation >= 0 && failed >= 0) { "Conteggi negativi." }
  }
}

/** I motivi stabili per cui il calcolo si ferma o aspetta. */
enum class AiStop {
  /** Tempo della tappa finito (8 minuti, `StagedRun`): si riprende dal media dopo. */
  BUDGET,

  /** Le regole (fascia, carica, temperatura `SEVERE`, E-A5) dicono di fermarsi. */
  RULES,

  /** Il ruolo Indicizzatore e' stato spento (E-A13): risultati pubblicati, prenotazione rilasciata. */
  ROLE_OFF,

  /** Il modello del calcolo manca o e' stato rifiutato. */
  MODEL_MISSING,

  /** Memoria del telefono sotto 1 GiB libero (E-A17): buffer svuotato, calcolo fermo. */
  STORAGE_LOW,

  /** Niente rete utile: solo il buffer (`LOCAL_BUFFER_ONLY`, E-A6). */
  BUFFER_ONLY,

  /** Il NAS non risponde. */
  NAS_UNREACHABLE,
}

/** La tappa del calcolo (§2.2): `AiComputeWorker` di :app la chiama dentro `StagedRun` (P11). */
interface AiComputeRun {
  suspend fun run(scope: AiScope, budget: Duration): AiRunResult
}

/**
 * Il buffer di analisi degli indicizzatori (D10): `noBackupFilesDir/ai-buffer/`, al massimo 500 MB, si ferma sotto 1 GiB libero.
 * Lo riempiono lo scaricamento delle anteprime (core-index, tramite la porta `AiBufferSink` legata da :app) e la preparazione
 * (`AnalysisSink` di core-prepare). [put] falso = pieno o poco spazio: chi scarica si ferma. [evict] svuota cio' che e' calcolato.
 */
interface AiBuffer {
  suspend fun put(key: ContentKey, cls: SegmentClass, bytes: ByteArray): Boolean

  suspend fun get(key: ContentKey, cls: SegmentClass): ByteArray?

  suspend fun evict(done: Collection<ContentKey>)

  val usedBytes: StateFlow<Long>

  companion object {
    const val MAX_BYTES: Long = 500L * 1024 * 1024
    const val MIN_FREE_BYTES: Long = 1024L * 1024 * 1024
  }
}

/** Un media da calcolare, per chiave di contenuto (D23: lo stesso contenuto in tre posti si calcola una volta). */
data class AiCandidate(
  val contentKey: ContentKey,
  val kind: MediaKind,
  val source: MediaSource,
  /** Le classi AI che mancano per la ricetta corrente (`ocr` solo se la lettura del testo e' attiva qui, D41). */
  val missing: Set<AiClass>,
) {
  override fun toString(): String = "AiCandidate($kind, $missing)"
}

/**
 * Le porte del calcolo verso il catalogo (P1), legate da :app. [forShare] e [phoneOnly] danno i media da calcolare dal piu'
 * recente (D21), nascosti compresi (risposta Q6: si calcolano, non si mostrano mai).
 */
interface AiCandidates {
  suspend fun forShare(share: String, recipe: Int, limit: Int): List<AiCandidate>

  suspend fun phoneOnly(recipe: Int, limit: Int): List<AiCandidate>
}

/**
 * Dove il calcolo salva i risultati, **subito** e per chiave di contenuto (D22, D23): vettori nel file dell'indice locale e righe
 * in `ai_vector`, testo in `ai_text`. Dopo una morte del processo [saved] dice cosa non rifare.
 */
interface AiResults {
  suspend fun saveVectors(key: ContentKey, body: VecBody)

  suspend fun saveText(key: ContentKey, body: OcrBody)

  /** Le classi gia' salvate qui per [key] con la ricetta [recipe] (o una piu' alta). */
  suspend fun saved(key: ContentKey, recipe: Int): Set<AiClass>
}

/**
 * Cosa puo' fare il calcolo su questo dispositivo adesso (D14, D15, D41): ruolo, lettura del testo, aree. L'area privata e' solo
 * quella dichiarata per l'account di questo dispositivo: **mai un'altra area privata**.
 */
interface AiRoles {
  val indexer: StateFlow<Boolean>

  /** La lettura del testo e' attiva per l'account e questo dispositivo non si e' escluso (D41, Q13). */
  val textReadingHere: StateFlow<Boolean>

  /** Le share su cui lavorare, in ordine (prima la privata propria, poi la comune, D14). */
  suspend fun shares(): List<String>
}

/** Perche' il calcolo aspetta (§1.5, `IndexerState`). Codici, mai messaggi. */
enum class AiWaitReason {
  RULES,
  NO_NETWORK,
  NAS_UNREACHABLE,
  STORAGE_LOW,
  HOT,
  PREPARATION,

  /** La tappa e' finita per tempo e ce n'e' un'altra in coda: "riprendo fra poco" (IX-2: non e' un'attesa delle regole). */
  NEXT_STAGE,
}

/** Lo stato del calcolo AI per la pagina Ricerca e modelli (§1.5). Solo numeri e codici. */
sealed interface AiStatus {
  /** Il dispositivo non e' Indicizzatore. */
  data object Off : AiStatus

  /** Indicizzatore senza il modello del calcolo. */
  data object ModelMissing : AiStatus

  /** Fermo con niente da fare; [lastRunNasMillis] e' l'ora del NAS dell'ultima tappa, se c'e'. */
  data class Idle(val lastRunNasMillis: Long?) : AiStatus

  data class Running(val done: Int, val total: Int, val etaMinutes: Int?, val backend: AiBackend) : AiStatus

  data class Waiting(val reason: AiWaitReason) : AiStatus

  /** Notte senza NAS (E-A6): "Fatto cio' che c'era in locale: N media; il resto aspetta il NAS". */
  data class BufferOnlyDone(val items: Int) : AiStatus

  data class Problem(val code: DiagCode) : AiStatus
}

/** La misura breve del dispositivo (D15): 20 immagini sintetiche dell'APK. */
data class ProbeResult(val imagesPerSecond: Double, val backend: AiBackend, val coldStartMs: Long) {
  /** Le notti per [items] media (una notte utile = [hoursPerNight] ore in carica). Almeno 1. */
  fun nightsFor(items: Int, hoursPerNight: Double = 6.4): Int {
    if (items <= 0 || imagesPerSecond <= 0.0) return 1
    val hours = items / imagesPerSecond / 3600.0
    return kotlin.math.ceil(hours / hoursPerNight).toInt().coerceAtLeast(1)
  }
}

interface DeviceProbe {
  suspend fun measure(): ProbeResult
}

/** I codici della Diagnostica del calcolo e dei motori (§2.6). Quelli dei dati AI dell'indice (`AI_SEGMENT_REJECTED`, `AI_VECTOR_DISAGREE`) sono di core-index (P2). */
object AiDiag {
  val AI_ENGINE_INIT_FAILED = DiagCode("AI_ENGINE_INIT_FAILED")
  val AI_GPU_FALLBACK = DiagCode("AI_GPU_FALLBACK", setOf("E-A12"))
  val AI_VECTOR_INVALID = DiagCode("AI_VECTOR_INVALID", setOf("E-A12"))
  val AI_NO_INDEXER_AREA = DiagCode("AI_NO_INDEXER_AREA", setOf("E-I12"))
  val AI_STORAGE_LOW = DiagCode("AI_STORAGE_LOW", setOf("E-A17"))
  val AI_KILLED_MEMORY = DiagCode("AI_KILLED_MEMORY", setOf("E-A16"))
  val AI_PENDING_PUBLISH = DiagCode("AI_PENDING_PUBLISH", setOf("E-A15"))
  val OCR_FAILED = DiagCode("OCR_FAILED")
  val TEXT_READING_OFF_CLEANUP = DiagCode("TEXT_READING_OFF_CLEANUP", setOf("E-A18"))
  val CUTOUT_FAILED = DiagCode("CUTOUT_FAILED")
  val CUTOUT_SAVE_FAILED = DiagCode("CUTOUT_SAVE_FAILED")

  val ALL: List<DiagCode> = listOf(
    AI_ENGINE_INIT_FAILED, AI_GPU_FALLBACK, AI_VECTOR_INVALID, AI_NO_INDEXER_AREA, AI_STORAGE_LOW, AI_KILLED_MEMORY,
    AI_PENDING_PUBLISH, OCR_FAILED, TEXT_READING_OFF_CLEANUP, CUTOUT_FAILED, CUTOUT_SAVE_FAILED,
  )
}
