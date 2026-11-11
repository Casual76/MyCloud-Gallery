package dev.pampa.mycloudgallery.feature.viewer

import androidx.compose.ui.test.center
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.click
import androidx.compose.ui.test.up
import androidx.compose.ui.geometry.Offset
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.core.imaging.contract.ImageFailure
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageState
import dev.pampa.mycloudgallery.core.metadata.contract.ByteRange
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeMotionVideoLocator
import dev.pampa.mycloudgallery.feature.viewer.motion.MotionAnimationSetting
import dev.pampa.mycloudgallery.feature.viewer.video.MotionVideo
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackProblem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gli screenshot del viewer (docs/screenshots/3-P8): ogni stato, chiaro e scuro, telefono e tablet. Si scrivono con
 * `gradlew :feature-viewer:recordRoborazziDebug`; nei normali `testDebugUnitTest` verificano solo che gli stati
 * compongano e non scrivono niente.
 *
 * I frame dell'espansione e dello swipe sono catturati a tempo fermo (`mainClock.autoAdvance = false`): sono i
 * fotogrammi che la revisione grafica guarda, perche' "e' giusto per costruzione" qui non basta mai.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = SHOT_PHONE)
class ViewerScreenshotTest(private val dark: Boolean) : ViewerTestBase() {

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun data(): List<Array<Any>> = listOf(arrayOf(true), arrayOf(false))
  }

  private val videoIndex get() = fixture.indicesOf(MediaKind.VIDEO).first()

  private fun shot(name: String, device: String = "telefono") {
    composeRule.onRoot().captureRoboImage("../docs/screenshots/3-P8/viewer-$name-${if (dark) "scuro" else "chiaro"}-$device.png")
  }

  private fun script(vararg steps: (MediaRef) -> Step) = ScriptedImages { ref -> steps.map { it(ref) } }
  private val preview: (MediaRef) -> Step = { ScriptedImages.preview(it) }
  private val sharp: (MediaRef) -> Step = { ScriptedImages.sharp(it) }
  private fun emit(state: ViewerImageState): (MediaRef) -> Step = { Step.Emit(state) }

  private fun pager() = composeRule.onNodeWithTag(ViewerTags.Pager)

  // --- Il movimento -----------------------------------------------------------------------------------------------

  @Test
  fun espansione() {
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent { ViewerTestHost(dark = dark) { ViewerOverlay(request = fixture.request(), onClosed = {}, services = services()) } }
    composeRule.mainClock.advanceTimeBy(50)
    shot("espansione-1")
    composeRule.mainClock.advanceTimeBy(45)
    shot("espansione-2")
    composeRule.mainClock.advanceTimeBy(120)
    shot("espansione-3")
  }

  @Test
  fun trascinamentoVersoIlBasso() {
    show(dark = dark)
    pager().performTouchInput {
      down(center)
      moveBy(Offset(0f, 330f), delayMillis = 60)
    }
    composeRule.waitForIdle()
    shot("trascinamento-1")
    pager().performTouchInput { moveBy(Offset(30f, 380f), delayMillis = 60) }
    composeRule.waitForIdle()
    shot("trascinamento-2")
    pager().performTouchInput { up() }
  }

  @Test
  fun chiusura() {
    show(dark = dark)
    composeRule.mainClock.autoAdvance = false
    composeRule.onNodeWithContentDescription("Chiudi").performClick()
    composeRule.mainClock.advanceTimeBy(60)
    shot("chiusura-1")
    composeRule.mainClock.advanceTimeBy(70)
    shot("chiusura-2")
  }

  // --- Il viewer a riposo ------------------------------------------------------------------------------------------

  @Test
  fun aperto() {
    show(dark = dark)
    shot("aperto")
  }

  @Test
  fun immersivo() {
    show(dark = dark)
    pager().performTouchInput { click(center) }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(600)
    shot("immersivo")
  }

  @Test
  fun ingrandito() {
    show(dark = dark)
    pager().performTouchInput { doubleClick(center) }
    composeRule.waitForIdle()
    shot("ingrandito")
  }

  @Test
  fun menu() {
    show(dark = dark)
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.waitUntil(10_000L) { composeRule.onAllNodesWithText("Copia").fetchSemanticsNodes().isNotEmpty() }
    composeRule.mainClock.advanceTimeBy(600)
    shot("menu")
  }

  @Test
  fun menuPovero() {
    show(services = services(actions = null, intents = null), dark = dark)
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.waitUntil(10_000L) { composeRule.onAllNodesWithText("Informazioni").fetchSemanticsNodes().isNotEmpty() }
    composeRule.mainClock.advanceTimeBy(600)
    shot("menu-povero")
  }

  // --- Le informazioni ---------------------------------------------------------------------------------------------

  @Test
  fun informazioniFoglio() {
    show(dark = dark)
    pager().performTouchInput { swipeUp() }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(800)
    shot("info-foglio")
  }

  @Test
  fun informazioniFoglioATrascinamento() {
    show(dark = dark)
    pager().performTouchInput {
      down(center)
      moveBy(Offset(0f, -300f), delayMillis = 60)
    }
    composeRule.waitForIdle()
    shot("info-trascinamento")
    pager().performTouchInput { up() }
  }

  @Config(qualifiers = TABLET)
  @Test
  fun informazioniPannelloTablet() {
    show(dark = dark)
    composeRule.onNodeWithContentDescription("Informazioni").performClick()
    composeRule.waitUntil(10_000L) { composeRule.onAllNodesWithContentDescription("Apri in Mappe").fetchSemanticsNodes().isNotEmpty() }
    composeRule.mainClock.advanceTimeBy(800)
    shot("info-pannello", device = "tablet")
  }

  @Config(qualifiers = TABLET)
  @Test
  fun apertoTablet() {
    show(dark = dark)
    shot("aperto", device = "tablet")
  }

  @Test
  fun informazioniTestoGrande() {
    composeRule.activity.resources.configuration.fontScale = 1.3f
    show(dark = dark)
    pager().performTouchInput { swipeUp() }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(800)
    shot("info-foglio-testo-grande")
  }

  // --- I fogli -------------------------------------------------------------------------------------------------------

  @Test
  fun foglioElimina() {
    show(dark = dark)
    composeRule.onNodeWithContentDescription("Elimina dal telefono").performClick()
    composeRule.waitUntil(10_000L) { composeRule.onAllNodesWithText("Eliminare la foto dal telefono?").fetchSemanticsNodes().isNotEmpty() }
    composeRule.mainClock.advanceTimeBy(800)
    shot("foglio-elimina")
  }

  @Test
  fun foglioMappe() {
    show(dark = dark)
    pager().performTouchInput { swipeUp() }
    composeRule.waitUntil(10_000L) { composeRule.onAllNodesWithContentDescription("Apri in Mappe").fetchSemanticsNodes().isNotEmpty() }
    composeRule.onNodeWithContentDescription("Apri in Mappe").performClick()
    composeRule.waitUntil(10_000L) { composeRule.onAllNodesWithText("Aprire in Mappe?").fetchSemanticsNodes().isNotEmpty() }
    composeRule.mainClock.advanceTimeBy(800)
    shot("foglio-mappe")
  }

  // --- Video e motion photo -----------------------------------------------------------------------------------------

  @Test
  fun video() {
    show(request = fixture.request(startIndex = videoIndex), dark = dark)
    player.emit { it.copy(positionMs = 47_000L, durationMs = 134_000L, playing = true, firstFrame = false, hasAudio = true, videoWidth = 1600, videoHeight = 900) }
    composeRule.waitForIdle()
    shot("video")
  }

  @Test
  fun videoInPausa() {
    show(request = fixture.request(startIndex = videoIndex), dark = dark)
    player.emit { it.copy(positionMs = 112_000L, durationMs = 134_000L, playing = false, hasAudio = true, muted = true) }
    composeRule.waitForIdle()
    shot("video-pausa")
  }

  @Test
  fun videoNonSupportato() {
    show(request = fixture.request(startIndex = videoIndex), dark = dark)
    player.failWith(PlaybackProblem.UNSUPPORTED)
    composeRule.waitForIdle()
    shot("video-non-supportato")
  }

  // --- Quando l'originale non c'e' -------------------------------------------------------------------------------------

  @Test
  fun caricamentoLento() {
    show(services = services(imageSource = script(preview, emit(ViewerImageState.Loading(progress = 0.42f)), { Step.Wait(60_000) })), dark = dark)
    shot("nota-caricamento")
  }

  @Test
  fun nasIrraggiungibile() {
    show(services = services(imageSource = script(preview, emit(ViewerImageState.Unavailable(UnavailableReason.NAS_UNREACHABLE)))), dark = dark)
    shot("nota-nas")
  }

  @Test
  fun datiMobili() {
    show(
      services = services(
        imageSource = script(preview, emit(ViewerImageState.Unavailable(UnavailableReason.NEEDS_MOBILE_DATA_CONFIRMATION, 12_400_000L))),
        confirmMobileData = {},
      ),
      dark = dark,
    )
    shot("nota-dati-mobili")
  }

  @Test
  fun fileIllegibile() {
    show(services = services(imageSource = script(preview, emit(ViewerImageState.Failed(ImageFailure.DECODE_FAILED)))), dark = dark)
    shot("nota-errore")
  }

  @Test
  fun avvisoElementoSparito() {
    show(dark = dark)
    fixture.items.publish(fixture.without(setOf(fixture.idAt(4))))
    composeRule.waitForIdle()
    shot("avviso-sparito")
  }

  @Config(qualifiers = TABLET)
  @Test
  fun notaTablet() {
    show(services = services(imageSource = script(preview, emit(ViewerImageState.Unavailable(UnavailableReason.NAS_UNREACHABLE)))), dark = dark)
    shot("nota-nas", device = "tablet")
  }
}

