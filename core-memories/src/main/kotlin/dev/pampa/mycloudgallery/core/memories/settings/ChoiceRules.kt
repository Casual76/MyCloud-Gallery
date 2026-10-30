package dev.pampa.mycloudgallery.core.memories.settings

import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import dev.pampa.mycloudgallery.core.personal.contract.MemoryLessLevel

/**
 * Cosa vuol dire una scelta dell'utente per un media gia' sullo scaffale (docs/fase9-progetto.md D9, D11, E-MEM10, E-MEM11): le regole di
 * "Mai" e "Mostra meno" in un posto solo, per la pulizia dello scaffale (P6) e per chi lo mostra. Nessuna di queste funzioni legge il nome di
 * una persona: l'id soltanto.
 */
object ChoiceRules {

  /** Se fra [persons] (le persone con nome e visibili di un media) ce n'e' una "Mai nei ricordi": il media esce. */
  fun hasNever(choices: MemoriesChoices, persons: Set<PersonId>): Boolean = persons.any { choices.people[it] == MemoryLessLevel.NEVER }

  /** Se fra [persons] ce n'e' una "Mostra meno" o "Mai": il media non e' mai copertina (E-MEM11). */
  fun hasLess(choices: MemoriesChoices, persons: Set<PersonId>): Boolean = persons.any { choices.people[it] != null }

  /**
   * Se il ricordo di una persona ([person], l'id con cui e' stato pubblicato) si puo' ancora mostrare: Persone disponibile qui, "Ricordi con le
   * persone" acceso, la persona esiste ancora ([resolved], l'alias dopo un'unione, null se e' nascosta, dimenticata o non c'e' piu') e ne' lei ne'
   * il suo alias hanno "Mostra meno" o "Mai" (E-MEM10).
   */
  fun personStillShown(choices: MemoriesChoices, person: PersonId, peopleAvailable: Boolean, resolved: PersonId?): Boolean {
    if (!peopleAvailable || !choices.peopleMemories) return false
    if (resolved == null) return false
    return choices.people[person] == null && choices.people[resolved] == null
  }
}
