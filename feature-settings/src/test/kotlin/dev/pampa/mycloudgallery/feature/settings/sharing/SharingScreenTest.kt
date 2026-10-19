package dev.pampa.mycloudgallery.feature.settings.sharing

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.SettingsLinks
import dev.pampa.mycloudgallery.feature.settings.SettingsScreen
import dev.pampa.mycloudgallery.feature.settings.SettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La pagina "Condivisioni" delle Impostazioni (Fase 5B, P12; `docs/fase5-progetto.md` §1.3, §2.7, D26, E-X5, C18, L41): l'interruttore "Rimuovi la
 * posizione" (per TalkBack e' un interruttore, con il suo stato), cosa succede acceso e spento, il controllo della copia e i formati che si sanno
 * trattare; la riga nella pagina principale e il suo indicatore. Bersagli da 48 dp a 1x e a 1,3x su telefono e tablet.
 */
abstract class SharingComposeBase {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  protected val fontScale = mutableFloatStateOf(1f)

  protected fun show(dark: Boolean = false, scale: Float? = null, content: @Composable () -> Unit) {
    fontScale.floatValue = scale ?: 1f
    rule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.floatValue)) { content() }
      }
    }
    rule.waitForIdle()
  }

  protected fun string(id: Int): String = rule.activity.getString(id)

  protected fun has(text: String): Boolean = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

  protected fun switchNode(): SemanticsNode =
    rule.onAllNodes(SemanticsMatcher("interruttore") { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch }).fetchSemanticsNodes().single()
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w412dp-h1800dp-xhdpi")
class SharingScreenTest : SharingComposeBase() {

  private fun page(on: Boolean, toggles: MutableList<Boolean> = mutableListOf()) =
    show { SharingScreen(removeLocation = on, onBack = {}, onToggle = { toggles += it }) }

  @Test
  fun spentaDiceCheLaPosizioneRestaEIlSuoStatoEDiSpento() {
    page(on = false)
    assertThat(has(string(R.string.sharing_remove_location))).isTrue()
    assertThat(has(string(R.string.sharing_state_off_detail))).isTrue()
    assertThat(has(string(R.string.sharing_state_on_detail))).isFalse()
    assertThat(switchNode().config[SemanticsProperties.ToggleableState]).isEqualTo(ToggleableState.Off)
  }

  @Test
  fun accesaDiceCheLaPosizioneSiToglieEIlMenuDelViewerLoDice() {
    page(on = true)
    assertThat(has(string(R.string.sharing_state_on_detail))).isTrue()
    assertThat(has(string(R.string.sharing_state_off_detail))).isFalse()
    assertThat(switchNode().config[SemanticsProperties.ToggleableState]).isEqualTo(ToggleableState.On)
    // L'indicatore: la frase dice dove si vede.
    assertThat(string(R.string.sharing_state_on_detail)).contains("Condividi senza posizione")
  }

  @Test
  fun toccareLaRigaInvertePassandoIlValoreNuovoUnaVolta() {
    val toggles = mutableListOf<Boolean>()
    page(on = false, toggles)
    rule.onNodeWithText(string(R.string.sharing_remove_location)).performClick()
    rule.waitForIdle()
    assertThat(toggles).containsExactly(true)
  }

  @Test
  fun daAccesaToccareLaRigaLaSpegne() {
    val toggles = mutableListOf<Boolean>()
    page(on = true, toggles)
    rule.onNodeWithText(string(R.string.sharing_remove_location)).performClick()
    rule.waitForIdle()
    assertThat(toggles).containsExactly(false)
  }

  @Test
  fun lInterruttoreEUnSoloControlloPerTalkBack() {
    page(on = false)
    // Il nome letto da TalkBack e' la riga intera (titolo e spiegazione), non un interruttore senza nome.
    val name = switchNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
    assertThat(name).contains(string(R.string.sharing_remove_location))
    assertThat(name).contains(string(R.string.sharing_remove_location_detail))
  }

