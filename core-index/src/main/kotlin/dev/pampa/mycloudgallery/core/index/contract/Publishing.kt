package dev.pampa.mycloudgallery.core.index.contract

import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.MetaFile
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.flow.StateFlow

// La PUBBLICAZIONE nell'indice condiviso (docs/fase4-progetto.md §2.1 "Pubblicazione", D5, D10, D31, D32, M3).
// Contratto di P0, implementazione di P3 (`publish/`). La usano la preparazione (P6) e, dalla Fase 5, l'uploader.

/**
 * Un cambiamento da pubblicare per un percorso della share. Il `seq` (D5) **non** lo sceglie chi chiama: lo assegna
 * l'outbox quando registra il cambiamento, nella stessa transazione, cosi' due lavori in parallelo non si contendono
 * un numero e un crash non ne lascia uno a meta'.
 */
sealed interface MetaChange {
  val path: SharePath

  /** Il file c'e' e si sa questo di lui (diventa `MetaRecord.Present`). */
  data class Put(override val path: SharePath, val file: MetaFile) : MetaChange

  /** Il file non c'e' piu' (diventa una lapide, `MetaRecord.Gone`). */
  data class Remove(override val path: SharePath) : MetaChange
}

/** Un fotogramma chiave di un video: istante nel video (ms) e JPEG (lato lungo 1024 px, §2.4). */
class VideoFrame(val atMs: Int, val jpeg: ByteArray) {
  init {
    require(atMs >= 0 && jpeg.isNotEmpty()) { "Fotogramma non valido." }
  }

  override fun equals(other: Any?): Boolean = other is VideoFrame && other.atMs == atMs && other.jpeg.contentEquals(jpeg)

  override fun hashCode(): Int = atMs * 31 + jpeg.contentHashCode()

  override fun toString(): String = "VideoFrame(${atMs}ms, ${jpeg.size} B)"
}

/**
 * Un'immagine da pubblicare per una chiave di contenuto. La codifica nel segmento (tabella, corpo `frames`) e' di P3:
 * chi prepara consegna solo le immagini.
 */
sealed interface BlobPut {
  val cls: SegmentClass
  val contentKey: ContentKey

  /** `thumbS`, `thumbM` (WebP), `analysis` (JPEG), `hq` (WebP). Le dimensioni le controlla P3 prima di firmare. */
  class Image(override val cls: SegmentClass, override val contentKey: ContentKey, val bytes: ByteArray) : BlobPut {
    init {
      require(cls in IMAGE_CLASSES) { "Classe non d'immagine." }
      require(bytes.isNotEmpty()) { "Immagine vuota." }
    }

    override fun toString(): String = "BlobPut.Image($cls, ${bytes.size} B)"
  }

  /** La copertina e i fotogrammi chiave di un video (da 1 a 9 immagini). */
  class Frames(override val contentKey: ContentKey, val frames: List<VideoFrame>) : BlobPut {
    init {
      require(frames.size in 1..MAX_FRAMES) { "Da 1 a $MAX_FRAMES fotogrammi." }
    }

    override val cls: SegmentClass get() = SegmentClass.FRAMES

    override fun toString(): String = "BlobPut.Frames(${frames.size})"
  }

  companion object {
    val IMAGE_CLASSES: Set<SegmentClass> = setOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.ANALYSIS, SegmentClass.HQ)
    const val MAX_FRAMES: Int = 9
  }
}

/** Perche' si chiede di pubblicare adesso. */
enum class FlushReason {
  /** Fine di un'unita' di lavoro (D13): l'unico caso che puo' superare il limite di una testa ogni 5 minuti (M3). */
  UNIT_FINISHED,

  /** Il segmento in costruzione ha raggiunto `TrustLimits.SEGMENT_TARGET_BYTES`. */
  SEGMENT_FULL,

  /** Fine di una scansione (lapidi e record di file nuovi adottati, 4B/Fase 5). */
  SCAN_FINISHED,

  /** Un giro dei lavori in background. */
  PERIODIC,
}

/** Cosa c'e' ancora nell'outbox (record e blob in attesa di una testa). */
data class PendingCounts(val records: Int, val blobs: Int, val bytes: Long) {
  init {
    require(records >= 0 && blobs >= 0 && bytes >= 0) { "Conteggi negativi." }
  }

  val isEmpty: Boolean get() = records == 0 && blobs == 0

  companion object {
    val EMPTY = PendingCounts(0, 0, 0)
  }
}

/**
 * Perche' adesso non si scrive nell'indice di una share. **Ogni** blocco lascia il lavoro nell'outbox (niente si perde) e
 * i dati restano visibili sul dispositivo dalla replica locale ([OwnIndexSink]).
 */
