package dev.pampa.mycloudgallery.feature.settings.family

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.FamilyStateQr
import dev.pampa.mycloudgallery.core.family.contract.OldAdminFate
import dev.pampa.mycloudgallery.core.family.contract.RecoveryKitRef
import dev.pampa.mycloudgallery.core.family.contract.RecoveryOutcome
import dev.pampa.mycloudgallery.core.family.testing.FakeQrPayloadCodec
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.qr.contract.ScanRejection
import dev.pampa.mycloudgallery.core.qr.contract.ScanVerdict
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.support.FakeNasAccountSettings
import dev.pampa.mycloudgallery.feature.settings.support.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.util.Optional

class AdminRecoveryViewModelTest {

  @get:Rule
  val main = MainDispatcherRule()

  private val kit = RecoveryKitRef(share = "alessio", fileName = "recovery-01.mcgkit", createdAt = null)
  private val stateCodec = FakeQrPayloadCodec<FamilyStateQr>()

  private class Spy(var outcome: RecoveryOutcome = RecoveryOutcome.Recovered) : dev.pampa.mycloudgallery.core.family.contract.AdminRecovery {
    var code: String? = null
    var codeWipedAfter: Boolean = false
    var familyState: FamilyStateQr? = null
    var typedFingerprint: String? = null
    var fate: OldAdminFate? = null
    var kits: NasResult<List<RecoveryKitRef>> = NasResult.Ok(emptyList())

    var searchedUser: String? = null
    var passwordGiven: String? = null

    override suspend fun findKits(
      account: dev.pampa.mycloudgallery.core.nas.contract.NasAccount,
      privateShare: String,
      password: CharArray?,
    ): NasResult<List<RecoveryKitRef>> {
      searchedUser = account.user
      passwordGiven = password?.let { String(it) }
      return kits
    }

    override suspend fun recover(kit: RecoveryKitRef, code: CharArray, familyState: FamilyStateQr?, oldAdmin: OldAdminFate, typedFingerprint: String?): RecoveryOutcome {
      this.code = String(code)
      this.typedFingerprint = typedFingerprint
      this.familyState = familyState
      this.fate = oldAdmin
      return outcome
    }
  }

  private fun viewModel(replace: Boolean, spy: Spy, accounts: FakeNasAccountSettings? = FakeNasAccountSettings()) = AdminRecoveryViewModel(
    handle = SavedStateHandle(mapOf(AdminRecoveryViewModel.REPLACE to replace)),
    recovery = spy,
    stateCodec = stateCodec,
    accountSettings = Optional.ofNullable(accounts),
  )

  @Test
  fun senzaAccountCollegatoLaPaginaDiceCheNonEDisponibile() = runTest {
    val vm = viewModel(replace = false, spy = Spy(), accounts = null)
    assertThat(vm.state.value.available).isFalse()
    vm.setAdminUser("alessio")
    vm.setAdminPassword("password-di-alessio")
    vm.search()
    assertThat(vm.state.value.stage).isEqualTo(RecoveryStage.SEARCH)
  }

  @Test
  fun ilSecondoAdminLasciaApprovatoIlPrimoELaScelta_nonSiPuoCambiare() = runTest {
    val vm = viewModel(replace = false, spy = Spy())
    assertThat(vm.state.value.oldAdmin).isEqualTo(OldAdminFate.KEEP_APPROVED)
    vm.setOldAdminFate(OldAdminFate.REVOKED)
    assertThat(vm.state.value.oldAdmin).isEqualTo(OldAdminFate.KEEP_APPROVED)
  }

  @Test
  fun ilRecuperoPartePersoESiPuoScegliereRevocato() = runTest {
    val vm = viewModel(replace = true, spy = Spy())
    assertThat(vm.state.value.oldAdmin).isEqualTo(OldAdminFate.LOST)
    vm.setOldAdminFate(OldAdminFate.REVOKED)
    assertThat(vm.state.value.oldAdmin).isEqualTo(OldAdminFate.REVOKED)
    // "Mantienilo approvato" non e' una scelta del recupero.
    vm.setOldAdminFate(OldAdminFate.KEEP_APPROVED)
    assertThat(vm.state.value.oldAdmin).isEqualTo(OldAdminFate.REVOKED)
  }

