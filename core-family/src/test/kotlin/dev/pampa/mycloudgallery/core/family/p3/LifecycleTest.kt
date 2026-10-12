package dev.pampa.mycloudgallery.core.family.p3

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.FamilyStateQr
import dev.pampa.mycloudgallery.core.family.contract.OldAdminFate
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.PreflightResult
import dev.pampa.mycloudgallery.core.family.contract.RecoveryOutcome
import dev.pampa.mycloudgallery.core.family.contract.ResetOutcome
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.contract.WipeReason
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.crypto.RecoveryCode
import dev.pampa.mycloudgallery.core.trust.crypto.RecoveryKit
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.RegistrySync
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il ciclo di vita completo sul NAS ostile (§2.3): segnalazione "perso" dal proprietario e conversione
 * dell'admin, recupero dell'admin con il kit, secondo admin, reset della famiglia.
 */
class LifecycleTest {

  private val world = FamilyWorld()
  private val account = NasAccount(NasAddress("192.168.178.58"), "admin2", "slot", "0f8fad5b-d9cb-469f-a165-70867728950e")

  /** Lo stato della famiglia mostrato come QR (`MCGS1:`) da un dispositivo approvato. */
  private fun stateQr(device: Device): FamilyStateQr {
    val state = device.engine.state!!
    val anchor = state.toAnchor()!!
    return FamilyStateQr(anchor.familyId, anchor.familyKeyFingerprint, state.registry.version, state.contentHash)
  }

  /** Un telefono nuovo dell'admin: stesso account, nessuna chiave, nessuno stato. */
  private fun newAdminPhone(name: String = "Telefono nuovo di Admin"): Device = Device(world, name, "admin2", privateShare = "admin2")

  // --- Smarrimento ---------------------------------------------------------------------------------------

  @Test
  fun ilProprietarioSegnalaLAdminConverteEIlPersoSiAzzera() = runTest {
    val tablet = world.joinApproved("Tablet di Mamma", "mamma", privateShare = null) // niente area privata: solo admin
    val phone = world.joinApproved("Telefono di Mamma", "mamma")
    val babbo = world.joinApproved("Telefono di Babbo", "babbo")
    val tabletId = tablet.id
    phone.service.refresh(Purpose.BACKGROUND)
    babbo.service.refresh(Purpose.BACKGROUND)

    assertThat(babbo.lostReporting.reportOwnDeviceLost(tabletId)).isEqualTo(AdminActionResult.NotAdmin) // altro account
    assertThat(phone.lostReporting.reportOwnDeviceLost(tabletId)).isEqualTo(AdminActionResult.Done)
    assertThat(world.common.exists(NasLayout.reportFile(tabletId, 1))).isTrue()
    assertThat(world.server.share("mamma").exists(NasLayout.reportFile(tabletId, 1))).isTrue()

    // Il tablet non ha l'area privata nel profilo: la segnalazione la converte l'admin.
    tablet.later(DAY)
    assertThat(tablet.guard.preflight(Purpose.BACKGROUND)).isInstanceOf(PreflightResult.Proceed::class.java)
    assertThat(world.admin.lostReporting.convertReports()).isEqualTo(AdminActionResult.Done)
    assertThat(world.admin.engine.state!!.statusOf(tabletId)).isEqualTo(DeviceStatus.LOST)
    assertThat(phone.lostReporting.convertReports()).isEqualTo(AdminActionResult.NotAdmin)

    tablet.later(DAY)
    assertThat(tablet.guard.preflight(Purpose.BACKGROUND)).isEqualTo(PreflightResult.WipeStarted)
    assertThat(tablet.selfWipe.record.value!!.reason).isEqualTo(WipeReason.MARKED_LOST)
  }

  // --- Recupero dell'admin -------------------------------------------------------------------------------

