package dev.pampa.mycloudgallery.core.designsystem.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Place
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.catalog.SamplePhoto
import dev.pampa.mycloudgallery.core.designsystem.catalog.SampleScene
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarAction
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * I componenti del viewer, provati sull'albero semantico: il chrome che si nasconde da solo (niente vetro ne' tocchi
 * quando e' spento), la barra del video che scorre con il dito e rilascia con la posizione definitiva, la nota con la
 * sua azione, le righe del pannello informazioni. E i **48 dp** di bersaglio di ogni tasto.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ViewerComponentsTest {

  @get:Rule
  val composeRule = createComposeRule()

  @Composable
  private fun Stage(content: @Composable (androidx.compose.ui.Modifier, dev.antigravity.fluidengine.ui.fluid.GlassBackdropState) -> Unit) {
    MyCloudGalleryTheme {
      val backdrop = rememberGlassBackdrop()
      Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().glassBackdropSource(backdrop)) { SamplePhoto(SampleScene.Dark) }
        content(Modifier, backdrop)
      }
    }
  }

  @Test
  fun ilChromeSpentoNonDisegnaNeBarreNeAzioni() {
    var visible by mutableStateOf(true)
    composeRule.setContent {
      Stage { _, backdrop ->
        GalleryViewerChrome(
          visible = visible,
          title = "12 marzo 2024",
          subtitle = "12:41 · Oslo",
          backdrop = backdrop,
          onClose = {},
          bottomActions = listOf(GalleryBarAction(Icons.Rounded.Favorite, "Preferito", onClick = {})),
        )
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("12 marzo 2024").assertExists()
    composeRule.onNodeWithContentDescription("Preferito").assertExists()
    composeRule.runOnUiThread { visible = false }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(1_000)
    composeRule.onNodeWithText("12 marzo 2024").assertDoesNotExist()
    composeRule.onNodeWithContentDescription("Preferito").assertDoesNotExist()
  }

  @Test
  fun ilChromeToglieSoloLaBarraInBassoQuandoSiApreIlPannello() {
    var bottom by mutableStateOf(true)
    composeRule.setContent {
      Stage { _, backdrop ->
        GalleryViewerChrome(
          visible = true,
          title = "12 marzo 2024",
          backdrop = backdrop,
          onClose = {},
          bottomVisible = bottom,
          bottomActions = listOf(GalleryBarAction(Icons.Rounded.Favorite, "Preferito", onClick = {})),
        )
      }
    }
    composeRule.waitForIdle()
    composeRule.runOnUiThread { bottom = false }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(1_000)
    composeRule.onNodeWithContentDescription("Preferito").assertDoesNotExist()
    composeRule.onNodeWithText("12 marzo 2024").assertExists()
  }

  @Test
  fun ilTastoPerChiudereHaIl48dp() {
    composeRule.setContent {
      Stage { _, backdrop -> GalleryViewerChrome(visible = true, title = "t", backdrop = backdrop, onClose = {}) }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Chiudi").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
  }

  @Test
  fun laBarraDelVideoScorreEPoiRilascia() {
    val scrubbed = mutableListOf<Long>()
    val finished = mutableListOf<Long>()
    var toggles = 0
    var mutedToggles = 0
    composeRule.setContent {
      Stage { _, backdrop ->
        GalleryVideoBar(
          positionMs = 10_000L,
          durationMs = 100_000L,
          playing = true,
          backdrop = backdrop,
          onPlayPause = { toggles++ },
          onScrub = { scrubbed += it },
          onScrubFinished = { finished += it },
          muted = false,
          onToggleMute = { mutedToggles++ },
        )
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("0:10").assertExists()
    composeRule.onNodeWithText("1:40").assertExists()
    composeRule.onNodeWithContentDescription("Pausa").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
    composeRule.onNodeWithContentDescription("Disattiva l'audio").performClick()
    assertThat(toggles).isEqualTo(1)
    assertThat(mutedToggles).isEqualTo(1)
    val slider = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
    slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
    composeRule.waitForIdle()
    // Il dito che trascina avvisa a ogni passo, e al rilascio c'e' la posizione definitiva.
    assertThat(scrubbed).containsExactly(25_000L)
    assertThat(finished).containsExactly(25_000L)
  }

  @Test
  fun senzaAudioIlTastoNonC_E_ConIlVideoFinitoDiventaRivedi() {
    composeRule.setContent {
      Stage { _, backdrop ->
        GalleryVideoBar(
          positionMs = 100_000L,
          durationMs = 100_000L,
          playing = false,
          ended = true,
          backdrop = backdrop,
          onPlayPause = {},
          onScrub = {},
          onScrubFinished = {},
          muted = null,
        )
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Rivedi dall'inizio").assertExists()
    composeRule.onNodeWithContentDescription("Disattiva l'audio").assertDoesNotExist()
    composeRule.onNodeWithContentDescription("Attiva l'audio").assertDoesNotExist()
  }

  @Test
  fun unVideoSenzaDurataNonSiScorre() {
    composeRule.setContent {
      Stage { _, backdrop ->
        GalleryVideoBar(positionMs = 0L, durationMs = 0L, playing = false, backdrop = backdrop, onPlayPause = {}, onScrub = {}, onScrubFinished = {})
      }
    }
    composeRule.waitForIdle()
    // Tempo e durata a zero, entrambi "0:00", e la barra non si muove (nessuna durata da scorrere).
    composeRule.onAllNodesWithText("0:00").assertCountEquals(2)
  }

  @Test
  fun iTempiSiScrivonoCosi() {
    assertThat(GalleryVideoTime.format(0)).isEqualTo("0:00")
    assertThat(GalleryVideoTime.format(7_999)).isEqualTo("0:07")
    assertThat(GalleryVideoTime.format(754_000)).isEqualTo("12:34")
    assertThat(GalleryVideoTime.format(3_723_000)).isEqualTo("1:02:03")
    assertThat(GalleryVideoTime.format(-5)).isEqualTo("0:00")
  }

  @Test
  fun laNotaHaLAzioneEIlLettoreDiSchermoLaAnnuncia() {
    var clicks = 0
    composeRule.setContent {
      Stage { _, backdrop ->
        GalleryViewerNotice(
          icon = Icons.Rounded.CloudOff,
          title = "Il NAS non risponde",
          detail = "Vedi l'anteprima.",
          backdrop = backdrop,
          progress = GalleryNoticeProgress.Determinate(0.4f),
          actionLabel = "Riprova",
          onAction = { clicks++ },
        )
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Il NAS non risponde").assertExists()
    composeRule.onNodeWithText("Vedi l'anteprima.").assertExists()
    composeRule.onNodeWithText("Riprova").performClick()
    assertThat(clicks).isEqualTo(1)
    composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertExists()
  }

  @Test
  fun leSezioniDelPannelloSenzaRigheNonSiDisegnano() {
    var tapped = 0
    composeRule.setContent {
      MyCloudGalleryTheme {
        androidx.compose.foundation.layout.Column {
          GalleryInfoSection(header = "Vuota", rows = emptyList())
          GalleryInfoSection(
            header = "Luogo",
            rows = listOf(
              GalleryInfoRow(title = "Oslo, Norvegia", caption = "59,9127° N, 10,7461° E", icon = Icons.Rounded.Place, onClick = { tapped++ }),
              GalleryInfoRow(title = "martedì 12 marzo 2024", caption = "Data ricavata dal nome del file", pill = "Stimata"),
            ),
          )
        }
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("VUOTA").assertDoesNotExist()
    composeRule.onNodeWithText("LUOGO").assertExists()
    composeRule.onNodeWithText("Stimata").assertExists()
    composeRule.onNodeWithText("Oslo, Norvegia").performClick()
    assertThat(tapped).isEqualTo(1)
  }

  @Test
  fun l_IntestazioneDelPannelloHaIlTastoDaQuarantottoDp() {
    var closed = 0
    composeRule.setContent {
      MyCloudGalleryTheme { GalleryInfoHeader(title = "Informazioni", onClose = { closed++ }) }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Chiudi le informazioni").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
    assertThat(closed).isEqualTo(1)
  }
}
