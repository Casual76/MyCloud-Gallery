package dev.pampa.mycloudgallery.core.search.parse

import dev.pampa.mycloudgallery.core.search.contract.SearchText
import java.time.DayOfWeek

// Il vocabolario dell'analizzatore (P7, §2.5): le parole che le regole conoscono, le parole "colla" (preposizioni e articoli che
// escono insieme al chip che precedono) e i refusi (E-Q5: distanza 1 da 4 a 7 lettere, 2 da 8 in su, solo se il migliore e' unico).

internal object QueryVocabulary {
  val MONTH_WORDS: Map<String, Int> = buildMap {
    ItalianCalendar.MONTHS.forEachIndexed { i, m -> put(m, i + 1) }
    listOf("gen", "feb", "mar", "apr", "mag", "giu", "lug", "ago", "set", "sett", "ott", "nov", "dic").forEachIndexed { i, m ->
      put(m, if (m == "sett") 9 else if (i < 9) i + 1 else i)
    }
  }

  val WEEKDAYS: Map<String, DayOfWeek> = mapOf(
    "lunedi" to DayOfWeek.MONDAY, "martedi" to DayOfWeek.TUESDAY, "mercoledi" to DayOfWeek.WEDNESDAY,
    "giovedi" to DayOfWeek.THURSDAY, "venerdi" to DayOfWeek.FRIDAY, "sabato" to DayOfWeek.SATURDAY, "domenica" to DayOfWeek.SUNDAY,
  )

  /** Stagione -> primo mese (meteorologiche: primavera 3-5, estate 6-8, autunno 9-11, inverno 12-2). */
  val SEASONS: Map<String, Int> = mapOf("primavera" to 3, "estate" to 6, "autunno" to 9, "inverno" to 12)

  val SCORSO: Set<String> = setOf("scorso", "scorsa", "passato", "passata")
  val QUESTO: Set<String> = setOf("questo", "questa", "quest")

  val NUMBER_WORDS: Map<String, Int> = mapOf(
    "un" to 1, "uno" to 1, "una" to 1, "due" to 2, "tre" to 3, "quattro" to 4, "cinque" to 5, "sei" to 6, "sette" to 7,
    "otto" to 8, "nove" to 9, "dieci" to 10, "undici" to 11, "dodici" to 12, "tredici" to 13, "quattordici" to 14,
    "quindici" to 15, "venti" to 20,
  )

  /** "anni novanta": parola -> decina (10, 20, ... 90; "duemila" = 0 -> 2000). */
  val DECADE_WORDS: Map<String, Int> = mapOf(
    "dieci" to 10, "venti" to 20, "trenta" to 30, "quaranta" to 40, "cinquanta" to 50, "sessanta" to 60, "settanta" to 70,
    "ottanta" to 80, "novanta" to 90, "duemila" to 0,
  )

  /** Preposizioni e articoli che escono con il chip che segue ("a Firenze", "nel 2023", "di ieri", "i preferiti"). */
  val GLUE: Set<String> = setOf(
    "a", "ad", "al", "allo", "alla", "all", "ai", "agli", "alle", "in", "nel", "nello", "nella", "nell", "nei", "negli", "nelle",
    "da", "dal", "dallo", "dalla", "dall", "dai", "dagli", "dalle", "di", "del", "dello", "della", "dell", "dei", "degli", "delle",
    "su", "sul", "sullo", "sulla", "sull", "sui", "sugli", "sulle", "per", "tra", "fra", "il", "lo", "la", "l", "i", "gli", "le",
  )

  /** Fase 8 (§2.7): "Marco e Anna", "Marco con Anna" = tutte le persone; "Marco o Anna" = una qualunque. Senza congiunzione: tutte. */
  val AND_WORDS: Set<String> = setOf("e", "ed", "con")
  val OR_WORDS: Set<String> = setOf("o", "od", "oppure")

