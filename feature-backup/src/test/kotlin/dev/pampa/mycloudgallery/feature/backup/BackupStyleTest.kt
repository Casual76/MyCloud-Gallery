package dev.pampa.mycloudgallery.feature.backup

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * La regola 1 del design system (D30: nessuno stile nuovo), verificata sul codice delle **schermate del backup**: nessun colore, raggio,
 * dimensione di testo o movimento scritto a mano, nessun `dp` letterale fuori da `BackupDimens.kt`, nessun log, nessuna dipendenza da un
 * altro feature. Come `NasStyleTest` di feature-nas, sui sorgenti di `src/main` di questo modulo.
 */
class BackupStyleTest {

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
      .containsAtLeast("BackupChrome.kt", "BackupPageScreen.kt", "FirstBackupSheet.kt", "BackupSettingsScreen.kt", "BackupProblems.kt", "BackupDimens.kt")
  }

  @Test
  fun nessunColoreEsadecimale() {
    val found = violations(Regex("""Color\(\s*0x"""))
    assertWithMessage("Colori scritti a mano:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunColoreNominato() {
    val found = violations(Regex("""\bColor\.(White|Black|Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray|Transparent)\b"""))
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
  fun nessunDpLetteraleFuoriDaBackupDimens() {
    val found = violations(Regex("""\b\d+(\.\d+)?\.dp\b"""), allowedFiles = setOf("BackupDimens.kt"))
    assertWithMessage("Misure scritte a mano (stanno in BackupDimens, o vengono da GalleryDimens e da GalleryChromeDefaults):\n" + found.joinToString("\n"))
      .that(found)
      .isEmpty()
  }

  @Test
  fun nessunMovimentoScrittoAMano() {
    val found = violations(Regex("""\b(tween|spring|keyframes|crossfade|Crossfade)\s*[<(]"""))
    assertWithMessage("Movimento scritto a mano (usa FluidMotion):\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunLogCheScriveDatiPersonali() {
    // Mai nomi di file, percorsi, share o id nei log (§4.3): qui non se ne scrivono affatto.
    val found = violations(Regex("""\bandroid\.util\.Log\b|\bLog\.[vdiwe]\(|\bprintln\(|\bGalleryLog\b"""))
    assertWithMessage("Log non ammessi in questo modulo:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunaDipendenzaDaUnAltroFeature() {
    val found = violations(Regex("""import dev\.pampa\.mycloudgallery\.feature\.(onboarding|diagnostics|settings|viewer|library|nas)\b"""))
    assertWithMessage("I feature non si conoscono: le uscite sono callback di :app:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun nessunPercorsoDelNasInUnaRotta() {
    val found = violations(Regex("navigate\\(\\s*\""))
    assertWithMessage("Rotte scritte a mano con un testo letterale:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun leSchermateNonVedonoIlNasNeLeOperazioniDistruttive() {
    // §1.1: feature-backup e' cieca a core-nas e core-nasops (Phase5ArchitectureTest lo verifica sul grafo, qui sui sorgenti delle UI).
    // I nomi vietati sono scritti in due pezzi: questo file non li contiene mai interi (come Phase5ArchitectureTest).
    val found = violations(
      Regex("""\bdev\.pampa\.mycloudgallery\.core\.nas(ops)?\b|\bNas""" + """FileOps\b|\bNas""" + """BackupOps\b"""),
    )
    assertWithMessage("Il NAS non si vede da qui:\n" + found.joinToString("\n")).that(found).isEmpty()
  }
}