  @Test
  fun cercaIlKitUnicoEPassaDirettoAlCodice() = runTest {
    val spy = Spy().apply { kits = NasResult.Ok(listOf(kit)) }
    val vm = viewModel(replace = false, spy = spy)
    vm.setAdminUser("alessio")
    vm.setAdminPassword("password-di-alessio")
    vm.search()
    assertThat(vm.state.value.stage).isEqualTo(RecoveryStage.CODE)
    assertThat(vm.state.value.selectedKit).isEqualTo(0)
    assertThat(vm.state.value.privateShare).isEqualTo("alessio")
  }

  @Test
  fun conPiuKitSiScegliePrimaDiDigitareIlCodice() = runTest {
    val spy = Spy().apply { kits = NasResult.Ok(listOf(kit, kit.copy(fileName = "recovery-02.mcgkit"))) }
    val vm = viewModel(replace = false, spy = spy)
    vm.setAdminUser("alessio")
    vm.setAdminPassword("password-di-alessio")
    vm.search()
    assertThat(vm.state.value.stage).isEqualTo(RecoveryStage.PICK)
    vm.pickKit(1)
    assertThat(vm.state.value.stage).isEqualTo(RecoveryStage.CODE)
    assertThat(vm.state.value.selectedKit).isEqualTo(1)
  }

  @Test
  fun nessunKitOUnErroreDelNasDannoUnMessaggioESiResta() = runTest {
    val spy = Spy().apply { kits = NasResult.Ok(emptyList()) }
    val vm = viewModel(replace = false, spy = spy)
    vm.setAdminUser("alessio")
    vm.setAdminPassword("password-di-alessio")
    vm.search()
    assertThat(vm.state.value.stage).isEqualTo(RecoveryStage.SEARCH)
    assertThat(vm.state.value.feedback?.message).isEqualTo(R.string.recovery_no_kit)

    spy.kits = NasResult.Err(NasError.PermissionDenied)
    // La password dell'admin vale una ricerca (P9): per riprovare la si riscrive.
    vm.setAdminPassword("password-di-alessio")
    vm.search()
    assertThat(vm.state.value.feedback?.message).isEqualTo(R.string.nas_error_permission)
  }

  @Test
  fun unAccountNonValidoNonCercaNiente() = runTest {
    val spy = Spy().apply { kits = NasResult.Ok(listOf(kit)) }
    val vm = viewModel(replace = false, spy = spy)
    vm.setAdminUser("con spazio")
    vm.search()
    assertThat(vm.state.value.stage).isEqualTo(RecoveryStage.SEARCH)
    assertThat(vm.state.value.feedback?.message).isEqualTo(R.string.recovery_error_fields)
  }

  @Test
  fun ilCodiceArrivaAlContrattoSenzaTrattiniEIlCampoSiSvuota() = runTest {
    val spy = Spy().apply { kits = NasResult.Ok(listOf(kit)) }
    val vm = viewModel(replace = true, spy = spy)
    vm.setAdminUser("alessio")
    vm.setAdminPassword("password-di-alessio")
    vm.search()
    vm.setCode("abcd-efgh 1234")
    assertThat(vm.state.value.code).isEqualTo("ABCD-EFGH-1234")
    vm.setFingerprint(PAPER)

    vm.recover()

    assertThat(spy.code).isEqualTo("ABCDEFGH1234")
    assertThat(spy.fate).isEqualTo(OldAdminFate.LOST)
    assertThat(vm.state.value.stage).isEqualTo(RecoveryStage.DONE)
    assertThat(vm.state.value.code).isEmpty()
    assertThat(vm.state.value.toString()).doesNotContain("ABCD")
  }

  @Test
  fun ilCodiceSbagliatoNonFaPerdereNienteESiPuoRiscrivere() = runTest {
    val spy = Spy(RecoveryOutcome.WrongCode).apply { kits = NasResult.Ok(listOf(kit)) }
    val vm = viewModel(replace = false, spy = spy)
    vm.setAdminUser("alessio")
    vm.setAdminPassword("password-di-alessio")
    vm.search()
    vm.setCode("AAAA-BBBB")
    vm.setFingerprint(PAPER)
    vm.recover()

    assertThat(vm.state.value.stage).isEqualTo(RecoveryStage.CODE)
    assertThat(vm.state.value.feedback?.message).isEqualTo(R.string.recovery_wrong_code)
    assertThat(vm.state.value.code).isEmpty()
    vm.setCode("CCCC-DDDD")
    spy.outcome = RecoveryOutcome.Recovered
    vm.recover()
    assertThat(vm.state.value.stage).isEqualTo(RecoveryStage.DONE)
  }

