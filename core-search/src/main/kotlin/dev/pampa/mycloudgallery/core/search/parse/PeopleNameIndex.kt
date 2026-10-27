package dev.pampa.mycloudgallery.core.search.parse

import dev.pampa.mycloudgallery.core.search.contract.DateMediaCount
import dev.pampa.mycloudgallery.core.search.contract.PeopleDictionary
import dev.pampa.mycloudgallery.core.search.contract.PersonEntry
import dev.pampa.mycloudgallery.core.search.contract.SearchText

// L'indice dei NOMI DELLE PERSONE (Fase 8, P10, D26, §2.7): costruito una volta per dizionario (il parser lo ricorda), cosi' una query non
// scandisce mai tutte le voci. Come l'indice dei luoghi: corrispondenza piu' lunga, omonimi dal piu' frequente (poi l'id minore), refusi solo
// sui nomi di almeno 5 lettere e solo se il migliore e' unico. Nomi e id non escono mai in un messaggio (D37): `toString()` senza valori.

/** Un nome trovato: quante parole copre, la persona (fra omonimi la piu' frequente) e, per un refuso, (cercato, scritto). */
internal class PersonHit(val len: Int, val entry: PersonEntry, val correction: Pair<String, String>?) {
  override fun toString(): String = "PersonHit($len)"
}

internal class PeopleNameIndex(dictionary: PeopleDictionary) {
  private val byName = HashMap<String, PersonEntry>()
  private val words = HashSet<String>()
  private val buckets = HashMap<Long, MutableList<String>>()
  private var maxWords = 1

  /** Il peso delle feste e dei mesi, quando un nome e' anche una data (E-F36). */
  val dates: DateMediaCount = dictionary.dates

  init {
    val top = compareByDescending<PersonEntry> { it.mediaCount }.thenBy { it.id }
    val grouped = LinkedHashMap<String, MutableList<PersonEntry>>()
    for (entry in dictionary.entries) {
      val name = SearchText.normalize(entry.name)
      val parts = name.split(' ')
      // Un nome che e' solo una parola vuota ("e", "di") non si puo' riconoscere: e' la colla della frase.
      if (name.isEmpty() || (parts.size == 1 && name in SearchText.STOPWORDS)) continue
      grouped.getOrPut(name) { ArrayList() } += entry
    }
    for ((name, list) in grouped) {
      byName[name] = list.sortedWith(top).first()
      val parts = name.split(' ')
      words.addAll(parts)
      maxWords = maxOf(maxWords, parts.size)
      buckets.getOrPut(key(parts.size, name.length)) { ArrayList() } += name
    }
  }

  val isEmpty: Boolean get() = byName.isEmpty()

  private fun key(wordCount: Int, length: Int): Long = wordCount.toLong() * 1000 + length

  /** Vero se [word] compare in un nome: allora non e' un refuso di una parola del calendario ("Maggi" non e' maggio). */
  fun hasWord(word: String): Boolean = word in words

  /** La corrispondenza esatta piu' lunga che comincia al token [i]: i nomi di piu' parole prima di quelli di una. */
  fun exact(tokens: List<Token>, i: Int): PersonHit? {
    if (isEmpty) return null
    val run = wordRun(tokens, i, maxWords)
    for (n in run downTo 1) {
      val entry = byName[join(tokens, i, n)] ?: continue
      return PersonHit(n, entry, null)
    }
    return null
  }

