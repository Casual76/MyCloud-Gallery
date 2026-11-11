package dev.pampa.mycloudgallery.feature.search

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.contract.SelectionModel
import dev.pampa.mycloudgallery.core.library.contract.ViewerAnchor
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.PlaceLevel
import dev.pampa.mycloudgallery.core.search.contract.PlaceOption
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.feature.search.contract.SearchScreenState
import dev.pampa.mycloudgallery.feature.search.contract.SearchSelection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La ricerca dentro la Libreria (H4), i pezzi di feature-search composti: la barra dei suggerimenti (tre voci, un tocco le applica, vuota si ritira) e la
 * griglia dei risultati (il piu' adatto in basso, sopra la barra; il tocco apre il viewer con l'ancora della cella; un tocco annullato dal sistema, come
 * l'Indietro dal bordo, non apre niente; la pressione lunga entra nella selezione della Libreria; scorrendo la tastiera scende).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w360dp-h780dp-xhdpi")
class SearchInLibraryUiTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val lib = library(count = 60)
  private val thumbs = TestThumbnails(lib)

  private val results = SearchScreenState.Results(emptyList(), lib.view(*(0 until 20).toList().toIntArray()), lib.view(40, 41, 42), SearchCoverage.Complete, emptyList())

  /** Le celle in vista, riconosciute dall'azione del tocco ("Apri", o "Scegli" in selezione). */
  private fun cells(label: String = "Apri") = rule.onAllNodes(SemanticsMatcher("tocco: $label") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label })

  private class Opened {
    var view: OrderedTimelineView? = null
    var media: MediaId? = null
    var anchor: ViewerAnchor? = null
  }

  private fun setResults(opened: Opened = Opened(), selection: SearchSelection? = null, onScroll: () -> Unit = {}) {
    rule.setSearchContent {
      Box(Modifier.fillMaxSize()) {
        SearchResults(
          source = FixedSearchSource(query = "mare", state = results),
          thumbnails = thumbs.thumbnails,
          onOpen = { view, media, anchor ->
            opened.view = view
            opened.media = media
            opened.anchor = anchor
          },
          contentPadding = androidx.compose.foundation.layout.PaddingValues(),
          selection = selection,
          onUserScroll = onScroll,
        )
      }
    }
  }

  @Test
  fun laBarraDeiSuggerimentiMostraLeTreVociEUnToccoLeApplica() {
    val handlers = RecordingHandlers()
    val source = mutableStateOf(
      FixedSearchSource(
        suggestions = listOf(
          Suggestion.Recent(recent(1, "mare ad agosto")),
          Suggestion.PlaceSuggestion(PlaceOption(setOf(1), "Roma", PlaceLevel.PLACE)),
          Suggestion.Term("Ferragosto"),
        ),
      ),
    )
    rule.setSearchContent { SearchSuggestions(source = source.value, handlers = handlers, backdrop = null) }
    rule.onNodeWithContentDescription("Cerca «Roma»").performClick()
    assertThat(handlers.calls).containsExactly("suggestion:Roma")
    // Senza suggerimenti la barra si ritira: niente vetro vuoto.
    source.value = FixedSearchSource()
    rule.waitForIdle()
    rule.onAllNodesWithText("Roma").assertCountEquals(0)
    rule.onNodeWithTag("chrome-suggerimenti").assertDoesNotExist()
  }

  @Test
  fun ilRisultatoPiuAdattoStaInBassoSopraLaBarra() {
    setResults()
    val first = rule.onNode(cellOf(lib.id(0))).fetchSemanticsNode().boundsInRoot
    val fifth = rule.onNode(cellOf(lib.id(4))).fetchSemanticsNode().boundsInRoot
    // Tre colonne a 360 dp: il quinto e' nella seconda riga, sopra la prima.
    assertThat(first.top).isGreaterThan(fifth.top)
  }

  @Test
  fun unToccoApreIlViewerSuiRisultatiConLAncoraDellaCella() {
    val opened = Opened()
    setResults(opened)
    rule.onNode(cellOf(lib.id(2))).performClick()
    assertThat(opened.media).isEqualTo(lib.id(2))
    // Il viewer scorre i sicuri e poi i meno probabili, nell'ordine mostrato.
    assertThat(opened.view!!.size).isEqualTo(23)
    // L'ancora dice dove sta la cella (il viewer parte da li' e ci torna).
    assertThat(opened.anchor!!.boundsOf(lib.id(2))).isNotNull()
  }

  @Test
  fun unToccoAnnullatoDalSistemaNonApreNiente() {
    val opened = Opened()
    setResults(opened)
    // L'Indietro dal bordo che parte sopra un risultato: il sistema si prende il gesto e manda un annullamento.
    rule.onNode(cellOf(lib.id(1))).performTouchInput {
      down(center)
      cancel()
    }
    rule.waitForIdle()
    assertThat(opened.media).isNull()
  }

  @Test
  fun laPressioneLungaEntraNellaSelezioneDellaLibreriaEIlToccoSceglie() {
    val model = SelectionModel()
    val selecting = mutableStateOf(false)
    val selection = SearchSelection(model, selecting = { selecting.value }, begin = { selecting.value = true })
    val opened = Opened()
    setResults(opened, selection)
    rule.onNode(cellOf(lib.id(3))).performTouchInput { longClick() }
    rule.waitForIdle()
    assertThat(selecting.value).isTrue()
    assertThat(model.selected.value.ids).containsExactly(lib.id(3))
    rule.onNode(cellOf(lib.id(5))).performClick()
    assertThat(model.selected.value.ids).containsExactly(lib.id(3), lib.id(5))
    // In selezione il tocco sceglie, non apre.
    assertThat(opened.media).isNull()
    cells("Scegli").fetchSemanticsNodes().isNotEmpty().let { assertThat(it).isTrue() }
  }

  @Test
  fun scorrendoIRisultatiLaTastieraScende() {
    var scrolled = 0
    setResults(onScroll = { scrolled++ })
    rule.onNode(cellOf(lib.id(0))).performTouchInput { swipeDown() }
    rule.waitForIdle()
    assertThat(scrolled).isGreaterThan(0)
  }

  private fun cellOf(id: MediaId): SemanticsMatcher = androidx.compose.ui.test.hasTestTag(searchResultTag(id))
}
