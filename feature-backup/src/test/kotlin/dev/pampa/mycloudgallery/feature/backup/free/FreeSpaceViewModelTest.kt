package dev.pampa.mycloudgallery.feature.backup.free

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupAction
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceKind
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.backup.testing.TestConfirmations
import dev.pampa.mycloudgallery.feature.backup.testing.RecordingBackupActions
import java.util.Optional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Il ViewModel di "Libera spazio" (D21, R6): apre i fogli, passa la conferma che il foglio ha fatto nascere dal tocco, fa partire il dialogo del
 * sistema **solo** dal tocco su "Libera ora" e aspetta la risposta dal ponte. Senza i servizi (`Optional` vuoti, quello che Dagger da' finche'
 * `:app` non li lega) dice "non e' ancora disponibile" e non fa niente.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FreeSpaceViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()
  private val clock = FakeBackupClock()
  private val dialogs = FakePhoneSystemDialogs(ApplicationProvider.getApplicationContext())

  @Before
  fun setUp() = Dispatchers.setMain(dispatcher)

  @After
  fun tearDown() = Dispatchers.resetMain()

  private fun viewModel(count: Int = 4, actions: RecordingBackupActions = RecordingBackupActions()) = FreeSpaceViewModel(
    Optional.of(FreeSpaceSamples.space(count, clock)),
    Optional.of(dialogs),
    Optional.of(clock),
    Optional.of(actions),
  )

  @Test
  fun senzaIServiziDiceCheNonEPronto() = runTest(dispatcher) {
    val vm = FreeSpaceViewModel(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())
    vm.start(selection = null)
    assertThat(vm.state.value).isEqualTo(FreeSpaceUi.Blocked(FreeSpaceBlockedBy.NOT_AVAILABLE))
    vm.openConfirm()
    assertThat(vm.sheet.value).isEqualTo(FreeSheet.None)
  }

  @Test
  fun laPaginaApreConIlRiepilogo_EIFiltriLoCambiano() = runTest(dispatcher) {
    val vm = viewModel(count = 6)
    vm.start(selection = null)
    assertThat((vm.state.value as FreeSpaceUi.Summary).plan.itemCount).isEqualTo(6)
    vm.choose(FreeSpaceKind.VIDEOS_ONLY)
    assertThat((vm.state.value as FreeSpaceUi.Summary).plan.itemCount).isEqualTo(2)
  }

  @Test
  fun ilFoglioSiApreDalTocco_EChiuderloNonConfermaNiente() = runTest(dispatcher) {
    val vm = viewModel()
    vm.start(selection = null)
    assertThat(vm.sheet.value).isEqualTo(FreeSheet.None)
    vm.openConfirm()
    assertThat(vm.sheet.value).isInstanceOf(FreeSheet.Confirm::class.java)
    vm.closeSheet()
    assertThat(vm.sheet.value).isEqualTo(FreeSheet.None)
    assertThat(vm.state.value).isInstanceOf(FreeSpaceUi.Summary::class.java)
    vm.openConfirmNow()
    val sheet = vm.sheet.value as FreeSheet.ConfirmNow
    assertThat(sheet.plan.permanent).isTrue()
    assertThat(sheet.plan.requiredConfirmations).isEqualTo(2)
  }

  @Test
  fun laConfermaFaPartireLaVerifica_EIlDialogoSoloDaLiberaOra() = runTest(dispatcher) {
    val vm = viewModel(count = 3)
    vm.start(selection = null)
    vm.openConfirm()
    val plan = (vm.sheet.value as FreeSheet.Confirm).plan
    vm.confirm(TestConfirmations.confirm(plan, clock.monotonicMs()))
    assertThat(vm.sheet.value).isEqualTo(FreeSheet.None)
    assertThat(vm.state.value).isInstanceOf(FreeSpaceUi.Ready::class.java)
    // Pronto: il sistema non e' ancora stato interpellato.
    assertThat(dialogs.removeRequests).isEmpty()

    vm.free()
    assertThat(vm.state.value).isInstanceOf(FreeSpaceUi.Confirming::class.java)
    assertThat(dialogs.removeRequests).hasSize(1)
    assertThat(vm.bridge.isWaiting).isTrue()
    vm.bridge.answered(true)
    val done = vm.state.value as FreeSpaceUi.Done
    assertThat(done.items).isEqualTo(3)
  }

  @Test
  fun seLUtenteDiceDiNoNonCambiaNiente() = runTest(dispatcher) {
    val vm = viewModel(count = 3)
    vm.start(selection = null)
    vm.openConfirm()
    vm.confirm(TestConfirmations.confirm((vm.sheet.value as FreeSheet.Confirm).plan, clock.monotonicMs()))
    vm.free()
    vm.bridge.answered(false)
    val done = vm.state.value as FreeSpaceUi.Done
    assertThat(done.cancelled).isTrue()
    assertThat(done.items).isEqualTo(0)
  }

  @Test
  fun senzaIlDialogoDelSistemaLaPaginaLoDice() = runTest(dispatcher) {
    val vm = viewModel(count = 3)
    vm.start(selection = null)
    vm.openConfirm()
    vm.confirm(TestConfirmations.confirm((vm.sheet.value as FreeSheet.Confirm).plan, clock.monotonicMs()))
    dialogs.missing = true
    vm.free()
    assertThat(vm.state.value).isEqualTo(FreeSpaceUi.Blocked(FreeSpaceBlockedBy.NO_SYSTEM_DIALOG))
  }

  @Test
  fun unaSceltaDellUtenteNonPerdeIGiorniRecenti() = runTest(dispatcher) {
    val vm = viewModel(count = 3)
    vm.start(selection = setOf(FreeSpaceSamples.mediaId(1), FreeSpaceSamples.mediaId(2)))
    val plan = (vm.state.value as FreeSpaceUi.Summary).plan
    assertThat(plan.filter.excludeRecentDays).isEqualTo(0)
    assertThat(plan.itemCount).isEqualTo(2)
  }

  @Test
  fun ilBloccoHaIlSuoTastoChePassaPerLeAzioniDiApp() = runTest(dispatcher) {
    val actions = RecordingBackupActions()
    val vm = viewModel(actions = actions)
    vm.perform(BackupAction.DIAGNOSTICS)
    assertThat(actions.performed).containsExactly(BackupAction.DIAGNOSTICS)
  }
}
