package dev.pampa.mycloudgallery.feature.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationSheet
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperationState
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpace
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpaceState
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashState
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpKind
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.PlanWarning
import dev.pampa.mycloudgallery.core.nasops.contract.PurgeStop
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.nasops.testing.RemoteTrashRecord
import dev.pampa.mycloudgallery.core.nasops.testing.ScriptedNasOperations
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.nas.operations.NasOperationsViewModel
import dev.pampa.mycloudgallery.feature.nas.operations.operationsUi
import dev.pampa.mycloudgallery.feature.nas.space.NasSpaceViewModel
import dev.pampa.mycloudgallery.feature.nas.trash.NasTrashViewModel
import dev.pampa.mycloudgallery.feature.nas.trash.key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Spazio NAS, Eliminati di recente del NAS (con "Elimina subito") e Operazioni in corso: i ViewModel sul NAS finto e sul copione. */
@OptIn(ExperimentalCoroutinesApi::class)
class NasOtherViewModelsTest {

  private val dispatcher = StandardTestDispatcher()
  private val kit = NasOpsTestKit()
  private val areas = FakeNasAreas()

  @Before
  fun setUp() = Dispatchers.setMain(dispatcher)

  @After
  fun tearDown() = Dispatchers.resetMain()

  private fun TestScope.settle() = runCurrent()

  // --- Spazio ----------------------------------------------------------------------------------------------------------------

  private fun fakeSpace(block: suspend (String) -> NasSpaceState): NasSpace = object : NasSpace {
    override suspend fun load(share: String): NasSpaceState = block(share)
  }

  @Test
  fun lSpazioSiCalcolaAllApertura_PerLaPrimaAreaESiRicalcolaCambiandola() = runTest(dispatcher) {
    val asked = mutableListOf<String>()
    val space = fakeSpace { share ->
      asked += share
      NasSpaceState.Ready(NasFixtures.space(share = share))
    }
    val vm = NasSpaceViewModel(space, areas)
    settle()
    assertThat(vm.state.value.share).isEqualTo("test")
    assertThat((vm.state.value.space as NasSpaceState.Ready).details.share).isEqualTo("test")
    assertThat(asked).containsExactly("test")

    vm.select("test-comune")
    settle()
    assertThat((vm.state.value.space as NasSpaceState.Ready).details.share).isEqualTo("test-comune")
    // Sceglierla di nuovo non rilegge; "Ricalcola" si'.
    vm.select("test-comune")
    settle()
    assertThat(asked).containsExactly("test", "test-comune")
    vm.reload()
    settle()
    assertThat(asked).containsExactly("test", "test-comune", "test-comune")
  }

  @Test
  fun senzaIlNasLoSpazioDiceNonDisponibileEMostraIFilePiuGrandi() = runTest(dispatcher) {
    val space = fakeSpace { NasSpaceState.Unavailable(OpErrorCode.UNREACHABLE, NasFixtures.largest(5)) }
    val vm = NasSpaceViewModel(space, areas)
    settle()
    val state = vm.state.value.space as NasSpaceState.Unavailable
    assertThat(state.code).isEqualTo(OpErrorCode.UNREACHABLE)
    assertThat(state.largest).hasSize(5)
  }

  @Test
  fun loSpazioSulNasFintoLeggeLaShareEIlCestino() = runTest(dispatcher) {
    val vm = NasSpaceViewModel(kit.space, areas)
    settle()
    val ready = vm.state.value.space as NasSpaceState.Ready
    assertThat(ready.details.totalBytes).isGreaterThan(0L)
    assertThat(ready.details.versionsBytes).isEqualTo(0L)
  }

  // --- Eliminati di recente ----------------------------------------------------------------------------------------------

  private fun TestScope.trashViewModel() = NasTrashViewModel(kit.trash, kit.operations, areas, kit.clocks).also { settle() }

  private suspend fun trashFile(path: String) {
    kit.putFile("test", path, byteArrayOf(1, 2, 3))
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", path))))
  }

  @Test
  fun ilCestinoMostraLeVociProprieERipristinareLeRiporta() = runTest(dispatcher) {
    trashFile("Viaggi/nota.txt")
    val vm = trashViewModel()
    val items = vm.state.value.items
    assertThat(items).hasSize(1)
    assertThat(items.single().deletableNow).isTrue()
    assertThat(items.single().restorable).isTrue()
    assertThat(kit.exists("test", "Viaggi/nota.txt")).isFalse()

    vm.toggle(items.single())
    assertThat(vm.state.value.canRestore).isTrue()
    vm.requestRestore()
    settle()
    val plan = checkNotNull(vm.state.value.confirm)
    assertThat(plan.kind).isEqualTo(OpKind.RESTORE)
    assertThat(plan.requiredConfirmations).isEqualTo(1)
    vm.confirmed(plan, checkNotNull(ConfirmationSheet(plan).tap(vm.monotonicMs())))
    settle()
    assertThat(kit.exists("test", "Viaggi/nota.txt")).isTrue()
    assertThat(vm.state.value.items).isEmpty()
    assertThat(vm.state.value.selection).isEmpty()
  }

