package dev.pampa.mycloudgallery.feature.people.activation

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * La regola 1 del design system sul codice di `activation/` (pagina di attivazione, avviso e Impostazioni -> Persone): **nessun colore, raggio, dimensione di
 * testo o movimento scritto a mano**, nessun `dp` fuori da `ActivationDimens.kt`; e la regola del consenso (D3, C41): `FacesActivationConsent` si costruisce solo
 * in `FacesActivationScreen.kt`, la pagina di attivazione (la stessa cosa la verifica `Phase8ArchitectureTest` su tutti i moduli).
 */
class ActivationStyleTest {

  private val root = File("src/main/kotlin/dev/pampa/mycloudgallery/feature/people/activation")

  private val sources: List<File> by lazy { root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

  private fun violations(pattern: Regex, allowedFiles: Set<String> = emptySet()): List<String> =
    sources.filter { it.name !in allowedFiles }.flatMap { file ->
      file.readLines().mapIndexedNotNull { index, line ->
        val code = line.substringBefore("//")
        val trimmed = code.trimStart()
        val isDoc = trimmed.startsWith("*") || trimmed.startsWith("/**")
        if (!isDoc && pattern.containsMatchIn(code)) "${file.name}:${index + 1}: ${line.trim()}" else null
      }
    }

  @Test
  fun sorgentiTrovati() {
    assertWithMessage("activation/ non trovata da ${File(".").absolutePath}").that(sources.size).isAtLeast(8)
  }

  @Test
  fun nessunColoreScrittoAMano() {
    val found = violations(Regex("""Color\(\s*0x|Color\.(White|Black|Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray|Transparent)\b"""))
    assertWithMessage("Colori scritti a mano:\n" + found.joinToString("\n")).that(found).isEmpty()
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
  fun nessunDpLetteraleFuoriDaActivationDimens() {
    val found = violations(Regex("""\b\d+(\.\d+)?\.dp\b"""), allowedFiles = setOf("ActivationDimens.kt"))
    assertWithMessage("Misure scritte a mano (stanno in ActivationDimens, o vengono da GalleryDimens):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunMovimentoScrittoAMano() {
    val found = violations(Regex("""\b(tween|spring|keyframes|fadeIn|fadeOut|crossfade|Crossfade)\s*[<(]"""))
    assertWithMessage("Movimento scritto a mano (usa FluidMotion):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  /** Nessun orologio del telefono, nessuna rete, nessun log con valori: le schermate non conoscono la generazione ne' i nomi (D36, D37). */
  @Test
  fun nessunLogENessunaGenerazioneNelleSchermate() {
    val found = violations(Regex("""\b(Log\.|println|Timber|GalleryLog|base32|generationOrNull)\b"""))
    assertWithMessage("Log o generazioni nelle schermate:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun fuoriDallaPaginaNessunoCostruisceIlConsenso() {
    val main = File("src/main/kotlin")
    val builders = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { file ->
      file.readLines().any { line ->
        val code = line.substringBefore("//")
        Regex("""\bFacesActivationConsent\s*\(""").containsMatchIn(code) && !Regex("""\bclass\s+FacesActivationConsent\b""").containsMatchIn(code)
      }
    }.map { it.name }.toList()
    assertWithMessage("Chi costruisce il consenso").that(builders).containsExactly("FacesActivationScreen.kt")
  }
}
