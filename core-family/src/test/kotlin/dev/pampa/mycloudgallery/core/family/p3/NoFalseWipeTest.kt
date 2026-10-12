package dev.pampa.mycloudgallery.core.family.p3

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.PreflightResult
import dev.pampa.mycloudgallery.core.family.contract.ReadOnlyReason
import dev.pampa.mycloudgallery.core.family.contract.StopReason
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.contract.WipePhase
import dev.pampa.mycloudgallery.core.family.contract.WipeReason
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.registry.CommitResult
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.FamilyAuthority
import dev.pampa.mycloudgallery.core.trust.registry.LostReport
import dev.pampa.mycloudgallery.core.trust.registry.LostReports
import dev.pampa.mycloudgallery.core.trust.registry.RegistryDraft
import dev.pampa.mycloudgallery.core.trust.registry.RegistrationRequest
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.registry.RegistryVerifier
import dev.pampa.mycloudgallery.core.trust.registry.TrustAnchor
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Test obbligatorio di P3 (§2.3, `docs/fase2-progetto.md` §7 "Azzeramento innescato da dati remoti"): un
 * registro falso, un rollback, un'equivocazione o una regressione **non** fanno mai azzerare; nemmeno un
 * registro troncato, un NAS che non risponde, una segnalazione "perso" falsa, di un altro account, di un
 * dispositivo non approvato o trovata solo nell'area comune. Azzerano solo lo stato `lost` in un registro
 * firmato e **accettato** e una segnalazione valida nell'area privata dell'account (decisione 3(a)).
 */
class NoFalseWipeTest {

  private val world = FamilyWorld()

  /** Il tablet di Mamma approvato (v2), un telefono X approvato (v3) e poi revocato (v4). Il tablet e' alla v4. */
  private suspend fun timeline(): Triple<Device, Device, List<RegistryState>> {
    val tablet = world.joinApproved("Tablet di Mamma", "mamma")
    val history = mutableListOf(world.admin.engine.state!!)
    val x = world.joinApproved("Telefono X", "babbo")
    history += world.admin.engine.state!!
    world.admin.service.changeStatus(x.id, DeviceStatus.REVOKED)
    history += world.admin.engine.state!!
    tablet.service.refresh(Purpose.BACKGROUND)
    check(tablet.engine.state!!.registry.version == 4L)
    return Triple(tablet, x, history)
  }

  /**
   * Firma con la chiave di famiglia una versione derivata da [base] (come farebbe un secondo admin rimasto
   * indietro, o chi ha rubato la chiave), su una share di prova che contiene solo la storia fino a [base] e,
   * se [junkUntil] > 0, spazzatura fino a quel nome: cosi' la versione esce al numero voluto.
   */
  private fun forge(base: RegistryState, junkUntil: Long = 0, edit: (FamilyAuthority, RegistryState) -> RegistryDraft): RegistryState {
    val scratch = HostileNas("scratch")
    for (v in 1..base.registry.version) scratch.attackerWrite(NasLayout.registryFile(v), world.common.bytesAt(NasLayout.registryFile(v))!!)
    for (v in base.registry.version + 1..junkUntil) scratch.attackerWrite(NasLayout.registryFile(v), "spazzatura".toByteArray())
    val authority = FamilyAuthority(world.familyKey, world.clock)
    return (authority.commit(scratch, edit(authority, base)) as CommitResult.Published).state
  }

  private suspend fun assertNotWiped(device: Device) {
    assertThat(device.selfWipe.progress.value).isNull()
    assertThat(device.keys.hasDeviceKey()).isTrue()
    assertThat(device.log.all()).doesNotContain("keys:destroyDeviceKey")
    assertThat(device.engine.status.value).isNotEqualTo(TrustStatus.Lost)
  }

