package dev.pampa.mycloudgallery.feature.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.nas.folders.FolderWhere
import dev.pampa.mycloudgallery.feature.nas.folders.HiddenFiles
import dev.pampa.mycloudgallery.feature.nas.folders.NasFoldersViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Fase 7 (P9, D23): in Cartelle NAS **i file nascosti nella Libreria non si elencano per nome** (I8 della revisione 4B): al loro posto una riga
 * "N elementi nascosti". Fallire chiusi: finche' `:app` non ha detto quali file sono nascosti, e se non riesce a dirlo, nessun file si elenca
 * (le cartelle si'). Il NAS e' quello finto di core-nasops; i nascosti li decide `FakeNasAreas` (il vero li legge dal catalogo).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NasHiddenFilesTest {

  private val dispatcher = StandardTestDispatcher()
  private val kit = NasOpsTestKit()
  private val areas = FakeNasAreas()
  private val here = FolderWhere("test", SharePath.of("Viaggi"))

  @Before
  fun setUp() = Dispatchers.setMain(dispatcher)

  @After
  fun tearDown() = Dispatchers.resetMain()

  private fun viewModel() = NasFoldersViewModel(kit.folders, kit.operations, areas, kit.clocks)

  private fun TestScope.settle() = runCurrent()

  private fun populate() {
    kit.putDir("test", "Viaggi/Oslo")
    kit.putFile("test", "Viaggi/mappa.png", byteArrayOf(1))
    kit.putFile("test", "Viaggi/Compleanno_Luca_segreto.jpg", byteArrayOf(2, 2))
    kit.putFile("test", "Viaggi/Tessera_sanitaria.jpg", byteArrayOf(3, 3, 3))
    kit.putFile("test", "Viaggi/biglietti.pdf", byteArrayOf(4))
    areas.hiddenPaths += "Viaggi/Compleanno_Luca_segreto.jpg"
    areas.hiddenPaths += "Viaggi/Tessera_sanitaria.jpg"
  }

  @Test
  fun iNomiDeiNascostiNonSonoMaiNelloStatoEUnaRigaDiceQuantiSono() = runTest(dispatcher) {
    populate()
    val vm = viewModel()

    vm.goTo(here)
    settle()

    val state = vm.state.value
    assertThat(state.entries.map { it.name }).containsExactly("Oslo", "biglietti.pdf", "mappa.png").inOrder()
    assertThat(state.hiddenCount).isEqualTo(2)
    assertThat(state.hiddenUnknown).isFalse()
    assertThat(state.entries.map { it.name }.joinToString()).doesNotContain("segreto")
    assertThat(state.entries.map { it.name }.joinToString()).doesNotContain("Tessera")
    // Solo i file vengono chiesti ad `:app`: una cartella non e' un media e il suo nome non e' nascosto.
    val asked = areas.hiddenFileAsks.last().second.map { it.value }
    assertThat(asked).containsExactly("Viaggi/mappa.png", "Viaggi/Compleanno_Luca_segreto.jpg", "Viaggi/Tessera_sanitaria.jpg", "Viaggi/biglietti.pdf")
  }

  @Test
  fun fincheNonSiSaQualiSonoNascostiNessunFileSiElenca() = runTest(dispatcher) {
    populate()
    areas.hiddenFilesGate = CompletableDeferred()
    val vm = viewModel()

    vm.goTo(here)
    settle()

    // L'elenco e' arrivato, `:app` non ha ancora risposto: le cartelle si vedono, i file no.
    val waiting = vm.state.value
    assertThat(waiting.hidden).isEqualTo(HiddenFiles.Checking)
    assertThat(waiting.entries.map { it.name }).containsExactly("Oslo")
    assertThat(waiting.checkingHidden).isTrue()
    assertThat(waiting.hiddenCount).isEqualTo(0)

    areas.hiddenFilesGate!!.complete(Unit)
    settle()

    assertThat(vm.state.value.checkingHidden).isFalse()
    assertThat(vm.state.value.entries.map { it.name }).containsExactly("Oslo", "biglietti.pdf", "mappa.png")
  }

  @Test
  fun seIlCatalogoNonRispondeNessunFileSiElencaELaPaginaLoDice() = runTest(dispatcher) {
    populate()
    areas.failing = true
    val vm = viewModel()

    vm.goTo(here)
    settle()

    val state = vm.state.value
    assertThat(state.hidden).isEqualTo(HiddenFiles.Unknown)
    assertThat(state.hiddenUnknown).isTrue()
    assertThat(state.entries.map { it.name }).containsExactly("Oslo")
    assertThat(state.hiddenCount).isEqualTo(0)
  }

  @Test
  fun unElencoRinnovatoRipartedaNonSoNienteENonFaLampeggiareIlNome() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(here)
    settle()
    assertThat(vm.state.value.hiddenCount).isEqualTo(2)

    // Un file nuovo e nascosto compare e l'elenco si rilegge: finche' `:app` non risponde, nessun file (nemmeno quelli di prima).
    kit.putFile("test", "Viaggi/Nuovo_segreto.jpg", byteArrayOf(5))
    areas.hiddenPaths += "Viaggi/Nuovo_segreto.jpg"
    areas.hiddenFilesGate = CompletableDeferred()
    vm.refresh()
    settle()

    assertThat(vm.state.value.entries.map { it.name }).containsExactly("Oslo")
    assertThat(vm.state.value.entries.joinToString { it.name }).doesNotContain("segreto")

    areas.hiddenFilesGate!!.complete(Unit)
    settle()
    assertThat(vm.state.value.hiddenCount).isEqualTo(3)
    assertThat(vm.state.value.entries.map { it.name }).containsExactly("Oslo", "biglietti.pdf", "mappa.png")
  }

  @Test
  fun selezionareTuttoENonPrendeMaIFileNascosti() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(here)
    settle()

    vm.selectAll()

    assertThat(vm.state.value.selection.map { it.value }).containsExactly("Viaggi/Oslo", "Viaggi/biglietti.pdf", "Viaggi/mappa.png")
    assertThat(vm.state.value.selectedItems().map { it.path.value }).containsNoneOf("Viaggi/Compleanno_Luca_segreto.jpg", "Viaggi/Tessera_sanitaria.jpg")
  }

  @Test
  fun seTuttiIFileSonoNascostiResta_LaRigaENonLaCartellaVuota() = runTest(dispatcher) {
    kit.putFile("test", "Viaggi/a.jpg", byteArrayOf(1))
    kit.putFile("test", "Viaggi/b.jpg", byteArrayOf(2))
    areas.hiddenPaths += setOf("Viaggi/a.jpg", "Viaggi/b.jpg")
    val vm = viewModel()

    vm.goTo(here)
    settle()

    val state = vm.state.value
    assertThat(state.entries).isEmpty()
    assertThat(state.hiddenCount).isEqualTo(2)
    assertThat(state.checkingHidden).isFalse()
  }

  @Test
  fun unaCartellaSenzaFileNonChiedeNiente() = runTest(dispatcher) {
    kit.putDir("test", "Viaggi/Oslo")
    val vm = viewModel()

    vm.goTo(here)
    settle()

    assertThat(areas.hiddenFileAsks).isEmpty()
    assertThat(vm.state.value.hidden).isEqualTo(HiddenFiles.Known(emptySet()))
    assertThat(vm.state.value.entries.map { it.name }).containsExactly("Oslo")
  }

  @Test
  fun inUnaAltraCartellaLaRispostaVecchiaNonConta() = runTest(dispatcher) {
    populate()
    kit.putFile("test", "Altro/x.jpg", byteArrayOf(9))
    areas.hiddenFilesGate = CompletableDeferred()
    val vm = viewModel()
    vm.goTo(here)
    settle()
    // Si va altrove mentre la risposta per "Viaggi" e' ancora in attesa.
    areas.hiddenFilesGate = null
    vm.goTo(FolderWhere("test", SharePath.of("Altro")))
    settle()

    assertThat(vm.state.value.where).isEqualTo(FolderWhere("test", SharePath.of("Altro")))
    assertThat(vm.state.value.entries.map { it.name }).containsExactly("x.jpg")
    assertThat(vm.state.value.hiddenCount).isEqualTo(0)
  }
}
