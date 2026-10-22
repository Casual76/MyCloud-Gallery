package dev.pampa.mycloudgallery.debugtools.eval

import java.text.Normalizer
import java.util.Locale

// L'ERRORE PER CARATTERE DEL SET OCR SINTETICO sul telefono (§5.3, P9): la corsa di debug "Lettura di prova" legge le immagini generate da
// `tools/fixtures/generate-ocr.ps1` con il lettore vero, scrive `letto.tsv` e questi conti danno gli stessi numeri del resoconto del PC
// (`OcrErrorRate.java`: stessa definizione). Stesso file in core-search (test) e in :app (debug).

/** Una riga del manifesto del set OCR: il testo disegnato e le condizioni (larghezza e altezza del carattere in px, degradazione, genere). */
class OcrCase(val id: String, val genre: String, val width: Int, val px: Int, val kind: String, val truth: String) {
  override fun toString(): String = "OcrCase($id)"
}

/** Errore per carattere e ricerca per parola di un gruppo di immagini (somme: si compongono pesando per i caratteri). */
class OcrScore(val edits: Int, val expectedChars: Int, val wordsFound: Int, val wordsExpected: Int) {
  fun plus(o: OcrScore) = OcrScore(edits + o.edits, expectedChars + o.expectedChars, wordsFound + o.wordsFound, wordsExpected + o.wordsExpected)

  val characterErrorRate: Double get() = if (expectedChars == 0) Double.NaN else edits.toDouble() / expectedChars
  val wordRecall: Double get() = if (wordsExpected == 0) Double.NaN else wordsFound.toDouble() / wordsExpected

  companion object {
    val ZERO = OcrScore(0, 0, 0, 0)
  }
}

object EvalOcr {
  private val SPACES = Regex("\\s+")
  private val MARKS = Regex("\\p{M}+")
  private val NON_WORD = Regex("[^a-z0-9]+")

  /** Il manifesto: `id, file, genere, larghezza, px, degradazione, testo` (la prima riga e' l'intestazione). */
  fun parseManifest(text: String): List<OcrCase> =
    text.split('\n').map { it.trimEnd('\r') }.drop(1).filter { it.isNotEmpty() }.map { line ->
      val c = line.split('\t')
      require(c.size >= 7) { "Riga del manifesto OCR non valida." }
      OcrCase(c[0], c[2], c[3].toInt(), c[4].toInt(), c[5], unescape(c[6]))
    }

  /** Il testo letto: `id<TAB>testo`; un'immagine senza riga e' "non letta". */
  fun parseRead(text: String): Map<String, String> = text.split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }.associate { line ->
    val tab = line.indexOf('\t')
    if (tab < 0) line to "" else line.substring(0, tab) to unescape(line.substring(tab + 1))
  }

  fun formatRead(id: String, read: String): String = id + "\t" + escape(read) + "\n"

  fun score(truth: String, read: String): OcrScore {
    val a = collapse(truth)
    val b = collapse(read)
    val edits = if (a.isEmpty()) b.length else levenshtein(a, b)
    val expected = words(truth).filter { it.length >= 3 }
    val present = words(read).toHashSet()
    return OcrScore(edits, a.length, expected.count { it in present }, expected.size)
  }

  /** Il resoconto in numeri: per altezza (immagini pulite, per larghezza), per degradazione e per genere. Mai testo. */
  fun report(cases: List<OcrCase>, read: Map<String, String>, reader: String): String {
    fun group(key: (OcrCase) -> String?): Map<String, OcrScore> {
      val out = java.util.TreeMap<String, OcrScore>()
      for (c in cases) {
        val k = key(c) ?: continue
        out[k] = (out[k] ?: OcrScore.ZERO).plus(score(c.truth, read[c.id].orEmpty()))
      }
      return out
    }
    val sb = StringBuilder("Lettore: $reader\nImmagini: ${cases.size}, senza lettura: ${cases.count { it.id !in read }}\n\n")
    fun table(title: String, rows: Map<String, OcrScore>) {
      sb.append(title).append("\ncondizione | errore per carattere | parole ritrovate\n---|---|---\n")
      for ((k, s) in rows) sb.append("$k | ${EvalReport.pct(s.characterErrorRate)} | ${EvalReport.pct(s.wordRecall)}\n")
      sb.append('\n')
    }
    table("Pulite: larghezza e altezza del carattere", group { if (it.kind == "pulita") "${it.width} px ${"%02d".format(it.px)}" else null })
    table("Degradazione (12, 20 e 32 px)", group { if (it.px == 12 || it.px == 20 || it.px == 32) "${it.width} ${it.kind}" else null })
    table("Genere", group { it.genre })
    return sb.toString()
  }

  private fun collapse(s: String) = s.replace(SPACES, " ").trim()

  private fun levenshtein(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
      cur[0] = i
      for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
      val t = prev
      prev = cur
      cur = t
    }
    return prev[b.length]
  }

  /** Come `SearchText.normalize`: "€" -> "euro", minuscolo, senza accenti, il resto separa. */
  fun normalize(text: String): String =
    Normalizer.normalize(text.replace("€", " euro ").lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(MARKS, "").replace(NON_WORD, " ").trim().replace(Regex(" +"), " ")

  private fun words(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() }

  private fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")

  private fun unescape(s: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
      if (s[i] == '\\' && i + 1 < s.length) {
        i++
        out.append(if (s[i] == 'n') '\n' else if (s[i] == 't') '\t' else s[i])
      } else {
        out.append(s[i])
      }
      i++
    }
    return out.toString()
  }
}
