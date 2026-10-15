package dev.pampa.mycloudgallery.feature.library.grid

import android.app.Application
import android.os.Looper
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.contract.PlaceholderCache
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.layout.TimelineLayoutEngine
import dev.pampa.mycloudgallery.core.library.testing.InMemoryTimelineView
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.places.testing.FakePlaceData
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import java.util.concurrent.ConcurrentHashMap
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * I segnaposto ThumbHash attorno a dove si e' si decodificano su un thread di lavoro (`PlaceholderWarmup`): sull'S25
 * (15/10) le celle del livello piu' fitto, al primo pinch, li decodificavano tutti sul thread principale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class GridPlaceholderWarmupTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  /** Una cache che conta da quale thread la si chiama; [threadSafe] decide se la griglia la scalda. */
  private class CountingCache(override val threadSafe: Boolean) : PlaceholderCache {
    val fromWorker: MutableSet<ThumbHash> = ConcurrentHashMap.newKeySet()
    val fromMain: MutableSet<ThumbHash> = ConcurrentHashMap.newKeySet()
    private val pixel = ImageBitmap(1, 1)

    override fun image(h: ThumbHash): ImageBitmap {
      if (Looper.myLooper() == Looper.getMainLooper()) fromMain += h else fromWorker += h
      return pixel
    }
  }

  private fun grid(cache: PlaceholderCache, library: SyntheticLibrary): MediaGridState {
    val view = InMemoryTimelineView(library.index(), LibraryFilter.ALL)
    return MediaGridState(
      view = view,
      loader = ControlledThumbnailLoader(),
      placeholders = cache,
      placeNames = FakePlaces(),
      engine = TimelineLayoutEngine(),
      refOf = { library.ref(it) },
      refAt = { _, index -> library.refAt(index) },
    )
  }

  @Test
  fun iSegnapostoAttornoAlCentroSiScaldanoSuUnThreadDiLavoro() {
    val library = SyntheticLibrary(seed = 1L, count = 2_000, placeIds = FakePlaceData.IDS)
    val cache = CountingCache(threadSafe = true)
    val state = grid(cache, library)
    composeRule.showThemed { MediaGrid(state = state) }

    val target = composeRule.runOnIdle { PlaceholderWarmup.around(state, state.layout!!) }!!
    val view = target.view
    val window = (target.center - PlaceholderWarmup.Before).coerceAtLeast(0)..(target.center + PlaceholderWarmup.After).coerceAtMost(view.size - 1)
    val expected = window.mapNotNull { view.snapshot.thumbHashAt(view.snapshotIndex(it)) }.toSet()
    assertThat(expected.size).isGreaterThan(100)
    composeRule.waitUntil(timeoutMillis = 10_000) { cache.fromWorker.containsAll(expected) }
    // Le celle sullo schermo sono dentro la finestra scaldata.
    assertThat(expected).containsAtLeastElementsIn(cache.fromMain)
  }

  @Test
  fun unaCacheNonSicuraFraThreadNonSiTocca() {
    val library = SyntheticLibrary(seed = 1L, count = 2_000, placeIds = FakePlaceData.IDS)
    val cache = CountingCache(threadSafe = false)
    val state = grid(cache, library)
    composeRule.showThemed { MediaGrid(state = state) }
    composeRule.waitForIdle()
    assertThat(cache.fromMain).isNotEmpty()
    assertThat(cache.fromWorker).isEmpty()
  }
}
