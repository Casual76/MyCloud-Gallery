package dev.pampa.mycloudgallery.feature.viewer.gesture

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.viewer.RegisterComponentActivity
import dev.pampa.mycloudgallery.feature.viewer.SHOT_PHONE
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * H2: il modificatore dei gesti da solo, sopra un figlio che ingrandisce come lo zoom (`transformable`). A pannello aperto due dita chiudono
 * il pannello e il figlio non vede il pizzico; a pannello chiuso il pizzico che allarga e' del figlio. Separa la logica dell'arbitro dal resto
 * del viewer.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE)
class ViewerGesturesModifierTest {

  val composeRule = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private var pinches = 0
  private var dismissStarts = 0
  private var childZoom = 1f

  private fun show(infoOpen: Boolean) {
    val context = GestureContext(enabled = true, zoomed = false, infoOpen = infoOpen, sidePanel = false, returning = false)
    composeRule.setContent {
      val callbacks = remember {
        ViewerGestureCallbacks(
          context = { context },
          onDismissStart = { dismissStarts++ },
          onDismissMove = { _, _, _ -> },
          onDismissEnd = { _, _ -> },
          onDismissCancel = {},
          onInfoDrag = { _, _ -> },
          onInfoEnd = { _, _ -> },
          onInfoCancel = {},
          onInfoPinch = { pinches++ },
        )
      }
      Box(Modifier.fillMaxSize().testTag(AREA).viewerGestures(callbacks)) {
        Box(Modifier.fillMaxSize().transformable(rememberTransformableState { zoom, _, _ -> childZoom *= zoom }))
      }
    }
    composeRule.waitForIdle()
  }

  private fun spread() = composeRule.onNodeWithTag(AREA).performTouchInput {
    pinch(
      start0 = center - Offset(60f, 0f),
      end0 = center - Offset(220f, 0f),
      start1 = center + Offset(60f, 0f),
      end1 = center + Offset(220f, 0f),
    )
  }

  @Test
  fun aPannelloApertoDueDitaChiudonoIlPannello_eIlFiglioNonIngrandisce() {
    show(infoOpen = true)
    spread()
    composeRule.waitForIdle()
    assertThat(pinches).isEqualTo(1)
    assertThat(dismissStarts).isEqualTo(0)
    assertThat(childZoom).isEqualTo(1f)
  }

  @Test
  fun aPannelloChiusoIlPizzicoCheAllargaEDelFiglio() {
    show(infoOpen = false)
    spread()
    composeRule.waitForIdle()
    assertThat(pinches).isEqualTo(0)
    assertThat(childZoom).isGreaterThan(1.5f)
  }

  private companion object {
    const val AREA = "area"
  }
}
