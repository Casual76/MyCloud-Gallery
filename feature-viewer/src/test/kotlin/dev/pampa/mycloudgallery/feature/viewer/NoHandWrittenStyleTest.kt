package dev.pampa.mycloudgallery.feature.viewer

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * La regola 1 del design system, verificata sul codice di `:feature-viewer`: **nessun colore, raggio, dimensione di
 * testo o movimento scritto a mano**. I colori vengono dallo schema (lo sfondo immersivo e' `scrim`, non un nero
 * scritto), gli angoli sono continui, i movimenti sono molle e dissolvenze di `FluidMotion` e le specifiche di
 * `GalleryMotion` (che questo modulo non puo' toccare: usa `GalleryViewerTransitionState` com'e').
 *
 * Una pagina in arrivo non sfuma mai (regola 3): qui nessuna `fadeIn` fra schermate. Quello che sfuma e' il chrome, con
 * `GalleryMotion.chromeAppearSpec`, ed e' nel design system.
 */
class NoHandWrittenStyleTest {

  private val root = File("src/main/kotlin")

  private val sources: List<File> by lazy {
    root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
  }

  private fun violations(pattern: Regex): List<String> =
    sources.flatMap { file ->
      file.readLines().mapIndexedNotNull { index, line ->
        val code = line.substringBefore("//")
        val trimmed = code.trimStart()
        val isDoc = trimmed.startsWith("*") || trimmed.startsWith("/**")
        if (!isDoc && pattern.containsMatchIn(code)) "${file.name}:${index + 1}: ${line.trim()}" else null
      }
    }

  @Test
  fun sorgentiTrovati() {
    assertWithMessage("src/main/kotlin non trovato dalla cartella ${File(".").absolutePath}").that(sources).isNotEmpty()
  }

  @Test
  fun nessunColoreEsadecimale() {
    val found = violations(Regex("""Color\(\s*0x"""))
    assertWithMessage("Colori scritti a mano:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunColoreNominato() {
    val found = violations(Regex("""Color\.(White|Black|Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray|Transparent)\b"""))
    assertWithMessage("Colori nominati a mano (usa MaterialTheme.colorScheme):\n" + found.joinToString("\n")).that(found).isEmpty()
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
  fun nessunMovimentoScrittoAMano() {
    val found = violations(Regex("""(?<!FluidMotion\.)(?<!GalleryMotion\.)\b(tween|keyframes|spring|fadeIn|fadeOut|crossfade|Crossfade|slideIn|slideOut)\s*[<(]"""))
    assertWithMessage("Movimenti scritti a mano (usa FluidMotion):\n" + found.joinToString("\n")).that(found).isEmpty()
  }
}
