package dev.pampa.mycloudgallery.feature.diagnostics

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * La regola 1 del design system Fluid, verificata sul codice della Diagnostica: **nessun colore, raggio, dimensione
 * di testo o animazione scritti a mano** nei sorgenti di `src/main`. Colori da `MaterialTheme.colorScheme`, testo da
 * `MaterialTheme.typography`, angoli da `ContinuousCornerShape`/`FluidRadius`, movimento da `FluidMotion`. Le
 * schermate usano solo componenti dell'engine (liste raggruppate, bottoni, messaggi): qui non c'e' niente da
 * disegnare a mano.
 */
class NoHandWrittenStyleTest {

  private val sources: List<File> by lazy {
    File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
  }

  private fun violations(pattern: Regex): List<String> =
    sources.flatMap { file ->
      file.readLines().mapIndexedNotNull { index, line ->
        val code = line.substringBefore("//")
        val isDoc = code.trimStart().startsWith("*") || code.trimStart().startsWith("/**")
        if (!isDoc && pattern.containsMatchIn(code)) "${file.name}:${index + 1}: ${line.trim()}" else null
      }
    }

  @Test
  fun sorgentiTrovati() {
    assertWithMessage("src/main/kotlin non trovato dalla cartella ${File(".").absolutePath}").that(sources).isNotEmpty()
  }

  @Test
  fun nessunColoreScrittoAMano() {
    val hex = violations(Regex("""Color\(\s*0x"""))
    val named = violations(Regex("""Color\.(White|Black|Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray)\b"""))
    assertWithMessage("Colori scritti a mano:\n" + (hex + named).joinToString("\n")).that(hex + named).isEmpty()
  }

  @Test
  fun nessunRaggioCircolare() {
    val found = violations(Regex("""\b(RoundedCornerShape|CircleShape|CutCornerShape)\b"""))
    assertWithMessage("Angoli circolari (usa ContinuousCornerShape / FluidRadius):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunaDimensioneDiTestoLetterale() {
    val found = violations(Regex("""\b\d+(\.\d+)?\.sp\b|\bfontSize\s*="""))
    assertWithMessage("Dimensioni di testo scritte a mano (usa MaterialTheme.typography):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunaAnimazioneScrittaAMano() {
    val found = violations(Regex("""\b(tween|spring|keyframes|fadeIn|fadeOut|animate\w*AsState)\s*[<(]"""))
    assertWithMessage("Animazioni scritte a mano (usa FluidMotion):\n" + found.joinToString("\n")).that(found).isEmpty()
  }
}
