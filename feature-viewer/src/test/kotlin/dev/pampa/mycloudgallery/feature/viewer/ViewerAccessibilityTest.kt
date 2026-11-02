package dev.pampa.mycloudgallery.feature.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.a11y.GalleryAccessibilityPrefs
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yAssertions
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yConfig
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yRule
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.model.MediaKind
import com.github.takahirom.roborazzi.checkRoboAccessibility
import org.junit.Test

/**
 * Fase 10, P8 (D23, D25-D27, E-AC7, E-AC9, §2.7 "Viewer", D28): il viewer letto come lo legge TalkBack. Gli stati del catalogo (aperto,
 * video, informazioni, menu, foglio di eliminazione), a testo 1,0 e 2,0 e con "piu' contrasto": nome e ruolo per ogni elemento attivo,
 * bersagli da 48 dp misurati, nessun id o nome di file nelle descrizioni, testo non tagliato; e le azioni al posto dei gesti.
 */
class ViewerAccessibilityTest : ViewerTestBase() {

  // Una prova monta il contenuto **una volta** (`setContent` non si puo' richiamare): richiesta, scala del testo, richieste del sistema e tema sono
  // stati osservabili, e `showA11y` li cambia e aspetta che si assesti.
  private val requestState = mutableStateOf<dev.pampa.mycloudgallery.core.library.contract.ViewerRequest?>(null)
  private val servicesState = mutableStateOf<ViewerServices?>(null)
  private val scale = mutableStateOf<Float?>(null)
  private val prefsState = mutableStateOf(GalleryAccessibilityPrefs.Standard)
  private val darkState = mutableStateOf(false)
  private var started = false

  private fun showA11y(
    fontScale: Float? = null,
    prefs: GalleryAccessibilityPrefs = GalleryAccessibilityPrefs.Standard,
    dark: Boolean = false,
    request: dev.pampa.mycloudgallery.core.library.contract.ViewerRequest = fixture.request(),
  ) {
    requestState.value = request
    servicesState.value = services()
    scale.value = fontScale
    prefsState.value = prefs
    darkState.value = dark
    if (!started) {
      started = true
      composeRule.setContent { Stage() }
    }
    composeRule.waitForIdle()
  }

