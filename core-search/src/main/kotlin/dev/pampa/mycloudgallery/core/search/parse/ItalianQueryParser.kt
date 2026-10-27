package dev.pampa.mycloudgallery.core.search.parse

import dev.pampa.mycloudgallery.core.search.contract.ParsedQuery
import dev.pampa.mycloudgallery.core.search.contract.PeopleDictionary
import dev.pampa.mycloudgallery.core.search.contract.PlaceDictionary
import dev.pampa.mycloudgallery.core.search.contract.QueryParser
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchMediaType
import dev.pampa.mycloudgallery.core.search.contract.SearchText
import java.time.LocalDate

/**
 * L'analizzatore italiano delle query (P7, §2.5, D26): regole deterministiche, nessuna rete, nessun modello. Puro: "oggi" e' solo il
 * parametro `today` (D39, dalla porta `Today`). Il testo si piega (minuscolo, senza accenti), si spezza in token, le parole del
 * dizionario scritte con un refuso si correggono (E-Q5) e le regole di data, calendario, tipo e luogo consumano i token: quello che
 * resta e' il contenuto (con le parole vuote) e le parole del testo letto (senza). Le virgolette chiedono il testo esatto; nessun'altra
 * sintassi si interpreta (E-Q9).
 *
 * Fase 8 (P10, D26, §2.7): con il dizionario delle persone un nome diventa un chip "Persona" -- **dopo le virgolette e prima dei luoghi**, la
 * corrispondenza piu' lunga; "Marco e Anna" sono tutte, "Marco o Anna" una qualunque, piu' nomi senza congiunzione tutte; se il nome e' anche una
 * festa, un mese o un luogo vince la lettura con piu' media visibili e il chip propone l'altra (E-F36). Senza dizionario (Persone spenta) un nome
 * resta testo: l'analisi e' quella di prima, riga per riga.
 */
class ItalianQueryParser : QueryParser {
  @Volatile private var cached: Pair<PlaceDictionary, PlaceIndex>? = null
  @Volatile private var cachedNames: Pair<PeopleDictionary, PeopleNameIndex>? = null

  private fun indexFor(places: PlaceDictionary): PlaceIndex {
    val c = cached
    if (c != null && c.first === places) return c.second
    return PlaceIndex(places).also { cached = places to it }
  }

  private fun namesFor(people: PeopleDictionary): PeopleNameIndex? {
    if (people.isEmpty) return null
    val c = cachedNames
    if (c != null && c.first === people) return c.second.takeUnless { it.isEmpty }
    return PeopleNameIndex(people).also { cachedNames = people to it }.takeUnless { it.isEmpty }
  }

  private class Found(val pos: Int, val chip: SearchChip)

  override fun parse(text: String, today: LocalDate, places: PlaceDictionary): ParsedQuery = parse(text, today, places, PeopleDictionary.EMPTY)

