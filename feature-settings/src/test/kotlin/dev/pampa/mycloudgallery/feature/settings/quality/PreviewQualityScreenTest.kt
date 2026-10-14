package dev.pampa.mycloudgallery.feature.settings.quality

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.feature.settings.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Anteprime del NAS" (Fase 4A, P11; D16, E-L6, E-N14): i tre livelli con la stima, lo spazio di adesso, la convergenza,
 * le copertine degli album; senza NAS solo la nota "non attive".
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w412dp-h1800dp-xhdpi")
class PreviewQualityScreenTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val chosen = mutableListOf<PreviewLevel>()
  private val covers = mutableListOf<Boolean>()

  private fun state(converging: Boolean = false, showCovers: Boolean = false) = PreviewQualityState(
    level = PreviewLevel.STANDARD,
    estimates = mapOf(
      PreviewLevel.SAVER to PreviewEstimate(100L * MB, 13_000),
      PreviewLevel.STANDARD to PreviewEstimate(380L * MB, 13_000),
      PreviewLevel.HIGH to PreviewEstimate(5_600L * MB, 13_000),
    ),
    currentBytes = 120L * MB,
    converging = converging,
    showAlbumCovers = showCovers,
  )

  private fun show(state: PreviewQualityState?) {
    rule.setContent {
      MyCloudGalleryTheme {
        PreviewQualityScreen(state = state, onBack = {}, onChoose = { chosen += it }, onShowCovers = { covers += it })
      }
    }
    rule.waitForIdle()
  }

  private fun string(id: Int): String = rule.activity.getString(id)

  @Test
  fun senzaNasDiceSoloCheLeAnteprimeNonSonoAttive() {
    show(null)
    rule.onNodeWithText(string(R.string.quality_not_active)).assertExists()
    rule.onNodeWithText(string(R.string.quality_saver)).assertDoesNotExist()
  }

  @Test
  fun treLivelliConLaStimaEToccareUnAltroLivelloLoSceglie() {
    show(state())
    rule.onNodeWithText(string(R.string.quality_saver)).assertExists()
    rule.onNodeWithText(string(R.string.quality_standard)).assertExists()
    rule.onNodeWithText(string(R.string.quality_high)).assertExists()
    rule.onNodeWithText(string(R.string.quality_high)).performClick()
    rule.onNodeWithText(string(R.string.quality_standard)).performClick()
    rule.waitForIdle()
    assertThat(chosen).containsExactly(PreviewLevel.HIGH)
  }

  @Test
  fun loSpazioDiAdessoEDettoEQuandoConvergeLoDice() {
    show(state(converging = true))
    rule.onNodeWithText(string(R.string.quality_converging).substringBefore("%1\$s").trim(), substring = true).assertExists()
  }

  @Test
  fun leCopertineDegliAlbumSiAccendonoConUnTocco() {
    show(state(showCovers = false))
    rule.onNode(hasScrollAction()).performScrollToNode(hasText(string(R.string.quality_covers)))
    rule.onNodeWithText(string(R.string.quality_covers)).performClick()
    rule.waitForIdle()
    assertThat(covers).containsExactly(true)
  }

  private companion object {
    const val MB = 1024L * 1024L
  }
}
