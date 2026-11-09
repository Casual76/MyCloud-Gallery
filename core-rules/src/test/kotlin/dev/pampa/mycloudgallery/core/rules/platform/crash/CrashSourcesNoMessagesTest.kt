package dev.pampa.mycloudgallery.core.rules.platform.crash

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Prova di architettura (C472, come C467 per i log di debug): il registro dei crash **non legge mai il testo di un'eccezione**. Il messaggio di smbj o di una libreria puo'
 * contenere il percorso o il nome di un file dell'utente; il registro tiene solo le classi e le cornici nostre. Qui si controlla il sorgente: nessuna riga del pacchetto
 * `platform/crash` legge `message`, `localizedMessage`, `toString()` o `getStackTraceString`, e non stampa lo stack (`printStackTrace`).
 */
class CrashSourcesNoMessagesTest {

  private val directory = File("src/main/kotlin/dev/pampa/mycloudgallery/core/rules/platform/crash")

  private val reads = Regex("""\.message\b|\.localizedMessage\b|\bgetMessage\b|\bgetLocalizedMessage\b|\.toString\(\)|getStackTraceString|printStackTrace|\.suppressed\b|\.suppressedExceptions\b""")

  private fun codeLines(file: File): List<Pair<Int, String>> =
    stripComments(file.readText()).lines().mapIndexed { i, line -> (i + 1) to line }

  /** Senza commenti: le spiegazioni possono nominare `message` o `toString()`, il codice no (le righe restano al loro posto). */
  private fun stripComments(source: String): String =
    source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)) { block -> "\n".repeat(block.value.count { it == '\n' }) }
      .lines().joinToString("\n") { it.substringBefore("//") }

  @Test
  fun ilPacchettoDelRegistroNonLeggeMaiIlTestoDiUnEccezione() {
    val sources = directory.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    assertThat(sources.map { it.name }).containsAtLeast("CrashRecord.kt", "CrashLogStore.kt", "CrashLogHandler.kt", "AnrTrace.kt")

    val offenders = sources.flatMap { file ->
      codeLines(file).filter { (_, line) -> reads.containsMatchIn(line) }.map { (n, line) -> "${file.name}:$n: ${line.trim()}" }
    }

    assertWithMessage("il registro dei crash non deve leggere il messaggio o il testo di un'eccezione (C472):\n" + offenders.joinToString("\n")).that(offenders).isEmpty()
  }

  @Test
  fun ilControlloMordePerDavvero() {
    for (bad in listOf("error.message", "e.localizedMessage", "e.getMessage()", "throwable.toString()", "Log.getStackTraceString(e)", "e.printStackTrace()", "error.suppressed")) {
      assertWithMessage("non vieta: $bad").that(reads.containsMatchIn(bad)).isTrue()
    }
    for (good in listOf("error.javaClass.name", "error.stackTrace", "current.cause", "element.className", "frames.toList()", "text.length")) {
      assertWithMessage("vieta per errore: $good").that(reads.containsMatchIn(good)).isFalse()
    }
  }
}
