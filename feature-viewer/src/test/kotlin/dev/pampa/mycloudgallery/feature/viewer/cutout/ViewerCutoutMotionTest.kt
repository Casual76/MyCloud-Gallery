package dev.pampa.mycloudgallery.feature.viewer.cutout

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.metadata.contract.ByteRange
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.viewer.ViewerFixture
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.feature.viewer.motion.MotionAnimationSetting
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeCutoutSaver
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeMotionVideoLocator
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeViewerSubjectCutter
import dev.pampa.mycloudgallery.feature.viewer.video.MotionVideo
import org.junit.Test

/**
 * Il ritaglio su una foto in movimento (Fase 6, P13; D45, E-V12): una pressione prolungata sul soggetto di una foto in movimento lo stacca come su
 * ogni altra foto e **il video non parte**; il video parte solo dal badge "Movimento". Serve una Libreria piu' grande: in quella da 50 elementi non
 * c'e' una foto in movimento (stessa scelta di `ViewerLiveTextMotionTest`).
 */
class ViewerCutoutMotionTest : CutoutViewerBase() {

  override val fixture: ViewerFixture = ViewerFixture(count = 400)

  private val found = MotionVideo.Found(ByteRange(1_000L, 5_000L), stillUs = 2_000_000L, durationUs = 4_000_000L)

  override val startIndex: Int get() = fixture.indicesOf(MediaKind.MOTION_PHOTO).first()

  private fun motionServices(cutter: FakeViewerSubjectCutter): ViewerServices {
    val ref = fixture.items.ref(shown)
    return services(
      imageSource = CutoutTestImage.source(),
      motion = FakeMotionVideoLocator(mapOf(ref to found)),
      motionAnimation = MotionAnimationSetting { false },
      subjectCutter = cutter,
      cutoutSaver = FakeCutoutSaver(),
    )
  }

  @Test
  fun e_V12_unaPressioneProlungataSulSoggettoDiUnaFotoInMovimentoLoStacca_eIlVideoNonParte() {
    val cutter = CutoutTestImage.cutter()
    showCutout(motionServices(cutter))
    composeRule.mainClock.advanceTimeBy(1_000)
    pressRobot()
    for (label in listOf(COPY, SHARE, SAVE, OUTLINE)) assertThat(hasNode(label)).isTrue()
    assertThat(cutter.calls).hasSize(1)
    assertThat(cutter.ids).containsExactly(shown)
    assertThat(player.calls).isEmpty()
    assertThat(playersCreated).isEqualTo(0)
  }

  @Test
  fun e_V12_fuoriDalSoggettoDiUnaFotoInMovimentoNonSuccedeNiente_eIlVideoNonParte() {
    val cutter = CutoutTestImage.cutter().apply { result = { _, _ -> ViewerCutout.NoSubject } }
    showCutout(motionServices(cutter))
    composeRule.mainClock.advanceTimeBy(1_000)
    pressGrass()
    assertThat(hasNode(SAVE)).isFalse()
    assertThat(player.calls).isEmpty()
    assertThat(playersCreated).isEqualTo(0)
  }

  @Test
  fun e_V12_ilVideoParteSoloDalBadgeMovimento() {
    showCutout(motionServices(CutoutTestImage.cutter()))
    composeRule.mainClock.advanceTimeBy(1_000)
    composeRule.onNodeWithContentDescription("Riproduci il movimento").performClick()
    composeRule.waitForIdle()
    assertThat(player.calls).isNotEmpty()
  }
}
