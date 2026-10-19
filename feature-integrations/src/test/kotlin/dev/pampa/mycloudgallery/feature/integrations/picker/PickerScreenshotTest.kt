package dev.pampa.mycloudgallery.feature.integrations.picker

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.feature.integrations.provider.NasUnreachableReason
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot di P10 per il selettore delle altre app (griglia a scelta multipla e a un tocco, avvisi, bloccata, non pronta, in consegna):
 * chiaro e scuro, telefono (412 dp) e tablet (1280 dp), piu' il testo grande (1,3x). Sulla Libreria sintetica mista (telefono e solo NAS).
 *
 * `gradlew :feature-integrations:recordRoborazziDebug --tests "*PickerScreenshot*"` scrive i PNG in `docs/screenshots/5-P10/`. Nei normali
 * `testDebugUnitTest` si verifica solo che le schermate compongano.
 */
abstract class PickerScreenshotBase(private val dark: Boolean, private val device: String) : PickerComposeBase() {

  private val look: String get() = if (dark) "scuro" else "chiaro"
  private val library = PickerSamples.library(count = 60)
  private val env = PickerSamples.env(library)
  private val multi = PickerSamples.request(multiple = true)
  private val single = PickerSamples.request(multiple = false)

  private fun shoot(name: String, fontScale: Float? = null, content: @Composable () -> Unit) {
    show(dark = dark, fontScale = fontScale, content = content)
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device$suffix.png")
  }

  @Composable
  private fun Screen(ui: PickerUi, request: PickerRequest = multi) = PickerScreen(ui = ui, request = request, env = env, actions = PickerActions())

  @Test
  fun grigliaSceltaMultiplaConTreScelti() = shoot("griglia-tre-scelti") { Screen(PickerSamples.grid(library, multi, selected = 3)) }

  @Test
  fun grigliaSceltaMultiplaConTreSceltiTestoGrande() = shoot("griglia-tre-scelti", fontScale = LARGE_TEXT) {
    Screen(PickerSamples.grid(library, multi, selected = 3))
  }

  @Test
  fun grigliaSceltaMultiplaSenzaScelti() = shoot("griglia-nessuno-scelto") { Screen(PickerSamples.grid(library, multi)) }

  @Test
  fun grigliaUnTocco() = shoot("griglia-un-tocco") { Screen(PickerSamples.grid(library, single), single) }

  @Test
  fun avvisoNasNonRisponde() = shoot("avviso-nas-non-risponde") {
    Screen(PickerSamples.grid(library, multi, selected = 2, notice = PickerNotice.NasUnreachable(NasUnreachableReason.OFFLINE)))
  }

  @Test
  fun avvisoNasNonRispondeTestoGrande() = shoot("avviso-nas-non-risponde", fontScale = LARGE_TEXT) {
    Screen(PickerSamples.grid(library, multi, selected = 2, notice = PickerNotice.NasUnreachable(NasUnreachableReason.OFFLINE)))
  }

  @Test
  fun avvisoDatiMobili() = shoot("avviso-dati-mobili") {
    Screen(PickerSamples.grid(library, multi, selected = 2, notice = PickerNotice.NasUnreachable(NasUnreachableReason.METERED)))
  }

  @Test
  fun avvisoTroppiSoloNas() = shoot("avviso-troppi-solo-nas") {
    Screen(PickerSamples.grid(library, multi, selected = 3, notice = PickerNotice.TooManyNasOnly(120, 3L * 1024 * 1024 * 1024)))
  }

  @Test
  fun bloccata() = shoot("bloccata") { Screen(PickerUi(PickerScreenState.LOCKED)) }

  @Test
  fun bloccataTestoGrande() = shoot("bloccata", fontScale = LARGE_TEXT) { Screen(PickerUi(PickerScreenState.LOCKED)) }

  @Test
  fun bloccataSbloccoNonRiuscito() = shoot("bloccata-sblocco-non-riuscito") { Screen(PickerUi(PickerScreenState.LOCKED, unlockFailed = true)) }

  @Test
  fun bloccataMentreSiSblocca() = shoot("bloccata-sblocco-in-corso") { Screen(PickerUi(PickerScreenState.LOCKED, unlocking = true)) }

  @Test
  fun nonPronta() = shoot("non-pronta") { Screen(PickerUi(PickerScreenState.NOT_READY)) }

  @Test
  fun vuota() = shoot("vuota") { Screen(PickerUi(PickerScreenState.GRID)) }

  @Test
  fun inConsegna() = shoot("in-consegna") { Screen(PickerUi(PickerScreenState.DELIVERING)) }

  companion object {
    const val DIR = "../docs/screenshots/5-P10"
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_QUALIFIERS)
class PickerPhoneScreenshotTest(dark: Boolean) : PickerScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class PickerTabletScreenshotTest(dark: Boolean) : PickerScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
