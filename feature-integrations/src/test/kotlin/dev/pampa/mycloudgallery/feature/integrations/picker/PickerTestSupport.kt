package dev.pampa.mycloudgallery.feature.integrations.picker

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.library.testing.NasMix
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.feature.integrations.provider.testing.FakeIntegrationLibrary
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.robolectric.Shadows.shadowOf

// Fase 5B (P10): il supporto dei test Compose del selettore (stesso schema dei moduli di P5): telefono da 412 dp, tablet da 1280 dp, testo a 1,3x.

const val PHONE_QUALIFIERS = "w412dp-h915dp-xhdpi"

/** Il telefono degli screenshot per l'utente (K4): il suo Galaxy S25, 360 x 780 dp. Non 412 dp, dove entrano piu' cose che sul suo schermo. */
const val SHOT_PHONE_QUALIFIERS = "w360dp-h780dp-xhdpi"
const val TABLET_QUALIFIERS = "w1280dp-h800dp-land-xhdpi"
const val LARGE_TEXT = 1.3f

/** Registra a mano l'activity vuota di Compose (non e' nel manifest di un modulo libreria). */
class RegisterComposeActivity : TestWatcher() {
  override fun starting(description: Description) {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val info = ActivityInfo().apply {
      name = ComponentActivity::class.java.name
      packageName = app.packageName
      flags = flags or ActivityInfo.FLAG_HARDWARE_ACCELERATED
    }
    shadowOf(app.packageManager).addOrUpdateActivity(info)
  }
}

abstract class PickerComposeBase {

  val composeRule: AndroidComposeTestRule<*, ComponentActivity> = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComposeActivity()).around(composeRule)

  protected val fontScale: MutableState<Float> = mutableStateOf(1f)

  /** Mostra [content] nel tema della galleria e aspetta che tutto sia assestato. `setContent` si chiama una volta per prova. */
  protected fun show(dark: Boolean = false, fontScale: Float? = null, content: @Composable () -> Unit) {
    this.fontScale.value = fontScale ?: 1f
    composeRule.setContent {
      val settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
      MyCloudGalleryTheme(settings = settings) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, this@PickerComposeBase.fontScale.value)) {
          Box(Modifier.fillMaxSize()) { content() }
        }
      }
    }
    composeRule.waitForIdle()
  }

  protected fun setFontScale(value: Float) {
    composeRule.runOnIdle { fontScale.value = value }
    composeRule.waitForIdle()
  }

  protected fun clickable(): List<SemanticsNode> =
    composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
      .filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }

  protected fun SemanticsNode.name(): String =
    config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(", ").orEmpty().ifEmpty {
      config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
    }.ifEmpty { children.joinToString(" ") { it.name() }.trim() }
}

/** I campioni del selettore: una piccola Libreria mista (telefono e solo NAS), la richiesta e lo stato. */
object PickerSamples {

  /** Dodici celle: stanno tutte in una pagina alta, cosi' i test dei bordi vedono ogni cella. */
  fun library(count: Int = 12): FakeIntegrationLibrary =
    FakeIntegrationLibrary(SyntheticLibrary(seed = 21, count = count, mix = NasMix(nasOnlyPercent = 40, bothPercentOfPhone = 25)))

  fun request(multiple: Boolean = true) = PickerRequest(
    action = PickerAction.GET_CONTENT,
    images = true,
    videos = true,
    allowMultiple = multiple,
    localOnly = false,
    mimeFilters = listOf("*/*"),
  )

  fun env(library: FakeIntegrationLibrary): PickerEnv =
    PickerEnv(library.thumbnails, library.placeholders) { id -> library.items.refOrNull(id) }

  fun grid(library: FakeIntegrationLibrary, request: PickerRequest, selected: Int = 0, notice: PickerNotice? = null): PickerUi {
    val items = PickerItems.of(library.items.view.value.snapshot, request)
    val chosen = (0 until selected).map { items.idAt(it * 2 + 1) }.toSet()
    return PickerUi(PickerScreenState.GRID, items, chosen, notice)
  }
}
