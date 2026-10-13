package dev.pampa.mycloudgallery.core.designsystem.grid

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryGridLevels
import dev.pampa.mycloudgallery.core.designsystem.motion.GalleryPinchSpec
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** I pezzi della griglia del design system: i livelli per larghezza e la cella leggera per il lettore di schermo. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class GalleryGridTest {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun leColonneDeiLivelliSeguonoLaLarghezza() {
    assertThat(GalleryGridLevels.forWidth(412f).map { it.columns }).containsExactly(7, 5, 4, 3).inOrder()
    assertThat(GalleryGridLevels.forWidth(1280f).map { it.columns }).containsExactly(16, 12, 10, 8).inOrder()
    // In una finestra stretta (split screen) i livelli restano quattro livelli diversi, dal piu' fitto al piu' largo.
    for (width in listOf(160f, 240f, 300f, 360f, 600f, 840f, 1000f, 2400f)) {
      val columns = GalleryGridLevels.forWidth(width).map { it.columns }
      assertThat(columns.zipWithNext().all { (a, b) -> a > b }).isTrue()
      assertThat(columns.last()).isAtLeast(1)
      // Il pinch sa risolvere ogni livello (nessuna coppia di livelli con le stesse colonne).
      columns.indices.forEach { assertThat(GalleryPinchSpec.resolve(columns, it, 1f).level).isEqualTo(it) }
    }
    assertThat(GalleryGridLevels.forWidth(412f).map { it.label }).isEqualTo(GalleryGridLevels.Default.map { it.label })
  }

  private object Actions : GalleryGridCellActions {
    var clicks = 0
    var longClicks = 0
    override fun describe(key: Any): String = "Foto, 12 marzo 2024, Oslo"
    override fun click(key: Any) {
      clicks++
    }

    override fun longClick(key: Any) {
      longClicks++
    }

    override val clickLabel: String = "Apri"
    override val longClickLabel: String = "Seleziona"
  }

  @Test
  fun laCellaParlaAlLettoreDiSchermoESiSeleziona() {
    val interaction = GalleryGridInteractionState()
    val image = mutableStateOf<ImageBitmap?>(null)
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings()) {
        GalleryGridCell(
          key = "a",
          image = image,
          badges = GalleryGridCellBadges(favorite = true, duration = "0:42"),
          style = rememberGalleryGridCellStyle(),
          interaction = interaction,
          actions = Actions,
          modifier = Modifier.size(100.dp),
        )
      }
    }
    val node = composeRule.onNodeWithContentDescription("Foto, 12 marzo 2024, Oslo")
    var config = node.fetchSemanticsNode().config
    assertThat(config.getOrNull(SemanticsProperties.Role)).isEqualTo(Role.Button)
    assertThat(config.getOrNull(SemanticsProperties.StateDescription)).isEqualTo("Anteprima in caricamento")
    assertThat(config[SemanticsActions.OnClick].label).isEqualTo("Apri")
    assertThat(config[SemanticsActions.OnLongClick].label).isEqualTo("Seleziona")

    // La miniatura arriva: non e' piu' "in caricamento".
    composeRule.runOnIdle { image.value = ImageBitmap(4, 4) }
    composeRule.waitForIdle()
    assertThat(node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)).isNull()

    // Modalita' selezione: una casella da spuntare.
    composeRule.runOnIdle { runBlocking { interaction.animateSelectionMode(on = true, reducedMotion = true) } }
    composeRule.waitForIdle()
    config = node.fetchSemanticsNode().config
    assertThat(config.getOrNull(SemanticsProperties.Role)).isEqualTo(Role.Checkbox)
    assertThat(config[SemanticsProperties.ToggleableState]).isEqualTo(ToggleableState.Off)
    composeRule.runOnIdle { interaction.selectedKeys = setOf("a") }
    composeRule.waitForIdle()
    config = node.fetchSemanticsNode().config
    assertThat(config[SemanticsProperties.ToggleableState]).isEqualTo(ToggleableState.On)
    assertThat(config[SemanticsProperties.StateDescription]).isEqualTo("Selezionato")

    composeRule.runOnIdle { config[SemanticsActions.OnClick].action?.invoke() }
    assertThat(Actions.clicks).isEqualTo(1)
  }
}
