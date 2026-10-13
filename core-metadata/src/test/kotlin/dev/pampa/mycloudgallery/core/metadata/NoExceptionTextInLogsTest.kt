package dev.pampa.mycloudgallery.core.metadata

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * R4 (docs/review/3-fase3.md): nei messaggi di `GalleryLog` di questo modulo non finisce mai il testo di un'eccezione:
 * `e.message`, `e.localizedMessage`, `e.toString()`, `$e`, `${e}`. Il messaggio di una `IOException` del provider o del
 * disco puo' contenere un URI o un percorso, e C14/C21 vietano percorsi e nomi nei log. E' ammesso solo
 * `e.javaClass.simpleName` (e testo fisso).
 *
 * E' una rete a maglie larghe sul testo dei sorgenti, sul modello di `NoLogSourcesTest` di `:core-places`: non vede un
 * messaggio costruito prima in una variabile. La regola resta quella scritta qui, il test impedisce la distrazione.
 * Passare l'eccezione come argomento `throwable` (`GalleryLog.w(TAG, e) { ... }`) non e' testo del messaggio e non e'
 * vietato da questa regola.
 */
class NoExceptionTextInLogsTest {

  @Test
  fun iLogDiQuestoModuloNonContengonoIlTestoDelleEccezioni() {
    assertThat(violations(File("src/main"))).isEmpty()
  }

  @Test
  fun laBarrieraVedeLeViolazioniEAmmetteIlNomeDellaClasse() {
    val d = "$"
    val bad = listOf(
      "GalleryLog.w(TAG) { \"x: ${d}{e.message}\" }",
      "GalleryLog.w(TAG) { \"x: \" + e.localizedMessage }",
      "GalleryLog.w(TAG) { \"x: ${d}e\" }",
      "GalleryLog.w(TAG) { \"x: ${d}{e}\" }",
      "GalleryLog.w(TAG, e) { \"x: ${d}{e.toString()}\" }",
      "GalleryLog.d(TAG) { \"x: \" + e }",
      "GalleryLog.d(TAG) { e.toString() + \"x\" }",
      "GalleryLog.e(TAG, cause) { \"boom: ${d}{cause.stackTraceToString()}\" }",
      "GalleryLog.i(TAG) {\n  \"multilinea\" +\n    it.message\n}",
      "try { f() } catch (failure: Exception) {\n  GalleryLog.w(TAG) { \"x: ${d}failure\" }\n}",
      "runCatching { f() }.onFailure { problem ->\n  GalleryLog.w(TAG) { \"x: \" + problem.toString() }\n}",
    )
    for (source in bad) assertThat(violationsIn(source)).isNotEmpty()

    val good = listOf(
      "GalleryLog.w(TAG) { \"x: ${d}{e.javaClass.simpleName}\" }",
      "GalleryLog.w(TAG, e) { \"Istantanea non scritta.\" }",
      "GalleryLog.d(TAG) { \"kind ${d}kind\" }",
      "GalleryLog.d(TAG) { \"x: ${d}{items.size} elementi\" }",
      "// GalleryLog.w(TAG) { \"x: ${d}{e.message}\" }  <- commento",
      "val text = e.message // fuori da GalleryLog",
    )
    for (source in good) assertThat(violationsIn(source)).isEmpty()
  }

  @Test
  fun laScansioneDelleCartelleTrovaLeViolazioniConIlNumeroDiRiga() {
    val dir = kotlin.io.path.createTempDirectory("barriera-log").toFile()
    try {
      File(dir, "sub").mkdirs()
      File(dir, "sub/Cattivo.kt").writeText("package x\n\nfun f() {\n  GalleryLog.w(TAG) { \"x: \${e.message}\" }\n}\n")
      File(dir, "Buono.kt").writeText("package x\n\nfun g() {\n  GalleryLog.w(TAG) { \"x: \${e.javaClass.simpleName}\" }\n}\n")
      assertThat(violations(dir)).containsExactly("sub/Cattivo.kt:4: message/localizedMessage")
    } finally {
      dir.deleteRecursively()
    }
  }

  // ---- La scansione -----------------------------------------------------------------------------------------------

  private val exceptionNames = setOf("e", "ex", "exc", "exception", "throwable", "cause")

  private fun violations(root: File): List<String> {
    require(root.isDirectory) { "Non trovo i sorgenti da controllare: ${root.path}" }
    val found = mutableListOf<String>()
    root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.forEach { file ->
      val relative = file.relativeTo(root).path.replace('\\', '/')
      violationsIn(file.readText()).forEach { found += "$relative:$it" }
    }
    return found
  }

