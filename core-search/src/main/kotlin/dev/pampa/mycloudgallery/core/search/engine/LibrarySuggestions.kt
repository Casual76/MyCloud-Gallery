package dev.pampa.mycloudgallery.core.search.engine

import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.search.contract.IdleSuggestions
import dev.pampa.mycloudgallery.core.search.contract.PeopleDictionary
import dev.pampa.mycloudgallery.core.search.contract.PeopleLookup
import dev.pampa.mycloudgallery.core.search.contract.PersonEntry
import dev.pampa.mycloudgallery.core.search.contract.PersonOption
import dev.pampa.mycloudgallery.core.search.contract.PlaceDictionary
import dev.pampa.mycloudgallery.core.search.contract.PlaceEntry
import dev.pampa.mycloudgallery.core.search.contract.PlaceLevel
import dev.pampa.mycloudgallery.core.search.contract.PlaceLookup
import dev.pampa.mycloudgallery.core.search.contract.PlaceOption
import dev.pampa.mycloudgallery.core.search.contract.SearchData
import dev.pampa.mycloudgallery.core.search.contract.SearchHistory
import dev.pampa.mycloudgallery.core.search.contract.SearchMediaType
import dev.pampa.mycloudgallery.core.search.contract.SearchText
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.core.search.contract.Suggestions
import dev.pampa.mycloudgallery.core.search.noop.NoPeopleLookup
import kotlin.coroutines.cancellation.CancellationException

/**
 * IL DIZIONARIO DEI LUOGHI della Libreria (§2.5) per l'analizzatore e i suggerimenti: **solo dalle celle dell'istantanea**, che non
 * contiene mai nascosti (D29: un luogo presente solo in media nascosti non si propone). Il luogo, la sua regione e il paese, con
 * quanti media visibili li hanno (fra omonimi vince il piu' frequente). I nomi si ricostruiscono solo se cambiano i conteggi dei luoghi.
 */
class LibraryPlaces(private val lookup: PlaceLookup) {

  private class Built(val snapshot: TimelineSnapshot, val counts: Map<Int, Int>, val dictionary: PlaceDictionary, val top: List<PlaceOption>)

  @Volatile private var built: Built? = null

  fun dictionary(snapshot: TimelineSnapshot): PlaceDictionary = buildFor(snapshot).dictionary

  /** I luoghi piu' frequenti (per Cerca a riposo). */
  fun top(snapshot: TimelineSnapshot, limit: Int = MAX_IDLE_PLACES): List<PlaceOption> = buildFor(snapshot).top.take(limit)

  @Synchronized
  private fun buildFor(snapshot: TimelineSnapshot): Built {
    val previous = built
    previous?.takeIf { it.snapshot === snapshot }?.let { return it }
    val counts = HashMap<Int, Int>()
    for (i in 0 until snapshot.size) {
      val id = snapshot.placeAt(i)
      if (id != 0) counts[id] = (counts[id] ?: 0) + 1
    }
    if (previous != null && previous.counts == counts) {
      return Built(snapshot, counts, previous.dictionary, previous.top).also { built = it }
    }
    val entries = ArrayList<PlaceEntry>()
    val regions = LinkedHashMap<String, Pair<String, MutableMap<Int, Int>>>()
    val countries = LinkedHashMap<String, Pair<String, MutableMap<Int, Int>>>()
    for ((id, count) in counts.entries.sortedBy { it.key }) {
      val name = lookup.place(id) ?: continue
      val key = SearchText.normalize(name.name)
      if (key.isNotEmpty()) entries += PlaceEntry(key, PlaceOption(setOf(id), name.name, PlaceLevel.PLACE), count)
      name.admin1?.let { a -> SearchText.normalize(a).takeIf { it.isNotEmpty() }?.let { regions.getOrPut(it) { a to HashMap() }.second[id] = count } }
      SearchText.normalize(name.country).takeIf { it.isNotEmpty() }?.let { countries.getOrPut(it) { name.country to HashMap() }.second[id] = count }
    }
    for ((key, v) in regions) entries += PlaceEntry(key, PlaceOption(v.second.keys, v.first, PlaceLevel.REGION), v.second.values.sum())
    for ((key, v) in countries) entries += PlaceEntry(key, PlaceOption(v.second.keys, v.first, PlaceLevel.COUNTRY), v.second.values.sum())
    val top = entries.filter { it.option.level == PlaceLevel.PLACE }.sortedWith(compareByDescending<PlaceEntry> { it.mediaCount }.thenBy { it.name })
      .map { it.option }
    return Built(snapshot, counts, PlaceDictionary(entries), top).also { built = it }
  }

  companion object {
    const val MAX_IDLE_PLACES: Int = 8
  }
}

