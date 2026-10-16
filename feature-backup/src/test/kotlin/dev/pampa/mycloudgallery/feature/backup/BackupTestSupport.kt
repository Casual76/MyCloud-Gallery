package dev.pampa.mycloudgallery.feature.backup

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
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Il telefono (412 dp) e il tablet (1280 dp, orizzontale) degli screenshot e dei test di layout. */
const val PHONE_QUALIFIERS = "w412dp-h915dp-xhdpi"
const val TABLET_QUALIFIERS = "w1280dp-h800dp-land-xhdpi"

/** Il testo ingrandito dei test (come nella Libreria). */
const val LARGE_TEXT = 1.3f

/** Registra a mano l'activity vuota di Compose (non e' nel manifest di un modulo libreria). */
class RegisterComponentActivity : TestWatcher() {
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

/**
 * La base dei test Compose di questo modulo (Robolectric, grafica nativa, telefono da 412 dp): mostra un contenuto nel tema della galleria
 * dentro un host dei fogli (senza non si aprono) e un fondale di vetro. `setContent` si chiama **una volta** per prova: gli stati cambiano
 * dagli stati che il contenuto legge, non rimostrando la scena.
 */
abstract class BackupComposeBase {

  val composeRule: AndroidComposeTestRule<*, ComponentActivity> = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  /** Il testo ingrandito di adesso: un test lo cambia con [setFontScale] senza rimostrare la scena. */
  protected val fontScale: MutableState<Float> = mutableStateOf(1f)

  /** Mostra [content] nel tema, con un host dei fogli in scena, e aspetta che tutto sia assestato. */
  protected fun show(dark: Boolean = false, fontScale: Float? = null, content: @Composable () -> Unit) {
    this.fontScale.value = fontScale ?: 1f
    composeRule.setContent {
      val settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
      MyCloudGalleryTheme(settings = settings) {
        val density = LocalDensity.current
        val host = rememberFluidGlassModalHostState()
        val backdrop = rememberGlassBackdrop()
        CompositionLocalProvider(
          LocalFluidGlassModalHostState provides host,
          LocalDensity provides Density(density.density, this@BackupComposeBase.fontScale.value),
        ) {
          Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().glassBackdropSource(backdrop)) { content() }
            FluidGlassModalHost(state = host, backdrop = backdrop)
          }
        }
      }
    }
    composeRule.waitForIdle()
  }

  /** Cambia il testo ingrandito della scena gia' mostrata. */
  protected fun setFontScale(value: Float) {
    composeRule.runOnIdle { fontScale.value = value }
    composeRule.waitForIdle()
  }

  /** Ogni nodo toccabile sullo schermo (con un'azione di tocco e dimensioni). */
  protected fun clickable(): List<SemanticsNode> =
    composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
      .filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }

  /**
   * Il nome che TalkBack legge per un nodo: la descrizione, o il testo, o (per una riga di Fluid, che e' un contenitore toccabile con il
   * suo testo in un nodo figlio) quello dei figli.
   */
  protected fun SemanticsNode.name(): String =
    config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(", ").orEmpty().ifEmpty {
      config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
    }.ifEmpty { children.joinToString(" ") { it.name() }.trim() }
}

/** La base dei test Compose a un solo dispositivo (il telefono da 412 dp). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_QUALIFIERS)
abstract class ComposeBackupTest : BackupComposeBase()

/** Lo stesso sul tablet (1280 dp, orizzontale). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
abstract class ComposeBackupTabletTest : BackupComposeBase()

/** Un telefono alto: i test dei bordi vedono tutta la pagina (una riga mezza fuori da una lista che scorre non e' un controllo storto). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h2600dp-xhdpi")
abstract class ComposeBackupTallTest : BackupComposeBase()

/** Un tablet alto, per lo stesso motivo. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w1280dp-h1600dp-xhdpi")
abstract class ComposeBackupTallTabletTest : BackupComposeBase()
