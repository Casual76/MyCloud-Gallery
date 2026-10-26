package dev.pampa.mycloudgallery.feature.nas

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderState
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.nas.folders.FolderWhere
import dev.pampa.mycloudgallery.feature.nas.folders.FoldersActions
import dev.pampa.mycloudgallery.feature.nas.folders.FoldersUiState
import dev.pampa.mycloudgallery.feature.nas.folders.HiddenFiles
import dev.pampa.mycloudgallery.feature.nas.folders.NasFoldersScreen
import dev.pampa.mycloudgallery.feature.nas.folders.NasFoldersViewModel
import dev.pampa.mycloudgallery.feature.nas.operations.NasOperationsScreen
import dev.pampa.mycloudgallery.feature.nas.operations.NasOperationsViewModel
import dev.pampa.mycloudgallery.feature.nas.operations.OperationsUi
import dev.pampa.mycloudgallery.feature.nas.contract.NasArea
import dev.pampa.mycloudgallery.feature.nas.contract.NasAreaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * La verifica funzionale delle fasi 1-5A (docs/review/funzionale-fasi-1-5a.md), il NAS nella Libreria e l'organizzazione, lato schermate:
 * NA-3 ("Aggiorna" in Cartelle NAS), NA-10 (escludere dalla Libreria una cartella dell'area privata) e NA-11 ("L'ora del NAS e' giusta,
 * continua"). Ogni gesto nasce da un tocco su cio' che l'utente ha davanti.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VerificaFunzionaleNasTest : ComposeNasTest() {

  private val dispatcher = StandardTestDispatcher()
  private val areas = FakeNasAreas()

  @Before
  fun setUp() = Dispatchers.setMain(dispatcher)

  @After
  fun tearDown() = Dispatchers.resetMain()

  private fun folders(
    where: FolderWhere = FolderWhere("test", SharePath.of("Viaggi")),
    selecting: Boolean = false,
    selection: Set<SharePath> = emptySet(),
    exclusions: Set<SharePath> = emptySet(),
    areaList: List<NasArea> = FakeNasAreas.defaultAreas(),
  ) = FoldersUiState(
    areas = areaList,
    where = where,
    listing = NasFolderState.Listing(NasFixtures.tripEntries(), readOnly = false, cachedAt = null),
    selecting = selecting,
    selection = selection,
    exclusions = exclusions,
    hidden = HiddenFiles.Known(emptySet()),
  )

  // --- NA-3 ----------------------------------------------------------------------------------------------------------------------

  /** NA-3: c'era un elenco che non si aggiornava e nessun gesto per aggiornarlo. */
  @Test
  fun na3_cartelleHaIlGestoAggiorna() {
    var refreshes = 0
    show { NasFoldersScreen(folders(), FoldersActions(onRetry = { refreshes++ }), { 0L }, onBack = {}) }

    composeRule.onNodeWithContentDescription("Aggiorna").assertIsEnabled().performClick()

    assertThat(refreshes).isEqualTo(1)
  }

  @Test
  fun na3_laPaginaDelleAreeNonHaNienteDaAggiornare() {
    show { NasFoldersScreen(folders().copy(where = null), FoldersActions(), { 0L }, onBack = {}) }

    composeRule.onNodeWithContentDescription("Aggiorna").assertDoesNotExist()
  }

  @Test
  fun na3_mentreLegge_ilGestoSiSpegne() {
    show { NasFoldersScreen(folders().copy(listing = NasFolderState.Loading), FoldersActions(), { 0L }, onBack = {}) }

    composeRule.onNodeWithContentDescription("Aggiorna").assertIsNotEnabled()
  }

  // --- NA-10 ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun na10_unaCartellaDellAreaPrivataSiPuoEscludereDallaLibreria() {
    var toggles = 0
    val oslo = NasFixtures.tripEntries()[0].path
    show {
      NasFoldersScreen(folders(selecting = true, selection = setOf(oslo)), FoldersActions(onToggleExcluded = { toggles++ }), { 0L }, onBack = {})
    }

    composeRule.onNodeWithContentDescription("Escludi dalla Libreria").assertIsEnabled().performClick()

    assertThat(toggles).isEqualTo(1)
  }

  @Test
  fun na10_unaCartellaGiaEsclusaSiIncludeDiNuovo_eLaRigaLoDice() {
    val oslo = NasFixtures.tripEntries()[0].path
    show { NasFoldersScreen(folders(selecting = true, selection = setOf(oslo), exclusions = setOf(oslo)), FoldersActions(), { 0L }, onBack = {}) }

    composeRule.onNodeWithContentDescription("Includi nella Libreria").assertIsEnabled()
    composeRule.onNodeWithContentDescription("Escludi dalla Libreria").assertDoesNotExist()
    composeRule.onNodeWithText("Esclusa dalla Libreria").assertExists()
  }

  @Test
  fun na10_unFileNonSiEscludeDallaLibreria() {
    val file = NasFixtures.tripEntries()[2].path
    // Un file nell'area privata: il gesto c'e' ma e' spento (si escludono cartelle).
    show { NasFoldersScreen(folders(selecting = true, selection = setOf(file)), FoldersActions(), { 0L }, onBack = {}) }
    composeRule.onNodeWithContentDescription("Escludi dalla Libreria").assertIsNotEnabled()
  }

  @Test
  fun na10_nellAreaComuneIlGestoNonCe() {
    val oslo = NasFixtures.tripEntries()[0].path
    show {
      NasFoldersScreen(folders(where = FolderWhere("test-comune", SharePath.of("Viaggi")), selecting = true, selection = setOf(oslo)), FoldersActions(), { 0L }, onBack = {})
    }
    composeRule.onNodeWithContentDescription("Escludi dalla Libreria").assertDoesNotExist()
  }

  private fun foldersViewModel(kit: NasOpsTestKit) =
    NasFoldersViewModel(kit.folders, kit.operations, areas, kit.clocks)

  @Test
  fun na10_escludereSalvaLaScelta_eIncludereLaTogli() = runTest(dispatcher) {
    val kit = NasOpsTestKit()
    kit.putFile("test", "Viaggi/a.jpg", byteArrayOf(1))
    kit.putFile("test", "nota.txt", byteArrayOf(2))
    val vm = foldersViewModel(kit)
    vm.goTo(FolderWhere("test", null))
    runCurrent()
    vm.toggle(SharePath.of("Viaggi"))
    assertThat(vm.state.value.inPrivateArea).isTrue()
    assertThat(vm.state.value.selectionExcluded).isFalse()

    vm.toggleExcluded()
    runCurrent()

    assertThat(areas.privateExclusions.value).containsExactly(SharePath.of("Viaggi"))
    assertThat(areas.verifies).isEqualTo(1)
    assertThat(vm.state.value.exclusions).containsExactly(SharePath.of("Viaggi"))
    assertThat(vm.state.value.notice?.text).isEqualTo(R.string.nas_folders_excluded)
    assertThat(vm.state.value.selection).isEmpty()

    vm.toggle(SharePath.of("Viaggi"))
    assertThat(vm.state.value.selectionExcluded).isTrue()
    vm.toggleExcluded()
    runCurrent()
    assertThat(areas.privateExclusions.value).isEmpty()
    assertThat(vm.state.value.notice?.text).isEqualTo(R.string.nas_folders_included)
  }

  @Test
  fun na10_ilGestoNonFaNienteSuUnFileOnellAreaComune() = runTest(dispatcher) {
    val kit = NasOpsTestKit()
    kit.putFile("test", "nota.txt", byteArrayOf(2))
    kit.putFile("test-comune", "Famiglia/b.jpg", byteArrayOf(3))
    val vm = foldersViewModel(kit)
    vm.goTo(FolderWhere("test", null))
    runCurrent()
    vm.toggle(SharePath.of("nota.txt"))
    vm.toggleExcluded()
    runCurrent()
    assertThat(areas.privateExclusions.value).isEmpty()

    vm.goTo(FolderWhere("test-comune", null))
    runCurrent()
    vm.toggle(SharePath.of("Famiglia"))
    assertThat(vm.state.value.inPrivateArea).isFalse()
    vm.toggleExcluded()
    runCurrent()
    assertThat(areas.privateExclusions.value).isEmpty()
    assertThat(areas.verifies).isEqualTo(0)
  }

  @Test
  fun na10_seNonRiesceAScrivereLoDice() = runTest(dispatcher) {
    val kit = NasOpsTestKit()
    kit.putFile("test", "Viaggi/a.jpg", byteArrayOf(1))
    areas.exclusionsFail = true
    val vm = foldersViewModel(kit)
    vm.goTo(FolderWhere("test", null))
    runCurrent()
    vm.toggle(SharePath.of("Viaggi"))

    vm.toggleExcluded()
    runCurrent()

    assertThat(vm.state.value.notice?.text).isEqualTo(R.string.nas_folders_exclude_failed)
    assertThat(areas.privateExclusions.value).isEmpty()
  }

  @Test
  fun na10_senzaAreaPrivataNonSalvaNiente() = runTest(dispatcher) {
    val kit = NasOpsTestKit()
    kit.putFile("test", "Viaggi/a.jpg", byteArrayOf(1))
    areas.set(listOf(NasArea("test", NasAreaKind.COMMON)))
    val vm = foldersViewModel(kit)
    vm.goTo(FolderWhere("test", null))
    runCurrent()
    vm.toggle(SharePath.of("Viaggi"))
    assertThat(vm.state.value.inPrivateArea).isFalse()
    assertThat(areas.setPrivateExclusions(setOf(SharePath.of("Viaggi")))).isFalse()
  }

  // --- NA-11 ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun na11_ilSaltoDellOraDiceCosaSuccedeEOffreIlGesto() {
    var asked = 0
    show { NasOperationsScreen(OperationsUi(clockJumpMs = 3_600_000L), onStop = {}, onAskClock = { asked++ }) }

    composeRule.onNodeWithText("L’ora del NAS è cambiata di colpo").assertExists()
    composeRule.onNodeWithText("L’ora del NAS è giusta, continua").performClick()

    assertThat(asked).isEqualTo(1)
  }

  @Test
  fun na11_senzaSaltoNonCeNiente() {
    show { NasOperationsScreen(OperationsUi(), onStop = {}) }

    composeRule.onNodeWithText("L’ora del NAS è giusta, continua").assertDoesNotExist()
  }

  @Test
  fun na11_ilGestoVuoleUnSecondoTocco() {
    var accepted = 0
    var dismissed = 0
    show {
      NasOperationsScreen(
        OperationsUi(clockJumpMs = 3_600_000L, askingClock = true),
        onStop = {},
        onAcceptClock = { accepted++ },
        onDismissClock = { dismissed++ },
      )
    }

    composeRule.onNodeWithText("L’ora del NAS è giusta?").assertExists()
    assertThat(accepted).isEqualTo(0)
    composeRule.onNodeWithText("Aspetta").performClick()
    assertThat(dismissed).isEqualTo(1)
    assertThat(accepted).isEqualTo(0)
    composeRule.onNodeWithText("Sì, l’ora è giusta").performClick()
    assertThat(accepted).isEqualTo(1)
  }

  @Test
  fun na11_ilViewModelAccettaSoloDopoIlFoglio() = runTest(dispatcher) {
    val kit = NasOpsTestKit()
    areas.jumpTo(3_600_000L)
    val vm = NasOperationsViewModel(kit.operations, areas)
    val collecting = backgroundScope.launch { vm.state.collect { } }
    runCurrent()
    assertThat(vm.state.value.clockJumpMs).isEqualTo(3_600_000L)
    assertThat(vm.state.value.askingClock).isFalse()

    vm.askClock()
    runCurrent()
    assertThat(vm.state.value.askingClock).isTrue()
    assertThat(areas.clockAccepted).isEqualTo(0)

    vm.dismissClock()
    runCurrent()
    assertThat(vm.state.value.askingClock).isFalse()
    assertThat(areas.clockAccepted).isEqualTo(0)

    vm.askClock()
    vm.acceptClock()
    runCurrent()
    assertThat(areas.clockAccepted).isEqualTo(1)
    assertThat(vm.state.value.clockJumpMs).isNull()
    assertThat(vm.state.value.askingClock).isFalse()
    collecting.cancel()
  }
}
