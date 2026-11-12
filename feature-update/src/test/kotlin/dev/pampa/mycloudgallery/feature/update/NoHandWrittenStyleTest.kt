package dev.pampa.mycloudgallery.feature.update

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * La regola 1 del design system sul codice di `:feature-update` (come `NoHandWrittenStyleTest` di feature-settings): nessun colore o raggio scritto a
 * mano, nessun `dp` letterale fuori da `UpdateDimens.kt`.
 */
class NoHandWrittenStyleTest {

  private val sources: List<File> = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

  private fun violations(pattern: Regex, allowed: Set<String> = emptySet()): List<String> =
    sources.filter { it.name !in allowed }.flatMap { file ->
      file.readLines().mapIndexedNotNull { index, line ->
        val code = line.substringBefore("//")
        val trimmed = code.trimStart()
        if (!trimmed.startsWith("*") && pattern.containsMatchIn(code)) "${file.name}:${index + 1}: ${line.trim()}" else null
      }
    }

  @Test
  fun nessunoStileScrittoAMano() {
    assertWithMessage("sorgenti").that(sources).isNotEmpty()
    assertWithMessage("colori scritti a mano").that(violations(Regex("""Color\(\s*0x|Color\.(White|Black|Red|Green|Blue|Gray|Transparent)\b"""))).isEmpty()
    assertWithMessage("angoli circolari").that(violations(Regex("""\b(RoundedCornerShape|CircleShape|CutCornerShape)\b"""))).isEmpty()
    assertWithMessage("dp fuori da UpdateDimens.kt").that(violations(Regex("""\b\d+(\.\d+)?\.dp\b"""), allowed = setOf("UpdateDimens.kt"))).isEmpty()
  }
}
