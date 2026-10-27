package dev.pampa.mycloudgallery.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I volti (docs/fase8-progetto.md §5.4; debito del 27/10: lo scenario `volti` era promesso e non c'era), sulla libreria mista da 100 mila elementi.
 *
 * Due prove, due nomi di `soglie.json` (scenario `volti`):
 *  - `facesEngines`: i tempi dei motori dei volti misurati **dentro l'app** ([FacesBenchTarget]): p95 di YuNet a 1024 px e di SFace per volto, millisecondi
 *    per foto (il giro intero: decodifica, rilevamento, allineamento, qualita', impronta) e PSS. Solo regressione, +15% sul riferimento accettato (§5.4: la
 *    misura vera di riferimento arriva con F104, sull'S25 e sul Tab S9);
 *  - `facesLoadedScroll`: la guardia di scorrimento della Libreria (livello Tutte, stile quadrato) **con il calcolo dei volti in corso**: meno dell'1% di
 *    fotogrammi in ritardo (`jankPercent`), regressione +15% di `frameDurationCpuMs` P95. E' "le guardie esistenti non devono peggiorare con il calcolo dei
 *    volti in corso" del §5.4; apertura del viewer e avvio si guardano dalle loro prove, con lo stesso marcatore, appena il dataset lo permette.
 *
 * Come la ricerca: se l'app non scrive lo stato dei volti la prova si **salta** con il motivo (`Assume`), mai un falso verde. Oggi la variante di misura non
 * ha la parte dei volti (vedi [FacesBenchTarget]). Niente si misura sull'emulatore: i numeri valgono solo su S25 e Tab S9.
 */
@RunWith(AndroidJUnit4::class)
class FacesBenchmark {

  @get:Rule
  val rule = MacrobenchmarkRule()

  @Before
  fun requireLibrary() {
    BenchSession.requireLibrary()
  }

  /** I motori dei volti misurati dall'app a ogni avvio. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun facesEngines() {
    probe(measure = true, load = false)
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(FacesEnginesMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(ENGINES_ITERATIONS),
      setupBlock = {
        FacesBenchState.reset()
        FacesBenchTarget.request(measure = true, load = false)
        killProcess()
      },
      measureBlock = {
        startActivityAndWait()
        FacesBenchState.status = FacesBenchTarget.awaitStatus(STATUS_TIMEOUT_MS)
      },
    )
    FacesBenchTarget.request(measure = false, load = false)
  }

  /** Fling avanti e indietro nella Libreria, mentre il calcolo dei volti lavora. */
  @OptIn(ExperimentalMetricApi::class)
  @Test
  fun facesLoadedScroll() {
    probe(measure = true, load = true)
    rule.measureRepeated(
      packageName = BenchSession.targetPackage,
      metrics = listOf(FrameTimingMetric(), FrameJankMetric(), FacesLoadMetric(), LibraryMixMetric()),
      compilationMode = BenchConfig.compilationMode,
      iterations = BenchConfig.iterations(SCROLL_ITERATIONS),
      setupBlock = {
        FacesBenchState.reset()
        FacesBenchTarget.request(measure = true, load = true)
        killProcess()
        startActivityAndWait()
        check(BenchUi.waitForLibrary(device, LIBRARY_TIMEOUT_MS)) { "La Libreria non si vede dopo l'avvio." }
        FacesBenchState.status = FacesBenchTarget.awaitStatus(STATUS_TIMEOUT_MS, needLoad = true)
        Assume.assumeTrue("Il calcolo dei volti non e' in corso (${FacesBenchTarget.FIELD_LOADED} manca in ${FacesBenchTarget.STATUS}).", FacesBenchState.status != null)
        // Come la prova `scroll_all_square`: livello Tutte, stile quadrato, in fondo (i piu' recenti).
        check(BenchUi.selectStyle(device, BenchStyle.SQUARE)) { "Lo stile '${BenchStyle.SQUARE.label}' non si sceglie dal menu della vista." }
        check(BenchUi.selectLevel(device, BenchLevel.ALL)) { "La griglia non c'e' per cambiare livello." }
        device.waitForIdle()
      },
      measureBlock = {
        BenchUi.fling(device, towardsOlder = true, times = BenchConfig.repeats(FLINGS))
        BenchUi.fling(device, towardsOlder = false, times = BenchConfig.repeats(FLINGS))
      },
    )
    FacesBenchTarget.request(measure = false, load = false)
  }

  /** La sonda: l'app sa misurare i motori dei volti? Se no la prova si salta, prima di consumare le iterazioni. */
  private fun probe(measure: Boolean, load: Boolean) {
    FacesBenchTarget.request(measure, load)
    BenchSession.stopApp()
    BenchSession.startApp()
    val status = FacesBenchTarget.awaitStatus(STATUS_TIMEOUT_MS, needLoad = load)
    BenchSession.stopApp()
    FacesBenchTarget.request(measure = false, load = false)
    Assume.assumeTrue(FacesBenchTarget.missingReason(), status != null)
  }

  private companion object {
    const val ENGINES_ITERATIONS = 5
    const val SCROLL_ITERATIONS = 8
    const val FLINGS = 6
    const val LIBRARY_TIMEOUT_MS = 20_000L

    /** La misura dei motori su un telefono sono decine di secondi: il modello si apre, si riscalda, poi 40 giri. */
    const val STATUS_TIMEOUT_MS = 180_000L
  }
}