  @Test
  fun ilControlloEIlPerCheDeiFormatiCiSono_L41() {
    page(on = false)
    rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(string(R.string.sharing_footnote_formats), substring = true))
    assertThat(has(string(R.string.sharing_footnote_check))).isTrue()
    val formats = string(R.string.sharing_footnote_formats)
    for (format in listOf("JPEG", "PNG", "WebP", "MP4", "MOV", "HEIC")) assertThat(formats).contains(format)
  }

  @Test
  fun leFrasiSonoSempliciENonHannoGergo() {
    val all = listOf(
      R.string.sharing_title, R.string.sharing_section_location, R.string.sharing_remove_location, R.string.sharing_remove_location_detail,
      R.string.sharing_state_on, R.string.sharing_state_on_detail, R.string.sharing_state_off, R.string.sharing_state_off_detail,
      R.string.sharing_footnote_check, R.string.sharing_footnote_formats, R.string.sharing_entry_on, R.string.sharing_entry_off,
    ).map { string(it) }
    for (text in all) {
      assertThat(text).isNotEmpty()
      for (word in listOf("EXIF", "GPS", "metadat", "tag", "IPTC", "XMP")) {
        assertWithMessage("\"$text\" nomina $word").that(text.contains(word, ignoreCase = false)).isFalse()
      }
    }
  }

  // --- La riga nella pagina principale -------------------------------------------------------------------------------------------------

  private val settingsState = SettingsUiState(TrustStatus.Approved(admin = false), deviceCount = 3, pendingRequests = 0, pendingSteps = emptyList())

  private fun settings(links: SettingsLinks, removeLocation: Boolean) {
    show { SettingsScreen(state = settingsState, onBack = {}, openDiagnostics = {}, resumeStep = {}, links = links, removeLocation = removeLocation) }
  }

  private fun scrollTo(text: String) {
    rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
    rule.waitForIdle()
  }

  @Test
  fun senzaLaCallbackLaVoceNonCe() {
    settings(SettingsLinks(), removeLocation = false)
    scrollTo("Copia esterna del NAS")
    assertThat(has(string(R.string.sharing_title))).isFalse()
  }

  @Test
  fun laVoceApreLaPaginaUnaVolta() {
    var opened = 0
    settings(SettingsLinks(openSharing = { opened++ }), removeLocation = false)
    scrollTo(string(R.string.sharing_entry_off))
    rule.onNodeWithText(string(R.string.sharing_entry_off)).assertIsDisplayed().performClick()
    assertThat(opened).isEqualTo(1)
  }

  @Test
  fun laVoceDiceSeLaPosizioneSiToglie() {
    val removing = mutableStateOf(false)
    show { SettingsScreen(state = settingsState, onBack = {}, openDiagnostics = {}, resumeStep = {}, links = SettingsLinks(openSharing = {}), removeLocation = removing.value) }
    scrollTo(string(R.string.sharing_entry_off))
    assertThat(has(string(R.string.sharing_entry_on))).isFalse()
    rule.runOnIdle { removing.value = true }
    rule.waitForIdle()
    // L'indicatore e' la frase: dice che la posizione si toglie, e la voce di prima non c'e' piu'.
    assertThat(has(string(R.string.sharing_entry_on))).isTrue()
    assertThat(has(string(R.string.sharing_entry_off))).isFalse()
  }
}

// --- I bordi: 48 dp, dentro lo schermo, niente sopra altro (docs/grafica-progetto.md §3, P2 e P3) ---------------------------------------------

private fun Rect.crosses(other: Rect): Boolean = left < other.right - 1f && other.left < right - 1f && top < other.bottom - 1f && other.top < bottom - 1f

private fun Rect.contains(other: Rect): Boolean = left <= other.left + 1f && top <= other.top + 1f && right >= other.right - 1f && bottom >= other.bottom - 1f

/** Il nome come lo leggono gli altri pacchetti: la descrizione, o il testo, o quello dei figli (la riga dell'engine tiene il testo in un figlio). */
private fun SemanticsNode.readName(): String =
  config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(", ").orEmpty().ifEmpty {
    config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
  }.ifEmpty { children.joinToString(" ") { it.readName() }.trim() }

private val ENGINE_BAR = setOf("Torna all'inizio", "Indietro")

abstract class SharingLayoutBase : SharingComposeBase() {

