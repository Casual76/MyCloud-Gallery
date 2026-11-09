package dev.pampa.mycloudgallery.feature.viewer

import dev.pampa.mycloudgallery.core.library.contract.TimelineView

// L'ORDINE DEL PAGER (grafica beta, G3, B5). L'utente: "le foto e i video piu' vecchi si dovrebbero trovare a sinistra di quello messo fullscreen [...] nessuna galleria che
// conosca lo fa [al contrario]". Le posizioni della vista crescono verso le foto **piu' vecchie** (la timeline: 0 e' il piu' recente), e il `HorizontalPager` mette la pagina 0 a
// sinistra: le vecchie finivano a destra. Qui si decide **una volta, all'apertura**, come disporre le pagine perche' la foto a sinistra sia quella che viene prima nel tempo.
// Niente di questo cambia l'indice, le chiavi, gli id salvati o il ritorno alla cella: cambia solo da che parte dello schermo sta ogni pagina.

/** Come le posizioni di una vista seguono il tempo: da questo dipende da che parte del pager stanno le foto piu' vecchie. */
internal enum class ViewerOrder {
  /** La posizione 0 e' la piu' recente (la Libreria, un album "Dal piu' recente", i risultati "Recenti"): le piu' vecchie hanno la posizione piu' alta. */
  NEWEST_FIRST,

  /** La posizione 0 e' la piu' vecchia (un album "Dal piu' vecchio", un ricordo in ordine di racconto): le piu' vecchie hanno la posizione piu' bassa. */
  OLDEST_FIRST,

  /** Nessun verso del tempo (pertinenza di una ricerca, ordine a mano di un album): non c'e' un "piu' vecchio" da mettere a sinistra, si segue l'ordine della lista. */
  UNORDERED,
}

internal object ViewerPaging {

  /** Quanti punti della vista si guardano per capire l'ordine: il costo e' costante, anche con centomila elementi. */
  private const val Samples = 512

  /**
   * L'ordine del tempo della vista [view], guardando [Samples] punti equidistanti (e l'ultimo). Una lista che scende (o resta uguale) in **tutti** i punti e' [ViewerOrder.NEWEST_FIRST];
   * una che sale, [ViewerOrder.OLDEST_FIRST]; ogni altra, [ViewerOrder.UNORDERED]. Una vista vuota, di un elemento o con un solo istante e' [ViewerOrder.NEWEST_FIRST]: e' la timeline,
   * e li' non cambia niente per nessuno.
   *
   * Non e' una prova: una lista in ordine a mano che per caso fosse ordinata nei 512 punti sarebbe letta come ordinata. Nel peggiore dei casi le foto vecchie stanno dalla parte
   * sbagliata in una lista che nessun ordine del tempo descrive: nessun dato in pericolo.
   */
  fun orderOf(view: TimelineView): ViewerOrder {
    val size = view.size
    if (size < 2) return ViewerOrder.NEWEST_FIRST
    val step = ((size - 1) / Samples).coerceAtLeast(1)
    var falling = true
    var rising = true
    var sawChange = false
    var previous = timeAt(view, 0)
    var at = step
    while (true) {
      val index = if (at >= size - 1) size - 1 else at
      val time = timeAt(view, index)
      if (time < previous) {
        rising = false
        sawChange = true
      } else if (time > previous) {
        falling = false
        sawChange = true
      }
      if (!falling && !rising) return ViewerOrder.UNORDERED
      previous = time
      if (index == size - 1) break
      at += step
    }
    return when {
      !sawChange -> ViewerOrder.NEWEST_FIRST
      falling -> ViewerOrder.NEWEST_FIRST
      else -> ViewerOrder.OLDEST_FIRST
    }
  }

  private fun timeAt(view: TimelineView, viewIndex: Int): Long = view.snapshot.sortTimeAt(view.snapshotIndex(viewIndex))

  /**
   * Se il pager va **rovesciato** (`reverseLayout`) perche' le foto piu' vecchie stiano a sinistra. Il pager di Compose mette la pagina 0 a sinistra in una finestra da sinistra a
   * destra e a destra in una da destra a sinistra; la **sinistra dello schermo** e' quella che l'utente vede, quindi si tiene conto della direzione ([rtl]).
   *
   * - [ViewerOrder.NEWEST_FIRST]: le vecchie hanno le posizioni alte, che devono stare a sinistra: rovesciato in una finestra da sinistra a destra.
   * - [ViewerOrder.OLDEST_FIRST]: le vecchie hanno le posizioni basse, che a sinistra ci stanno gia': mai rovesciato (rovesciato solo da destra a sinistra).
   * - [ViewerOrder.UNORDERED]: l'ordine della lista, con la posizione 0 dalla parte da cui si comincia a leggere.
   */
  fun reverseLayout(order: ViewerOrder, rtl: Boolean): Boolean = when (order) {
    ViewerOrder.NEWEST_FIRST -> !rtl
    ViewerOrder.OLDEST_FIRST, ViewerOrder.UNORDERED -> rtl
  }

  /**
   * Di quante pagine si sposta il pager per andare alla pagina **a sinistra** della corrente: -1 o +1. Serve ai tasti: la freccia sinistra va a sinistra, sempre (e la destra a destra).
   * Con il pager rovesciato in una finestra da sinistra a destra la pagina a sinistra e' la +1, la piu' vecchia. Le azioni di TalkBack no: "successiva" e "precedente" seguono la
   * numerazione, come "5 di 50".
   */
  fun leftPageDelta(reversed: Boolean, rtl: Boolean): Int = if (reversed == rtl) -1 else 1
}
