package dev.pampa.mycloudgallery.core.places

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Privacy (docs/fase3-progetto.md §2.3): nessun log di coordinate o luoghi. Il codice che gira nell'app non scrive
 * niente da nessuna parte: ne' `println`, ne' `Log`, ne' il logger interno. (Lo strumento `tool/` gira sul PC e stampa
 * solo conteggi e impronte: ha un test suo, BuildPlacesDatasetTest.)
 */
class NoLogSourcesTest {

  private val forbidden = listOf(
    Regex("""\bprintln\b"""),
    Regex("""\bprint\("""),
    Regex("""System\.(out|err)"""),
    Regex("""\bLog\.[a-z]\("""),
    Regex("""android\.util\.Log"""),
    Regex("""\bGalleryLog\b"""),
    Regex("""\bTimber\b"""),
    Regex("""printStackTrace"""),
    Regex("""java\.util\.logging"""),
  )

  @Test
  fun ilCodiceDellAppNonScriveLog() {
    val violations = mutableListOf<String>()
    File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
      val relative = file.relativeTo(File("src/main")).path.replace('\\', '/')
      // Lo strumento sul PC puo' stampare i suoi conteggi (e solo quelli); il resto no.
      if ("/tool/" in relative) return@forEach
      stripComments(file.readText()).lineSequence().forEachIndexed { index, line ->
        forbidden.filter { it.containsMatchIn(line) }.forEach { violations += "$relative:${index + 1}: ${it.pattern}" }
      }
    }
    assertThat(violations).isEmpty()
  }

  /** Toglie i commenti (anche KDoc) lasciando le righe al loro posto; senza badare alle stringhe: basta per cercare nomi vietati. */
  private fun stripComments(source: String): String =
    source.replace(Regex("(?s)/\\*.*?\\*/")) { m -> "\n".repeat(m.value.count { it == '\n' }) }.replace(Regex("//[^\n]*"), "")

  @Test
  fun leClassiConPosizioniNonHannoUnToStringChePubblichiDati() {
    // GeoPoint e PlaceRef nascondono i dati; le classi di questo modulo che li tengono non devono ridefinire toString.
    val withToString = File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }
      .filter { "/contract/" !in it.path.replace('\\', '/') && "/testing/" !in it.path.replace('\\', '/') }
      .filter { Regex("""override fun toString""").containsMatchIn(it.readText()) }
      .map { it.name }.toList()
    assertThat(withToString).isEmpty()
  }
}
