package dev.pampa.mycloudgallery.feature.settings.devices

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.DeviceKeyQr
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.testing.FakeQrPayloadCodec
import dev.pampa.mycloudgallery.core.family.testing.FakeRegistryService
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasSessionManager
import dev.pampa.mycloudgallery.core.qr.contract.ScanRejection
import dev.pampa.mycloudgallery.core.qr.contract.ScanVerdict
import dev.pampa.mycloudgallery.core.security.lock.AuthResult
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.feature.settings.support.MainDispatcherRule
import dev.pampa.mycloudgallery.feature.settings.support.Samples
import dev.pampa.mycloudgallery.feature.settings.support.keepAlive
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Il flusso di approvazione (piano 2.3, §2.2 lato admin): conferma dell'account, prova con il codice digitato o
 * con il QR della chiave, sblocco del telefono, poi `approve`. Con i fake di core-family.
 */
class ApprovalViewModelTest {

  @get:Rule
  val main = MainDispatcherRule()

  private val codec = FakeQrPayloadCodec<DeviceKeyQr>()

  private fun registry(admin: Boolean = true, requests: List<dev.pampa.mycloudgallery.core.family.contract.PendingRequestView> = listOf(Samples.request)) =
    FakeRegistryService(TrustStatus.Approved(admin), Samples.familyAsAdmin(), requests)

  private fun viewModel(registry: FakeRegistryService, requestId: String = Samples.REQUEST_ID) = ApprovalViewModel(
    handle = SavedStateHandle(mapOf(ApprovalViewModel.REQUEST_ID to requestId)),
    registry = registry,
    deviceKeyCodec = codec,
    sessions = FakeNasSessionManager(),
    clock = Samples.clock,
  )

  private val unlocked: suspend () -> AuthResult = { AuthResult.Success }

  @Test
  fun senzaCodiceCompletoEConfermaDellAccountNonSiPuoApprovare() = runTest {
    val vm = viewModel(registry())
    keepAlive(vm.state)

    assertThat(vm.state.value.canSubmit).isFalse()
    vm.setCode("k7qd 2m9x")
    assertThat(vm.state.value.code).isEqualTo("K7QD-2M9X")
    vm.setAccountConfirmed(true)
    assertThat(vm.state.value.canSubmit).isFalse()

    vm.setCode("k7qd-2m9x-fa4t-1b8c")
    assertThat(vm.state.value.code).isEqualTo("K7QD-2M9X-FA4T-1B8C")
    assertThat(vm.state.value.canSubmit).isTrue()

    vm.setAccountConfirmed(false)
    assertThat(vm.state.value.canSubmit).isFalse()
  }

  @Test
  fun conIlCodiceGiustoApprovaEDopoLoSbloccoDelTelefono() = runTest {
    val registry = registry()
    val vm = viewModel(registry)
    keepAlive(vm.state)
    vm.setCode("K7QD2M9XFA4T1B8C")
    vm.setAccountConfirmed(true)

    var prompts = 0
    vm.approve {
      prompts++
      AuthResult.Success
    }

    assertThat(prompts).isEqualTo(1)
    assertThat(registry.approvals).containsExactly(Samples.REQUEST_ID)
    assertThat(vm.state.value.phase).isEqualTo(ApprovalPhase.DONE)
    // Il codice digitato non resta in memoria dopo l'approvazione.
    assertThat(vm.state.value.code).isEmpty()
  }

  @Test
  fun seIlTelefonoNonConfermaLaCredenzialeNonApprovaNiente() = runTest {
    val registry = registry()
    val vm = viewModel(registry)
    keepAlive(vm.state)
    vm.setCode("K7QD2M9XFA4T1B8C")
    vm.setAccountConfirmed(true)

    vm.approve { AuthResult.Cancelled }
    assertThat(registry.approvals).isEmpty()
    assertThat(vm.state.value.phase).isEqualTo(ApprovalPhase.EDITING)
    assertThat(vm.state.value.feedback).isNull()

    vm.approve { AuthResult.LockedOut(permanent = false) }
    assertThat(registry.approvals).isEmpty()
    assertThat(vm.state.value.feedback).isNotNull()
  }

  @Test
  fun unCodiceSbagliatoDaUnMessaggioEResta() = runTest {
    val registry = registry().apply { nextResult = AdminActionResult.ProofMismatch }
    val vm = viewModel(registry)
    keepAlive(vm.state)
    vm.setCode("AAAAAAAAAAAAAAAA")
    vm.setAccountConfirmed(true)

    vm.approve(unlocked)

    assertThat(vm.state.value.phase).isEqualTo(ApprovalPhase.EDITING)
    assertThat(vm.state.value.feedback?.message).isEqualTo(dev.pampa.mycloudgallery.feature.settings.R.string.fb_mismatch)
  }

  @Test
  fun laFinestraScadutaBloccaLApprovazioneAncheConTuttoGiusto() = runTest {
    val registry = registry(requests = listOf(Samples.expiredRequest))
    val vm = viewModel(registry, Samples.EXPIRED_REQUEST_ID)
    keepAlive(vm.state)
    vm.setCode("K7QD2M9XFA4T1B8C")
    vm.setAccountConfirmed(true)

    assertThat(vm.state.value.canSubmit).isFalse()
    vm.approve(unlocked)
    assertThat(registry.approvals).isEmpty()
  }

