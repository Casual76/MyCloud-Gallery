package dev.pampa.mycloudgallery.core.prepare.contract

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.index.contract.UnitPriority
import dev.pampa.mycloudgallery.core.index.contract.VideoFrame
import dev.pampa.mycloudgallery.core.index.contract.WorkItem
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.File
import kotlin.time.Duration

// La PREPARAZIONE (docs/fase4-progetto.md §2.4, D12, D14; E-M1–E-M14). Contratto di P0, logica di P6. Il preparatore
// non conosce il NAS: riceve byte ([PrepareSource]) e restituisce immagini ([PrepOutput]); chi lo esegue sulle unita' di
// lavoro ([PreparationRun]) le pubblica con `IndexPublisher` (core-index) e apre gli originali tramite [PrepInputs],
// legato da :app sopra `NasMediaReader`.

/** Da dove leggere l'originale. */
sealed interface PrepareSource {
  val kind: MediaKind
  val mime: String?

  /**
   * Byte a intervalli: un video si prepara **senza scaricarlo** (indice del contenitore e fotogrammi chiave con
   * `MediaMetadataRetriever` su `MediaBytes.dataSource()`, cache di lettura da 4 MiB, E-M7); una foto fino a 64 MiB si
   * legge in memoria con l'hash completo calcolato durante la lettura.
   */
  class Bytes(val bytes: MediaBytes, override val kind: MediaKind, override val mime: String?) : PrepareSource {
    override fun toString(): String = "PrepareSource.Bytes($kind, ${bytes.size} B)"
  }

  /** Un temporaneo locale (foto oltre 64 MiB, `cacheDir/prep/`; dalla Fase 5 i byte del telefono). */
  class Local(val file: File, override val kind: MediaKind, override val mime: String?) : PrepareSource {
    override fun toString(): String = "PrepareSource.Local($kind)"
  }
}

/** Le misure delle uscite (D12, Q2): lati in pixel e fotogrammi dei video. */
data class PrepSpec(
  /** Miniatura S: lato **corto** (WebP). */
  val thumbS: Int = 200,
  /** Miniatura M: lato **corto** (WebP). */
  val thumbM: Int = 400,
  /** Anteprima di analisi: lato **lungo** (JPEG q90). */
  val analysis: Int = 1024,
  /** Anteprima HQ: lato **lungo** (WebP). */
  val hq: Int = 2048,
  /** Un fotogramma ogni tanti secondi, da 1 a [maxFrames], piu' la copertina (E-A9). */
  val frameEverySec: Int = 30,
  val maxFrames: Int = 8,
  /** Le classi da produrre: quelle che mancano per la chiave di contenuto (`WorkItem.missing`). */
  val classes: Set<SegmentClass> = DEFAULT_CLASSES,
) {
  init {
    require(thumbS in 16..thumbM && thumbM <= analysis && analysis <= hq) { "Misure non crescenti." }
    require(frameEverySec > 0 && maxFrames in 1..8) { "Fotogrammi non validi." }
    require(classes.all { it.isContentBlob }) { "Solo classi d'immagine." }
  }

  companion object {
    val DEFAULT_CLASSES: Set<SegmentClass> =
      setOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.ANALYSIS, SegmentClass.HQ, SegmentClass.FRAMES)
  }
}

/** Un'uscita della preparazione. */
sealed interface PrepOutput {
  val cls: SegmentClass

  /** Un'immagine (WebP o JPEG) gia' orientata, con le sue dimensioni. */
  class Image(override val cls: SegmentClass, val bytes: ByteArray, val width: Int, val height: Int) : PrepOutput {
    init {
      require(cls in setOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.ANALYSIS, SegmentClass.HQ)) { "Classe non d'immagine." }
      require(bytes.isNotEmpty() && width > 0 && height > 0) { "Immagine vuota." }
    }

    override fun toString(): String = "PrepOutput.Image($cls, ${width}x$height, ${bytes.size} B)"
  }

  /** Copertina e fotogrammi chiave di un video (JPEG, lato lungo 1024). */
  class Frames(val frames: List<VideoFrame>) : PrepOutput {
    init {
      require(frames.size in 1..9) { "Da 1 a 9 fotogrammi." }
    }

    override val cls: SegmentClass get() = SegmentClass.FRAMES
  }
}

/** Cio' che la decodifica ha scoperto (i metadati corretti per il record, §2.4 "Uscite"). */
data class DecodedFacts(val width: Int, val height: Int, val durationMs: Long? = null) {
  init {
    require(width > 0 && height > 0) { "Dimensioni non valide." }
    require(durationMs == null || durationMs >= 0) { "Durata negativa." }
  }
}

/** I motivi di un fallimento (codici stabili, mai messaggi). */
enum class PrepFailCode {
  /** Il file non si decodifica (corrotto): 5 tentativi, poi "non leggibile" (E-M10). */
  DECODE_FAILED,

