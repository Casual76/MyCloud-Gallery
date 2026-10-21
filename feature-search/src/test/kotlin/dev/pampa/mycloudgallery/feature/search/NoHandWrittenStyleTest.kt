package dev.pampa.mycloudgallery.feature.search

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * La regola 1 del design system verificata sul codice di `:feature-search` (Fase 6, P8, D36): **nessun colore, raggio, dimensione di testo o movimento
 * scritto a mano** nelle schermate, nessun `dp` letterale fuori da `SearchDimens.kt` e nessuna dissolvenza fra pagine. Come `NoHandWrittenStyleTest` di
 * `:feature-settings`. Cerca nei sorgenti di `src/main` e fallisce con la lista delle righe.
 */
class NoHandWrittenStyleTest {

  private val root = File("src/main/kotlin")

  private val sources: List<File> by lazy { root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

  private fun violations(pattern: Regex, allowedFiles: Set<String> = emptySet(), skipFolder: String? = null): List<String> =
    sources
      .filter { it.name !in allowedFiles }
      .filter { skipFolder == null || it.parentFile.name != skipFolder }
      .flatMap { file ->
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
    assertWithMessage("Colori nominati a mano:\n" + found.joinToString("\n")).that(found).isEmpty()
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
  fun nessunDpLetteraleFuoriDaSearchDimens() {
    val found = violations(Regex("""\b\d+(\.\d+)?\.dp\b"""), allowedFiles = setOf("SearchDimens.kt"))
    assertWithMessage("Misure scritte a mano (stanno in SearchDimens, o vengono da GalleryDimens):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunMovimentoScrittoAMano() {
    val found = violations(Regex("""\b(tween|spring|keyframes|fadeIn|fadeOut|crossfade|Crossfade)\s*[<(]"""))
    assertWithMessage("Movimento scritto a mano (usa FluidMotion; una pagina in arrivo non sfuma mai):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunaDipendenzaDaUnAltroFeature() {
    val found = violations(Regex("""import dev\.pampa\.mycloudgallery\.feature\.(library|settings|viewer|onboarding|diagnostics|backup|nas|integrations)\b"""))
    assertWithMessage("I feature non si conoscono: le uscite sono callback di :app:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunLogCheScriveTestiDellaRicerca() {
    // D40: mai il testo delle query, il testo letto o i luoghi in un log. Qui nessun log: nessun android.util.Log, nessun println.
    val found = violations(Regex("""\bandroid\.util\.Log\b|\bLog\.[vdiwe]\(|\bprintln\("""))
    assertWithMessage("Log non ammessi (D40):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunNomeDelNasNellaSchermataCerca() {
    // D28, D33: Cerca non vede il NAS, nemmeno per nome (il test d'architettura lo controlla sui moduli, questo sulle parole dei sorgenti di Cerca).
    val found = violations(Regex("""\bNasRef\b|\bMediaRef\.Nas\s*\(|\bNasSession\b|\bSmb[A-Z]\w*"""), allowedFiles = setOf("SearchPorts.kt"))
    assertWithMessage("Cerca nomina il NAS:\n" + found.joinToString("\n")).that(found).isEmpty()
  }
}