  @Test
  fun unRegistroFirmatoDaUnAltraChiaveNonFaAzzerare() = runTest {
    val (tablet, _, _) = timeline()
    val me = tablet.id
    // L'attaccante fonda una "famiglia" con lo stesso id e la sua chiave, ci mette il tablet (la richiesta e'
    // leggibile nell'area comune) e lo segna perso, poi pianta il file al nome successivo.
    val attackerKey = FamilyKey.generate()
    val scratch = HostileNas("scratch")
    val attacker = FamilyAuthority(attackerKey, world.clock)
    val tabletRequest = (RegistrationRequest.verify(world.common.bytesAt(NasLayout.requestFile(me))!!, world.familyId, me) as Verdict.Accepted).value
    val founded = attacker.found(world.familyId, world.config, tabletRequest, version = 1)
    scratch.publishExclusive(NasLayout.registryFile(1), founded.bytes)
    val fake0 = (RegistryVerifier.accept(founded.bytes, null, TrustAnchor.create(world.familyId, attackerKey.publicKey.fingerprint, 1)!!) as Verdict.Accepted).value
    val fake = (attacker.commit(scratch, attacker.changeStatus(fake0, me, DeviceStatus.LOST)) as CommitResult.Published).state
    world.common.attackerWrite(NasLayout.registryFile(5), fake.signedBytes)

    tablet.later(DAY)
    val result = tablet.guard.preflight(Purpose.BACKGROUND)

    assertThat(result).isInstanceOf(PreflightResult.Proceed::class.java)
    assertNotWiped(tablet)
    assertThat(tablet.engine.facts.value.rejected).isNotEmpty()
  }

  @Test
  fun unaVersioneVecchiaFirmataDallaFamigliaNonFaAzzerare_rollback() = runTest {
    val (tablet, _, history) = timeline()
    val rolledBack = forge(history[0]) { a, base -> a.changeStatus(base, tablet.id, DeviceStatus.LOST) } // versione 3 con "perso"
    check(rolledBack.registry.version == 3L)
    world.common.attackerWrite(NasLayout.registryFile(5), rolledBack.signedBytes) // al nome dopo la v4
    world.common.attackerDelete(NasLayout.registryFile(3))
    world.common.attackerWrite(NasLayout.registryFile(3), rolledBack.signedBytes) // e al posto della v3

    tablet.later(DAY)
    tablet.guard.preflight(Purpose.BACKGROUND)
    tablet.service.refresh(Purpose.BACKGROUND)

    assertNotWiped(tablet)
    assertThat(tablet.engine.state!!.registry.version).isEqualTo(4L)
  }

  @Test
  fun unEquivocazioneFirmataDallaFamigliaNonFaAzzerare() = runTest {
    val (tablet, _, history) = timeline()
    val other = forge(history[1]) { a, base -> a.changeStatus(base, tablet.id, DeviceStatus.LOST) } // un'altra v4
    check(other.registry.version == 4L)
    world.common.attackerWrite(NasLayout.registryFile(4), other.signedBytes)

    tablet.later(DAY)
    val result = tablet.guard.preflight(Purpose.BACKGROUND)

    assertNotWiped(tablet)
    assertThat(result).isEqualTo(PreflightResult.ReadOnly(ReadOnlyReason.REGISTRY_STALE)) // allarme: rami firmati dalla famiglia
    assertThat(tablet.engine.facts.value.forkCount).isEqualTo(1)
  }

  @Test
  fun unaVersionePiuNuovaCheTornaIndietroNonFaAzzerare_regressione() = runTest {
    val (tablet, x, history) = timeline()
    // Base v3 (X ancora approvato) + spazzatura al 4: esce una v5 che segna il tablet perso ma riapprova X.
    val regression = forge(history[1], junkUntil = 4) { a, base -> a.changeStatus(base, tablet.id, DeviceStatus.LOST) }
    check(regression.registry.version == 5L && regression.canWrite(x.id))
    world.common.attackerWrite(NasLayout.registryFile(5), regression.signedBytes)

    tablet.later(DAY)
    tablet.guard.preflight(Purpose.BACKGROUND)

    assertNotWiped(tablet)
    assertThat(tablet.engine.facts.value.forkCount).isEqualTo(1)
    assertThat(tablet.engine.state!!.canWrite(x.id)).isFalse()
  }

  @Test
  fun unRegistroTroncatoNonFaAzzerareFinchENonSiLeggeIntero() = runTest {
    val (tablet, _, _) = timeline()
    world.admin.service.changeStatus(tablet.id, DeviceStatus.LOST) // v5, vera
    world.common.attackerTruncate(NasLayout.registryFile(5), 120)

    tablet.later(DAY)
    assertThat(tablet.guard.preflight(Purpose.BACKGROUND)).isInstanceOf(PreflightResult.Proceed::class.java)
    assertNotWiped(tablet)

    world.common.attackerRestore(NasLayout.registryFile(5))
    tablet.later(DAY)
    assertThat(tablet.guard.preflight(Purpose.BACKGROUND)).isEqualTo(PreflightResult.WipeStarted)
    assertThat(tablet.selfWipe.progress.value).isEqualTo(WipePhase.DONE)
  }

