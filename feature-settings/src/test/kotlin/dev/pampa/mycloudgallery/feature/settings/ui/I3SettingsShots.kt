package dev.pampa.mycloudgallery.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.PresetCatalog
import dev.pampa.mycloudgallery.feature.settings.quality.PreviewEstimate
import dev.pampa.mycloudgallery.feature.settings.quality.PreviewLevel
import dev.pampa.mycloudgallery.feature.settings.quality.PreviewQualityScreen
import dev.pampa.mycloudgallery.feature.settings.quality.PreviewQualityState
import dev.pampa.mycloudgallery.feature.settings.rules.CapChoice
import dev.pampa.mycloudgallery.feature.settings.rules.RulesActions
import dev.pampa.mycloudgallery.feature.settings.rules.RulesScreen
import dev.pampa.mycloudgallery.feature.settings.rules.RulesUiState
import dev.pampa.mycloudgallery.feature.settings.sharing.SharingScreen
import dev.pampa.mycloudgallery.feature.settings.widget.WidgetContent
import dev.pampa.mycloudgallery.feature.settings.widget.WidgetRefresh
import dev.pampa.mycloudgallery.feature.settings.widget.WidgetSettingsScreen
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * I3 (fase I, «l'app parla troppo»): tutte le pagine delle Impostazioni **prima e dopo** il ridisegno, per l'utente. La pagina principale com'e'
 * in produzione (tutte le voci collegate, come in H5) e le sottopagine di questo modulo, intere (360 x 2000 dp: quanto testo c'e' lo si vede
 * solo tutto insieme); la principale anche al primo schermo (360 x 780) e a 1,3x, la principale e la rete sul tablet. Chiaro e scuro.
 *
 * [STAGE] e' `prima` sul codice di partenza (f7d91f7f) e `dopo` sul codice di I3; i PNG vanno in `docs/screenshots/beta-grafica/I3/` e, accanto, in
 * `build/i3-parole/`, quante parole si vedono (per `docs/I3-prima-dopo.md`).
 */
internal abstract class I3SettingsShotsBase(
  private val page: I3SettingsPage,
  private val dark: Boolean,
  private val device: String,
  private val fontScale: Float = 1f,
) {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun capture() {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
          SettingsHost { page.content() }
        }
      }
    }
    composeRule.waitForIdle()
    val look = if (dark) "scuro" else "chiaro"
    val name = "impostazioni-${page.slug}-$STAGE-$look-$device"
    composeRule.onRoot().captureRoboImage("$DIR/$name.png")
    I3SettingsWords.write(name, composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
  }

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/I3"
    // Giro 1 della fase I: `giro1` (i `dopo` di I3 sono il «prima» del giro).
    const val STAGE = "giro1"

    fun data(pages: List<I3SettingsPage>): List<Array<Any>> = pages.flatMap { listOf(arrayOf<Any>(it, false), arrayOf<Any>(it, true)) }
  }
}

/** Le parole visibili di una schermata (i testi dei nodi, non le descrizioni per TalkBack), in `build/i3-parole/<nome>.txt`. */
internal object I3SettingsWords {
  fun count(node: SemanticsNode): Int {
    val own = node.config.getOrNull(SemanticsProperties.Text).orEmpty().sumOf { text -> words(text.text) }
    return own + node.children.sumOf { count(it) }
  }

  private fun words(text: String): Int = text.split(Regex("\\s+")).count { token -> token.any { it.isLetterOrDigit() } }

  fun write(name: String, root: SemanticsNode) {
    val dir = File("build/i3-parole").apply { mkdirs() }
    File(dir, "$name.txt").writeText(count(root).toString())
  }
}

/** Una pagina da fotografare: [slug] e' il nome nel file. */
internal class I3SettingsPage(val slug: String, val content: @Composable () -> Unit) {
  override fun toString(): String = slug

  companion object {
    private fun shot(name: String): @Composable () -> Unit = ShotCatalog.all.first { it.name == name }.content

    private const val MB = 1_000_000L

    val home = I3SettingsPage("principale") { H5Page.home.content() }
    val network = I3SettingsPage("rete") { shot("rete-casa")() }
    val security = I3SettingsPage("sicurezza") { H5Page.security.content() }
    val backupNotice = I3SettingsPage("copia-esterna") { H5Page.backupNotice.content() }
    val rules = I3SettingsPage("regole") {
      RulesScreen(
        state = RulesUiState(
          preset = Preset.BALANCED,
          cap = CapChoice.FromPreset,
          presetCapBytes = PresetCatalog.DEFAULT_MOBILE_CAP_BYTES,
          allowRoaming = false,
          pauseOnPowerSaver = true,
        ),
        actions = RulesActions.None,
      )
    }
    val quality = I3SettingsPage("qualita") {
      PreviewQualityScreen(
        state = PreviewQualityState(
          level = PreviewLevel.STANDARD,
          estimates = mapOf(
            PreviewLevel.SAVER to PreviewEstimate(100L * MB, 13_000),
            PreviewLevel.STANDARD to PreviewEstimate(380L * MB, 13_000),
            PreviewLevel.HIGH to PreviewEstimate(5_600L * MB, 13_000),
          ),
          currentBytes = 120L * MB,
          converging = false,
          showAlbumCovers = true,
        ),
        onBack = {}, onChoose = {}, onShowCovers = {},
      )
    }
    val sharing = I3SettingsPage("condivisione") { SharingScreen(removeLocation = true, onBack = {}, onToggle = {}) }
    val widget = I3SettingsPage("widget") {
      WidgetSettingsScreen(content = WidgetContent.DEFAULT, period = WidgetRefresh.DEFAULT, onBack = {}, onContent = {}, onPeriod = {})
    }
    val about = I3SettingsPage("informazioni") { shot("informazioni-licenze")() }
    val account = I3SettingsPage("account") { shot("account-collegato")() }
    val family = I3SettingsPage("famiglia") { shot("famiglia-admin")() }
    val devices = I3SettingsPage("dispositivi") { shot("dispositivi-admin")() }

    val all: List<I3SettingsPage> = listOf(home, network, security, backupNotice, rules, quality, sharing, widget, about, account, family, devices)
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w360dp-h2000dp-xhdpi")
internal class I3SettingsTallShots(page: I3SettingsPage, dark: Boolean) : I3SettingsShotsBase(page, dark, "telefono-intera") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1}")
    fun data(): List<Array<Any>> = data(I3SettingsPage.all)
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w360dp-h780dp-xhdpi")
internal class I3SettingsPhoneShots(page: I3SettingsPage, dark: Boolean) : I3SettingsShotsBase(page, dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1}")
    fun data(): List<Array<Any>> = data(listOf(I3SettingsPage.home, I3SettingsPage.security))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w360dp-h780dp-xhdpi")
internal class I3SettingsLargeTextShots(page: I3SettingsPage, dark: Boolean) :
  I3SettingsShotsBase(page, dark, "telefono-testo-grande", fontScale = 1.3f) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1}")
    fun data(): List<Array<Any>> = listOf(arrayOf<Any>(I3SettingsPage.home, false), arrayOf<Any>(I3SettingsPage.network, true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w1280dp-h800dp-hdpi")
internal class I3SettingsTabletShots(page: I3SettingsPage, dark: Boolean) : I3SettingsShotsBase(page, dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0} scuro={1}")
    fun data(): List<Array<Any>> = data(listOf(I3SettingsPage.home, I3SettingsPage.network))
  }
}
