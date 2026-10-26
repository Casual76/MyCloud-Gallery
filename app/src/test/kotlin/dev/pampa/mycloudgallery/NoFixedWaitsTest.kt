package dev.pampa.mycloudgallery

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

/**
 * Nessun test di `:app` aspetta con un **tempo scritto a mano**. Un `withTimeout(20.seconds)` o un ciclo con la scadenza calcolata dall'orologio
 * (`System.currentTimeMillis() + 10_000`) misura la velocita' del PC invece di aspettare l'evento: con piu' build Gradle accese lo stesso lavoro e'
 * dieci volte piu' lento e il test cade solo allora (sono caduti cosi' `bk3_riprova...`, `bk3_alRitorno...` e altri della verifica funzionale del
 * backup, `Correzioni5B...`). Le attese stanno in `integration/Attese.kt`: un tetto solo, largo, e `until` per le condizioni senza un flusso.
 *
 * Questa barriera guarda i sorgenti di `src/test`: ogni `withTimeout`/`withTimeoutOrNull` con un numero come argomento, ogni scadenza calcolata
 * dall'orologio e ogni costante `TIMEOUT...` con un numero. Passare da `Attese.TETTO` o `Attese.TETTO_MS` (o da una costante che li usa) e' la strada.
 */
class NoFixedWaitsTest {

  @Test
  fun nessunTestAspettaConUnTempoScrittoAMano() {
    val root = File("src/test/kotlin")
    // Una cartella che non si trova non deve far passare la barriera a vuoto.
    assertThat(File(root, "dev/pampa/mycloudgallery/integration/Attese.kt").isFile).isTrue()
    assertThat(violations(root)).isEmpty()
  }

  @Test
  fun laBarrieraVedeLeAttesePerTempo() {
    val dir = Files.createTempDirectory("attese-per-tempo").toFile()
    try {
      File(dir, "Cattivo.kt").writeText(
        """
        package x

        class Cattivo {
          suspend fun a() = withTimeout(20.seconds) { }
          suspend fun b() = withTimeoutOrNull(5_000) { }
          fun c() { val end = System.currentTimeMillis() + 10_000 }
          fun d() { val end = System.nanoTime() +  3_000_000L }
          companion object {
            const val TIMEOUT_MS: Long = 20_000L
          }
        }
        """.trimIndent(),
      )
      assertThat(violations(dir)).hasSize(5)
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun laBarrieraLasciaPassareIlTettoComune() {
    val dir = Files.createTempDirectory("attese-comuni").toFile()
    try {
      File(dir, "Buono.kt").writeText(
        """
        package x

        class Buono {
          // withTimeout(20.seconds) nei commenti non conta
          suspend fun a() = withTimeout(Attese.TETTO) { }
          suspend fun b() = withTimeoutOrNull(Attese.TETTO_MS) { }
          suspend fun c() = withTimeout(TIMEOUT) { }
          val monotonic = { System.nanoTime() / 1_000_000 }
          companion object {
            val TIMEOUT = Attese.TETTO
          }
        }
        """.trimIndent(),
      )
      assertThat(violations(dir)).isEmpty()
    } finally {
      dir.deleteRecursively()
    }
  }

  private companion object {
    val WITH_TIMEOUT_LITERAL = Regex("""withTimeout(OrNull)?\(\s*[0-9]""")
    val DEADLINE_FROM_CLOCK = Regex("""(currentTimeMillis|nanoTime)\(\)\s*\+""")
    val TIMEOUT_CONSTANT_LITERAL = Regex("""\bval\s+TIMEOUT\w*\s*(:\s*\w+\s*)?=\s*[0-9]""")

    /** Questo file nomina i modelli da cercare: non si guarda. */
    const val SELF = "NoFixedWaitsTest.kt"

    /** Le righe con un'attesa scritta a mano, `file:riga: testo`. */
    fun violations(dir: File): List<String> = dir.walkTopDown()
      .filter { it.isFile && it.extension == "kt" && it.name != SELF }
      .flatMap { file ->
        file.readLines().mapIndexedNotNull { index, line ->
          val code = line.trim()
          val isComment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
          val bad = !isComment && (WITH_TIMEOUT_LITERAL.containsMatchIn(code) || DEADLINE_FROM_CLOCK.containsMatchIn(code) || TIMEOUT_CONSTANT_LITERAL.containsMatchIn(code))
          if (bad) "${file.path}:${index + 1}: $code" else null
        }
      }
      .toList()
  }
}
