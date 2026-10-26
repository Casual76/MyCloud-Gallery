package dev.pampa.mycloudgallery.graph

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.files.Patience
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Il battito dei lavori (`HeartbeatStore`) non si perde se coincide con una lettura, non fa mai vedere "nessun battito" mentre si aggiorna, e
 * `clear()` non fa credere cancellato un file che c'e' ancora.
 *
 * Su Windows `java.io` apre i file senza `FILE_SHARE_DELETE`: un altro handle sul battito faceva fallire in silenzio `delete()` e `renameTo()` del
 * codice di prima (e `renameTo` non sostituisce comunque un file che c'e' gia': si cancellava prima, e in quell'istante il battito mancava).
 * L'altro handle e' un `FileInputStream` e si chiude **dentro l'attesa** del codice che si prova ([Patience.pause]): niente orologio.
 */
class HeartbeatStoreTest {

  @get:Rule
  val folder = TemporaryFolder()

  private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

  private class Holder(file: File) {
    private val stream = FileInputStream(file)
    var waits = 0
      private set
    val patience = Patience(listOf(1L, 1L, 1L, 1L, 1L)) {
      waits++
      stream.close()
    }

    fun close() = stream.close()
  }

  private fun file() = File(folder.root, "heartbeat")

  @Test
  fun unBattitoNonSiPerdeSeUnAltroHandleTieneIlFile() = runBlocking<Unit> {
    HeartbeatStore(file()).beat(1_000L)
    val holder = Holder(file())

    try {
      HeartbeatStore(file(), holder.patience).beat(2_000L)
    } finally {
      holder.close()
    }

    assertThat(HeartbeatStore(file()).lastBeatAt()).isEqualTo(2_000L)
    assertThat(folder.root.list().orEmpty().toList()).containsExactly("heartbeat")
    if (windows) assertThat(holder.waits).isAtLeast(1)
  }

  @Test
  fun clearTogliePerDavveroIlFileAncheSeUnAltroHandleLoTiene() = runBlocking<Unit> {
    HeartbeatStore(file()).beat(1_000L)
    val holder = Holder(file())

    try {
      HeartbeatStore(file(), holder.patience).clear()
    } finally {
      holder.close()
    }

    assertThat(file().exists()).isFalse()
    assertThat(HeartbeatStore(file()).lastBeatAt()).isNull()
    if (windows) assertThat(holder.waits).isAtLeast(1)
  }

  @Test
  fun clearChePoiNonRiesce_lanciaECosiLAzzeramentoLoSaEControlla() = runBlocking<Unit> {
    // Un nome occupato da una cartella non vuota: nessuna attesa lo risolve.
    file().mkdirs()
    File(file(), "dentro").writeText("1")

    val error = runCatching { HeartbeatStore(file(), Patience.NONE).clear() }.exceptionOrNull()

    assertThat(error).isInstanceOf(IOException::class.java)
    assertThat(file().exists()).isTrue()
  }

  @Test
  fun clearDiUnFileCheNonCeNonE_unErrore() = runBlocking<Unit> {
    HeartbeatStore(file(), Patience.NONE).clear()

    assertThat(file().exists()).isFalse()
  }

  @Test
  fun unLettoreNonVedeMaiNessunBattitoMentreSiAggiorna() {
    val store = HeartbeatStore(file())
    runBlocking { store.beat(1L) }
    val stop = AtomicBoolean(false)
    val problems = ConcurrentLinkedQueue<String>()
    val reader = thread {
      var last = 0L
      while (!stop.get()) {
        val seen = runBlocking { store.lastBeatAt() }
        when {
          seen == null -> problems += "nessun battito"
          seen < last -> problems += "un battito vecchio dopo uno nuovo: $seen dopo $last"
          else -> last = seen
        }
        // Un lettore vero respira: uno che riapre il file senza fermarsi mai lo terrebbe aperto di continuo (su Windows nessuna attesa basta).
        Thread.sleep(1)
      }
    }

    try {
      runBlocking { for (at in 2L..200L) store.beat(at) }
    } finally {
      stop.set(true)
      reader.join()
    }

    assertThat(problems.toList().distinct()).isEmpty()
    // Sotto carico un battito in mezzo puo' non essere salvato (lo dice il log): quello che conta qui e' che nessun lettore abbia visto niente
    // di strano. Senza piu' lettori, il battito dopo si salva.
    runBlocking { store.beat(1_000L) }
    assertThat(runBlocking { store.lastBeatAt() }).isEqualTo(1_000L)
  }
}
