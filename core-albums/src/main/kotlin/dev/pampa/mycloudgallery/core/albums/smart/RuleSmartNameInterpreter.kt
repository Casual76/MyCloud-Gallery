package dev.pampa.mycloudgallery.core.albums.smart

import dev.pampa.mycloudgallery.core.albums.contract.CandidateFilter
import dev.pampa.mycloudgallery.core.albums.contract.HomeClearance
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.NoHomeClearance
import dev.pampa.mycloudgallery.core.albums.contract.PeopleRule
import dev.pampa.mycloudgallery.core.albums.contract.PlaceAlternative
import dev.pampa.mycloudgallery.core.albums.contract.PlaceRule
import dev.pampa.mycloudgallery.core.albums.contract.ProposalNote
import dev.pampa.mycloudgallery.core.albums.contract.RuleProposal
import dev.pampa.mycloudgallery.core.albums.contract.SmartMediaType
import dev.pampa.mycloudgallery.core.albums.contract.SmartNameInterpreter
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.places.contract.ReverseGeocoder
import dev.pampa.mycloudgallery.core.search.contract.ChipMatching
import dev.pampa.mycloudgallery.core.search.contract.DateMediaCount
import dev.pampa.mycloudgallery.core.search.contract.PeopleDictionary
import dev.pampa.mycloudgallery.core.search.contract.PeopleLookup
import dev.pampa.mycloudgallery.core.search.contract.PlaceDictionary
import dev.pampa.mycloudgallery.core.search.contract.PlaceLevel
import dev.pampa.mycloudgallery.core.search.contract.PlaceLookup
import dev.pampa.mycloudgallery.core.search.contract.PlaceOption
import dev.pampa.mycloudgallery.core.search.contract.QueryParser
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchMediaType
import dev.pampa.mycloudgallery.core.search.noop.NoPeopleLookup
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

// IL NOME DIVENTA REGOLE (D13, §2.2 "Creazione" passi 1-3; E-AL1, E-AL2, E-AL9, E-AL12). Il titolo passa da `ItalianQueryParser` con il dizionario
// dei luoghi della Libreria (solo media visibili): i chip di data diventano il **periodo**, il chip di luogo il **luogo** (con gli omonimi come
// alternative), il resto la **descrizione**. Poi il **raffinamento**: con un luogo e un periodo lungo (un anno, una stagione, un mese) si cercano i
// giorni con foto **proprie** geolocalizzate nel luogo e il periodo diventa quella serie di giorni piu' o meno un giorno; piu' serie = l'editor le
// mostra tutte (E-AL12). Senza foto nel luogo resta il periodo del titolo, con una nota. Mai rete, mai modelli: solo regole e il catalogo di qui.
//
// Fase 8 (D27, §2.7 "Creazione"): con il dizionario delle persone ([PeopleLookup], la stessa porta della ricerca) un nome nel titolo diventa la regola delle
// **persone** ("Marco 2025" = Marco nel 2025; "Marco e Anna" = tutte e due; "Marco o Anna" = una qualunque). Come in Cerca, un nome che e' anche un luogo o una festa
// lo decide chi ha piu' media visibili. Piu' chip "Persona" in "e" si fondono in "tutte"; se uno e' "una qualunque" di piu' persone vale il primo (L206).

