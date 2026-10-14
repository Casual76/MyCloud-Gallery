package dev.pampa.mycloudgallery.feature.viewer

import androidx.compose.ui.test.center
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.core.imaging.contract.UnavailableReason
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageState
import dev.pampa.mycloudgallery.core.imaging.viewer.testing.ScriptedNasReader
import dev.pampa.mycloudgallery.core.imaging.viewer.MobileDataConsent
import dev.pampa.mycloudgallery.core.imaging.viewer.NasMediaLocation
import dev.pampa.mycloudgallery.core.imaging.viewer.OriginalFetch
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.viewer.download.DownloadState
import dev.pampa.mycloudgallery.feature.viewer.video.NasVideoSource
import dev.pampa.mycloudgallery.feature.viewer.video.PlaybackProblem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gli screenshot del viewer con i media del NAS (docs/screenshots/4-P8): la voce "Scarica sul telefono", lo scaricamento in
 * corso e il suo esito, i dati mobili per un video, "Preparo il video", il NAS che non risponde con "Riprova", l'originale
 * chiesto con lo zoom. Chiaro e scuro, telefono e tablet, testo grande.
 *
 * Si scrivono con `gradlew :feature-viewer:recordRoborazziDebug`; nei normali `testDebugUnitTest` verificano solo che gli
 * stati compongano e non scrivono niente (come `ViewerScreenshotTest`).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class ViewerNasScreenshotTest(private val dark: Boolean) : ViewerTestBase() {

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun data(): List<Array<Any>> = listOf(arrayOf(true), arrayOf(false))
  }

  override val fixture: ViewerFixture = ViewerFixture(count = 400)

  private val photoIndex get() = (0 until fixture.library.count).first { fixture.library.snapshot.kindAt(it) == MediaKind.PHOTO }
  private val videoIndex get() = fixture.indicesOf(MediaKind.VIDEO).first()

  private fun shot(name: String, device: String = "telefono") {
    composeRule.onRoot().captureRoboImage("../docs/screenshots/4-P8/viewer-nas-$name-${if (dark) "scuro" else "chiaro"}-$device.png")
  }

  private fun nasRequest(index: Int): ViewerRequest {
    fixture.placeCell(index)
    return ViewerRequest(items = NasItems(fixture.library.view(), fixture), start = fixture.idAt(index), anchor = fixture.anchor, secure = false)
  }

  private fun refAt(index: Int) = NasItems(fixture.library.view(), fixture).ref(fixture.idAt(index)) as MediaRef.Nas

  private fun script(vararg steps: (MediaRef) -> Step) = ScriptedImages { ref -> steps.map { it(ref) } }
  private val preview: (MediaRef) -> Step = { ScriptedImages.preview(it) }
  private val sharp: (MediaRef) -> Step = { ScriptedImages.sharp(it) }
  private fun emit(state: ViewerImageState): (MediaRef) -> Step = { Step.Emit(state) }

  private fun photo(download: FakePhoneDownload? = null, originals: FakeOriginals? = null, source: ScriptedImages = script(preview, sharp)) =
    show(request = nasRequest(photoIndex), services = services(imageSource = source, download = download, originals = originals), dark = dark)

  private val metered = booleanArrayOf(false)
  private val consent = MobileDataConsent()

  private fun nasVideo(): NasVideoSource = NasVideoSource(
    reader = ScriptedNasReader(InMemoryNasMedia().reader),
    locator = { NasMediaLocation("test", SharePath.of("Video", "gita.mp4"), 1_200_000_000L) },
    link = { metered[0] },
    consent = consent,
  )

  private fun video() = show(request = nasRequest(videoIndex), services = services(imageSource = script(preview, sharp), nasVideo = nasVideo()), dark = dark)

  // --- Scarica sul telefono ------------------------------------------------------------------------------------------

  @Test
  fun menuConScaricaSulTelefono() {
    photo(download = FakePhoneDownload())
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.waitUntil(10_000L) { composeRule.onAllNodesWithText("Scarica sul telefono").fetchSemanticsNodes().isNotEmpty() }
    composeRule.mainClock.advanceTimeBy(800)
    shot("menu")
  }

  @Test
  fun scaricamentoInCorso() {
    val download = FakePhoneDownload()
    photo(download = download)
    download.set(refAt(photoIndex).locationId, DownloadState.Running(0.42f))
    composeRule.waitForIdle()
    shot("scarico-in-corso")
  }

  @Config(qualifiers = TABLET)
  @Test
  fun scaricamentoInCorsoTablet() {
    val download = FakePhoneDownload()
    photo(download = download)
    download.set(refAt(photoIndex).locationId, DownloadState.Running(0.42f))
    composeRule.waitForIdle()
    shot("scarico-in-corso", device = "tablet")
  }

  @Test
  fun scaricamentoInCoda() {
    val download = FakePhoneDownload()
    photo(download = download)
    download.set(refAt(photoIndex).locationId, DownloadState.Queued)
    composeRule.waitForIdle()
    shot("scarico-in-coda")
  }

  @Test
  fun scaricamentoRiuscito() {
    val download = FakePhoneDownload()
    photo(download = download)
    download.set(refAt(photoIndex).locationId, DownloadState.Done("vacanza.jpg"))
    composeRule.waitForIdle()
    shot("scarico-fatto")
  }

  @Test
  fun scaricamentoCoiDatiMobili() {
    val download = FakePhoneDownload()
    show(
      request = nasRequest(photoIndex),
      services = services(imageSource = script(preview, sharp), download = download, confirmMobileData = {}),
      dark = dark,
    )
    download.set(refAt(photoIndex).locationId, DownloadState.NeedsMobileData(48_000_000))
    composeRule.waitForIdle()
    shot("scarico-dati-mobili")
  }

  // --- Foto e video del NAS ---------------------------------------------------------------------------------------------

  @Test
  fun fotoDelNasNonRaggiungibile() {
    photo(source = script(preview, emit(ViewerImageState.Unavailable(UnavailableReason.NAS_UNREACHABLE, 6_000_000))))
    shot("foto-nas-non-risponde")
  }

  @Test
  fun originaleChiestoConLoZoom() {
    val originals = FakeOriginals()
    photo(originals = originals)
    composeRule.onNodeWithTag(ViewerTags.Pager).performTouchInput { doubleClick(center) }
    composeRule.waitForIdle()
    originals.events.trySend(OriginalFetch.Reading(0.55f))
    composeRule.waitForIdle()
    shot("originale-zoom")
  }

  @Test
  fun videoCheSiPrepara() {
    video()
    shot("video-preparo")
  }

  @Test
  fun videoCoiDatiMobili() {
    metered[0] = true
    show(
      request = nasRequest(videoIndex),
      services = services(imageSource = script(preview, sharp), nasVideo = nasVideo(), confirmMobileData = { consent.confirm(it) }),
      dark = dark,
    )
    shot("video-dati-mobili")
  }

  @Test
  fun videoNasNonRisponde() {
    video()
    player.failWith(PlaybackProblem.NAS_UNREACHABLE)
    composeRule.waitForIdle()
    shot("video-nas-non-risponde")
  }

  @Test
  fun videoNasNonRispondeTestoGrande() {
    composeRule.activity.resources.configuration.fontScale = 1.3f
    video()
    player.failWith(PlaybackProblem.NAS_UNREACHABLE)
    composeRule.waitForIdle()
    shot("video-nas-non-risponde-testo-grande")
  }
}
