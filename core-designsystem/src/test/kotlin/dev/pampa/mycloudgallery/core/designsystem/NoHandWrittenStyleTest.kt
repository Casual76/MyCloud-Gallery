package dev.pampa.mycloudgallery.core.designsystem

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * La regola 1 del design system, verificata sul codice: **nessun colore, raggio o dimensione di testo
 * scritto a mano** nei file della galleria. Cerca nei sorgenti di `src/main` e fallisce con la lista
 * delle righe, cosi' un'eccezione non puo' entrare di nascosto.
 *
 * Le eccezioni sono poche, dichiarate qui e motivate nel file che le contiene:
 *  - `GalleryTheme.kt`: i colori del marchio Ametista *sono* la definizione del marchio;
 *  - `GalleryTokens.kt`: la coppia "sopra una foto" (`Color.White` sullo scrim);
 *  - `GalleryMotion.kt` e `ComponentCatalog.kt`: le `tween` delle specifiche di movimento, costruite
 *    solo con durate ed easing di `FluidMotion` (come fa l'engine in `RouteMotion`).
 */
class NoHandWrittenStyleTest {

  private val root = File("src/main/kotlin")

  private val sources: List<File> by lazy {
    root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
  }

  private fun violations(pattern: Regex, allowedFiles: Set<String> = emptySet()): List<String> =
    sources
      .filter { it.name !in allowedFiles }
      .flatMap { file ->
        file.readLines().mapIndexedNotNull { index, line ->
          val code = line.substringBefore("//")
          val isDoc = code.trimStart().startsWith("*") || code.trimStart().startsWith("/**")
          if (!isDoc && pattern.containsMatchIn(code)) "${file.name}:${index + 1}: ${line.trim()}" else null
        }
      }

  @Test
  fun sorgentiTrovati() {
    assertWithMessage("src/main/kotlin non trovato dalla cartella ${File(".").absolutePath}")
      .that(sources)
      .isNotEmpty()
  }

  @Test
  fun nessunColoreEsadecimaleFuoriDalMarchio() {
    val found = violations(Regex("""Color\(\s*0x"""), allowedFiles = setOf("GalleryTheme.kt"))
    assertWithMessage("Colori scritti a mano:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunColoreNominatoFuoriDallaCoppiaSopraUnaFoto() {
    val found = violations(
      Regex("""Color\.(White|Black|Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray)\b"""),
      allowedFiles = setOf("GalleryTokens.kt"),
    )
    assertWithMessage("Colori nominati a mano:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunRaggioCircolare() {
    val found = violations(Regex("""\b(RoundedCornerShape|CircleShape|CutCornerShape)\b"""))
    assertWithMessage("Angoli circolari (usa ContinuousCornerShape / FluidRadius):\n" + found.joinToString("\n"))
      .that(found)
      .isEmpty()
  }

  @Test
  fun nessunaDimensioneDiTestoLetterale() {
    val found = violations(Regex("""\b\d+(\.\d+)?\.sp\b|\bfontSize\s*="""))
    assertWithMessage("Dimensioni di testo scritte a mano (usa MaterialTheme.typography):\n" + found.joinToString("\n"))
      .that(found)
      .isEmpty()
  }

  @Test
  fun nessunaTweenFuoriDallaSpecificaDelMovimento() {
    val found = violations(Regex("""\btween\s*[<(]"""), allowedFiles = setOf("GalleryMotion.kt", "ComponentCatalog.kt"))
    assertWithMessage("tween scritte a mano (usa FluidMotion):\n" + found.joinToString("\n")).that(found).isEmpty()
  }
}