  @Test
  fun soloLAdminApprova() = runTest {
    val registry = registry(admin = false)
    val vm = viewModel(registry)
    keepAlive(vm.state)
    vm.setCode("K7QD2M9XFA4T1B8C")
    vm.setAccountConfirmed(true)
    assertThat(vm.state.value.canSubmit).isFalse()
    vm.approve(unlocked)
    assertThat(registry.approvals).isEmpty()
  }

  @Test
  fun seCEUnaVersionePiuNuovaRileggeIlRegistroERiprova() = runTest {
    val registry = registry().apply { nextResult = AdminActionResult.NewerExists }
    val vm = viewModel(registry)
    keepAlive(vm.state)
    vm.setCode("K7QD2M9XFA4T1B8C")
    vm.setAccountConfirmed(true)

    vm.approve(unlocked)

    assertThat(registry.refreshes).containsExactly(Purpose.INTERACTIVE)
    assertThat(vm.state.value.phase).isEqualTo(ApprovalPhase.EDITING)
    assertThat(vm.state.value.feedback).isNotNull()
  }

  @Test
  fun conLaScansioneDellaChiaveDellaRichiestaApprovaConLaChiaveIntera() = runTest {
    val key = Samples.publicKey()
    val text = "MCGK1:chiave-della-richiesta"
    codec.known[text] = DeviceKeyQr("famiglia", Samples.REQUEST_ID, key.encoded)
    val registry = registry()
    val vm = viewModel(registry)
    keepAlive(vm.state)

    vm.setMode(ProofMode.SCAN)
    assertThat(vm.onScanned(text)).isEqualTo(ScanVerdict.Accept)
    assertThat(vm.state.value.scan).isEqualTo(ScanState.MATCHING)
    vm.setAccountConfirmed(true)
    assertThat(vm.state.value.canSubmit).isTrue()

    vm.approve(unlocked)
    assertThat(registry.approvals).containsExactly(Samples.REQUEST_ID)
    assertThat(vm.state.value.phase).isEqualTo(ApprovalPhase.DONE)
  }

  @Test
  fun unQrDiUnAltroDispositivoSiAccettaMaNonSiApprova() = runTest {
    val key = Samples.publicKey()
    val text = "MCGK1:chiave-di-un-altro"
    codec.known[text] = DeviceKeyQr("famiglia", "id-di-un-altro-dispositivo", key.encoded)
    val registry = registry()
    val vm = viewModel(registry)
    keepAlive(vm.state)
    vm.setMode(ProofMode.SCAN)
    vm.setAccountConfirmed(true)

    assertThat(vm.onScanned(text)).isEqualTo(ScanVerdict.Accept)
    assertThat(vm.state.value.scan).isEqualTo(ScanState.WRONG_DEVICE)
    assertThat(vm.state.value.canSubmit).isFalse()
    vm.approve(unlocked)
    assertThat(registry.approvals).isEmpty()

    vm.rescan()
    assertThat(vm.state.value.scan).isEqualTo(ScanState.NONE)
  }

  @Test
  fun iQrNonDellAppOQuelloDiWireGuardSiRifiutanoConIlLoroMotivo() = runTest {
    val vm = viewModel(registry())
    keepAlive(vm.state)

    assertThat(vm.onScanned("https://esempio.it")).isEqualTo(ScanVerdict.Reject(ScanRejection.NOT_OURS))
    assertThat(vm.onScanned("[Interface]\nPrivateKey = x")).isEqualTo(ScanVerdict.Reject(ScanRejection.WIREGUARD_SECRET))
    assertThat(vm.onScanned("MCGK1:non-decodificabile")).isEqualTo(ScanVerdict.Reject(ScanRejection.MALFORMED))
    assertThat(vm.state.value.scan).isEqualTo(ScanState.NONE)
  }

  @Test
  fun inRichiestaNonTrovataNonSiApprovaNiente() = runTest {
    val registry = registry(requests = emptyList())
    val vm = viewModel(registry)
    keepAlive(vm.state)
    assertThat(vm.state.value.request).isNull()
    vm.setCode("K7QD2M9XFA4T1B8C")
    vm.setAccountConfirmed(true)
    vm.approve(unlocked)
    assertThat(registry.approvals).isEmpty()
  }

  @Test
  fun unSecondoToccoMentreApprovaNonApprovaDueVolte() = runTest {
    val registry = registry()
    val vm = viewModel(registry)
    keepAlive(vm.state)
    vm.setCode("K7QD2M9XFA4T1B8C")
    vm.setAccountConfirmed(true)
    vm.approve(unlocked)
    // Dopo Done il codice e' sparito: un altro tocco non ha una prova da inviare.
    vm.approve(unlocked)
    assertThat(registry.approvals).hasSize(1)
  }

  @Suppress("unused")
  private fun proofTypes(): List<Class<out ApprovalProof>> = listOf(ApprovalProof.TypedCode::class.java, ApprovalProof.ScannedKey::class.java)
}
