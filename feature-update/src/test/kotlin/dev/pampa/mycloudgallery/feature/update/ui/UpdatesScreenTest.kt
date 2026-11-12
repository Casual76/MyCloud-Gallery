package dev.pampa.mycloudgallery.feature.update.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.foundation.UpdateChannel
import dev.antigravity.fluidengine.ui.fluid.FluidUpdate
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.feature.update.R
import dev.pampa.mycloudgallery.feature.update.UpdatesState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pacchetto U: la pagina Impostazioni -> Aggiornamenti e l'avviso all'apertura, con stati di esempio ([UpdateSamples]): che cosa si vede (poche parole,
 * lo stato una volta), che cosa sente TalkBack, che cosa fanno i tasti.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w360dp-h1800dp-xhdpi")
class UpdatesScreenTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val called = mutableListOf<String>()
  private val channels = mutableListOf<UpdateChannel>()

  private val actions = UpdateActions(
    check = { called += "check" },
    install = { called += "install" },
    downloadMetered = { called += "metered" },
    cancelMetered = { called += "cancelMetered" },
    allow = { called += "allow" },
    cancelDownload = { called += "cancelDownload" },
    close = { called += "close" },
    chooseChannel = { channels += it },
    later = { called += "later" },
  )

  private fun show(content: @Composable () -> Unit) {
    rule.setContent { MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) { content() } }
    rule.waitForIdle()
  }

  private fun page(state: UpdatesState) = show { UpdatesScreen(state = state, actions = actions, onBack = {}) }

  private fun string(id: Int, vararg args: Any): String = rule.activity.getString(id, *args)

  private fun texts(): Set<String> =
    rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
      .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }.toSet()

  private fun choice(name: String) =
    rule.onNode(hasContentDescription(name, substring = true) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))

  @Test
  fun primaDiControllare_laFiguraEUnTasto_laVersioneInstallataUnaVolta() {
    page(UpdateSamples.idle)
    // In pagina: il titolo, il tasto, il canale con le due scelte e le loro righe, la versione installata. Nient'altro da leggere.
    assertThat(texts()).containsExactly(
      string(R.string.update_title),
      string(R.string.update_check_now),
      // L'intestazione di sezione comune scrive in maiuscolo.
      string(R.string.update_channel).uppercase(),
      string(R.string.update_channel_stable),
      string(R.string.update_channel_stable_detail),
      string(R.string.update_channel_beta),
      string(R.string.update_channel_beta_detail),
      string(R.string.update_installed_version),
      "1.0.0",
    )
    assertThat(rule.onAllNodesWithText("1.0.0").fetchSemanticsNodes()).hasSize(1)
    rule.onNodeWithText(string(R.string.update_check_now)).performClick()
    assertThat(called).containsExactly("check")
  }

  @Test
  fun ilCanaleSonoDueScelteConIlSegno_StabileDiSerie() {
    page(UpdateSamples.upToDate)
    val stable = choice(string(R.string.update_channel_stable)).fetchSemanticsNode()
    val beta = choice(string(R.string.update_channel_beta)).fetchSemanticsNode()
    assertThat(stable.config.getOrNull(SemanticsProperties.Selected)).isTrue()
    assertThat(stable.config.getOrNull(SemanticsProperties.StateDescription)).isEqualTo(string(R.string.update_selected))
    assertThat(beta.config.getOrNull(SemanticsProperties.Selected)).isFalse()
    choice(string(R.string.update_channel_beta)).performClick()
    assertThat(channels).containsExactly(UpdateChannel.BETA)
  }

  @Test
  fun unaVersioneNuova_versionePesoNovitaEAggiorna() {
    page(UpdateSamples.found)
    val size = FluidUpdate.sizeLabel(UpdateSamples.SIZE)!!
    rule.onNodeWithText(string(R.string.update_found, "1.1.0")).assertExists()
    rule.onNodeWithText(size).assertExists()
    rule.onNodeWithText(string(R.string.update_whats_new)).assertExists()
    // La nota di rilascio senza Markdown, solo il primo paragrafo.
    rule.onNode(hasText("Album più veloci", substring = true)).assertExists()
    assertThat(texts().none { it.contains("**") }).isTrue()
    rule.onNodeWithText(string(R.string.update_action)).performClick()
    assertThat(called).containsExactly("install")
  }

  @Test
  fun mentreScarica_lAnelloEIlPercentoPerTalkBack() {
    page(UpdateSamples.downloading)
    rule.onNode(hasContentDescription(string(R.string.update_progress_spoken, 42))).assertExists()
    rule.onNodeWithText(string(R.string.update_downloading)).assertExists()
    // Mentre scarica il canale non si cambia.
    choice(string(R.string.update_channel_beta)).performClick()
    assertThat(channels).isEmpty()
    rule.onNodeWithText(string(R.string.update_cancel)).performClick()
    assertThat(called).containsExactly("cancelDownload")
  }

  @Test
  fun suiDatiMobiliPrimaSiChiedeConIlPeso() {
    page(UpdateSamples.metered)
    val size = FluidUpdate.sizeLabel(UpdateSamples.SIZE)!!
    rule.onNodeWithText(string(R.string.update_metered)).assertExists()
    rule.onNodeWithText(string(R.string.update_download_anyway, size)).performClick()
    rule.onNodeWithText(string(R.string.update_cancel)).performClick()
    assertThat(called).containsExactly("metered", "cancelMetered").inOrder()
  }

  @Test
  fun ilPermessoInUnaRigaEConsenti() {
    page(UpdateSamples.permission)
    rule.onNodeWithText(string(R.string.update_permission)).assertExists()
    rule.onNodeWithText(string(R.string.update_allow)).performClick()
    assertThat(called).containsExactly("allow")
  }

  @Test
  fun unErroreCortoConRiprova() {
    page(UpdateSamples.signature)
    rule.onNodeWithText(string(R.string.update_failed)).assertExists()
    rule.onNodeWithText(string(R.string.update_problem_signature)).assertExists()
    rule.onNodeWithText(string(R.string.update_retry)).performClick()
    assertThat(called).containsExactly("install")
  }

  @Test
  fun unaVersionePubblicataMaleNonHaAggiorna() {
    page(UpdateSamples.unusable)
    rule.onNodeWithText(string(R.string.update_unusable, "1.1.0")).assertExists()
    assertThat(rule.onAllNodesWithText(string(R.string.update_action)).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun lAvviso_nuovaVersioneAggiornaEDopo() {
    show { UpdatePromptCard(state = UpdateSamples.found, actions = actions) }
    val size = FluidUpdate.sizeLabel(UpdateSamples.SIZE)!!
    rule.onNodeWithText(string(R.string.update_prompt_title)).assertExists()
    rule.onNodeWithText("1.1.0 · $size").assertExists()
    rule.onNodeWithText(string(R.string.update_later)).performClick()
    rule.onNodeWithText(string(R.string.update_action)).performClick()
    assertThat(called).containsExactly("later", "install").inOrder()
  }

  @Test
  fun lAvvisoMentreScarica_annullaODopo_ilDownloadContinua() {
    show { UpdatePromptCard(state = UpdateSamples.downloading, actions = actions) }
    rule.onNodeWithText(string(R.string.update_cancel)).assertExists()
    rule.onNodeWithText(string(R.string.update_later)).performClick()
    assertThat(called).containsExactly("later")
  }

  @Test
  fun lAvvisoNonCompareSenzaUnaVersione() {
    // Il modello dell'avviso non ha tasti di controllo: «Controlla adesso» e' della pagina.
    assertThat(promptButtons(UpdatePanels.of(UpdateSamples.upToDate)).mapNotNull { it.action }).isEmpty()
  }
}
