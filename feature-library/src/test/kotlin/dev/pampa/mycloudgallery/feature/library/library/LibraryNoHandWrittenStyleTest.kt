package dev.pampa.mycloudgallery.feature.library.library

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * La regola 1 del design system sulla Libreria e sulla griglia (P7): **nessun colore, raggio, dimensione di testo o
 * `tween` scritto a mano** nei sorgenti di `library/` e `grid/`. Colori dal tema, testo dalla tipografia, angoli da
 * `FluidRadius` con `ContinuousCornerShape`, movimento da `FluidMotion` e `GalleryMotion`.
 */
class LibraryNoHandWrittenStyleTest {

  private val roots = listOf(
    File("src/main/kotlin/dev/pampa/mycloudgallery/feature/library/library"),
    File("src/main/kotlin/dev/pampa/mycloudgallery/feature/library/grid"),
  )

  private val sources: List<File> by lazy {
    roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
  }

  private fun violations(pattern: Regex): List<String> = sources.flatMap { file ->
    file.readLines().mapIndexedNotNull { index, line ->
      val code = line.substringBefore("//")
      val isDoc = code.trimStart().startsWith("*") || code.trimStart().startsWith("/**")
      if (!isDoc && pattern.containsMatchIn(code)) "${file.name}:${index + 1}: ${line.trim()}" else null
    }
  }

  @Test
  fun sorgentiTrovati() {
    assertWithMessage("Sorgenti di library/ e grid/ non trovati").that(sources.size).isAtLeast(8)
  }

  @Test
  fun nessunColoreScrittoAMano() {
    val found = violations(Regex("""Color\(\s*0x|Color\.(White|Black|Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray)\b"""))
    assertWithMessage("Colori scritti a mano:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunRaggioCircolare() {
    val found = violations(Regex("""\b(RoundedCornerShape|CircleShape|CutCornerShape)\b"""))
    assertWithMessage("Angoli circolari:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunaDimensioneDiTestoLetterale() {
    val found = violations(Regex("""\b\d+(\.\d+)?\.sp\b|\bfontSize\s*="""))
    assertWithMessage("Dimensioni di testo scritte a mano:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunaTweenScrittaAMano() {
    val found = violations(Regex("""\btween\s*[<(]|\bspring\s*[<(]"""))
    assertWithMessage("Movimento scritto a mano (usa FluidMotion):\n" + found.joinToString("\n")).that(found).isEmpty()
  }
}
