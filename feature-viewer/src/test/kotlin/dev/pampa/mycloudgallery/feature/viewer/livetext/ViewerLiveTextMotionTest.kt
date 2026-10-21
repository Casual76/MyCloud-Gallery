package dev.pampa.mycloudgallery.feature.viewer.livetext

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.metadata.contract.ByteRange
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.viewer.ViewerFixture
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.feature.viewer.motion.MotionAnimationSetting
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeMotionVideoLocator
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeViewerTextSource
import dev.pampa.mycloudgallery.feature.viewer.video.MotionVideo
import org.junit.Test

/**
 * Le foto in movimento e i video con il testo dal vivo (Fase 6, P12; D45, E-V12): una foto in movimento si comporta come le altre foto
 * (testo dal vivo o ritaglio) e **il video non parte** dalla pressione prolungata, ma solo dal badge "Movimento"; su un video la
 * pressione prolungata non fa niente. Serve una Libreria piu' grande: in quella da 50 elementi non c'e' una foto in movimento.
 */
class ViewerLiveTextMotionTest : LiveTextViewerBase() {

  override val fixture: ViewerFixture = ViewerFixture(count = 400)

  private val kind = MediaKind.MOTION_PHOTO
  private val text = LiveTextTestLines.multiline
  private val found = MotionVideo.Found(ByteRange(1_000L, 5_000L), stillUs = 2_000_000L, durationUs = 4_000_000L)

  override val startIndex: Int get() = fixture.indicesOf(kind).first()

  private fun motionServices(subject: dev.pampa.mycloudgallery.feature.viewer.gesture.ViewerSubjectPress? = null): ViewerServices {
    val ref = fixture.items.ref(shown)
    return services(
      imageSource = textServices().images,
      motion = FakeMotionVideoLocator(mapOf(ref to found)),
      motionAnimation = MotionAnimationSetting { false },
      textSource = FakeViewerTextSource(mutableMapOf(shown to text)),
      subjectPress = subject,
    )
  }

  @Test
  fun e_V12_unaPressioneProlungataSulTestoDiUnaFotoInMovimentoSelezionaIlTesto_eIlVideoNonParte() {
    showText(motionServices())
    composeRule.mainClock.advanceTimeBy(1_000)
    longPress(at(text, 0, 1))
    assertThat(hasNode(START)).isTrue()
    composeRule.onNodeWithContentDescription("Copia").performClick()
    assertThat(clipText()).isEqualTo("di")
    assertThat(player.calls).isEmpty()
    assertThat(playersCreated).isEqualTo(0)
  }

  @Test
  fun e_V12_fuoriDalTestoLaPressioneVaAlRitaglio_eIlVideoNonParte() {
    val pressed = mutableListOf<Offset>()
    showText(motionServices { _, x, y -> pressed += Offset(x, y) })
    composeRule.mainClock.advanceTimeBy(1_000)
    longPress(atImage(0.8f, 0.9f))
    assertThat(hasNode(START)).isFalse()
    assertThat(pressed).hasSize(1)
    assertThat(pressed.single().x).isWithin(0.01f).of(0.8f)
    assertThat(pressed.single().y).isWithin(0.01f).of(0.9f)
    assertThat(player.calls).isEmpty()
    assertThat(playersCreated).isEqualTo(0)
  }

  @Test
  fun e_V12_ilVideoParteSoloDalBadgeMovimento() {
    showText(motionServices())
    composeRule.mainClock.advanceTimeBy(1_000)
    composeRule.onNodeWithContentDescription("Riproduci il movimento").performClick()
    composeRule.waitForIdle()
    assertThat(player.calls).isNotEmpty()
  }

  @Test
  fun suUnVideoLaPressioneProlungataNonFaNiente_nemmenoSeCeIlTesto() {
    val video = fixture.indicesOf(MediaKind.VIDEO).first()
    val request = fixture.request(startIndex = video)
    val services = services(
      textSource = FakeViewerTextSource(mutableMapOf(fixture.idAt(video) to text)),
      subjectPress = { _, _, _ -> throw AssertionError("Su un video la pressione prolungata non va al ritaglio") },
    )
    composeRule.setContent {
      dev.pampa.mycloudgallery.feature.viewer.ViewerTestHost {
        dev.pampa.mycloudgallery.feature.viewer.ViewerOverlay(request = request, onClosed = { closed++ }, services = services)
      }
    }
    composeRule.waitForIdle()
    longPress(pageCenter())
    assertThat(hasNode(START)).isFalse()
    assertThat(hasNode("Testo nella foto")).isFalse()
  }
}
