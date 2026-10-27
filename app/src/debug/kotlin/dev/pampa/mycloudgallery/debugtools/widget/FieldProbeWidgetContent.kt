package dev.pampa.mycloudgallery.debugtools.widget

import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.WidgetFeed
import dev.pampa.mycloudgallery.core.library.text.GalleryDates
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.time.LocalDate
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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

  /** Quanto si aspetta l'istantanea della Libreria dentro il ricevitore (Android concede poche decine di secondi, e il widget non deve tenerlo in vita). */
  const val SNAPSHOT_WAIT_MS: Long = 8_000L

  /** Quanto si continua ad aspettarla fuori dal ricevitore, a processo freddo, prima di rinunciare fino al giro dopo. */
  const val SNAPSHOT_LATE_WAIT_MS: Long = 120_000L

  /**
   * NA-2 (revisione di Fase 7): l'istantanea della Libreria, **aspettata** fino a [waitMs]. A processo freddo (il sistema dei widget sveglia l'app solo per
   * ridisegnare) l'istantanea e' ancora null: prima [cells] rispondeva "niente" e `updateAppWidget` sovrascriveva il widget con celle vuote fino al giro dopo,
   * trenta minuti, e la prova F73 passava "a vuoto". Null dopo l'attesa vuol dire che non c'e' (accesso alle sole foto selezionate, Libreria non letta): il
   * widget resta senza celle, mai con un elemento che non si e' potuto filtrare.
   */
  suspend fun awaitSnapshot(snapshot: StateFlow<TimelineSnapshot?>, waitMs: Long = SNAPSHOT_WAIT_MS): TimelineSnapshot? =
    snapshot.value ?: withTimeoutOrNull(waitMs) { snapshot.filterNotNull().first() }

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
