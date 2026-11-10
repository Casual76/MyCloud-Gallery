package dev.pampa.mycloudgallery.feature.people.erase

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.core.faces.contract.AckState
import dev.pampa.mycloudgallery.core.faces.contract.DeviceAck
import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.faces.contract.StepState
import dev.pampa.mycloudgallery.feature.people.activation.setPeopleContent
import dev.pampa.mycloudgallery.feature.people.contract.EraseSheetState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot del foglio e della pagina "Disattiva e cancella" (Fase 8, P8, docs/screenshots/8-P8): ogni scena in chiaro e scuro, telefono e tablet, piu'
 * il testo a 1,3x sul telefono in chiaro. `gradlew :feature-people:recordRoborazziDebug --tests "*EraseScreenshot*"` scrive i PNG; nei normali
 * `testDebugUnitTest` verificano solo che le scene compongano. Stati finti, nessun nome di persona: solo nomi di dispositivi inventati. Le scene
 * lunghe ([EraseScene.tall]: l'anteprima con i pulsanti, l'avanzamento con i dispositivi) si fotografano su uno schermo alto, per intero (come P7).
 */
internal enum class EraseScene(val file: String, val tall: Boolean = false) {
  ANTEPRIMA("anteprima", tall = true),
  ANTEPRIMA_RECINZIONE("anteprima-recinzione"),
  AVANZAMENTO_NAS("avanzamento-attesa-nas", tall = true),
  AVANZAMENTO_CONGELATO("avanzamento-congelamento", tall = true),
  CESTINO("cestino-di-rete", tall = true),
  DISPOSITIVI_MANCANTI("dispositivi-mancanti"),
  FATTO("fatto-ovunque"),
  FATTO_QUI("fatto-su-questo-dispositivo"),
  NIENTE("niente-da-cancellare"),
  SCADUTA("riepilogo-cambiato"),
}

private const val NOON_3_NOV = 1_793_707_200_000L
private val phone = DeviceAck("Telefono di casa", "a".repeat(26), AckState.CONFIRMED, null, thisDevice = true)
private val tablet = DeviceAck("Tablet del soggiorno", "b".repeat(26), AckState.WAITING, NOON_3_NOV, thisDevice = false)
private val lost = DeviceAck("Telefono vecchio", "c".repeat(26), AckState.NOT_EXPECTED, null, thisDevice = false)
private val token = EraseSheetToken(previewDigest = 42, shownAtMonotonicNanos = 1)

private fun stateOf(scene: EraseScene): EraseSheetState = when (scene) {
  EraseScene.ANTEPRIMA -> EraseSheetState.Preview(4, 2, historyWithNames = true, devices = listOf(phone, tablet, lost), choice = PeopleAlbumsChoice.KEEP_PHOTOS, localOnly = false)
  EraseScene.ANTEPRIMA_RECINZIONE -> EraseSheetState.Preview(1, 0, historyWithNames = true, devices = emptyList(), choice = null, localOnly = true)
  EraseScene.AVANZAMENTO_NAS -> EraseSheetState.Progress(StepState.DONE, StepState.WAITING, listOf(phone, tablet), EraseWait.NAS)
  EraseScene.AVANZAMENTO_CONGELATO -> EraseSheetState.Progress(StepState.DONE, StepState.WAITING, listOf(phone, tablet, lost), EraseWait.FROZEN)
  EraseScene.CESTINO -> EraseSheetState.Progress(StepState.DONE, StepState.DONE, listOf(phone, tablet.copy(state = AckState.CONFIRMED)), EraseWait.RECYCLE_BIN)
  EraseScene.DISPOSITIVI_MANCANTI -> EraseSheetState.WaitingDevices(listOf(tablet))
  EraseScene.FATTO -> EraseSheetState.Done
  EraseScene.FATTO_QUI -> EraseSheetState.DoneHere
  EraseScene.NIENTE -> EraseSheetState.NothingToErase
  EraseScene.SCADUTA -> EraseSheetState.Expired
}

@Composable
private fun SceneContent(scene: EraseScene) {
  FacesEraseSheet(
    state = stateOf(scene),
    token = token,
    onConfirm = {},
    onChoose = {},
    onCancel = {},
    onReload = {},
    onClose = {},
    onBack = {},
  )
}

internal abstract class EraseScreenshotBase(private val scene: EraseScene, private val dark: Boolean, private val scale: Float?, private val device: String) {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun capture() {
    rule.setPeopleContent(dark = dark, fontScale = scale) { SceneContent(scene) }
    rule.waitForIdle()
    val name = "${scene.file}-${if (dark) "scuro" else "chiaro"}-$device${if (scale != null) "-testo-grande" else ""}"
    rule.onRoot().captureRoboImage("../docs/screenshots/8-P8/$name.png")
  }

  companion object {
    /** Ogni scena in chiaro e scuro; il testo grande solo in chiaro (telefono). */
    fun parameters(wide: Boolean, tall: Boolean): List<Array<Any?>> = EraseScene.entries.filter { it.tall == tall }.flatMap { scene ->
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
internal class EraseScreenshotPhoneTest(scene: EraseScene, dark: Boolean, scale: Float?) : EraseScreenshotBase(scene, dark, scale, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1} testo={2}")
    fun data(): List<Array<Any?>> = parameters(wide = false, tall = false)
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w1280dp-h800dp-xhdpi")
internal class EraseScreenshotTabletTest(scene: EraseScene, dark: Boolean, scale: Float?) : EraseScreenshotBase(scene, dark, scale, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1} testo={2}")
    fun data(): List<Array<Any?>> = parameters(wide = true, tall = false)
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w360dp-h2200dp-xhdpi")
internal class EraseScreenshotTallPhoneTest(scene: EraseScene, dark: Boolean, scale: Float?) : EraseScreenshotBase(scene, dark, scale, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1} testo={2}")
    fun data(): List<Array<Any?>> = parameters(wide = false, tall = true)
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w1280dp-h2000dp-hdpi")
internal class EraseScreenshotTallTabletTest(scene: EraseScene, dark: Boolean, scale: Float?) : EraseScreenshotBase(scene, dark, scale, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1} testo={2}")
    fun data(): List<Array<Any?>> = parameters(wide = true, tall = true)
  }
}