class RuleSmartNameInterpreter(
  private val parser: QueryParser,
  private val visible: VisibleMedia,
  mask: HiddenMask,
  candidates: SmartCandidates,
  private val lookup: PlaceLookup,
  private val search: SmartContentSearch = NoSmartContentSearch,
  geocoder: ReverseGeocoder? = null,
  /** R16: dove NON si scrive il centro di un luogo (casa e luoghi abituali); senza casa nota non c'e' niente da evitare. */
  home: HomeClearance = NoHomeClearance,
  /** Fase 8: i nomi delle persone con un nome (Persone spenta o esclusa: nessun nome, il nome resta testo). */
  private val people: PeopleLookup = NoPeopleLookup,
) : SmartNameInterpreter {

  private val library = VisibleCandidates(candidates, visible, mask)
  private val resolver = PlaceResolver(library, geocoder, home)

  @Volatile private var cached: Pair<TimelineSnapshot, LibraryDictionary>? = null

  private fun dictionaryOf(snapshot: TimelineSnapshot?): LibraryDictionary {
    if (snapshot == null) return LibraryDictionary(PlaceDictionary.EMPTY)
    cached?.takeIf { it.first === snapshot }?.let { return it.second }
    return SmartLibraryPlaces.build(snapshot, lookup).also { cached = snapshot to it }
  }

  override suspend fun propose(title: String, today: LocalDate): RuleProposal {
    val dictionary = dictionaryOf(visible.snapshot.value)
    val parsed = parser.parse(title.take(QueryParser.MAX_QUERY_CHARS), today, dictionary.dictionary, peopleDictionary(visible.snapshot.value))
    val notes = LinkedHashSet<ProposalNote>()

    val period = PeriodChips.period(parsed.chips)
    if (period == null) notes += ProposalNote.NO_DATE

    val placeChips = parsed.chips.filterIsInstance<SearchChip.Place>()
    var place: PlaceRule? = null
    val alternatives = ArrayList<PlaceAlternative>()
    if (placeChips.isNotEmpty()) {
      val first = placeChips.first()
      // Gli omonimi veri: un'interpretazione con gli stessi luoghi (la regione "Oslo" di Oslo) e' la stessa cosa, non un'alternativa.
      val others = first.alternatives.filter { it.ids != first.option.ids }.distinctBy { it.ids }
      val labels = labelsOf(listOf(first.option) + others)
      place = resolver.rule(listOf(first.option) + placeChips.drop(1).map { it.option }, labels.getValue(first.option))
      if (place != null) {
        for (other in others) resolver.rule(other, labels.getValue(other))?.let { alternatives += PlaceAlternative(it, dictionary.countOf(other)) }
      }
    }
    if (place == null) notes += ProposalNote.NO_PLACE

    var rulesPeriod = period
    var series = emptyList<LocalDayRange>()
    if (place != null && period != null && PeriodChips.lengthDays(period) >= SmartTuning.REFINE_MIN_DAYS) {
      val days = ownDaysInPlace(place, period)
      if (days.isEmpty()) {
        notes += ProposalNote.NO_PHOTOS_IN_PLACE
      } else {
        series = PeriodSeries.build(days)
        rulesPeriod = SmartPeriod.Ranges(series)
        if (series.size > 1) notes += ProposalNote.SEVERAL_SERIES
      }
    }

    val text = descriptionOf(parsed.semantic, parsed.exact)
    if (text != null && !search.ready) notes += ProposalNote.NO_SEARCH
    val rules = SmartRules(period = rulesPeriod, place = place, text = text, types = typesOf(parsed.chips), people = peopleRule(parsed.chips))
    return RuleProposal(rules, alternatives, series, notes)
  }

  /** Il dizionario dei nomi per l'analizzatore, con il peso delle date dell'istantanea (E-F36); vuoto dove i volti non ci sono o se la porta sbaglia. */
  private suspend fun peopleDictionary(snapshot: TimelineSnapshot?): PeopleDictionary {
    if (!people.available.value) return PeopleDictionary.EMPTY
    val loaded = try {
      people.dictionary()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return PeopleDictionary.EMPTY
    }
    if (loaded.isEmpty || snapshot == null) return loaded
    return PeopleDictionary(loaded.entries, DateMediaCount { chip -> (0 until snapshot.size).count { ChipMatching.accepts(chip, snapshot, it) } })
  }

  /** La regola delle persone dei chip "Persona" del titolo, o null. */
  private fun peopleRule(chips: List<SearchChip>): PeopleRule? {
    val persons = chips.filterIsInstance<SearchChip.Person>()
    val first = persons.firstOrNull() ?: return null
    if (persons.size > 1 && persons.all { it.option.ids.size == 1 || it.option.mode == PeopleMode.ALL }) {
      return PeopleRule(persons.flatMap { it.option.ids }.distinct().take(PeopleRule.MAX_PEOPLE), PeopleMode.ALL)
    }
    return PeopleRule(first.option.ids, first.option.mode)
  }

  /**
   * I nomi da mostrare per gli omonimi (E-AL2): "Oslo (Norvegia)" e "Oslo (Stati Uniti)", o la regione se il paese e' lo stesso ("San Marco
   * (Veneto)"), o il tipo se uno e' un luogo e l'altro un paese ("Panama (luogo)"). Un luogo senza omonimi ha il suo nome.
   */
  private fun labelsOf(group: List<PlaceOption>): Map<PlaceOption, String> {
    if (group.size < 2) return group.associateWith { it.label }
    val info = group.associateWith { option -> option.ids.minOrNull()?.let { lookup.place(it) } }
    val levels = group.map { it.level }.distinct()
    val countries = info.values.mapNotNull { it?.country }.distinct()
    val regions = info.values.mapNotNull { it?.admin1 }.distinct()
    return group.associateWith { option ->
      val qualifier = when {
        levels.size > 1 -> when (option.level) {
          PlaceLevel.PLACE -> "luogo"
          PlaceLevel.REGION -> "regione"
          PlaceLevel.COUNTRY -> "paese"
        }
        countries.size > 1 -> info[option]?.country
        regions.size > 1 -> info[option]?.admin1
        else -> null
      }
      if (qualifier == null) option.label else "${option.label} ($qualifier)"
    }
  }

  /** Il testo che resta al titolo: il contenuto e le frasi fra virgolette (che la ricerca rilegge come frasi esatte). */
  private fun descriptionOf(semantic: String?, exact: List<String>): String? {
    val parts = ArrayList<String>()
    semantic?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
    exact.forEach { parts += "\"$it\"" }
    return parts.joinToString(" ").takeIf { it.isNotBlank() }
  }

  private fun typesOf(chips: List<SearchChip>): Set<SmartMediaType> {
    val wanted = chips.filterIsInstance<SearchChip.MediaType>().mapNotNull {
      when (it.type) {
        SearchMediaType.PHOTO -> SmartMediaType.PHOTO
        SearchMediaType.VIDEO -> SmartMediaType.VIDEO
        // Screenshot, panorami, foto in movimento e documenti non sono un tipo delle regole degli album (solo foto e video).
        else -> null
      }
    }.toSet()
    return wanted.ifEmpty { SmartMediaType.ALL }
  }

  /** I giorni del [period] con foto **proprie** geolocalizzate nel luogo (solo visibili): la materia delle serie. */
  private suspend fun ownDaysInPlace(place: PlaceRule, period: SmartPeriod): Set<LocalDate> {
    val matcher = PlaceMatcher(place, lookup)
    val filters = when (period) {
      is SmartPeriod.Ranges -> period.ranges.map { CandidateFilter(days = it, withGpsOnly = true) }
      else -> listOf(CandidateFilter(withGpsOnly = true))
    }
    val days = LinkedHashSet<LocalDate>()
    for (filter in filters) {
      for (c in library.all(filter)) {
        if (!c.mine || c.c.screen || c.c.gps == null || c.c.day !in period) continue
        if (matcher.fit(c.c) == PlaceFit.IN) days += c.c.day
      }
    }
    return days
  }
}