  /** Una riga `riga: perche'` per ogni chiamata a `GalleryLog` il cui testo contiene quello di un'eccezione. */
  private fun violationsIn(source: String): List<String> {
    val code = stripComments(source)
    val names = exceptionNames + catchedNames(code)
    val alternatives = names.joinToString("|") { Regex.escape(it) }
    val checks = listOf(
      Regex("""\.(message|localizedMessage|getMessage|getLocalizedMessage|stackTraceToString)\b""") to "message/localizedMessage",
      Regex("""\b($alternatives)\s*\.\s*toString\s*\(""") to "toString() dell'eccezione",
      Regex("""\$\{\s*($alternatives)\s*\}""") to "template con l'eccezione intera",
      Regex("""\$($alternatives)(?![A-Za-z0-9_])""") to "template con l'eccezione intera",
      Regex("""\+\s*($alternatives)\b(?!\s*[.(\w])""") to "concatenazione con l'eccezione intera",
      Regex("""\b($alternatives)\s*\+\s*"""") to "concatenazione con l'eccezione intera",
    )
    val out = mutableListOf<String>()
    for (match in Regex("""\bGalleryLog\s*\.\s*[vdiwe]\s*\(""").findAll(code)) {
      val call = code.substring(match.range.first, callEnd(code, match.range.last))
      val why = checks.firstOrNull { it.first.containsMatchIn(call) }?.second ?: continue
      out += "${code.substring(0, match.range.first).count { it == '\n' } + 1}: $why"
    }
    return out
  }

  /** I nomi delle eccezioni catturate nel file: `catch (x: ...)` e `onFailure { x -> ...}`. */
  private fun catchedNames(code: String): Set<String> =
    (Regex("""\bcatch\s*\(\s*(\w+)\s*:""").findAll(code) + Regex("""\b(?:onFailure|catch)\s*\{\s*(\w+)\s*->""").findAll(code))
      .map { it.groupValues[1] }.filter { it != "_" }.toSet()

  /** Dove finisce la chiamata che comincia con la parentesi in [openParen]: la parentesi chiusa e, se segue, la lambda finale. */
  private fun callEnd(code: String, openParen: Int): Int {
    val closeParen = closeOf(code, openParen)
    if (closeParen < 0) return code.length
    var i = closeParen + 1
    while (i < code.length && code[i].isWhitespace()) i++
    if (i < code.length && code[i] == '{') {
      val closeBrace = closeOf(code, i)
      return if (closeBrace < 0) code.length else closeBrace + 1
    }
    return closeParen + 1
  }

  /** L'indice della parentesi che chiude quella aperta in [open], saltando stringhe e caratteri; -1 se non chiude. */
  private fun closeOf(code: String, open: Int): Int {
    var depth = 0
    var i = open
    while (i < code.length) {
      when (code[i]) {
        '(', '{', '[' -> depth++
        ')', '}', ']' -> if (--depth == 0) return i
        '"' -> i = skipString(code, i)
        '\'' -> i = skipChar(code, i)
      }
      i++
    }
    return -1
  }

  /** L'indice dell'ultimo carattere della stringa che comincia in [start] (anche raw, e con `${...}` dentro). */
  private fun skipString(code: String, start: Int): Int {
    if (code.startsWith("\"\"\"", start)) {
      val end = code.indexOf("\"\"\"", start + 3)
      return if (end < 0) code.length else end + 2
    }
    var i = start + 1
    while (i < code.length) {
      when (code[i]) {
        '\\' -> i++
        '"' -> return i
        '$' -> if (i + 1 < code.length && code[i + 1] == '{') {
          val close = closeOf(code, i + 1)
          if (close < 0) return code.length
          i = close
        }
      }
      i++
    }
    return code.length
  }

  private fun skipChar(code: String, start: Int): Int {
    var i = start + 1
    while (i < code.length && code[i] != '\'') {
      if (code[i] == '\\') i++
      i++
    }
    return i
  }

  /** Toglie i commenti (anche KDoc) lasciando le righe al loro posto; le stringhe sono rispettate (`"https://..."` non e' un commento). */
  private fun stripComments(source: String): String {
    val out = StringBuilder(source.length)
    var i = 0
    var blockDepth = 0
    var lineComment = false
    while (i < source.length) {
      val c = source[i]
      val next = if (i + 1 < source.length) source[i + 1] else '\u0000'
      when {
        lineComment -> if (c == '\n') { lineComment = false; out.append(c) }
        blockDepth > 0 -> when {
          c == '/' && next == '*' -> { blockDepth++; i++ }
          c == '*' && next == '/' -> { blockDepth--; i++ }
          c == '\n' -> out.append(c)
        }
        c == '/' && next == '/' -> { lineComment = true; i++ }
        c == '/' && next == '*' -> { blockDepth = 1; i++ }
        c == '"' -> {
          val end = skipString(source, i)
          out.append(source, i, minOf(end + 1, source.length))
          i = end
        }
        c == '\'' -> {
          val end = skipChar(source, i)
          out.append(source, i, minOf(end + 1, source.length))
          i = end
        }
        else -> out.append(c)
      }
      i++
    }
    return out.toString()
  }
}
