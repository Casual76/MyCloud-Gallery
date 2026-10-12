package dev.pampa.mycloudgallery.core.qr.scanner

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.qr.R
import dev.pampa.mycloudgallery.core.qr.contract.ScanRejection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Le pagine dello scanner, da un [ScannerUi] e senza permessi ne' fotocamera: mirino, torcia, rifiuti,
 * bersagli di tocco, testo ingrandito e altezza non limitata.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ScannerContentTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private fun str(id: Int): String = rule.activity.getString(id)

  private fun show(
    ui: ScannerUi,
    actions: ScannerActions = ScannerActions(),
    fontScale: Float? = null,
    host: @Composable (@Composable () -> Unit) -> Unit = { it() },
    feed: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
  ) {
    rule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings()) {
        val density = LocalDensity.current
        val provided = if (fontScale == null) density else Density(density.density, fontScale)
        CompositionLocalProvider(LocalDensity provides provided) {
          host { ScannerContent(ui = ui, actions = actions, feed = feed) }
        }
      }
    }
    rule.waitForIdle()
  }

  // --- mirino ---------------------------------------------------------------------------------------------------

  @Test
  fun ilMirino_haUnaDescrizione_eIlSuggerimento() {
    show(ScannerUi(access = CameraAccess.Granted))
    rule.onNodeWithContentDescription(str(R.string.qr_viewfinder_description)).assertIsDisplayed()
    rule.onNodeWithText(str(R.string.qr_viewfinder_hint)).assertIsDisplayed()
    rule.onNodeWithText(str(R.string.qr_manual_open)).assertIsDisplayed()
    rule.onNodeWithText(str(R.string.qr_cancel)).assertIsDisplayed()
  }

  @Test
  fun laFotocameraVieneDalLambdaFeed() {
    var composed = false
    show(ScannerUi(access = CameraAccess.Granted)) { composed = true }
    assertThat(composed).isTrue()
  }

  @Test
  fun aLetturaAvvenuta_laFotocameraSiSpegne_eNonRestanoBottoni() {
    var composed = false
    show(ScannerUi(access = CameraAccess.Granted, read = true)) { composed = true }
    assertThat(composed).isFalse() // niente fotocamera: CameraX si slega perche' il composable esce
    rule.onNodeWithText(str(R.string.qr_read_done)).assertIsDisplayed()
    rule.onNodeWithText(str(R.string.qr_manual_open)).assertDoesNotExist()
    rule.onNodeWithText(str(R.string.qr_cancel)).assertDoesNotExist()
  }

  @Test
  fun unRifiuto_sostituisceIlSuggerimento_conIlMotivo() {
    show(ScannerUi(access = CameraAccess.Granted, rejection = ScanRejection.EXPIRED))
    rule.onNodeWithText(str(R.string.qr_reject_expired)).assertIsDisplayed()
    rule.onNodeWithText(str(R.string.qr_viewfinder_hint)).assertDoesNotExist()
  }

  // --- torcia ---------------------------------------------------------------------------------------------------

  @Test
  fun senzaTorcia_ilBottoneNonCe() {
    show(ScannerUi(access = CameraAccess.Granted, torch = TorchUi(available = false)))
    rule.onNodeWithContentDescription(str(R.string.qr_torch)).assertDoesNotExist()
  }

  @Test
  fun laTorcia_eUnInterruttoreConIlSuoStato_eSiAccendeAlTocco() {
    var toggled = 0
    val actions = ScannerActions(onToggleTorch = { toggled++ })
    show(ScannerUi(access = CameraAccess.Granted, torch = TorchUi(available = true, on = false)), actions)

    val node = rule.onNodeWithContentDescription(str(R.string.qr_torch))
    node.assertIsDisplayed()
    assertThat(node.fetchSemanticsNode().config[SemanticsProperties.StateDescription]).isEqualTo(str(R.string.qr_torch_off))
    assertThat(node.fetchSemanticsNode().config[SemanticsProperties.Role]).isEqualTo(Role.Switch)
    node.performClick()
    assertThat(toggled).isEqualTo(1)
  }

  @Test
  fun laTorciaAccesa_dichiaraIlSuoStato() {
    show(ScannerUi(access = CameraAccess.Granted, torch = TorchUi(available = true, on = true)))
    val state = rule.onNodeWithContentDescription(str(R.string.qr_torch)).fetchSemanticsNode()
      .config[SemanticsProperties.StateDescription]
    assertThat(state).isEqualTo(str(R.string.qr_torch_on))
  }

  // --- accessibilita' ------------------------------------------------------------------------------------------

  private fun everyScreen(): List<Pair<String, ScannerUi>> = listOf(
    "chiesta" to ScannerUi(access = CameraAccess.NotAsked),
    "negato" to ScannerUi(access = CameraAccess.Denied),
    "bloccato" to ScannerUi(access = CameraAccess.DeniedPermanently, manualText = "MCG1:ABC"),
    "senza fotocamera" to ScannerUi(access = CameraAccess.NoCamera, rejection = ScanRejection.MALFORMED, manualText = "x"),
    "mirino" to ScannerUi(access = CameraAccess.Granted, torch = TorchUi(available = true)),
    "a mano" to ScannerUi(access = CameraAccess.Granted, manualOpen = true, manualText = "MCG1:ABC"),
  )

  @Test
  fun ognunDeiBottoniHaUnBersaglioDiAlmeno48dp() {
    val minPx = (48 * rule.activity.resources.displayMetrics.density).toInt()
    var index = 0
    val screens = everyScreen()
    // un test Compose chiama setContent una volta: le pagine si cambiano da uno stato
    var current by mutableStateOf(screens[0].second)
    rule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings()) {
        ScannerContent(ui = current, actions = ScannerActions(), feed = {})
      }
    }
    for ((name, ui) in screens) {
      rule.runOnUiThread { current = ui }
      rule.waitForIdle()
      val buttons = rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) or
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).fetchSemanticsNodes()
      assertThat(buttons).isNotEmpty()
      for (button in buttons) {
        assertThat(button.size.height).isAtLeast(minPx)
        assertThat(button.size.width).isAtLeast(minPx)
      }
      index++
      assertThat(name).isNotEmpty()
    }
    assertThat(index).isEqualTo(screens.size)
  }

  @Test
  fun iTitoliSonoIntestazioniPerIlLettoreDiSchermo() {
    show(ScannerUi(access = CameraAccess.NotAsked))
    val title = rule.onNodeWithText(str(R.string.qr_perm_ask_title)).fetchSemanticsNode()
    assertThat(title.config.contains(SemanticsProperties.Heading)).isTrue()
  }

  // --- testo grande e contenitori ------------------------------------------------------------------------------

  @Test
  fun conIlTestoAlMassimo_ilBottonePrincipaleSiRaggiungeScorrendo() {
    show(
      ui = ScannerUi(access = CameraAccess.DeniedPermanently, manualText = "MCG1:ABC"),
      fontScale = 2f,
      host = { content -> Box(Modifier.height(500.dp)) { content() } },
    )
    rule.onNodeWithText(str(R.string.qr_manual_submit)).performScrollTo().assertIsDisplayed()
    rule.onNodeWithText(str(R.string.qr_cancel)).performScrollTo().assertIsDisplayed()
  }

  @Test
  fun inUnContenitoreCheScorre_senzaAltezzaLimitata_nonCrasha() {
    show(
      ui = ScannerUi(access = CameraAccess.Granted),
      host = { content -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() } },
    )
    rule.onNodeWithText(str(R.string.qr_viewfinder_hint)).assertIsDisplayed()
  }
}
