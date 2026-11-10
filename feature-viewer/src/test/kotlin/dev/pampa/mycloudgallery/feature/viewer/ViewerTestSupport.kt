package dev.pampa.mycloudgallery.feature.viewer

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.PixelRect
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.library.testing.FakeViewerAnchor
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.places.testing.FakePlaceData
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.robolectric.Shadows.shadowOf

/** L'activity vuota della regola di Compose non e' nel manifest di un modulo libreria: la si registra a mano. */
internal class RegisterComponentActivity : TestWatcher() {
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

/** Il telefono (412 dp) e il tablet (1280 dp) delle prove. */
internal const val PHONE = "it-w412dp-h915dp-xhdpi"
internal const val TABLET = "it-w1280dp-h800dp-xhdpi"

/** Il telefono degli screenshot per l'utente (K4): il suo Galaxy S25, 360 x 780 dp. Non 412 dp, dove entrano piu' cose che sul suo schermo. */
internal const val SHOT_PHONE = "it-w360dp-h780dp-xhdpi"

/**
 * Come la radice di un'app vera: il tema, e un `FluidGlassModalHost` sopra tutto, perche' il menu e i fogli di conferma
 * si aprono li' (senza host non si apre niente, ed e' la degradazione giusta in produzione, ma nei test serve vederli).
 */
@Composable
internal fun ViewerTestHost(dark: Boolean = true, content: @Composable () -> Unit) {
  MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
    val modalHost = rememberFluidGlassModalHostState()
    val backdrop = rememberGlassBackdrop()
    CompositionLocalProvider(LocalFluidGlassModalHostState provides modalHost) {
      Box(Modifier.fillMaxSize().glassBackdropSource(backdrop)) { content() }
      FluidGlassModalHost(state = modalHost, backdrop = backdrop)
    }
  }
}

/** Una Libreria sintetica pronta per il viewer, con la geometria della cella scelta dal test. */
internal class ViewerFixture(count: Int = 50, seed: Long = 3) {
  val library = SyntheticLibrary(seed = seed, count = count, placeIds = FakePlaceData.IDS)
  val items = PhoneItems(library.view(), this)
  val anchor = FakeViewerAnchor()

  fun idAt(index: Int): MediaId = library.snapshot.idAt(index)

  /** Gli indici degli elementi di un tipo (video, motion photo...) nella Libreria di prova. */
  fun indicesOf(kind: dev.pampa.mycloudgallery.core.model.MediaKind): List<Int> =
    (0 until library.count).filter { library.snapshot.kindAt(it) == kind }

  /** Una miniatura di 100 px nella cella [index] (griglia di quattro colonne a 200 px per colonna). */
  fun placeCell(index: Int, top: Float = 400f) {
    val column = index % 4
    anchor.bounds[idAt(index)] = PixelRect(column * 200f + 4f, top, column * 200f + 196f, top + 192f)
  }

  fun request(startIndex: Int = 4, secure: Boolean = false): ViewerRequest {
    placeCell(startIndex)
    return ViewerRequest(items = items, start = idAt(startIndex), anchor = anchor, secure = secure)
  }

  /** La stessa Libreria senza [removed]: un elemento sparito dal catalogo (E-V6). */
  fun without(removed: Set<MediaId>): TimelineView = WithoutView(library.view(), removed)
}

/** Una vista da cui sono spariti alcuni elementi: la mappa dalla posizione di vista a quella della vista di base. */
internal class WithoutView(private val base: TimelineView, removed: Set<MediaId>) : TimelineView {
  private val map: IntArray = (0 until base.size).filter { base.snapshot.idAt(base.snapshotIndex(it)) !in removed }.toIntArray()

  override val snapshot: TimelineSnapshot = base.snapshot
  override val filter: LibraryFilter = base.filter
  override val size: Int = map.size

  override fun snapshotIndex(viewIndex: Int): Int = base.snapshotIndex(map[viewIndex])

  override fun viewIndexOf(id: MediaId): Int {
    val baseIndex = base.viewIndexOf(id)
    if (baseIndex < 0) return -1
    val found = map.binarySearch(baseIndex)
    return if (found >= 0) found else -1
  }
}

/**
 * Il gesto per andare alla pagina con la posizione **dopo** nella vista della Libreria, cioe' alla foto piu' vecchia: dalla beta le piu' vecchie stanno a sinistra di quella aperta
 * (B5), quindi e' uno swipe verso destra. Le prove dicono "alla foto dopo" e non "a sinistra": se l'ordine cambia, cambia qui.
 */
internal fun TouchInjectionScope.swipeToNext(durationMillis: Long = 200) = swipeRight(durationMillis = durationMillis)

/** Il gesto contrario di [swipeToNext]: alla foto con la posizione prima (la piu' recente), uno swipe verso sinistra. */
internal fun TouchInjectionScope.swipeToPrevious(durationMillis: Long = 200) = swipeLeft(durationMillis = durationMillis)
