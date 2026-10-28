package dev.pampa.mycloudgallery.feature.library.grid

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.noop.NoPlaceholderCache
import dev.pampa.mycloudgallery.core.library.contract.GridLayout
import dev.pampa.mycloudgallery.core.library.contract.GridLayoutEngine
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridSpec
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.layout.TimelineLayoutEngine
import dev.pampa.mycloudgallery.core.library.testing.InMemoryTimelineView
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import java.util.Collections
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Avvio istantaneo (28/10): il layout della Libreria preparato mentre il processo nasce ([GridWarmup]) e' **proprio quello** che la griglia usa alla
 * prima composizione, con gli stessi ingressi che `MediaGrid` calcola (larghezza, fessura, densita' delle intestazioni, colonne dei livelli). Se un
 * ingresso non coincide non si usa e la griglia impagina come prima. Una vista grande (oltre `GridLayoutStore.SyncLimit`) senza layout preparato
 * nasce vuota e si impagina fuori dal thread principale: e' il fotogramma che il layout preparato toglie.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class GridWarmupTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  /** Il motore vero, che ricorda i layout che ha dato (per riconoscerli per identita'). */
  private class RecordingEngine : GridLayoutEngine {
    private val engine = TimelineLayoutEngine()
    val given: MutableList<GridLayout> = Collections.synchronizedList(ArrayList())

    override fun layout(view: TimelineView, spec: GridSpec): GridLayout = engine.layout(view, spec).also { given += it }
  }

  private val library = SyntheticLibrary(seed = 3L, count = GridLayoutStore.SyncLimit + 2_000)
  private val view = InMemoryTimelineView(library.index(), LibraryFilter.ALL)

  @After
  fun cleanUp() {
    GridWarmup.clear()
  }

  private fun grid(engine: GridLayoutEngine, level: GridLevel = GridLevel.DAYS): MediaGridState = MediaGridState(
    view = view,
    loader = ControlledThumbnailLoader(),
    placeholders = NoPlaceholderCache,
    placeNames = FakePlaces(),
    engine = engine,
    refOf = { library.ref(it) },
    level = level,
    style = GridStyle.SQUARE,
    refAt = { _, index -> library.refAt(index) },
  )

  private fun prepare(engine: GridLayoutEngine, level: GridLevel = GridLevel.DAYS, widthPx: Int? = null): GridLayout {
    val metrics = ApplicationProvider.getApplicationContext<Application>().resources.displayMetrics
    val fontScale = ApplicationProvider.getApplicationContext<Application>().resources.configuration.fontScale
    val recording = engine as RecordingEngine
    GridWarmup.prepare(view, GridStyle.SQUARE, level, widthPx ?: metrics.widthPixels, metrics.density, fontScale, engine)
    return recording.given.last()
  }

  @Test
  fun laPrimaComposizioneUsaIlLayoutPreparato() {
    val engine = RecordingEngine()
    val prepared = prepare(engine)
    val state = grid(engine)
    composeRule.showThemed { MediaGrid(state = state) }
    composeRule.runOnIdle {
      // Lo stesso oggetto: la griglia non ha rifatto il livello mostrato (gli altri tre si calcolano dopo, come sempre).
      assertThat(state.layout).isSameInstanceAs(prepared)
      assertThat(engine.given.count { it.spec.level == GridLevel.DAYS }).isEqualTo(1)
    }
  }

  @Test
  fun unaLarghezzaDiversaNonSiUsa() {
    val engine = RecordingEngine()
    val prepared = prepare(engine, widthPx = 333)
    val state = grid(engine)
    composeRule.showThemed { MediaGrid(state = state) }
    composeRule.waitUntil { state.layout != null }
    composeRule.runOnIdle { assertThat(state.layout).isNotSameInstanceAs(prepared) }
  }

  @Test
  fun unAltroLivelloNonSiUsa() {
    val engine = RecordingEngine()
    val prepared = prepare(engine, level = GridLevel.MONTHS)
    val state = grid(engine, level = GridLevel.DAYS)
    composeRule.showThemed { MediaGrid(state = state) }
    composeRule.waitUntil { state.layout != null }
    composeRule.runOnIdle {
      assertThat(state.layout).isNotSameInstanceAs(prepared)
      assertThat(state.layout!!.spec.level).isEqualTo(GridLevel.DAYS)
    }
  }
}