  override fun parse(text: String, today: LocalDate, places: PlaceDictionary, people: PeopleDictionary): ParsedQuery {
    val input = truncate(text).trim()
    if (input.length <= 1) return EMPTY
    val folded = QueryTokenizer.fold(input).toCharArray()
    val removed = BooleanArray(input.length)
    val found = ArrayList<Found>()
    val exact = ArrayList<String>()
    for (m in QUOTED.findAll(input)) {
      val phrase = m.groupValues[1].trim().replace(SPACES, " ")
      for (k in m.range) {
        folded[k] = '\u0000'; removed[k] = true
      }
      if (SearchText.normalize(phrase).isEmpty()) continue
      exact += phrase
      found += Found(m.range.first, SearchChip.Word(phrase, exact = true))
    }
    val tokens = QueryTokenizer.tokenize(String(folded))
    val index = indexFor(places)
    val names = namesFor(people)
    for (tk in tokens) {
      if (tk.isWord && tk.word.length >= 4 && !QueryVocabulary.isProtected(tk.word) && !index.hasWord(tk.word) && names?.hasWord(tk.word) != true && names?.looksLikeName(tk.word) != true) {
        QueryVocabulary.correct(tk.word)?.let { tk.word = it }
      }
    }
    val ctx = RuleContext(tokens, today)
    val dates = DateRules(ctx)
    val calendar = CalendarRules(ctx)
    val types = TypeRules(ctx)
    val corrections = ArrayList<Pair<String, String>>()
    val readings = QueryReadings(tokens, index, names, dates, calendar, types)
    val consumed = BooleanArray(tokens.size)
    var i = 0
    while (i < tokens.size) {
      val tk = tokens[i]
      val reading = readings.at(i)
      if (reading == null) {
        i++; continue
      }
      var len = reading.len
      val chip: SearchChip
      val declared: List<Pair<String, String>>
      if (reading is PersonReading) {
        val chain = readings.chain(i, reading)
        len = chain.end - i
        chip = chain.chip
        declared = chain.corrections
        // Anche i separatori fra una persona e l'altra (virgole, congiunzioni) escono dal contenuto.
        for (p in tk.start until tokens[chain.end - 1].end) removed[p] = true
      } else {
        reading as PlainReading
        chip = reading.chip
        declared = reading.notes + listOfNotNull(reading.correction)
      }
      for (k in i until i + len) {
        consumed[k] = true
        val t = tokens[k]
        for (p in t.start until t.end) removed[p] = true
        if (t.isWord && t.word != t.typed) corrections += t.word to t.typed
      }
      corrections += declared
      // La preposizione o l'articolo subito prima esce con il chip ("a Firenze", "nel 2023", "di ieri").
      if (i > 0 && !consumed[i - 1] && tokens[i - 1].isWord && tokens[i - 1].word in QueryVocabulary.GLUE) {
        consumed[i - 1] = true
        for (p in tokens[i - 1].start until tokens[i - 1].end) removed[p] = true
      }
      found += Found(tk.start, chip)
      i += len
    }
    return build(input, removed, found, exact, corrections)
  }

  private fun build(
    input: String,
    removed: BooleanArray,
    found: List<Found>,
    exact: List<String>,
    corrections: List<Pair<String, String>>,
  ): ParsedQuery {
    var chips = found.sortedBy { it.pos }.map { it.chip }.distinct()
    // "foto e video di Natale": tutti e due i tipi = nessun filtro di tipo.
    val hasPhoto = chips.any { it is SearchChip.MediaType && it.type == SearchMediaType.PHOTO }
    val hasVideo = chips.any { it is SearchChip.MediaType && it.type == SearchMediaType.VIDEO }
    if (hasPhoto && hasVideo) {
      chips = chips.filterNot { it is SearchChip.MediaType && (it.type == SearchMediaType.PHOTO || it.type == SearchMediaType.VIDEO) }
    }
    val rest = StringBuilder(input.length)
    for (p in input.indices) {
      val ch = input[p]
      rest.append(if (removed[p] || ch in QUOTE_CHARS) ' ' else ch)
    }
    val text = rest.toString().replace(SPACES, " ").trim { it.isWhitespace() || it in EDGE_PUNCTUATION }
    val words = SearchText.words(text).filter { it !in SearchText.STOPWORDS }.distinct()
    return ParsedQuery(chips, if (words.isEmpty()) null else text, words, exact, corrections.distinct())
  }

  private fun truncate(text: String): String {
    val cut = text.take(QueryParser.MAX_QUERY_CHARS)
    return if (cut.isNotEmpty() && cut.length < text.length && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
  }

  private companion object {
    val EMPTY = ParsedQuery(emptyList(), null, emptyList(), emptyList())
    val QUOTED = Regex("[\"“”«»]([^\"“”«»]+)[\"“”«»]")
    val SPACES = Regex("\\s+")
    const val QUOTE_CHARS = "\"“”«»"
    const val EDGE_PUNCTUATION = ",;:.-–/"
  }
}
