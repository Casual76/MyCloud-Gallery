package dev.pampa.mycloudgallery.core.ai.faces

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Fase 8 (P2, regola dei brief del §4.3): **nessun ritratto, nessuna immagine di volto nel repository**, solo tensori numerici piccoli e immagini
 * sintetiche disegnate dal test. Qui: nelle cartelle dei test del modulo non c'e' nessun file di immagine, di modello o di tensori binari; le fixture
 * dei volti sono testo con la loro intestazione, piccole; nessuna etichetta dei ritratti di P1 ne' nome di persone nei file di prova.
 * (Il controllo sul repository intero, per P1, e' `NoFaceModelSourcesInGitTest` di core-models.)
 */
class FaceFixturesGuardTest {

  private val forbiddenExtensions = setOf(
    "png", "jpg", "jpeg", "webp", "bmp", "gif", "heic", "heif", "avif", "tif", "tiff", "ico", "svg", "dng", "raw",
    "onnx", "tflite", "pb", "task", "litertlm", "npy", "npz", "pt", "h5", "bin", "mp4", "mov", "webm",
  )

  private fun files(root: String): List<File> = File(root).walkTopDown().filter { it.isFile }.toList()

  @Test
  fun neiTestDelModuloNonCiSonoImmaginiNeModelli() {
    for (root in listOf("src/test", "src/androidTest")) {
      assertWithMessage(root).that(File(root).isDirectory).isTrue()
      val bad = files(root).filter { it.extension.lowercase() in forbiddenExtensions }.map { it.path.replace('\\', '/') }
      assertWithMessage("$root: file di immagini o di modelli").that(bad).isEmpty()
    }
  }

  @Test
  fun leFixtureDeiVoltiSonoTestoPiccoloConLaLoroIntestazione() {
    val dir = File("src/test/resources/faces")
    val all = dir.listFiles().orEmpty().filter { it.isFile }
    assertThat(all.map { it.name }).containsAtLeast("tensori-sintetici.txt", "allineamento.txt", "genera_riferimenti.py")
    assertThat(all.sumOf { it.length() }).isLessThan(400_000L)
    for (file in all) {
      assertWithMessage(file.name).that(file.length()).isLessThan(150_000L)
      if (file.extension == "txt") {
        val first = file.useLines { it.first() }
        assertWithMessage("${file.name}: intestazione").that(first).startsWith("MCGF-")
        // Testo vero: nessun byte di controllo oltre a fine riga e tabulazione.
        assertWithMessage(file.name).that(file.readBytes().none { it < 0x20 && it != 0x0A.toByte() && it != 0x0D.toByte() && it != 0x09.toByte() }).isTrue()
      }
    }
  }

  @Test
  fun nelleFixtureNonCiSonoNomiDiRitrattiNeDiPersone() {
    // Le etichette dei ritratti di P1 e le fonti non passano nei file di prova (neppure nei commenti): solo "composizione-a-img-NNN" e simili.
    val text = File("src/test/resources/faces").listFiles().orEmpty().filter { it.extension == "txt" }.joinToString("\n") { it.readText() }
    assertThat(text).doesNotContain("ritratto-")
    assertThat(text.lowercase()).doesNotContain("soggetto")
    assertThat(text.lowercase()).doesNotContain("commons.wikimedia")
  }
}
