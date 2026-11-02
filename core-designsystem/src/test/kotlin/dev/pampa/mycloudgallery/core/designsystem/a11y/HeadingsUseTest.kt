package dev.pampa.mycloudgallery.core.designsystem.a11y

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Fase 10, P8 (D23 (2)): i titoli di sezione dei `feature-*` sono **intestazioni** per TalkBack. `FluidSectionHeader` dell'engine non lo dichiara
 * (e `engine/` non si tocca da qui): la galleria usa `GallerySectionHeader`. Questo test fa fallire una riga nuova che torna a quella dell'engine, e
 * dice il file e la riga: chi unisce i rami (P13) rifa' la sostituzione di nome e di import.
 */
class HeadingsUseTest {

  private val root: File = File("..").canonicalFile

  @Test
  fun nessunFeatureUsaLIntestazioneDelEngine() {
    val offenders = root.listFiles { f -> f.isDirectory && f.name.startsWith("feature-") }.orEmpty().flatMap { module ->
      File(module, "src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }.flatMap { file ->
      file.readLines().mapIndexedNotNull { i, line ->
        val code = line.substringBefore("//")
        if (Regex("""(?<![A-Za-z])FluidSectionHeader\(""").containsMatchIn(code) && !code.trimStart().startsWith("*")) "${file.name}:${i + 1}" else null
      }
    }
    assertWithMessage("FluidSectionHeader( fuori da GallerySectionHeader.kt (usare GallerySectionHeader, dichiarata come intestazione):\n" + offenders.joinToString("\n"))
      .that(offenders).isEmpty()
  }
}
