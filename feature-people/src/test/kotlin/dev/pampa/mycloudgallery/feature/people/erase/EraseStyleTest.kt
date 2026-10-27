package dev.pampa.mycloudgallery.feature.people.erase

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Le regole del codice di `erase/` (Fase 8, P8): la conferma (D29, C38) si costruisce **solo** in `FacesEraseSheet.kt`, al tocco del pulsante (la stessa
 * cosa la verifica `Phase8ArchitectureTest` su tutti i moduli); nessun `dp`, colore, raggio, dimensione di testo o movimento scritto a mano (le misure
 * vengono da `PeopleDimens` e `GalleryDimens`); nessun nome di persona nei log (non c'e' nessun log); e il pulsante di pericolo e' uno solo.
 */
class EraseStyleTest {

  private val root = File("src/main/kotlin/dev/pampa/mycloudgallery/feature/people/erase")

  private val sources: List<File> by lazy { root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

  private fun code(file: File): List<String> = file.readLines().map { it.substringBefore("//") }.filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/**") }

  private fun violations(pattern: Regex, allowedFiles: Set<String> = emptySet()): List<String> = sources.filter { it.name !in allowedFiles }.flatMap { file ->
    code(file).mapIndexedNotNull { index, line -> if (pattern.containsMatchIn(line)) "${file.name}:${index + 1}: ${line.trim()}" else null }
  }

  @Test
  fun sorgentiTrovati() {
    assertWithMessage("erase/ non trovata da ${File(".").absolutePath}").that(sources.map { it.name }).containsAtLeast("FacesEraseSheet.kt", "FacesEraseController.kt", "EraseRoutes.kt")
  }

  @Test
  fun laConfermaSiCostruisceSoloNelFoglio() {
    val found = violations(Regex("""\bFacesEraseConfirmation\s*\("""), allowedFiles = setOf("FacesEraseSheet.kt"))
    assertWithMessage("FacesEraseConfirmation costruita fuori dal foglio:\n" + found.joinToString("\n")).that(found).isEmpty()
    val sheet = code(File(root, "FacesEraseSheet.kt")).filter { Regex("""\bFacesEraseConfirmation\s*\(""").containsMatchIn(it) }
    assertThat(sheet).hasSize(1)
    // Al tocco: dentro l'onClick del pulsante, con l'impronta e l'istante del gettone dell'anteprima.
    assertThat(sheet.single()).contains("onClick")
    assertThat(sheet.single()).contains("previewDigest")
  }

  @Test
  fun nienteScrittoAMano() {
    for ((pattern, what) in listOf(
      Regex("""\b\d+(\.\d+)?\.dp\b""") to "dp",
      Regex("""Color\(\s*0x|Color\.(White|Black|Red|Green|Blue|Gray|Transparent)\b""") to "colori",
      Regex("""\b(RoundedCornerShape|CircleShape|CutCornerShape)\b""") to "raggi",
      Regex("""\b\d+(\.\d+)?\.sp\b|\bfontSize\s*=""") to "testo",
      Regex("""\b(tween|spring|keyframes|fadeIn|fadeOut|crossfade|Crossfade)\s*[<(]""") to "movimento",
      Regex("""\bLog\.|GalleryLog|println\(""") to "log",
    )) {
      val found = violations(pattern)
      assertWithMessage("$what scritti a mano:\n" + found.joinToString("\n")).that(found).isEmpty()
    }
  }

  @Test
  fun unSoloPulsanteDiPericolo() {
    val destructive = sources.sumOf { file -> code(file).count { it.contains("FluidButtonStyle.Destructive") } }
    assertThat(destructive).isEqualTo(1)
  }
}
