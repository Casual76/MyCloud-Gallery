package dev.pampa.mycloudgallery.feature.settings.widget

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.widget.contract.WidgetChoice
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPeriod
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.SettingsLinks
import dev.pampa.mycloudgallery.feature.settings.SettingsScreen
import dev.pampa.mycloudgallery.feature.settings.SettingsUiState
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Impostazioni -> Widget (Fase 10, P3, D3, D6; Q1 (a), Q2 (a), Q3 (c), Q4 (a)): le due scelte (cosa mostra, ogni quanto cambia), che si scelgono con un tocco e valgono una volta;
 * **nessuna scelta per il blocco dell'app e nessuna per la schermata di blocco** (sono regole, dette in chiaro); i valori di serie sono le risposte dell'utente; i codici sono quelli di
 * `core-widget`; la riga nella pagina principale. `gradlew :feature-settings:recordRoborazziDebug --tests "*WidgetSettingsScreenTest*"` scrive gli screenshot in `docs/screenshots/10-P3/`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "it-w412dp-h1800dp-xhdpi")
class WidgetSettingsScreenTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val fontScale = mutableFloatStateOf(1f)

  private fun show(dark: Boolean = false, scale: Float = 1f, content: @Composable () -> Unit) {
    fontScale.floatValue = scale
    rule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.floatValue)) { content() }
      }
    }
    rule.waitForIdle()
  }

  private fun string(id: Int): String = rule.activity.getString(id)

  private fun has(text: String): Boolean = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

  private fun page(content: WidgetContent = WidgetContent.DEFAULT, period: WidgetRefresh = WidgetRefresh.DEFAULT, onContent: (WidgetContent) -> Unit = {}, onPeriod: (WidgetRefresh) -> Unit = {}) =
    show { WidgetSettingsScreen(content = content, period = period, onBack = {}, onContent = onContent, onPeriod = onPeriod) }

  private fun scrollTo(text: String) {
    rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text, substring = true))
    rule.waitForIdle()
  }

  @Test
  fun iValoriDiSerieSonoLeRisposteDellUtente() {
    // Q1 (a): il ricordo del giorno quando c'e', altrimenti foto a caso. Q2 (a): circa ogni 3 ore.
    assertThat(WidgetContent.DEFAULT).isEqualTo(WidgetContent.MEMORY_THEN_PHOTOS)
    assertThat(WidgetRefresh.DEFAULT).isEqualTo(WidgetRefresh.HOURS_3)
    assertThat(WidgetContent.ofCode(null)).isEqualTo(WidgetContent.DEFAULT)
    assertThat(WidgetContent.ofCode("rovinato")).isEqualTo(WidgetContent.DEFAULT)
    assertThat(WidgetRefresh.ofCode("rovinato")).isEqualTo(WidgetRefresh.DEFAULT)
  }

  /** I tipi di questa pagina non vedono `core-widget` in produzione: qui si prova che i **codici** e le ore coincidono (altrimenti `:app` leggerebbe la scelta sbagliata). */
  @Test
  fun iCodiciSonoQuelliDiCoreWidget() {
    assertThat(WidgetContent.entries.map { it.code }).containsExactlyElementsIn(WidgetChoice.entries.map { it.code }).inOrder()
    assertThat(WidgetRefresh.entries.map { it.code }).containsExactlyElementsIn(WidgetPeriod.entries.map { it.code }).inOrder()
    assertThat(WidgetRefresh.entries.map { it.hours }).containsExactlyElementsIn(WidgetPeriod.entries.map { it.hours }).inOrder()
    assertThat(WidgetContent.DEFAULT.code).isEqualTo(WidgetChoice.DEFAULT.code)
    assertThat(WidgetRefresh.DEFAULT.code).isEqualTo(WidgetPeriod.DEFAULT.code)
  }

  @Test
  fun leScelteSiSalvanoESiRitrovano() = runBlocking<Unit> {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val first = SharedPrefsWidgetSettings(context)
    assertThat(first.content.value).isEqualTo(WidgetContent.MEMORY_THEN_PHOTOS)
    assertThat(first.period.value).isEqualTo(WidgetRefresh.HOURS_3)
    first.setContent(WidgetContent.MEMORIES_ONLY)
    first.setPeriod(WidgetRefresh.DAILY)
    // Un'istanza nuova (il processo che riparte) trova le scelte; un valore rovinato nel file vale quello di serie.
    val second = SharedPrefsWidgetSettings(context)
    assertThat(second.content.value).isEqualTo(WidgetContent.MEMORIES_ONLY)
    assertThat(second.period.value).isEqualTo(WidgetRefresh.DAILY)
    context.getSharedPreferences(SharedPrefsWidgetSettings.FILE, Context.MODE_PRIVATE).edit().putString("content", "???").putString("period", "???").commit()
    val third = SharedPrefsWidgetSettings(context)
    assertThat(third.content.value).isEqualTo(WidgetContent.DEFAULT)
    assertThat(third.period.value).isEqualTo(WidgetRefresh.DEFAULT)
    // Il file e' privato e porta solo due codici: nessun dato personale.
    val stored = context.getSharedPreferences(SharedPrefsWidgetSettings.FILE, Context.MODE_PRIVATE).all
    assertThat(stored.keys).containsExactly("content", "period")
  }

  @Test
  fun laPaginaMostraLeDueScelteELaSceltaAttivaEQuellaGiusta() {
    page(WidgetContent.PHOTOS_ONLY, WidgetRefresh.HOURS_6)
    for (id in listOf(R.string.widget_content_memory_then_photos, R.string.widget_content_photos_only, R.string.widget_content_memories_only)) assertThat(has(string(id))).isTrue()
    for (id in listOf(R.string.widget_period_hours_3, R.string.widget_period_hours_6, R.string.widget_period_daily)) assertThat(has(string(id))).isTrue()
    val selected = rule.onAllNodes(SemanticsMatcher("scelta attiva") { it.config.getOrNull(SemanticsProperties.Selected) == true && it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton })
      .fetchSemanticsNodes()
    assertThat(selected).hasSize(2)
    val names = selected.map { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ") }
    assertThat(names.any { it.contains(string(R.string.widget_content_photos_only)) }).isTrue()
    assertThat(names.any { it.contains(string(R.string.widget_period_hours_6)) }).isTrue()
  }

  @Test
  fun siSceglieConUnTocco_UnaVoltaSola_ELaSceltaGiaAttivaNonFaNiente() {
    val contents = mutableListOf<WidgetContent>()
    val periods = mutableListOf<WidgetRefresh>()
    page(WidgetContent.MEMORY_THEN_PHOTOS, WidgetRefresh.HOURS_3, { contents += it }, { periods += it })
    rule.onNodeWithText(string(R.string.widget_content_photos_only)).performClick()
    rule.onNodeWithText(string(R.string.widget_content_memory_then_photos)).performClick()
    rule.onNodeWithText(string(R.string.widget_period_daily)).performClick()
    rule.onNodeWithText(string(R.string.widget_period_hours_3)).performClick()
    rule.waitForIdle()
    assertThat(contents).containsExactly(WidgetContent.PHOTOS_ONLY)
    assertThat(periods).containsExactly(WidgetRefresh.DAILY)
  }

  /** Q3 (c) e Q4 (a): per il blocco dell'app e per la schermata di blocco non c'e' nessuna scelta, solo la regola detta in chiaro. */
  @Test
  fun perIlBloccoEPerLaSchermataDiBloccoCeSoloLaRegola() {
    page()
    val allChoices = rule.onAllNodes(SemanticsMatcher("scelta") { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }).fetchSemanticsNodes()
    // Sei scelte in tutto (tre contenuti e tre frequenze): nessun interruttore e nessuna scelta per il blocco.
    assertThat(allChoices).hasSize(6)
    assertThat(rule.onAllNodes(SemanticsMatcher("interruttore") { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch }).fetchSemanticsNodes()).isEmpty()
    scrollTo(string(R.string.widget_privacy_home))
    assertThat(has(string(R.string.widget_privacy_lock))).isTrue()
    assertThat(has(string(R.string.widget_privacy_hidden))).isTrue()
    assertThat(string(R.string.widget_privacy_lock)).contains("solo foto che sono anche sul telefono")
    assertThat(string(R.string.widget_privacy_home)).contains("mai sulla schermata di blocco")
    assertThat(string(R.string.widget_period_footnote)).contains("Mai di notte")
  }

  @Test
  fun leFrasiSonoSempliciESenzaGergo() {
    val all = R.string::class.java.fields.filter { it.name.startsWith("widget_") }.map { string(it.getInt(null)) }
    assertThat(all).isNotEmpty()
    for (text in all) {
      assertThat(text).isNotEmpty()
      for (word in listOf("WorkManager", "Glance", "RemoteViews", "launcher", "provider", "bitmap", "cache", "thumbnail", "API")) {
        assertWithMessage("\"$text\" nomina $word").that(text.contains(word, ignoreCase = true)).isFalse()
      }
    }
  }

  // --- La riga nella pagina principale -------------------------------------------------------------------------------------------------

  private val settingsState = SettingsUiState(TrustStatus.Approved(admin = false), deviceCount = 3, pendingRequests = 0, pendingSteps = emptyList())

  private fun settings(links: SettingsLinks, summary: WidgetEntrySummary = WidgetEntrySummary(WidgetContent.DEFAULT, WidgetRefresh.DEFAULT)) {
    show { SettingsScreen(state = settingsState, onBack = {}, openDiagnostics = {}, resumeStep = {}, links = links, widgetSummary = summary) }
  }

  @Test
  fun senzaLaCallbackLaVoceNonCe() {
    settings(SettingsLinks())
    assertThat(has(string(R.string.widget_title))).isFalse()
  }

  @Test
  fun laVoceDiceLeDueSceltePerEApreLaPaginaUnaVolta() {
    var opened = 0
    val summary = mutableStateOf(WidgetEntrySummary(WidgetContent.DEFAULT, WidgetRefresh.DEFAULT))
    show { SettingsScreen(state = settingsState, onBack = {}, openDiagnostics = {}, resumeStep = {}, links = SettingsLinks(openWidget = { opened++ }), widgetSummary = summary.value) }
    val first = "Il ricordo del giorno o foto a caso, circa ogni 3 ore"
    scrollTo(first)
    assertThat(has(first)).isTrue()
    rule.onNodeWithText(first).performClick()
    assertThat(opened).isEqualTo(1)
    rule.runOnIdle { summary.value = WidgetEntrySummary(WidgetContent.MEMORIES_ONLY, WidgetRefresh.DAILY) }
    rule.waitForIdle()
    assertThat(has("Solo ricordi, una volta al giorno")).isTrue()
    assertThat(has(first)).isFalse()
  }

  // --- Gli screenshot ------------------------------------------------------------------------------------------------------------------

  private fun shoot(name: String, dark: Boolean = false, scale: Float = 1f) {
    show(dark = dark, scale = scale) { WidgetSettingsScreen(content = WidgetContent.MEMORY_THEN_PHOTOS, period = WidgetRefresh.HOURS_3, onBack = {}, onContent = {}, onPeriod = {}) }
    rule.onRoot().captureRoboImage("$DIR/$name.png")
  }

  @Test
  fun screenshotChiaro() = shoot("impostazioni-widget-chiaro")

  @Test
  fun screenshotScuro() = shoot("impostazioni-widget-scuro", dark = true)

  @Test
  fun screenshotTestoUnVirgolaTre() = shoot("impostazioni-widget-testo-1-3x", scale = 1.3f)

  private companion object {
    const val DIR = "../docs/screenshots/10-P3"
  }
}