  @Test
  fun unNasCheNonRispondeFermaIlLavoroMaNonAzzera() = runTest {
    val (tablet, _, _) = timeline()
    world.admin.service.changeStatus(tablet.id, DeviceStatus.LOST)
    tablet.sessions.error = NasError.Timeout

    tablet.later(DAY)
    val result = tablet.guard.preflight(Purpose.BACKGROUND)

    assertThat(result).isEqualTo(PreflightResult.Stop(StopReason.NAS_UNAVAILABLE, NasError.Timeout))
    assertNotWiped(tablet)
  }

  @Test
  fun loStatoPersoInUnRegistroFirmatoEAccettatoFaAzzerareChiavePrimaDeiDati() = runTest {
    val (tablet, _, _) = timeline()
    world.admin.service.changeStatus(tablet.id, DeviceStatus.LOST)

    tablet.later(DAY)
    assertThat(tablet.guard.preflight(Purpose.BACKGROUND)).isEqualTo(PreflightResult.WipeStarted)

    val log = tablet.log.all()
    assertThat(log.indexOf("keys:destroyDeviceKey")).isLessThan(log.indexOf("eraser:eraseAll"))
    assertThat(log.indexOf("journal:MARKED")).isLessThan(log.indexOf("keys:destroyDeviceKey"))
    assertThat(tablet.keys.hasDeviceKey()).isFalse()
    assertThat(tablet.store.snapshot).isNull()
    assertThat(tablet.selfWipe.record.value!!.reason).isEqualTo(WipeReason.MARKED_LOST)
    assertThat(tablet.guard.writePermit()).isNull()
    assertThat(tablet.guard.preflight(Purpose.BACKGROUND)).isEqualTo(PreflightResult.Stop(StopReason.NOT_CONFIGURED))
  }

  // --- Segnalazioni "perso" (decisione 3(a)) ----------------------------------------------------------

  private suspend fun plantReport(share: String, reporter: Device, target: String, account: String = "mamma") {
    val signed = LostReport(world.familyId, reporter.id, target, account, world.clock.now).sign(reporter.keys.deviceSigner())
    checkNotNull(LostReports.publish(world.server.share(share), target, signed))
  }

  @Test
  fun segnalazioniFalseODiAltriAccountNonFannoAzzerare() = runTest {
    val tablet = world.joinApproved("Tablet di Mamma", "mamma")
    val mammaPhone = world.joinApproved("Telefono di Mamma", "mamma")
    val babbo = world.joinApproved("Telefono di Babbo", "babbo")
    val pending = world.join("Ancora in attesa", "mamma")
    val revoked = world.joinApproved("Vecchio di Mamma", "mamma")
    world.admin.service.changeStatus(revoked.id, DeviceStatus.REVOKED)
    tablet.service.refresh(Purpose.BACKGROUND)

    plantReport(COMMON, mammaPhone, tablet.id) // valida, ma solo nell'area comune: per l'admin
    plantReport("mamma", babbo, tablet.id, account = "babbo") // altro account
    plantReport("mamma", pending, tablet.id) // non approvato (E-S14)
    plantReport("mamma", revoked, tablet.id) // revocato
    world.server.share("mamma").attackerWrite(NasLayout.reportFile(tablet.id, 4), "spazzatura".toByteArray())

    tablet.later(DAY)
    assertThat(tablet.guard.preflight(Purpose.BACKGROUND)).isInstanceOf(PreflightResult.Proceed::class.java)

    assertNotWiped(tablet)
    assertThat(tablet.lostWatcher.lastScan.value!!.rejected.total).isEqualTo(4)
  }

  @Test
  fun unaSegnalazioneValidaNellAreaPrivataDellAccountFaAzzerare() = runTest {
    val tablet = world.joinApproved("Tablet di Mamma", "mamma")
    val mammaPhone = world.joinApproved("Telefono di Mamma", "mamma")
    tablet.service.refresh(Purpose.BACKGROUND)

    assertThat(mammaPhone.lostReporting.reportOwnDeviceLost(tablet.id)).isEqualTo(dev.pampa.mycloudgallery.core.family.contract.AdminActionResult.Done)

    tablet.later(DAY)
    assertThat(tablet.guard.preflight(Purpose.BACKGROUND)).isEqualTo(PreflightResult.WipeStarted)
    assertThat(tablet.selfWipe.record.value!!.reason).isEqualTo(WipeReason.LOST_REPORT)
    assertThat(tablet.keys.hasDeviceKey()).isFalse()
  }

  private companion object {
    const val DAY = 24L * 60 * 60 * 1000
  }
}
