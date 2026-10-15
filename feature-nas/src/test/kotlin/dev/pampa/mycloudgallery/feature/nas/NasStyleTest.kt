package dev.pampa.mycloudgallery.feature.nas

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * La regola 1 del design system, verificata sul codice delle **schermate della 4B**: nessun colore, raggio, dimensione di testo o
 * movimento scritto a mano, nessun `dp` letterale fuori da `NasDimens.kt`, nessun log, nessuna dipendenza da un altro feature. Come
 * `CollectionsStyleTest` di feature-library, sui sorgenti di `src/main` di questo modulo.
 */
class NasStyleTest {

  private val sources: List<File> by lazy {
    File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
  }

  private fun violations(pattern: Regex, allowedFiles: Set<String> = emptySet()): List<String> =
    sources
      .filter { it.name !in allowedFiles }
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
    assertWithMessage("src/main/kotlin non trovato dalla cartella ${File(".").absolutePath}")
      .that(sources.map { it.name })
      .containsAtLeast("NasChrome.kt", "NasFoldersScreen.kt", "NasTrashScreen.kt", "NasSpaceScreen.kt", "NasOperationsScreen.kt", "NasConfirm.kt")
  }

  @Test
  fun nessunColoreEsadecimale() {
    val found = violations(Regex("""Color\(\s*0x"""))
    assertWithMessage("Colori scritti a mano:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunColoreNominato() {
    val found = violations(Regex("""\bColor\.(White|Black|Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray|Transparent)\b"""))
    assertWithMessage("Colori nominati a mano (la coppia sopra una foto e' GalleryOnPhoto):\n" + found.joinToString("\n")).that(found).isEmpty()
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
  fun nessunDpLetteraleFuoriDaNasDimens() {
    val found = violations(Regex("""\b\d+(\.\d+)?\.dp\b"""), allowedFiles = setOf("NasDimens.kt"))
    assertWithMessage("Misure scritte a mano (stanno in NasDimens, o vengono da GalleryDimens e da GalleryChromeDefaults):\n" + found.joinToString("\n"))
      .that(found)
      .isEmpty()
  }

  @Test
  fun nessunMovimentoScrittoAMano() {
    val found = violations(Regex("""\b(tween|spring|keyframes|crossfade|Crossfade)\s*[<(]"""))
    assertWithMessage("Movimento scritto a mano (usa FluidMotion; una pagina in arrivo non sfuma mai):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunLogCheScriveDatiPersonali() {
    // Mai nomi di file, percorsi, share o id nei log (§4.3): qui non se ne scrivono affatto.
    val found = violations(Regex("""\bandroid\.util\.Log\b|\bLog\.[vdiwe]\(|\bprintln\(|\bGalleryLog\b"""))
    assertWithMessage("Log non ammessi in questo modulo:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunaDipendenzaDaUnAltroFeature() {
    val found = violations(Regex("""import dev\.pampa\.mycloudgallery\.feature\.(onboarding|diagnostics|settings|viewer|library)\b"""))
    assertWithMessage("I feature non si conoscono: le uscite sono callback di :app:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunPercorsoDelNasInUnaRotta() {
    // Le rotte sono stringhe stabili (`NasScreen.route`): mai un percorso o una share dentro (§1.1 del progetto).
    val found = violations(Regex("navigate\\(\\s*\""))
    assertWithMessage("Rotte scritte a mano con un testo letterale:\n" + found.joinToString("\n")).that(found).isEmpty()
  }
}
