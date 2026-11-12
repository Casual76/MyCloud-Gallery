package dev.pampa.mycloudgallery.feature.settings.appicon

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.SettingsLinks
import dev.pampa.mycloudgallery.feature.settings.SettingsScreen
import dev.pampa.mycloudgallery.feature.settings.SettingsUiState
import dev.pampa.mycloudgallery.feature.settings.AppIconEntry
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * I5: Impostazioni -> Icona dell'app. Tre anteprime (Automatica, Chiara, Scura) che TalkBack legge come scelte con il nome e lo stato, il tocco che
 * sceglie subito, una riga sola di avviso e nient'altro; la voce nella pagina principale (l'icona scelta in piccolo, il nome a destra) solo se `:app`
 * ha collegato l'icona. Le risorse qui sono quelle del sistema: gli screenshot con le risorse vere sono in `:app` (`AppIconShots`).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w360dp-h1800dp-xhdpi")
class AppIconScreenTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val art = AppIconArt(
    foreground = android.R.drawable.ic_menu_gallery,
    lightBackground = android.R.color.white,
    darkBackground = android.R.color.black,
  )

  private fun show(content: @Composable () -> Unit) {
    rule.setContent { MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) { content() } }
    rule.waitForIdle()
  }

  private fun string(id: Int): String = rule.activity.getString(id)

  private fun option(name: String) =
    rule.onNode(hasContentDescription(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))

  @Test
  fun treSceltePerTalkBackConNomeEStato() {
    show { AppIconScreen(choice = AppIconChoice.AUTO, art = art, onBack = {}, onChoose = {}) }

    val selected = mapOf(R.string.app_icon_auto to true, R.string.app_icon_light to false, R.string.app_icon_dark to false)
    for ((id, expected) in selected) {
      val node = option(string(id)).fetchSemanticsNode()
      assertWithMessage("${string(id)}: selezionata").that(node.config.getOrNull(SemanticsProperties.Selected)).isEqualTo(expected)
    }
    assertThat(option(string(R.string.app_icon_auto)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
      .isEqualTo(string(R.string.selected))
  }

  @Test
  fun ilToccoSceglieSubitoESoloUnaVolta() {
    val chosen = mutableListOf<AppIconChoice>()
    var choice by mutableStateOf(AppIconChoice.AUTO)
    show { AppIconScreen(choice = choice, art = art, onBack = {}, onChoose = { chosen += it; choice = it }) }

    option(string(R.string.app_icon_dark)).performClick()
    rule.waitForIdle()
    assertThat(chosen).containsExactly(AppIconChoice.DARK)
    assertThat(option(string(R.string.app_icon_dark)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected)).isTrue()

    // Toccare quella gia' scelta non chiede niente.
    option(string(R.string.app_icon_dark)).performClick()
    rule.waitForIdle()
    assertThat(chosen).containsExactly(AppIconChoice.DARK)
  }

  @Test
  fun unaRigaSolaDiTesto() {
    show { AppIconScreen(choice = AppIconChoice.LIGHT, art = art, onBack = {}, onChoose = {}) }

    assertThat(rule.onAllNodesWithText(string(R.string.app_icon_warning)).fetchSemanticsNodes()).hasSize(1)
    // In pagina: il titolo, i tre nomi e l'avviso. Nient'altro da leggere.
    val texts = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
      .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
    assertThat(texts.toSet()).containsExactly(
      string(R.string.app_icon_title),
      string(R.string.app_icon_auto),
      string(R.string.app_icon_light),
      string(R.string.app_icon_dark),
      string(R.string.app_icon_warning),
    )
  }

  @Test
  fun laVoceNellaPaginaPrincipaleSoloSeCollegata() {
    var current by mutableStateOf<AppIconEntry?>(null)
    show {
      SettingsScreen(
        state = SettingsUiState(TrustStatus.Approved(admin = false), deviceCount = 3, pendingRequests = 0, pendingSteps = emptyList()),
        onBack = {},
        openDiagnostics = {},
        resumeStep = {},
        links = SettingsLinks(openAppIcon = {}),
        appIcon = current,
      )
    }
    val title = string(R.string.app_icon_title)
    assertThat(rule.onAllNodesWithText(title, substring = true).fetchSemanticsNodes()).isEmpty()

    current = AppIconEntry(AppIconChoice.DARK, art)
    rule.waitForIdle()
    rule.onNode(hasContentDescription(title + ", " + string(R.string.app_icon_dark))).assertExists()
  }
}
