package dev.pampa.mycloudgallery.core.library.contract

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.StateFlow

// Il contratto fra la Libreria e il viewer (docs/fase3-progetto.md D12, §2.5). Il viewer e' una sovrapposizione nella
// STESSA schermata, non una rotta: la griglia fornisce la geometria e il "porta in vista", il viewer si ritaglia
// dalla miniatura. Nessun tipo di Compose in questo contratto: `PixelRect` e' nostro (core-library non conosce Compose UI).

/**
 * Un rettangolo in pixel nelle coordinate **della finestra** (le stesse della geometria del viewer). Float perche' la
 * miniatura sotto un pinch o uno scorrimento sta fra due pixel.
 */
@Immutable
data class PixelRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {

  val width: Float get() = right - left
  val height: Float get() = bottom - top
  val centerX: Float get() = (left + right) / 2f
  val centerY: Float get() = (top + bottom) / 2f
  val isEmpty: Boolean get() = right <= left || bottom <= top

  /** Se [other] sta tutto dentro questo rettangolo. */
  fun contains(other: PixelRect): Boolean =
    other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
}

/**
 * Gli elementi che il viewer scorre: la vista della Libreria (stesso filtro) e come arrivare ai byte di ognuno.
 * [view] cambia quando l'indice cambia (modifica esterna, cancellazione): il viewer si adegua (E-V6).
 */
@Stable
interface ViewerItems {

  val view: StateFlow<TimelineView>

  /** Dove stanno i byte di [id]: per la miniatura di partenza, la decodifica, la condivisione. */
  fun ref(id: MediaId): MediaRef

  /**
   * Come [ref], ma null se adesso non c'e' una copia leggibile (P5: un `_ID` del MediaStore di un'altra versione non si
   * usa finche' la scansione non lo rilegge, E-P22; un elemento appena uscito dalla vista). La griglia e il viewer
   * mostrano il segnaposto invece di fallire. Aggiunta compatibile: il default delega a [ref].
   */
  fun refOrNull(id: MediaId): MediaRef? = try {
    ref(id)
  } catch (e: NoSuchElementException) {
    null
  }
}

/**
 * La geometria della miniatura di partenza e il "porta in vista" per il ritorno. La griglia lo implementa; il viewer
 * lo usa per aprirsi dalla cella e per chiudersi nella cella dell'elemento che sta mostrando (che puo' essere un altro).
 */
@Stable
interface ViewerAnchor {

  /** I bordi della cella di [id] nella finestra, o null se non e' sullo schermo adesso. */
  fun boundsOf(id: MediaId): PixelRect?

  /**
   * Porta in vista la cella di [id] (al centro se era fuori schermo), poi ritorna: la chiusura del viewer va ai suoi
   * bordi. Sospende finche' lo scorrimento non e' assestato.
   */
  suspend fun reveal(id: MediaId)

  /** La chiave della miniatura di [id], per partire dalla stessa bitmap che la cella ha (`ThumbnailLoader.peek`). */
  fun thumbKey(id: MediaId): ThumbKey
}

/**
 * La richiesta di aprire il viewer, ricevuta dall'host in `:app` ([onOpenViewer] della Libreria).
 *
 * @property start l'elemento da cui si parte.
 * @property secure vero per i Nascosti: vale `SecureScreen` (screenshot bloccati, recenti oscurati) per tutto il
 *   viewer, E-S5.
 */
@Immutable
data class ViewerRequest(
  val items: ViewerItems,
  val start: MediaId,
  val anchor: ViewerAnchor,
  val secure: Boolean,
)
