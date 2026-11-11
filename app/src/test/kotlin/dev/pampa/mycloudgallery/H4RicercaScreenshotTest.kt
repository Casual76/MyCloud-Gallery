package dev.pampa.mycloudgallery

import android.app.Application
import android.content.pm.ActivityInfo
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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
 * Gli screenshot della ricerca dentro la Libreria (H4 della fase H, 11/11: «stile ricerca in app Apple»; giro di correzione 1), per l'utente: la barra
 * aperta a campo vuoto con i tre suggerimenti in alto e la Libreria dietro; la stessa con la tastiera su e la Libreria salita con la barra; mentre si
 * scrive, con i risultati per pertinenza al posto della griglia (i piu' adatti subito sopra la barra, i meno probabili in cima con la loro intestazione
 * sopra); nessun risultato (la riga sopra la barra e la Libreria attenuata dietro); l'apertura a meta' da un'altra scheda; "Trova simili"; i risultati in
 * selezione (la barra delle azioni della Libreria al posto della barra di ricerca). Chiaro e scuro, il telefono dell'utente (360 dp) e il tablet; sul
 * telefono anche i suggerimenti lunghi, distinguibili, a misura normale e con il testo a 1,3x. Tutto dallo scaffold delle prove: le schede vere su stati
 * dati.
 *
 * La tastiera vera non c'e' (Robolectric): nello screenshot con la tastiera il suo posto e' un blocco neutro alto quanto l'inset.
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

  /**
   * [afterShow] porta la schermata nello stato da fotografare; poi si lascia assestare ogni animazione (la barra che si apre, le dissolvenze) e si scatta.
   * Con [settle] falso si scatta subito: un fotogramma a meta' di un'animazione, fermato da [afterShow].
   */
  protected fun shoot(name: String, fontScale: Float? = null, settle: Boolean = true, afterShow: () -> Unit = {}, content: @Composable () -> Unit) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) { content() }
      }
    }
    composeRule.waitForIdle()
    afterShow()
    if (settle) {
      composeRule.waitForIdle()
      composeRule.mainClock.advanceTimeBy(SETTLE_MS)
      composeRule.waitForIdle()
    }
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

  /**
   * Nessun risultato (CU-11 del giro 1, CS-12 e CS-23 del giro 2): una riga sola sopra la barra, dietro la Libreria attenuata da un velo (non uno schermo
   * vuoto) senza il riepilogo in fondo, e in alto le ultime ricerche da cui ripartire.
   */
  @Test
  fun nessunRisultato() {
    val scenes = ScaffoldScenes()
    shoot("nessun-risultato") { GalleryScaffold(scenes, GalleryTab.Library, searchOpen = true, search = scenes.searchNothing) }
  }

  /**
   * Aperta dalla scheda Album, **a meta' apertura** (CG-2 del giro 1): dietro c'e' gia' la Libreria, la pillola si raccoglie verso il cerchio e la barra si
   * allarga dal cerchio. Il vetro della barra ha la sua misura finale: si vede la parte rivelata, con il capo sinistro vero (bordo e ombra) e il capo destro
   * ritagliato tondo. (Prima: `da-album-scrivendo`, uguale a `scrivendo-risultati` perche' i risultati coprono la Libreria.)
   */
  @Test
  fun daUnAltraSchedaAMetaApertura() {
    val scenes = ScaffoldScenes()
    var open by mutableStateOf(false)
    shoot(
      "apertura-a-meta-da-album",
      settle = false,
      afterShow = {
        composeRule.mainClock.autoAdvance = false
        // Con l'orologio fermo la scrittura va notificata subito (uno snapshot che si applica), se no la composizione non la vede.
        Snapshot.withMutableSnapshot { open = true }
        composeRule.mainClock.advanceTimeBy(HALF_OPEN_MS)
      },
    ) { GalleryScaffold(scenes, GalleryTab.Albums, searchOpen = open, search = scenes.searchIdle) }
  }

  /**
   * A campo vuoto con la tastiera su (CU-4 del giro 1): la barra sta sopra la tastiera e **la Libreria e' salita con lei**, le foto piu' recenti restano
   * visibili. Robolectric non disegna la tastiera: al suo posto un blocco neutro della sua altezza, disegnato dalla prova.
   */
  @Test
  fun aCampoVuotoConLaTastiera() {
    val scenes = ScaffoldScenes()
    shoot("aperta-tastiera-libreria-alzata", afterShow = { composeRule.runOnIdle { openKeyboard(KEYBOARD_FRACTION) } }) {
      val view = LocalView.current
      SideEffect { composeView = view }
      Box(Modifier.fillMaxSize()) {
        GalleryScaffold(scenes, GalleryTab.Library, searchOpen = true, search = scenes.searchIdle)
        KeyboardStandIn(Modifier.align(Alignment.BottomCenter))
      }
    }
  }

  /** La vista di Compose della scena (gli inset della tastiera si consegnano a lei). */
  private var composeView: View? = null

  /** Il posto della tastiera nello screenshot: un blocco del colore delle superfici alte, alto quanto l'inset della tastiera. */
  @Composable
  private fun KeyboardStandIn(modifier: Modifier) {
    val height = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }
    Box(modifier.fillMaxWidth().height(height).background(MaterialTheme.colorScheme.surfaceContainerHigh))
  }

  /**
   * La tastiera aperta per una frazione dell'altezza dello schermo: Robolectric non manda gli inset da solo, si consegnano alla vista di Compose (come in
   * `RicercaTastieraTest`).
   */
  private fun openKeyboard(fraction: Float) {
    val view = checkNotNull(composeView) { "La scena non ha ancora una vista." }
    val insets = android.view.WindowInsets.Builder()
      .setInsets(android.view.WindowInsets.Type.ime(), android.graphics.Insets.of(0, 0, 0, (view.height * fraction).toInt()))
      .setVisible(android.view.WindowInsets.Type.ime(), true)
      .build()
    view.dispatchApplyWindowInsets(insets)
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

    /** Il fotogramma dell'apertura a meta': la molla della barra e' circa a meta' strada. */
    const val HALF_OPEN_MS = 110L

    /** La tastiera dello screenshot: due quinti dello schermo, come una tastiera vera in verticale. */
    const val KEYBOARD_FRACTION = 0.4f

    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = SHOT_PHONE)
