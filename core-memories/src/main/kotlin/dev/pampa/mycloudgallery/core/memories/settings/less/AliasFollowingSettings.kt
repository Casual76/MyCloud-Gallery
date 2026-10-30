package dev.pampa.mycloudgallery.core.memories.settings.less

import dev.pampa.mycloudgallery.core.memories.contract.MemoryPeople
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.MemoryLessLevel

/**
 * "Mostra meno" e "Mai" che **seguono l'alias** (docs/fase9-progetto.md §2.3, D11, E-MEM10): una persona unita a un'altra ha un id nuovo, e
 * una scelta fatta sull'id vecchio (il ricordo era stato pubblicato con quello) non deve restare su un id che non compare piu' nelle foto.
 * Prende la porta delle scelte di core-personal e la usa cosi' com'e', tranne in una cosa: scrivendo un livello per una persona
 * ([setLess]) la scelta va sull'id **di adesso** (`MemoryPeople.resolve`) e la voce dell'id vecchio, se c'era, diventa una lapide.
 *
 * Quello che gia' c'e' scritto sull'id vecchio lo porta sull'id nuovo `PeopleLessJanitor.sweep` (stesso criterio: [LessLevels.stronger]).
 * Non legge e non scrive nomi: gli id sono quelli casuali della Fase 8. Il resto delle scelte (visti, scartati, periodi, "Ricordi con le
 * persone") passa invariato.
 */
class AliasFollowingSettings(
  private val inner: MemoriesPersonalSettings,
  private val people: MemoryPeople,
) : MemoriesPersonalSettings by inner {

  override suspend fun setLess(person: PersonId, level: MemoryLessLevel?) {
    // Togliere una scelta e' sempre sull'id che l'elenco mostra; con Persone non disponibile qui non si sa chi e' chi.
    if (level == null || !people.available.value) return inner.setLess(person, level)
    val resolved = people.resolve(person)
    if (resolved == null || resolved == person) return inner.setLess(person, level)
    // Prima la voce nuova, poi la lapide della vecchia: a meta' restano due scelte uguali, mai nessuna.
    inner.setLess(resolved, level)
    inner.setLess(person, null)
  }

  override fun toString(): String = "AliasFollowingSettings"
}

/** Quale scelta vale quando due id si uniscono in uno (D11): "Mai" e' piu' forte di "Mostra meno". */
object LessLevels {
  fun stronger(a: MemoryLessLevel?, b: MemoryLessLevel?): MemoryLessLevel? = when {
    a == null -> b
    b == null -> a
    a == MemoryLessLevel.NEVER || b == MemoryLessLevel.NEVER -> MemoryLessLevel.NEVER
    else -> MemoryLessLevel.LESS
  }
}
