package dev.pampa.mycloudgallery.feature.backup.versions

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.NasVersionPlan
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmation
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationRules
import dev.pampa.mycloudgallery.core.backup.contract.PhoneWriteGrant
import dev.pampa.mycloudgallery.core.backup.contract.RestoreAvailability
import dev.pampa.mycloudgallery.core.backup.contract.RestoreOutcome
import dev.pampa.mycloudgallery.core.backup.contract.VersionKeeper
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryVersionKeeper
import dev.pampa.mycloudgallery.core.backup.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.feature.backup.free.FakePhoneSystemDialogs
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
 * Il ViewModel delle versioni e di "Ripristina originale" (`docs/fase5-progetto.md` §2.4, D17, D18, E-E2, E-E10): mostra il piano, passa la
 * conferma nata dal tocco, chiede il permesso del sistema solo se la copia del telefono ha la versione modificata. Non scrive niente da solo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VersionsViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()
  private val clock = FakeBackupClock()
  private val dialogs = FakePhoneSystemDialogs(ApplicationProvider.getApplicationContext())
  private val keeper = InMemoryVersionKeeper(clock)
  private val actions = RecordingVersionActions()

  @Before
  fun setUp() = Dispatchers.setMain(dispatcher)

  @After
  fun tearDown() = Dispatchers.resetMain()

  private class RecordingVersionActions : VersionActions {
    override val canView: Boolean = true
    override val canDeleteIntermediates: Boolean = true
    val viewed = ArrayList<VersionId>()
    val deleted = ArrayList<List<VersionId>>()

    override fun view(mediaId: MediaId, version: VersionId) {
      viewed += version
    }

    override fun deleteIntermediates(mediaId: MediaId, versions: List<VersionId>) {
      deleted += versions
    }
  }

  private fun viewModel(service: VersionKeeper? = keeper) = VersionsViewModel(
    Optional.ofNullable(service),
    Optional.of(dialogs),
    Optional.of(clock),
    Optional.of(actions),
  )

  private fun confirmationFor(plan: NasVersionPlan): PhoneConfirmation = TestConfirmations.confirm(plan, clock.monotonicMs())

  @Test
  fun senzaIlCustodeDelleVersioniLaFunzioneDiceCheNonEPronta() = runTest(dispatcher) {
    val vm = viewModel(service = null)
    vm.bind(VersionSamples.media, VersionsStart.LIST)
    assertThat(vm.state.value).isEqualTo(VersionsUi.Unavailable)
  }

  @Test
  fun laListaMostraIlPianoComeE() = runTest(dispatcher) {
    keeper.setPlan(VersionSamples.plan())
    val vm = viewModel()
    vm.bind(VersionSamples.media, VersionsStart.LIST)
    val listing = vm.state.value as VersionsUi.Listing
    assertThat(listing.plan.versions).hasSize(3)
    assertThat(listing.canView).isTrue()
    assertThat(listing.canDeleteIntermediates).isTrue()
    // Nessuna scrittura: aprire la lista non muove niente.
    assertThat(keeper.restored).isEmpty()
  }

  @Test
  fun senzaIntermedieNonCeIlTastoPerEliminarle() = runTest(dispatcher) {
    keeper.setPlan(VersionSamples.plan(versions = listOf(VersionSamples.original(), VersionSamples.current())))
    val vm = viewModel()
    vm.bind(VersionSamples.media, VersionsStart.LIST)
    assertThat((vm.state.value as VersionsUi.Listing).canDeleteIntermediates).isFalse()
  }

  @Test
  fun dalMenuRipristinaOriginaleSiApreDirettamenteIlRiepilogo() = runTest(dispatcher) {
    keeper.setPlan(VersionSamples.plan())
    val vm = viewModel()
    vm.bind(VersionSamples.media, VersionsStart.RESTORE)
    assertThat(vm.state.value).isInstanceOf(VersionsUi.ConfirmRestore::class.java)
  }

  @Test
  fun seIlRipristinoNonSiPuoFareLaListaDicePerche() = runTest(dispatcher) {
    keeper.setPlan(VersionSamples.plan(restore = RestoreAvailability.ORIGINAL_NOT_SAFE))
    val vm = viewModel()
    vm.bind(VersionSamples.media, VersionsStart.RESTORE)
    val listing = vm.state.value as VersionsUi.Listing
    assertThat(listing.plan.restore).isEqualTo(RestoreAvailability.ORIGINAL_NOT_SAFE)
  }

  @Test
  fun ilRipristinoFuzionaConLaConfermaDelTocco_NienteSiCarica() = runTest(dispatcher) {
    keeper.setPlan(VersionSamples.plan())
    val vm = viewModel()
    vm.bind(VersionSamples.media, VersionsStart.LIST)
    vm.askRestore()
    val plan = (vm.state.value as VersionsUi.ConfirmRestore).plan
    vm.restore(confirmationFor(plan))
    assertThat(vm.state.value).isEqualTo(VersionsUi.Restored(plan, phoneKeptEdited = false))
    assertThat(keeper.restored).containsExactly(VersionSamples.media)
  }

  @Test
  fun unaConfermaDiUnAltroPianoNonRipristinaNiente_ELoDice() = runTest(dispatcher) {
    keeper.setPlan(VersionSamples.plan())
    val vm = viewModel()
    vm.bind(VersionSamples.media, VersionsStart.RESTORE)
    val shown = (vm.state.value as VersionsUi.ConfirmRestore).plan
    // Nel frattempo e' arrivata una versione nuova: il piano di adesso non e' quello firmato.
    keeper.setPlan(VersionSamples.plan(versions = shown.versions + VersionSamples.intermediate(n = 5)))
    vm.restore(confirmationFor(shown))
    val again = vm.state.value as VersionsUi.ConfirmRestore
    assertThat(again.message).isEqualTo(VersionsMessage.PLAN_CHANGED)
    assertThat(again.plan.versions).hasSize(4)
    assertThat(keeper.restored).isEmpty()
  }

  @Test
  fun unaConfermaVecchiaDiDieciMinutiNonVale() = runTest(dispatcher) {
    keeper.setPlan(VersionSamples.plan())
    val vm = viewModel()
    vm.bind(VersionSamples.media, VersionsStart.RESTORE)
    val plan = (vm.state.value as VersionsUi.ConfirmRestore).plan
    val old = confirmationFor(plan)
    clock.advance(PhoneConfirmationRules.MAX_AGE_MS + 1)
    vm.restore(old)
    assertThat((vm.state.value as VersionsUi.ConfirmRestore).message).isEqualTo(VersionsMessage.CONFIRMATION_EXPIRED)
    assertThat(keeper.restored).isEmpty()
  }

  @Test
  fun conLaVersioneModificataSulTelefonoServeIlPermessoDelSistema() = runTest(dispatcher) {
    keeper.setPlan(VersionSamples.plan())
    keeper.phoneRowsToRewrite[VersionSamples.media] = listOf(VersionSamples.phoneRow)
    val vm = viewModel()
    vm.bind(VersionSamples.media, VersionsStart.RESTORE)
    val plan = (vm.state.value as VersionsUi.ConfirmRestore).plan
    vm.restore(confirmationFor(plan))
    assertThat(vm.state.value).isEqualTo(VersionsUi.AwaitingSystemDialog(plan, rows = 1))
    assertThat(dialogs.writeRequests).hasSize(1)
    assertThat(keeper.restored).isEmpty()

    vm.bridge.answered(true)
    assertThat(vm.state.value).isEqualTo(VersionsUi.Restored(plan, phoneKeptEdited = false))
    assertThat(keeper.restored).containsExactly(VersionSamples.media)
  }

  @Test
  fun senzaIlPermessoDelTelefonoNonSiRipristinaNienteConIlCustodeDiRiferimento() = runTest(dispatcher) {
    keeper.setPlan(VersionSamples.plan())
    keeper.phoneRowsToRewrite[VersionSamples.media] = listOf(VersionSamples.phoneRow)
    val vm = viewModel()
    vm.bind(VersionSamples.media, VersionsStart.RESTORE)
    vm.restore(confirmationFor((vm.state.value as VersionsUi.ConfirmRestore).plan))
    vm.bridge.answered(false)
    assertThat((vm.state.value as VersionsUi.Listing).message).isEqualTo(VersionsMessage.PHONE_PERMISSION_MISSING)
    assertThat(keeper.restored).isEmpty()
  }

  /** Il custode vero (P8): un permesso che non copre le righe = "dialogo negato" -> si scambia solo sul NAS e il telefono tiene la modifica. */
  private class DenyingKeeper(private val plan: NasVersionPlan) : VersionKeeper {
    override suspend fun plan(mediaId: MediaId): NasVersionPlan = plan

    override suspend fun restoreOriginal(mediaId: MediaId, confirmation: PhoneConfirmation, writeGrant: PhoneWriteGrant?): RestoreOutcome =
      if (writeGrant == null) RestoreOutcome.NeedsPhoneWrite(listOf(VersionSamples.phoneRow)) else RestoreOutcome.Queued
  }

  @Test
  fun seLUtenteNegaIlPermessoLOriginaleTornaSulNasEIlTelefonoTieneLaModifica() = runTest(dispatcher) {
    val plan = VersionSamples.plan()
    val vm = viewModel(DenyingKeeper(plan))
    vm.bind(VersionSamples.media, VersionsStart.RESTORE)
    vm.restore(confirmationFor((vm.state.value as VersionsUi.ConfirmRestore).plan))
    vm.bridge.answered(false)
    assertThat(vm.state.value).isEqualTo(VersionsUi.Restored(plan, phoneKeptEdited = true))
  }

  @Test
  fun senzaIlDialogoDelSistemaNonSiRipristinaNiente() = runTest(dispatcher) {
    keeper.setPlan(VersionSamples.plan())
    keeper.phoneRowsToRewrite[VersionSamples.media] = listOf(VersionSamples.phoneRow)
    dialogs.missing = true
    val vm = viewModel()
    vm.bind(VersionSamples.media, VersionsStart.RESTORE)
    vm.restore(confirmationFor((vm.state.value as VersionsUi.ConfirmRestore).plan))
    assertThat((vm.state.value as VersionsUi.Listing).message).isEqualTo(VersionsMessage.NO_SYSTEM_DIALOG)
    assertThat(keeper.restored).isEmpty()
  }

  @Test
  fun unRipristinoNonPossibileNonSiFaEDiceLaRagioneDalPianoRicalcolato() = runTest(dispatcher) {
    keeper.setPlan(VersionSamples.plan())
    val vm = viewModel()
    vm.bind(VersionSamples.media, VersionsStart.RESTORE)
    val shown = (vm.state.value as VersionsUi.ConfirmRestore).plan
    // Il NAS cade fra il riepilogo e il tocco.
    keeper.setPlan(VersionSamples.plan(restore = RestoreAvailability.NAS_UNREACHABLE))
    vm.restore(confirmationFor(shown))
    val listing = vm.state.value as VersionsUi.Listing
    assertThat(listing.plan.restore).isEqualTo(RestoreAvailability.NAS_UNREACHABLE)
    assertThat(keeper.restored).isEmpty()
  }

  @Test
  fun vediEEliminaLeIntermedieSonoUsciteVersoApp() = runTest(dispatcher) {
    keeper.setPlan(VersionSamples.plan())
    val vm = viewModel()
    vm.bind(VersionSamples.media, VersionsStart.LIST)
    vm.view(VersionSamples.versionId(2))
    assertThat(actions.viewed).containsExactly(VersionSamples.versionId(2))
    vm.deleteIntermediates()
    assertThat(actions.deleted).containsExactly(listOf(VersionSamples.versionId(2)))
  }

  @Test
  fun chiudereERiaprireRiparteDaCapo() = runTest(dispatcher) {
    keeper.setPlan(VersionSamples.plan())
    val vm = viewModel()
    vm.bind(VersionSamples.media, VersionsStart.RESTORE)
    vm.restore(confirmationFor((vm.state.value as VersionsUi.ConfirmRestore).plan))
    assertThat(vm.state.value).isInstanceOf(VersionsUi.Restored::class.java)
    vm.reset()
    assertThat(vm.state.value).isEqualTo(VersionsUi.Loading)
    keeper.setPlan(VersionSamples.plan(restore = RestoreAvailability.ALREADY_ORIGINAL))
    vm.bind(VersionSamples.media, VersionsStart.LIST)
    assertThat(vm.state.value).isInstanceOf(VersionsUi.Listing::class.java)
  }
}
