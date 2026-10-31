package dev.pampa.mycloudgallery.core.widget.contract

import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.model.MediaId

// IL TOCCO SUL WIDGET (docs/fase10-progetto.md §1.2 "Tocco", D9, C17, C433). Contratto di P0; il `WidgetTaps` vero e' di P2 e passa la suite
// `WidgetTapContract`. Il `PendingIntent` lo costruisce solo feature-widget (`tap/`, P3), immutabile ed esplicito verso `MainActivity`, con
// l'azione fissa [WidgetTapUri.ACTION] e **solo due numeri** nell'URI opaco `mcg-widget:<id>/<cella>`: nessun id di media nell'intent.

/** I due numeri di un tocco: il widget e la cella. */
data class WidgetTapRequest(val appWidgetId: Int, val cell: Int) {
  init {
    require(appWidgetId in 1..WidgetTapUri.MAX_WIDGET_ID) { "Id di widget fuori dai limiti." }
    require(cell in 0 until WidgetTapUri.CELLS) { "Cella fuori dai limiti." }
  }
}

/** Dove porta un tocco, **dopo** il blocco dell'app (E-W7). */
sealed interface WidgetTarget {
  /** Il viewer su quel media, solo quello (niente "successiva"). */
  data class Viewer(val media: MediaId) : WidgetTarget {
    override fun toString(): String = "WidgetTarget.Viewer"
  }

  /** La pagina del ricordo (la copertina); se il ricordo non c'e' piu', :app apre la pagina Ricordi. */
  data class MemoryPage(val id: MemoryId) : WidgetTarget {
    override fun toString(): String = "WidgetTarget.MemoryPage"
  }

  /** La Libreria; con [notice] "Questa foto non c'e' piu'" (E-W8). Anche per ogni tocco non valido (widget non nostro, numeri fuori). */
  data class Library(val notice: Boolean) : WidgetTarget
}

/** La forma fissa del tocco (D9). */
object WidgetTapUri {
  /** L'azione che `MainActivity` riconosce nella pulizia dell'intent, come `OPEN_MEMORIES`. Nessun'altra azione del widget. */
  const val ACTION: String = "dev.pampa.mycloudgallery.action.OPEN_WIDGET"

  /** Lo schema dell'URI opaco (una richiesta distinta per cella). */
  const val SCHEME: String = "mcg-widget"

  /** Le celle possibili (la misura grande, D3). */
  const val CELLS: Int = WidgetRules.MAX_IMAGES

  /** Il piu' grande id di widget ammesso (gli id di Android sono interi positivi). */
  const val MAX_WIDGET_ID: Int = Int.MAX_VALUE

  /** L'URI di [request]: `mcg-widget:<id>/<cella>`, cifre decimali senza segno ne' zeri davanti. */
  fun format(request: WidgetTapRequest): String = "$SCHEME:${request.appWidgetId}/${request.cell}"
}

/**
 * Il tocco (D9, §2.2): [parse] accetta **solo** `mcg-widget:<int>/<int>` con interi nei limiti di [WidgetTapRequest] (cifre ASCII, niente
 * segno, spazi, zeri davanti, parti in piu', parametri o frammenti); tutto il resto e' null. [resolve] valida sul **registro locale**: un
 * widget che non e' fra [ownSlots] o una cella che il registro non ha = `Library(notice = false)`; il media non piu' visibile o nascosto adesso
 * (`VisibleMedia.visible`, `HiddenMask.hiddenAmong`) = `Library(notice = true)`; la copertina di un ricordo = `MemoryPage`; altrimenti il
 * `Viewer` sul media. Un estraneo che manda gli stessi numeri puo' solo aprire, dietro il blocco, una foto che il widget mostra gia'.
 */
interface WidgetTaps {
  fun parse(uri: String?): WidgetTapRequest?

  suspend fun resolve(
    request: WidgetTapRequest,
    ownSlots: Set<WidgetSlot>,
    ledger: WidgetLedger,
    visible: VisibleMedia,
    mask: HiddenMask,
  ): WidgetTarget
}
