package dev.pampa.mycloudgallery.feature.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.nas.contract.NasFolderPreviews
import dev.pampa.mycloudgallery.feature.nas.contract.NasPreviewDose
import dev.pampa.mycloudgallery.feature.nas.contract.NasThumbnails
import dev.pampa.mycloudgallery.feature.nas.folders.FolderWhere
import dev.pampa.mycloudgallery.feature.nas.folders.NasFoldersViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * I4 (fase I): le anteprime di Cartelle NAS. Il ViewModel chiede a `:app` **una volta per elenco** che cosa la Libreria conosce dei file e delle
 * sottocartelle, tiene la risposta solo per l'elenco a cui si riferisce e, se la domanda fallisce, lascia le piastrelle (nessuna anteprima, l'elenco c'e').
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NasFolderPreviewsTest {

  private val dispatcher = StandardTestDispatcher()
  private val kit = NasOpsTestKit()
  private val areas = FakeNasAreas()

  @Before
  fun setUp() = Dispatchers.setMain(dispatcher)

  @After
  fun tearDown() = Dispatchers.resetMain()

  private fun viewModel() = NasFoldersViewModel(kit.folders, kit.operations, areas, kit.clocks)

  /** CG-2: la Libreria visibile finta (un segno che cambia) e il ViewModel che la osserva. */
  private val visible = MutableStateFlow<Any?>(0)

  private fun watchingViewModel() = NasFoldersViewModel(kit.folders, kit.operations, areas, kit.clocks, NasThumbnails(null, visible) { null })

  private fun populate() {
    kit.putFile("test", "Viaggi/Oslo/a.jpg", byteArrayOf(1, 2, 3))
    kit.putFile("test", "Viaggi/mappa.png", byteArrayOf(8))
    kit.putFile("test", "Viaggi/biglietti.pdf", byteArrayOf(9))
  }

  private val photo = MediaId("00000000-0000-4000-8000-000000000001")
  private val cover = MediaId("00000000-0000-4000-8000-000000000002")

  @Test
  fun unaDomandaPerElencoConIFileELeSottocartelle() = runTest(dispatcher) {
    populate()
    areas.previewAnswer = NasFolderPreviews(
      files = mapOf(SharePath.parse("Viaggi/mappa.png") to photo),
      folders = mapOf(SharePath.parse("Viaggi/Oslo") to listOf(cover)),
    )
    val vm = viewModel()
    vm.goTo(FolderWhere("test", SharePath.of("Viaggi")))
    runCurrent()

    assertThat(areas.previewAsks).hasSize(1)
    val (folder, files, folders) = areas.previewAsks.single()
    assertThat(folder).isEqualTo(SharePath.of("Viaggi"))
    assertThat(files).containsExactly(SharePath.parse("Viaggi/mappa.png"), SharePath.parse("Viaggi/biglietti.pdf"))
    assertThat(folders).containsExactly(SharePath.parse("Viaggi/Oslo"))
    assertThat(vm.state.value.previews).isEqualTo(areas.previewAnswer)
  }

  @Test
  fun unNuovoElencoRiparteSenzaAnteprimeEChiedeDiNuovo() = runTest(dispatcher) {
    populate()
    areas.previewAnswer = NasFolderPreviews(files = mapOf(SharePath.parse("Viaggi/mappa.png") to photo))
    val vm = viewModel()
    vm.goTo(FolderWhere("test", SharePath.of("Viaggi")))
    runCurrent()
    assertThat(vm.state.value.previews.files).isNotEmpty()

    areas.previewAnswer = NasFolderPreviews.None
    vm.goTo(FolderWhere("test", SharePath.parse("Viaggi/Oslo")))
    runCurrent()
    assertThat(areas.previewAsks).hasSize(2)
    assertThat(vm.state.value.previews).isEqualTo(NasFolderPreviews.None)
  }

  @Test
  fun seLaDomandaFallisceRestanoLePiastrelleELElenco() = runTest(dispatcher) {
    populate()
    areas.failing = true
    val vm = viewModel()
    vm.goTo(FolderWhere("test", SharePath.of("Viaggi")))
    runCurrent()
    assertThat(areas.previewAsks).hasSize(1)
    assertThat(vm.state.value.previews).isEqualTo(NasFolderPreviews.None)
    // Le cartelle si elencano comunque (i file no: con il catalogo muto non si sa quali sono nascosti, I8).
    assertThat(vm.state.value.entries.map { it.name }).contains("Oslo")
  }

  @Test
  fun quandoLaLibreriaVisibileCambiaLeCopertineSiRileggono() = runTest(dispatcher) {
    // CG-2: la foto di Oslo e' stata nascosta mentre la pagina era aperta (o sotto il viewer): la copertina si aggiorna con la rilettura.
    populate()
    areas.previewAnswer = NasFolderPreviews(folders = mapOf(SharePath.parse("Viaggi/Oslo") to listOf(cover, photo)))
    val vm = watchingViewModel()
    vm.goTo(FolderWhere("test", SharePath.of("Viaggi")))
    runCurrent()
    assertThat(areas.previewAsks).hasSize(1)

    areas.previewAnswer = NasFolderPreviews(folders = mapOf(SharePath.parse("Viaggi/Oslo") to listOf(photo)))
    visible.value = 1
    runCurrent()
    assertThat(areas.previewAsks).hasSize(2)
    assertThat(vm.state.value.previews.folders.getValue(SharePath.parse("Viaggi/Oslo"))).containsExactly(photo)
  }

  @Test
  fun ventiCambiInDueSecondiFannoPocheRilettureELUltimaArriva() = runTest(dispatcher) {
    populate()
    areas.previewAnswer = NasFolderPreviews.None
    val vm = watchingViewModel()
    vm.goTo(FolderWhere("test", SharePath.of("Viaggi")))
    runCurrent()
    val before = areas.previewAsks.size

    // Un backup che aggiunge: venti istantanee nuove in due secondi. La risposta del catalogo cambia con l'ultima.
    repeat(20) { n ->
      areas.previewAnswer = NasFolderPreviews(files = mapOf(SharePath.parse("Viaggi/mappa.png") to MediaId("00000000-0000-4000-8000-%012d".format(100 + n))))
      visible.value = n + 1
      advanceTimeBy(100)
      runCurrent()
    }
    advanceTimeBy(NasPreviewDose.PERIOD_MS * 2)
    runCurrent()

    val rereads = areas.previewAsks.size - before
    assertThat(rereads).isAtLeast(1)
    assertThat(rereads).isAtMost(4)
    assertThat(vm.state.value.previews.files.getValue(SharePath.parse("Viaggi/mappa.png"))).isEqualTo(MediaId("00000000-0000-4000-8000-%012d".format(119)))
  }
}