  /** Le parole che le regole usano: mai corrette come refusi. */
  private val RULE_WORDS: Set<String> = buildSet {
    addAll(MONTH_WORDS.keys); addAll(WEEKDAYS.keys); addAll(SEASONS.keys); addAll(SCORSO); addAll(QUESTO); addAll(NUMBER_WORDS.keys)
    addAll(DECADE_WORDS.keys); addAll(GLUE)
    addAll(
      listOf(
        "oggi", "ieri", "altro", "avantieri", "settimana", "mese", "anno", "anni", "giorno", "giorni", "settimane", "mesi", "fa",
        "weekend", "fine", "natale", "vigilia", "santo", "san", "s", "stefano", "valentino", "capodanno", "epifania", "pasqua",
        "pasquetta", "ferragosto", "foto", "fotografia", "fotografie", "video", "filmato", "filmati", "screenshot", "screenshots",
        "schermata", "schermate", "panorami", "panoramica", "panoramiche", "movimento", "animate", "motion", "photo", "documenti",
        "documento", "whatsapp", "quick", "share", "quickshare", "preferiti", "preferite", "preferito", "preferita", "modificate",
        "modificati", "modificata", "modificato", "telefono", "nas", "solo", "soltanto", "primo", "e", "ed", "od", "oppure",
      ),
    )
  }

  /** Parole comuni (nomi, cose) a un refuso di distanza dalle parole del dizionario: mai corrette ("Mario" non e' marzo). */
  private val NEVER_CORRECT: Set<String> = setOf(
    "ogni", "mare", "marco", "mario", "mazzo", "vedi", "state", "gennaro", "domenico", "corso", "panorama", "inferno", "natali",
    "sabati", "festa", "fieri", "estati", "maggior", "aprire",
  )

  /** Il dizionario dei refusi: mesi, giorni, stagioni, feste, tipi, sorgenti e stati (non "foto": e' una parola vuota). */
  private val TARGETS: List<String> = buildList {
    addAll(ItalianCalendar.MONTHS); addAll(WEEKDAYS.keys); addAll(SEASONS.keys)
    addAll(
      listOf(
        "oggi", "ieri", "natale", "capodanno", "epifania", "pasqua", "pasquetta", "ferragosto", "vigilia", "video", "screenshot",
        "schermate", "panorami", "documenti", "whatsapp", "preferiti", "modificate",
      ),
    )
  }.distinct()

  fun isRuleWord(word: String): Boolean = word in RULE_WORDS

  /** True per le parole che non vanno mai corrette: le regole, le parole vuote e le parole comuni di [NEVER_CORRECT]. */
  fun isProtected(word: String): Boolean = word in RULE_WORDS || word in NEVER_CORRECT || word in SearchText.STOPWORDS

  /** La tolleranza di un refuso in base alle lettere scritte: 0 sotto le 4, 1 da 4 a 7, 2 da 8 in su. */
  fun tolerance(length: Int): Int = if (length < 4) 0 else if (length < 8) 1 else 2

  /** La parola del dizionario che [word] intendeva, se il migliore e' unico e nella tolleranza; altrimenti null. */
  fun correct(word: String): String? {
    val tol = tolerance(word.length)
    if (tol == 0 || isProtected(word)) return null
    var best: String? = null
    var bestDistance = Int.MAX_VALUE
    var tie = false
    for (target in TARGETS) {
      if (kotlin.math.abs(target.length - word.length) > tol) continue
      val d = distance(word, target, tol)
      if (d > tol) continue
      if (d < bestDistance) {
        best = target; bestDistance = d; tie = false
      } else if (d == bestDistance) tie = true
    }
    return if (tie) null else best
  }

  /** Distanza di Damerau-Levenshtein (scambio di due lettere = 1); [limit] + 1 se supera il limite. */
  fun distance(a: String, b: String, limit: Int): Int {
    val n = a.length
    val m = b.length
    if (kotlin.math.abs(n - m) > limit) return limit + 1
    var prev2 = IntArray(m + 1)
    var prev = IntArray(m + 1) { it }
    var cur = IntArray(m + 1)
    for (i in 1..n) {
      cur[0] = i
      var rowMin = cur[0]
      for (j in 1..m) {
        val cost = if (a[i - 1] == b[j - 1]) 0 else 1
        var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
        cur[j] = v
        if (v < rowMin) rowMin = v
      }
      if (rowMin > limit) return limit + 1
      val t = prev2; prev2 = prev; prev = cur; cur = t
    }
    return prev[m].coerceAtMost(limit + 1)
  }
}
