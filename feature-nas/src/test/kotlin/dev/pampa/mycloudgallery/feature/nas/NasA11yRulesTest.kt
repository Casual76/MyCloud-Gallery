package dev.pampa.mycloudgallery.feature.nas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.onRoot
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yAssertions
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yConfig
import dev.pampa.mycloudgallery.core.designsystem.testing.A11yRule
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderState
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpaceState
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashState
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.nas.folders.FolderWhere
import dev.pampa.mycloudgallery.feature.nas.folders.FoldersActions
import dev.pampa.mycloudgallery.feature.nas.folders.FoldersUiState
import dev.pampa.mycloudgallery.feature.nas.folders.HiddenFiles
import dev.pampa.mycloudgallery.feature.nas.folders.NasFoldersScreen
import dev.pampa.mycloudgallery.feature.nas.operations.NasOperationsScreen
import dev.pampa.mycloudgallery.feature.nas.operations.operationsUi
import dev.pampa.mycloudgallery.feature.nas.space.NasSpaceScreen
import dev.pampa.mycloudgallery.feature.nas.space.SpaceUi
import dev.pampa.mycloudgallery.feature.nas.trash.NasTrashScreen
import dev.pampa.mycloudgallery.feature.nas.trash.TrashActions
import dev.pampa.mycloudgallery.feature.nas.trash.TrashUi
import dev.pampa.mycloudgallery.feature.nas.trash.key
import com.github.takahirom.roborazzi.checkRoboAccessibility
import org.junit.Test

/**
 * Fase 10, P8 (D23, D25-D27, §2.7 "Backup, NAS, Diagnostica", D28): le pagine del NAS lette come le legge TalkBack, con le regole comuni di
 * `A11yAssertions`: nome e ruolo per ogni elemento attivo, bersagli da 48 dp misurati, testo non tagliato, a testo 1,0 e 2,0.
 *
 * **Eccezione scritta**: nelle pagine che mostrano i file e le cartelle del NAS (Cartelle NAS, Eliminati di recente, e l'elenco dei file piu' grandi di Spazio sul NAS) il **nome del file e'
 * il contenuto** ("DJI_0142.mp4, video, 2,3 GB"), non un identificativo tecnico: la regola "nessun nome di file" (E-AC12) vale per le foto
 * e i video della Libreria, non per un esploratore di file. La regola dei nomi si esenta li', e solo li'.
 */
class NasA11yRulesTest : ComposeNasTest() {

  private val areas = FakeNasAreas.defaultAreas()

  private fun root(): SemanticsNode = composeRule.onRoot().fetchSemanticsNode()

  private fun config(fileNamesAreContent: Boolean = false) = A11yConfig(
    density = composeRule.density,
    exempt = { rule, _ -> fileNamesAreContent && rule == A11yRule.LEAK },
  )

  private fun folders(selecting: Boolean = false, listing: NasFolderState = NasFolderState.Listing(NasFixtures.tripEntries(), false, null), selection: Set<SharePath> = emptySet()) =
    FoldersUiState(
      areas = areas,
      where = FolderWhere("test", SharePath.of("Viaggi")),
      listing = listing,
      selecting = selecting,
      selection = selection,
      hidden = HiddenFiles.Known(emptySet()),
    )

  private fun trash(list: NasTrashState = NasTrashState.Ready(NasFixtures.trashItems(), NasFixtures.NOW, writable = true), selecting: Boolean = false, selection: Set<String> = emptySet()) =
    TrashUi(areas = areas, list = list, selecting = selecting, selection = selection, confirm = null, notice = null)

  private fun space(space: NasSpaceState) = SpaceUi(areas = areas, share = "test", space = space)

  /** La scala del testo di adesso: si cambia senza rimostrare la scena (`setContent` si puo' chiamare una volta per prova). */
  private val scale = mutableStateOf(1f)

