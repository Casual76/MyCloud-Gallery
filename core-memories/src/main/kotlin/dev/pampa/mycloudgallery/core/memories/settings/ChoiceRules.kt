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
   *
   * [undecided] (MO-1): lo stato di Persone non e' ancora noto (prima del montaggio di Persone): non si sa se Persone e' disponibile ne' chi e' la persona, e "non lo so"
   * non toglie il ricordo; valgono solo le scelte esplicite dell'utente ("Ricordi con le persone" spento, "Mostra meno" o "Mai" sulla persona o sul suo alias).
   */
  fun personStillShown(choices: MemoriesChoices, person: PersonId, peopleAvailable: Boolean, resolved: PersonId?, undecided: Boolean = false): Boolean {
    if (!choices.peopleMemories) return false
    if (choices.people[person] != null) return false
    if (undecided) return resolved == null || choices.people[resolved] == null
    if (!peopleAvailable || resolved == null) return false
    return choices.people[resolved] == null
  }
}
