package dev.pampa.mycloudgallery.feature.search

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisWait
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisFlow
import dev.pampa.mycloudgallery.feature.search.models.AnalysisScreen
import dev.pampa.mycloudgallery.feature.search.models.NoAnalysisHandlers
import dev.pampa.mycloudgallery.feature.search.models.NoSearchSettingsHandlers
import dev.pampa.mycloudgallery.feature.search.models.SearchSettingsScreen
import dev.pampa.mycloudgallery.feature.search.testing.SearchSamples
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Grafica XU (12/11/2026): gli screenshot della pagina "Analisi delle foto" e della testata di Ricerca e modelli, **alti quanto lo schermo vero**
 * (telefono 360x800 dp; tablet 1280x800 in orizzontale e 800x1280 in verticale), per ogni stato, chiaro e scuro, piu' il testo a 1,3x.
 * `gradlew :feature-search:recordRoborazziDebug --tests "*AnalysisScreenshot*"` scrive i PNG in docs/screenshots/beta-grafica/X-indice-visibile.
 */
internal enum class AnalysisShot(val file: String) {
  RUNNING("analisi-in-corso"),
  STEP("analisi-in-corso-a-meta-passo"),
  QUEUED("analisi-in-coda"),
  PAUSED("analisi-in-pausa-telefono-caldo"),
  NIGHT("analisi-in-pausa-di-notte"),
  QUIET("analisi-in-corso-con-foto-nascoste"),
  READY("analisi-tutto-pronto"),
  MODEL("analisi-modello-mancante"),
  NOT_HERE("analisi-non-analizza-qui"),
  PROBLEM("analisi-problema"),
  DISABLED("analisi-spenta"),
  SETTINGS("ricerca-e-modelli-testata"),
  SETTINGS_QUEUED("ricerca-e-modelli-testata-in-coda"),

  // Giro 4.
  SETTINGS_READY_PARTIAL("ricerca-e-modelli-tutto-pronto-copertura-parziale"),
  BETWEEN("analisi-in-corso-fra-due-tappe"),
  PREPARATION("analisi-in-attesa-foto-in-preparazione"),
  NAS("analisi-in-pausa-nas"),
  ;

  @Composable
  fun Content() {
    val s = AnalysisScenes
    when (this) {
      RUNNING -> AnalysisScreen(s.RUNNING, s.flow(), s.thumbnails, NoAnalysisHandlers, onBack = {})
      STEP -> AnalysisScreen(s.RUNNING, s.flow(), s.thumbnails, NoAnalysisHandlers, onBack = {}, frozenShift = HALF_STEP)
      QUEUED -> AnalysisScreen(LibraryAnalysisStatus.Queued(2_700, 12_300, 15_000), s.restFlow(), s.thumbnails, NoAnalysisHandlers, onBack = {})
      PAUSED -> AnalysisScreen(s.PAUSED, s.restFlow(), s.thumbnails, NoAnalysisHandlers, onBack = {})
      NIGHT -> AnalysisScreen(
        LibraryAnalysisStatus.Paused(LibraryAnalysisWait.NIGHT_WINDOW, 2_700, 12_300, 15_000),
        AnalysisFlow.EMPTY,
        s.thumbnails,
        NoAnalysisHandlers,
        onBack = {},
      )
      QUIET -> AnalysisScreen(s.RUNNING, s.quietFlow(), s.thumbnails, NoAnalysisHandlers, onBack = {})
      READY -> AnalysisScreen(LibraryAnalysisStatus.AllReady, s.restFlow(), s.thumbnails, NoAnalysisHandlers, onBack = {})
      MODEL -> AnalysisScreen(LibraryAnalysisStatus.ModelMissing, AnalysisFlow.EMPTY, s.thumbnails, NoAnalysisHandlers, onBack = {})
      NOT_HERE -> AnalysisScreen(LibraryAnalysisStatus.NotHere(ready = 12_300, total = 15_000), AnalysisFlow.EMPTY, s.thumbnails, NoAnalysisHandlers, onBack = {})
      PROBLEM -> AnalysisScreen(LibraryAnalysisStatus.Problem("AI_STORAGE_LOW"), s.restFlow(), s.thumbnails, NoAnalysisHandlers, onBack = {})
      DISABLED -> AnalysisScreen(LibraryAnalysisStatus.Disabled, AnalysisFlow.EMPTY, s.thumbnails, NoAnalysisHandlers, onBack = {})
      SETTINGS -> SearchSettingsScreen(SearchSamples.settingsIndexer, false, true, true, true, NoSearchSettingsHandlers, onBack = {})
      SETTINGS_READY_PARTIAL -> SearchSettingsScreen(SearchSamples.settingsAllReadyPartial, false, true, true, true, NoSearchSettingsHandlers, onBack = {})
      BETWEEN -> s.flow().let { AnalysisScreen(s.RUNNING, AnalysisFlow(null, it.recent, it.next), s.thumbnails, NoAnalysisHandlers, onBack = {}) }
      PREPARATION -> AnalysisScreen(
        LibraryAnalysisStatus.Paused(LibraryAnalysisWait.PREPARATION, 2_700, 12_300, 15_000),
        s.restFlow(),
        s.thumbnails,
        NoAnalysisHandlers,
        onBack = {},
      )
      NAS -> AnalysisScreen(LibraryAnalysisStatus.Paused(LibraryAnalysisWait.NAS, 2_700, 12_300, 15_000), s.restFlow(), s.thumbnails, NoAnalysisHandlers, onBack = {})
      SETTINGS_QUEUED -> SearchSettingsScreen(
        SearchSamples.settingsIndexer.copy(
          indexer = SearchSamples.settingsIndexer.indexer.copy(analysis = LibraryAnalysisStatus.Paused(LibraryAnalysisWait.THERMAL, 2_700, 12_300, 15_000)),
        ),
        false,
        true,
        true,
        true,
        NoSearchSettingsHandlers,
        onBack = {},
      )
    }
  }