  @Test
  fun loStatoDellaFamigliaScansionatoViaggiaConIlRecuperoEAlzaLaVersioneMinima() = runTest {
    val spy = Spy().apply { kits = NasResult.Ok(listOf(kit)) }
    val vm = viewModel(replace = false, spy = spy)
    val payload = FamilyStateQr("famiglia", "impronta", registryVersion = 12, registryHash = "hash")
    stateCodec.known["MCGS1:stato"] = payload
    vm.setAdminUser("alessio")
    vm.setAdminPassword("password-di-alessio")
    vm.search()

    assertThat(vm.onStateScanned("MCGS1:stato")).isEqualTo(ScanVerdict.Accept)
    assertThat(vm.state.value.stateVersion).isEqualTo(12)
    vm.setCode("AAAA-BBBB")
    vm.recover()
    assertThat(spy.familyState).isEqualTo(payload)
    // Con il QR di stato l'impronta del foglio non serve e non viaggia.
    assertThat(spy.typedFingerprint).isNull()
  }

  /**
   * Revisione 2, F3: senza il QR di stato serve l'impronta della famiglia scritta sul foglio (il nome del file sul NAS
   * non e' un'ancora). Senza, il recupero non parte; con, viaggia fino al contratto.
   */
  @Test
  fun senzaLoStatoDellaFamigliaServeLImprontaDelFoglio() = runTest {
    val spy = Spy().apply { kits = NasResult.Ok(listOf(kit)) }
    val vm = viewModel(replace = false, spy = spy)
    vm.setAdminUser("alessio")
    vm.setAdminPassword("password-di-alessio")
    vm.search()
    vm.setCode("AAAA-BBBB")
    assertThat(vm.state.value.canRecover).isFalse()
    vm.recover()
    assertThat(spy.code).isNull()

    vm.setFingerprint("abcd efgh")
    assertThat(vm.state.value.canRecover).isFalse()
    vm.setFingerprint(PAPER)
    assertThat(vm.state.value.canRecover).isTrue()
    vm.recover()
    assertThat(spy.familyState).isNull()
    assertThat(spy.typedFingerprint).isEqualTo(PAPER)
  }

  @Test
  fun iQrSbagliatiAlloStatoSiRifiutanoConIlMotivo() = runTest {
    val vm = viewModel(replace = false, spy = Spy())
    assertThat(vm.onStateScanned("MCG1:invito")).isEqualTo(ScanVerdict.Reject(ScanRejection.NOT_OURS))
    assertThat(vm.onStateScanned("[Interface]")).isEqualTo(ScanVerdict.Reject(ScanRejection.WIREGUARD_SECRET))
    assertThat(vm.onStateScanned("MCGS1:illeggibile")).isEqualTo(ScanVerdict.Reject(ScanRejection.MALFORMED))
  }

  /** P9: l'area privata dell'admin si apre solo con il suo account; la password vale una ricerca e non resta. */
  @Test
  fun conUnAdminDiversoDaQuestoDispositivoServeLaSuaPasswordUnaVolta() = runTest {
    val spy = Spy().apply { kits = NasResult.Ok(listOf(kit)) }
    val vm = viewModel(replace = false, spy = spy)
    vm.setAdminUser("alessio")
    assertThat(vm.state.value.needsAdminPassword).isTrue()
    assertThat(vm.state.value.canSearch).isFalse()
    vm.search()
    assertThat(spy.searchedUser).isNull()

    vm.setAdminPassword("password-di-alessio")
    assertThat(vm.state.value.canSearch).isTrue()
    vm.search()
    assertThat(spy.searchedUser).isEqualTo("alessio")
    assertThat(spy.passwordGiven).isEqualTo("password-di-alessio")
    assertThat(vm.state.value.adminPassword).isEmpty()
    assertThat(vm.state.value.toString()).doesNotContain("password-di-alessio")
  }

  @Test
  fun seLAdminEQuestoDispositivoLaPasswordNonSiChiede() = runTest {
    val spy = Spy().apply { kits = NasResult.Ok(listOf(kit)) }
    val vm = viewModel(replace = false, spy = spy)
    vm.setAdminUser("TEST2")
    assertThat(vm.state.value.needsAdminPassword).isFalse()
    vm.search()
    assertThat(spy.passwordGiven).isNull()
    assertThat(vm.state.value.stage).isEqualTo(RecoveryStage.CODE)
  }
}

/** Un'impronta della famiglia come la si scrive dal foglio. */
private const val PAPER = "ABCD EFGH JKMN PQRS TVWX YZ01 23"
