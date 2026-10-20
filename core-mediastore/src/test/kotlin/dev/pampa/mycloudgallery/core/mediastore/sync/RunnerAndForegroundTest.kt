package dev.pampa.mycloudgallery.core.mediastore.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreSource
import dev.pampa.mycloudgallery.core.mediastore.contract.MetaPriority
import dev.pampa.mycloudgallery.core.mediastore.contract.MetadataPass
import dev.pampa.mycloudgallery.core.mediastore.contract.PauseReason
import dev.pampa.mycloudgallery.core.mediastore.contract.RunResult
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
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

  /** Una fase META finta che risponde con gli esiti dati, uno per fetta, poi `Done`. */
  private class SlicedPass(vararg results: RunResult) : MetadataPass {
    private val queue = java.util.concurrent.ConcurrentLinkedQueue(results.toList())
    val calls = AtomicInteger()

    override suspend fun run(budget: Duration, priority: MetaPriority): RunResult {
      calls.incrementAndGet()
      return queue.poll() ?: RunResult.Done
    }
  }

  // La pausa fra due fette di META in primo piano e' breve nel test (di norma 5 secondi).
  override val config = PhoneSyncConfig(batchSize = 3, vanishCap = 2, vanishAlarmMin = 50, foregroundMetaPause = 20.milliseconds)

  /**
   * GA-6: in primo piano la fase META lavora per 30 secondi e poi si fermava fino al prossimo evento: nella prima lettura di
   * migliaia di foto luogo, foto in movimento e raffiche restavano incompleti finche' non si riapriva l'app. Adesso, finche' il
   * budget finisce prima del lavoro, parte la fetta dopo; si ferma a `Done`.
   */
  @Test
  fun ga6_inPrimoPianoLaFaseMetaContinuaAFetteFinoAllaFine() = runBlocking<Unit> {
    store.put(photo(1, taken = T0))
    val meta = SlicedPass(RunResult.BudgetExhausted(remaining = null), RunResult.BudgetExhausted(remaining = 7), RunResult.Done)
    val sync = newSync(metadataPass = meta)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      sync.attach(scope)
      waitUntil { meta.calls.get() >= 3 }
      // Finita: nessuna fetta in piu' senza un evento nuovo.
      delay(300)
      assertThat(meta.calls.get()).isEqualTo(3)
    } finally {
      sync.detach()
      scope.cancel()
    }
  }

  @Test
  fun ga6_unaPausaOUnFallimentoFermanoLeFetteSenzaGirareAVuoto() = runBlocking<Unit> {
    store.put(photo(1, taken = T0))
    val meta = SlicedPass(RunResult.Paused(PauseReason.MEDIA_LOCATION_DENIED))
    val sync = newSync(metadataPass = meta)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      sync.attach(scope)
      waitUntil { meta.calls.get() >= 1 }
      delay(300)
      assertThat(meta.calls.get()).isEqualTo(1)
    } finally {
      sync.detach()
      scope.cancel()
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

  /**
   * In primo piano ci si iscrive ai segnali (cambiamenti del MediaStore, permessi) **prima** della prima lettura: una foto scattata mentre
   * la prima lettura e' in corso, e prima che l'iscrizione parta, non lascia traccia e la Libreria non la mostra fino al segnale dopo (il
   * ciclo lanciava gli ascoltatori con un `launch` che parte quando il thread e' libero, e leggeva subito; i test sotto carico cadevano per
   * un timeout di 20 s, e ci giravano intorno con `delay(100)`). Qui il ciclo gira su un thread solo, come un telefono carico: se gli
   * ascoltatori non sono partiti prima della lettura, la sorgente li trova ancora spenti.
   */
  @Test
  fun inPrimoPianoCiSiIscriveAiSegnaliPrimaDellaPrimaLettura() = runBlocking<Unit> {
    store.put(photo(1, taken = T0))
    val listening = AtomicBoolean(false)
    val listeningAtFirstRead = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
    val watched = object : MediaStoreSource by store {
      override suspend fun volumes(): Set<String> {
        listeningAtFirstRead.compareAndSet(null, listening.get())
        return store.volumes()
      }

      override fun changes(): Flow<Unit> = store.changes().onStart { listening.set(true) }
    }
    val sync = DefaultPhoneLibrarySync(
      source = watched, access = access, catalog = catalog, reads = reads, clock = { now }, monotonicMs = { mono },
      dispatchers = InlineDispatchers, config = config,
    )
    val oneThread = Dispatchers.Default.limitedParallelism(1)
    val scope = CoroutineScope(SupervisorJob() + oneThread)
    try {
      sync.attach(scope)
      waitUntil { mediaCount() == 1 }
      assertThat(listeningAtFirstRead.get()).isTrue()
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
