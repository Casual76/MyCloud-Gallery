package dev.pampa.mycloudgallery.main

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.SHOT_PHONE
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerAnchor
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.IdleSuggestions
import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.core.search.contract.SearchEngine
import dev.pampa.mycloudgallery.core.search.contract.SearchReadiness
import dev.pampa.mycloudgallery.core.search.contract.SearchRequest
import dev.pampa.mycloudgallery.core.search.contract.SearchResults
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.core.search.contract.Suggestions
import dev.pampa.mycloudgallery.core.search.testing.FakeSearchData
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchHistory
import dev.pampa.mycloudgallery.feature.library.library.LibraryTabState
import dev.pampa.mycloudgallery.feature.library.testing.syntheticLibraryTabState
import dev.pampa.mycloudgallery.feature.search.SearchController
import dev.pampa.mycloudgallery.feature.search.contract.SearchActions
import dev.pampa.mycloudgallery.feature.search.contract.SearchRefs
import dev.pampa.mycloudgallery.feature.search.contract.SearchThumbnails
import dev.pampa.mycloudgallery.feature.search.noop.NoSearchActions
import dev.pampa.mycloudgallery.feature.search.rememberSearchSource
import dev.pampa.mycloudgallery.feature.search.searchResultTag
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.integration.Attese
import dev.pampa.mycloudgallery.status.GlobalStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Il cablaggio della ricerca dentro la Libreria nella schermata principale (H4; CS-18 del giro 1): `MainSearch` con un `SearchController` vero, il
 * suo motore a copione, `MainScaffold` e l'ospite del viewer. Prima il comportamento stava solo negli screenshot (le prove di `:app` lasciavano la
 * ricerca senza controller).
 *  - Dal cerchio, da un'altra scheda: dietro c'e' la Libreria, il motore si scalda, scrivendo arrivano i risultati; Indietro chiude, svuota il campo e
 *    torna alla scheda di partenza.
 *  - Un risultato toccato apre il viewer dalla sua cella ed entra fra le recenti; **chiudendo il viewer si torna ai risultati con la ricerca ancora
 *    aperta**, lo stesso testo, la stessa cella in vista, e il campo non riprende il fuoco (la tastiera non risale da sola).
 *  - "Trova simili" apre la ricerca senza tastiera, con la riga che lo dice, e non entra fra le recenti.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = SHOT_PHONE)
class RicercaCablaggioTest {

  private val compose = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(compose)

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

  private val state: LibraryTabState = syntheticLibraryTabState()
  private val snapshot = state.grid.view.snapshot
  private val engine = Copione(snapshot.let { s -> { positions: IntRange -> IndexOrderedTimelineView(s, positions.filter { it < s.size }.toIntArray()) } })
  private val history = InMemorySearchHistory()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val controller = SearchController(engine, NessunSuggerimento, history, FakeSearchData(snapshot), null, scope)
  private val host = ViewerHostState(null)
  private val opened = ArrayList<Pair<MediaId, ViewerAnchor>>()
  private val actions = object : SearchActions by NoSearchActions {
    override fun openResults(view: OrderedTimelineView, start: MediaId, anchor: ViewerAnchor) {
      opened += start to anchor
      host.open(ViewerRequest(state.viewerItems, start, anchor, secure = false), ViewerServices())
    }
  }
  private var tab by mutableStateOf(GalleryTab.Library)
  private lateinit var main: MainSearch

  @After
  fun tearDown() = scope.cancel()

  private fun show() {
    compose.setContent {
      MyCloudGalleryTheme {
        CompositionLocalProvider(LocalViewerHost provides host) {
          // Come `MainDestination`: il cablaggio, la sorgente del controller, la ricerca dello scaffold.
          val mainSearch = rememberMainSearch(controller)
          SideEffect { main = mainSearch }
          val source = rememberSearchSource(controller)
          val search = remember(source, mainSearch) {
            ScaffoldSearch(
              source = source,
              handlers = controller,
              thumbnails = SearchThumbnails(state.grid.loader, state.grid.placeholders, SearchRefs { _, _ -> null }),
              actions = actions,
              onResultOpened = mainSearch::resultOpened,
            )
          }
          MainScaffold(
            status = GlobalStatus.HOME_CONNECTED,
            selectedTab = tab,
            onSelectTab = { tab = it },
            onStatusClick = {},
            onOpenSettings = {},
            libraryState = state,
            collections = { _ -> Text("Raccolte di prova") },
            search = search,
            searchOpen = mainSearch.open,
            onOpenSearch = mainSearch::openFromCircle,
            onCloseSearch = mainSearch::close,
            searchFocus = mainSearch.focus,
            onSearchFocused = mainSearch::focused,
          )
        }
      }
    }
    compose.waitForIdle()
    touchMode()
  }

  /**
   * La finestra in **modalita' tocco**, come sul telefono. Robolectric non ci entra (i tocchi delle prove arrivano a Compose senza passare dalla finestra)
   * e fuori dalla modalita' tocco Android, quando un campo lascia il fuoco, lo riassegna alla prima vista dall'alto (`View.clearFocus`, da Android 9): la vista
   * di Compose lo riprende e lo restituisce al campo. Sul telefono, che e' in modalita' tocco, il campo lasciato resta senza fuoco.
   */
  private fun touchMode() = compose.runOnIdle {
    val decor = compose.activity.window.decorView
    val attachInfo = android.view.View::class.java.getDeclaredField("mAttachInfo").apply { isAccessible = true }.get(decor)
    attachInfo.javaClass.getDeclaredField("mInTouchMode").apply { isAccessible = true }.setBoolean(attachInfo, true)
    check(decor.isInTouchMode) { "La finestra non e' in modalita' tocco." }
  }

