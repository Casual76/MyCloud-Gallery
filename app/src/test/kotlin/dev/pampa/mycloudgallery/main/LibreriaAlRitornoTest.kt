package dev.pampa.mycloudgallery.main

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.GalleryScaffold
import dev.pampa.mycloudgallery.SHOT_PHONE
import dev.pampa.mycloudgallery.ScaffoldScenes
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.RowKind
import dev.pampa.mycloudgallery.core.library.layout.TimelineGridAnchors
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridState
import dev.pampa.mycloudgallery.feature.library.grid.SavedGridPosition
import dev.pampa.mycloudgallery.feature.library.testing.syntheticLibraryTabState
import kotlin.math.abs
import kotlin.math.roundToInt
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
 * GS-10 (K6): tornando alla Libreria dopo Cerca o dopo un'altra scheda la griglia e' **esattamente dov'era** (stessa prima riga, stesso scostamento
 * entro 1 px), con il centro dell'area utile su un'intestazione e dentro una foto, a Giorni e agli altri livelli. Con lo scaffold delle prove (le
 * pagine vere nel `SaveableStateHolder` di `MainScaffold`, K3a) sul telefono dell'utente.
 *
 * Prima, la pagina che tornava riapplicava alla griglia viva l'ancora salvata uscendo: con il centro su un'intestazione l'ancora e' il bordo alto della
 * prima foto del giorno, e rimetterla al centro faceva saltare la griglia (55 px nella prova del critico). E la ricreazione dell'activity continua a
 * riportare sulla stessa foto: con la griglia viva (il ViewModel sopravvive), con una griglia nuova (il processo ripartito) e con la Libreria coperta
 * da un'altra scheda mentre l'activity si ricrea.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = SHOT_PHONE)
class LibreriaAlRitornoTest {

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

  private var tab by mutableStateOf(GalleryTab.Library)
  private var searchOpen by mutableStateOf(false)
  private var scenes: ScaffoldScenes? by mutableStateOf(null)

  // Lo stato salvato dell'activity (il `Bundle`) e la composizione che si toglie e si rimette: la ricreazione, come in `LibrarySelectionRestoreTest`.
  private var registry by mutableStateOf(SaveableStateRegistry(restoredValues = null, canBeSaved = { true }))
  private var alive by mutableStateOf(true)