  private companion object {
    const val HALF_STEP = 0.5f
  }
}

private const val X_DIR = "../docs/screenshots/beta-grafica/X-indice-visibile"

internal abstract class AnalysisScreenshotBase(private val shot: AnalysisShot, private val dark: Boolean, private val scale: Float?, private val device: String) {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun capture() {
    rule.setSearchContent(dark = dark, fontScale = scale) { shot.Content() }
    rule.waitForIdle()
    val name = "${shot.file}-${if (dark) "scuro" else "chiaro"}-$device${if (scale != null) "-testo-grande" else ""}"
    rule.onRoot().captureRoboImage("$X_DIR/$name.png")
  }

  companion object {
    fun every(vararg shots: AnalysisShot, large: Boolean = false): List<Array<Any?>> = shots.flatMap { shot ->
      buildList {
        add(arrayOf<Any?>(shot, false, null))
        add(arrayOf<Any?>(shot, true, null))
        if (large) add(arrayOf<Any?>(shot, false, LARGE_TEXT))
      }
    }

    private const val LARGE_TEXT = 1.3f
  }
}

/** Il telefono: ogni stato, chiaro e scuro; il testo grande per quelli con piu' parole. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w360dp-h800dp-xhdpi")
internal class AnalysisPhoneScreenshotTest(shot: AnalysisShot, dark: Boolean, scale: Float?) : AnalysisScreenshotBase(shot, dark, scale, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1} testo={2}")
    fun data(): List<Array<Any?>> =
      every(AnalysisShot.RUNNING, AnalysisShot.PAUSED, AnalysisShot.PROBLEM, AnalysisShot.SETTINGS, large = true) +
        every(
          AnalysisShot.STEP,
          AnalysisShot.QUIET,
          AnalysisShot.QUEUED,
          AnalysisShot.NIGHT,
          AnalysisShot.READY,
          AnalysisShot.MODEL,
          AnalysisShot.NOT_HERE,
          AnalysisShot.DISABLED,
          AnalysisShot.SETTINGS_QUEUED,
          AnalysisShot.SETTINGS_READY_PARTIAL,
          AnalysisShot.BETWEEN,
          AnalysisShot.PREPARATION,
          AnalysisShot.NAS,
        )
  }
}

/** XU2-13 (giro 4): il telefono in orizzontale (800 x 360 dp): testata e nastro affiancati, il nastro sopra la piega. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w800dp-h360dp-land-xhdpi")
internal class AnalysisPhoneLandscapeScreenshotTest(shot: AnalysisShot, dark: Boolean, scale: Float?) :
  AnalysisScreenshotBase(shot, dark, scale, "telefono-orizzontale") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1} testo={2}")
    fun data(): List<Array<Any?>> = every(AnalysisShot.RUNNING, AnalysisShot.PAUSED)
  }
}

/** Il tablet in orizzontale: testata e nastro affiancati, il nastro su tutta la larghezza. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w1280dp-h800dp-hdpi")
internal class AnalysisTabletScreenshotTest(shot: AnalysisShot, dark: Boolean, scale: Float?) : AnalysisScreenshotBase(shot, dark, scale, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1} testo={2}")
    fun data(): List<Array<Any?>> = every(AnalysisShot.RUNNING, AnalysisShot.PAUSED, AnalysisShot.READY, AnalysisShot.NOT_HERE, AnalysisShot.SETTINGS)
  }
}

/** Il tablet in verticale: uno sopra l'altro, con le misure grandi. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w800dp-h1280dp-hdpi")
internal class AnalysisTabletPortraitScreenshotTest(shot: AnalysisShot, dark: Boolean, scale: Float?) :
  AnalysisScreenshotBase(shot, dark, scale, "tablet-verticale") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1} testo={2}")
    fun data(): List<Array<Any?>> = every(AnalysisShot.RUNNING, AnalysisShot.PAUSED)
  }
}