  /** Questo dispositivo non ha il decoder (APV, 8K...): passa di mano (D14, E-M13). */
  DECODER_MISSING,

  /** Oltre il tetto del temporaneo (512 MiB) o dei pixel: segnaposto (E-M3). */
  TOO_LARGE,

  /** Il NAS non risponde: si riprova senza contare il tentativo. */
  NAS_UNREACHABLE,

  /** Il file e' cambiato fra l'elenco e la lettura: lo rileva la scansione, qui si salta. */
  SOURCE_CHANGED,

  /** Tempo o memoria della tappa finiti: si riprende. */
  INTERRUPTED,
}

/** L'esito della preparazione di un media. */
sealed interface PrepResult {
  /**
   * @property fullHash lo SHA-256 dell'intero originale, calcolato **mentre** si leggeva (foto: gratis); null per i
   *   video, che non si leggono interi.
   * @property thumbHash il segnaposto dalla miniatura S (per un video, dalla copertina).
   * @property quickHash l'impronta rapida `qh1` dei byte **letti adesso** (foto: gratis dai byte in memoria; video: due
   *   letture da 64 KiB, che l'indice del contenitore riusa dalla cache). Chi pubblica la confronta con la chiave di
   *   contenuto del lavoro: se differisce il file e' cambiato dopo l'elenco e **nulla** si pubblica (D8: un `fh` accanto a
   *   un `qh` di altri byte potrebbe far unire due media diversi). Aggiunta di P6, con valore di default.
   */
  data class Done(
    val outputs: List<PrepOutput>,
    val fullHash: Sha256?,
    val facts: DecodedFacts?,
    val thumbHash: ThumbHash?,
    val quickHash: QuickHash? = null,
  ) : PrepResult

  /**
   * @property retryable vero se riprovare ha senso (rete, interruzione); falso per corrotto e decoder assente.
   * @property decoderFamily la famiglia del decoder di questo dispositivo, da pubblicare con [PrepFailCode.DECODER_MISSING].
   */
  data class Failed(val code: PrepFailCode, val retryable: Boolean, val decoderFamily: String? = null) : PrepResult {
    val decoderMissing: Boolean get() = code == PrepFailCode.DECODER_MISSING
  }
}

/**
 * Il preparatore (§2.4): una decodifica per media alla dimensione HQ (orientamento e spazio colore applicati, E-V3,
 * E-M15), poi le riduzioni dalla bitmap HQ; pixel limitati da [PrepBudget] (E-M1–E-M4); animati e motion photo dal primo
 * fotogramma o dalla foto ferma (E-M5, E-M11).
 */
interface Preparer {
  suspend fun prepare(source: PrepareSource, spec: PrepSpec = PrepSpec()): PrepResult
}

/**
 * I tetti di memoria della preparazione (§2.4, rischio "memoria con foto enormi"): **una** decodifica pesante alla volta,
 * al massimo due media in parallelo, temporanei fino a 512 MiB, pixel decodificati entro una frazione della memoria
 * dell'app. P6 ha fissato i numeri (vedi sotto); quelli con un valore di default si possono stringere nei test.
 *
 * | Tetto | Valore | Perche' |
 * |---|---|---|
 * | media insieme | 2 con 4+ processori, 1 sotto | la CPU del NAS e' il collo di bottiglia (16 MB/s con 4 flussi) |
 * | decodifiche pesanti | 1 | oltre 24 MP di sorgente il codec ha un working set che non si moltiplica |
 * | pixel di una bitmap | un quarto di `memoryClass` (ARGB_8888) | 256 MB → 16,7 MP; l'HQ ne usa 3,1 |
 * | pixel di una sorgente | 600 MP | oltre, la decodifica campionata costerebbe minuti di CPU (E-M3): `TOO_LARGE` |
 * | foto in memoria | fino a 64 MiB; oltre 16 MiB una per volta | il file sta in un array: 2 grandi insieme sono troppi |
 * | temporaneo | fino a 512 MiB | oltre, `TOO_LARGE` e segnaposto |
 *
 * @param memoryClassMb `ActivityManager.memoryClass` (o `largeMemoryClass` se dichiarato).
 * @param cores i processori disponibili.
 * @param inMemoryMaxBytes foto lette in memoria fino a qui, oltre si usa un temporaneo.
 * @param maxTempBytes tetto dei temporanei (`cacheDir/prep/`): oltre, `TOO_LARGE`.
 * @param maxSourcePixels oltre questi pixel (della sorgente, orientata) la foto non si decodifica nemmeno campionata: `TOO_LARGE`.
 */