  private fun assertBounds(where: String) {
    val min = with(rule.density) { GalleryDimens.MinTouchTarget.toPx() }
    val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
    val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
    assertWithMessage("$where: ci sono controlli").that(nodes).isNotEmpty()
    for (node in nodes) {
      val b = node.boundsInRoot
      val name = node.readName()
      assertWithMessage("$where: $b ha un nome").that(name).isNotEmpty()
      // La barra di `FluidScreen` e' dell'engine (non si tocca): il tasto "Indietro" e il tocco sul titolo sono alti 44 dp, `ControlRowHeight`. Si
      // segnala (rapporto di P12), qui si provano i controlli della pagina, che sono nostri.
      if (name !in ENGINE_BAR) {
        assertWithMessage("$where: $name $b alto almeno 48 dp").that(b.height).isAtLeast(min - 1f)
        assertWithMessage("$where: $name $b largo almeno 48 dp").that(b.width).isAtLeast(min - 1f)
      }
      assertWithMessage("$where: $name $b dentro lo schermo $root").that(b.left >= root.left - 1f && b.right <= root.right + 1f).isTrue()
    }
    for (i in nodes.indices) {
      for (j in i + 1 until nodes.size) {
        val a = nodes[i].boundsInRoot
        val b = nodes[j].boundsInRoot
        if (a.contains(b) || b.contains(a)) continue
        assertWithMessage("$where: ${nodes[i].readName()} $a e ${nodes[j].readName()} $b si sovrappongono").that(a.crosses(b)).isFalse()
      }
    }
  }

  private fun eachState(name: String, scene: @Composable (Boolean) -> Unit) {
    val on = mutableStateOf(false)
    show { scene(on.value) }
    for (scale in listOf(1f, 1.3f)) {
      fontScale.floatValue = scale
      for (state in listOf(false, true)) {
        rule.runOnIdle { on.value = state }
        rule.waitForIdle()
        assertBounds("$name, ${if (state) "acceso" else "spento"}, ${scale}x")
      }
    }
  }

  @Test
  fun laPagina() = eachState("Condivisioni") { on -> SharingScreen(removeLocation = on, onBack = {}, onToggle = {}) }

  @Test
  fun laRigaNelleImpostazioni() = eachState("Voce Condivisioni") { on ->
    Box(Modifier.fillMaxSize().padding(16.dp)) {
      FluidListGroup { SharingEntryRow(removeLocation = on, onClick = {}) }
    }
  }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w412dp-h915dp-xhdpi")
class SharingPhoneLayoutTest : SharingLayoutBase()

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w1280dp-h800dp-hdpi")
class SharingTabletLayoutTest : SharingLayoutBase()

// --- Gli screenshot (docs/screenshots/5-P12/) -----------------------------------------------------------------------------------------------

/**
 * La pagina spenta e accesa e la riga nelle Impostazioni: chiaro e scuro, telefono e tablet, piu' il testo grande.
 * `gradlew :feature-settings:recordRoborazziDebug --tests "*SharingShots*"`.
 */
abstract class SharingShots(private val device: String) : SharingComposeBase() {

  private fun shoot(name: String, dark: Boolean, scale: Float? = null, content: @Composable () -> Unit) {
    show(dark = dark, scale = scale, content = content)
    val suffix = if (scale != null) "-testo-grande" else ""
    rule.onRoot().captureRoboImage("../docs/screenshots/5-P12/impostazioni-$name-${if (dark) "scuro" else "chiaro"}-$device$suffix.png")
  }

  @Composable
  private fun EntryRows() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
      FluidListGroup {
        SharingEntryRow(removeLocation = false, onClick = {})
        FluidListDivider()
        SharingEntryRow(removeLocation = true, onClick = {})
      }
    }
  }

  @Test
  fun spentaChiaro() = shoot("condivisioni-spenta", dark = false) { SharingScreen(removeLocation = false, onBack = {}, onToggle = {}) }

  @Test
  fun spentaScuro() = shoot("condivisioni-spenta", dark = true) { SharingScreen(removeLocation = false, onBack = {}, onToggle = {}) }

  @Test
  fun accesaChiaro() = shoot("condivisioni-accesa", dark = false) { SharingScreen(removeLocation = true, onBack = {}, onToggle = {}) }

  @Test
  fun accesaScuro() = shoot("condivisioni-accesa", dark = true) { SharingScreen(removeLocation = true, onBack = {}, onToggle = {}) }

  @Test
  fun accesaChiaroTestoGrande() = shoot("condivisioni-accesa", dark = false, scale = 1.3f) { SharingScreen(removeLocation = true, onBack = {}, onToggle = {}) }

  @Test
  fun voceChiaro() = shoot("voce-condivisioni", dark = false) { EntryRows() }

  @Test
  fun voceScuro() = shoot("voce-condivisioni", dark = true) { EntryRows() }

  @Test
  fun voceChiaroTestoGrande() = shoot("voce-condivisioni", dark = false, scale = 1.3f) { EntryRows() }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w412dp-h915dp-xhdpi")
class SharingPhoneShots : SharingShots("telefono")

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w1280dp-h800dp-hdpi")
class SharingTabletShots : SharingShots("tablet")
