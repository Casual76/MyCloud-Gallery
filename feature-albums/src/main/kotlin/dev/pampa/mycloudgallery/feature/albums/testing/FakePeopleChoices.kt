package dev.pampa.mycloudgallery.feature.albums.testing

import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.albums.contract.PeopleChoices
import dev.pampa.mycloudgallery.feature.albums.contract.PeopleRuleInfo
import dev.pampa.mycloudgallery.feature.albums.contract.PersonChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fase 8 (P10): le persone finte per i test e le anteprime dell'editor degli album. [people] sono quelle che si scelgono (nomi inventati, le piu' frequenti prima);
 * [lost] sono id che la porta dice "non piu' riconosciuti" (dimenticati, E-F27); [hidden] id nascosti (valgono ancora, il nome non si mostra, E-F28). Con [available]
 * falso non si offre nessuna persona (E-F37). [failing] fa sbagliare la porta (l'editor non deve rompersi).
 */
class FakePeopleChoices(
  people: List<PersonChoice> = emptyList(),
  available: Boolean = true,
  var lost: Set<PersonId> = emptySet(),
  var hidden: Set<PersonId> = emptySet(),
) : PeopleChoices {
  private val all = people.toMutableList()
  private val on = MutableStateFlow(available)

  /** Se vero ogni chiamata alla porta fallisce. */
  var failing: Boolean = false

  override val available: StateFlow<Boolean> get() = on

  fun setAvailable(value: Boolean) {
    on.value = value
  }

  fun add(choice: PersonChoice) {
    all += choice
  }

  override suspend fun choices(): List<PersonChoice> {
    check(!failing) { "la porta delle persone non risponde" }
    return if (on.value) all.filter { it.id !in hidden } else emptyList()
  }

  override suspend fun inspect(ids: List<PersonId>): PeopleRuleInfo {
    check(!failing) { "la porta delle persone non risponde" }
    val names = all.filter { it.id in ids && it.id !in hidden && it.id !in lost }.associate { it.id to it.label }
    return PeopleRuleInfo(names, ids.filterTo(LinkedHashSet()) { it in lost })
  }
}
