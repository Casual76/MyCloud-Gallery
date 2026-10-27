package dev.pampa.mycloudgallery.feature.people.contract

import dev.pampa.mycloudgallery.core.imaging.contract.PlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.StableCode

// LE PORTE E I MESSAGGI DELLE SCHERMATE DELLE PERSONE (docs/fase8-progetto.md §1.5, §2.6, D38; riga P9 del §4.4). Aggiunte di P9 ai
// contratti di feature-people: le miniature delle foto di una persona (la parete di foto della sua pagina) e i messaggi che un'azione puo'
// lasciare sulla schermata. Le lega `:app` (P13); finche' non lo fa le schermate partono lo stesso con i null-object (nessuna miniatura:
// segnaposto). Mai nomi o id nei log (D37).

/**
 * Tutto cio' che serve a disegnare le foto di una persona oltre alla vista: il caricatore delle miniature della Libreria, i segnaposto e da dove
 * viene la sorgente di ogni cella. Lo stesso caricatore della Libreria (o uno finto nei test).
 */
class PeopleThumbnails(
  val loader: ThumbnailLoader,
  val placeholders: PlaceholderCache,
  val refs: PeopleRefs,
)

/** La sorgente di una miniatura: per una cella di una vista (come la Libreria). */
interface PeopleRefs {
  /** La sorgente della miniatura per la cella [index] dell'istantanea, o null se il media non ha una sorgente locale (segnaposto sfocato). */
  fun refAt(snapshot: TimelineSnapshot, index: Int): MediaRef?
}

/** Nessuna sorgente: ogni miniatura e' un segnaposto. */
object NoPeopleRefs : PeopleRefs {
  override fun refAt(snapshot: TimelineSnapshot, index: Int): MediaRef? = null
}

/** Cosa un'azione lascia sulla schermata quando non e' andata come previsto (o va detta): un codice stabile, il testo e' della schermata. */
enum class PeopleMessage(override val code: String) : StableCode {
  /** Persone non e' attiva su questo dispositivo (spenta, in attesa dell'avviso, esclusa, in cancellazione). */
  NOT_ACTIVE("not_active"),

  /** La persona o il volto non ci sono piu' (dimenticata, unita, sparita). */
  NOT_FOUND("not_found"),

  /** Il nome non e' ammesso (vuoto, oltre 64 caratteri, caratteri vietati). */
  INVALID_NAME("invalid_name"),

  /** Piu' di due persone con un nome da unire: si fa due alla volta (si sceglie quale nome tenere fra due). */
  TOO_MANY_NAMES("too_many_names"),

  /** La richiesta non ha senso (una sola persona da unire, tutti i volti da dividere). */
  INVALID("invalid"),
}
