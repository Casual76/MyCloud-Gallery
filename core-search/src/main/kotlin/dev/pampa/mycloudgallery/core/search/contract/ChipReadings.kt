package dev.pampa.mycloudgallery.core.search.contract

// LE LETTURE DI UN CHIP (Fase 8, P10, E-F36, come E-Q10): una parola che e' un nome di persona e anche una festa, un mese o un luogo ha un chip solo, quello con
// piu' media visibili, e propone l'altra lettura. Qui le due mosse dell'utente nel foglio del chip, pure e senza UI: scegliere l'alternativa di un chip "Persona"
// ("Cerca la festa", "Cerca il luogo") e tornare alla persona da un chip di data o di luogo ("Cerca la persona"). "Cerca come parola" e' il chip `Word`, che non
// ha ritorno (come per i luoghi).

object ChipReadings {

  /** La persona che la stessa parola di [chip] e' anche (E-F36), o null. */
  fun personOf(chip: SearchChip): PersonOption? = when (chip) {
    is SearchChip.DateRange -> chip.person
    is SearchChip.DateSet -> chip.person
    is SearchChip.Place -> chip.person
    else -> null
  }

  /** Il chip che prende il posto di [chip] se l'utente sceglie [alternative]: la festa, il mese o il luogo, che ricorda la persona per tornare indietro. */
  fun switchTo(chip: SearchChip.Person, alternative: ChipAlternative): SearchChip = when (val other = alternative.chip) {
    is SearchChip.DateRange -> other.copy(person = chip.option)
    is SearchChip.DateSet -> other.copy(person = chip.option)
    is SearchChip.Place -> other.copy(person = chip.option)
    else -> other
  }

  /** Il chip "Persona" che torna al posto di [chip] (una data o un luogo che ha una persona come altra lettura), con quella data o quel luogo come alternativa. */
  fun backToPerson(chip: SearchChip): SearchChip.Person? {
    val option = personOf(chip) ?: return null
    val plain: SearchChip = when (chip) {
      is SearchChip.DateRange -> chip.copy(person = null)
      is SearchChip.DateSet -> chip.copy(person = null)
      is SearchChip.Place -> chip.copy(person = null)
      else -> return null
    }
    val word = (chip as? SearchChip.Place)?.word ?: SearchText.normalize(option.label)
    return SearchChip.Person(option, listOf(ChipAlternative(plain), ChipAlternative(SearchChip.Word(word, exact = false))), word)
  }

  /** Il chip "Persona" di una scelta fatta altrove (un suggerimento): la persona, "Cerca come parola" come unica alternativa. */
  fun ofOption(option: PersonOption): SearchChip.Person {
    val word = SearchText.normalize(option.label)
    return SearchChip.Person(option, listOf(ChipAlternative(SearchChip.Word(word, exact = false))), word)
  }
}
