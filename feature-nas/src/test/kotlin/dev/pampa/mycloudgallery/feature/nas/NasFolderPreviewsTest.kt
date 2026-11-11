package dev.pampa.mycloudgallery.feature.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.nas.contract.NasFolderPreviews
import dev.pampa.mycloudgallery.feature.nas.folders.FolderWhere
import dev.pampa.mycloudgallery.feature.nas.folders.NasFoldersViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
}
