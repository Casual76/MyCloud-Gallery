package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.core.faces.contract.MatchLevel
import dev.pampa.mycloudgallery.core.faces.contract.PeopleCells
import dev.pampa.mycloudgallery.core.faces.contract.PeopleIndex
import dev.pampa.mycloudgallery.core.faces.people.PeopleVisibility
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.search.contract.PeopleDictionary
import dev.pampa.mycloudgallery.core.search.contract.PeopleLookup
import dev.pampa.mycloudgallery.core.search.contract.PersonEntry
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.search.contract.SearchResults
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.core.search.engine.LibraryPlaces
import dev.pampa.mycloudgallery.core.search.engine.LibrarySuggestions
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import dev.pampa.mycloudgallery.core.search.testing.FixedToday
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchEngine
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchHistory
import dev.pampa.mycloudgallery.search.CatalogSearchData
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Fase 8 (P10, D26, D28, §2.9, E-F36, E-F38; riga PEOPLE del registro): la SONDA `search-people`. La ricerca con una persona -- il dizionario dei nomi, i chip
// "Persona", i candidati, i suggerimenti e i risultati -- sul mondo casuale, con le persone vere (`ArchivePeople`) e la visibilita' vera.
//
// La porta `PeopleLookup` di core-search la lega :app (P13) sopra `PeopleIndex` e le celle; qui la scrive il banco ([IndexPeopleLookup]) con le stesse strade che
// seguira' il prodotto: i candidati sono le celle dell'istantanea (`PeopleCells`) filtrate da `PeopleVisibility` (`VisibleMedia`, filtro finale `HiddenMask`), il
// dizionario viene dalle persone con media mostrabili. Il motore e' quello di riferimento (come la sonda `search`) con il filtro finale vero del catalogo.

/**
 * La porta della ricerca per persona sopra l'indice delle persone: **la forma che P13 dara' al legame in `:app`**. Per ogni persona della scelta si prendono le chiavi
 * di contenuto ([PeopleIndex.contentWith] al livello chiesto), si portano alle **celle** ([PeopleCells]: un membro di un gruppo e' la sua copertina) e si tengono solo
 * quelle che [visibility] dice mostrabili adesso; poi "una qualunque" unisce le celle e "tutte" le interseca **per cella** (due persone in due membri dello stesso gruppo
 * sono nella stessa cella). Il dizionario e' quello dell'indice: nomi di persone non nascoste ne' dimenticate, con almeno un media mostrabile.
 */
internal class IndexPeopleLookup(
  private val index: PeopleIndex,
  private val cells: PeopleCells,
  private val visibility: PeopleVisibility,
  override val progressPercent: StateFlow<Int?> = PeopleLookup.NO_PROGRESS,
) : PeopleLookup {
  override val available: StateFlow<Boolean> get() = index.available

  override suspend fun dictionary(): PeopleDictionary {
    if (!index.available.value) return PeopleDictionary.EMPTY
    return PeopleDictionary(index.dictionary().map { PersonEntry(PeopleDictionary.normalize(it.name), it.id, it.name, it.visibleMedia) })
  }

  override suspend fun cells(option: PersonOption, includeLikely: Boolean): Set<MediaId> {
    if (!index.available.value) return emptySet()
    val level = if (includeLikely) MatchLevel.LIKELY else MatchLevel.SURE
    val perPerson = option.ids.map { id -> cellsOf(id, level) }
    return when (option.mode) {
      PeopleMode.ANY -> perPerson.flatten().toSet()
      PeopleMode.ALL -> perPerson.reduce { acc, set -> acc intersect set }
    }
  }

  private suspend fun cellsOf(id: PersonId, level: MatchLevel): Set<MediaId> {
    val keys = index.contentWith(setOf(id), PeopleMode.ANY, level).keys
    if (keys.isEmpty()) return emptySet()
    return visibility.shown(cells.cellsWith(keys).values.flatten())
  }
}

internal object RealSearchPeopleProbes {

  private val TODAY = LocalDate.of(2026, 10, 21)

