package dev.pampa.mycloudgallery.core.search.parse

import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

// Il tokenizzatore delle query (P7): il testo si "piega" carattere per carattere (minuscolo, senza accenti: la lunghezza non cambia,
// cosi' ogni token ricorda il suo tratto nel testo originale) e si spezza in parole, numeri e date numeriche (12/8, 8/2023).

internal enum class TokenKind { WORD, NUMBER, NUMDATE, BARRIER }

internal class Token(
  val kind: TokenKind,
  /** La parola (anche corretta da un refuso); per i numeri le cifre. */
  var word: String,
  val start: Int,
  val end: Int,
  /** Il testo piegato com'era scritto (prima delle correzioni). */
  val typed: String = word,
  /** NUMBER: il valore (-1 se troppo lungo). NUMDATE: il giorno (0 = mese e anno). */
  val num: Int = -1,
  val month: Int = 0,
  /** NUMDATE: l'anno, o -1 se non c'e'. */
  val year: Int = -1,
) {
  val isWord: Boolean get() = kind == TokenKind.WORD
}

internal object QueryTokenizer {
  private val FOLD_CACHE = ConcurrentHashMap<Char, Char>()

  /** Un carattere -> un carattere: minuscolo e senza accento ("è" -> "e"); gli apostrofi e il resto diventano separatori. */
  fun foldChar(c: Char): Char {
    if (c in 'a'..'z' || c in '0'..'9') return c
    if (c in 'A'..'Z') return c + 32
    if (c.code < 128) return c
    return FOLD_CACHE.getOrPut(c) {
      val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
      val first = base.firstOrNull() ?: c
      first.lowercaseChar()
    }
  }

  fun fold(text: String): String {
    val out = CharArray(text.length)
    for (i in text.indices) out[i] = foldChar(text[i])
    return String(out)
  }

  /** [folded] ha gia' le parti fra virgolette sostituite da `\u0000` (diventano una barriera: nessuna regola le attraversa). */
  fun tokenize(folded: String): List<Token> {
    val out = ArrayList<Token>()
    var i = 0
    val n = folded.length
    while (i < n) {
      val c = folded[i]
      when {
        c == '\u0000' -> {
          var j = i
          while (j < n && folded[j] == '\u0000') j++
          out += Token(TokenKind.BARRIER, "", i, j)
          i = j
        }
        c.isLetter() -> {
          var j = i
          while (j < n && folded[j].isLetter()) j++
          out += Token(TokenKind.WORD, folded.substring(i, j), i, j)
          i = j
        }
        c in '0'..'9' -> {
          val date = numericDate(folded, i)
          if (date != null) {
            out += date
            i = date.end
          } else {
            var j = i
            while (j < n && folded[j] in '0'..'9') j++
            val digits = folded.substring(i, j)
            out += Token(TokenKind.NUMBER, digits, i, j, num = if (digits.length <= 4) digits.toInt() else -1)
            i = j
          }
        }
        else -> i++
      }
    }
    return out
  }

  private fun digitRun(s: String, from: Int): Int {
    var j = from
    while (j < s.length && s[j] in '0'..'9') j++
    return j
  }

  /** `g/m`, `g/m/aaaa`, `g-m-aa`, `g.m.aaaa`, `m/aaaa`; null se non e' una data numerica plausibile. */
  private fun numericDate(s: String, start: Int): Token? {
    val e1 = digitRun(s, start)
    val len1 = e1 - start
    if (len1 > 4 || e1 >= s.length) return null
    val sep = s[e1]
    if (sep != '/' && sep != '-' && sep != '.') return null
    val e2 = digitRun(s, e1 + 1)
    val len2 = e2 - (e1 + 1)
    if (len2 == 0 || len2 > 4) return null
    val a = s.substring(start, e1).toInt()
    val b = s.substring(e1 + 1, e2).toInt()
    var end = e2
    var third = -1
    if (e2 < s.length && s[e2] == sep) {
      val e3 = digitRun(s, e2 + 1)
      val len3 = e3 - (e2 + 1)
      if (len3 == 2 || len3 == 4) {
        third = s.substring(e2 + 1, e3).toInt()
        end = e3
        if (len3 == 2) third = expandYear(third)
      }
    }
    // Un numero attaccato ad altre cifre o lettere non e' una data.
    if (end < s.length && s[end].isLetterOrDigit()) return null
    return when {
      len1 <= 2 && len2 <= 2 && a in 1..31 && b in 1..12 && (sep != '.' || third != -1) ->
        Token(TokenKind.NUMDATE, s.substring(start, end), start, end, num = a, month = b, year = third)
      len1 <= 2 && len2 == 4 && third == -1 && a in 1..12 && b in 1900..2100 && sep != '.' ->
        Token(TokenKind.NUMDATE, s.substring(start, end), start, end, num = 0, month = a, year = b)
      else -> null
    }
  }

  /** Anno a due cifre: 00-69 = 2000-2069, 70-99 = 1970-1999 (regola fissa: niente dipendenza da "oggi"). */
  fun expandYear(yy: Int): Int = if (yy <= 69) 2000 + yy else 1900 + yy
}
