package dev.pampa.mycloudgallery.core.mediastore.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.MetaPriority
import dev.pampa.mycloudgallery.core.mediastore.contract.MetadataPass
import dev.pampa.mycloudgallery.core.mediastore.contract.PauseReason
import dev.pampa.mycloudgallery.core.mediastore.contract.RunResult
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/** Il lavoro in background con un budget (D10) e la sincronizzazione legata al primo piano. */
@RunWith(AndroidJUnit4::class)
class RunnerAndForegroundTest : PhoneSyncTestBase() {

  /** Una fase META finta che conta le chiamate. */
  private class CountingPass(val result: RunResult = RunResult.Done) : MetadataPass {
    val calls = AtomicInteger()

    override suspend fun run(budget: Duration, priority: MetaPriority): RunResult {
      calls.incrementAndGet()
      return result
    }
  }

  @Test
  fun ilBudgetFermaLaScansioneCheRiprendeAlGiroDopo() = runBlocking<Unit> {
    (1L..7L).forEach { store.put(photo(it, taken = T0 + it * MINUTE)) }
    // Ogni lettura dell'orologio dei budget avanza di un minuto: il budget di 3 minuti finisce a meta' scansione.
    val sync = DefaultPhoneLibrarySync(
      source = store, access = access, catalog = catalog, reads = reads, clock = { now },
      monotonicMs = { mono.also { mono += MINUTE } }, dispatchers = InlineDispatchers, config = config,
    )
    val meta = CountingPass()
    val runner = DefaultPhoneSyncRunner(sync, meta, monotonicMs = { mono })

    val first = runner.run(3.minutes)
    assertThat(first).isEqualTo(RunResult.BudgetExhausted(remaining = null))
    assertThat(meta.calls.get()).isEqualTo(0)
    assertThat(mediaCount()).isLessThan(7)
    assertThat(catalog.mediaStoreCursor(PRIMARY)!!.scanEpoch).isNotNull()

    var result: RunResult = first
    var rounds = 0
    while (result != RunResult.Done && rounds < 10) {
      result = runner.run(30.minutes)
      rounds++
    }
    assertThat(result).isEqualTo(RunResult.Done)
    assertThat(mediaCount()).isEqualTo(7)
    assertThat(meta.calls.get()).isAtLeast(1)
  }

  @Test
  fun senzaPermessiIlLavoroEInPausa() = runBlocking<Unit> {
    setAccess(MediaAccess.NONE)
    val runner = DefaultPhoneSyncRunner(newSync(), CountingPass())
    assertThat(runner.run(8.minutes)).isEqualTo(RunResult.Paused(PauseReason.NO_PERMISSION))
  }

  @Test
  fun senzaPosizioneIlLavoroProsegueConVideoEMeta() = runBlocking<Unit> {
    setAccess(MediaAccess.FULL, mediaLocation = false)
    store.put(photo(1, taken = T0))
    store.put(video(2, taken = T0 + MINUTE))
    val meta = CountingPass(RunResult.Paused(PauseReason.MEDIA_LOCATION_DENIED))
    val result = DefaultPhoneSyncRunner(newSync(), meta).run(8.minutes)
    assertThat(meta.calls.get()).isEqualTo(1)
    assertThat(result).isEqualTo(RunResult.Paused(PauseReason.MEDIA_LOCATION_DENIED))
    assertThat(mediaCount()).isEqualTo(1)
  }

  @Test
  fun inPrimoPianoUnaModificaEsternaArrivaDaSola() = runBlocking<Unit> {
    store.put(photo(1, taken = T0))
    val meta = CountingPass()
    val sync = newSync(metadataPass = meta)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      sync.attach(scope)
      // Il giro di primo piano all'aggancio.
      waitUntil { mediaCount() == 1 }
      delay(100)
      // Una foto scattata con l'app aperta: il segnale del MediaStore fa partire un giro.
      store.put(photo(2, taken = T0 + MINUTE))
      waitUntil { mediaCount() == 2 }
      waitUntil { meta.calls.get() >= 2 }
      // Un secondo aggancio non duplica il ciclo.
      sync.attach(scope)
      sync.detach()
      store.put(photo(3, taken = T0 + 2 * MINUTE))
      delay(300)
      assertThat(mediaCount()).isEqualTo(2)
    } finally {
      sync.detach()
      scope.cancel()
    }
  }

  @Test
  fun inPrimoPianoIlCambioDeiPermessiFaUnGiro() = runBlocking<Unit> {
    store.put(photo(1, taken = T0))
    setAccess(MediaAccess.NONE)
    val sync = newSync()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      sync.attach(scope)
      // Il primo giro (senza permessi) e' finito; poi un attimo perche' l'osservatore dei permessi sia agganciato.
      waitUntil { sync.stats.snapshot.value.lastReason == SyncReason.FOREGROUND }
      delay(200)
      assertThat(mediaCount()).isEqualTo(0)
      setAccess(MediaAccess.FULL)
      // Il bilancio del giro si scrive alla fine: si aspetta quello, non solo la riga nel catalogo (P11: prima il test
      // poteva leggere il motivo del giro precedente se il catalogo arrivava un attimo prima del bilancio).
      waitUntil { mediaCount() == 1 && sync.stats.snapshot.value.lastReason != SyncReason.FOREGROUND }
      assertThat(sync.stats.snapshot.value.lastReason).isEqualTo(SyncReason.PERMISSION_CHANGED)
    } finally {
      sync.detach()
      scope.cancel()
    }
  }

  private suspend fun waitUntil(condition: suspend () -> Boolean) {
    withTimeout(15_000) {
      while (!condition()) delay(20)
    }
  }
}
