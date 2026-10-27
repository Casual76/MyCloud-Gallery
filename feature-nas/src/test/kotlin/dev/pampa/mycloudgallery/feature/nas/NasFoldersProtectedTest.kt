package dev.pampa.mycloudgallery.feature.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationSheet
import dev.pampa.mycloudgallery.core.nasops.contract.OpKind
import dev.pampa.mycloudgallery.core.nasops.contract.ProtectedFiles
import dev.pampa.mycloudgallery.core.nasops.contract.SkipReason
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.nas.confirm.confirmFacts
import dev.pampa.mycloudgallery.feature.nas.folders.FolderWhere
import dev.pampa.mycloudgallery.feature.nas.folders.FoldersMode
import dev.pampa.mycloudgallery.feature.nas.folders.NasFoldersViewModel
import dev.pampa.mycloudgallery.feature.nas.ui.CountedText
import dev.pampa.mycloudgallery.feature.nas.ui.NasTone
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
 * Cartelle NAS e i file di un album condiviso in cui l'utente non puo' eliminare, spostare o rinominare (Fase 7, correzioni F, risposta dell'utente del 27/10
 * su R3 e L152). L'utente ha voluto **tenere le azioni** ("Elimina", "Sposta", "Rinomina") e, **al tocco**, un messaggio chiaro: l'operazione non e' riuscita, e
 * perche'. Il piano di `core-nasops` e' quello che protegge davvero (qui il NAS finto ha la stessa porta `ProtectedFiles` del vero); la schermata deve dirlo:
 *  - tutto protetto: niente foglio, l'avviso dice che non e' riuscito e perche' (eliminare, spostare, rinominare);
 *  - una selezione mista: il foglio elenca quelli che restano dove sono, e dopo la conferma l'avviso dice «avviata, ma N no, perche'...»;
 *  - rinomina: il rifiuto arriva al tocco di «Rinomina», prima di far scrivere un nome.
 * Il testo esatto delle frasi lo prova `NasProtectedNoticeTextTest` (Robolectric).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NasFoldersProtectedTest {

  private val dispatcher = StandardTestDispatcher()
  private val kit = NasOpsTestKit(protectedFiles = ProtectedFiles { _, items -> items.filter { it.path.name.startsWith("album-") }.map { it.path }.toSet() })
  private val areas = FakeNasAreas()

  @Before
  fun setUp() = Dispatchers.setMain(dispatcher)

  @After
  fun tearDown() = Dispatchers.resetMain()

  private fun viewModel() = NasFoldersViewModel(kit.folders, kit.operations, areas, kit.clocks)

  private fun TestScope.settle() {
    runCurrent()
  }

  private val folder = SharePath.parse("Album condivisi/Guardano")

  /** L'area comune con due file di un album condiviso (protetti) e uno libero, nella cartella di un album. */
  private fun populate() {
    kit.putFile("test-comune", "Album condivisi/Guardano/album-1.jpg", byteArrayOf(1))
    kit.putFile("test-comune", "Album condivisi/Guardano/album-2.jpg", byteArrayOf(2, 2))
    kit.putFile("test-comune", "Album condivisi/Guardano/libera.jpg", byteArrayOf(3, 3, 3))
    kit.putDir("test-comune", "Famiglia")
  }

  private fun TestScope.openFolder(vm: NasFoldersViewModel) {
    vm.goTo(FolderWhere("test-comune", folder))
    settle()
  }

  private fun select(vm: NasFoldersViewModel, vararg names: String) = names.forEach { vm.toggle(folder.child(it)) }

  // --- Elimina ------------------------------------------------------------------------------------------------------------------------

  @Test
  fun eliminareSoloFileDiUnAlbumCondiviso_NonApreIlFoglio_ELAvvisoDicePerche() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    openFolder(vm)
    select(vm, "album-1.jpg", "album-2.jpg")

    vm.requestTrash()
    settle()

    // L'azione c'e' (il tocco arriva al piano), ma non apre nessun foglio: l'avviso dice che non e' riuscito e il motivo, in tono di allarme.
    assertThat(vm.state.value.confirm).isNull()
    val notice = checkNotNull(vm.state.value.notice)
    assertThat(notice.text).isEqualTo(R.string.nas_reject_shared_album_item)
    assertThat(notice.tone).isEqualTo(NasTone.Alarm)
    assertThat(notice.counted).isNull()
    assertThat(kit.exists("test-comune", "Album condivisi/Guardano/album-1.jpg")).isTrue()
    assertThat(kit.exists("test-comune", "Album condivisi/Guardano/album-2.jpg")).isTrue()
  }

  @Test
  fun eliminareUnaSelezioneMista_IlFoglioElencaQuelliCheRestano_EDopoLaConfermaLAvvisoDiceQuantiNoEPerche() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    openFolder(vm)
    select(vm, "album-1.jpg", "album-2.jpg", "libera.jpg")

    vm.requestTrash()
    settle()

    // Il foglio: una cosa parte, due restano dove sono, con il loro motivo.
    val plan = checkNotNull(vm.state.value.confirm)
    assertThat(plan.kind).isEqualTo(OpKind.TRASH)
    assertThat(plan.items.map { it.srcPath.name }).containsExactly("libera.jpg")
    assertThat(confirmFacts(plan, vm.state.value.areas).skipped).containsExactly(SkipReason.SHARED_ALBUM_ITEM to 2)
    // Il tocco di conferma: l'operazione parte per quella sola, e l'avviso dice 2 no perche' di un album condiviso.
    val confirmation = checkNotNull(ConfirmationSheet(plan).tap(vm.monotonicMs()))
    vm.confirmed(plan, confirmation)
    settle()

    val notice = checkNotNull(vm.state.value.notice)
    assertThat(notice.counted).isEqualTo(CountedText(R.plurals.nas_started_partly_protected, 2))
    assertThat(notice.opensOperations).isTrue()
    assertThat(notice.tone).isEqualTo(NasTone.Attention)
    assertThat(kit.exists("test-comune", "Album condivisi/Guardano/libera.jpg")).isFalse()
    assertThat(kit.exists("test-comune", "Album condivisi/Guardano/album-1.jpg")).isTrue()
    assertThat(kit.exists("test-comune", "Album condivisi/Guardano/album-2.jpg")).isTrue()
  }

  @Test
  fun senzaFileProtettiLAvvisoDopoLaConfermaResta_OperazioneAvviata() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    openFolder(vm)
    select(vm, "libera.jpg")
    vm.requestTrash()
    settle()
    val plan = checkNotNull(vm.state.value.confirm)

    vm.confirmed(plan, checkNotNull(ConfirmationSheet(plan).tap(vm.monotonicMs())))
    settle()

    val notice = checkNotNull(vm.state.value.notice)
    assertThat(notice.text).isEqualTo(R.string.nas_started)
    assertThat(notice.counted).isNull()
    assertThat(notice.tone).isEqualTo(NasTone.Calm)
  }

  // --- Sposta -------------------------------------------------------------------------------------------------------------------------

  @Test
  fun spostareSoloFileDiUnAlbumCondiviso_NonApreIlFoglio_ELAvvisoDicePerche() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    openFolder(vm)
    select(vm, "album-1.jpg")
    vm.startMove()
    vm.goTo(FolderWhere("test-comune", SharePath.of("Famiglia")))
    settle()

    vm.moveHere()
    settle()

    assertThat(vm.state.value.confirm).isNull()
    assertThat(vm.state.value.notice?.text).isEqualTo(R.string.nas_reject_shared_album_item)
    assertThat(vm.state.value.notice?.tone).isEqualTo(NasTone.Alarm)
    assertThat(kit.exists("test-comune", "Album condivisi/Guardano/album-1.jpg")).isTrue()
    assertThat(vm.state.value.mode).isInstanceOf(FoldersMode.PickDestination::class.java)
  }

  @Test
  fun spostareUnaSelezioneMista_PortaViaSoloIFileLiberi_ELAvvisoDiceQuantiNo() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    openFolder(vm)
    select(vm, "album-1.jpg", "libera.jpg")
    vm.startMove()
    vm.goTo(FolderWhere("test-comune", SharePath.of("Famiglia")))
    settle()
    vm.moveHere()
    settle()

    val plan = checkNotNull(vm.state.value.confirm)
    assertThat(plan.kind).isEqualTo(OpKind.MOVE)
    assertThat(plan.items.map { it.srcPath.name }).containsExactly("libera.jpg")
    assertThat(confirmFacts(plan, vm.state.value.areas).skipped).containsExactly(SkipReason.SHARED_ALBUM_ITEM to 1)
    vm.confirmed(plan, checkNotNull(ConfirmationSheet(plan).tap(vm.monotonicMs())))
    settle()

    assertThat(vm.state.value.notice?.counted).isEqualTo(CountedText(R.plurals.nas_started_partly_protected, 1))
    assertThat(kit.exists("test-comune", "Famiglia/libera.jpg")).isTrue()
    assertThat(kit.exists("test-comune", "Album condivisi/Guardano/album-1.jpg")).isTrue()
  }

  // --- Rinomina -----------------------------------------------------------------------------------------------------------------------

  @Test
  fun rinominareUnFileDiUnAlbumCondiviso_LAvvisoArrivaAlTocco_PrimaDelNome() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    openFolder(vm)
    select(vm, "album-1.jpg")

    vm.openRename()
    settle()

    // Nessun foglio del nome da riempire per niente: subito l'avviso, con il motivo. Il file non si tocca.
    assertThat(vm.state.value.name).isNull()
    assertThat(vm.state.value.notice?.text).isEqualTo(R.string.nas_reject_shared_album_item)
    assertThat(vm.state.value.notice?.tone).isEqualTo(NasTone.Alarm)
    assertThat(kit.exists("test-comune", "Album condivisi/Guardano/album-1.jpg")).isTrue()
  }

  @Test
  fun rinominareUnFileLibero_ApreIlFoglioDelNomeComePrima() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    openFolder(vm)
    select(vm, "libera.jpg")

    vm.openRename()
    settle()

    assertThat(vm.state.value.name?.text).isEqualTo("libera.jpg")
    assertThat(vm.state.value.notice).isNull()
  }
}
