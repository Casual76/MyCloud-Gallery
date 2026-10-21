package dev.pampa.mycloudgallery.core.imaging.contract

import android.graphics.ColorSpace
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import dev.pampa.mycloudgallery.core.model.MediaRef
import kotlinx.coroutines.flow.Flow

// Le immagini del viewer (docs/fase3-progetto.md §2.5). Contratti di P0; la decodifica per lo schermo e' di P6.

/** Perche' i byte di un media non sono raggiungibili adesso. Pronto per la Fase 4 (E-V1, E-V2), provato con sorgenti finte. */
enum class UnavailableReason {
  /** Il NAS non risponde (Fase 4): il viewer mostra la miniatura che ha, con la spiegazione. */
  NAS_UNREACHABLE,

  /** Il percorso verso il NAS non e' consentito qui (rete sconosciuta, VPN non di casa). */
  NOT_ALLOWED_HERE,

  /**
   * D-15 delle prove sul campo: fuori casa con la VPN di casa spenta. Il NAS non si raggiunge proprio, nemmeno con i dati mobili: il viewer non
   * propone di "usare i dati mobili" (non servirebbero a niente) e dice che serve la VPN.
   */
  VPN_OFF_AWAY,

  /** D-15: nessuna rete. Il NAS si raggiungera' quando tornera'. */
  OFFLINE,

  /** Servono i dati mobili e l'utente non ha ancora confermato: il viewer mostra la dimensione (E-V2). */
  NEEDS_MOBILE_DATA_CONFIRMATION,

  /** Il volume non e' montato (microSD tolta, E-P6). */
  VOLUME_UNMOUNTED,
}

/** Perche' un'immagine non si e' decodificata. Codici stabili: finiscono nella Diagnostica, mai il file. */
enum class ImageFailure {
  DECODE_FAILED,
  OUT_OF_MEMORY,
  UNSUPPORTED_FORMAT,
  NOT_FOUND,

  /** Fase 4 (P8): il telefono non ha posto per il temporaneo dell'originale letto dal NAS. Aggiunta compatibile. */
  STORAGE_FULL,
}

/**
 * La sequenza di stati con cui il viewer mostra un'immagine, **senza salti** (E-V1): prima l'anteprima (la
 * miniatura che la griglia ha gia'), poi la versione nitida alla taglia dello schermo, poi, allo zoom, i tasselli
 * (`ZoomSpec`). Anteprima e versione nitida hanno la stessa geometria, cosi' la sostituzione non sposta niente.
 */
@Immutable
sealed interface ViewerImageState {

  /** L'anteprima, subito: la bitmap della miniatura. */
  data class Preview(val bitmap: ImageBitmap) : ViewerImageState

  /**
   * L'immagine alla taglia dello schermo, gia' dritta (8 orientamenti, E-V3 ed E-M15).
   *
   * @property hdr vero per Ultra HDR con gain map: il viewer passa la finestra a `COLOR_MODE_HDR` (API 34+, E-V4).
   * @property colorSpace lo spazio colore dell'originale (Display P3 si conserva), se noto.
   */
  data class Sharp(val bitmap: ImageBitmap, val hdr: Boolean, val colorSpace: ColorSpace?) : ViewerImageState

  /** In caricamento, con l'avanzamento se noto (dal NAS, Fase 4). */
  data class Loading(val progress: Float?) : ViewerImageState

  /** I byte non sono raggiungibili adesso; [sizeBytes] per lo stato "dati mobili" (E-V2). */
  data class Unavailable(val reason: UnavailableReason, val sizeBytes: Long? = null) : ViewerImageState

  /** Non si decodifica: il viewer lo dice con una frase, non si blocca (E-M10). */
  data class Failed(val code: ImageFailure) : ViewerImageState
}

/**
 * Le dimensioni dell'originale per lo zoom a tasselli. Un file da 200 MP non si decodifica mai intero: si campiona
 * per lo schermo e, allo zoom, si leggono i tasselli (telephoto, avvolto in `ZoomableMedia`).
 *
 * @property tiled se i tasselli sono possibili per questo formato (JPEG, PNG, WebP e HEIC dove
 *   `BitmapRegionDecoder` li regge); altrimenti lo zoom usa la decodifica campionata (DNG, E-M1-M4).
 */
@Immutable
data class ZoomSpec(
  val width: Int,
  val height: Int,
  val tiled: Boolean,
) {
  init {
    require(width > 0 && height > 0) { "Dimensioni non valide." }
  }
}

/** I formati animati (E-M11). Nella griglia restano fermi; solo il viewer li anima. */
enum class AnimatedKind { GIF, WEBP }

/** Un'immagine animata: il viewer la mostra con `AnimatedImageDrawable`. */
@Immutable
data class AnimatedSpec(val kind: AnimatedKind, val width: Int, val height: Int)

/**
 * Le immagini del viewer. [state] emette la sequenza [ViewerImageState] per [ref] alla taglia dello schermo
 * [screen]; il flusso si annulla quando la pagina esce dalla finestra del pager (E-V5: al massimo tre decodifiche in
 * volo). Una sorgente lenta o irraggiungibile e' un caso normale, non un'eccezione.
 */
interface ViewerImageSource {

  fun state(ref: MediaRef, screen: IntSize): Flow<ViewerImageState>

  /** Le dimensioni per lo zoom a tasselli, o null se non si puo' ingrandire oltre l'immagine alla taglia dello schermo. */
  fun zoomSpec(ref: MediaRef): ZoomSpec?

  /** Se [ref] e' un'immagine animata (GIF, WebP), come si anima; null altrimenti. */
  fun animated(ref: MediaRef): AnimatedSpec?
}
