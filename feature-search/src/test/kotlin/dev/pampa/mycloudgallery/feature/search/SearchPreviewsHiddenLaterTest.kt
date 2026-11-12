package dev.pampa.mycloudgallery.feature.search

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.PlaceName
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.core.search.engine.LibraryPlaces
import dev.pampa.mycloudgallery.core.search.engine.LibrarySuggestions
import dev.pampa.mycloudgallery.core.search.testing.FakeSearchData
import dev.pampa.mycloudgallery.core.search.testing.FixedToday
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchEngine
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchHistory
import dev.pampa.mycloudgallery.core.search.testing.SimpleQueryParser
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * CS-23 e CG (giro 2 della fase I): una foto nascosta **dopo** che la terna degli esempi e' pronta, **a cache calda** (la miniatura e' gia' in memoria e il
 * tondo l'ha gia' disegnata). La voce tiene solo l'id della foto: al disegno dopo il tondo la mostra solo se e' nell'istantanea della Libreria visibile di
 * adesso (`photoArt`), e il caricatore la tiene accanto alla finestra solo alla stessa condizione (`KeepSuggestionPreviews`). Qui la terna e' quella vera
 * del controller con gli esempi (due esempi con la foto e la ricerca recente con la foto del suo primo risultato), disegnata dalla barra vera con il
 * caricatore finto con cache; poi la foto esce dall'istantanea, prima quella di un esempio, poi quella della recente.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w1280dp-h800dp-hdpi")
class SearchPreviewsHiddenLaterTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val lib = SyntheticLibrary(seed = 33L, count = 240, placeIds = intArrayOf(1, 2, 3, 4, 5))

  /** La terna pronta del controller vero: gli esempi con la foto e, per ultima, la ricerca recente «torta» con la foto del suo primo risultato. */
  private fun readyStrip(): List<SuggestionItem> {
    var strip = emptyList<SuggestionItem>()
    runTest {
      val history = InMemorySearchHistory()
      val data = FakeSearchData(lib.snapshot)
      data.texts[lib.id(4)] = "torta di compleanno"
      val places = LibraryPlaces { id -> PlaceName("Luogo $id", null, "Italia") }
      val engine = InMemorySearchEngine(data, embedder = null, parser = SimpleQueryParser(), today = FixedToday(), places = { places.dictionary(lib.snapshot) })
      val examples = SearchExamples(engine, data, null, backgroundScope, compute = StandardTestDispatcher(testScheduler), prepareDelayMillis = 10)
      val c = SearchController(engine, LibrarySuggestions(data, places, history), history, data, null, backgroundScope, 100, 200, examples)
      advanceTimeBy(1_000)
      runCurrent()
      c.onQueryChange("torta")
      c.onSubmit()
      runCurrent()
      c.clear()
      runCurrent()
      strip = c.strip.value
    }
    return strip
  }

  /** L'istantanea visibile dopo che [gone] sono stati nascosti: non li contiene piu'. */
  private fun without(snapshot: TimelineSnapshot, gone: Set<MediaId>): TimelineSnapshot = object : TimelineSnapshot by snapshot {
    override fun indexOf(id: MediaId): Int = if (id in gone) -1 else snapshot.indexOf(id)
  }

  private fun photoOf(item: SuggestionItem): MediaId = (item.art as SuggestionArt.Photo).media

  private fun keyOf(thumbs: TestThumbnails, media: MediaId): ThumbKey = thumbRequestOf(thumbs.thumbnails, lib.snapshot, lib.snapshot.indexOf(media))!!.key

  private class Scene(val strip: List<SuggestionItem>, val example: MediaId, val recent: MediaId, val thumbs: TestThumbnails)

  /** La terna pronta, disegnata dalla barra vera con le miniature in cache; [library] e' l'istantanea che la barra legge. */
  private fun scene(library: () -> TimelineSnapshot): Scene {
    val strip = readyStrip()
    assertThat(strip.last().suggestion).isInstanceOf(Suggestion.Recent::class.java)
    assertThat(strip.all { it.art is SuggestionArt.Photo }).isTrue()
    val recent = photoOf(strip.last())
    val example = strip.dropLast(1).map(::photoOf).first { it != recent }
    val thumbs = TestThumbnails(lib)
    rule.setSearchContent {
      SearchSuggestions(FixedSearchSource(items = strip, library = library()), NoSearchHandlers, backdrop = null, thumbnails = thumbs.thumbnails)
    }
    return Scene(strip, example, recent, thumbs)
  }

  private fun photosDrawn() = rule.onAllNodesWithTag(SuggestionPhotoTag, useUnmergedTree = true)

  private fun glyphsDrawn() = rule.onAllNodesWithTag(SuggestionGlyphTag, useUnmergedTree = true)

  @Test
  fun unaFotoNascostaDopo_ACacheCalda_IlTondoMostraIlSegnoDelTipo_EsempioERecente_CS23() {
    var library by mutableStateOf<TimelineSnapshot>(lib.snapshot)
    val s = scene { library }
    // Cache calda: le tre miniature sono in memoria e i tre tondi le disegnano.
    photosDrawn().assertCountEquals(s.strip.size)
    glyphsDrawn().assertCountEquals(0)
    assertThat(s.thumbs.loader.peek(keyOf(s.thumbs, s.example))).isNotNull()
    assertThat(s.thumbs.loader.peek(keyOf(s.thumbs, s.recent))).isNotNull()
    // La foto dell'esempio si nasconde: esce dall'istantanea visibile. Al disegno dopo il suo tondo ha il segno del tipo.
    library = without(lib.snapshot, setOf(s.example))
    rule.waitForIdle()
    val hiddenExample = s.strip.count { photoOf(it) == s.example }
    photosDrawn().assertCountEquals(s.strip.size - hiddenExample)
    glyphsDrawn().assertCountEquals(hiddenExample)
    // Lo stesso per la ricerca recente.
    library = without(lib.snapshot, setOf(s.example, s.recent))
    rule.waitForIdle()
    val hiddenBoth = s.strip.count { photoOf(it) == s.example || photoOf(it) == s.recent }
    photosDrawn().assertCountEquals(s.strip.size - hiddenBoth)
    glyphsDrawn().assertCountEquals(hiddenBoth)
    // La cache e' ancora calda: e' il tondo a non usarla.
    assertThat(s.thumbs.loader.peek(keyOf(s.thumbs, s.example))).isNotNull()
  }

  @Test
  fun unaFotoNascostaDopo_IlCaricatoreNonLaTienePiuAccantoAllaFinestra_EsempioERecente_CS23() {
    var library by mutableStateOf<TimelineSnapshot>(lib.snapshot)
    val s = scene { library }
    fun keptKeys() = s.thumbs.loader.kept[SuggestionKeepOwner].orEmpty().map { it.key }.toSet()
    assertThat(keptKeys()).containsAtLeast(keyOf(s.thumbs, s.example), keyOf(s.thumbs, s.recent))
    library = without(lib.snapshot, setOf(s.example))
    rule.waitForIdle()
    assertThat(keptKeys()).doesNotContain(keyOf(s.thumbs, s.example))
    assertThat(keptKeys()).contains(keyOf(s.thumbs, s.recent))
    library = without(lib.snapshot, setOf(s.example, s.recent))
    rule.waitForIdle()
    assertThat(keptKeys()).containsNoneOf(keyOf(s.thumbs, s.example), keyOf(s.thumbs, s.recent))
  }
}
