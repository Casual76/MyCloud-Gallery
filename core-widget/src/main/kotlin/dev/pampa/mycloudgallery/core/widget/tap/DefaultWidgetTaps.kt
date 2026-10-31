package dev.pampa.mycloudgallery.core.widget.tap

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.widget.contract.WidgetLedger
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTapRequest
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTapUri
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTaps
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTarget
import kotlin.coroutines.cancellation.CancellationException

/**
 * Il tocco sul widget (D9, C17, C433; §2.2). L'URI e' **opaco e nostro**: `mcg-widget:<id>/<cella>`, due interi decimali ASCII e nient'altro.
 * Un intent costruito da un'altra app con la stessa azione passa da qui: se non e' esattamente questa forma, [parse] risponde null e
 * `MainActivity` apre la Libreria; se lo e', [resolve] controlla i numeri sul **registro locale** e al peggio apre, dietro il blocco dell'app, una
 * foto che il widget mostra gia' sulla Home.
 *
 * [parse] legge **carattere per carattere**: niente espressioni regolari (nessuna ambiguita' di `\d` su cifre non ASCII, nessun backtracking) e
 * niente `toInt` permissivo (che accetta il segno e le cifre di altre scritture). Accetta solo cifre `0-9`, almeno una, **nessuno zero
 * davanti** (tranne "0" solo), l'id fra 1 e [WidgetTapUri.MAX_WIDGET_ID], la cella fra 0 e [WidgetTapUri.CELLS] - 1, e nessun carattere
 * in piu': niente spazi, segni, parametri, frammenti, barre in piu', maiuscole nello schema.
 *
 * [resolve] **fallisce chiuso**: un errore di lettura del registro o della visibilita' non apre mai un media, apre la Libreria senza avviso.
 */
class DefaultWidgetTaps : WidgetTaps {

  override fun parse(uri: String?): WidgetTapRequest? {
    if (uri == null || uri.length > MAX_URI_LENGTH) return null
    val prefix = WidgetTapUri.SCHEME
    if (uri.length <= prefix.length + 1 || !uri.startsWith(prefix) || uri[prefix.length] != ':') return null
    var i = prefix.length + 1
    val idStart = i
    while (i < uri.length && uri[i] in '0'..'9') i++
    val idDigits = i - idStart
    if (i >= uri.length || uri[i] != '/' || !validDigits(uri, idStart, idDigits)) return null
    val id = toInt(uri, idStart, idDigits) ?: return null
    i++ // la barra
    val cellStart = i
    while (i < uri.length && uri[i] in '0'..'9') i++
    // Dopo le cifre della cella non deve esserci niente: ne' un'altra barra, ne' spazi, ne' parametri.
    if (i != uri.length || !validDigits(uri, cellStart, i - cellStart)) return null
    val cell = toInt(uri, cellStart, i - cellStart) ?: return null
    if (id < 1 || cell >= WidgetTapUri.CELLS) return null
    return WidgetTapRequest(id, cell)
  }

  /** Almeno una cifra, al piu' [MAX_DIGITS], e nessuno zero davanti a un numero di piu' cifre. */
  private fun validDigits(text: String, start: Int, count: Int): Boolean =
    count in 1..MAX_DIGITS && !(count > 1 && text[start] == '0')

  /** Il numero delle cifre gia' controllate, o null se non sta in un `Int`. */
  private fun toInt(text: String, start: Int, count: Int): Int? {
    var value = 0L
    for (k in start until start + count) {
      value = value * 10 + (text[k] - '0')
      if (value > Int.MAX_VALUE) return null
    }
    return value.toInt()
  }

  override suspend fun resolve(
    request: WidgetTapRequest,
    ownSlots: Set<WidgetSlot>,
    ledger: WidgetLedger,
    visible: VisibleMedia,
    mask: HiddenMask,
  ): WidgetTarget {
    val slot = WidgetSlot(request.appWidgetId)
    if (slot !in ownSlots) return WidgetTarget.Library(notice = false)
    return try {
      val ref = ledger.cell(slot, request.cell) ?: return WidgetTarget.Library(notice = false)
      val probe = listOf(ref.media)
      // Letture che lanciano: un guasto non deve passare per "e' ancora li'" (E-W8) e aprire un media nascosto nel frattempo.
      val stillThere = ref.media in visible.visibleOrThrow(probe) && ref.media !in mask.hiddenAmongOrThrow(probe)
      if (!stillThere) return WidgetTarget.Library(notice = true)
      val memory = ref.memory
      if (memory != null) WidgetTarget.MemoryPage(memory) else WidgetTarget.Viewer(ref.media)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "Tocco sul widget non risolto (${e.javaClass.simpleName}): si apre la Libreria." }
      WidgetTarget.Library(notice = false)
    }
  }

  private companion object {
    const val TAG = "WidgetTap"

    /** `mcg-widget:` + 10 cifre + `/` + 1 cifra sono 23 caratteri: oltre 32 non puo' essere nostro (si scarta prima di leggerlo). */
    const val MAX_URI_LENGTH = 32

    /** Un `Int` ha al piu' 10 cifre. */
    const val MAX_DIGITS = 10
  }
}

/**
 * Il tocco come punto d'ingresso statico, senza grafo: `MainActivity` lo chiama nella pulizia dell'intent (P3) per `parse`; `resolve`
 * serve il grafo del widget (P13) con il registro, la visibilita' e la maschera.
 */
object WidgetTap : WidgetTaps by DefaultWidgetTaps()
