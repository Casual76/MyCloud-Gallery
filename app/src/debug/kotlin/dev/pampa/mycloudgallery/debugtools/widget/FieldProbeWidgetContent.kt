package dev.pampa.mycloudgallery.debugtools.widget

import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.WidgetFeed
import dev.pampa.mycloudgallery.core.library.text.GalleryDates
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.time.LocalDate

// SOLO build debug (Fase 7, P9, D22, L65): il contenuto del widget di prova dei Nascosti. Tutto cio' che il widget mostra viene da `WidgetFeed`,
// il contratto che usera' il widget vero della Fase 10: qui non si pesca mai da nessun'altra parte, quindi il widget non puo' mostrare un nascosto
// se la sorgente non lo fa. Senza Android: lo provano i test JVM.

/** Una cella del widget: cosa e' (per il lettore di schermo, mai un nome di file ne' un id) e, se c'e', dove stanno i pixel. */
internal class FieldProbeWidgetCell(
  val id: MediaId,
  val kind: MediaKind,
  val day: LocalDate,
  val ref: MediaRef?,
) {
  /** "Foto, 12 marzo 2024": la descrizione che legge TalkBack (e UI Automator nella prova F73). */
  fun description(photo: String, video: String): String =
    (if (kind == MediaKind.VIDEO) video else photo).replace("%1\$s", GalleryDates.date(day))

  override fun toString(): String = "FieldProbeWidgetCell($kind)"
}

internal object FieldProbeWidgetContent {

  /** Le celle del widget. */
  const val CELLS: Int = 4

  /**
   * Le prossime [CELLS] celle da [feed]. Un id che l'istantanea non conosce (cambiata nel frattempo) non si descrive e quindi non si mostra:
   * nel dubbio, niente. [refOf] dice dove stanno i pixel di un id.
   */
  suspend fun cells(feed: WidgetFeed, snapshot: TimelineSnapshot?, refOf: (MediaId) -> MediaRef?): List<FieldProbeWidgetCell> {
    snapshot ?: return emptyList()
    val picked = feed.next(CELLS)
    val out = ArrayList<FieldProbeWidgetCell>(picked.size)
    for (id in picked) {
      val at = snapshot.indexOf(id)
      if (at < 0) continue
      out += FieldProbeWidgetCell(id, snapshot.kindAt(at), dayOf(snapshot.localDayAt(at)), refOf(id))
    }
    return out
  }

  /** `aaaammgg` come data; un valore che non lo e' (0, giorno inesistente) cade su un giorno fisso, mai su un errore. */
  internal fun dayOf(localDay: Int): LocalDate =
    runCatching { LocalDate.of(localDay / 10_000, localDay / 100 % 100, localDay % 100) }.getOrDefault(LocalDate.of(1970, 1, 1))
}
