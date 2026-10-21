package dev.pampa.mycloudgallery.feature.viewer.livetext

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Il testo letto e il testo copiato non finiscono mai nei log (Fase 6, D40, regola dei brief: "mai testo delle query, testo letto..."), e
 * nessun codice del testo dal vivo nomina ML Kit ne' la rete (D41, D24). Una rete a maglie larghe sul testo dei sorgenti, sul modello
 * di `NoExceptionTextInLogsTest`: il testo dal vivo non scrive log per costruzione, la prova impedisce la distrazione.
 */
class NoLiveTextLogTest {

  private val root = File("src/main/kotlin")

  private val livetext: List<File> by lazy {
    root.walkTopDown().filter { it.isFile && it.extension == "kt" && ("/livetext/" in it.invariantPath() || it.name == "LongPressArbiter.kt") }.toList()
  }

  private fun File.invariantPath() = path.replace('\\', '/')

  private fun violations(pattern: Regex): List<String> = livetext.flatMap { file ->
    file.readLines().mapIndexedNotNull { index, line ->
      val code = line.substringBefore("//")
      val trimmed = code.trimStart()
      val isDoc = trimmed.startsWith("*") || trimmed.startsWith("/**")
      if (!isDoc && pattern.containsMatchIn(code)) "${file.name}:${index + 1}: ${line.trim()}" else null
    }
  }

  @Test
  fun sorgentiTrovati() {
    assertThat(livetext.map { it.name }).containsAtLeast("LiveTextController.kt", "LiveTextSystem.kt", "LongPressArbiter.kt")
  }

  @Test
  fun ilTestoDalVivoNonScriveLog() {
    val found = violations(Regex("""GalleryLog|android\.util\.Log|\bLog\.[dviwe]\(|println\(|Timber|printStackTrace"""))
    assertWithMessage("Log nel testo dal vivo:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun ilTestoDalVivoNonNominaMlKitNeLaRete() {
    val found = violations(Regex("""com\.google\.mlkit|java\.net\.|okhttp|HttpURLConnection|javax\.net"""))
    assertWithMessage("ML Kit o rete nel testo dal vivo:\n" + found.joinToString("\n")).that(found).isEmpty()
  }

  @Test
  fun ilTestoDelleRigheNonSiStampaMaiNeiToString() {
    val line = ViewerTextLine(ViewerTextBox(0f, 0f, 1f, 1f), 0, listOf(ViewerWordSpan(0f, 1f)), "numero di telefono 0123456789")
    assertThat(line.toString()).doesNotContain("telefono")
    assertThat(line.toString()).doesNotContain("0123456789")
    assertThat(ViewerTextLayout(listOf(line)).lines.single().toString()).isEqualTo("ViewerTextLine(1 parole)")
  }
}
