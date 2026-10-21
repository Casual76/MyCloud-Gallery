package dev.pampa.mycloudgallery.core.search.index

import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.search.contract.SearchText
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * I REFUSI sul vocabolario locale delle parole lette (D25, metodo a cancellazioni): distanza 1 per le parole di 4-7 lettere, 2 da 8 in
 * su, niente sotto le 4. L'indice tiene per ogni parola lei stessa e le sue cancellazioni di una lettera (come impronte intere, per la
 * memoria); la query aggiunge le sue cancellazioni di due lettere per le parole lunghe; una distanza di Damerau-Levenshtein vera decide.
 * Due sostituzioni nella stessa parola lunga non si trovano sempre: scelta dichiarata (costa meta' della memoria).
 *
 * Il vocabolario contiene **anche le parole dei nascosti** (P1): queste correzioni non si mostrano mai da sole, solo se trovano
 * qualcosa fra le celle visibili ([TextSearch]).
 */
internal class TypoIndex {
  private val words = ArrayList<String>()
  private val docs = ArrayList<Int>()
  private val known = HashMap<String, Int>()
  private val variants = HashMap<Int, IntArray>()

  val size: Int get() = words.size

  fun add(word: String, count: Int = 1) {
    if (word.length !in MIN_LENGTH..MAX_LENGTH) return
    val id = known[word]
    if (id != null) {
      docs[id] = docs[id] + count
      return
    }
    val newId = words.size
    words += word
    docs += count
    known[word] = newId
    put(word.hashCode(), newId)
    deletes(word).forEach { put(it.hashCode(), newId) }
  }

  /** Le parole del vocabolario entro la distanza ammessa per [word] (esclusa lei stessa), dalla piu' vicina e frequente. */
  fun corrections(word: String, limit: Int = 3): List<String> {
    if (word.length < MIN_LENGTH || word.length > MAX_LENGTH) return emptyList()
    val max = if (word.length >= 8) 2 else 1
    val probes = HashSet<String>()
    probes += word
    val first = deletes(word)
    probes += first
    if (max == 2) first.forEach { probes += deletes(it) }
    val found = HashSet<Int>()
    for (p in probes) variants[p.hashCode()]?.let { ids -> ids.forEach { found += it } }
    return found.asSequence().map { it to words[it] }.filter { (_, w) -> w != word }
      .map { (id, w) -> Triple(w, distance(word, w), docs[id]) }
      .filter { it.second <= max }
      .sortedWith(compareBy<Triple<String, Int, Int>> { it.second }.thenByDescending { it.third }.thenBy { it.first })
      .take(limit).map { it.first }.toList()
  }

  private fun put(hash: Int, id: Int) {
    val old = variants[hash]
    if (old == null) {
      variants[hash] = intArrayOf(id)
    } else if (old.last() != id) {
      variants[hash] = old + id
    }
  }

  private fun deletes(w: String): Set<String> = (w.indices).mapTo(HashSet()) { w.removeRange(it, it + 1) }

  companion object {
    const val MIN_LENGTH: Int = 4
    const val MAX_LENGTH: Int = 64

    /** Damerau-Levenshtein (trasposizioni adiacenti). */
    fun distance(a: String, b: String): Int {
      val d = Array(a.length + 1) { IntArray(b.length + 1) }
      for (i in 0..a.length) d[i][0] = i
      for (j in 0..b.length) d[0][j] = j
      for (i in 1..a.length) for (j in 1..b.length) {
        val cost = if (a[i - 1] == b[j - 1]) 0 else 1
        d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
        if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
      }
      return d[a.length][b.length]
    }
  }
}

/** Il risultato del testo per una query: le parole esatte e con i refusi per chiave, le frasi complete, le correzioni proposte. */
internal class TextHits(
  val terms: Int,
  val exact: Map<ContentKey, Int>,
  val loose: Map<ContentKey, Int>,
  val phrases: Set<ContentKey>?,
  val corrections: List<Pair<String, Set<ContentKey>>>,
  val corrected: List<Pair<String, String>>,
) {
  /**
   * Il punteggio del testo di una chiave (D27): 1 = tutte le parole lette **esatte** (e le frasi), 0,6 = almeno meta' anche con un
   * refuso, 0 = niente. Le frasi fra virgolette sono obbligatorie.
   */
  fun score(key: ContentKey): Float {
    if (phrases != null && key !in phrases) return 0f
    if (terms == 0) return 1f
    if ((exact[key] ?: 0) == terms) return 1f
    val loose = loose[key] ?: exact[key] ?: 0
    return if (loose > 0 && loose * 2 >= terms) 0.6f else 0f
  }
}

/** La ricerca nel testo letto (D25) sopra `ai_text` e l'FTS4 di P1 ([TextRowStore]): termini sempre letterali (E-Q9). */
internal class TextSearch(private val store: TextRowStore) {
  private val mutex = Mutex()
  private val typos = TypoIndex()
  private var loaded = false
  private val learned = ConcurrentLinkedQueue<String>()

  /** Le parole di un testo appena salvato entrano nel vocabolario dei refusi (senza rileggerlo tutto). */
  fun learn(normalizedText: String) {
    learned += normalizedText
  }

  suspend fun hits(words: List<String>, exact: List<String>): TextHits? {
    val wanted = words.map { SearchText.normalize(it) }.filter { it.isNotEmpty() }.distinct().take(MAX_TERMS)
    val phrases = exact.map { SearchText.normalize(it) }.filter { it.isNotEmpty() }.take(MAX_TERMS)
    if (wanted.isEmpty() && phrases.isEmpty()) return null
    val phraseKeys = if (phrases.isEmpty()) null else store.matches(phrases.map { listOf(it) }).all
    if (wanted.isEmpty()) return TextHits(0, emptyMap(), emptyMap(), phraseKeys, emptyList(), emptyList())
    val exactHits = store.matches(wanted.map { listOf(it) }).hits
    val counts = store.wordCounts(wanted)
    val unknown = wanted.filter { (counts[it] ?: 0) == 0 }
    if (unknown.isEmpty()) return TextHits(wanted.size, exactHits, exactHits, phraseKeys, emptyList(), emptyList())
    val fixes = vocabulary { typos -> unknown.associateWith { typos.corrections(it) } }
    if (fixes.values.all { it.isEmpty() }) return TextHits(wanted.size, exactHits, exactHits, phraseKeys, emptyList(), emptyList())
    val loose = store.matches(wanted.map { w -> listOf(w) + fixes[w].orEmpty() }).hits
    val corrections = fixes.flatMap { (w, list) -> list.map { c -> "$w>$c" to store.matches(listOf(listOf(c))).all } }
    val corrected = fixes.flatMap { (w, list) -> list.map { w to it } }
    return TextHits(wanted.size, exactHits, loose, phraseKeys, corrections, corrected)
  }

  private suspend fun <T> vocabulary(block: (TypoIndex) -> T): T = mutex.withLock {
    if (!loaded) {
      var after = ""
      while (true) {
        val page = store.vocabularyPage(after, VOCABULARY_PAGE)
        page.forEach { typos.add(it.word, it.docs) }
        if (page.size < VOCABULARY_PAGE) break
        after = page.last().word
      }
      loaded = true
      learned.clear()
    }
    while (true) {
      val text = learned.poll() ?: break
      text.split(' ').toSet().forEach { typos.add(it) }
    }
    block(typos)
  }

  private companion object {
    const val MAX_TERMS = 16
    const val VOCABULARY_PAGE = 2_000
  }
}
