package dev.pampa.mycloudgallery.feature.onboarding

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Le regole del design system, verificate sul codice dell'onboarding: **nessun colore, raggio, dimensione di testo,
 * misura o curva scritti a mano** nelle schermate. Cerca nei sorgenti di `src/main` e fallisce con la lista delle
 * righe, cosi' un'eccezione non puo' entrare di nascosto.
 *
 * Le misure (`dp`) stanno tutte in `OnboardingDimens.kt`, che e' l'unico file in cui si scrivono; i colori vengono da
 * `MaterialTheme.colorScheme`; i raggi da `FluidRadius` con `ContinuousCornerShape`; il testo da
 * `MaterialTheme.typography`; il movimento da `FluidMotion`.
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
  fun nessunColoreEsadecimale() {
    val found = violations(Regex("""Color\(\s*0x"""))
    assertWithMessage("Colori scritti a mano:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunColoreNominato() {
    val found = violations(Regex("""Color\.(White|Black|Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray)\b"""))
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
  fun nessunaMisuraInDpFuoriDaOnboardingDimens() {
    val found = violations(Regex("""\b\d+(\.\d+)?\.dp\b"""), allowedFiles = setOf("OnboardingDimens.kt"))
    assertWithMessage("Misure scritte a mano (vanno in OnboardingDimens):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunaCurvaDiMovimentoScrittaAMano() {
    val found = violations(Regex("""\b(tween|keyframes|snap)\s*[<(]|\bspring\s*\("""))
    assertWithMessage("Animazioni scritte a mano (usa FluidMotion):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunaTransizioneDiPaginaCheSfuma() {
    val found = violations(Regex("""\bfade(In|Out)\s*\("""))
    assertWithMessage("Una pagina in arrivo non sfuma mai:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunLogDiDatiPersonali() {
    val found = violations(Regex("""\b(Log\.[vdiwe]|println|Timber)\b"""))
    assertWithMessage("Log nelle schermate (solo GalleryLog e Redacted, e mai utente o password):\n" + found.joinToString("\n"))
      .that(found)
      .isEmpty()
  }

  @Test
  fun leSchermateNonSalvanoLePasswordInStatoSalvato() {
    val found = violations(Regex("""rememberSaveable"""))
    assertWithMessage("Una password in uno stato salvato finirebbe in un Bundle:\n" + found.joinToString("\n")).that(found).isEmpty()
  }
}