  /**
   * Il nome che il token [i] (e i seguenti) intendeva, se unico (E-Q5). Un nome e' una cosa che l'utente ha scritto lui: un refuso si corregge solo se
   * non puo' essere un altro nome ("Maria" non e' un refuso di "Marina", "Chiaro" non e' un refuso di "Chiara"). Quindi: da cinque lettere, la stessa
   * iniziale, una sola modifica, mai solo l'ultima lettera, mai una parola delle regole o del dizionario dei luoghi ([isKnownWord]), e la modifica e' una
   * lettera scambiata con la vicina, una lettera raddoppiata o tolta dal raddoppio, una "k" per una "c" (o il contrario), oppure -- da sette lettere in
   * su -- una lettera qualunque. La correzione e' (nome cercato, scritto).
   */
  fun fuzzy(tokens: List<Token>, i: Int, isKnownWord: (String) -> Boolean): PersonHit? {
    if (isEmpty) return null
    val run = wordRun(tokens, i, minOf(maxWords, 3))
    for (n in run downTo 1) {
      val typed = join(tokens, i, n)
      if (n == 1 && (QueryVocabulary.isProtected(typed) || isKnownWord(typed))) continue
      if (n > 1 && tokens[i].typed in SearchText.STOPWORDS) continue
      val best = typoOf(typed, n) ?: continue
      val entry = byName.getValue(best)
      return PersonHit(n, entry, entry.label to typed)
    }
    return null
  }

  /**
   * Vero se la parola [word] e' un refuso di un nome del dizionario: allora non e' un refuso di un mese o di una festa ("Marko" e' "Marco", non "marzo"; il
   * nome, che l'utente ha scelto, e' la lettura piu' probabile).
   */
  fun looksLikeName(word: String): Boolean = !isEmpty && !QueryVocabulary.isProtected(word) && typoOf(word, 1) != null

  /** L'unico nome di [n] parole di cui [typed] e' un refuso, o null (nessuno, piu' di uno, meno di cinque lettere). */
  private fun typoOf(typed: String, n: Int): String? {
    if (typed.length < 5) return null
    var best: String? = null
    for (len in typed.length - 1..typed.length + 1) {
      for (name in buckets[key(n, len)].orEmpty()) {
        if (name[0] != typed[0] || !isTypo(typed, name)) continue
        if (best != null && name != best) return null
        best = name
      }
    }
    return best
  }

  /** [typed] e' [name] scritto con un solo errore di battitura che non da' un altro nome (vedi [fuzzy]). */
  private fun isTypo(typed: String, name: String): Boolean {
    if (typed == name || QueryVocabulary.distance(typed, name, 1) > 1) return false
    // Cambiare solo l'ultima lettera da' un'altra parola italiana ("Chiara" e "chiaro", "Paolo" e "Paola"): non e' un refuso.
    if (typed.length == name.length && typed.dropLast(1) == name.dropLast(1)) return false
    if (name.length >= LONG_NAME) return true
    return isSwap(typed, name) || isDoubled(typed, name) || isDoubled(name, typed) || isSoundAlike(typed, name)
  }

  /** Una "k" al posto di una "c" o il contrario ("marko" per "marco"): si scrive uguale, non e' un altro nome. */
  private fun isSoundAlike(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    val diff = a.indices.filter { a[it] != b[it] }
    return diff.size == 1 && setOf(a[diff[0]], b[diff[0]]) == K_AND_C
  }

  /** Due lettere vicine scambiate ("giulai" per "giulia"). */
  private fun isSwap(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    val k = a.indices.firstOrNull { a[it] != b[it] } ?: return false
    return k + 1 < a.length && a[k] == b[k + 1] && a[k + 1] == b[k] && a.substring(k + 2) == b.substring(k + 2)
  }

  /** [longer] e' [shorter] con una lettera scritta due volte ("chiarra" per "chiara"). */
  private fun isDoubled(longer: String, shorter: String): Boolean {
    if (longer.length != shorter.length + 1) return false
    for (j in 1 until longer.length) {
      if (longer[j] == longer[j - 1] && longer.removeRange(j, j + 1) == shorter) return true
    }
    return false
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

  override fun toString(): String = "PeopleNameIndex(${byName.size})"

  private companion object {
    val K_AND_C: Set<Char> = setOf('c', 'k')

    /** Da questa lunghezza un nome ha pochi vicini: un errore qualunque si corregge. */
    const val LONG_NAME = 7
  }
}
