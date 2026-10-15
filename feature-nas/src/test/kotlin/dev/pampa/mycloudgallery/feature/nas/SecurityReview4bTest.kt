package dev.pampa.mycloudgallery.feature.nas

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nasops.contract.OpKind
import dev.pampa.mycloudgallery.core.nasops.testing.KitEngine
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.nas.confirm.confirmFacts
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
 * Revisione di sicurezza della Fase 4B (docs/review/4b-fase4b.md). Il test descrive il comportamento **sicuro** atteso e oggi
 * **fallisce** sull'ultima asserzione; quelle prima provano che lo scenario si costruisce davvero (ViewModel vero, pianificazione e
 * cartelle di produzione: `KitEngine.PRODUCTION`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecurityReview4bTest {

  private val dispatcher = StandardTestDispatcher()

  @Before
  fun setUp() = Dispatchers.setMain(dispatcher)

  @After
  fun tearDown() = Dispatchers.resetMain()

  /**
   * R2. "Elimina" su una cartella da Cartelle NAS: il foglio di conferma (E-D7, "riepilogo con numero e dimensione") deve dire quanti
   * file e quanti byte vanno nel cestino. Il piano porta la cartella come un elemento solo, con la dimensione dell'elenco SMB (0 per una
   * cartella): il foglio dice "1 cartella e 0 file", "0 B".
   */
  @Test
  fun r2_ilFoglioPerEliminareUnaCartellaDiceQuantiFileEQuantiByte() = runTest(dispatcher) {
    val kit = NasOpsTestKit(engine = KitEngine.PRODUCTION)
    kit.putFile("test", "Viaggi/Oslo/a.jpg", byteArrayOf(1, 2, 3))
    kit.putFile("test", "Viaggi/Oslo/b.jpg", byteArrayOf(4, 5, 6, 7))
    kit.putFile("test", "Viaggi/mappa.png", byteArrayOf(8))
    val vm = NasFoldersViewModel(kit.folders, kit.operations, FakeNasAreas(), kit.clocks)
    vm.goTo(FolderWhere("test", null))
    runCurrent()
    vm.toggle(SharePath.of("Viaggi"))
    vm.requestTrash()
    runCurrent()

    val plan = checkNotNull(vm.state.value.confirm)
    assertThat(plan.kind).isEqualTo(OpKind.TRASH)
    val facts = confirmFacts(plan, vm.state.value.areas)
    assertThat(facts.folderCount).isEqualTo(1)

    assertWithMessage("file che il foglio dice di mandare nel cestino").that(facts.fileCount).isEqualTo(3)
    assertWithMessage("byte che il foglio dice di mandare nel cestino").that(facts.totalBytes).isEqualTo(8)
  }
}
