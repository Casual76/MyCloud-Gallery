package dev.pampa.mycloudgallery.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.regex.Pattern
import org.junit.runner.RunWith

/**
 * Il generatore del Baseline Profile (§2.6, "Baseline Profile"): copre lo scorrimento, il pinch, l'apertura del viewer e
 * le info, cioe' i percorsi che la fluidita' a 120 Hz non puo' pagare a freddo con la compilazione JIT.
 *
 * Si lancia con `:app:generateBaselineProfile` (S25, o l'emulatore `Medium_Phone` in mancanza) oppure con
 * `tools\bench\generate-profile.ps1`, che lo esegue sulla variante `benchmarkRelease` (l'unica con il dataset e con il
 * ProfileInstallReceiver, vedi docs/bench/README.md). Il file va committato: `app/src/main/generated/baselineProfiles/`.
 *
 * **Non produce un profilo povero.** Se la Libreria non e' raggiungibile il test si salta (`Assume`): un profilo generato
 * dall'onboarding della Fase 2 sarebbe peggio di nessun profilo, perche' darebbe l'impressione di averlo.
 *
 * Gli argomenti dello strumentatore `androidx.benchmark.enabledRules=baselineprofile` (che il plugin imposta da solo e che
 * `generate-profile.ps1` passa a mano) tengono fuori le prove di misura, e viceversa `macrobenchmark` tiene fuori questo.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

  @get:Rule
  val rule = BaselineProfileRule()

  @Before
  fun requireLibrary() {
    BenchSession.requireLibrary()
  }

  @Test
  fun generate() {
    rule.collect(
      packageName = BenchSession.targetPackage,
      // Il profilo di avvio: le classi e i metodi del percorso a freddo (§2.6, "Percorso d'avvio") vanno anche nel DEX primario.
      includeInStartupProfile = true,
    ) {
      pressHome()
      startActivityAndWait()
      check(BenchUi.waitForLibrary(device, LIBRARY_TIMEOUT_MS)) { "La Libreria non si vede dopo l'avvio." }

      scrollLibrary()
      pinchThroughLevels()
      openViewer()
    }
  }

  private fun MacrobenchmarkScope.scrollLibrary() {
    BenchUi.fling(device, towardsOlder = true, times = 4)
    device.waitForIdle()
    BenchUi.fling(device, towardsOlder = false, times = 4)
    device.waitForIdle()
  }

  private fun MacrobenchmarkScope.pinchThroughLevels() {
    repeat(2) {
      BenchUi.pinch(device, close = true)
      device.waitForIdle()
      BenchUi.pinch(device, close = false)
      device.waitForIdle()
    }
  }

  /** Apre un'immagine, passa alla vicina, mostra le info (swipe su), zooma e torna alla Libreria con Indietro. */
  private fun MacrobenchmarkScope.openViewer() {
    val cell = BenchUi.centerCell(device) ?: return
    cell.click()
    device.waitForIdle()
    Thread.sleep(VIEWER_SETTLE_MS)
    // Pager: avanti e indietro di una pagina.
    val width = device.displayWidth
    val y = device.displayHeight / 2
    device.swipe(width * 3 / 4, y, width / 4, y, SWIPE_STEPS)
    device.waitForIdle()
    device.swipe(width / 4, y, width * 3 / 4, y, SWIPE_STEPS)
    device.waitForIdle()
    // Info: swipe su (foglio a meta') e giu' per richiuderlo.
    device.swipe(width / 2, y * 3 / 2, width / 2, y / 2, SWIPE_STEPS)
    device.waitForIdle()
    device.swipe(width / 2, y / 2, width / 2, y * 3 / 2, SWIPE_STEPS)
    device.waitForIdle()
    // Zoom e pan.
    BenchUi.doubleTapCenter(device)
    device.waitForIdle()
    BenchUi.panFromCenter(device, 1, 0)
    device.waitForIdle()
    BenchUi.doubleTapCenter(device)
    device.waitForIdle()
    // Un video, se il dataset ne ha uno in vista: la barra di scorrimento e il player entrano nel profilo.
    device.pressBack()
    device.waitForIdle()
    device.findObject(By.desc(Pattern.compile("^Video,.*")))?.let { video ->
      video.click()
      device.waitForIdle()
      Thread.sleep(VIEWER_SETTLE_MS)
      device.pressBack()
      device.waitForIdle()
    }
    val grid = BenchUi.grid(device)
    grid?.fling(Direction.DOWN)
  }

  private companion object {
    const val LIBRARY_TIMEOUT_MS = 25_000L
    const val VIEWER_SETTLE_MS = 1_000L
    const val SWIPE_STEPS = 12
  }
}
