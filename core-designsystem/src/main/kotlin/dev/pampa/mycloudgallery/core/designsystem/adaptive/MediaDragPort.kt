package dev.pampa.mycloudgallery.core.designsystem.adaptive

import android.content.ClipData
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap

// LA PORTA DEL TRASCINAMENTO VERSO UN'ALTRA APP PER LE SCHERMATE (Fase 10, P6, D20; docs/fase10-progetto.md §2.5). Come `NewWindowPort` (P4): le schermate
// (feature-*) non conoscono il provider dei documenti ne' i tetti; chiedono a questa porta i dati di un trascinamento e `:app` la lega a `MediaDragHandler` di
// `core-library` (l'adattatore sta in `feature-integrations/.../drag/`). Gli id viaggiano come stringhe (il valore del `MediaId`): il design system non conosce i
// moduli dei dati. Senza porta (test, anteprime, app non pronta) il gesto non fa niente: **fallire chiusi**.

/** Con che cosa si preme: il tocco parte con la pressione lunga, il mouse anche trascinando subito. */
enum class DragPointer { TOUCH, PEN, MOUSE }

/** Dove e come e' stata premuta la superficie che e' una sorgente: la posizione nelle coordinate di chi porta il modificatore. */
class DragTouch(val position: Offset, val pointer: DragPointer) {
  override fun toString(): String = "DragTouch($pointer)"
}

/**
 * Cio' che si trascina, deciso **al momento del tocco** (prima della pressione lunga): gli [ids] (valori di `MediaId`: la cella sotto il dito, o la selezione se la
 * cella ne fa parte; vuoto = niente da trascinare) e la [thumbnail] locale per l'ombra (se la schermata ce l'ha gia' in memoria; senza, un'ombra neutra).
 * Mai un nascosto: chi sceglie gli id e' una schermata che mostra solo la vista senza nascosti, e la porta ricontrolla comunque.
 */
class DragSubject(val ids: List<String>, val thumbnail: ImageBitmap? = null) {
  override fun toString(): String = "DragSubject(${ids.size})"
}

/**
 * Un trascinamento pronto: i dati per Android ([clip] con i soli URI del provider dei documenti, [flags] di sola lettura sul singolo URI) e come lasciarlo andare.
 * [discard] si chiama **solo** se il trascinamento non parte (il dito e' stato alzato, il sistema ha rifiutato): toglie le copie e revoca gli id subito. Se parte,
 * il resto e' dei tempi della porta (le copie vivono finche' l'app e' in primo piano piu' 10 minuti, e non oltre il prossimo avvio).
 */
class MediaDrag(val clip: ClipData, val flags: Int, val count: Int, val discard: () -> Unit) {
  init {
    require(count >= 1) { "Niente da trascinare." }
  }

  override fun toString(): String = "MediaDrag($count)"
}

/**
 * La porta con cui una schermata prepara un trascinamento. Si chiama **dopo** la pressione lunga e prima che il sistema cominci il gesto: sospende (anche a lungo,
 * per un media solo sul NAS che si copia senza posizione), e chi la implementa dice all'utente "Preparo..." se dura piu' di 300 ms e il motivo di un rifiuto.
 */
@Stable
interface MediaDragPort {
  /**
   * Prepara il trascinamento di [ids] (valori di `MediaId`) che parte da [origin] (il codice di `DragOrigin`: `library`, `album`, `search`, `memory`, `map_zone`,
   * `viewer`). Null: niente parte (la porta ha gia' detto perche'). Una cancellazione (il dito si alza prima) toglie cio' che era stato fatto.
   */
  suspend fun prepare(origin: String, ids: List<String>): MediaDrag?
}

/**
 * Chi comincia il gesto vero per Android (`View.startDragAndDrop`). Un'interfaccia perche' Robolectric non ha il sistema che porta il trascinamento: i test ne
 * danno uno che registra. Vero se il sistema ha accettato.
 */
fun interface MediaDragStarter {
  fun start(drag: MediaDrag, shadow: MediaDragShadow): Boolean
}

/** Cio' che serve a disegnare l'ombra: la [thumbnail] locale (se c'e'), quanti elementi si trascinano ([count]). */
class MediaDragShadow(val thumbnail: ImageBitmap?, val count: Int) {
  override fun toString(): String = "MediaDragShadow($count)"
}

/** La porta del processo: `:app` la installa una volta all'avvio (come `IntegrationPorts`). Una schermata legge prima quella della composizione, poi questa. */
object MediaDragPorts {
  @Volatile
  var installed: MediaDragPort? = null
    private set

  fun install(port: MediaDragPort?) {
    installed = port
  }

  fun reset() = install(null)
}

/** La porta del trascinamento nella composizione (test, anteprime); `null` = vale quella del processo ([MediaDragPorts]), e senza nemmeno quella niente si trascina. */
val LocalMediaDragPort = staticCompositionLocalOf<MediaDragPort?> { null }

/** Chi comincia il gesto, nella composizione (solo i test lo mettono): `null` = `View.startDragAndDrop` della finestra. */
val LocalMediaDragStarter = staticCompositionLocalOf<MediaDragStarter?> { null }
