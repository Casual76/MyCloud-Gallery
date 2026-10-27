package dev.pampa.mycloudgallery.core.library.contract

import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.StateFlow

// Fase 7 (`docs/fase7-progetto.md` D22, §1.2 "core-library", §2.1 "Nascosti"): **i nascosti mai, su ogni superficie, da una sola
// fonte**. Firme di P0; le implementazioni sul catalogo sono di P9 (registro `HiddenSurfaces` e test di proprieta' in :app), il widget di
// prova (solo debug) sta sopra [WidgetFeed]. Ogni superficie che mostra media fuori dai Nascosti legge da [VisibleMedia] o filtra con
// [HiddenMask]: album (anche quelli in cui un altro ha messo un mio nascosto, E-H5), copertine (E-H6), "Suggerite", "Da condividere",
// viaggi ed eventi (anche la rilevazione, E-H7), Utility e Doppioni, "Aggiungi ad album", widget. Fallire chiusi: nel dubbio un media
// e' nascosto.

/**
 * La vista del catalogo **senza nascosti** (D22): ne' i media nascosti ne' i membri di un gruppo nascosto (il nascosto e' contagioso nei
 * gruppi, F5-D36). [snapshot] e' l'istantanea della timeline, che i nascosti non li ha gia' (E-H1) e ha una cella per gruppo.
 */
interface VisibleMedia {
  /** L'istantanea della Libreria (senza nascosti, una cella per gruppo); null finche' non c'e'. */
  val snapshot: StateFlow<TimelineSnapshot?>

  /**
   * Quali di [ids] si possono mostrare adesso: presenti nel catalogo, non nascosti, non membri di un gruppo nascosto. Un membro **non**
   * copertina di un gruppo visibile e' visibile (si mostra con il suo gruppo). Un id sconosciuto non e' visibile.
   */
  suspend fun visible(ids: Collection<MediaId>): Set<MediaId>

  /**
   * Come [visible], ma un errore di lettura **lancia** invece di rispondere "niente e' visibile" (R17 della revisione di fine Fase 7). Per chi
   * confronta il risultato con uno stato salvato e lo riscrive (gli album smart e i viaggi): un guasto del database non deve passare per una
   * Libreria vuota, che cancellerebbe le righe automatiche e le proposte. Chi mostra e basta (le schermate) resta su [visible], che fallisce
   * chiuso. Il default risponde come [visible]: le implementazioni che non leggono un database (i fake) non hanno un guasto da distinguere.
   */
  suspend fun visibleOrThrow(ids: Collection<MediaId>): Set<MediaId> = visible(ids)
}

/**
 * Il **filtro finale** (D22): quali di [ids] sono nascosti adesso (anche come membri di un gruppo nascosto). Lo chiama ogni superficie
 * subito prima di mostrare, anche se i suoi candidati vengono gia' da [VisibleMedia] (un media nascosto un attimo fa non deve
 * comparire). Un errore si tratta come "tutti nascosti". `:app` lo lega a `CatalogHidden`.
 */
fun interface HiddenMask {
  suspend fun hiddenAmong(ids: Collection<MediaId>): Set<MediaId>

  /** Come [hiddenAmong], ma un errore di lettura lancia invece di rendere nascosti tutti gli id (vedi [VisibleMedia.visibleOrThrow], R17). */
  suspend fun hiddenAmongOrThrow(ids: Collection<MediaId>): Set<MediaId> = hiddenAmong(ids)
}

/**
 * La sorgente del widget (D22): il **widget di prova** della Fase 7 (solo debug, `AppWidgetProvider` con `RemoteViews`) e il widget vero
 * della Fase 10 leggono solo da qui. [next] da' i prossimi [size] media da mostrare (a giro), **mai** un nascosto: chi la implementa
 * parte da [VisibleMedia] e filtra con [HiddenMask]. Lista vuota se non c'e' niente da mostrare.
 */
interface WidgetFeed {
  suspend fun next(size: Int): List<MediaId>

  companion object {
    /** Quanti media al massimo chiede un widget alla volta. */
    const val MAX_SIZE: Int = 16
  }
}