enum class WriteBlock {
  /** D31: `FeatureFlags.indexPublishTestSharesOnly` e la share non e' di prova (`TestShares`). */
  LOCAL_ONLY,

  /** D10: la share e' congelata per cambiamenti di massa. */
  FROZEN,

  /** D32, E-N17: l'ora del NAS ha fatto un salto oltre 5 minuti; si aspetta il gesto dell'utente o 24 ore stabili. */
  CLOCK_JUMP,

  /** E-I19: questo account legge l'area ma non ci scrive (`ShareProbe` = `READ_ONLY`). */
  READ_ONLY_AREA,

  /** "Ferma le scritture sul NAS" di Diagnostica (§1.4): l'emergenza dell'utente. */
  EMERGENCY_STOP,

  /** Il dispositivo non e' (piu') approvato, o il registro non e' fresco (`TrustGuard.preflight`). */
  NOT_TRUSTED,
}

/**
 * Chi decide se l'indice di una share si puo' scrivere adesso: lo compone :app (P11) dai flag (D31), dallo stato di
 * congelamento (`FreezeState` di core-nasmedia), dall'ora del NAS (`SessionNasClock.lastJumpMs`), dalla prova della
 * share e dall'emergenza. Il pubblicatore lo interroga **prima di firmare** ogni testa e prima di ogni prenotazione.
 * `null` = si puo' scrivere.
 */
fun interface IndexWriteGate {
  suspend fun blockFor(share: String): WriteBlock?
}

/** L'esito di [IndexPublisher.flush]. */
sealed interface PublishOutcome {
  /** Una testa nuova ([headVersion]) con [segments] segmenti nuovi pubblicati. */
  data class Published(val headVersion: Long, val segments: Int, val bytes: Long) : PublishOutcome

  /** L'outbox della share era vuota. */
  data object NothingPending : PublishOutcome

  /** Bloccato da [block]: tutto resta nell'outbox (e nella replica locale). */
  data class Blocked(val block: WriteBlock) : PublishOutcome

  /** M3: una testa ogni 5 minuti per share; si riprova dopo [retryAtNasMillis] (ora del NAS). */
  data class TooSoon(val retryAtNasMillis: Long) : PublishOutcome

  /**
   * E-I6 (NAS o quota pieni): si e' pubblicato per priorita' (`meta` > `thumbS` > `thumbM` > `analysis`/`frames` >
   * `hq`) solo [published]; il resto ([remaining]) aspetta nell'outbox e c'e' un avviso.
   */
  data class Partial(val published: Set<SegmentClass>, val remaining: PendingCounts, val failure: RemoteFailure) : PublishOutcome

  /** Niente pubblicato per un errore del NAS: si riprova al giro dopo, l'outbox e' intatta. */
  data class Failed(val failure: RemoteFailure) : PublishOutcome
}

/**
 * Il pubblicatore dell'indice di **questo** dispositivo (§2.1). Uno solo per processo (:app, `NasGraph`).
 *
 * - [stage] mette record e blob nell'**outbox persistente** (record nel database, blob in un pacco temporaneo per classe
 *   in `noBackupFilesDir/index-outbox/`): niente si perde se il processo muore. Assegna il `seq`. Passa subito tutto
 *   anche alla replica locale ([OwnIndexSink]), cosi' chi prepara vede le proprie miniature anche quando non si pubblica
 *   (D31). Regola M3, controllata qui: ogni percorso e ogni blob devono venire da un file di **questa** share (chi
 *   chiama lo garantisce costruendo i cambiamenti dai file della share; l'implementazione rifiuta un percorso che non
 *   appartiene alla share e un test di proprieta' lo verifica).
 * - [flush] costruisce i segmenti (nome = impronta, una ripetizione dopo un crash trova "esiste gia'" con gli stessi
 *   byte), poi la testa `version + 1`; prima di firmare chiede [IndexWriteGate] e rispetta M3.
 * - [pending] e' per lo stato (Diagnostica, piede della Libreria).
 */
interface IndexPublisher {
  suspend fun stage(share: String, changes: List<MetaChange>, blobs: List<BlobPut>)

  suspend fun flush(share: String, reason: FlushReason): PublishOutcome

  val pending: StateFlow<PendingCounts>
}

/**
 * La replica locale riceve subito cio' che questo dispositivo prepara o scopre, prima e indipendentemente dalla
 * pubblicazione (D31: sulle share vere la 4A lavora in solo locale; D17 vale per le miniature provvisorie). P4 la
 * implementa nella replica; P3 la chiama da [IndexPublisher.stage].
 */
interface OwnIndexSink {
  suspend fun applyOwn(share: String, records: List<MetaRecord>, blobs: List<BlobPut>)
}
