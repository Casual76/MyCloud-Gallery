package dev.pampa.mycloudgallery.feature.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationSheet
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderEntry
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderState
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolders
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState
import dev.pampa.mycloudgallery.core.nasops.contract.OpKind
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRejection
import dev.pampa.mycloudgallery.core.nasops.contract.PlanWarning
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.nas.confirm.NameCheck
import dev.pampa.mycloudgallery.feature.nas.confirm.NamePurpose
import dev.pampa.mycloudgallery.feature.nas.confirm.confirmFacts
import dev.pampa.mycloudgallery.feature.nas.folders.FolderWhere
import dev.pampa.mycloudgallery.feature.nas.folders.FoldersMode
import dev.pampa.mycloudgallery.feature.nas.folders.NasFoldersViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Cartelle NAS sul NAS finto di core-nasops (il kit: operazioni, cestino, cartelle e orologi collegati): sfogliare, selezionare,
 * i piani da confermare, il nome controllato mentre si scrive, la sola lettura (E-N9) e il NAS che non risponde. Ogni gesto che
 * cambia il NAS passa da un piano e da una conferma costruita dal foglio (`ConfirmationSheet`) dopo un tocco.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NasFoldersViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val kit = NasOpsTestKit()
  private val areas = FakeNasAreas()

  @Before
  fun setUp() = Dispatchers.setMain(dispatcher)

  @After
  fun tearDown() = Dispatchers.resetMain()

  private fun viewModel(folders: NasFolders = kit.folders) = NasFoldersViewModel(folders, kit.operations, areas, kit.clocks)

  private fun TestScope.settle() {
    runCurrent()
  }

  private fun populate() {
    kit.putFile("test", "Viaggi/Oslo/a.jpg", byteArrayOf(1, 2, 3))
    kit.putFile("test", "Viaggi/Oslo/b.jpg", byteArrayOf(4, 5, 6, 7))
    kit.putFile("test", "Viaggi/mappa.png", byteArrayOf(8))
    kit.putFile("test", "nota.txt", byteArrayOf(9))
    kit.putDir("test-comune", "Famiglia")
  }

  @Test
  fun siSfogliaDalleAreeFinoAUnaCartella() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    settle()
    assertThat(vm.state.value.where).isNull()
    assertThat(vm.state.value.areas).hasSize(2)

    vm.openArea(areas.areas.value.first())
    settle()
    val root = vm.state.value
    assertThat(root.where).isEqualTo(FolderWhere("test", null))
    // Le cartelle prima, poi i file, per nome.
    assertThat(root.entries.map { it.name }).containsExactly("Viaggi", "nota.txt").inOrder()
    assertThat(root.canModify).isTrue()

    vm.openFolder(root.entries.first())
    settle()
    assertThat(vm.state.value.where).isEqualTo(FolderWhere("test", SharePath.of("Viaggi")))
    assertThat(vm.state.value.entries.map { it.name }).containsExactly("Oslo", "mappa.png").inOrder()
  }

  @Test
  fun indietroSaleUnLivelloAllaVoltaPoiTornaAlleAree() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", SharePath.parse("Viaggi/Oslo")))
    settle()
    assertThat(vm.up()).isTrue()
    assertThat(vm.state.value.where).isEqualTo(FolderWhere("test", SharePath.of("Viaggi")))
    assertThat(vm.up()).isTrue()
    assertThat(vm.state.value.where).isEqualTo(FolderWhere("test", null))
    assertThat(vm.up()).isTrue()
    assertThat(vm.state.value.where).isNull()
    // Alle aree non c'e' piu' da salire: tocca a chi ospita la pagina.
    assertThat(vm.up()).isFalse()
  }

  @Test
  fun indietroConUnaSelezioneLaToglieSoltanto() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.toggle(SharePath.of("nota.txt"))
    assertThat(vm.state.value.selection).containsExactly(SharePath.of("nota.txt"))
    assertThat(vm.up()).isTrue()
    assertThat(vm.state.value.selection).isEmpty()
    assertThat(vm.state.value.selecting).isFalse()
    assertThat(vm.state.value.where).isEqualTo(FolderWhere("test", null))
  }

  @Test
  fun eliminareChiedeUnPianoEdopoLaConfermaMandaNelCestino() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.toggle(SharePath.of("nota.txt"))
    vm.requestTrash()
    settle()

    val plan = checkNotNull(vm.state.value.confirm)
    assertThat(plan.kind).isEqualTo(OpKind.TRASH)
    assertThat(plan.summary.itemCount).isEqualTo(1)
    // Il piano da solo non cambia niente sul NAS.
    assertThat(kit.exists("test", "nota.txt")).isTrue()

    val confirmation = checkNotNull(ConfirmationSheet(plan).tap(vm.monotonicMs()))
    vm.confirmed(plan, confirmation)
    settle()
    assertThat(kit.exists("test", "nota.txt")).isFalse()
    assertThat(vm.state.value.confirm).isNull()
    assertThat(vm.state.value.selection).isEmpty()
    assertThat(vm.state.value.notice?.opensOperations).isTrue()
    // Dopo l'operazione l'elenco e' riletto: la nota non c'e' piu'.
    assertThat(vm.state.value.entries.map { it.name }).containsExactly("Viaggi")
  }

  @Test
  fun unaConfermaDiUnAltroPianoNonFaPartireNiente() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.toggle(SharePath.of("nota.txt"))
    vm.requestTrash()
    settle()
    val plan = checkNotNull(vm.state.value.confirm)
    // Una conferma data per un altro piano (un altro elemento): il diario non parte.
    vm.clearSelection()
    vm.toggle(SharePath.of("Viaggi"))
    vm.requestTrash()
    settle()
    val other = checkNotNull(vm.state.value.confirm)
    assertThat(other.hash).isNotEqualTo(plan.hash)
    val wrong = checkNotNull(ConfirmationSheet(other).tap(vm.monotonicMs()))
    vm.confirmed(plan, wrong)
    settle()
    assertThat(kit.exists("test", "nota.txt")).isTrue()
    assertThat(kit.exists("test", "Viaggi/mappa.png")).isTrue()
    assertThat(vm.state.value.notice?.opensOperations).isFalse()
  }

  @Test
  fun spostareNellAreaComuneDiceChePassaDallaCopia() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.toggle(SharePath.of("nota.txt"))
    vm.startMove()
    assertThat(vm.state.value.mode).isInstanceOf(FoldersMode.PickDestination::class.java)
    assertThat(vm.state.value.selection).isEmpty()

    vm.goTo(FolderWhere("test-comune", SharePath.of("Famiglia")))
    settle()
    assertThat(vm.state.value.mode).isInstanceOf(FoldersMode.PickDestination::class.java)
    vm.moveHere()
    settle()

    val plan = checkNotNull(vm.state.value.confirm)
    assertThat(plan.kind).isEqualTo(OpKind.MOVE)
    assertThat(plan.warnings).contains(PlanWarning.CROSS_SHARE_COPY)
    val facts = confirmFacts(plan, vm.state.value.areas)
    assertThat(facts.intoCommon).isTrue()
    assertThat(facts.destFolder).containsExactly("Famiglia")

    vm.confirmed(plan, checkNotNull(ConfirmationSheet(plan).tap(vm.monotonicMs())))
    settle()
    assertThat(vm.state.value.mode).isEqualTo(FoldersMode.Browse)
    assertThat(kit.exists("test-comune", "Famiglia/nota.txt")).isTrue()
    assertThat(kit.exists("test", "nota.txt")).isFalse()
  }

  @Test
  fun spostareNellaStessaCartellaERifiutato() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.toggle(SharePath.of("nota.txt"))
    vm.startMove()
    vm.moveHere()
    settle()
    assertThat(vm.state.value.confirm).isNull()
    assertThat(vm.state.value.notice).isNotNull()
  }

  @Test
  fun annullareLoSpostamentoTornaASfogliare() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.toggle(SharePath.of("nota.txt"))
    vm.startMove()
    vm.cancelMove()
    assertThat(vm.state.value.mode).isEqualTo(FoldersMode.Browse)
  }

  // --- Il nome -----------------------------------------------------------------------------------------------------------

  @Test
  fun ilNomeDiUnaNuovaCartellaSiControllaMentreSiScrive() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.openNewFolder()
    assertThat(vm.state.value.name?.purpose).isEqualTo(NamePurpose.NEW_FOLDER)
    assertThat(vm.state.value.name?.check).isEqualTo(NameCheck.Empty)

    vm.onNameChanged("Scuola")
    assertThat(vm.state.value.name?.check).isEqualTo(NameCheck.Checking)
    advanceTimeBy(300)
    settle()
    val ready = vm.state.value.name?.check
    assertThat(ready).isInstanceOf(NameCheck.Ready::class.java)

    // Un nome gia' occupato.
    vm.onNameChanged("Viaggi")
    advanceTimeBy(300)
    settle()
    assertThat(vm.state.value.name?.check).isEqualTo(NameCheck.Exists)

    // Un nome che il NAS non ammette: nessuna richiesta al NAS, la correzione proposta (E-D9).
    vm.onNameChanged("A:B ")
    val invalid = vm.state.value.name?.check as NameCheck.Invalid
    assertThat(invalid.suggestion).isEqualTo("A_B")

    vm.onNameChanged("")
    assertThat(vm.state.value.name?.check).isEqualTo(NameCheck.Empty)
  }

  @Test
  fun ilTocco_sulPulsanteDelNomeCreaLaCartella() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.openNewFolder()
    vm.onNameChanged("Scuola")
    advanceTimeBy(300)
    settle()
    val plan = (vm.state.value.name?.check as NameCheck.Ready).plan
    val confirmation = checkNotNull(ConfirmationSheet(plan).tap(vm.monotonicMs()))
    vm.confirmed(plan, confirmation)
    settle()
    assertThat(vm.state.value.name).isNull()
    assertThat(vm.state.value.entries.map { it.name }).contains("Scuola")
  }

  @Test
  fun rinominareControllaChiEIlNomeDiAdesso() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.toggle(SharePath.of("nota.txt"))
    vm.openRename()
    val draft = checkNotNull(vm.state.value.name)
    assertThat(draft.purpose).isEqualTo(NamePurpose.RENAME)
    assertThat(draft.text).isEqualTo("nota.txt")
    assertThat(draft.check).isEqualTo(NameCheck.Same)

    vm.onNameChanged("nota.txt")
    assertThat(vm.state.value.name?.check).isEqualTo(NameCheck.Same)
    vm.onNameChanged("promemoria.txt")
    advanceTimeBy(300)
    settle()
    assertThat(vm.state.value.name?.check).isInstanceOf(NameCheck.Ready::class.java)
  }

  @Test
  fun rinominareVuoleUnSoloElemento() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    vm.selectAll()
    vm.openRename()
    assertThat(vm.state.value.name).isNull()
  }

  // --- Sola lettura (E-N9) e NAS che non risponde ----------------------------------------------------------------------------

  @Test
  fun inSolaLetturaLeAzioniCheModificanoSonoSpente() = runTest(dispatcher) {
    populate()
    kit.readOnlyShares += "test-comune"
    kit.putFile("test-comune", "Famiglia/ricetta.txt", byteArrayOf(1))
    val vm = viewModel()
    vm.goTo(FolderWhere("test-comune", SharePath.of("Famiglia")))
    settle()
    val state = vm.state.value
    assertThat(state.readOnly).isTrue()
    assertThat(state.canModify).isFalse()

    vm.toggle(SharePath.parse("Famiglia/ricetta.txt"))
    vm.requestTrash()
    vm.startMove()
    vm.openNewFolder()
    vm.openRename()
    settle()
    assertThat(vm.state.value.confirm).isNull()
    assertThat(vm.state.value.mode).isEqualTo(FoldersMode.Browse)
    assertThat(vm.state.value.name).isNull()
    assertThat(kit.exists("test-comune", "Famiglia/ricetta.txt")).isTrue()
  }

  @Test
  fun senzaIlNasSiVedeLUltimoElencoESiModificaNiente() = runTest(dispatcher) {
    val cached = listOf(
      NasFolderEntry(SharePath.of("Viaggi"), true, 0, NasFixtures.NOW, null, 12),
      NasFolderEntry(SharePath.of("nota.txt"), false, 3, NasFixtures.NOW, 7),
    )
    val offline = object : NasFolders {
      override fun observe(share: String, dir: SharePath?): Flow<NasFolderState> = flow {
        emit(NasFolderState.Loading)
        emit(NasFolderState.Offline(cached, NasFixtures.NOW - 3_600_000))
      }

      override suspend fun refresh(share: String, dir: SharePath?) = Unit
    }
    val vm = viewModel(offline)
    vm.goTo(FolderWhere("test", null))
    settle()
    val state = vm.state.value
    assertThat(state.offline).isTrue()
    assertThat(state.entries.map { it.name }).containsExactly("Viaggi", "nota.txt").inOrder()
    assertThat(state.canModify).isFalse()
    vm.toggle(SharePath.of("nota.txt"))
    vm.requestTrash()
    settle()
    assertThat(vm.state.value.confirm).isNull()
  }

  @Test
  fun unaCartellaNegataEUnErroreHannoIlLoroStato() = runTest(dispatcher) {
    val states = MutableStateFlow<NasFolderState>(NasFolderState.Denied)
    val scripted = object : NasFolders {
      override fun observe(share: String, dir: SharePath?): Flow<NasFolderState> = states
      override suspend fun refresh(share: String, dir: SharePath?) = Unit
    }
    val vm = viewModel(scripted)
    vm.goTo(FolderWhere("test", null))
    settle()
    assertThat(vm.state.value.listing).isEqualTo(NasFolderState.Denied)
    assertThat(vm.state.value.entries).isEmpty()
    states.value = NasFolderState.Error(dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode.UNEXPECTED)
    settle()
    assertThat(vm.state.value.listing).isInstanceOf(NasFolderState.Error::class.java)
  }

  @Test
  fun leOperazioniInCorsoSiVedonoESiRileggeQuandoFiniscono() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    assertThat(vm.state.value.activeOperations).isEqualTo(0)
    // Un'operazione vera (nota nel cestino): finita subito, l'elenco non la contiene piu'.
    vm.toggle(SharePath.of("nota.txt"))
    vm.requestTrash()
    settle()
    val plan = checkNotNull(vm.state.value.confirm)
    vm.confirmed(plan, checkNotNull(ConfirmationSheet(plan).tap(vm.monotonicMs())))
    settle()
    assertThat(kit.operations.operations.value.single()).isInstanceOf(NasOperationState.Done::class.java)
    assertThat(vm.state.value.activeOperations).isEqualTo(0)
    assertThat(vm.state.value.entries.map { it.name }).doesNotContain("nota.txt")
  }

  @Test
  fun unPianoRifiutatoDiceIlMotivoSenzaFoglio() = runTest(dispatcher) {
    populate()
    val vm = viewModel()
    vm.goTo(FolderWhere("test", null))
    settle()
    kit.deleteFromPc("test", "nota.txt")
    vm.toggle(SharePath.of("nota.txt"))
    vm.requestTrash()
    settle()
    // Il file non c'e' piu' (cancellato dal PC): il piano non nasce, e la pagina lo dice.
    assertThat(vm.state.value.confirm).isNull()
    assertThat(vm.state.value.notice?.text).isEqualTo(dev.pampa.mycloudgallery.feature.nas.ui.planRejectionRes(PlanRejection.SOURCE_MISSING))
  }
}