  @Test
  fun recuperoDellAdmin_conIlKitEIlQrDiStato_ilVecchioDiventaPerso() = runTest {
    val tablet = world.joinApproved("Tablet di Mamma", "mamma")
    val oldAdmin = world.admin
    val oldAdminId = oldAdmin.id
    val qr = stateQr(tablet)
    val phone = newAdminPhone()

    val kits = (phone.recovery.findKits(account, "admin2") as NasResult.Ok).value
    assertThat(kits.map { it.fileName }).containsExactly("${world.familyId}/${world.familyKey.publicKey.fingerprint}")

    val code = world.recoveryCode.toDisplayString().toCharArray()
    val outcome = phone.recovery.recover(kits.single(), code, qr, OldAdminFate.LOST)

    assertThat(outcome).isEqualTo(RecoveryOutcome.Recovered)
    assertThat(code.all { it == '\u0000' }).isTrue() // il codice si azzera
    val state = phone.engine.state!!
    assertThat(state.registry.device(phone.id)!!.admin).isTrue()
    assertThat(state.statusOf(oldAdminId)).isEqualTo(DeviceStatus.LOST)
    assertThat(phone.vault.key!!.publicKey).isEqualTo(world.familyKey.publicKey)
    assertThat(phone.engine.status.value).isEqualTo(TrustStatus.Approved(admin = true))
    // Il nuovo admin lavora: approva un dispositivo.
    val babbo = world.join("Telefono di Babbo", "babbo")
    phone.service.refresh(Purpose.INTERACTIVE)
    assertThat(phone.service.approve(babbo.id, ApprovalProof.TypedCode(babbo.shownCode))).isEqualTo(AdminActionResult.Done)
    // Il vecchio telefono, se si ricollega, si azzera.
    oldAdmin.later(DAY)
    assertThat(oldAdmin.guard.preflight(Purpose.BACKGROUND)).isEqualTo(PreflightResult.WipeStarted)
    assertThat(oldAdmin.vault.key).isNull()
    // Il tablet vede il nuovo admin.
    tablet.service.refresh(Purpose.BACKGROUND)
    assertThat(tablet.service.devices.value.single { it.id == phone.id }.admin).isTrue()
  }

  @Test
  fun recuperoDellAdmin_codiceSbagliatoOConUnErrore() = runTest {
    val phone = newAdminPhone()
    val kit = (phone.recovery.findKits(account, "admin2") as NasResult.Ok).value.single()

    val other = RecoveryCode.generate().toDisplayString().toCharArray()
    assertThat(phone.recovery.recover(kit, other, null, OldAdminFate.REVOKED)).isEqualTo(RecoveryOutcome.WrongCode)
    val typo = world.recoveryCode.toDisplayString().let { it.substring(0, 5) + (if (it[5] == 'X') 'Y' else 'X') + it.substring(6) }
    assertThat(phone.recovery.recover(kit, typo.toCharArray(), null, OldAdminFate.REVOKED)).isEqualTo(RecoveryOutcome.WrongCode)
    assertThat(phone.vault.key).isNull()
    assertThat(world.common.exists(NasLayout.registryFile(2))).isFalse()
  }

  @Test
  fun recuperoDellAdmin_unQrDiStatoDiUnAltraChiaveFermaSubito_M7() = runTest {
    val phone = newAdminPhone()
    val kit = (phone.recovery.findKits(account, "admin2") as NasResult.Ok).value.single()
    val foreign = FamilyStateQr(world.familyId, FamilyKey.generate().publicKey.fingerprint, 1, "x")

    val outcome = phone.recovery.recover(kit, world.recoveryCode.toDisplayString().toCharArray(), foreign, OldAdminFate.LOST)

    assertThat(outcome).isEqualTo(RecoveryOutcome.Failed(OnboardingProblem.RECOVERY_KIT_UNVERIFIED))
    assertThat(phone.vault.key).isNull()
  }

