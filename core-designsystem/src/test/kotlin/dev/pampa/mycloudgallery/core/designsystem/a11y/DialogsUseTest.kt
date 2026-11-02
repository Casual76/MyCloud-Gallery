package dev.pampa.mycloudgallery.core.designsystem.a11y

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Fase 10, I3 della revisione di sicurezza (C439): ogni finestra a se' (un `Dialog`, un `AlertDialog`, un `Popup`, un menu a discesa, un foglio
 * modale di Material 3, `FluidSheet` e `FluidAlert` dell'engine, i dialoghi di Android) ha una vista che non discende da quella marcata "dati
 * sensibili per l'accessibilita'" dal tema: un servizio di accessibilita' qualunque la leggerebbe. In tutta la galleria si apre un dialogo
 * solo con [GalleryDialog] o [GalleryAlertDialog] (`a11y/GalleryDialog.kt`), che marcano la loro vista. Questo test fa fallire un file nuovo che
 * torna a quelle dell'engine, di Compose o di Material 3, e dice il file. I fogli e i menu su `FluidGlassModalPortal` stanno nella
 * composizione dell'app (stessa finestra): non servono e non sono vietati. `engine/` non si tocca da qui e non si guarda.
 */
class DialogsUseTest {

  private val root: File = File("..").canonicalFile

  /** I moduli di `settings.gradle` (la galleria, senza `engine/`). */
  private val modules: List<String> = File(root, "settings.gradle").readLines()
    .mapNotNull { Regex("""^\s*include\s+['"]:([A-Za-z0-9\-]+)['"]""").find(it.substringBefore("//"))?.groupValues?.get(1) }

  private fun productionSources(): Sequence<File> = modules.asSequence().flatMap { module ->
    listOf("main", "debug", "release", "benchmarkRelease").asSequence().map { File(root, "$module/src/$it") }.filter { it.isDirectory }
  }.flatMap { dir -> dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") } }

  private fun rel(file: File): String = file.relativeTo(root).invariantSeparatorsPath

  /** Il codice senza commenti (a blocco e di riga): i nomi vietati si possono nominare nei commenti. */
  private fun stripComments(source: String): String =
    source.replace(Regex("""/\*[\s\S]*?\*/"""), "").lines().joinToString(NEWLINE) { it.substringBefore("//") }

  @Test
  fun nessunaFinestraASeFuoriDagliInvolucri() {
    val offenders = productionSources()
      .filter { rel(it) != WRAPPER }
      .flatMap { file ->
        stripComments(file.readText()).lineSequence().mapIndexedNotNull { i, line ->
          FORBIDDEN.find(line)?.let { "${rel(file)}:${i + 1}: ${it.value}" }
        }
      }.toList()
    assertWithMessage(
      "una finestra a se' fuori da GalleryDialog.kt (usare GalleryDialog o GalleryAlertDialog, che marcano la vista del dialogo per l'accessibilita'):\n" +
        offenders.joinToString("\n"),
    ).that(offenders).isEmpty()
  }

  @Test
  fun ilControlloVedeIFile_eRiconoscePerDavveroLeFinestre() {
    assertThat(modules).containsAtLeast("app", "core-designsystem", "feature-library", "feature-settings", "feature-viewer")
    assertThat(productionSources().count()).isAtLeast(500)
    // l'involucro e' l'unico che apre una finestra, e lo fa davvero
    val wrapper = stripComments(File(root, WRAPPER).readText())
    assertThat(FORBIDDEN.containsMatchIn(wrapper)).isTrue()
    assertThat(wrapper).contains("GalleryAccessibilityDataSensitive()")
    // il controllo morde: le forme che deve vietare...
    for (bad in listOf(
      "import androidx.compose.ui.window.Dialog",
      "import androidx.compose.ui.window.Popup",
      "import androidx.compose.ui.window.*",
      "import androidx.compose.material3.AlertDialog",
      "import androidx.compose.material3.*",
      "androidx.compose.material3.ModalBottomSheet(onDismissRequest = x)",
      "  AlertDialog(onDismissRequest = onDismiss,",
      "DropdownMenu(expanded = open, onDismissRequest = close) {",
      "FluidSheet(onDismissRequest = close) {",
      "FluidAlert(visible = shown)",
      "android.app.AlertDialog.Builder(context)",
      "val d = PopupWindow(view)",
    )) {
      assertWithMessage("non vieta: $bad").that(FORBIDDEN.containsMatchIn(bad)).isTrue()
    }
    // ... e quelle che no (nomi nostri, parti di un nome, i parametri dell'involucro)
    for (good in listOf(
      "GalleryAlertDialog(onDismissRequest = onDismiss, confirmButton = {})",
      "GalleryDialog(onDismissRequest = close) {",
      "NasUnfreezeDialog(host) {}",
      "import androidx.compose.ui.window.DialogProperties",
      "import androidx.compose.ui.window.DialogWindowProvider",
      "FluidAlertAction(label = x)",
      "FluidGlassModalPortal(state = s)",
      "return RestoreStart.Dialog(it)",
      "class Dialog(val sender: IntentSender) : RestoreStart",
    )) {
      assertWithMessage("vieta per errore: $good").that(FORBIDDEN.containsMatchIn(good)).isFalse()
    }
  }

  private companion object {
    const val WRAPPER = "core-designsystem/src/main/kotlin/dev/pampa/mycloudgallery/core/designsystem/a11y/GalleryDialog.kt"
    const val NEWLINE = "\n"

    // I nomi vietati sono scritti in pezzi dove servirebbe: questo file non e' scansionato (e' un test), ma resta leggibile.
    val FORBIDDEN = Regex(
      """androidx\.compose\.ui\.window\.(Dialog|Popup)\b|androidx\.compose\.ui\.window\.\*""" +
        """|androidx\.compose\.material3\.(AlertDialog|BasicAlertDialog|DatePickerDialog|TimePickerDialog|ModalBottomSheet|DropdownMenu|ExposedDropdownMenuBox|\*)(?!\w)""" +
        """|android\.app\.(AlertDialog|Dialog|DatePickerDialog|TimePickerDialog)\b|android\.widget\.PopupWindow|androidx\.appcompat\.app\.AlertDialog""" +
        """|dev\.antigravity\.fluidengine\.ui\.fluid\.(FluidAlert|FluidSheet)\b""" +
        """|(?<![.\w])(AlertDialog|BasicAlertDialog|DatePickerDialog|TimePickerDialog|ModalBottomSheet|DropdownMenu|ExposedDropdownMenuBox|PopupWindow|FluidAlert|FluidSheet)\b""",
    )
  }
}
