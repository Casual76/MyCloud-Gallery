package dev.pampa.mycloudgallery.core.memories.shelf

import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPeople
import dev.pampa.mycloudgallery.core.memories.contract.MemoryRules
import dev.pampa.mycloudgallery.core.memories.contract.ShelfFile
import dev.pampa.mycloudgallery.core.memories.settings.ChoiceRules
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Lo scaffale ripulito e cosa e' costata la pulizia: elementi tolti da ricordi che restano ([trimmed]) e ricordi usciti ([removed]). */
internal class Pruned(val file: ShelfFile, val trimmed: Int, val removed: Int) {
  override fun toString(): String = "Pruned(${file.memories.size}, tolti=$trimmed, usciti=$removed)"
}

/**
 * La pulizia dello scaffale (docs/fase9-progetto.md §2.1 passo 7, D9, D10, D11, E-MEM5, E-MEM6, E-MEM10, R17): **solo togliere**. Mai
 * aggiungere un elemento, mai riordinare, mai cambiare il titolo; la copertina passa al primo elemento rimasto (che non sia di una persona
 * "Mostra meno") solo se la sua sparisce.
 *
 * Toglie dagli elementi di ogni ricordo:
 *  - i media nascosti o eliminati (non sono piu' visibili, o il filtro finale li dice nascosti);
 *  - i media di un periodo "Mai" (E-MEM12) e quelli con una persona "Mai" (D11).
 *
 * Fa uscire un ricordo se: ha piu' di 30 giorni ([MemoryRules.SHELF_DAYS]), la data di pubblicazione non e' una data, gli restano meno di 3
 * elementi, o e' di una persona che non si mostra piu' (nascosta, dimenticata, "Mostra meno" o "Mai", Persone spenta qui o "Ricordi con le
 * persone" spento, E-MEM10).
 *
 * **R17**: un guasto della lettura dei visibili o dei nascosti si propaga; lo scaffale resta com'era. Mai "non lo so" trattato come "e'
 * nascosto".
 */
internal class ShelfPruner(private val library: MemoryVisibility, private val people: MemoryPeople) {

  suspend fun clean(shelf: ShelfFile, choices: MemoriesChoices, today: LocalDate): Pruned {
    if (shelf.memories.isEmpty()) return Pruned(shelf, 0, 0)
    val snapshot = library.snapshot.value
    val everyMember = shelf.memories.flatMap { it.members }.distinct()
    val visibleNow = library.visibleOrThrow(everyMember)
    val hiddenNow = library.hiddenOrThrow(everyMember)
    val peopleOn = people.available.value
    val peopleOf = if (peopleOn) people.peopleIn(everyMember) else emptyMap()

    fun neverDay(id: MediaId): Boolean {
      if (snapshot == null || choices.neverDays.isEmpty()) return false
      val i = snapshot.indexOf(id)
      return i >= 0 && choices.neverOn(snapshot.localDayAt(i))
    }

    fun neverPerson(id: MediaId): Boolean = ChoiceRules.hasNever(choices, peopleOf[id].orEmpty())

    fun lessPerson(id: MediaId): Boolean = ChoiceRules.hasLess(choices, peopleOf[id].orEmpty())

    var trimmed = 0
    var removed = 0
    val kept = ArrayList<Memory>(shelf.memories.size)
    for (memory in shelf.memories) {
      val published = MemoryDays.dateOf(memory.publishedDay)
      if (published == null || ChronoUnit.DAYS.between(published, today) >= MemoryRules.SHELF_DAYS) {
        removed++
        continue
      }
      val person = memory.title.person
      if (memory.kind == MemoryKind.PERSON) {
        val shown = person != null && ChoiceRules.personStillShown(choices, person, peopleOn, if (peopleOn) people.resolve(person) else null)
        if (!shown) {
          removed++
          continue
        }
      }
      val members = memory.members.filter { it in visibleNow && it !in hiddenNow && !neverDay(it) && !neverPerson(it) }
      if (members.size < Memory.MIN_MEMBERS) {
        removed++
        continue
      }
      trimmed += memory.members.size - members.size
      val coverStays = memory.cover in members && !lessPerson(memory.cover)
      val cover = if (coverStays) memory.cover else members.firstOrNull { !lessPerson(it) } ?: members.first()
      kept += if (members.size == memory.members.size && cover == memory.cover) memory else memory.copy(members = members, cover = cover)
    }
    return Pruned(shelf.copy(memories = kept), trimmed, removed)
  }
}