  @Test
  fun recuperoDellAdmin_unKitPiantatoConUnAltraChiaveNonPortaANulla() = runTest {
    // Chi conosce il codice (il foglio) e scrive nell'area privata mette un kit con la sua chiave.
    val attackerKey = FamilyKey.generate()
    val planted = RecoveryKit.seal(attackerKey, world.familyId, world.recoveryCode, world.clock.now)
    world.server.share("admin2").attackerWrite(NasLayout.recoveryKitFile(world.familyId, attackerKey.publicKey.fingerprint), planted)
    world.server.share("admin2").attackerWrite(NasLayout.RECOVERY_DIR.child("non-un-id").child("x"), ByteArray(3))
    val phone = newAdminPhone()
    val kits = (phone.recovery.findKits(account, "admin2") as NasResult.Ok).value
    assertThat(kits).hasSize(2) // i nomi strani sono ignorati
    val fake = kits.single { it.fileName.endsWith(attackerKey.publicKey.fingerprint) }

    // Con il QR di stato: rifiutato prima di decifrare. Senza: il registro firmato dalla famiglia non lo accetta.
    assertThat(phone.recovery.recover(fake, world.recoveryCode.toDisplayString().toCharArray(), stateQr(world.admin.also { it.engine.ensureLoaded() }), OldAdminFate.LOST))
      .isEqualTo(RecoveryOutcome.Failed(OnboardingProblem.RECOVERY_KIT_UNVERIFIED))
    assertThat(phone.recovery.recover(fake, world.recoveryCode.toDisplayString().toCharArray(), null, OldAdminFate.LOST))
      .isEqualTo(RecoveryOutcome.Failed(OnboardingProblem.REGISTRY_UNTRUSTED))
    assertThat(world.admin.engine.state!!.registry.version).isEqualTo(1)
    assertThat(world.common.exists(NasLayout.registryFile(2))).isFalse()
  }

  @Test
  fun secondoAdmin_ilPrimoRestaApprovatoEEntrambiAmministrano() = runTest {
    world.admin.engine.ensureLoaded()
    val tabletProfile = newAdminPhone("Tablet — profilo Admin")
    val kit = (tabletProfile.recovery.findKits(account, "admin2") as NasResult.Ok).value.single()

    val outcome = tabletProfile.recovery.recover(kit, world.recoveryCode.toDisplayString().toCharArray(), stateQr(world.admin), OldAdminFate.KEEP_APPROVED)

    assertThat(outcome).isEqualTo(RecoveryOutcome.Recovered)
    val state = tabletProfile.engine.state!!
    assertThat(state.registry.devices.filter { it.admin && it.status == DeviceStatus.APPROVED }.map { it.id })
      .containsExactly(world.admin.id, tabletProfile.id)
    // Il primo admin (su una base ormai vecchia) pubblica comunque: risincronizza e riprova.
    val babbo = world.join("Telefono di Babbo", "babbo")
    world.admin.service.refresh(Purpose.INTERACTIVE)
    assertThat(world.admin.service.approve(babbo.id, ApprovalProof.TypedCode(babbo.shownCode))).isEqualTo(AdminActionResult.Done)
    tabletProfile.service.refresh(Purpose.INTERACTIVE)
    assertThat(tabletProfile.engine.state!!.canWrite(babbo.id)).isTrue()
    assertThat(tabletProfile.service.changeStatus(babbo.id, DeviceStatus.REVOKED)).isEqualTo(AdminActionResult.Done)
  }

  @Test
  fun secondoAdmin_suUnDispositivoGiaApprovatoCambiaSoloIlSegno() = runTest {
    val member = world.joinApproved("Tablet — profilo Admin", "admin2", privateShare = "admin2")
    val kit = (member.recovery.findKits(account, "admin2") as NasResult.Ok).value.single()

    assertThat(member.recovery.recover(kit, world.recoveryCode.toDisplayString().toCharArray(), stateQr(member), OldAdminFate.KEEP_APPROVED))
      .isEqualTo(RecoveryOutcome.Recovered)

    assertThat(member.engine.state!!.registry.device(member.id)!!.admin).isTrue()
    assertThat(member.engine.state!!.registry.devices).hasSize(2)
  }

  // --- Reset della famiglia --------------------------------------------------------------------------------