  private fun idsOf(results: SearchResults): Set<MediaId> =
    (results.confident.let { view -> (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) } } +
      results.likely.let { view -> (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) } }).toSet()

  private fun confidentOf(results: SearchResults): Set<MediaId> =
    results.confident.let { view -> (0 until view.size).mapTo(LinkedHashSet()) { view.snapshot.idAt(view.snapshotIndex(it)) } }

  /** La ricerca con una persona: nomi, chip, candidati, suggerimenti e risultati. [leaky] toglie il filtro della visibilita' (la prova al contrario). */
  fun searchPeople(leaky: Boolean = false) = SurfaceProbe { w ->
    RealPeopleStack.build(w, leaky).use { stack ->
      val problems = ArrayList<String>()
      val shown = LinkedHashSet<MediaId>()
      val lookup = IndexPeopleLookup(stack.people, stack.cells, PeopleVisibility(stack.visible, stack.mask))

      // 1. Il dizionario dei nomi: solo persone con un nome, non nascoste, non dimenticate, con almeno un media mostrabile; con il conto giusto.
      val dictionary = lookup.dictionary()
      val expectedNamed = stack.expectedNamed()
      checkSame(dictionary.entries.map { it.id }, expectedNamed, "dizionario della ricerca", problems)
      for (entry in dictionary.entries) {
        val want = stack.expectedCells(entry.id).size
        if (entry.mediaCount != want) problems += "il dizionario dichiara ${entry.mediaCount} foto per una persona, ne attendeva $want"
      }

      // 2. I candidati: ogni scelta di persone da' solo celle mostrabili adesso, e quelle giuste (tutti i volti del mondo sono "sicuri").
      suspend fun candidates(ids: List<PersonId>, mode: PeopleMode, label: String) {
        val option = PersonOption(ids, mode, label)
        val sure = lookup.cells(option, includeLikely = false)
        val likely = lookup.cells(option, includeLikely = true)
        shown += sure
        shown += likely
        val perPerson = ids.map { stack.expectedCells(it) }
        val expected = if (mode == PeopleMode.ANY) perPerson.flatten().toSet() else perPerson.reduce { acc, set -> acc intersect set }
        if (sure != expected) problems += "i candidati sicuri di $label sono ${sure.size}, ne attendeva ${expected.size}"
        if (!likely.containsAll(sure)) problems += "i candidati di $label con i \"forse\" non contengono i sicuri"
      }
      val searchable = stack.named.map { it.first }
      for (id in searchable) candidates(listOf(id), PeopleMode.ANY, "una persona")
      for (a in searchable.indices) for (b in a + 1 until searchable.size) {
        candidates(listOf(searchable[a], searchable[b]), PeopleMode.ANY, "una qualunque")
        candidates(listOf(searchable[a], searchable[b]), PeopleMode.ALL, "tutte")
      }
      // Una persona dimenticata: "tutte" con lei non da' niente, "una qualunque" solo le altre.
      val forgotten = stack.forgottenPerson
      shown += lookup.cells(PersonOption(listOf(forgotten), PeopleMode.ANY, "dimenticata"), includeLikely = true)
      if (lookup.cells(PersonOption(listOf(forgotten, searchable.first()), PeopleMode.ALL, "tutte"), includeLikely = true).isNotEmpty()) {
        problems += "\"tutte\" con una persona dimenticata da' celle"
      }
      // Una persona che vive solo su media da non mostrare non ha celle.
      val onlyForbidden = lookup.cells(PersonOption(listOf(stack.onlyForbidden), PeopleMode.ANY, "solo nascosta"), includeLikely = true)
      shown += onlyForbidden
      if (onlyForbidden.isNotEmpty()) problems += "una persona che vive solo su media nascosti ha ${onlyForbidden.size} celle"

      // 3. La ricerca: il motore con il catalogo vero per il filtro finale. Un nome del dizionario e' un chip; il nome di una persona nascosta resta testo.
      val data = CatalogSearchData(MutableStateFlow(w.snapshot), w.db) { w.catalog }
      val engine = InMemorySearchEngine(data, null, ItalianQueryParser(), FixedToday(TODAY), people = lookup)
      val names = stack.named.associate { it.first to it.second }
      fun nameOf(id: PersonId) = names.getValue(id)
      val visibleNamed = expectedNamed
      for (id in visibleNamed) {
        val results = engine.search(SearchRequest(nameOf(id), showLikely = true))
        shown += idsOf(results)
        val chip = results.chips.filterIsInstance<SearchChip.Person>().singleOrNull()
        if (chip == null || chip.option.ids != listOf(id)) problems += "il nome di una persona visibile non e' un chip \"Persona\""
        if (confidentOf(results) != stack.expectedCells(id)) problems += "la ricerca di una persona mostra ${confidentOf(results).size} foto, ne attendeva ${stack.expectedCells(id).size}"
      }
      for (a in visibleNamed.indices) for (b in a + 1 until visibleNamed.size) {
        val first = nameOf(visibleNamed[a])
        val second = nameOf(visibleNamed[b])
        val both = engine.search(SearchRequest("$first e $second", order = ResultOrder.RELEVANT))
        shown += idsOf(both)
        val expectedBoth = stack.expectedCells(visibleNamed[a]) intersect stack.expectedCells(visibleNamed[b])
        if (confidentOf(both) != expectedBoth) problems += "\"A e B\" mostra ${confidentOf(both).size} foto, ne attendeva ${expectedBoth.size}"
        val either = engine.search(SearchRequest("$first o $second"))
        shown += idsOf(either)
        val expectedEither = stack.expectedCells(visibleNamed[a]) + stack.expectedCells(visibleNamed[b])
        if (confidentOf(either) != expectedEither) problems += "\"A o B\" mostra ${confidentOf(either).size} foto, ne attendeva ${expectedEither.size}"
      }
      // Con un altro filtro: la persona e i video.
      for (id in visibleNamed) shown += idsOf(engine.search(SearchRequest("video ${nameOf(id)}", showLikely = true)))
      // La persona nascosta ha un nome, ma la ricerca non la conosce: nessun chip, nessun risultato "per persona".
      val hiddenName = names.getValue(stack.namedHiddenPerson)
      val hiddenResults = engine.search(SearchRequest(hiddenName))
      if (hiddenResults.chips.any { it is SearchChip.Person }) problems += "il nome di una persona nascosta e' un chip \"Persona\""
      shown += idsOf(hiddenResults)

      // 4. I suggerimenti: solo nomi di persone visibili, a riposo e mentre si scrive.
      val suggestions = LibrarySuggestions(data, LibraryPlaces { null }, InMemorySearchHistory(), lookup)
      val allowedLabels = visibleNamed.map { nameOf(it) }.toSet()
      val idle = suggestions.idle().people.map { it.label }
      if (!allowedLabels.containsAll(idle)) problems += "a riposo si propone una persona che non e' visibile"
      if (idle.size != minOf(visibleNamed.size, LibrarySuggestions.MAX_IDLE_PEOPLE)) problems += "a riposo ci sono ${idle.size} persone, ne attendeva ${minOf(visibleNamed.size, LibrarySuggestions.MAX_IDLE_PEOPLE)}"
      for (prefix in listOf("a", "m", "g", "l", "ma", "an", "gi", "lu")) {
        val proposed = suggestions.complete(prefix).filterIsInstance<Suggestion.PersonSuggestion>().map { it.label }
        if (!allowedLabels.containsAll(proposed)) problems += "mentre si scrive si propone una persona che non e' visibile"
      }
      Probed(shown = shown, within = w.liveShowable, problems = problems)
    }
  }

  private fun checkSame(got: List<PersonId>, want: List<PersonId>, where: String, problems: MutableList<String>) {
    val extra = got.toSet() - want.toSet()
    val missing = want.toSet() - got.toSet()
    if (extra.isNotEmpty()) problems += "$where: ${extra.size} persone in piu'"
    if (missing.isNotEmpty()) problems += "$where: ${missing.size} persone mancano"
    if (got.size != got.toSet().size) problems += "$where: una persona compare due volte"
  }
}
