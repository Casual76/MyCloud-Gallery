package dev.pampa.mycloudgallery.core.ai.compute

import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.contract.AiStop
import dev.pampa.mycloudgallery.core.ai.contract.ModelImage
import dev.pampa.mycloudgallery.core.ai.contract.OcrImage
import dev.pampa.mycloudgallery.core.index.contract.VideoFrame
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOpen
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.VecBody
import java.util.concurrent.atomic.AtomicInteger

// Le PORTE del calcolo AI vero (docs/fase6-progetto.md §2.2, D9-D11, D14, D22; P5). Si aggiungono a quelle dei contratti di P0
// (`AiCandidates`, `AiResults`, `AiRoles`, `AiBuffer`) e di P2 (`WorkUnits` con `WorkPass.AI`, `AiDoneClasses`, `AiIndexPublisher`,
// `AnalysisFetcher`). Le lega :app (P11); qui solo firme, null-object e la loro documentazione.

/** Cosa dicono adesso regole e tempo della tappa (`StagedRun`, D22), riletto fra un lotto e l'altro. */
sealed interface AiPace {
  /**
   * Si continua. [reduced]: telefono caldo (`Pace.REDUCED`, E-A5): lotti piccoli e pause. [localOnly]: `Run(LOCAL_BUFFER_ONLY)`
   * (E-A6): niente NAS, solo cio' che sta gia' nel buffer o sul telefono.
   */
  data class Go(val reduced: Boolean = false, val localOnly: Boolean = false) : AiPace

  /** Ci si ferma al media dopo: [stop] e' [AiStop.RULES] (regole, `SEVERE`) o [AiStop.BUDGET] (tempo della tappa). */
  data class Halt(val stop: AiStop) : AiPace

  companion object {
    val GO: AiPace = Go()
  }
}

/**
 * Le regole come le vede il calcolo: :app la lega a `StageScope` di `StagedRun` (vedi [AiStageBatch]). [check] si chiama prima di
 * ogni lotto di media (non per ogni media: i lotti sono da 20, da 5 col caldo). [checkpoint] salva il punto di ripresa: e' solo
 * un'informazione per la Diagnostica e per la tappa dopo; la ripresa vera la fa [dev.pampa.mycloudgallery.core.ai.contract.AiResults.saved]
 * (ogni media si salva subito, D22).
 */
interface AiRunControl {
  suspend fun check(): AiPace

  suspend fun checkpoint(point: AiCheckpoint) = Unit

  companion object {
    /** Nessuna regola: solo il tempo di `run(scope, budget)`. */
    val ALWAYS: AiRunControl = object : AiRunControl {
      override suspend fun check(): AiPace = AiPace.GO
    }
  }
}

/**
 * Il punto di ripresa di una tappa (§2.2, riga "Tappa"): l'ambito, l'unita' e l'ultimo media finito. Mai nei log (la share e la
 * chiave sono dati): solo nel `CheckpointStore` del lavoro.
 */
data class AiCheckpoint(val scope: String, val unit: String?, val lastKey: String?) {
  override fun toString(): String = "AiCheckpoint(${if (unit == null) "-" else "unita'"})"
}

/**
 * La preparazione degli ingressi dei motori (D5): **mai** byte del NAS o di altre app al decodificatore nativo. P4 la implementa con
 * `ModelInputs` (`ImageBounds`, `ImageDecoder`, 768 px, JPEG q95); null o lista vuota = ingresso rifiutato ("non supportato").
 */
interface AiInputs {
  fun image(analysis: ByteArray, recipe: AiRecipe): ModelImage?

  /** Un'immagine per fotogramma, nello stesso ordine, con `frameMs`; i fotogrammi rifiutati mancano. */
  fun frames(frames: List<VideoFrame>, recipe: AiRecipe): List<ModelImage>

  fun ocr(bytes: ByteArray, source: OcrSource): OcrImage?
}

/** La sorgente dei media (per gli screenshot l'OCR va subito sull'HQ, D12). Assente = [MediaSource.OTHER]. */
fun interface AiMediaSources {
  suspend fun sources(keys: Collection<ContentKey>): Map<ContentKey, MediaSource>

  companion object {
    val UNKNOWN: AiMediaSources = AiMediaSources { emptyMap() }
  }
}

