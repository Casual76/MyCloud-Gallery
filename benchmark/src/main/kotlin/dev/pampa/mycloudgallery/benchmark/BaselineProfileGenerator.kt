package dev.pampa.mycloudgallery.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
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
      levelsAndStyles()
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

  /** I segmenti dei livelli e il menu della vista: ogni livello e lo stile giustificato si compongono e scorrono almeno una volta. */
  private fun MacrobenchmarkScope.levelsAndStyles() {
    for (level in listOf(BenchLevel.ALL, BenchLevel.MONTHS, BenchLevel.YEARS)) {
      BenchUi.selectLevel(device, level)
      BenchUi.fling(device, towardsOlder = true, times = 2)
      device.waitForIdle()
    }
    if (BenchUi.selectStyle(device, BenchStyle.JUSTIFIED)) {
      BenchUi.selectLevel(device, BenchLevel.DAYS)
      BenchUi.fling(device, towardsOlder = true, times = 2)
      device.waitForIdle()
      BenchUi.selectStyle(device, BenchStyle.SQUARE)
    }
    BenchUi.selectLevel(device, BenchLevel.DAYS)
  }

  private fun MacrobenchmarkScope.pinchThroughLevels() {
    repeat(2) {
      BenchUi.pinch(device, close = true)
      device.waitForIdle()
      BenchUi.pinch(device, close = false)
      device.waitForIdle()
    }
  }

  /**
   * Apre un'immagine, passa alla vicina e torna, zooma e sposta, apre le informazioni (swipe su) e le chiude, chiude il
   * viewer; poi, se ce n'e' uno in vista, un video. Ogni Indietro solo se c'e' qualcosa da chiudere: a viewer chiuso
   * Indietro uscirebbe dall'app (e il resto del giro finirebbe sulla schermata Home).
   */
  private fun MacrobenchmarkScope.openViewer() {
    if (!BenchUi.tapCell(device, BenchUi.centerCell(device)) || !BenchUi.awaitViewer(device)) return
    device.waitForIdle()
    Thread.sleep(VIEWER_SETTLE_MS)
    // Pager: avanti e indietro di una pagina.
    BenchUi.ensureForeground(device)
    val width = device.displayWidth
    val y = device.displayHeight / 2
    device.swipe(width * 3 / 4, y, width / 4, y, SWIPE_STEPS)
    device.waitForIdle()
    device.swipe(width / 4, y, width * 3 / 4, y, SWIPE_STEPS)
    device.waitForIdle()
    // Zoom e pan, poi di nuovo intera.
    BenchUi.doubleTapCenter(device)
    device.waitForIdle()
    BenchUi.panFromCenter(device, 1, 0)
    device.waitForIdle()
    BenchUi.doubleTapCenter(device)
    device.waitForIdle()
    Thread.sleep(VIEWER_SETTLE_MS)
    // Info: swipe su (foglio a meta'), chiuso con Indietro se si e' aperto.
    if (BenchUi.viewerOpen(device)) {
      BenchUi.ensureForeground(device)
      device.swipe(width / 2, y * 3 / 2, width / 2, y / 2, SWIPE_STEPS)
      if (BenchUi.awaitFresh(device, By.text(INFO_TITLE), INFO_TIMEOUT_MS)) {
        Thread.sleep(VIEWER_SETTLE_MS)
        BenchUi.back(device)
        device.wait(Until.gone(By.text(INFO_TITLE)), INFO_TIMEOUT_MS)
      }
    }
    BenchUi.closeViewer(device)
    if (BenchUi.awaitGrid(device) == null) return
    // Un video, se il dataset ne ha uno in vista: la barra di scorrimento e il player entrano nel profilo.
    device.findObject(By.desc(Pattern.compile("^Video,.*")))?.let { video ->
      if (BenchUi.tapCell(device, video) && BenchUi.awaitViewer(device)) {
        Thread.sleep(VIEWER_SETTLE_MS)
        BenchUi.closeViewer(device)
      }
    }
    BenchUi.fling(device, towardsOlder = false, times = 1)
  }

  private companion object {
    const val LIBRARY_TIMEOUT_MS = 25_000L
    const val VIEWER_SETTLE_MS = 1_000L
    const val SWIPE_STEPS = 12
    const val INFO_TIMEOUT_MS = 2_000L

    /** Il titolo del pannello informazioni (`viewer_info_title`). */
    const val INFO_TITLE = "Informazioni"
  }
}
