package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.testing.FakeTimelineRepository
import dev.pampa.mycloudgallery.core.library.testing.InMemoryTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.testing.InMemoryTimelineView
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.feature.library.grid.RegisterComponentActivity
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FAB-2 della review generale: **una foto arrivata in cima durante la selezione faceva parlare il foglio della foto vicina**.
 *
 * `unsafeAmong`, `hiddenWithGroups` e `isFavorite` chiedevano la posizione del media a `MediaGridState.indexOf` (l'indice **mostrato**, o la
 * memoria delle celle composte) e con quella leggevano i flag dell'indice **nuovo**. Con una riga in piu' in cima le posizioni sono scalate di una:
 * il foglio "Elimina dal telefono" diceva «Ha una copia al sicuro sul NAS» per una foto che non ce l'ha, "Preferito" si rovesciava, "Nascondi"
 * contava il gruppo della vicina. Ora la posizione si cerca nell'indice da cui si leggono i flag.
 *
 * Scena: l'indice ha, in cima, le celle 0..5; la **vicina** (cella 2) e' al sicuro, preferita e copertina di un gruppo di 5; la **scelta** (cella 3) e'
 * solo sul telefono, non preferita, e non fa gruppo. Poi arriva una foto nuova in cima e tutte scalano di una riga.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class LibraryIndexShiftTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private class Commands : LibraryCommands {
    override val canDeleteFromPhone: Boolean get() = true
  }

  /** La Libreria della scena: le celle 0..5 hanno i flag e i gruppi descritti sopra; il resto e' quello sintetico. */
  private fun scene(): LibraryTabState = syntheticLibraryState(count = 60, edit = { snapshot ->
    var out = snapshot
    for (i in 0 until 6) {
      val base = out.flagsAt(i) and (PRESENCE or CellFlags.FAVORITE).inv()
      out = out.withFlags(i, base or presenceOf(i))
    }
    out
  }).let { first ->
    LibraryTabState(
      grid = first.grid, viewerItems = first.viewerItems, viewerImages = first.viewerImages, photoCount = 0, videoCount = 0,
      timeline = first.timeline, preferences = first.preferences, phone = first.phone, commands = Commands(), actions = first.actions,
    )
  }

  private fun presenceOf(index: Int): Int = when (index) {
    NEIGHBOR -> CellFlags.ON_PHONE or CellFlags.ON_NAS or CellFlags.SAFE or CellFlags.FAVORITE
    else -> CellFlags.ON_PHONE
  }

  /** Lo stesso indice con una foto in piu' **in cima** (la piu' recente): ogni cella di prima scala di una posizione. */
  private fun InMemoryTimelineSnapshot.withNewestPhoto(): InMemoryTimelineSnapshot {
    val n = size + 1
    val msb = LongArray(n)
    val lsb = LongArray(n)
    val sortTime = LongArray(n)
    val localDay = IntArray(n)
    val kinds = ByteArray(n)
    val sources = ByteArray(n)
    val flags = IntArray(n)
    val aspect = IntArray(n)
    val duration = LongArray(n)
    val place = IntArray(n)
    val group = IntArray(n)
    msb[0] = NEW_MSB
    lsb[0] = NEW_LSB
    sortTime[0] = sortTimeAt(0) + 1_000L
    localDay[0] = localDayAt(0)
    kinds[0] = MediaKind.PHOTO.ordinal.toByte()
    sources[0] = MediaSource.CAMERA.ordinal.toByte()
    flags[0] = CellFlags.ON_PHONE
    aspect[0] = 1333
    group[0] = 1
    for (i in 0 until size) {
      val uuid = UUID.fromString(idAt(i).value)
      msb[i + 1] = uuid.mostSignificantBits
      lsb[i + 1] = uuid.leastSignificantBits
      sortTime[i + 1] = sortTimeAt(i)
      localDay[i + 1] = localDayAt(i)
      kinds[i + 1] = kindAt(i).ordinal.toByte()
      sources[i + 1] = sourceAt(i).ordinal.toByte()
      flags[i + 1] = flagsAt(i)
      aspect[i + 1] = aspectMilliAt(i)
      duration[i + 1] = durationMsAt(i)
      place[i + 1] = placeAt(i)
      // Le celle della scena: solo la vicina e' la copertina di un gruppo di 5; l'indice sintetico non deve decidere per noi.
      group[i + 1] = if (i < 6) (if (i == NEIGHBOR) 5 else 1) else groupSizeAt(i)
    }
    return InMemoryTimelineSnapshot(seq + 1, msb, lsb, sortTime, localDay, kinds, sources, flags, aspect, duration, place, group)
  }

  private fun idOf(state: LibraryTabState, index: Int): MediaId = state.grid.view.snapshot.idAt(index)

  // --- Le funzioni che leggono i flag -------------------------------------------------------------------------------------------

  /** Come dopo il disegno: la griglia ricorda le posizioni **vecchie** (le celle composte), poi arriva l'indice con la riga in piu'. */
  @Test
  fun unaFotoArrivataInCimaNonSpostaLaLetturaDeiFlag_conLaMemoriaDelleCelle() {
    val state = scene()
    val grid = state.grid
    val chosen = idOf(state, CHOSEN)
    val neighbor = idOf(state, NEIGHBOR)
    grid.noteIndex(neighbor, NEIGHBOR)
    grid.noteIndex(chosen, CHOSEN)

    grid.view = InMemoryTimelineView((grid.view.snapshot as InMemoryTimelineSnapshot).withNewestPhoto(), LibraryFilter.ALL)

    assertAboutTheChosenPhoto(state, chosen, neighbor)
  }

  /** Senza memoria (la cella non e' mai stata composta) la risposta e' quella dell'indice mostrato, che e' ancora il vecchio: stesso difetto. */
  @Test
  fun unaFotoArrivataInCimaNonSpostaLaLetturaDeiFlag_conLIndiceMostratoIndietro() {
    val state = scene()
    val chosen = idOf(state, CHOSEN)
    val neighbor = idOf(state, NEIGHBOR)

    state.grid.view = InMemoryTimelineView((state.grid.view.snapshot as InMemoryTimelineSnapshot).withNewestPhoto(), LibraryFilter.ALL)

    assertAboutTheChosenPhoto(state, chosen, neighbor)
  }

  private fun assertAboutTheChosenPhoto(state: LibraryTabState, chosen: MediaId, neighbor: MediaId) {
    val grid = state.grid
    // L'avviso di "Elimina dal telefono": la scelta non e' al sicuro (la vicina si').
    assertThat(grid.unsafeAmong(setOf(chosen))).isEqualTo(1)
    assertThat(grid.unsafeAmong(setOf(neighbor))).isEqualTo(0)
    assertThat(grid.unsafeAmong(setOf(chosen, neighbor))).isEqualTo(1)
    // "Preferito" non si rovescia.
    assertThat(grid.isFavorite(chosen)).isFalse()
    assertThat(grid.isFavorite(neighbor)).isTrue()
    // Il conteggio di "Nascondi" e' quello del gruppo della foto scelta (1), non della vicina (5).
    assertThat(grid.hiddenWithGroups(setOf(chosen))).isEqualTo(1)
    assertThat(grid.hiddenWithGroups(setOf(neighbor))).isEqualTo(5)
    assertThat(grid.hiddenWithGroups(setOf(chosen, neighbor))).isEqualTo(6)
  }

  /** Le azioni di TalkBack della cella leggono la stessa cosa: «Aggiungi ai preferiti» per la scelta, non «Togli» (che e' della vicina). */
  @Test
  fun leAzioniDiTalkBackDellaCellaParlanoDellaFotoScelta() {
    val state = scene()
    val chosen = idOf(state, CHOSEN)
    val neighbor = idOf(state, NEIGHBOR)
    state.grid.noteIndex(chosen, CHOSEN)
    state.grid.noteIndex(neighbor, NEIGHBOR)
    state.grid.view = InMemoryTimelineView((state.grid.view.snapshot as InMemoryTimelineSnapshot).withNewestPhoto(), LibraryFilter.ALL)
    val labels = LibraryCellLabels(favorite = "Preferito", unfavorite = "Togli", share = "Condividi", hide = "Nascondi", delete = "Elimina")
    val scope = CoroutineScope(Dispatchers.Unconfined)

    val ofChosen = libraryCellActions(state, chosen, labels, scope, onDelete = {}, onHideGroups = {})
    val ofNeighbor = libraryCellActions(state, neighbor, labels, scope, onDelete = {}, onHideGroups = {})

    assertThat(ofChosen.first().label).isEqualTo("Preferito")
    assertThat(ofNeighbor.first().label).isEqualTo("Togli")
  }

  // --- Il foglio e la barra, quelli veri ----------------------------------------------------------------------------------------

  private fun showLibrary(state: LibraryTabState) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) {
        val modalHost = rememberFluidGlassModalHostState()
        val backdrop = rememberGlassBackdrop()
        CompositionLocalProvider(LocalFluidGlassModalHostState provides modalHost) {
          Box(Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
            LibraryTab(
              contentPadding = PaddingValues(bottom = 96.dp),
              backdropOut = {},
              onOpenViewer = {},
              onOpenSettings = {},
              state = state,
            )
          }
          FluidGlassModalHost(state = modalHost, backdrop = backdrop)
        }
      }
    }
    composeRule.waitForIdle()
  }

  private fun shiftWhileSelecting(state: LibraryTabState) {
    val shifted = (state.grid.view.snapshot as InMemoryTimelineSnapshot).withNewestPhoto()
    composeRule.runOnIdle { (state.timeline as FakeTimelineRepository).publish(shifted) }
    composeRule.waitForIdle()
  }

  /** La selezione e' aperta, il foglio e' aperto, arriva una foto in cima: l'avviso resta quello della foto scelta (non al sicuro). */
  @Test
  fun ilFoglioEliminaDalTelefonoParlaDellaFotoSceltaAncheSeArrivaUnaFotoInCima() {
    val state = scene()
    val chosen = idOf(state, CHOSEN)
    showLibrary(state)
    composeRule.onNodeWithContentDescription("Seleziona").performClick()
    composeRule.waitForIdle()
    composeRule.runOnIdle { state.grid.selection.selectAll(listOf(chosen)) }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Elimina dal telefono").performClick()
    composeRule.waitForIdle()
    composeRule.onAllNodesWithText("Non c’è ancora una copia sul NAS", substring = true).onFirst().assertExists()

    shiftWhileSelecting(state)

    composeRule.onAllNodesWithText("Non c’è ancora una copia sul NAS", substring = true).onFirst().assertExists()
    composeRule.onAllNodesWithText("Ha una copia al sicuro", substring = true).assertCountEquals(0)
  }

  /** Con la selezione aperta, "Preferito" della barra resta quello della foto scelta (non preferita) anche se arriva una foto in cima. */
  @Test
  fun preferitoDellaBarraNonSiRovesciaSeArrivaUnaFotoInCima() {
    val state = scene()
    val chosen = idOf(state, CHOSEN)
    showLibrary(state)
    composeRule.onNodeWithContentDescription("Seleziona").performClick()
    composeRule.waitForIdle()
    composeRule.runOnIdle { state.grid.selection.selectAll(listOf(chosen)) }
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Aggiungi ai preferiti").assertExists()

    shiftWhileSelecting(state)

    composeRule.onNodeWithContentDescription("Aggiungi ai preferiti").assertExists()
    composeRule.onAllNodesWithContentDescription("Togli dai preferiti").assertCountEquals(0)
  }

  private companion object {
    const val NEIGHBOR = 2
    const val CHOSEN = 3
    const val NEW_MSB = 0x0FAB_0002L
    const val NEW_LSB = 0x0001_0203L
    const val PRESENCE: Int = CellFlags.ON_PHONE or CellFlags.ON_NAS or CellFlags.SAFE or CellFlags.TO_VERIFY
  }
}