  @Test
  fun resetDellaFamiglia_famigliaNuovaKitNuovoIDispositiviVecchiIgnorati() = runTest {
    val tablet = world.joinApproved("Tablet di Mamma", "mamma")
    val oldVersion = world.admin.engine.state!!.registry.version
    assertThat(world.admin.reset.reset(confirmedTwice = false)).isEqualTo(ResetOutcome.Failed(OnboardingProblem.STEP_NOT_AVAILABLE))

    val outcome = world.admin.reset.reset(confirmedTwice = true)

    assertThat(outcome).isInstanceOf(ResetOutcome.Done::class.java)
    val newFamilyId = (outcome as ResetOutcome.Done).newFamilyId
    assertThat(newFamilyId).isNotEqualTo(world.familyId)
    val state = world.admin.engine.state!!
    assertThat(state.registry.familyId).isEqualTo(newFamilyId)
    assertThat(state.registry.version).isGreaterThan(oldVersion)
    assertThat(state.registry.devices.map { it.id }).containsExactly(world.admin.id) // gli altri si riconfigurano
    assertThat(world.admin.engine.status.value).isEqualTo(TrustStatus.Approved(admin = true))
    val newKey = world.admin.vault.key!!
    assertThat(newKey.publicKey).isNotEqualTo(world.familyKey.publicKey)

    // Il codice nuovo si mostra, si riscrive e apre il kit nuovo.
    val display = world.admin.reset.recoveryCodeForDisplay()!!
    val typed = display.groups.joinToString("-") { String(it) }
    val kitBytes = world.server.share("admin2").bytesAt(NasLayout.recoveryKitFile(newFamilyId, newKey.publicKey.fingerprint))!!
    assertThat(RecoveryKit.open(kitBytes, RecoveryCode.parse(typed)!!, newFamilyId, newKey.publicKey.fingerprint)).isInstanceOf(Verdict.Accepted::class.java)
    assertThat(world.admin.reset.confirmTypedCode("AAAA".toCharArray())).isFalse()
    assertThat(world.admin.reset.confirmTypedCode(typed.toCharArray())).isTrue()
    assertThat(world.admin.reset.recoveryCodeForDisplay()).isNull()

    // Il QR nuovo porta un dispositivo nuovo nella famiglia nuova.
    val anchor = state.toAnchor()!!
    val sync = RegistrySync(world.common).refresh(null, anchor)
    assertThat(sync.state!!.registry.familyId).isEqualTo(newFamilyId)
    // Il tablet non migrato resta nella famiglia vecchia e non si fa ingannare dalla nuova.
    tablet.later(DAY)
    assertThat(tablet.guard.preflight(Purpose.BACKGROUND)).isInstanceOf(PreflightResult.Proceed::class.java)
    assertThat(tablet.engine.state!!.registry.familyId).isEqualTo(world.familyId)
    // Il vecchio kit resta dov'e' (ignorato, non cancellato).
    assertThat(world.server.share("admin2").exists(NasLayout.recoveryKitFile(world.familyId, world.familyKey.publicKey.fingerprint))).isTrue()
  }

  @Test
  fun resetDellaFamiglia_senzaBloccoSchermoNonParteENonPerdeLaChiaveVecchia() = runTest {
    world.admin.engine.ensureLoaded()
    world.admin.vault.deviceSecure = false

    assertThat(world.admin.reset.reset(confirmedTwice = true)).isEqualTo(ResetOutcome.Failed(OnboardingProblem.CREDENTIALS_NOT_SAVED))

    world.admin.vault.deviceSecure = true
    assertThat(world.admin.vault.key!!.publicKey).isEqualTo(world.familyKey.publicKey)
    assertThat(world.admin.engine.state!!.registry.familyId).isEqualTo(world.familyId)
  }

  @Test
  fun resetDellaFamiglia_seIlNasNonScriveSiTornaAllaChiaveVecchia() = runTest {
    world.admin.engine.ensureLoaded()
    world.server.share("admin2").failWith = dev.pampa.mycloudgallery.core.nas.contract.NasError.PermissionDenied

    val outcome = world.admin.reset.reset(confirmedTwice = true)

    assertThat(outcome).isInstanceOf(ResetOutcome.Failed::class.java)
    assertThat(world.admin.vault.key!!.publicKey).isEqualTo(world.familyKey.publicKey)
    assertThat(world.admin.engine.state!!.registry.familyId).isEqualTo(world.familyId)
  }

  private companion object {
    const val DAY = 24L * 60 * 60 * 1000
  }
}
