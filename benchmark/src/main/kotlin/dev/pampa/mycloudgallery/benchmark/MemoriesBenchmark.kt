package dev.pampa.mycloudgallery.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I ricordi (docs/fase9-progetto.md §5.4, riga P12 del §4.4; scenario `ricordi`), sulla libreria mista da 100 mila elementi con lo **scaffale dei ricordi
 * seminato** ([MemoriesBenchTarget]): cinque ricordi veri nel file che l'app legge al montaggio, e le righe con le date di "oggi" di 1 e 3 anni fa.
 *
 * Cinque prove, cinque scenari di `soglie.json`:
 *  - `memoryOpen` (`ricordi-apertura`): dal tocco su un ricordo di Raccolte al primo fotogramma della sua griglia (`memoryOpenMs`). Soglia: p90 <= 300 ms, +15%;
 *  - `memoryPlayer` (`ricordi-presentazione`): la presentazione per 30 s con il movimento. Soglia: meno dell'1% di fotogrammi in ritardo (`jankPercent`, come la
 *    Libreria) e il fotogramma piu' lungo (`maxFrameCpuMs`) entro 16,7 ms; guardia +10% (pavimento 0,15 punti) e +15%;
 *  - `memoriesGenerate` (`ricordi-generazione`): la generazione su 100 mila (sezioni di traccia `MCG:memories.generate`, passo pieno, e
 *    `MCG:memories.generate.light`, passo leggero). Soglia: <= 2.000 ms e <= 300 ms, +15%;
 *  - `collectionsScroll` (`ricordi-raccolte`): lo scorrimento delle Raccolte con le sezioni Ricordi e Luoghi: la guardia della Libreria (meno dell'1%);
 *  - `memoriesLoadedScroll` (`ricordi-scroll-carico`): la guardia di scorrimento della Libreria (livello Tutte, stile quadrato) **con la generazione dei ricordi
 *    in corso** (come `facesLoadedScroll`): le guardie esistenti non peggiorano.
 *
 * Come la ricerca e gli album: se il dataset non ha lo scaffale seminato, o l'app non mostra un ricordo da aprire, la prova si **salta** con il motivo (`Assume`),
 * mai un falso verde. Niente si misura sull'emulatore: i numeri valgono solo su S25 e Tab S9 (`compare.ps1 -Smoke` sugli emulatori). Niente rete.
 */
@RunWith(AndroidJUnit4::class)
class MemoriesBenchmark {

  @get:Rule
  val rule = MacrobenchmarkRule()

  @Before
  fun requireMemories() {
    // Il pilota della generazione si accende solo dalle prove che lo vogliono: un marcatore rimasto dalla prova di prima falserebbe le altre.
    stopDriver()
    val readiness = BenchSession.requireLibrary()
    Assume.assumeTrue(MemoriesBenchTarget.missingReason(readiness.dataset), MemoriesBenchTarget.shelfSeeded(readiness.dataset))
    Phase9BenchState.reset()
    MemoriesBenchState.reset()
  }

  @After
  fun cleanUp() = stopDriver()

  /** Dal tocco su un ricordo di Raccolte alla prima cella della sua griglia. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun memoryOpen() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(MemoryOpenMetric(), FrameTimingMetric(), MemoriesSeedMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(OPEN_ITERATIONS),
      setupBlock = {
        Phase9BenchState.reset()
        openCollections()
      },
      measureBlock = {
        Phase9BenchState.memoryOpenMs = MemoriesUi.openMemory(device)
        device.waitForIdle()
        Thread.sleep(SETTLE_MS)
        BenchUi.back(device)
      },
    )
  }

  /** La presentazione di un ricordo per 30 s (6 s nello smoke), con il movimento: jank e fotogramma piu' lungo. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun memoryPlayer() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(FrameTimingMetric(), FrameJankMetric(), PresentationMetric(), MemoriesSeedMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(PLAYER_ITERATIONS),
      setupBlock = {
        Phase9BenchState.reset()
        openCollections()
        Assume.assumeTrue("Un ricordo dello scaffale non si apre (vedi MemoriesUi).", MemoriesUi.openMemory(device) != null)
        device.waitForIdle()
      },
      measureBlock = {
        check(MemoriesUi.play(device)) { "La presentazione non parte dalla pagina del ricordo (vedi MemoriesUi)." }
        val started = System.currentTimeMillis()
        val duration = if (BenchConfig.smoke) SMOKE_PLAYER_MS else PLAYER_MS
        while (System.currentTimeMillis() - started < duration) {
          // Un ricordo corto finisce prima dei 30 s: "Rivedi" lo fa ripartire, cosi' il movimento dura quanto serve.
          if (MemoriesUi.playerEnded(device) && !MemoriesUi.restart(device)) break
          Thread.sleep(PLAYER_POLL_MS)
        }
        Phase9BenchState.presentationMs = System.currentTimeMillis() - started
        BenchUi.back(device)
      },
    )
  }

  /** La generazione su 100 mila: un giro pieno e uno leggero a ogni avvio (il pilota dell'app), dalle sezioni di traccia del motore. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun memoriesGenerate() {
    // La sonda: se l'app non sa generare (il pilota non scrive lo stato, o i ricordi non si montano) la prova si salta senza consumare le iterazioni.
    val probe = probeDriver(load = false)
    Assume.assumeTrue(driverMissing(probe), probe?.optString("stato") == "pronto")
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(MemoriesGenerateMetric(), MemoriesSeedMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(GENERATE_ITERATIONS),
      setupBlock = {
        MemoriesBenchState.reset()
        BenchAppFiles.touch(MemoriesBenchTarget.GENERATE_MARKER)
        BenchAppFiles.remove(MemoriesBenchTarget.STATUS)
        killProcess()
      },
      measureBlock = {
        startActivityAndWait()
        MemoriesBenchState.status = BenchAppFiles.awaitStatus(MemoriesBenchTarget.STATUS, GENERATE_TIMEOUT_MS) { it.optString("stato") == "pronto" && it.has("passoLeggeroMs") }
        // Le sezioni di traccia si scrivono alla fine del giro: un attimo perche' arrivino nel buffer di Perfetto.
        Thread.sleep(SETTLE_MS)
      },
    )
  }

  /** Fling nelle Raccolte (con le sezioni Ricordi e Luoghi) avanti e indietro: la guardia di scorrimento della Libreria. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun collectionsScroll() {
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(FrameTimingMetric(), FrameJankMetric(), MemoriesSeedMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(SCROLL_ITERATIONS),
      setupBlock = {
        openCollections()
        device.waitForIdle()
      },
      measureBlock = {
        // Le Raccolte si aprono in cima: prima si scende (la direzione e' quella in cui scorre il contenuto), poi si risale.
        BenchUi.fling(device, towardsOlder = false, times = BenchConfig.repeats(FLINGS))
        BenchUi.fling(device, towardsOlder = true, times = BenchConfig.repeats(FLINGS))
      },
    )
  }

  /** Fling avanti e indietro nella Libreria mentre la generazione dei ricordi lavora (livello Tutte, stile quadrato: la prova `scroll_all_square`). */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun memoriesLoadedScroll() {
    val probe = probeDriver(load = true)
    Assume.assumeTrue(driverMissing(probe), probe?.optString("stato") == "pronto")
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(FrameTimingMetric(), FrameJankMetric(), MemoriesComputingMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(SCROLL_ITERATIONS),
      setupBlock = {
        Phase9BenchState.reset()
        BenchAppFiles.touch(MemoriesBenchTarget.LOAD_MARKER)
        BenchAppFiles.remove(MemoriesBenchTarget.STATUS)
        startLibrary()
        // La generazione deve lavorare davvero: si aspetta il primo giro (poi continua, a bassa priorita'), altrimenti e' lo scorrimento di sempre.
        val status = BenchAppFiles.awaitStatus(MemoriesBenchTarget.STATUS, LOAD_TIMEOUT_MS) { it.optString("stato") == "pronto" && it.optBoolean("calcoloInCorso") }
        Assume.assumeTrue("La generazione dei ricordi non e' in corso (calcoloInCorso manca in ${MemoriesBenchTarget.STATUS}).", status != null && status.optString("stato") == "pronto")
        Phase9BenchState.memoriesComputing = true
        check(BenchUi.selectStyle(device, BenchStyle.SQUARE)) { "Lo stile '${BenchStyle.SQUARE.label}' non si sceglie dal menu della vista." }
        check(BenchUi.selectLevel(device, BenchLevel.ALL)) { "La griglia non c'e' per cambiare livello." }
        device.waitForIdle()
      },
      measureBlock = {
        BenchUi.fling(device, towardsOlder = true, times = BenchConfig.repeats(FLINGS))
        BenchUi.fling(device, towardsOlder = false, times = BenchConfig.repeats(FLINGS))
      },
    )
  }

  /** Avvia l'app a freddo e aspetta la Libreria. */
  private fun MacrobenchmarkScope.startLibrary() {
    killProcess()
    startActivityAndWait()
    check(BenchUi.waitForLibrary(device, LIBRARY_TIMEOUT_MS)) { "La Libreria non si vede dopo l'avvio." }
    device.waitForIdle()
  }

  /** Avvio a freddo, Raccolte aperte e la riga di un ricordo visibile (lo scaffale seminato si disegna dal file); altrimenti la prova si salta. */
  private fun MacrobenchmarkScope.openCollections() {
    startLibrary()
    Assume.assumeTrue("La scheda Raccolte non si apre (vedi MemoriesUi).", MemoriesUi.openCollections(device))
    Assume.assumeTrue(
      "Raccolte non mostra nessun ricordo da aprire: lo scaffale seminato non e' stato montato (i ricordi si montano dopo il primo disegno, senza NAS solo nella variante di misura).",
      MemoriesUi.memoryRow(device) != null,
    )
    device.waitForIdle()
  }

  /** Accende il pilota ([load] = di continuo), riavvia l'app e aspetta il suo stato; poi ferma tutto. `null` se l'app non scrive lo stato. */
  private fun probeDriver(load: Boolean): org.json.JSONObject? {
    BenchAppFiles.touch(if (load) MemoriesBenchTarget.LOAD_MARKER else MemoriesBenchTarget.GENERATE_MARKER)
    BenchAppFiles.remove(MemoriesBenchTarget.STATUS)
    BenchSession.stopApp()
    BenchSession.startApp()
    val status = BenchAppFiles.awaitStatus(MemoriesBenchTarget.STATUS, PROBE_TIMEOUT_MS) { it.optString("stato") == "pronto" }
    BenchSession.stopApp()
    stopDriver()
    return status
  }

  private fun driverMissing(status: org.json.JSONObject?): String =
    if (status == null) {
      "L'app non scrive ${MemoriesBenchTarget.STATUS}: il pilota della generazione non c'e' nella variante di misura (BenchMemoriesDriver)."
    } else {
      "Il pilota della generazione non e' pronto: stato '${status.optString("stato")}' ${status.optString("errore")}".trim()
    }

  private fun stopDriver() {
    BenchAppFiles.remove(MemoriesBenchTarget.GENERATE_MARKER)
    BenchAppFiles.remove(MemoriesBenchTarget.LOAD_MARKER)
    BenchAppFiles.remove(MemoriesBenchTarget.STATUS)
  }

  private companion object {
    const val OPEN_ITERATIONS = 12
    const val PLAYER_ITERATIONS = 3
    const val GENERATE_ITERATIONS = 5
    const val SCROLL_ITERATIONS = 8
    const val FLINGS = 6
    const val LIBRARY_TIMEOUT_MS = 20_000L
    const val SETTLE_MS = 500L
    const val PLAYER_MS = 30_000L
    const val SMOKE_PLAYER_MS = 6_000L
    const val PLAYER_POLL_MS = 500L

    /** Una generazione su 100 mila: secondi sul telefono, piu' sull'emulatore; il pilota aspetta anche l'istantanea della Libreria. */
    const val GENERATE_TIMEOUT_MS = 120_000L
    const val PROBE_TIMEOUT_MS = 180_000L
    const val LOAD_TIMEOUT_MS = 120_000L
  }
}
