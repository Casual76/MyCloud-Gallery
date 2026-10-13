package dev.pampa.mycloudgallery.core.places

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Lo strumento `buildPlacesDataset` gira su una JVM normale con le sole classi di questo modulo e la libreria standard
 * di Kotlin (vedi `build.gradle`): se un file che gli serve importasse Android, `core-model` o i contratti, fallirebbe
 * sul PC dell'utente con un `NoClassDefFoundError`. Questo test lo impedisce a monte.
 */
class ToolIndependenceTest {

  private val base = "src/main/kotlin/dev/pampa/mycloudgallery/core/places"

  /** I file usati dallo strumento: tutto `tool/` piu' il formato e lo scrittore. */
  private val toolFiles: List<File> = File("$base/tool").listFiles { f -> f.extension == "kt" }!!.toList() +
    listOf("Mcgp1Format.kt", "Mcgp1Writer.kt", "Hex.kt").map { File("$base/dataset/$it") }

  @Test
  fun loStrumentoNonImportaAndroidNeIlModelloNeIContratti() {
    assertThat(toolFiles.size).isAtLeast(6)
    val forbidden = listOf(
      "import android.", "import androidx.", "import dev.pampa.mycloudgallery.core.model", "import dev.pampa.mycloudgallery.core.common",
      "import dev.pampa.mycloudgallery.core.places.contract", "import dev.pampa.mycloudgallery.core.places.noop",
      "import dev.pampa.mycloudgallery.core.places.testing",
    )
    val violations = toolFiles.flatMap { file ->
      file.readLines().mapIndexedNotNull { index, line ->
        if (forbidden.any { line.trim().startsWith(it) }) "${file.name}:${index + 1}: ${line.trim()}" else null
      }
    }
    assertThat(violations).isEmpty()
  }

  @Test
  fun lAppNonUsaMaiLoStrumento() {
    // Nessun file fuori da tool/ importa le classi dello strumento: R8 le toglie dalla release.
    val users = File(base).walkTopDown().filter { it.isFile && it.extension == "kt" && "/tool/" !in it.path.replace('\\', '/') }
      .filter { it.readText().contains("core.places.tool") }
      .map { it.name }.toList()
    assertThat(users).isEmpty()
  }
}
