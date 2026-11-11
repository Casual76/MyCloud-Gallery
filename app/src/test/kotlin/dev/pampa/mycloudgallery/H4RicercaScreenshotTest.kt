package dev.pampa.mycloudgallery

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.search.contract.HistoryEntry
import dev.pampa.mycloudgallery.core.search.contract.PlaceLevel
import dev.pampa.mycloudgallery.core.search.contract.PlaceOption
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.feature.search.FixedSearchSource
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot della ricerca dentro la Libreria (H4 della fase H, 11/11: «stile ricerca in app Apple»), per l'utente: la barra aperta a campo vuoto con i
 * tre suggerimenti in alto e la Libreria dietro; mentre si scrive, con i risultati per pertinenza al posto della griglia (i piu' adatti subito sopra la
 * barra, i meno probabili in coda); nessun risultato (una riga sola sopra la barra); aperta da un'altra scheda; "Trova simili"; i risultati in selezione
 * (la barra delle azioni della Libreria al posto della barra di ricerca). Chiaro e scuro, il telefono dell'utente (360 dp) e il tablet; sul telefono anche
 * i suggerimenti lunghi con il testo a 1,3x (si accorciano con i puntini, non vanno a capo). Tutto dallo scaffold delle prove: le schede vere su stati dati.
 *
 * La tastiera non c'e' (Robolectric): la barra sta nel posto della pillola. La salita con la tastiera si giudica sul telefono.
 *
 * `gradlew :app:recordRoborazziDebug --tests dev.pampa.mycloudgallery.H4RicercaPhoneScreenshotTest` (e `...TabletScreenshotTest`) scrive i PNG in
 * `docs/screenshots/beta-grafica/H4/`.
 */
internal abstract class H4RicercaScreenshotBase(private val dark: Boolean, private val device: String) {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private class RegisterComponentActivity : TestWatcher() {
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

  private val look: String get() = if (dark) "scuro" else "chiaro"

  /** [afterShow] porta la schermata nello stato da fotografare; poi si lascia assestare ogni animazione (la barra che si apre, le dissolvenze) e si scatta. */
  protected fun shoot(name: String, fontScale: Float? = null, afterShow: () -> Unit = {}, content: @Composable () -> Unit) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) { content() }
      }
    }
    composeRule.waitForIdle()
    afterShow()
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SETTLE_MS)
    composeRule.waitForIdle()
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device$suffix.png")
  }

  /** Aperta dal cerchio, a campo vuoto: dietro la Libreria com'era, in alto i tre suggerimenti (una ricerca recente, un luogo, un tipo). */
  @Test
  fun apertaACampoVuoto() {
    val scenes = ScaffoldScenes()
    shoot("aperta-campo-vuoto") { GalleryScaffold(scenes, GalleryTab.Library, searchOpen = true, search = scenes.searchIdle) }
  }

  /** Mentre si scrive «mare»: i tre completamenti in alto, i risultati per pertinenza al posto della griglia, il separatore e i meno probabili in coda. */
  @Test
  fun scrivendoConIRisultati() {
    val scenes = ScaffoldScenes()
    shoot("scrivendo-risultati") { GalleryScaffold(scenes, GalleryTab.Library, searchOpen = true, search = scenes.searchTyping) }
  }

  /** Nessun risultato: la griglia vuota e una riga sola sopra la barra; senza completamenti la barra in alto si e' ritirata. */
  @Test
  fun nessunRisultato() {
    val scenes = ScaffoldScenes()
    shoot("nessun-risultato") { GalleryScaffold(scenes, GalleryTab.Library, searchOpen = true, search = scenes.searchNothing) }
  }

  /** Aperta dalla scheda Album: dietro c'e' la Libreria come base dei risultati (chiudendo si torna agli Album). */
  @Test
  fun daUnAltraScheda() {
    val scenes = ScaffoldScenes()
    shoot("da-album-scrivendo") { GalleryScaffold(scenes, GalleryTab.Albums, searchOpen = true, search = scenes.searchTyping) }
  }

  /** "Trova simili" da una foto: il campo vuoto, i simili per pertinenza, la riga che dice da dove vengono. */
  @Test
  fun trovaSimili() {
    val scenes = ScaffoldScenes()
    shoot("trova-simili") { GalleryScaffold(scenes, GalleryTab.Library, searchOpen = true, search = scenes.searchSimilar) }
  }

  /** I risultati in selezione: la barra delle azioni della Libreria al posto della barra di ricerca, in alto quanti sono e "Fine". */
  @Test
  fun risultatiInSelezione() {
    val scenes = ScaffoldScenes()
    val state = scenes.library
    val results = scenes.searchTyping.state as SearchScreenState.Results
    shoot(
      "risultati-selezione",
      afterShow = {
        composeRule.runOnIdle {
          state.grid.startSelecting()
          val view = results.confident
          state.grid.selection.selectAll((0 until 3).map { view.snapshot.idAt(view.snapshotIndex(it)) })
        }
      },
    ) { GalleryScaffold(scenes, GalleryTab.Library, searchOpen = true, search = scenes.searchTyping) }
  }

  companion object {
    const val DIR = "../docs/screenshots/beta-grafica/H4"

    /** Piu' di ogni animazione (la barra che si apre, le dissolvenze dei pezzi in alto e dei risultati). */
    const val SETTLE_MS = 2_000L

    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE)
internal class H4RicercaPhoneScreenshotTest(dark: Boolean) : H4RicercaScreenshotBase(dark, "telefono") {

  /** A 360 dp con il testo a 1,3x i tre suggerimenti lunghi si accorciano con i puntini, senza andare a capo (solo in chiaro). */
  @Test
  fun suggerimentiLunghiTestoGrande() {
    if (dark) return
    val scenes = ScaffoldScenes()
    val long = FixedSearchSource(
      query = "compleanno",
      suggestions = listOf(
        Suggestion.Recent(HistoryEntry(1, "compleanno della nonna a Firenze", emptyList())),
        Suggestion.PlaceSuggestion(PlaceOption(setOf(7), "Castiglione della Pescaia", PlaceLevel.PLACE)),
        Suggestion.Recent(HistoryEntry(2, "compleanno di Anna al mare", emptyList())),
      ),
      state = scenes.searchTyping.state,
    )
    shoot("suggerimenti-lunghi", fontScale = LARGE_TEXT) { GalleryScaffold(scenes, GalleryTab.Library, searchOpen = true, search = long) }
  }

  private val dark: Boolean = dark

  companion object {
    private const val LARGE_TEXT = 1.3f

    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_TABLET)
internal class H4RicercaTabletScreenshotTest(dark: Boolean) : H4RicercaScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
