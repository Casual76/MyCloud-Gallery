package dev.pampa.mycloudgallery.feature.people.activation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.core.faces.contract.FacesPhase
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.feature.people.contract.ActivationProblem
import dev.pampa.mycloudgallery.feature.people.contract.ActivationRequirements
import dev.pampa.mycloudgallery.feature.people.contract.ComputingDevice
import dev.pampa.mycloudgallery.feature.people.contract.FaceModelRow
import dev.pampa.mycloudgallery.feature.people.contract.FacesActivationState
import dev.pampa.mycloudgallery.feature.people.contract.FacesNoticeState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSettingsState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleSwitchShown
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot della pagina di attivazione, dell'avviso e di Impostazioni -> Persone (Fase 8, P7, docs/screenshots/8-P7): ogni scena in chiaro e scuro,
 * telefono e tablet, piu' il testo a 1,3x sul telefono in chiaro. `gradlew :feature-people:recordRoborazziDebug --tests "*Activation*ScreenshotTest"` scrive i PNG;
 * nei normali `testDebugUnitTest` verificano solo che le scene compongano e non scrivono niente. Stati finti, nessun nome ne' volto: solo le parole della pagina.
 */
internal enum class Scene(val file: String, val tall: Boolean) {
  SPIEGAZIONE("attivazione-spiegazione", tall = true),
  SPIEGAZIONE_RECINZIONE("attivazione-spiegazione-recinzione", tall = true),
  SENZA_AREA("attivazione-senza-area-privata", tall = false),
  SENZA_INDICIZZATORI("attivazione-senza-indicizzatori", tall = false),
  ATTIVA("attivazione-attivata", tall = false),
  ATTIVAZIONE_NON_RIUSCITA("attivazione-non-disponibile", tall = false),
  AVVISO("avviso-altro-dispositivo", tall = false),
  IMPOSTAZIONI_SPENTA("impostazioni-persone-spenta", tall = false),
  IMPOSTAZIONI_ATTIVA("impostazioni-persone-attiva", tall = true),
  IMPOSTAZIONI_LOCALE("impostazioni-persone-recinzione", tall = true),
  IMPOSTAZIONI_IN_ATTESA("impostazioni-persone-avviso", tall = false),
  IMPOSTAZIONI_ESCLUSO("impostazioni-persone-escluso", tall = false),
}

private val requirements = ActivationRequirements(privateArea = true, indexers = 1, thisDeviceIndexer = false, canBecomeIndexer = true, localOnly = false)

private val models = listOf(FaceModelRow("YuNet", "2023mar", "MIT"), FaceModelRow("SFace", "2021dec", "Apache-2.0"))

private val devices = listOf(ComputingDevice("Telefono di casa", thisDevice = true, excluded = false), ComputingDevice("Tablet del soggiorno", thisDevice = false, excluded = false))

private fun settings(shown: PeopleSwitchShown, withWork: Boolean = false, hidden: Int = 0, canErase: Boolean = false) = PeopleSettingsState(
  shown = shown,
  devices = if (withWork) devices else emptyList(),
  progress = if (withWork) FacesStatus.Running(done = 4_200, total = 12_000, phase = FacesPhase.DETECT) else FacesStatus.Off,
  excludedHere = shown == PeopleSwitchShown.EXCLUDED_HERE,
  hiddenPeople = hidden,
  models = models,
  canErase = canErase,
  erase = null,
)

private val handlers = PeopleSettingsHandlers(
  onActivate = {},
  onOpenNotice = {},
  onExclude = {},
  onInclude = {},
  onOpenHidden = {},
  onOpenLicenses = {},
  onOpenDiagnostics = {},
  onErase = {},
)

