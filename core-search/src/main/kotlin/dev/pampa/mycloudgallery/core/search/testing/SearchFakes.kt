package dev.pampa.mycloudgallery.core.search.testing

import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.EmbeddingSpace
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.search.contract.HistoryEntry
import dev.pampa.mycloudgallery.core.search.contract.MediaText
import dev.pampa.mycloudgallery.core.search.contract.ParsedQuery
import dev.pampa.mycloudgallery.core.search.contract.PlaceDictionary
import dev.pampa.mycloudgallery.core.search.contract.QueryParser
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchData
import dev.pampa.mycloudgallery.core.search.contract.SearchFlag
import dev.pampa.mycloudgallery.core.search.contract.SearchHistory
import dev.pampa.mycloudgallery.core.search.contract.SearchMediaType
import dev.pampa.mycloudgallery.core.search.contract.SearchText
import dev.pampa.mycloudgallery.core.search.contract.TextLayout
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.core.search.history.HistoryNames
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow

// I fake della ricerca (Fase 6, P0): in src/main per i test di P6, P7, P8, P9, P12 e P11. Mai nella release (tools/check.ps1).

/** Il catalogo finto della ricerca: istantanea, vettori e testo per media, nascosti scelti dal test. */
class FakeSearchData(snapshot: TimelineSnapshot? = null) : SearchData {
  override val snapshot: MutableStateFlow<TimelineSnapshot?> = MutableStateFlow(snapshot)
  val vectors: MutableMap<MediaId, List<FloatArray>> = HashMap()
  val texts: MutableMap<MediaId, String> = HashMap()
  val hiddenIds: MutableSet<MediaId> = HashSet()
  var calls: Int = 0
    private set

  override suspend fun vectors(ids: Collection<MediaId>, space: EmbeddingSpace): Map<MediaId, List<FloatArray>> {
    calls++
    if (space != SpaceCode.CURRENT.space) return emptyMap()
    return ids.mapNotNull { id -> vectors[id]?.let { id to it } }.toMap()
  }

  override suspend fun texts(ids: Collection<MediaId>): Map<MediaId, String> {
    calls++
    return ids.mapNotNull { id -> texts[id]?.let { id to it } }.toMap()
  }

  override suspend fun hidden(ids: Collection<MediaId>): Set<MediaId> = ids.filterTo(HashSet()) { it in hiddenIds }
}

/** Una data fissa (D39: mai l'orologio del telefono nei test). */
class FixedToday(var today: LocalDate = LocalDate.of(2026, 10, 21)) : Today {
  override fun date(): LocalDate = today
}

/**
 * Un analizzatore **semplice** per i test di chi non e' P7 (il vero, con date relative, feste, stagioni, refusi, e' di P7): virgolette
 * = testo esatto, alcuni tipi ("video", "screenshot", "documenti", "preferiti"), un anno a quattro cifre, un luogo di una parola dal
 * dizionario; il resto e' contenuto e parole. Deterministico e puro.
 */
class SimpleQueryParser : QueryParser {
  override fun parse(text: String, today: LocalDate, places: PlaceDictionary): ParsedQuery {
    val input = text.take(QueryParser.MAX_QUERY_CHARS).trim()
    if (input.length <= 1) return ParsedQuery(emptyList(), null, emptyList(), emptyList())
    val chips = mutableListOf<SearchChip>()
    val exact = QUOTED.findAll(input).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
    exact.forEach { chips += SearchChip.Word(it, exact = true) }
    val rest = mutableListOf<String>()
    for (word in SearchText.words(input.replace(QUOTED, " "))) {
      val chip = typeChip(word) ?: yearChip(word) ?: placeChip(word, places)
      if (chip != null) chips += chip else rest += word
    }
    // Il contenuto tiene le parole vuote (il modello le usa); senza nessuna parola piena non c'e' ricerca per contenuto.
    val semantic = rest.takeIf { r -> r.any { it !in SearchText.STOPWORDS } }?.joinToString(" ")
    val words = rest.filter { it !in SearchText.STOPWORDS }
    return ParsedQuery(chips, semantic, words, exact)
  }

  private fun typeChip(word: String): SearchChip? = when (word) {
    "video" -> SearchChip.MediaType(SearchMediaType.VIDEO, "Video")
    "screenshot", "schermate" -> SearchChip.MediaType(SearchMediaType.SCREENSHOT, "Screenshot")
    "documenti" -> SearchChip.MediaType(SearchMediaType.DOCUMENT, "Documenti")
    "preferiti" -> SearchChip.Flag(SearchFlag.FAVORITE, "Preferiti")
    "whatsapp" -> SearchChip.Source(MediaSource.WHATSAPP, "WhatsApp")
    else -> null
  }

  private fun yearChip(word: String): SearchChip? {
    val year = word.toIntOrNull()?.takeIf { word.length == 4 && it in 1900..2100 } ?: return null
    return SearchChip.DateRange(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), everyYear = false, label = "$year")
  }

  private fun placeChip(word: String, places: PlaceDictionary): SearchChip? {
    val found = places.lookup(word)
    val first = found.firstOrNull() ?: return null
    return SearchChip.Place(first.option, found.drop(1).map { it.option }, word)
  }

  private companion object {
    val QUOTED = Regex("[\"“”«»]([^\"“”«»]+)[\"“”«»]")
  }
}

/** La cronologia in memoria (D32): al massimo 50 voci, dalla piu' recente, la stessa ricerca una volta sola. */
class InMemorySearchHistory : SearchHistory {
  override val entries: MutableStateFlow<List<HistoryEntry>> = MutableStateFlow(emptyList())
  private var nextId = 1L

  override suspend fun record(text: String, chips: List<SearchChip>) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return
    val entry = HistoryEntry(nextId++, trimmed, chips.map { it.label })
    entries.value = (listOf(entry) + entries.value.filter { it.text != trimmed }).take(SearchHistory.MAX_ENTRIES)
  }

  override suspend fun delete(id: Long) {
    entries.value = entries.value.filter { it.id != id }
  }

  override suspend fun clear() {
    entries.value = emptyList()
  }

  override suspend fun purge(names: Collection<String>): Int {
    val wanted = HistoryNames.wanted(names)
    val before = entries.value.size
    entries.value = entries.value.filterNot { HistoryNames.contains(it.text, it.chipLabels, wanted) }
    return before - entries.value.size
  }
}

/** Il testo letto finto per il testo dal vivo (P12): righe scelte dal test per media. */
class FakeMediaText : MediaText {
  val layouts: MutableMap<MediaId, TextLayout> = HashMap()

  override suspend fun layout(media: MediaId): TextLayout? = layouts[media]
}