  @Test
  fun eliminareSubitoVuoleDueToccheESoloPerLeVociProprie() = runTest(dispatcher) {
    trashFile("a.txt")
    val vm = trashViewModel()
    val item = vm.state.value.items.single()
    vm.toggle(item)
    assertThat(vm.state.value.canPurge).isTrue()
    vm.requestPurge()
    settle()
    val plan = checkNotNull(vm.state.value.confirm)
    assertThat(plan.kind).isEqualTo(OpKind.PURGE_NOW)
    assertThat(plan.requiredConfirmations).isEqualTo(2)
    assertThat(plan.warnings).contains(PlanWarning.PERMANENT_DELETE)

    // Una conferma sola non basta: nessuna operazione e il file resta nel cestino.
    val sheet = ConfirmationSheet(plan)
    assertThat(sheet.tap(vm.monotonicMs())).isNull()
    assertThat(sheet.tapsLeft).isEqualTo(1)
    assertThat(kit.filesIn("test")).isNotEmpty()

    // R6: il secondo tocco e' un secondo gesto, non il secondo colpo di un doppio tocco.
    kit.clocks.monotonic += dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationRules.MIN_TAP_GAP_MS
    val confirmation = checkNotNull(sheet.tap(vm.monotonicMs()))
    assertThat(confirmation.confirmedSteps).isEqualTo(2)
    vm.confirmed(plan, confirmation)
    settle()
    assertThat(kit.filesIn("test")).isEmpty()
    assertThat(vm.state.value.notice?.opensOperations).isTrue()
  }

  @Test
  fun leVociDegliAltriSiRipristinanoMaNonSiEliminanoSubito() = runTest(dispatcher) {
    trashFile("a.txt")
    kit.remoteTrash += RemoteTrashRecord(
      share = "test-comune", ownerDeviceId = NasOpsTestKit.DEVICE_B, trashId = NasFixtures.trashId(9),
      originalPath = SharePath.of("ricetta.txt"), size = 10, trashedAtNas = NasFixtures.NOW, author = "Tablet di Mamma",
    )
    kit.putFile("test-comune", ".mycloudgallery/trash/${NasOpsTestKit.DEVICE_B}/files/${NasFixtures.trashId(9)}", byteArrayOf(1))
    val vm = trashViewModel()
    vm.refresh()
    settle()
    val items = vm.state.value.items
    assertThat(items.map { it.deletableNow }).containsExactly(true, false)
    // Se tra le scelte c'e' una voce di un altro dispositivo, "Elimina subito" e' spento: il piano non si chiede nemmeno.
    vm.selectAll()
    assertThat(vm.state.value.canPurge).isFalse()
    vm.requestPurge()
    settle()
    assertThat(vm.state.value.confirm).isNull()
    // Ripristinare invece si puo' (qualunque dispositivo con il permesso, D25).
    assertThat(vm.state.value.canRestore).isTrue()
  }

  @Test
  fun unaVoceSenzaRecordValidoSiVedeNonSiRipristinaENonSiElimina() = runTest(dispatcher) {
    kit.remoteTrash += RemoteTrashRecord(
      share = "test", ownerDeviceId = NasOpsTestKit.DEVICE_B, trashId = NasFixtures.trashId(8),
      originalPath = SharePath.of("x.txt"), size = 5, trashedAtNas = NasFixtures.NOW, author = null, valid = false,
    )
    kit.putFile("test", ".mycloudgallery/trash/${NasOpsTestKit.DEVICE_B}/files/${NasFixtures.trashId(8)}", byteArrayOf(1))
    val vm = trashViewModel()
    vm.refresh()
    settle()
    val item = vm.state.value.items.single()
    assertThat(item.withoutValidRecord).isTrue()
    assertThat(item.name).isNull()
    vm.toggle(item)
    assertThat(vm.state.value.canRestore).isFalse()
    assertThat(vm.state.value.canPurge).isFalse()
    vm.requestRestore()
    vm.requestPurge()
    settle()
    assertThat(vm.state.value.confirm).isNull()
  }

