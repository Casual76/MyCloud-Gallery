package dev.pampa.mycloudgallery.core.albums.smart

import dev.pampa.mycloudgallery.core.albums.contract.CandidateFilter
import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
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
import dev.pampa.mycloudgallery.core.places.contract.ReverseGeocoder
import dev.pampa.mycloudgallery.core.search.contract.PlaceDictionary
import dev.pampa.mycloudgallery.core.search.contract.PlaceLookup
import dev.pampa.mycloudgallery.core.search.contract.QueryParser
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchMediaType
import java.time.LocalDate

// IL NOME DIVENTA REGOLE (D13, §2.2 "Creazione" passi 1-3; E-AL1, E-AL2, E-AL9, E-AL12). Il titolo passa da `ItalianQueryParser` con il dizionario
// dei luoghi della Libreria (solo media visibili): i chip di data diventano il **periodo**, il chip di luogo il **luogo** (con gli omonimi come
// alternative), il resto la **descrizione**. Poi il **raffinamento**: con un luogo e un periodo lungo (un anno, una stagione, un mese) si cercano i
// giorni con foto **proprie** geolocalizzate nel luogo e il periodo diventa quella serie di giorni piu' o meno un giorno; piu' serie = l'editor le
// mostra tutte (E-AL12). Senza foto nel luogo resta il periodo del titolo, con una nota. Mai rete, mai modelli: solo regole e il catalogo di qui.

class RuleSmartNameInterpreter(
  private val parser: QueryParser,
  private val visible: VisibleMedia,
  mask: HiddenMask,
  candidates: SmartCandidates,
  private val lookup: PlaceLookup,
  private val search: SmartContentSearch = NoSmartContentSearch,
  geocoder: ReverseGeocoder? = null,
) : SmartNameInterpreter {

  private val library = VisibleCandidates(candidates, visible, mask)
  private val resolver = PlaceResolver(library, geocoder)

  @Volatile private var cached: Pair<TimelineSnapshot, LibraryDictionary>? = null

  private fun dictionaryOf(snapshot: TimelineSnapshot?): LibraryDictionary {
    if (snapshot == null) return LibraryDictionary(PlaceDictionary.EMPTY)
    cached?.takeIf { it.first === snapshot }?.let { return it.second }
    return SmartLibraryPlaces.build(snapshot, lookup).also { cached = snapshot to it }
  }

  override suspend fun propose(title: String, today: LocalDate): RuleProposal {
    val dictionary = dictionaryOf(visible.snapshot.value)
    val parsed = parser.parse(title.take(QueryParser.MAX_QUERY_CHARS), today, dictionary.dictionary)
    val notes = LinkedHashSet<ProposalNote>()

    val period = PeriodChips.period(parsed.chips)
    if (period == null) notes += ProposalNote.NO_DATE

    val placeChips = parsed.chips.filterIsInstance<SearchChip.Place>()
    var place: PlaceRule? = null
    val alternatives = ArrayList<PlaceAlternative>()
    if (placeChips.isNotEmpty()) {
      val first = placeChips.first()
      place = resolver.rule(listOf(first.option) + placeChips.drop(1).map { it.option })
      if (place != null) {
        for (other in first.alternatives) resolver.rule(other)?.let { alternatives += PlaceAlternative(it, dictionary.countOf(other)) }
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
    val rules = SmartRules(period = rulesPeriod, place = place, text = text, types = typesOf(parsed.chips))
    return RuleProposal(rules, alternatives, series, notes)
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
