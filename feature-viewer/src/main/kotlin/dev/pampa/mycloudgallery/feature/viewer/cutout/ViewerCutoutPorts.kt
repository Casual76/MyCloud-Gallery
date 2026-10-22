package dev.pampa.mycloudgallery.feature.viewer.cutout

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef

// Il RITAGLIO DEL SOGGETTO nel viewer (docs/fase6-progetto.md §2.10, D44, D45, D47; risposte Q9, Q11, Q12). Porte e stati di P0; la
// UI (animazione del soggetto che si stacca, barra "Copia · Condividi · Salva come nuova foto", interruttore "Contorno", voce ⋮) e'
// di P13, che lega anche il ritagliatore vero (MagicTouch su LiteRT in core-ai) tramite :app. Il viewer non vede core-ai.

/** Un rettangolo in frazioni dell'immagine raddrizzata (0..1). */
data class ViewerRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** La maschera del soggetto: alfa (0..255) per pixel, grande come l'immagine su cui si e' ritagliato. */
class ViewerMask private constructor(val width: Int, val height: Int, private val data: ByteArray, @Suppress("UNUSED_PARAMETER") owned: Unit) {

  /** Copia [alpha]. */
  constructor(width: Int, height: Int, alpha: ByteArray) : this(width, height, alpha.copyOf(), Unit)

  init {
    require(width > 0 && height > 0 && data.size == width * height) { "Maschera non valida." }
  }

  fun alpha(): ByteArray = data.copyOf()

  /**
   * Presta l'alfa a [block] **senza copiarlo** (R20): chi lo riceve lo legge soltanto e non lo tiene. Una foto da 12 megapixel pesa 12,6 MB per copia, e con poca
   * memoria ogni copia in meno conta (il ritaglio dava un `OutOfMemoryError` proprio qui).
   */
  fun <T> readAlpha(block: (ByteArray) -> T): T = block(data)

  /** L'alfa (0..255) del pixel ([x], [y]) senza copiare la maschera (P13): fuori dai bordi 0. */
  fun at(x: Int, y: Int): Int = if (x in 0 until width && y in 0 until height) data[y * width + x].toInt() and 0xff else 0

  /** L'alfa senza copiarlo, per chi lo legge soltanto (la preparazione del disegno). */
  internal fun raw(): ByteArray = data

  override fun toString(): String = "ViewerMask(${width}x$height)"

  companion object {
    /** La maschera che prende possesso di [alpha] senza copiarlo (chi la chiama non lo tocca piu'): il ritagliatore di `:app` (R20). */
    fun adopting(width: Int, height: Int, alpha: ByteArray): ViewerMask = ViewerMask(width, height, alpha, Unit)
  }
}

/** L'esito di un ritaglio per il viewer. */
sealed interface ViewerCutout {
  data class Subject(val mask: ViewerMask, val bounds: ViewerRect) : ViewerCutout {
    /** Quanto e' grande il soggetto: l'area del suo rettangolo in frazione dell'immagine (0..1), per scegliere "il piu' grande" (D46). */
    val area: Float get() = (bounds.right - bounds.left).coerceAtLeast(0f) * (bounds.bottom - bounds.top).coerceAtLeast(0f)

    /** Il punto normalizzato ([x], [y]) cade sul soggetto (alfa >= 128). */
    fun contains(x: Float, y: Float): Boolean = mask.at((x * mask.width).toInt().coerceIn(0, mask.width - 1), (y * mask.height).toInt().coerceIn(0, mask.height - 1)) >= 128
  }

  /** Nessun soggetto chiaro (E-V10): niente, vibrazione leggera, nessun messaggio d'errore. */
  data object NoSubject : ViewerCutout

  /**
   * Un guasto, con il codice stabile per la Diagnostica (`CUTOUT_FAILED`). [reason] dice **perche'**, quando lo si sa, cosi' il viewer lo dice (VR-2, R20):
   * poca memoria o poco spazio non sono "riprova e basta".
   */
  data class Failed(val code: String, val reason: CutoutFailure = CutoutFailure.OTHER) : ViewerCutout
}

/** Perche' un ritaglio non e' riuscito, quando lo si sa. */
enum class CutoutFailure {
  /** Non si sa, o niente da dire all'utente oltre a "riprova". */
  OTHER,

  /** La memoria e' finita (una foto grande, molte app aperte): chiudere qualche app e riprovare (R20). */
  LOW_MEMORY,

  /** Lo spazio del telefono non basta nemmeno per il piccolo modello del ritaglio (VR-2). */
  NO_SPACE,
}

/**
 * Il ritagliatore visto dal viewer: dal punto toccato ([x], [y] normalizzati sull'immagine raddrizzata, dopo la trasformazione dello
 * zoom) sulla migliore immagine gia' sul dispositivo (mai uno scaricamento solo per ritagliare). Mai rete (F67).
 */
fun interface ViewerSubjectCutter {
  suspend fun cut(id: MediaId, ref: MediaRef, x: Float, y: Float): ViewerCutout
}

/** L'esito di "Salva come nuova foto" (D47). */
sealed interface CutoutSaveResult {
  data class Saved(val newMedia: MediaId?) : CutoutSaveResult

  /** Spazio o permesso (`CUTOUT_SAVE_FAILED`). */
  data object Failed : CutoutSaveResult
}

/**
 * Copia, condivisione e salvataggio del ritaglio (D47): PNG trasparente **senza metadati**; Copia e Condividi passano da
 * `cacheDir/share/` del FileProvider esistente con appunti sensibili; "Salva come nuova foto" scrive in `Pictures/MyCloud Gallery/
 * Ritagli/` (in debug con un account di prova `Pictures/mcg-prova/Ritagli/`) con la data di adesso e nessuna posizione; dai
 * Nascosti ([fromHidden]) la foto nuova nasce nascosta (E-V11).
 */
interface CutoutSaver {
  suspend fun copy(id: MediaId, ref: MediaRef, cutout: ViewerCutout.Subject, outline: Boolean): Boolean

  suspend fun share(id: MediaId, ref: MediaRef, cutout: ViewerCutout.Subject, outline: Boolean): Boolean

  suspend fun saveAsNewPhoto(id: MediaId, ref: MediaRef, cutout: ViewerCutout.Subject, outline: Boolean, fromHidden: Boolean): CutoutSaveResult
}

/** Lo stato del ritaglio (§1.5, `CutoutState`; l'aspetto lo decide G3). */
sealed interface CutoutState {
  data object Idle : CutoutState

  /** Il velo pulsante mentre il modello lavora; [progress] da 0 a 1 se noto. */
  data class Lifting(val progress: Float?) : CutoutState

  /** Il soggetto staccato; [outline] = "Contorno" (adesivo con bordo bianco). */
  data class Lifted(val cutout: ViewerCutout.Subject, val outline: Boolean) : CutoutState

  data object Saving : CutoutState

  data object Saved : CutoutState

  data object NoSubject : CutoutState

  data class Failed(val code: String) : CutoutState
}