  @Test
  fun senzaIlNasSiVedonoSoloLeProprieESiModificaNiente() = runTest(dispatcher) {
    trashFile("a.txt")
    kit.trash.refresh()
    val own = kit.trash.items.value
    assertThat(own).isInstanceOf(NasTrashState.Ready::class.java)
    val scripted = object : dev.pampa.mycloudgallery.core.nasops.contract.NasTrash {
      override val items = kotlinx.coroutines.flow.MutableStateFlow<NasTrashState>(
        NasTrashState.Offline((kit.trash.items.value as NasTrashState.Ready).items.filter { it.deletableNow }),
      )

      override suspend fun refresh() = Unit

      override suspend fun purgeExpired(budget: kotlin.time.Duration) = dev.pampa.mycloudgallery.core.nasops.contract.PurgeReport(0, 0, emptyMap(), 0, 0, PurgeStop.NAS_UNREACHABLE)
    }
    val vm = NasTrashViewModel(scripted, kit.operations, areas, kit.clocks)
    settle()
    assertThat(vm.state.value.offline).isTrue()
    assertThat(vm.state.value.canModify).isFalse()
    vm.startSelecting()
    assertThat(vm.state.value.selecting).isFalse()
    vm.selectAll()
    assertThat(vm.state.value.canPurge).isFalse()
    assertThat(vm.state.value.canRestore).isFalse()
  }

  // --- Operazioni in corso -----------------------------------------------------------------------------------------------

  @Test
  fun leOperazioniSiDividonoInCorsoEFinite_LePiuRecentiPrima() {
    val ui = operationsUi(NasFixtures.operations())
    assertThat(ui.active.map { it.id }).containsExactly(NasFixtures.opId(1), NasFixtures.opId(2), NasFixtures.opId(3), NasFixtures.opId(4)).inOrder()
    // Le chiuse dal diario sono nell'ordine di creazione: sulla pagina la piu' recente e' la prima.
    assertThat(ui.closed.map { it.id }).containsExactly(NasFixtures.opId(8), NasFixtures.opId(7), NasFixtures.opId(6), NasFixtures.opId(5)).inOrder()
    assertThat(ui.isEmpty).isFalse()
  }

  @Test
  fun ferma_inoltraLAnnullamentoEBasta() = runTest(dispatcher) {
    val scripted = ScriptedNasOperations(kit.clocks)
    val vm = NasOperationsViewModel(scripted)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
    settle()
    assertThat(vm.state.value.isEmpty).isTrue()
    scripted.emit(NasFixtures.operations().first())
    settle()
    assertThat(vm.state.value.active).hasSize(1)
    vm.stop(NasFixtures.opId(1))
    settle()
    assertThat(scripted.cancelled).containsExactly(NasFixtures.opId(1))
  }

  @Test
  fun unaOperazioneVeraSiVedeAvanzareEFinire() = runTest(dispatcher) {
    kit.putFile("test", "a.txt", byteArrayOf(1))
    val vm = NasOperationsViewModel(kit.operations)
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
    settle()
    val plan = (kit.operations.plan(OpRequest.Trash(listOf(kit.itemOf("test", "a.txt")))) as PlanOutcome.Ready).plan
    val outcome = kit.operations.start(plan, checkNotNull(ConfirmationSheet(plan).tap(kit.clocks.monotonicMs())))
    assertThat(outcome).isInstanceOf(StartOutcome.Started::class.java)
    settle()
    assertThat(vm.state.value.closed.single()).isInstanceOf(NasOperationState.Done::class.java)
    assertThat(vm.state.value.active).isEmpty()
  }

  // --- I numeri ----------------------------------------------------------------------------------------------------------

  @Test
  fun iPesiSiScrivonoCome_IlTelefono() {
    val cases = mapOf(
      0L to "0 B", 999L to "999 B", 1_000L to "1,0 kB", 3_210L to "3,2 kB", 301_445L to "301 kB", 4_812_345L to "4,8 MB",
      812_345_678L to "812 MB", 999_600_000L to "1,0 GB", 4_100_000_000L to "4,1 GB", 187_000_000L to "187 MB",
      1_840_000_000_000L to "1,8 TB", 3_600_000_000_000L to "3,6 TB", -5L to "0 B",
    )
    // Fra il numero e l'unita' c'e' uno spazio che non va a capo.
    for ((bytes, expected) in cases) assertThat(dev.pampa.mycloudgallery.feature.nas.ui.NasText.size(bytes)).isEqualTo(expected.replace(' ', '\u00A0'))
  }

  @Test
  fun iNomiDeiFileDannoLIcona() {
    val text = dev.pampa.mycloudgallery.feature.nas.ui.NasText
    assertThat(text.kindOf("IMG_1.JPG")).isEqualTo(dev.pampa.mycloudgallery.feature.nas.ui.FileKind.IMAGE)
    assertThat(text.kindOf("scatto.heic")).isEqualTo(dev.pampa.mycloudgallery.feature.nas.ui.FileKind.IMAGE)
    assertThat(text.kindOf("clip.MP4")).isEqualTo(dev.pampa.mycloudgallery.feature.nas.ui.FileKind.VIDEO)
    assertThat(text.kindOf("Bollette.pdf")).isEqualTo(dev.pampa.mycloudgallery.feature.nas.ui.FileKind.OTHER)
    assertThat(text.kindOf("senza estensione")).isEqualTo(dev.pampa.mycloudgallery.feature.nas.ui.FileKind.OTHER)
  }

  @Suppress("unused")
  private fun unusedKey() = NasFixtures.trashItems().first().key()
}