  @Composable
  private fun Stage() {
    val request = requestState.value ?: return
    val services = servicesState.value ?: return
    MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (darkState.value) ThemeMode.DARK else ThemeMode.LIGHT), accessibility = prefsState.value) {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value ?: density.fontScale)) {
        key(request) {
          val modalHost = rememberFluidGlassModalHostState()
          val backdrop = rememberGlassBackdrop()
          CompositionLocalProvider(LocalFluidGlassModalHostState provides modalHost) {
            Box(Modifier.fillMaxSize().glassBackdropSource(backdrop)) { ViewerOverlay(request = request, onClosed = { closed++ }, services = services) }
            FluidGlassModalHost(state = modalHost, backdrop = backdrop)
          }
        }
      }
    }
  }

  private fun root(): SemanticsNode = composeRule.onRoot().fetchSemanticsNode()

  /**
   * [infoPanel]: il pannello **Informazioni** mostra di proposito i dettagli del file (nome, cartella): sono il contenuto della pagina, non la
   * descrizione di un media. La regola LEAK (E-AC12) vale per cio' che si legge di una foto o di un video, non per questo elenco di dettagli.
   */
  private fun config(infoPanel: Boolean = false) = A11yConfig(
    density = composeRule.density,
    exempt = { rule, _ -> infoPanel && rule == A11yRule.LEAK },
  )

  private fun position(): SemanticsNode = A11yAssertions.nodes(root()).first { Regex("^\\d+ di \\d+$").matches(A11yAssertions.name(it)) }

  @Test
  fun apertoPulito() {
    showA11y()
    A11yAssertions.assertClean(root(), config())
  }

  @Test
  fun videoPulito() {
    val index = fixture.indicesOf(MediaKind.VIDEO).first()
    showA11y(request = fixture.request(startIndex = index))
    A11yAssertions.assertClean(root(), config())
  }

  @Test
  fun informazioniPulite() {
    showA11y()
    composeRule.onNodeWithContentDescription("Informazioni").performClick()
    composeRule.waitForIdle()
    A11yAssertions.assertClean(root(), config(infoPanel = true))
  }

  @Test
  fun menuPulito() {
    showA11y()
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.waitUntil(10_000L) { composeRule.onAllNodesWithText("Copia").fetchSemanticsNodes().isNotEmpty() }
    A11yAssertions.assertClean(root(), config())
  }

  @Test
  fun foglioDiEliminazionePulitoEIlTitoloVieneDelPulsanteDistruttivo() {
    showA11y()
    composeRule.onNodeWithContentDescription("Elimina dal telefono").performClick()
    composeRule.waitUntil(10_000L) { composeRule.onAllNodesWithText("Eliminare la foto dal telefono?").fetchSemanticsNodes().isNotEmpty() }
    A11yAssertions.assertClean(root(), config())
    // Il foglio dice cosa succede e quanto nel titolo, ed e' un'intestazione: TalkBack parte da li', non dal pulsante distruttivo.
    val order = A11yAssertions.readingOrder(root())
    val title = order.indexOfFirst { A11yAssertions.name(it) == "Eliminare la foto dal telefono?" }
    val destructive = order.indexOfLast { A11yAssertions.isActionable(it) && A11yAssertions.name(it) == "Elimina dal telefono" }
    assertThat(title).isAtLeast(0)
    assertThat(A11yAssertions.headingNames(root())).contains("Eliminare la foto dal telefono?")
    if (destructive >= 0) assertThat(title).isLessThan(destructive)
  }

  @Test
  fun testoDoppioSenzaTagli() {
    showA11y(fontScale = 2.0f)
    A11yAssertions.assertClean(root(), config())
  }

  @Test
  fun contrastoAltoEMenoTrasparenzaPuliti() {
    for (p in listOf(GalleryAccessibilityPrefs(highContrast = true), GalleryAccessibilityPrefs(reduceTransparency = true))) {
      showA11y(prefs = p, dark = true)
      A11yAssertions.assertClean(root(), config())
    }
  }

  @Test
  fun siVaAvantiEIndietroSenzaGesti() {
    showA11y()
    val labels = A11yAssertions.customActionLabels(position())
    assertThat(labels).containsAtLeast("Foto successiva", "Foto precedente", "Informazioni")
    assertThat(labels.any { it == "Aggiungi ai preferiti" || it == "Togli dai preferiti" }).isTrue()
  }

  @Test
  fun alPrimoElementoNonCeLaPrecedente() {
    showA11y(request = fixture.request(startIndex = 0))
    val labels = A11yAssertions.customActionLabels(position())
    assertThat(labels).contains("Foto successiva")
    assertThat(labels).doesNotContain("Foto precedente")
  }

  @Test
  fun screenshotTesto2xEContrastoAlto() {
    showA11y(fontScale = 2.0f)
    composeRule.onRoot().captureRoboImage("../docs/screenshots/10-P8/viewer-testo-2x-chiaro-telefono.png")
    showA11y(prefs = GalleryAccessibilityPrefs(highContrast = true), dark = true)
    composeRule.onRoot().captureRoboImage("../docs/screenshots/10-P8/viewer-contrasto-alto-scuro-telefono.png")
    showA11y(prefs = GalleryAccessibilityPrefs(reduceTransparency = true))
    composeRule.onRoot().captureRoboImage("../docs/screenshots/10-P8/viewer-meno-trasparenza-chiaro-telefono.png")
  }

  @Test
  fun lePosizioniSiLeggonoComeNumeri() {
    showA11y()
    assertThat(A11yAssertions.name(position())).matches("\\d+ di \\d+")
  }

  // --- ATF (D28): il controllo automatico indipendente, sulle schermate principali del modulo -----------------------------------------

  @Test
  fun atfAperto() {
    showA11y()
    composeRule.onRoot().checkRoboAccessibility()
  }

  @Test
  fun atfInformazioni() {
    showA11y()
    composeRule.onNodeWithContentDescription("Informazioni").performClick()
    composeRule.waitForIdle()
    composeRule.onRoot().checkRoboAccessibility()
  }
}