/**
 * I SUGGERIMENTI di Cerca (§2.6): a riposo le ricerche recenti (fino a 10), i luoghi piu' frequenti (fino a 8) e i tipi; mentre si
 * scrive le ricerche recenti, i luoghi e i tipi che cominciano cosi'. **Mai** parole del testo letto (il vocabolario contiene anche
 * quelle dei nascosti, D29), mai un luogo che non ha celle visibili.
 *
 * Fase 8 (P10, §2.7): a riposo una riga "Persone" (fino a [MAX_IDLE_PEOPLE], le piu' frequenti con un nome) e, mentre si scrive, i nomi che cominciano
 * cosi'. Vengono dal dizionario della porta [PeopleLookup]: persone con un nome, mai nascoste ne' dimenticate, con almeno un media **visibile** (D28);
 * dove i volti non ci sono (Persone spenta, in attesa dell'avviso, esclusa) il dizionario e' vuoto e non si propone niente.
 */
class LibrarySuggestions(
  private val data: SearchData,
  private val places: LibraryPlaces,
  private val history: SearchHistory,
  private val people: PeopleLookup = NoPeopleLookup,
) : Suggestions {

  override suspend fun idle(): IdleSuggestions {
    val snapshot = data.snapshot.value
    return IdleSuggestions(
      recents = history.entries.value.take(MAX_RECENTS),
      places = snapshot?.let { places.top(it) }.orEmpty(),
      types = TYPES.map { it.first },
      people = peopleDictionary().top(MAX_IDLE_PEOPLE).map { optionOf(it) },
    )
  }

  override suspend fun complete(prefix: String): List<Suggestion> {
    val p = SearchText.normalize(prefix)
    // I nomi delle persone si completano in ogni alfabeto (RI-1: "Ан" propone "Анна"); luoghi e tipi restano latini.
    val pn = PeopleDictionary.normalize(prefix)
    if (p.isEmpty() && pn.isEmpty()) return emptyList()
    val out = ArrayList<Suggestion>()
    val latin = p.isNotEmpty()
    history.entries.value.filter { if (latin) SearchText.normalize(it.text).startsWith(p) else PeopleDictionary.normalize(it.text).startsWith(pn) }
      .take(MAX_RECENTS).forEach { out += Suggestion.Recent(it) }
    if (pn.isNotEmpty()) {
      peopleDictionary().entries.filter { e -> e.name.startsWith(pn) || e.name.split(' ').any { it.startsWith(pn) } }
        .sortedWith(compareByDescending<PersonEntry> { it.mediaCount }.thenBy { it.name }.thenBy { it.id })
        .distinctBy { it.id }.take(MAX_PEOPLE).forEach { out += Suggestion.PersonSuggestion(optionOf(it)) }
    }
    if (p.isEmpty()) return out
    data.snapshot.value?.let { snapshot ->
      places.dictionary(snapshot).entries.filter { e -> e.name.startsWith(p) || e.name.split(' ').any { it.startsWith(p) } }
        .sortedWith(compareByDescending<PlaceEntry> { it.mediaCount }.thenBy { it.name })
        .distinctBy { it.option }.take(MAX_PLACES).forEach { out += Suggestion.PlaceSuggestion(it.option) }
    }
    TYPES.filter { (_, label) -> SearchText.normalize(label).startsWith(p) }.forEach { (type, label) -> out += Suggestion.Type(type, label) }
    return out
  }

  /** Il dizionario dei nomi; vuoto dove i volti non ci sono o se la porta sbaglia (le persone sono un di piu': mai un errore per Cerca). */
  private suspend fun peopleDictionary(): PeopleDictionary {
    if (!people.available.value) return PeopleDictionary.EMPTY
    return try {
      people.dictionary()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      PeopleDictionary.EMPTY
    }
  }

  private fun optionOf(entry: PersonEntry) = PersonOption(listOf(entry.id), PeopleMode.ANY, entry.label)

  companion object {
    const val MAX_RECENTS: Int = 10
    const val MAX_PLACES: Int = 8

    /** La riga "Persone" a riposo (§2.7). */
    const val MAX_IDLE_PEOPLE: Int = 6

    /** I nomi proposti mentre si scrive. */
    const val MAX_PEOPLE: Int = 4

    /** I tipi proposti (le etichette come quelle dei chip dell'analizzatore). */
    val TYPES: List<Pair<SearchMediaType, String>> = listOf(
      SearchMediaType.PHOTO to "Foto",
      SearchMediaType.VIDEO to "Video",
      SearchMediaType.SCREENSHOT to "Screenshot",
      SearchMediaType.DOCUMENT to "Documenti",
      SearchMediaType.PANORAMA to "Panorami",
      SearchMediaType.MOTION_PHOTO to "Foto in movimento",
    )
  }
}