internal class H4RicercaPhoneScreenshotTest(dark: Boolean) : H4RicercaScreenshotBase(dark, "telefono") {

  /** Scrivendo «compleanno», tre completamenti lunghi: due cominciano con cio' che l'utente ha gia' scritto. */
  private fun longSuggestions(scenes: ScaffoldScenes) = FixedSearchSource(
    query = "compleanno",
    suggestions = listOf(
      Suggestion.Recent(HistoryEntry(1, "compleanno della nonna", emptyList())),
      Suggestion.PlaceSuggestion(PlaceOption(setOf(7), "Castiglione della Pescaia", PlaceLevel.PLACE)),
      Suggestion.Recent(HistoryEntry(2, "compleanno di Anna al mare", emptyList())),
    ),
    state = scenes.searchTyping.state,
  )

  /**
   * CU-2 (giri 1 e 2): a 360 dp i suggerimenti lunghi si mostrano **interi**, come la barra delle parole della tastiera (prima «comple… | Castigli… |
   * comple…», poi «…della nonn… | Castiglione… | …di Anna al…»): quelli che entrano interi, il piu' adatto per primo.
   */
  @Test
  fun suggerimentiLunghi() {
    val scenes = ScaffoldScenes()
    shoot("suggerimenti-lunghi") { GalleryScaffold(scenes, GalleryTab.Library, searchOpen = true, search = longSuggestions(scenes)) }
  }

  /** Gli stessi con il testo a 1,3x (solo in chiaro): ancora interi (meno voci), senza puntini ne' a capo. */
  @Test
  fun suggerimentiLunghiTestoGrande() {
    if (dark) return
    val scenes = ScaffoldScenes()
    shoot("suggerimenti-lunghi", fontScale = LARGE_TEXT) { GalleryScaffold(scenes, GalleryTab.Library, searchOpen = true, search = longSuggestions(scenes)) }
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
