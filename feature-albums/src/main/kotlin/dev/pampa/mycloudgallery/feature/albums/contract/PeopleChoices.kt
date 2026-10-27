package dev.pampa.mycloudgallery.feature.albums.contract

import dev.pampa.mycloudgallery.core.model.PersonId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// LE PERSONE CHE SI SCELGONO PER UN ALBUM SMART (Fase 8, P10; docs/fase8-progetto.md §2.7 "Album smart", D27, E-F23, E-F27, E-F28, E-F37). feature-albums non vede core-faces
// ne' core-search (Phase7ArchitectureTest): la lista delle persone con un nome e il loro stato la da' questa porta, che `:app` (P13) lega sopra `PeopleIndex` (i nomi, gli
// alias, le persone nascoste) e `PeopleVisibility` (solo persone con un media visibile). Opzionale per Dagger: senza, l'editor non offre le persone e non dice niente.

/** Una persona che si puo' scegliere: l'id, il nome (dato dell'utente, mai nei log) e quante foto ha. */
class PersonChoice(val id: PersonId, val label: String, val photos: Int) {
  /** Mai il nome ne' l'id nei log (D37). */
  override fun toString(): String = "PersonChoice($photos)"
}

/**
 * Cosa si sa delle persone di una regola: [names] il nome di quelle riconosciute (seguendo gli alias), [lost] quelle che non lo sono piu' (dimenticate o sconosciute,
 * E-F27: "Una persona della regola non e' piu' riconosciuta"). Una persona nascosta non e' in nessuna delle due: vale ancora (E-F28) e non si mostra il nome.
 */
class PeopleRuleInfo(val names: Map<PersonId, String>, val lost: Set<PersonId>) {
  override fun toString(): String = "PeopleRuleInfo(${names.size}, ${lost.size})"

  companion object {
    val EMPTY = PeopleRuleInfo(emptyMap(), emptySet())
  }
}

interface PeopleChoices {
  /** Falso dove i volti non ci sono (Persone spenta, in attesa dell'avviso, dispositivo escluso: E-F37): l'editor e la pagina dell'album lo dicono. */
  val available: StateFlow<Boolean>

  /** Le persone con un nome, non nascoste, con almeno un media visibile; le piu' frequenti prima. Vuota se [available] e' falso. Mai una persona nascosta. */
  suspend fun choices(): List<PersonChoice>

  /** Lo stato delle persone di una regola ([ids]): i nomi di quelle riconosciute e quelle perse. */
  suspend fun inspect(ids: List<PersonId>): PeopleRuleInfo
}

/** Nessuna persona: l'editor non offre la scelta e dice che le persone non sono attive. Fallisce chiuso. */
object NoPeopleChoices : PeopleChoices {
  override val available: StateFlow<Boolean> = MutableStateFlow(false)

  override suspend fun choices(): List<PersonChoice> = emptyList()

  override suspend fun inspect(ids: List<PersonId>): PeopleRuleInfo = PeopleRuleInfo.EMPTY
}