  /** Mostra [content] e controlla le regole a testo 1,0 e poi a 2,0 sulla stessa scena. */
  private fun clean(name: String, fileNamesAreContent: Boolean = false, content: @Composable () -> Unit) {
    show {
      val d = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(d.density, scale.value)) { content() }
    }
    for (value in listOf(1f, 2f)) {
      scale.value = value
      composeRule.waitForIdle()
      val found = A11yAssertions.check(root(), config(fileNamesAreContent))
      assertWithMessage("$name a testo $value: " + found.joinToString(" | ")).that(found).isEmpty()
    }
  }

  private val entries get() = NasFixtures.tripEntries()

  private fun foldersScreen(state: FoldersUiState, onBack: (() -> Unit)? = {}): @Composable () -> Unit = {
    NasFoldersScreen(state, FoldersActions(), { 0L }, onBack = onBack)
  }

  // --- Cartelle NAS (i nomi dei file sono il contenuto) -------------------------------------------------------------------------

  @Test
  fun cartelleLeAree() =
    clean("le aree", fileNamesAreContent = true, content = foldersScreen(folders(listing = NasFolderState.Loading).copy(where = null), onBack = null))

  @Test fun cartelleUnaCartella() = clean("una cartella", fileNamesAreContent = true, content = foldersScreen(folders()))

  @Test
  fun cartelleInSelezione() =
    clean("in selezione", fileNamesAreContent = true, content = foldersScreen(folders(selecting = true, selection = setOf(entries[0].path, entries[2].path))))

  @Test
  fun cartelleNasNonRisponde() = clean("il NAS non risponde", fileNamesAreContent = true, content = foldersScreen(folders(listing = NasFolderState.Offline(null, null))))

  @Test
  fun cartelleErrore() = clean("errore", fileNamesAreContent = true, content = foldersScreen(folders(listing = NasFolderState.Error(OpErrorCode.UNEXPECTED))))

  @Test
  fun cartelleVuota() = clean("vuota", fileNamesAreContent = true, content = foldersScreen(folders(listing = NasFolderState.Listing(emptyList(), false, null))))

  // --- Eliminati di recente ---------------------------------------------------------------------------------------------------

  @Test
  fun eliminati() = clean("eliminati", fileNamesAreContent = true) { NasTrashScreen(trash(), TrashActions(), { 0L }, onBack = {}) }

  @Test
  fun eliminatiInSelezione() {
    val items = NasFixtures.trashItems()
    clean("eliminati in selezione", fileNamesAreContent = true) {
      NasTrashScreen(trash(selecting = true, selection = setOf(items[0].key(), items[1].key())), TrashActions(), { 0L }, onBack = {})
    }
  }

  @Test
  fun eliminatiVuoti() = clean("eliminati vuoti", fileNamesAreContent = true) {
    NasTrashScreen(trash(list = NasTrashState.Ready(emptyList(), NasFixtures.NOW, writable = true)), TrashActions(), { 0L }, onBack = {})
  }

  // --- Spazio e operazioni ----------------------------------------------------------------------------------------------------

  @Test
  fun spazio() = clean("spazio", fileNamesAreContent = true) {
    NasSpaceScreen(state = space(NasSpaceState.Ready(NasFixtures.space())), onSelectArea = {}, onReload = {}, onBack = {}, onOpenTrash = {}, initiallyExpanded = false)
  }

  @Test
  fun spazioNonDisponibile() = clean("spazio non disponibile", fileNamesAreContent = true) {
    NasSpaceScreen(
      state = space(NasSpaceState.Unavailable(OpErrorCode.UNREACHABLE, NasFixtures.largest(6))),
      onSelectArea = {}, onReload = {}, onBack = {}, onOpenTrash = {}, initiallyExpanded = false,
    )
  }

  @Test
  fun operazioni() = clean("operazioni") { NasOperationsScreen(state = operationsUi(NasFixtures.operations()), onStop = {}, onBack = {}) }

  // --- La semantica attesa del §2.7 -------------------------------------------------------------------------------------------

  @Test
  fun leIntestazioniDelleSezioniSiLeggonoComeIntestazioni() {
    show {
      NasSpaceScreen(state = space(NasSpaceState.Ready(NasFixtures.space())), onSelectArea = {}, onReload = {}, onBack = {}, onOpenTrash = {}, initiallyExpanded = false)
    }
    assertThat(A11yAssertions.headingNames(root())).isNotEmpty()
  }

  // --- ATF (D28): il controllo automatico indipendente, sulle schermate principali del modulo -----------------------------------------

  @Test
  fun atfUnaCartella() {
    show(content = foldersScreen(folders()))
    composeRule.onRoot().checkRoboAccessibility()
  }

  @Test
  fun atfSpazio() {
    show {
      NasSpaceScreen(state = space(NasSpaceState.Ready(NasFixtures.space())), onSelectArea = {}, onReload = {}, onBack = {}, onOpenTrash = {}, initiallyExpanded = false)
    }
    composeRule.onRoot().checkRoboAccessibility()
  }
}