/**
 * I byte di un media **del telefono di questo dispositivo** (D10): per i media del solo telefono e per quelli del NAS che sono anche
 * qui (backup). `PrepOpen.Unavailable` = non c'e' (o non si apre ora). :app lo lega a MediaStore; la preparazione la fa il
 * `Preparer` di core-prepare (solo `analysis` e `frames`).
 */
fun interface AiPhoneMedia {
  suspend fun open(key: ContentKey, kind: MediaKind): PrepOpen

  companion object {
    val NONE: AiPhoneMedia = AiPhoneMedia { _, _ -> PrepOpen.Unavailable(dev.pampa.mycloudgallery.core.prepare.contract.PrepFailCode.SOURCE_CHANGED) }
  }
}

/** Lo spazio libero del telefono dove sta il buffer (`noBackupFilesDir`): sotto 1 GiB il calcolo si ferma (E-A17). */
fun interface AiFreeSpace {
  fun usableBytes(): Long

  companion object {
    val UNLIMITED: AiFreeSpace = AiFreeSpace { Long.MAX_VALUE }
  }
}

/**
 * I corpi gia' salvati **da questo dispositivo** nell'indice locale (P6: `LocalAiIndex`, origine locale). Servono a ripubblicare senza
 * ricalcolare: un media calcolato per l'area privata che compare anche nell'area comune (§2.2, "stesso contenuto in area privata e
 * comune"), o salvato poco prima di una morte del processo e non ancora messo nell'outbox. Null = non c'e'.
 */
interface AiLocalBodies {
  suspend fun vectors(key: ContentKey): VecBody?

  suspend fun text(key: ContentKey): OcrBody?

  companion object {
    val NONE: AiLocalBodies = object : AiLocalBodies {
      override suspend fun vectors(key: ContentKey): VecBody? = null

      override suspend fun text(key: ContentKey): OcrBody? = null
    }
  }
}

/**
 * Un media che questo dispositivo non sa calcolare (ingresso rifiutato, vettore non valido due volte: E-A12): :app lo segna nel
 * catalogo come "non supportato" per quella classe (`AiWorkCatalog.settled` lo considera a posto, la copertura lo conta, D31).
 */
fun interface AiUnsupported {
  suspend fun mark(key: ContentKey, cls: AiClass)

  companion object {
    val NONE: AiUnsupported = AiUnsupported { _, _ -> }
  }
}

/** I contatori del calcolo per la Diagnostica (§2.6): solo numeri. */
class AiComputeCounters {
  /** Il motore delle immagini e' passato alla CPU (E-A12, `AI_GPU_FALLBACK`). */
  val gpuFallbacks = AtomicInteger()

  /** Vettori non validi due volte: media saltato (`AI_VECTOR_INVALID`). */
  val vectorsInvalid = AtomicInteger()

  /** Ingressi rifiutati da [AiInputs] ("non supportato"). */
  val inputsRefused = AtomicInteger()

  /** Letture del testo fallite (`OCR_FAILED`): il media resta senza testo, si riprova al giro dopo. */
  val ocrFailed = AtomicInteger()

  /** Tappe fermate sotto 1 GiB libero (`AI_STORAGE_LOW`, E-A17). */
  val storageLow = AtomicInteger()

  /** Voci in attesa ripubblicate quando la share si e' aperta (D8, F4-D31). */
  val pendingReleased = AtomicInteger()

  /** Voci rimesse nell'outbox da cio' che era gia' salvato qui (nessun ricalcolo). */
  val restaged = AtomicInteger()

  /** Prenotazioni perse a meta' unita' (E-I2). */
  val unitsLost = AtomicInteger()

  /** Pubblicazioni fallite per un errore del NAS (l'outbox resta, si riprova). */
  val flushFailures = AtomicInteger()

  /** Ambiti rifiutati perche' non sono fra le aree di questo dispositivo (D14): devono restare a zero. */
  val foreignScopes = AtomicInteger()
}