/**
 * La foto in movimento (Fase 6, P14, docs/screenshots/6-P14): il badge "Movimento" nella barra in alto, pronto e mentre il video intero gira, chiaro
 * e scuro, telefono e tablet. Serve una Libreria piu' grande, perche' in quella di 50 elementi non ce n'e'. Si scrivono con
 * `gradlew :feature-viewer:recordRoborazziDebug`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = SHOT_PHONE)
class ViewerMotionScreenshotTest(private val dark: Boolean) : ViewerTestBase() {

  override val fixture: ViewerFixture = ViewerFixture(count = 400)

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun data(): List<Array<Any>> = listOf(arrayOf(true), arrayOf(false))
  }

  private fun shot(name: String, device: String = "telefono") {
    composeRule.onRoot().captureRoboImage("../docs/screenshots/6-P14/viewer-$name-${if (dark) "scuro" else "chiaro"}-$device.png")
  }

  private fun showMotion(): Int {
    val motion = fixture.indicesOf(MediaKind.MOTION_PHOTO).first()
    val ref = fixture.items.ref(fixture.idAt(motion))
    val found = MotionVideo.Found(ByteRange(1_000L, 5_000L), stillUs = 2_000_000L, durationUs = 4_000_000L)
    // L'animazione d'apertura e' spenta: lo screenshot fissa la pagina ferma con il suo badge.
    show(
      request = fixture.request(startIndex = motion),
      services = services(motion = FakeMotionVideoLocator(mapOf(ref to found)), motionAnimation = MotionAnimationSetting { false }),
      dark = dark,
    )
    composeRule.mainClock.advanceTimeBy(500)
    composeRule.waitForIdle()
    return motion
  }

  @Test
  fun badgeMovimentoPronto() {
    showMotion()
    composeRule.onNodeWithContentDescription("Riproduci il movimento").assertExists()
    shot("movimento-badge")
  }

  @Test
  fun badgeMovimentoInRiproduzione() {
    showMotion()
    composeRule.onNodeWithContentDescription("Riproduci il movimento").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Ferma il movimento").assertExists()
    shot("movimento-in-riproduzione")
  }
}

/** Lo stesso badge sul tablet: la barra e' piu' larga, il pannello informazioni e' laterale. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = TABLET)
class ViewerMotionTabletScreenshotTest(private val dark: Boolean) : ViewerTestBase() {

  override val fixture: ViewerFixture = ViewerFixture(count = 400)

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun data(): List<Array<Any>> = listOf(arrayOf(true), arrayOf(false))
  }

  @Test
  fun badgeMovimentoSulTablet() {
    val motion = fixture.indicesOf(MediaKind.MOTION_PHOTO).first()
    val ref = fixture.items.ref(fixture.idAt(motion))
    val found = MotionVideo.Found(ByteRange(1_000L, 5_000L), stillUs = 2_000_000L, durationUs = 4_000_000L)
    show(
      request = fixture.request(startIndex = motion),
      services = services(motion = FakeMotionVideoLocator(mapOf(ref to found)), motionAnimation = MotionAnimationSetting { false }),
      dark = dark,
    )
    composeRule.mainClock.advanceTimeBy(500)
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Riproduci il movimento").assertExists()
    composeRule.onRoot().captureRoboImage("../docs/screenshots/6-P14/viewer-movimento-badge-${if (dark) "scuro" else "chiaro"}-tablet.png")
  }
}
