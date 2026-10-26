package dev.pampa.mycloudgallery.feature.settings.devices

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.testing.FakeLostReporting
import dev.pampa.mycloudgallery.core.family.testing.FakeRegistryService
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasSessionManager
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.feature.settings.support.MainDispatcherRule
import dev.pampa.mycloudgallery.feature.settings.support.Samples
import dev.pampa.mycloudgallery.feature.settings.support.keepAlive
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class DeviceDetailViewModelTest {

  @get:Rule
  val main = MainDispatcherRule()

  private fun viewModel(
    deviceId: String,
    registry: FakeRegistryService,
    lost: FakeLostReporting = FakeLostReporting(),
  ) = DeviceDetailViewModel(
    handle = SavedStateHandle(mapOf(DeviceDetailViewModel.DEVICE_ID to deviceId)),
    registry = registry,
    lostReporting = lost,
    sessions = FakeNasSessionManager(),
    clock = Samples.clock,
  )

  private fun adminRegistry() = FakeRegistryService(TrustStatus.Approved(admin = true), Samples.familyAsAdmin())

  @Test
  fun lAdminPuoGestireUnAltroDispositivoApprovatoMaNonQuelloInUso() = runTest {
    val vm = viewModel(Samples.TABLET_MAMMA, adminRegistry())
    keepAlive(vm.state)
    assertThat(vm.state.value.canManage).isTrue()

    val own = viewModel(Samples.ADMIN_PHONE, adminRegistry())
    keepAlive(own.state)
    assertThat(own.state.value.canManage).isFalse()
    own.startLost()
    assertThat(own.state.value.lostStage).isEqualTo(LostStage.NONE)
  }

  @Test
  fun unDispositivoGiaPersoORevocatoNonSiPuoGestireDiNuovo() = runTest {
    val lost = viewModel(Samples.LOST_PHONE, adminRegistry())
    keepAlive(lost.state)
    assertThat(lost.state.value.canManage).isFalse()
    val revoked = viewModel(Samples.REVOKED_TABLET, adminRegistry())
    keepAlive(revoked.state)
    assertThat(revoked.state.value.canManage).isFalse()
  }

  @Test
  fun segnalarePersoVuoleDueConfermeEDuePresaDAtto() = runTest {
    val registry = adminRegistry()
    val vm = viewModel(Samples.TABLET_MAMMA, registry)
    keepAlive(vm.state)

    // Senza il primo foglio non si scrive niente.
    vm.confirmLost()
    assertThat(registry.statusChanges).isEmpty()

    vm.startLost()
    assertThat(vm.state.value.lostStage).isEqualTo(LostStage.FIRST_CONFIRM)
    // Nemmeno dal primo foglio si salta alla scrittura.
    vm.confirmLost()
    assertThat(registry.statusChanges).isEmpty()

    vm.continueLost()
    assertThat(vm.state.value.lostStage).isEqualTo(LostStage.SECOND_CONFIRM)
    // Secondo foglio, ma senza "ho capito": ancora niente.
    vm.confirmLost()
    assertThat(registry.statusChanges).isEmpty()

    vm.setAcknowledged(true)
    vm.confirmLost()
    assertThat(registry.statusChanges).containsExactly(Samples.TABLET_MAMMA to DeviceStatus.LOST)
    assertThat(vm.state.value.lostStage).isEqualTo(LostStage.GUIDE)
  }

  @Test
  fun annullareDalPrimoODalSecondoFoglioNonScriveNiente() = runTest {
    val registry = adminRegistry()
    val vm = viewModel(Samples.TABLET_MAMMA, registry)
    keepAlive(vm.state)

    vm.startLost()
    vm.cancelLost()
    assertThat(vm.state.value.lostStage).isEqualTo(LostStage.NONE)

    vm.startLost()
    vm.continueLost()
    vm.setAcknowledged(true)
    vm.cancelLost()
    assertThat(vm.state.value.lostStage).isEqualTo(LostStage.NONE)
    assertThat(vm.state.value.acknowledged).isFalse()
    assertThat(registry.statusChanges).isEmpty()
  }

  @Test
  fun laGuidaVaInOrdineSegnalaRevocaPasswordSamsungFind() {
    assertThat(GuideStep.ordered).containsExactly(
      GuideStep.REPORT,
      GuideStep.REVOKE_PEER,
      GuideStep.CHANGE_PASSWORD,
      GuideStep.FIND_DEVICE,
    ).inOrder()
  }

  @Test
  fun laGuidaSbloccaUnPassoAllaVoltaENonSiCambiaLaPasswordPrimaDiRevocareIlPeer() = runTest {
    val vm = viewModel(Samples.TABLET_MAMMA, adminRegistry())
    keepAlive(vm.state)
    vm.startLost()
    vm.continueLost()
    vm.setAcknowledged(true)
    vm.confirmLost()

    // La segnalazione e' fatta: il passo 1 e' gia' segnato.
    assertThat(vm.state.value.guideDone).containsExactly(GuideStep.REPORT)
    assertThat(vm.state.value.nextGuideStep).isEqualTo(GuideStep.REVOKE_PEER)

    // Saltare un passo non e' ammesso.
    vm.markGuideStepDone(GuideStep.CHANGE_PASSWORD)
    vm.markGuideStepDone(GuideStep.FIND_DEVICE)
    assertThat(vm.state.value.guideDone).containsExactly(GuideStep.REPORT)

    vm.markGuideStepDone(GuideStep.REVOKE_PEER)
    vm.markGuideStepDone(GuideStep.CHANGE_PASSWORD)
    assertThat(vm.state.value.guideDone)
      .containsExactly(GuideStep.REPORT, GuideStep.REVOKE_PEER, GuideStep.CHANGE_PASSWORD)
    assertThat(vm.state.value.nextGuideStep).isEqualTo(GuideStep.FIND_DEVICE)
  }

  @Test
  fun seLaScritturaFallisceNonSiApreLaGuidaEIlMessaggioDiceCosaFare() = runTest {
    val registry = adminRegistry().apply { nextResult = AdminActionResult.Failed(NasError.Timeout) }
    val vm = viewModel(Samples.TABLET_MAMMA, registry)
    keepAlive(vm.state)

    vm.startLost()
    vm.continueLost()
    vm.setAcknowledged(true)
    vm.confirmLost()

    assertThat(vm.state.value.lostStage).isEqualTo(LostStage.NONE)
    assertThat(vm.state.value.guideDone).isEmpty()
    assertThat(vm.state.value.feedback).isNotNull()
  }

  @Test
  fun unSecondoTocco_mentreScrive_nonRipeteLaSegnalazione() = runTest {
    val registry = adminRegistry()
    val vm = viewModel(Samples.TABLET_MAMMA, registry)
    keepAlive(vm.state)
    vm.startLost()
    vm.continueLost()
    vm.setAcknowledged(true)
    vm.confirmLost()
    vm.confirmLost()
    assertThat(registry.statusChanges).hasSize(1)
  }

  @Test
  fun ilProprietarioSegnalaUnProprioDispositivoESegnalaComeProprietario() = runTest {
    // Stesso account NAS: e' un proprio dispositivo.
    val sameAccount = Samples.tabletMamma().copy(nasUser = "anna")
    val registry = FakeRegistryService(
      TrustStatus.Approved(admin = false),
      listOf(Samples.adminPhone(thisDevice = false), sameAccount, Samples.phoneAnna(thisDevice = true)),
    )
    val lost = FakeLostReporting()
    val vm = viewModel(Samples.TABLET_MAMMA, registry, lost)
    keepAlive(vm.state)

    assertThat(vm.state.value.canManage).isFalse()
    assertThat(vm.state.value.canReportOwn).isTrue()
    vm.startLost()
    vm.continueLost()
    vm.setAcknowledged(true)
    vm.confirmLost()

    assertThat(lost.reported).containsExactly(Samples.TABLET_MAMMA)
    assertThat(registry.statusChanges).isEmpty()
    assertThat(vm.state.value.reportedByOwner).isTrue()
    assertThat(vm.state.value.lostStage).isEqualTo(LostStage.GUIDE)
  }

  @Test
  fun unFamiliareNonPuoSegnalareIlDispositivoDiUnAltroAccount() = runTest {
    val registry = FakeRegistryService(TrustStatus.Approved(admin = false), Samples.familyAsMember())
    val vm = viewModel(Samples.TABLET_MAMMA, registry)
    keepAlive(vm.state)
    assertThat(vm.state.value.canReportOwn).isFalse()
    vm.startLost()
    assertThat(vm.state.value.lostStage).isEqualTo(LostStage.NONE)
  }

  @Test
  fun revocareEDismettereHannoUnaConfermaEScrivonoLoStatoGiusto() = runTest {
    val registry = adminRegistry()
    val vm = viewModel(Samples.PHONE_ANNA, registry)
    keepAlive(vm.state)

    vm.confirmChange()
    assertThat(registry.statusChanges).isEmpty()

    vm.askChange(PendingChange.REVOKE)
    vm.cancelChange()
    vm.confirmChange()
    assertThat(registry.statusChanges).isEmpty()

    vm.askChange(PendingChange.REVOKE)
    vm.confirmChange()
    assertThat(registry.statusChanges).containsExactly(Samples.PHONE_ANNA to DeviceStatus.REVOKED)

    vm.askChange(PendingChange.RETIRE)
    vm.confirmChange()
    assertThat(registry.statusChanges.last()).isEqualTo(Samples.PHONE_ANNA to DeviceStatus.RETIRED)
  }

  @Test
  fun unDispositivoPersoPermetteDiRiaprireLaGuida() = runTest {
    val vm = viewModel(Samples.LOST_PHONE, adminRegistry())
    keepAlive(vm.state)
    vm.openGuide()
    assertThat(vm.state.value.lostStage).isEqualTo(LostStage.GUIDE)
    assertThat(vm.state.value.guideDone).containsExactly(GuideStep.REPORT)
    vm.closeGuide()
    assertThat(vm.state.value.lostStage).isEqualTo(LostStage.NONE)
  }

  // --- F27: "Rinomina questo dispositivo" -------------------------------------------------------------------------

  @Test
  fun f27_laRinominaCeSoloPerLAdminSulDispositivoInUso() = runTest {
    val own = viewModel(Samples.ADMIN_PHONE, adminRegistry())
    keepAlive(own.state)
    assertThat(own.state.value.canRename).isTrue()

    // Un altro dispositivo non si rinomina da qui (le azioni su di lui sono altre).
    val other = viewModel(Samples.TABLET_MAMMA, adminRegistry())
    keepAlive(other.state)
    assertThat(other.state.value.canRename).isFalse()
    other.startRename()
    assertThat(other.state.value.rename).isNull()

    // Un familiare non admin non firma il registro: niente voce, niente foglio.
    val member = viewModel(Samples.PHONE_ANNA, FakeRegistryService(TrustStatus.Approved(admin = false), Samples.familyAsMember()))
    keepAlive(member.state)
    assertThat(member.state.value.canRename).isFalse()
    member.startRename()
    assertThat(member.state.value.rename).isNull()
  }

  @Test
  fun f27_ilFoglioParteDalNomeDiAdessoEPerConfermareVuoleUnNomeDiverso() = runTest {
    val registry = adminRegistry()
    val vm = viewModel(Samples.ADMIN_PHONE, registry)
    keepAlive(vm.state)

    vm.startRename()
    assertThat(vm.state.value.rename?.text).isEqualTo("Telefono di Alessio")
    assertThat(vm.state.value.rename?.problem).isEqualTo(RenameProblem.SAME)
    // Con il nome di adesso non si scrive niente.
    vm.confirmRename()
    assertThat(registry.renames).isEmpty()

    vm.onRenameText("Telefono di Mario")
    assertThat(vm.state.value.rename?.problem).isNull()
    vm.confirmRename()

    assertThat(registry.renames).containsExactly(Samples.ADMIN_PHONE to "Telefono di Mario")
    assertThat(vm.state.value.rename).isNull()
    assertThat(vm.state.value.feedback?.message).isEqualTo(dev.pampa.mycloudgallery.feature.settings.R.string.rename_done)
  }

  @Test
  fun f27_ilNomeSiControllaMentreSiScriveEUnNomeNonValidoNonParte() = runTest {
    val registry = adminRegistry()
    val vm = viewModel(Samples.ADMIN_PHONE, registry)
    keepAlive(vm.state)
    vm.startRename()

    fun problemOf(text: String): RenameProblem? {
      vm.onRenameText(text)
      return vm.state.value.rename?.problem
    }

    assertThat(problemOf("")).isEqualTo(RenameProblem.EMPTY)
    assertThat(problemOf("   ")).isEqualTo(RenameProblem.EMPTY)
    assertThat(problemOf("A".repeat(65))).isEqualTo(RenameProblem.TOO_LONG)
    assertThat(problemOf("Telefono‮")).isEqualTo(RenameProblem.INVALID)
    // Uguale (o "sembra uguale") al nome di un altro dispositivo: due nomi uguali farebbero sbagliare chi approva o revoca.
    assertThat(problemOf("Tablet — profilo Mamma")).isEqualTo(RenameProblem.TAKEN)
    assertThat(problemOf("TABLET — profilo   mamma")).isEqualTo(RenameProblem.TAKEN)
    // Gli spazi ai bordi si tolgono, il resto va bene.
    assertThat(problemOf("  Telefono di Mario  ")).isNull()

    vm.onRenameText("Tablet — profilo Mamma")
    vm.confirmRename()
    assertThat(registry.renames).isEmpty()

    vm.onRenameText("  Telefono di Mario  ")
    vm.confirmRename()
    assertThat(registry.renames).containsExactly(Samples.ADMIN_PHONE to "Telefono di Mario")
  }

  @Test
  fun f27_annullareNonScriveNienteEUnaScritturaChePassaDicePerche() = runTest {
    val registry = adminRegistry()
    val vm = viewModel(Samples.ADMIN_PHONE, registry)
    keepAlive(vm.state)

    vm.startRename()
    vm.onRenameText("Telefono di Mario")
    vm.cancelRename()
    assertThat(vm.state.value.rename).isNull()
    vm.confirmRename()
    assertThat(registry.renames).isEmpty()

    // Il registro risponde che il nome c'e' gia' (un altro admin l'ha preso nel frattempo): la pagina lo dice, non dice "fatto".
    registry.nextResult = AdminActionResult.Failed(NasError.Exists)
    vm.startRename()
    vm.onRenameText("Telefono di Mario")
    vm.confirmRename()
    assertThat(vm.state.value.feedback?.message).isEqualTo(dev.pampa.mycloudgallery.feature.settings.R.string.rename_err_taken)

    // Il NAS non risponde: l'errore di sempre, mai un finto "fatto".
    registry.nextResult = AdminActionResult.Failed(NasError.Timeout)
    vm.startRename()
    vm.onRenameText("Telefono di Luca")
    vm.confirmRename()
    assertThat(vm.state.value.feedback?.message).isNotEqualTo(dev.pampa.mycloudgallery.feature.settings.R.string.rename_done)
  }
}
