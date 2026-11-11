package dev.pampa.mycloudgallery.feature.settings.library

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.SettingsScreen
import dev.pampa.mycloudgallery.feature.settings.SettingsUiState
import java.util.Optional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** L'impostazione sopra una porta finta: tiene il valore e dice quante volte e' stato scritto. */
internal class FakeMotionAnimationSettings(on: Boolean = true) : MotionAnimationSettings {
  override val enabled: MutableStateFlow<Boolean> = MutableStateFlow(on)
  val writes = mutableListOf<Boolean>()

  override suspend fun setEnabled(on: Boolean) {
    writes += on
    enabled.value = on
  }
}

/** La logica della sezione: senza collegamento non c'e', col collegamento segue il valore e lo scrive (D13, D50). */
@OptIn(ExperimentalCoroutinesApi::class)
class MotionAnimationViewModelTest {

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun senzaIlCollegamentoDiAppLaSezioneNonCe_EScrivereNonFaNiente() = runTest {
    val viewModel = MotionAnimationViewModel(Optional.empty())
    assertThat(viewModel.enabled.value).isNull()
    viewModel.set(false)
    assertThat(viewModel.enabled.value).isNull()
  }

  @Test
  fun conIlCollegamentoSegueIlValoreDellImpostazione_AccesaDiSerie() = runTest {
    val port = FakeMotionAnimationSettings()
    val viewModel = MotionAnimationViewModel(Optional.of(port))
    assertThat(viewModel.enabled.value).isTrue()
    viewModel.set(false)
    assertThat(port.writes).containsExactly(false)
    assertThat(viewModel.enabled.value).isFalse()
    // Un secondo dispositivo dello stesso account cambia la voce: la pagina la segue.
    port.enabled.value = true
    assertThat(viewModel.enabled.value).isTrue()
  }
}

/**
 * La riga e la sezione "Libreria" delle Impostazioni (Fase 6, P14; D50, Q16 (b)): per TalkBack e' un interruttore con il suo stato e il suo
 * nome, dice che il tasto Movimento resta, compare solo se `:app` ha collegato l'impostazione, bersaglio da 48 dp anche a 1,3x.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w412dp-h1800dp-xhdpi")
class MotionAnimationScreenTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val fontScale = mutableFloatStateOf(1f)
  private val state = SettingsUiState(TrustStatus.Approved(admin = false), deviceCount = 3, pendingRequests = 0, pendingSteps = emptyList())

  private fun show(content: @Composable () -> Unit) {
    rule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.floatValue)) { content() }
      }
    }
    rule.waitForIdle()
  }

  private fun string(id: Int): String = rule.activity.getString(id)

  private fun has(text: String): Boolean = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

  private fun scrollToLibrary() {
    rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(string(R.string.library_motion_animation)))
    rule.waitForIdle()
  }

  private fun switchNode(): SemanticsNode =
    rule.onAllNodes(SemanticsMatcher("interruttore") { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch }).fetchSemanticsNodes().single()

  private fun page(on: Boolean?, toggles: MutableList<Boolean> = mutableListOf()) =
    show { SettingsScreen(state = state, onBack = {}, openDiagnostics = {}, resumeStep = {}, motionAnimation = on, onMotionAnimation = { toggles += it }) }

  @Test
  fun senzaLImpostazioneCollegataLaSezioneNonCe() {
    page(on = null)
    // I3 (fase I): la riga sta nel gruppo «Foto», che c'e' sempre; senza l'impostazione collegata la riga non c'e'.
    assertThat(has(string(R.string.library_motion_animation))).isFalse()
  }

  @Test
  fun acceseDiDefaultLaRigaEUnInterruttoreAcceso() {
    page(on = true)
    scrollToLibrary()
    // Il titolo della sezione puo' essere scritto in maiuscole dall'engine: si cerca senza badare alla grafia.
    val header = rule.onAllNodesWithText(string(R.string.settings_section_photos), ignoreCase = true).fetchSemanticsNodes()
    assertThat(header).isNotEmpty()
    assertThat(switchNode().config[SemanticsProperties.ToggleableState]).isEqualTo(ToggleableState.On)
  }

  @Test
  fun spentaLInterruttoreEspento() {
    page(on = false)
    scrollToLibrary()
    assertThat(switchNode().config[SemanticsProperties.ToggleableState]).isEqualTo(ToggleableState.Off)
  }

  @Test
  fun toccareLaRigaInvertePassandoIlValoreNuovoUnaVolta() {
    val toggles = mutableListOf<Boolean>()
    page(on = true, toggles)
    scrollToLibrary()
    rule.onNodeWithText(string(R.string.library_motion_animation)).performClick()
    rule.waitForIdle()
    assertThat(toggles).containsExactly(false)
  }

  @Test
  fun daSpentaToccareLaRigaLaAccende() {
    val toggles = mutableListOf<Boolean>()
    page(on = false, toggles)
    scrollToLibrary()
    rule.onNodeWithText(string(R.string.library_motion_animation)).performClick()
    rule.waitForIdle()
    assertThat(toggles).containsExactly(true)
  }

  @Test
  fun perTalkBackEUnInterruttoreConIlSuoNomeEDiceCheIlTastoMovimentoResta() {
    page(on = true)
    scrollToLibrary()
    val name = switchNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
    assertThat(name).contains(string(R.string.library_motion_animation))
    assertThat(name).contains("Il tasto Movimento resta sempre")
  }

  @Test
  fun leFrasiSonoSempliciENonHannoGergo() {
    val all = listOf(
      R.string.settings_section_photos,
      R.string.library_motion_animation,
      R.string.library_motion_animation_detail,
    ).map { string(it) }
    for (text in all) {
      assertThat(text).isNotEmpty()
      for (word in listOf("decoder", "Media3", "XMP", "MotionPhoto", "SEF", "timestamp")) {
        assertWithMessage("\"$text\" nomina $word").that(text.contains(word, ignoreCase = true)).isFalse()
      }
    }
  }

  @Test
  fun ilBersaglioEAltoAlmeno48dp_ADueDimensioniDiTesto_ELaSezioneSiAggiornaDalValore() {
    val on = mutableStateOf(true)
    show { SettingsScreen(state = state, onBack = {}, openDiagnostics = {}, resumeStep = {}, motionAnimation = on.value, onMotionAnimation = { on.value = it }) }
    val min = with(rule.density) { GalleryDimens.MinTouchTarget.toPx() }
    for (scale in listOf(1f, 1.3f)) {
      fontScale.floatValue = scale
      for (value in listOf(true, false)) {
        rule.runOnIdle { on.value = value }
        rule.waitForIdle()
        scrollToLibrary()
        val node = switchNode()
        assertWithMessage("alto almeno 48 dp a ${scale}x").that(node.boundsInRoot.height).isAtLeast(min - 1f)
        assertWithMessage("largo almeno 48 dp a ${scale}x").that(node.boundsInRoot.width).isAtLeast(min - 1f)
        assertThat(node.config[SemanticsProperties.ToggleableState]).isEqualTo(if (value) ToggleableState.On else ToggleableState.Off)
      }
    }
  }
}
