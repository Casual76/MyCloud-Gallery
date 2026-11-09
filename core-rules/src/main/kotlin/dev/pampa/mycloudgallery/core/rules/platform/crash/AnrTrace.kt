package dev.pampa.mycloudgallery.core.rules.platform.crash

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Dalla traccia di un ANR (`ApplicationExitInfo.getTraceInputStream()`, Android 11+) alle sole cornici nostre del **thread principale** (C472).
 *
 * La traccia che Android scrive e' un testo con tutti i thread del processo, i suoi nomi, gli indirizzi di memoria e, nei frammenti nativi, i percorsi delle librerie
 * del sistema. Qui non se ne tiene niente di tutto questo: si cerca il blocco del thread `"main"` e di quelle righe si ricostruisce, da zero, una cornice come
 * `classe.metodo(File.kt:riga)` solo se la classe e' nostra ([CrashDigest.frameOf]). Quello che non ha esattamente questa forma si butta, righe intere comprese.
 */
internal object AnrTrace {

  /** Un ANR non e' un caso normale: se la traccia e' piu' lunga di cosi' si smette di leggere (il thread principale sta sempre in testa). */
  private const val MAX_LINES = 40_000
  private const val MAX_LINE_LENGTH = 2_000

  /** `  at dev.pampa.mycloudgallery.Foo$bar$1.invokeSuspend(Foo.kt:12)`: la classe e il metodo non sono separati, li separa l'ultimo punto. */
  private val JAVA_FRAME = Regex("""^\s+at\s+(\S+)\.([^.\s(]+)\(([A-Za-z0-9_$]+\.(?:kt|java)):([0-9]+)\)\s*$""")

  /** Le cornici nostre del thread principale, dalla piu' vicina al punto in cui era fermo. Vuota se la traccia non si legge o non ne ha. */
  fun mainThreadFrames(stream: InputStream): List<String> {
    val frames = ArrayList<String>(CrashDigest.MAX_FRAMES)
    try {
      BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
        var inMain = false
        var lines = 0
        while (lines++ < MAX_LINES) {
          val line = reader.readLine() ?: break
          if (line.length > MAX_LINE_LENGTH) continue
          if (!inMain) {
            // L'intestazione del thread: `"main" prio=5 tid=1 Blocked`.
            inMain = line.startsWith("\"main\" ")
            continue
          }
          // Il blocco finisce alla prima riga vuota (o all'intestazione del thread dopo).
          if (line.isBlank() || line.startsWith("\"")) break
          val match = JAVA_FRAME.matchEntire(line) ?: continue
          val frame = CrashDigest.frameOf(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4].toIntOrNull() ?: -1) ?: continue
          frames += frame
          if (frames.size == CrashDigest.MAX_FRAMES) break
        }
      }
    } catch (_: IOException) {
      // Una traccia che non si legge fino in fondo: valgono le cornici raccolte finora.
    }
    return frames
  }
}
