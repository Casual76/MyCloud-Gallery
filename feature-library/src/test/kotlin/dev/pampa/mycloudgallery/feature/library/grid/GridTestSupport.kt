package dev.pampa.mycloudgallery.feature.library.grid

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbStats
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.testing.FakePlaceholderCache
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.layout.TimelineLayoutEngine
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.places.testing.FakePlaceData
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.robolectric.Shadows.shadowOf

/** L'activity vuota della regola di Compose non e' nel manifest di un modulo libreria: la si registra a mano. */
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

fun ComposeContentTestRule.showThemed(dark: Boolean = false, content: @Composable () -> Unit) {
  setContent {
    MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) { content() }
  }
  waitForIdle()
}

/**
 * Un caricatore pilotato dal test: ogni chiave ha uno stato che parte vuoto, e il test decide quando "arriva" una
 * miniatura ([deliver]). Registra le finestre ricevute.
 */
class ControlledThumbnailLoader : ThumbnailLoader {
  val states = HashMap<ThumbKey, MutableState<ImageBitmap?>>()
  val windows = mutableListOf<Pair<List<ThumbRequest>, List<ThumbRequest>>>()
  private val statsFlow = MutableStateFlow(ThumbStats())

  override fun image(key: ThumbKey, ref: MediaRef): State<ImageBitmap?> = states.getOrPut(key) { mutableStateOf(null) }

  override fun peek(key: ThumbKey): ImageBitmap? = states[key]?.value

  override fun window(visible: List<ThumbRequest>, ahead: List<ThumbRequest>) {
    windows += visible to ahead
  }

  override fun trim(level: Int) = Unit

  override val stats: StateFlow<ThumbStats> = statsFlow

  fun deliver(key: ThumbKey, image: ImageBitmap) {
    states.getValue(key).value = image
  }
}

/** Conta le composizioni di righe e celle (il budget di ricomposizione, §2.6). */
class CountingProbe : GridProbe {
  val rows = HashMap<Int, Int>()
  val cells = HashMap<Int, Int>()

  override fun rowComposed(row: Int) {
    rows.merge(row, 1, Int::plus)
  }

  override fun cellComposed(position: Int) {
    cells.merge(position, 1, Int::plus)
  }

  fun snapshotRows(): Map<Int, Int> = HashMap(rows)

  fun snapshotCells(): Map<Int, Int> = HashMap(cells)
}

/** Una griglia sulla Libreria sintetica, con il caricatore e il motore scelti dal test. */
fun syntheticGrid(
  count: Int = 600,
  level: GridLevel = GridLevel.DAYS,
  style: GridStyle = GridStyle.SQUARE,
  loader: ThumbnailLoader = ControlledThumbnailLoader(),
  filter: LibraryFilter = LibraryFilter.ALL,
): Pair<MediaGridState, SyntheticLibrary> {
  val library = SyntheticLibrary(seed = 1L, count = count, placeIds = FakePlaceData.IDS)
  val state = MediaGridState(
    view = library.view(filter),
    loader = loader,
    placeholders = FakePlaceholderCache(),
    placeNames = FakePlaces(),
    engine = TimelineLayoutEngine(),
    refOf = { library.ref(it) },
    level = level,
    style = style,
    refAt = { _, index -> library.refAt(index) },
  )
  return state to library
}

/**
 * Fase H (D2): una cella della Libreria che si puo' toccare, cioe' il cui centro sta **sotto la chrome in alto** (oltre [belowFraction]
 * dell'altezza): i pezzi di vetro in alto trattengono i tocchi, e la prima cella della lista sta proprio sotto l'etichetta.
 */
fun ComposeContentTestRule.cellBelowChrome(belowFraction: Float = 0.25f): SemanticsNodeInteraction {
  val nodes = onAllNodesWithContentDescription("Foto, ", substring = true)
  val rootHeight = onRoot().fetchSemanticsNode().size.height
  val index = nodes.fetchSemanticsNodes().indexOfFirst { it.boundsInRoot.center.y > rootHeight * belowFraction }
  check(index >= 0) { "Nessuna cella sotto la chrome." }
  return nodes[index]
}

