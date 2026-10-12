package dev.pampa.mycloudgallery.core.qr

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

/**
 * "Nessun salvataggio di immagini", verificato sul codice e sul manifest del modulo: la fotocamera serve a
 * leggere un QR, non a fare foto. Lo scanner usa solo l'analisi dei fotogrammi di CameraX; qui si vieta
 * tutto cio' che scrive un'immagine o un file, e si controlla che il manifest chieda la fotocamera e nient'altro.
 */
class NoImageStorageTest {

  private val sources: List<File> =
    File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

  private fun mentions(pattern: Regex): List<String> =
    sources.flatMap { file ->
      file.readLines().mapIndexedNotNull { index, line ->
        val code = line.substringBefore("//").trimStart()
        val isDoc = code.startsWith("*") || code.startsWith("/**")
        if (!isDoc && pattern.containsMatchIn(code)) "${file.name}:${index + 1}: ${line.trim()}" else null
      }
    }

  @Test
  fun nessunCasoDUsoCheScriveImmagini() {
    val found = mentions(
      Regex(
        """\b(ImageCapture|VideoCapture|Recorder|MediaStore|FileOutputStream|FileWriter|openFileOutput|""" +
          """getExternalFilesDir|createTempFile|FileProvider|ContentResolver|toBitmap|Bitmap\.createBitmap)\b|compress\(""",
      ),
    )
    assertWithMessage("Scritture di immagini o file nel modulo QR:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun lAnalisiEUnicoUsoDellaFotocameraOltreAllAnteprima() {
    val useCases = mentions(Regex("""\b(Preview|ImageAnalysis|ImageCapture|VideoCapture)\.Builder\b"""))
    assertThat(useCases.map { it.substringAfter(": ").substringBefore(".Builder") }.toSet())
      .containsExactly("Preview", "ImageAnalysis")
  }

  @Test
  fun nessunaRete() {
    val found = mentions(Regex("""\b(java\.net|okhttp3|HttpURLConnection)\b"""))
    assertWithMessage("Rete nel modulo QR:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  // --- manifest -------------------------------------------------------------------------------------------------

  private val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml")).documentElement

  private fun attrs(tag: String): List<Pair<String, String>> =
    (0 until manifest.getElementsByTagName(tag).length).map { i ->
      val e = manifest.getElementsByTagName(tag).item(i) as Element
      e.getAttributeNS(ANDROID, "name") to e.getAttributeNS(ANDROID, "required")
    }

  @Test
  fun ilManifestChiedeSoloLaFotocamera_enonLaRendeObbligatoria() {
    assertThat(attrs("uses-permission").map { it.first }).containsExactly("android.permission.CAMERA")
    val features = attrs("uses-feature")
    assertThat(features).isNotEmpty()
    // nessun hardware obbligatorio: un tablet senza fotocamera (o senza autofocus) installa lo stesso
    for ((name, required) in features) {
      assertWithMessage("uses-feature $name").that(required).isEqualTo("false")
    }
    assertThat(features.map { it.first }).contains("android.hardware.camera")
  }

  @Test
  fun nessunComponente() {
    for (tag in listOf("application", "activity", "activity-alias", "service", "receiver", "provider", "intent-filter")) {
      assertThat(manifest.getElementsByTagName(tag).length).isEqualTo(0)
    }
  }

  private companion object {
    const val ANDROID = "http://schemas.android.com/apk/res/android"
  }
}