@Composable
private fun SceneContent(scene: Scene) {
  when (scene) {
    Scene.SPIEGAZIONE -> activation(FacesActivationState.Explain(1, requirements))
    Scene.SPIEGAZIONE_RECINZIONE -> activation(FacesActivationState.Explain(1, requirements.copy(localOnly = true)))
    Scene.SENZA_AREA -> activation(FacesActivationState.NoPrivateArea)
    Scene.SENZA_INDICIZZATORI -> activation(FacesActivationState.NeedsIndexer(canBecomeIndexer = true))
    Scene.ATTIVA -> activation(FacesActivationState.Activated(syncPending = true))
    Scene.ATTIVAZIONE_NON_RIUSCITA -> activation(FacesActivationState.Failed(ActivationProblem.UNAVAILABLE))
    Scene.AVVISO -> FacesNoticeScreen(state = FacesNoticeState.Show(1, "Tablet del soggiorno"), onOk = {}, onExclude = {}, onRead = {}, onBack = {})
    Scene.IMPOSTAZIONI_SPENTA -> PeopleSettingsScreen(settings(PeopleSwitchShown.OFF), handlers, onBack = {})
    Scene.IMPOSTAZIONI_ATTIVA -> PeopleSettingsScreen(settings(PeopleSwitchShown.ON, withWork = true, hidden = 2, canErase = true), handlers, onBack = {})
    Scene.IMPOSTAZIONI_LOCALE -> PeopleSettingsScreen(settings(PeopleSwitchShown.LOCAL_ONLY, withWork = true, canErase = true), handlers, onBack = {})
    Scene.IMPOSTAZIONI_IN_ATTESA -> PeopleSettingsScreen(settings(PeopleSwitchShown.AWAITING_NOTICE, canErase = true), handlers, onBack = {})
    Scene.IMPOSTAZIONI_ESCLUSO -> PeopleSettingsScreen(settings(PeopleSwitchShown.EXCLUDED_HERE, canErase = true), handlers, onBack = {})
  }
}

@Composable
private fun activation(state: FacesActivationState) {
  FacesActivationScreen(
    state = state,
    onActivate = {},
    onNotNow = {},
    onBecomeIndexer = {},
    onOpenLicenses = {},
    onRetry = {},
    onDone = {},
    onBack = {},
  )
}

internal abstract class ActivationScreenshotBase(
  private val scene: Scene,
  private val dark: Boolean,
  private val scale: Float?,
  private val device: String,
) {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun capture() {
    rule.setPeopleContent(dark = dark, fontScale = scale) { SceneContent(scene) }
    rule.waitForIdle()
    val name = "${scene.file}-${if (dark) "scuro" else "chiaro"}-$device${if (scale != null) "-testo-grande" else ""}"
    rule.onRoot().captureRoboImage("../docs/screenshots/8-P7/$name.png")
  }

  companion object {
    /** Ogni scena in chiaro e scuro; il testo grande solo in chiaro (telefono). */
    fun parameters(wide: Boolean, tall: Boolean): List<Array<Any?>> = Scene.entries.filter { it.tall == tall }.flatMap { scene ->
      buildList {
        add(arrayOf<Any?>(scene, false, null))
        add(arrayOf<Any?>(scene, true, null))
        if (!wide) add(arrayOf<Any?>(scene, false, 1.3f))
      }
    }
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w360dp-h780dp-xhdpi")
internal class ActivationPhoneScreenshotTest(scene: Scene, dark: Boolean, scale: Float?) : ActivationScreenshotBase(scene, dark, scale, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1} testo={2}")
    fun data(): List<Array<Any?>> = parameters(wide = false, tall = false)
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w1280dp-h800dp-xhdpi")
internal class ActivationTabletScreenshotTest(scene: Scene, dark: Boolean, scale: Float?) : ActivationScreenshotBase(scene, dark, scale, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1} testo={2}")
    fun data(): List<Array<Any?>> = parameters(wide = true, tall = false)
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w360dp-h2200dp-xhdpi")
internal class ActivationPagesPhoneScreenshotTest(scene: Scene, dark: Boolean, scale: Float?) : ActivationScreenshotBase(scene, dark, scale, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1} testo={2}")
    fun data(): List<Array<Any?>> = parameters(wide = false, tall = true)
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w1280dp-h2000dp-hdpi")
internal class ActivationPagesTabletScreenshotTest(scene: Scene, dark: Boolean, scale: Float?) : ActivationScreenshotBase(scene, dark, scale, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1} testo={2}")
    fun data(): List<Array<Any?>> = parameters(wide = true, tall = true)
  }
}
