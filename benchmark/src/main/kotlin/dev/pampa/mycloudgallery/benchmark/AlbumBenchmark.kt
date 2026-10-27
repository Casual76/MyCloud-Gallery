package dev.pampa.mycloudgallery.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gli album (docs/fase7-progetto.md §5.4, riga P12 del §4.4; debito del 27/10: lo scenario era promesso e non c'era), sulla libreria mista da 100 mila
 * elementi con gli album sintetici ([AlbumBenchTarget]): un album a mano da 5.000 elementi, almeno 50 album, uno smart sulle 100 mila righe.
 *
 * Quattro prove, quattro nomi di `soglie.json` (scenario `album`):
 *  - `albumOpen`: dal tocco sull'album da 5.000 alla prima cella della sua griglia (`albumOpenMs`). Soglia: p90 <= 300 ms, regressione +15%;
 *  - `albumScroll`: fling avanti e indietro nell'album da 5.000. Soglia: la stessa guardia della Libreria, meno dell'1% di fotogrammi in ritardo
 *    (`jankPercent`), regressione +15% di `frameDurationCpuMs` P95;
 *  - `albumRecompute`: il ricalcolo dello smart sulle 100 mila righe **con il lavoro in corso** (l'app lo fa a ogni avvio, mentre la libreria si
 *    rilegge: `albumRecomputeMs` dal file di stato). Soglia: <= 2 s (la peggiore delle iterazioni), regressione +15%;
 *  - `collectionsOpen`: dal tocco sulla scheda Raccolte alla riga dell'album, con 50 album (`collectionsOpenMs`). Il progetto non fissa un tetto: solo la
 *    regressione (+15%) sul riferimento accettato.
 *
 * Come la ricerca: se il dataset non ha gli album, o la scheda non mostra la riga, la prova si **salta** con il motivo (`Assume`), mai un falso verde. Oggi
 * la variante di misura non monta gli album (vedi [AlbumBenchTarget]); le guardie esistenti (scorrimento, viewer, avvio) con la sincronizzazione degli
 * album condivisi in corso si guardano dalle loro prove, appena l'app puo' avviarla. Niente si misura sull'emulatore: i numeri valgono solo su S25 e Tab S9.
 */
@RunWith(AndroidJUnit4::class)
class AlbumBenchmark {

  @get:Rule
  val rule = MacrobenchmarkRule()

  @Before
  fun requireAlbums() {
    // Il marcatore prima della sonda: l'app crea gli album insieme al dataset, la prima volta.
    AlbumBenchTarget.requestAlbums()
    val readiness = BenchSession.requireLibrary()
    Assume.assumeTrue(AlbumBenchTarget.missingReason(readiness.dataset), BenchConfig.smoke || AlbumBenchTarget.seeded(readiness.dataset))
  }

  /** Dal tocco sull'album da 5.000 alla prima cella della sua griglia. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun albumOpen() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(AlbumOpenMetric(), FrameTimingMetric(), AlbumSeedMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(OPEN_ITERATIONS),
      setupBlock = {
        AlbumBenchState.reset()
        startLibrary()
        Assume.assumeTrue("La scheda Raccolte non mostra l'album '${AlbumBenchTarget.BIG_TITLE}' (vedi AlbumUi).", AlbumUi.openCollections(device, AlbumBenchTarget.BIG_TITLE) != null)
        device.waitForIdle()
      },
      measureBlock = {
        AlbumBenchState.openMs = AlbumUi.openAlbum(device, AlbumBenchTarget.BIG_TITLE)
        device.waitForIdle()
        Thread.sleep(SETTLE_MS)
        BenchUi.back(device)
      },
    )
  }

  /** Fling avanti e indietro nell'album da 5.000. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun albumScroll() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(FrameTimingMetric(), FrameJankMetric(), AlbumSeedMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(SCROLL_ITERATIONS),
      setupBlock = {
        startLibrary()
        Assume.assumeTrue("La scheda Raccolte non mostra l'album '${AlbumBenchTarget.BIG_TITLE}' (vedi AlbumUi).", AlbumUi.openCollections(device, AlbumBenchTarget.BIG_TITLE) != null)
        Assume.assumeTrue("L'album '${AlbumBenchTarget.BIG_TITLE}' non si apre.", AlbumUi.openAlbum(device, AlbumBenchTarget.BIG_TITLE) != null)
        device.waitForIdle()
      },
      measureBlock = {
        BenchUi.fling(device, towardsOlder = true, times = BenchConfig.repeats(FLINGS))
        BenchUi.fling(device, towardsOlder = false, times = BenchConfig.repeats(FLINGS))
      },
    )
  }

  /** Le Raccolte con 50 album: dal tocco sulla scheda alla riga dell'album grande. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun collectionsOpen() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(CollectionsOpenMetric(), FrameTimingMetric(), AlbumSeedMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(OPEN_ITERATIONS),
      setupBlock = {
        AlbumBenchState.reset()
        startLibrary()
      },
      measureBlock = {
        AlbumBenchState.collectionsMs = AlbumUi.openCollections(device, AlbumBenchTarget.BIG_TITLE)
        device.waitForIdle()
      },
    )
  }

  /** Il ricalcolo dello smart sulle 100 mila righe, con il lavoro in corso: il tempo lo scrive l'app, a ogni avvio con il marcatore. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun albumRecompute() {
    // La sonda: se l'app non sa ricalcolare lo smart sintetico non scrive lo stato, e la prova si salta senza consumare le iterazioni.
    AlbumBenchTarget.requestRecompute(on = true)
    BenchSession.stopApp()
    BenchSession.startApp()
    val probe = awaitRecompute()
    BenchSession.stopApp()
    Assume.assumeTrue(
      "L'app non scrive ${AlbumBenchTarget.RECOMPUTE_STATUS} (campo ${AlbumBenchTarget.FIELD_RECOMPUTE_MS}): il ricalcolo dello smart sintetico non e' ancora nel dataset.",
      probe != null,
    )
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(AlbumRecomputeMetric(), AlbumSeedMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(RECOMPUTE_ITERATIONS),
      setupBlock = {
        AlbumBenchState.reset()
        AlbumBenchTarget.requestRecompute(on = true)
        killProcess()
      },
      measureBlock = {
        startActivityAndWait()
        AlbumBenchState.recomputeMs = awaitRecompute()
      },
    )
    AlbumBenchTarget.requestRecompute(on = false)
  }

  /** Avvia l'app a freddo e aspetta la Libreria: da qui si passa alle Raccolte. */
  private fun MacrobenchmarkScope.startLibrary() {
    killProcess()
    startActivityAndWait()
    check(BenchUi.waitForLibrary(device, LIBRARY_TIMEOUT_MS)) { "La Libreria non si vede dopo l'avvio." }
    device.waitForIdle()
  }

  /** Il tempo del ricalcolo dallo stato scritto dall'app, aspettandolo; `null` se non arriva. */
  private fun awaitRecompute(): Double? =
    AlbumBenchTarget.awaitStatus(AlbumBenchTarget.RECOMPUTE_STATUS, RECOMPUTE_TIMEOUT_MS) {
      it.optString("stato") == "pronto" && it.has(AlbumBenchTarget.FIELD_RECOMPUTE_MS)
    }?.optDouble(AlbumBenchTarget.FIELD_RECOMPUTE_MS)

  private companion object {
    const val OPEN_ITERATIONS = 12
    const val SCROLL_ITERATIONS = 8
    const val RECOMPUTE_ITERATIONS = 5
    const val FLINGS = 6
    const val LIBRARY_TIMEOUT_MS = 20_000L
    const val SETTLE_MS = 500L
    const val RECOMPUTE_TIMEOUT_MS = 60_000L
  }
}
