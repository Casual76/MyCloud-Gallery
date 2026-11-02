package dev.pampa.mycloudgallery.feature.viewer

import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performMouseInput
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.adaptive.LocalMediaDragPort
import dev.pampa.mycloudgallery.core.designsystem.adaptive.LocalMediaDragStarter
import dev.pampa.mycloudgallery.core.designsystem.adaptive.MediaDrag
import dev.pampa.mycloudgallery.core.designsystem.adaptive.MediaDragPort
import dev.pampa.mycloudgallery.core.designsystem.adaptive.MediaDragShadow
import dev.pampa.mycloudgallery.core.designsystem.adaptive.MediaDragStarter
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import org.junit.Test

/**
 * Fase 10 (P6, D20, E-T18): il trascinamento della foto del viewer verso un'altra app, **senza il sistema che trascina** (porta e avvio finti che registrano). Col mouse la foto corrente si
 * porta via trascinando; con lo zoom il trascinamento e' la panoramica e non parte; **dai Nascosti** (`ViewerRequest.secure`) la porta non si chiama mai.
 */
@OptIn(ExperimentalTestApi::class)
class ViewerDragTest : ViewerTestBase() {

  private class RecordingPort : MediaDragPort {
    val requests = mutableListOf<Pair<String, List<String>>>()

    override suspend fun prepare(origin: String, ids: List<String>): MediaDrag {
      requests += origin to ids
      val clip = ClipData(ClipDescription("", arrayOf("image/jpeg")), ClipData.Item(Uri.parse("content://x.documents/document/aaaaaaaaaaaaaaaa")))
      return MediaDrag(clip, 257, ids.size) {}
    }
  }

  private class RecordingStarter : MediaDragStarter {
    var started = 0

    override fun start(drag: MediaDrag, shadow: MediaDragShadow): Boolean {
      started++
      return true
    }
  }

  private val port = RecordingPort()
  private val starter = RecordingStarter()

  private fun showWithDrag(request: ViewerRequest) {
    composeRule.setContent {
      CompositionLocalProvider(LocalMediaDragPort provides port, LocalMediaDragStarter provides starter) {
        ViewerTestHost(dark = true) { ViewerOverlay(request = request, onClosed = { closed++ }, services = services()) }
      }
    }
    composeRule.waitForIdle()
  }

  private fun dragWithMouse() {
    composeRule.onNodeWithContentDescription("5 di 50").performMouseInput {
      moveTo(center)
      press(MouseButton.Primary)
      moveBy(Offset(60f, 0f))
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("5 di 50").performMouseInput { release(MouseButton.Primary) }
    composeRule.waitForIdle()
  }

  @Test
  fun ilMouseTrascinaLaFotoCorrente() {
    showWithDrag(fixture.request())
    val current = fixture.idAt(4)

    dragWithMouse()

    assertThat(port.requests).containsExactly("viewer" to listOf(current.value))
    assertThat(starter.started).isEqualTo(1)
  }

  @Test
  fun daiNascostiLaFotoNonSiTrascinaMai() {
    showWithDrag(fixture.request(secure = true))

    dragWithMouse()

    assertThat(port.requests).isEmpty()
    assertThat(starter.started).isEqualTo(0)
  }

  @Test
  fun conLoZoomIlTrascinamentoEPanoramicaENonParte() {
    showWithDrag(fixture.request())
    // Un passo di rotella ingrandisce (P4): da li' il mouse che trascina sposta la foto, non la porta via.
    composeRule.onNodeWithContentDescription("5 di 50").performMouseInput {
      moveTo(center)
      scroll(-3f)
    }
    composeRule.waitForIdle()

    dragWithMouse()

    assertThat(port.requests).isEmpty()
  }
}
