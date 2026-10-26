package dev.pampa.mycloudgallery.core.search.contract

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlinx.coroutines.flow.StateFlow

// LA RICERCA PER PERSONA (docs/fase8-progetto.md §1.2 "core-search", §2.7, D26, D28, E-F36). Firme di P0; l'analizzatore (dizionario dei
// nomi, congiunzioni, ambiguita'), i candidati, i suggerimenti e la cronologia sono di P10. **core-search non vede core-faces** (F6-D33,
// Phase8ArchitectureTest): le persone arrivano dalla porta [PeopleLookup], che :app lega sopra `PeopleIndex` di core-faces e le chiavi
// di contenuto delle celle. Si cerca per **persone con un nome**: nessuna ricerca libera "trova questo volto" (D26, minaccia "riconoscimento
// usato per cercare chiunque"). Mai nomi o id nei log (D37).

/**
 * Una scelta di persone (D26): gli id ([ids], gia' risolti gli alias), "una qualunque" o "tutte" ([mode]) e l'etichetta gia' in italiano
 * ("Marco", "Marco e Anna", "Marco o Anna"). Al piu' [MAX_PEOPLE] persone, senza ripetizioni.
 */
data class PersonOption(val ids: List<PersonId>, val mode: PeopleMode, val label: String) {
  init {
    require(ids.isNotEmpty() && ids.size <= MAX_PEOPLE) { "Da 1 a $MAX_PEOPLE persone." }
    require(ids.toSet().size == ids.size) { "Persone ripetute." }
  }

  /** Mai nomi ne' id (D37). */
  override fun toString(): String = "PersonOption(${ids.size}, ${mode.code})"

  companion object {
    /** Come la regola degli album smart (§2.7: al piu' 32 persone). */
    const val MAX_PEOPLE: Int = 32
  }
}

/**
 * Un'altra lettura della parola di un chip "Persona" (E-F36, come E-Q10): il chip che lo sostituirebbe se l'utente la tocca ("Cerca la
 * festa" = un [SearchChip.DateRange] o [SearchChip.DateSet], "Cerca il luogo" = un [SearchChip.Place], "Cerca come parola" = un
 * [SearchChip.Word]). Mai un'altra persona: gli omonimi stanno nel dizionario.
 */
data class ChipAlternative(val chip: SearchChip) {
  init {
    require(chip !is SearchChip.Person) { "Un'alternativa non e' un'altra persona." }
  }

  val label: String get() = chip.label

  override fun toString(): String = "ChipAlternative(${chip::class.simpleName})"
}

/**
 * Un nome del dizionario (D26): il nome **normalizzato** come i luoghi ([PeopleDictionary.normalize]: minuscolo, senza accenti, apostrofi
 * uniformi; anche di piu' parole), la persona, l'etichetta da mostrare e quanti media **visibili** ha (fra omonimi vince il piu' frequente).
 * Solo persone con un nome, non nascoste ne' dimenticate, con almeno un media visibile (P10 lo prova sulle sonde dei Nascosti).
 */
data class PersonEntry(val name: String, val id: PersonId, val label: String, val mediaCount: Int) {
  init {
    require(name.isNotEmpty() && name == PeopleDictionary.normalize(name)) { "Nome non normalizzato." }
    require(mediaCount >= 1) { "Una persona nel dizionario ha almeno un media visibile." }
  }

  /** Mai il nome (D37). */
  override fun toString(): String = "PersonEntry($mediaCount)"
}

/** Il dizionario dei nomi delle persone (D26). Vuoto quando Persone e' spenta, in attesa dell'avviso o esclusa: il nome resta testo. */
class PeopleDictionary(val entries: List<PersonEntry>) {
  /** Gli omonimi di [name] (normalizzato), dal piu' frequente; a pari numero l'id minore (deterministico). */
  fun lookup(name: String): List<PersonEntry> =
    entries.filter { it.name == name }.sortedWith(compareByDescending<PersonEntry> { it.mediaCount }.thenBy { it.id })

  val isEmpty: Boolean get() = entries.isEmpty()

  override fun toString(): String = "PeopleDictionary(${entries.size})"

  companion object {
    val EMPTY = PeopleDictionary(emptyList())

    /** La stessa normalizzazione dei luoghi e delle parole ([SearchText.normalize]). */
    fun normalize(name: String): String = SearchText.normalize(name)
  }
}

/**
 * Le persone per la ricerca (D26), legate da :app sopra `PeopleIndex` di core-faces (P10, P13). [available] e' falso dove i volti non ci
 * sono (Persone spenta, in attesa dell'avviso, dispositivo escluso: E-F37, L101): allora il dizionario e' vuoto e nessun chip "Persona"
 * nasce. [cells] da' le celle con un volto **assegnato** a quelle persone (automatico o confermato, membri del gruppo compresi), e con
 * [includeLikely] anche quelle con un volto nella fascia "forse" ("Mostra anche risultati meno probabili"). **Mai un nascosto**: i
 * candidati vengono da `VisibleMedia` e passano dal filtro finale `HiddenMask` (D28).
 */
interface PeopleLookup {
  val available: StateFlow<Boolean>

  suspend fun dictionary(): PeopleDictionary

  suspend fun cells(option: PersonOption, includeLikely: Boolean = false): Set<MediaId>
}