  /** La schermata principale sulla Libreria di [first], poi un trascinamento vero: da li' la griglia non si riporta piu' sui piu' recenti da sola. */
  private fun show(first: ScaffoldScenes) {
    scenes = first
    compose.setContent {
      CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
        if (alive) {
          MyCloudGalleryTheme {
            GalleryScaffold(
              scenes = checkNotNull(scenes),
              tab = tab,
              searchOpen = searchOpen,
              onSelectTab = { tab = it },
              onOpenSearch = { searchOpen = true },
              onCloseSearch = { searchOpen = false },
            )
          }
        }
      }
    }
    compose.waitForIdle()
    compose.onNode(hasScrollAction()).performTouchInput { swipeDown() }
    compose.waitForIdle()
  }

  /** Come una ricreazione dell'activity: si salva il registro, si toglie la composizione e se ne fa una nuova da quei valori, sugli stati di [next]. */
  private fun recreate(next: ScaffoldScenes) {
    val saved = compose.runOnIdle { registry.performSave() }
    compose.runOnIdle { alive = false }
    compose.waitForIdle()
    compose.runOnIdle {
      scenes = next
      registry = SaveableStateRegistry(restoredValues = saved, canBeSaved = { true })
      alive = true
    }
    compose.waitForIdle()
  }

  /** L'altezza dell'area utile della griglia (fra la chrome in alto e la pillola), come la usa la griglia per il suo centro. */
  private fun contentHeight(grid: MediaGridState): Int {
    val info = grid.listState.layoutInfo
    return info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
  }

  /** Cosa c'e' al centro dell'area utile: la riga, il suo tipo, a che altezza della riga cade il centro e quanto e' alta. */
  private data class Center(val row: Int, val kind: RowKind, val yInRow: Float, val height: Int)

  private fun center(grid: MediaGridState): Center = compose.runOnIdle {
    val layout = checkNotNull(grid.layout)
    val y = layout.rowTop(grid.listState.firstVisibleItemIndex) + grid.listState.firstVisibleItemScrollOffset + contentHeight(grid) / 2f
    val row = layout.rowAtOffset(y.toInt())
    Center(row, layout.rowKind(row), y - layout.rowTop(row), layout.rowHeight(row))
  }

  /**
   * Porta il centro dell'area utile dentro una riga di tipo [kind], lontano dai piu' recenti (dal primo terzo della Libreria in poi), a [IN_ROW]
   * della sua altezza. Le premesse si asseriscono: sotto il centro c'e' davvero una riga [kind], il centro non e' sul suo bordo, la griglia non e' in
   * fondo e la posizione salvata non e' "sui piu' recenti".
   */
  private fun centerOn(grid: MediaGridState, kind: RowKind) {
    val (row, offset) = compose.runOnIdle {
      val layout = checkNotNull(grid.layout)
      val half = contentHeight(grid) / 2f
      val target = (layout.rowCount / 3 until layout.rowCount).first { r ->
        layout.rowKind(r) == kind && layout.rowHeight(r) > 4 * EDGE_PX &&
          layout.rowTop(r) - half >= 0 && layout.rowTop(r) + layout.rowHeight(r) + 2 * half < layout.totalHeight
      }
      val scroll = TimelineGridAnchors.at(layout, (layout.rowTop(target) + layout.rowHeight(target) * IN_ROW - half).roundToInt())
      scroll.row to scroll.offsetPx
    }
    compose.runOnIdle { grid.listState.requestScrollToItem(row, offset) }
    compose.waitForIdle()
    val c = center(grid)
    assertThat(c.kind).isEqualTo(kind)
    assertThat(c.yInRow).isGreaterThan(EDGE_PX.toFloat())
    assertThat(c.yInRow).isLessThan((c.height - EDGE_PX).toFloat())
    compose.runOnIdle {
      assertThat(grid.listState.canScrollForward).isTrue()
      assertThat(checkNotNull(grid.savePosition()).atNewest).isFalse()
    }
  }

  /** La posizione della lista e quella che la griglia salverebbe adesso. */
  private data class Place(val index: Int, val offset: Int, val saved: SavedGridPosition)

  private fun place(grid: MediaGridState): Place = compose.runOnIdle {
    Place(grid.listState.firstVisibleItemIndex, grid.listState.firstVisibleItemScrollOffset, checkNotNull(grid.savePosition()))
  }

  private fun assertSamePlace(before: Place, after: Place) {
    assertThat(after.index).isEqualTo(before.index)
    assertThat(abs(after.offset - before.offset)).isAtMost(1)
    assertThat(after.saved).isEqualTo(before.saved)
  }

  private enum class Via { SEARCH, ALBUMS, COLLECTIONS }

  /** Si lascia la Libreria (Cerca, Album o Raccolte) e si torna. La pagina della Libreria esce davvero dalla composizione (niente menu "Vista"). */
  private fun leaveAndReturn(via: Via) {
    when (via) {
      Via.SEARCH -> searchOpen = true
      Via.ALBUMS -> tab = GalleryTab.Albums
      Via.COLLECTIONS -> tab = GalleryTab.Collections
    }
    compose.waitForIdle()
    compose.onNodeWithContentDescription(VIEW_MENU).assertDoesNotExist()
    if (via == Via.SEARCH) searchOpen = false else tab = GalleryTab.Library
    compose.waitForIdle()
    compose.onNodeWithContentDescription(VIEW_MENU).assertExists()
  }

  private fun assertStaysPut(level: GridLevel, kind: RowKind, via: Via) {
    val scenes = ScaffoldScenes(library = syntheticLibraryTabState(level = level))
    show(scenes)
    val grid = scenes.library.grid
    assertThat(grid.level).isEqualTo(level)
    centerOn(grid, kind)
    val before = place(grid)
    leaveAndReturn(via)
    assertSamePlace(before, place(grid))
  }

  // --- Il ritorno da Cerca e dalle altre schede ------------------------------------------------------------------------------------------------------

  @Test
  fun conIlCentroSuUnIntestazione_dopoCercaLaLibreriaNonSiMuove() = assertStaysPut(GridLevel.DAYS, RowKind.HEADER, Via.SEARCH)

  @Test
  fun conIlCentroSuUnIntestazione_dopoAlbumLaLibreriaNonSiMuove() = assertStaysPut(GridLevel.DAYS, RowKind.HEADER, Via.ALBUMS)

  @Test
  fun conIlCentroDentroUnaFoto_dopoCercaLaLibreriaNonSiMuove() = assertStaysPut(GridLevel.DAYS, RowKind.CELLS, Via.SEARCH)

  @Test
  fun conIlCentroDentroUnaFoto_dopoAlbumLaLibreriaNonSiMuove() = assertStaysPut(GridLevel.DAYS, RowKind.CELLS, Via.ALBUMS)

  @Test
  fun aiMesi_conIlCentroSuUnIntestazione_dopoRaccolteLaLibreriaNonSiMuove() = assertStaysPut(GridLevel.MONTHS, RowKind.HEADER, Via.COLLECTIONS)

  @Test
  fun agliAnni_conIlCentroSuUnIntestazione_dopoCercaLaLibreriaNonSiMuove() = assertStaysPut(GridLevel.YEARS, RowKind.HEADER, Via.SEARCH)

  @Test
  fun aTutte_conIlCentroDentroUnaFoto_dopoAlbumLaLibreriaNonSiMuove() = assertStaysPut(GridLevel.ALL, RowKind.CELLS, Via.ALBUMS)

  // --- La ricreazione dell'activity -----------------------------------------------------------------------------------------------------------------

  /** Tema, lingua, densita': l'activity si ricrea e il ViewModel resta. La stessa griglia, e non si muove nemmeno con il centro su un'intestazione. */
  @Test
  fun dopoUnaRicreazioneConLaGrigliaVivaLaLibreriaNonSiMuove() {
    val scenes = ScaffoldScenes()
    show(scenes)
    val grid = scenes.library.grid
    centerOn(grid, RowKind.HEADER)
    val before = place(grid)
    recreate(scenes)
    compose.onNodeWithContentDescription(VIEW_MENU).assertExists()
    assertSamePlace(before, place(grid))
  }

  /** Il processo ripartito: una griglia nuova riceve la posizione salvata (E-P18) e torna sulla stessa foto. */
  @Test
  fun dopoUnaRicreazioneConUnaGrigliaNuovaSiTornaSullaStessaFoto() {
    val first = ScaffoldScenes()
    show(first)
    centerOn(first.library.grid, RowKind.CELLS)
    val before = place(first.library.grid)
    val second = ScaffoldScenes()
    recreate(second)
    val after = place(second.library.grid)
    assertThat(after.saved.atNewest).isFalse()
    assertThat(after.saved.mediaId).isEqualTo(before.saved.mediaId)
  }

  /** K3a: l'activity si ricrea (processo ripartito) mentre la Libreria e' coperta dalla scheda Album; tornando, la griglia nuova e' sulla stessa foto. */
  @Test
  fun laLibreriaCopertaDaUnAltraSchedaRitrovaLaFotoDopoUnaRicreazione() {
    val first = ScaffoldScenes()
    show(first)
    centerOn(first.library.grid, RowKind.CELLS)
    val before = place(first.library.grid)
    tab = GalleryTab.Albums
    compose.waitForIdle()
    val second = ScaffoldScenes()
    recreate(second)
    compose.onNodeWithContentDescription(VIEW_MENU).assertDoesNotExist()
    tab = GalleryTab.Library
    compose.waitForIdle()
    val after = place(second.library.grid)
    assertThat(after.saved.atNewest).isFalse()
    assertThat(after.saved.mediaId).isEqualTo(before.saved.mediaId)
  }

  private companion object {
    /** Il menu della vista nella chrome in alto della Libreria: c'e' solo se la pagina della Libreria e' in composizione. */
    const val VIEW_MENU = "Vista"

    /** Dove cade il centro nella riga scelta: ne' sul bordo alto (dove l'ancora di un'intestazione sarebbe neutra) ne' su quello basso. */
    const val IN_ROW = 0.45f

    /** Il margine dai bordi della riga entro cui il centro non deve cadere (px). */
    const val EDGE_PX = 4
  }
}