  private fun field() = compose.onNode(hasSetTextAction())

  private fun firstResult(): MediaId = snapshot.idAt(0)

  private fun waitForResults() {
    compose.waitUntil(Attese.TETTO_MS) { compose.onAllNodes(hasTestTag(searchResultTag(firstResult()))).fetchSemanticsNodes().isNotEmpty() }
  }

  private fun openAndType(text: String) {
    compose.onNodeWithContentDescription("Cerca").performClick()
    compose.waitForIdle()
    field().performTextInput(text)
    waitForResults()
  }

  @Test
  fun daUnAltraScheda_ilCerchioApreLaRicercaConLaLibreriaDietro_eIndietroTornaLiColCampoVuoto() {
    tab = GalleryTab.Collections
    show()
    compose.onNodeWithText("Raccolte di prova").assertExists()
    openAndType("mare")
    assertThat(main.open).isTrue()
    // Dietro la Libreria (i risultati al posto della griglia), il motore scaldato all'apertura, la ricerca fatta sul testo scritto.
    compose.onNodeWithText("Raccolte di prova").assertDoesNotExist()
    assertThat(engine.warmUps).isAtLeast(1)
    assertThat(engine.requests.last().text).isEqualTo("mare")
    // La richiesta del fuoco e' di una volta: servita, si consuma.
    assertThat(main.focus).isFalse()

    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.waitForIdle()
    assertThat(main.open).isFalse()
    assertThat(controller.query.value).isEmpty()
    assertThat(tab).isEqualTo(GalleryTab.Collections)
    compose.onNodeWithText("Raccolte di prova").assertExists()
  }

  @Test
  fun unRisultatoAperto_entraFraLeRecenti_eChiudendoIlViewerSiTornaAiRisultatiConLaRicercaAperta() {
    show()
    openAndType("mare")
    val id = firstResult()
    compose.onNode(hasTestTag(searchResultTag(id))).performClick()
    compose.waitForIdle()
    assertThat(opened.map { it.first }).containsExactly(id)
    assertThat(host.request).isNotNull()
    compose.waitUntil(Attese.TETTO_MS) { history.entries.value.any { it.text == "mare" } }
    // Il viewer copre lo schermo: il campo lascia il fuoco (la tastiera scende).
    field().assertIsNotFocused()

    compose.runOnIdle { host.close() }
    compose.waitForIdle()
    // La ricerca e' ancora aperta, con lo stesso testo e gli stessi risultati; la cella da cui si era partiti e' sullo schermo (il viewer ci torna).
    assertThat(main.open).isTrue()
    assertThat(controller.query.value).isEqualTo("mare")
    field().assert(hasText("mare"))
    compose.onNode(hasTestTag(searchResultTag(id))).assertExists()
    assertThat(compose.runOnIdle { opened.single().second.boundsOf(id) }).isNotNull()
    // Il campo non riprende il fuoco da solo e la richiesta del fuoco non torna: la tastiera non risale.
    field().assertIsNotFocused()
    assertThat(main.focus).isFalse()
    // Con del testo c'e' la crocetta che svuota, non la X che chiude (CU-12: una X sola).
    compose.onNodeWithContentDescription("Chiudi la ricerca").assertDoesNotExist()
  }

  @Test
  fun trovaSimili_apreLaRicercaSenzaTastiera_conLaRigaCheLoDice_eNonEntraFraLeRecenti() {
    show()
    val id = firstResult()
    compose.runOnIdle { main.findSimilar(listOf(id)) }
    // I2: la riga mostra il segno e «Simili»; TalkBack (e qui la prova) legge la frase intera.
    compose.waitUntil(Attese.TETTO_MS) { compose.onAllNodes(hasContentDescription("Simili a questa foto")).fetchSemanticsNodes().isNotEmpty() }
    assertThat(main.open).isTrue()
    assertThat(main.focus).isFalse()
    field().assertIsNotFocused()
    assertThat(engine.similarSets).containsExactly(listOf(id))
    assertThat(history.entries.value).isEmpty()
    // A campo vuoto la X chiude.
    compose.onNodeWithContentDescription("Chiudi la ricerca").performClick()
    compose.waitForIdle()
    assertThat(main.open).isFalse()
  }

  /** Il motore a copione: ogni testo trova le prime dodici foto della Libreria, i simili le prime sei. */
  private class Copione(private val view: (IntRange) -> OrderedTimelineView) : SearchEngine {
    val requests: MutableList<SearchRequest> = java.util.Collections.synchronizedList(ArrayList())
    val similarSets: MutableList<List<MediaId>> = java.util.Collections.synchronizedList(ArrayList())

    @Volatile
    var warmUps: Int = 0
      private set

    override val coverage = MutableStateFlow<SearchCoverage>(SearchCoverage.Complete)
    override val readiness = MutableStateFlow<SearchReadiness>(SearchReadiness.Ready)

    private fun results(range: IntRange) = SearchResults(emptyList(), view(range), view(IntRange.EMPTY), SearchCoverage.Complete, emptyList(), ResultOrder.RELEVANT)

    override suspend fun search(request: SearchRequest): SearchResults {
      requests += request
      return results(0 until 12)
    }

    override suspend fun similar(media: MediaId, order: ResultOrder): SearchResults = similarToAll(listOf(media), order)

    override suspend fun similarToAll(media: List<MediaId>, order: ResultOrder): SearchResults {
      similarSets += media
      return results(0 until 6)
    }

    override fun warmUp() {
      warmUps++
    }
  }

  private object NessunSuggerimento : Suggestions {
    override suspend fun idle(): IdleSuggestions = IdleSuggestions(emptyList(), emptyList(), emptyList())

    override suspend fun complete(prefix: String): List<Suggestion> = emptyList()
  }
}
