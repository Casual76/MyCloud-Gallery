package dev.pampa.mycloudgallery.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.catalog.SampleThumb
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryActionBar
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarAction
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryBarButton
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuEntry
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryMenuPanel
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryBadge
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryBadgeCluster
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryBadgeView
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryMediaCell
import dev.pampa.mycloudgallery.core.designsystem.media.GalleryTimelineHeaderAction
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryStatus
import dev.pampa.mycloudgallery.core.designsystem.state.GalleryStatusIndicator
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * L'accessibilita' dei componenti, provata sull'albero semantico: descrizioni che un lettore di
 * schermo pronuncia, stato di selezione, e i **48 dp** di bersaglio di tocco di ogni controllo.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class GalleryAccessibilityTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun show(content: @Composable () -> Unit) {
    composeRule.setContent { MyCloudGalleryTheme { content() } }
    composeRule.waitForIdle()
  }

  @Test
  fun laCellaPronunciaIlMediaEITuoiBadge() {
    show {
      GalleryMediaCell(
        contentDescription = "Foto, 12 marzo 2024",
        onClick = {},
        topEnd = { GalleryBadgeCluster(listOf(GalleryBadge.NasOnly)) },
        bottomStart = { GalleryBadgeCluster(listOf(GalleryBadge.Favorite)) },
        bottomEnd = { GalleryBadgeView(GalleryBadge.VideoDuration("0:42")) },
      ) { SampleThumb(1) }
    }

    val node = composeRule.onNode(hasContentDescription("Foto, 12 marzo 2024")).fetchSemanticsNode()
    val spoken = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    assertThat(spoken).contains("Foto, 12 marzo 2024")
    assertThat(spoken).contains("Solo sul NAS")
    assertThat(spoken).contains("Preferito")
    assertThat(spoken).contains("Video, durata 0:42")
  }

  @Test
  fun laCellaInSelezioneDiceSeESelezionata() {
    show {
      GalleryMediaCell(
        contentDescription = "Foto selezionata",
        selectionMode = true,
        selected = true,
        onClick = {},
      ) { SampleThumb(2) }
    }
    composeRule.onNode(hasContentDescription("Foto selezionata")).assertIsOn()
    val state = composeRule.onNode(hasContentDescription("Foto selezionata")).fetchSemanticsNode()
      .config.getOrNull(SemanticsProperties.StateDescription)
    assertThat(state).isEqualTo("Selezionato")
  }

  @Test
  fun unaCellaCheCaricaDiceChePuoEssereInCaricamento() {
    show {
      GalleryMediaCell(contentDescription = "Foto in arrivo", loaded = false) { SampleThumb(3) }
    }
    val state = composeRule.onNode(hasContentDescription("Foto in arrivo")).fetchSemanticsNode()
      .config.getOrNull(SemanticsProperties.StateDescription)
    assertThat(state).isEqualTo("Anteprima in caricamento")
  }

  @Test
  fun ogniBadgeHaUnaDescrizione() {
    val badges = listOf(
      GalleryBadge.NasOnly to "Solo sul NAS",
      GalleryBadge.PhoneOnly to "Solo sul telefono",
      GalleryBadge.Safe to "Al sicuro",
      GalleryBadge.Uploading(0.62f) to "In caricamento, 62%",
      GalleryBadge.Uploading(null) to "In caricamento",
      GalleryBadge.Failed to "Errore",
      GalleryBadge.Favorite to "Preferito",
      GalleryBadge.Shared to "Condiviso",
      GalleryBadge.EditedVersion to "Versione modificata",
      GalleryBadge.EstimatedDate to "Data stimata",
      GalleryBadge.VideoDuration("1:03:20") to "Video, durata 1:03:20",
    )
    show {
      Column { badges.forEach { (badge, _) -> GalleryBadgeView(badge) } }
    }
    badges.forEach { (_, expected) ->
      composeRule.onNodeWithContentDescription(expected).assertExists()
    }
  }

  @Test
  fun lIndicatoreDiStatoHaUnBersaglioDa48() {
    show { GalleryStatusIndicator(status = GalleryStatus.AllSafe, onClick = {}) }
    composeRule.onNodeWithContentDescription("Tutto al sicuro")
      .assertHeightIsAtLeast(48.dp)
      .assertWidthIsAtLeast(48.dp)
  }

  @Test
  fun lIndicatoreCompattoHaComunqueUnBersaglioDa48() {
    show { GalleryStatusIndicator(status = GalleryStatus.NasUnreachable, onClick = {}, compact = true) }
    composeRule.onNodeWithContentDescription("NAS non raggiungibile")
      .assertHeightIsAtLeast(48.dp)
      .assertWidthIsAtLeast(48.dp)
  }

  @Test
  fun iTastiDellaBarraHannoUnBersaglioDa48() {
    show {
      GalleryBarButton(icon = Icons.Rounded.Favorite, contentDescription = "Preferito", onClick = {})
    }
    composeRule.onNodeWithContentDescription("Preferito")
      .assertHeightIsAtLeast(48.dp)
      .assertWidthIsAtLeast(48.dp)
  }

  @Test
  fun leAzioniDellaBarraAzioniHannoUnBersaglioDa48() {
    show {
      GalleryActionBar(
        actions = listOf(
          GalleryBarAction(Icons.Rounded.Favorite, "Metti tra i preferiti", onClick = {}),
        ),
        backdrop = rememberGlassBackdrop(),
      )
    }
    composeRule.onNodeWithContentDescription("Metti tra i preferiti")
      .assertHeightIsAtLeast(48.dp)
      .assertWidthIsAtLeast(48.dp)
  }

  @Test
  fun leVociDelMenuHannoUnBersaglioDa48() {
    show {
      GalleryMenuPanel(
        entries = listOf(
          GalleryMenuEntry.Item(label = "Condividi", onClick = {}),
          GalleryMenuEntry.GroupBreak,
          GalleryMenuEntry.Item(label = "Elimina…", onClick = {}, destructive = true),
        ),
      )
    }
    composeRule.onNodeWithText("Condividi").assertHeightIsAtLeast(48.dp)
    composeRule.onNodeWithText("Elimina…").assertHeightIsAtLeast(48.dp)
  }

  @Test
  fun lAzioneDellIntestazioneHaUnBersaglioDa48() {
    show { GalleryTimelineHeaderAction(label = "Seleziona", onClick = {}) }
    composeRule.onNodeWithText("Seleziona")
      .assertHeightIsAtLeast(48.dp)
      .assertWidthIsAtLeast(48.dp)
  }
}