class PrepBudget(
  val memoryClassMb: Int,
  val cores: Int,
  val inMemoryMaxBytes: Long = 64L * 1024 * 1024,
  val maxTempBytes: Long = 512L * 1024 * 1024,
  val maxSourcePixels: Long = 600_000_000L,
) {
  init {
    require(memoryClassMb > 0 && cores > 0) { "Budget non valido." }
    require(inMemoryMaxBytes in 1..Int.MAX_VALUE.toLong() && maxTempBytes >= inMemoryMaxBytes && maxSourcePixels > 0) { "Tetti non validi." }
  }

  /** Media preparati insieme: il collo di bottiglia e' la CPU del NAS (16 MB/s con 4 flussi). */
  val parallelMedia: Int get() = if (cores >= 4) 2 else 1

  /** Decodifiche pesanti (oltre [heavyPixelThreshold] pixel di sorgente) contemporanee. */
  val heavyDecodes: Int get() = 1

  /** Pixel oltre i quali una decodifica e' "pesante": 24 MP. */
  val heavyPixelThreshold: Long get() = 24_000_000L

  /** Pixel massimi di una bitmap decodificata (ARGB_8888): un quarto della memoria dell'app. */
  val maxDecodedPixels: Long get() = memoryClassMb.toLong() * 1024 * 1024 / 4 / 4

  /** I byte di una bitmap al tetto dei pixel. */
  val maxDecodedBytes: Long get() = maxDecodedPixels * BYTES_PER_PIXEL

  /** Una foto in memoria piu' grande di cosi' si legge da sola: due da 64 MiB insieme sono 128 MiB di array. */
  val largeFileBytes: Long get() = 16L * 1024 * 1024

  /** Il blocco di lettura dell'originale (con l'hash che segue la lettura). */
  val readChunkBytes: Int get() = 1024 * 1024

  /** Video: la cache di lettura a intervalli sopra `MediaBytes` (§2.4) e il suo blocco. */
  val videoCacheBytes: Int get() = 4 * 1024 * 1024
  val videoBlockBytes: Int get() = 256 * 1024

  /** Video: l'indice del contenitore (`moov`) si legge intero fino a qui; oltre non si guarda (lo guarda il sistema). */
  val maxIndexBytes: Long get() = 64L * 1024 * 1024

  companion object {
    const val BYTES_PER_PIXEL: Long = 4L
  }
}

/** Il risultato di un'apertura per la preparazione. */
sealed interface PrepOpen {
  /** L'originale pronto; [close] va chiamato sempre (chiude l'handle del NAS o toglie il temporaneo). */
  class Ready(val source: PrepareSource, val close: () -> Unit) : PrepOpen

  /** Non apribile adesso ([code]: `NAS_UNREACHABLE`, `SOURCE_CHANGED`, `TOO_LARGE`...). */
  data class Unavailable(val code: PrepFailCode) : PrepOpen
}

/**
 * Come si apre l'originale di un media di un'unita' (:app lo lega a `NasMediaReader` con `NasMediaBytes`, P5, e controlla
 * che il timbro sia ancora quello dell'elenco). La preparazione non vede il NAS.
 */
fun interface PrepInputs {
  suspend fun open(share: String, item: WorkItem): PrepOpen
}

/** Perche' un giro di preparazione si e' fermato. */
enum class PrepStop { BUDGET, RULES, NO_WORK, UNAVAILABLE, BLOCKED }

/** Il resoconto di un giro (solo numeri). */
data class RunResult(
  val units: Int,
  val prepared: Int,
  val failed: Int,
  val handedOver: Int,
  val bytesRead: Long,
  val stoppedBy: PrepStop?,
)

/**
 * Un giro di preparazione (§2.4): `WorkUnits.next` (prima le unita' visibili, poi dal mese piu' recente), per ogni media
 * [PrepInputs.open] → [Preparer.prepare] → `IndexPublisher.stage`, `keepAlive` ogni 20 minuti, `flush` e `finish` a
 * fine unita'. Salva l'unita' a ogni media (tappe da 8 minuti, D22). Lo esegue `PreparationWorker` (PREPARATION, solo
 * Preparatori) di :app.
 */
interface PreparationRun {
  suspend fun run(share: String, budget: Duration, priority: UnitPriority): RunResult
}

/** I codici di Diagnostica della preparazione (sezione `NAS_LIBRARY`), fissati da P0, catalogati da P11. */
object PrepDiag {
  val PREP_STATE = DiagCode("PREP_STATE")
  val PREP_DECODER_MISSING = DiagCode("PREP_DECODER_MISSING", setOf("E-M13"))
  val PREP_UNREADABLE = DiagCode("PREP_UNREADABLE", setOf("E-M10"))
  val PREP_TOO_LARGE = DiagCode("PREP_TOO_LARGE", setOf("E-M3"))

  val ALL: List<DiagCode> = listOf(PREP_STATE, PREP_DECODER_MISSING, PREP_UNREADABLE, PREP_TOO_LARGE)
}
