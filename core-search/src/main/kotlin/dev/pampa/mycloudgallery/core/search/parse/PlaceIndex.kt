package dev.pampa.mycloudgallery.core.search.parse

import dev.pampa.mycloudgallery.core.search.contract.PlaceDictionary
import dev.pampa.mycloudgallery.core.search.contract.PlaceEntry
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchText

// L'indice del dizionario dei luoghi (P7, §2.5): costruito una volta per dizionario (il parser lo ricorda), cosi' una query non
// scandisce mai tutte le voci. Corrispondenza piu' lunga, omonimi dal piu' frequente, refusi solo sui nomi di almeno 5 lettere.

internal class PlaceHit(val len: Int, val chip: SearchChip.Place, val correction: Pair<String, String>?, val count: Int = 0)

internal class PlaceIndex(dictionary: PlaceDictionary) {
  private val byName = HashMap<String, List<PlaceEntry>>()
  private val words = HashSet<String>()
  private val buckets = HashMap<Long, MutableList<String>>()
  private var maxWords = 1

  init {
    val grouped = LinkedHashMap<String, MutableList<PlaceEntry>>()
    for (entry in dictionary.entries) {
      val name = SearchText.normalize(entry.name)
      if (name.isNotEmpty()) grouped.getOrPut(name) { ArrayList() } += entry
    }
    for ((name, list) in grouped) {
      byName[name] = list.sortedByDescending { it.mediaCount }
      val parts = name.split(' ')
      words.addAll(parts)
      maxWords = maxOf(maxWords, parts.size)
      buckets.getOrPut(key(parts.size, name.length)) { ArrayList() } += name
    }
  }

  private fun key(wordCount: Int, length: Int): Long = wordCount.toLong() * 1000 + length

  /** True se [word] compare in un nome di luogo (allora non e' un refuso di una parola del dizionario). */
  fun hasWord(word: String): Boolean = word in words

  val isEmpty: Boolean get() = byName.isEmpty()

  /** La corrispondenza esatta piu' lunga che comincia al token [i]. */
  fun exact(tokens: List<Token>, i: Int): PlaceHit? {
    if (isEmpty) return null
    val run = wordRun(tokens, i, maxWords)
    for (n in run downTo 1) {
      val name = join(tokens, i, n)
      val entries = byName[name] ?: continue
      return hit(entries, n, name, null)
    }
    return null
  }

  /** Il luogo che il token [i] (e i seguenti) intendeva, se unico e nella tolleranza (E-Q5). */
  fun fuzzy(tokens: List<Token>, i: Int): PlaceHit? {
    if (isEmpty) return null
    val run = wordRun(tokens, i, 3)
    for (n in run downTo 1) {
      val typed = join(tokens, i, n)
      if (typed.length < 5) continue
      if (n == 1 && QueryVocabulary.isProtected(typed)) continue
      if (n > 1 && tokens[i].typed in SearchText.STOPWORDS) continue
      val tol = QueryVocabulary.tolerance(typed.length)
      var best: String? = null
      var bestDistance = Int.MAX_VALUE
      var tie = false
      for (len in typed.length - tol..typed.length + tol) {
        for (name in buckets[key(n, len)].orEmpty()) {
          val d = QueryVocabulary.distance(typed, name, tol)
          if (d > tol) continue
          if (d < bestDistance) {
            best = name; bestDistance = d; tie = false
          } else if (d == bestDistance && name != best) tie = true
        }
      }
      if (best != null && !tie) return hit(byName.getValue(best), n, typed, best to typed)
    }
    return null
  }

  private fun hit(entries: List<PlaceEntry>, n: Int, word: String, correction: Pair<String, String>?): PlaceHit {
    val option = entries.first().option
    val others = entries.drop(1).map { it.option }.filter { it != option }.distinct()
    return PlaceHit(n, SearchChip.Place(option, others, word), correction, entries.first().mediaCount)
  }

  private fun wordRun(tokens: List<Token>, i: Int, max: Int): Int {
    var n = 0
    while (n < max && i + n < tokens.size && tokens[i + n].isWord) n++
    return n
  }

  private fun join(tokens: List<Token>, i: Int, n: Int): String {
    if (n == 1) return tokens[i].typed
    val sb = StringBuilder()
    for (k in 0 until n) {
      if (k > 0) sb.append(' ')
      sb.append(tokens[i + k].typed)
    }
    return sb.toString()
  }
}
