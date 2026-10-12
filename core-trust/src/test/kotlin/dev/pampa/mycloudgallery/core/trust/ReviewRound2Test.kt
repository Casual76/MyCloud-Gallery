package dev.pampa.mycloudgallery.core.trust

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.trust.crypto.AndroidKeystoreFamilyKeyVault
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.crypto.RecoveryCode
import dev.pampa.mycloudgallery.core.trust.crypto.RecoveryKit
import dev.pampa.mycloudgallery.core.trust.crypto.RecoveryKitFiles
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.registry.CommitResult
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.FamilyAuthority
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.registry.RegistrySync
import dev.pampa.mycloudgallery.core.trust.registry.RegistryVerifier
import dev.pampa.mycloudgallery.core.trust.registry.TrustAnchor
import dev.pampa.mycloudgallery.core.trust.testing.FakeNasFileSystem
import dev.pampa.mycloudgallery.core.trust.testing.TestFamily
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import dev.pampa.mycloudgallery.core.trust.testing.newSigner
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

/**
 * Secondo giro della revisione 1.4 ("Verifica delle correzioni" in `docs/review/1.4-core-trust.md`): ogni
 * test riproduce lo scenario della voce nel nome e fallisce se si reintroduce la debolezza nel codice.
 */
class ReviewRound2Test {

  private val family = TestFamily()

  /** Il secondo admin (stessa chiave di famiglia) che resta indietro. */
  private val stale = FamilyAuthority(family.familyKey, family.clock)

  private fun published(result: CommitResult): RegistryState {
    assertThat(result).isInstanceOf(CommitResult.Published::class.java)
    return (result as CommitResult.Published).state
  }

  private fun staleApprove(base: RegistryState, name: String): Pair<RegistryState, String> {
    val device = newSigner()
    val request = family.verified(family.request(device, name, "babbo"))
    return published(stale.commit(family.common, stale.approve(base, request, family.typed(request), family.clock.now))) to device.deviceId
  }

  /**
   * Lo scenario di N1 e A1 insieme: il telefono dell'admin revoca il tablet (v3), qualcuno cancella la v3,
   * il secondo admin rimasto alla v2 pubblica TRE versioni (v3', v4', v5'). Restituisce lo stato del
   * telefono, l'ultimo del ramo, il tablet e i due dispositivi approvati nel ramo.
   */
  private fun threeVersionFork(): ForkScenario {
    val tablet = family.approveNewDevice(name = "Tablet", nasUser = "mamma") // v2
    val staleBase = family.state
    family.changeStatus(tablet, DeviceStatus.REVOKED) // v3
    val phone = family.state
    family.common.attackerDelete(NasLayout.registryFile(3))
    val (v3, x) = staleApprove(staleBase, "Telefono di Babbo")
    val v4 = published(stale.commit(family.common, stale.updateConfig(v3, family.config.copy(nasHost = "192.168.178.20"))))
    val (v5, y) = staleApprove(v4, "Tablet di Babbo")
    return ForkScenario(phone, v5, tablet.deviceId, x, y)
  }

  private data class ForkScenario(val phone: RegistryState, val staleTip: RegistryState, val tablet: String, val x: String, val y: String)

  // --- N1: rami di piu' versioni ---------------------------------------------------------------------------

  @Test
  fun n1_unRamoDiTreVersioniSiUnisceELaRevocaEsce() {
    val s = threeVersionFork()
    val draft = family.authority.changeStatus(s.phone, s.tablet, DeviceStatus.LOST)

    val first = family.authority.commit(family.common, draft)
    assertThat(first).isInstanceOf(CommitResult.Fork::class.java)
    val forks = (first as CommitResult.Fork).forks
    assertThat(forks.map { it.registry.version }).containsExactly(3L, 4L, 5L)
    assertThat(forks.any { it.configDiffers(s.phone.registry) }).isTrue() // il ramo ha cambiato nasHost (domanda 10)

    val merged = published(family.authority.commit(family.common, family.authority.mergeForks(draft, forks)))
    assertThat(merged.statusOf(s.tablet)).isEqualTo(DeviceStatus.LOST)
    assertThat(merged.canWrite(s.x) && merged.canWrite(s.y)).isTrue()

    // Lo accettano entrambi i rami.
    val onStaleBranch = RegistrySync(family.common).refresh(s.staleTip, family.anchor())
    assertThat(onStaleBranch.state!!.registry.version).isEqualTo(merged.registry.version)
    assertThat(onStaleBranch.state!!.mustSelfWipe(s.tablet)).isTrue()
    val onPhoneBranch = RegistrySync(family.common).refresh(s.phone, family.anchor())
    assertThat(onPhoneBranch.state!!.registry.version).isEqualTo(merged.registry.version)
  }

  @Test
  fun n1_unRamoNonRiconosciuto_siEsceRevocandoneIDispositivi() {
    val s = threeVersionFork()
    val draft = family.authority.changeStatus(s.phone, s.tablet, DeviceStatus.LOST)
    val forks = (family.authority.commit(family.common, draft) as CommitResult.Fork).forks

    val out = published(family.authority.commit(family.common, family.authority.rejectForks(draft, forks)))

    assertThat(out.statusOf(s.x)).isEqualTo(DeviceStatus.REVOKED)
    assertThat(out.statusOf(s.y)).isEqualTo(DeviceStatus.REVOKED)
    assertThat(out.statusOf(s.tablet)).isEqualTo(DeviceStatus.LOST)
    val onStaleBranch = RegistrySync(family.common).refresh(s.staleTip, family.anchor())
    assertThat(onStaleBranch.state!!.canWrite(s.x)).isFalse() // anche il ramo "estraneo" accetta l'uscita
  }

  // --- N2: buco sotto una versione piu' nuova ------------------------------------------------------------

  @Test
  fun n2_nonSiPubblicaInUnBucoSottoUnaVersionePiuNuova() {
    val tablet = family.approveNewDevice() // v2
    val adminA = family.state
    val (v3, _) = staleApprove(adminA, "Telefono di Babbo") // l'admin B: v3, v4
    val v4 = published(stale.commit(family.common, stale.updateConfig(v3, family.config.copy(nasHost = "192.168.178.20"))))
    family.common.attackerDelete(NasLayout.registryFile(3))

    val attempt = family.authority.commit(family.common, family.authority.changeStatus(adminA, tablet.deviceId, DeviceStatus.REVOKED))
    assertThat(attempt).isEqualTo(CommitResult.NewerExists)

    val resynced = RegistrySync(family.common).refresh(adminA, family.anchor()).state!!
    assertThat(resynced.registry.version).isEqualTo(4)
    published(family.authority.commit(family.common, family.authority.changeStatus(resynced, tablet.deviceId, DeviceStatus.REVOKED)))
    val device = RegistrySync(family.common).refresh(v4, family.anchor())
    assertThat(device.state!!.canWrite(tablet.deviceId)).isFalse()
  }

  // --- N3: versione fidata sostituita ----------------------------------------------------------------------

  @Test
  fun n3_versioneFidataSostituitaConSpazzatura_allarmeERipubblicazione() {
    val tablet = family.approveNewDevice()
    val observer = family.state // non ha ancora visto la revoca
    family.changeStatus(tablet, DeviceStatus.REVOKED) // v3
    family.common.attackerDelete(NasLayout.registryFile(3))
    family.common.attackerWrite(NasLayout.registryFile(3).value, "spazzatura".toByteArray())

    assertThat(RegistrySync(family.common).refresh(observer, family.anchor()).state!!.canWrite(tablet.deviceId)).isTrue() // il limite

    val phone = RegistrySync(family.common).refresh(family.state, family.anchor())
    assertThat(phone.trustedVersionReplaced).isTrue()
    assertThat(phone.tamperingSuspected).isTrue()

    published(family.authority.commit(family.common, family.authority.reissue(family.state)))
    assertThat(RegistrySync(family.common).refresh(observer, family.anchor()).state!!.canWrite(tablet.deviceId)).isFalse()
  }

  // --- N4: QR durante la rotazione --------------------------------------------------------------------------

  @Test
  fun n4_nessunQrConLaChiaveVecchiaDuranteLaRotazione() {
    val next = family.announceRotation()
    assertThat(family.state.toAnchor()).isNull() // solo l'annuncio: niente QR

    family.commit(family.authority.reissue(family.state))
    val qr = family.state.toAnchor()!!
    assertThat(qr.familyKeyFingerprint).isEqualTo(next.publicKey.fingerprint)
    assertThat(RegistrySync(family.common).refresh(null, qr).state!!.trustedFamilyKey).isEqualTo(next.publicKey)
  }

  @Test
  fun n4_laRotazioneCompletaFirmaSubitoConLaChiaveNuova() {
    val next = FamilyKey.generate()

    val result = FamilyAuthority.rotate(family.common, family.state, family.familyKey, next, family.clock)

    assertThat(published(result).signerKey).isEqualTo(next.publicKey)
    assertThat(published(result).toAnchor()!!.familyKeyFingerprint).isEqualTo(next.publicKey.fingerprint)
  }

  // --- N5: rami storici --------------------------------------------------------------------------------------

  @Test
  fun n5_unRamoLungoGiaUnitoNonFermaLaLetturaENonDaAllarmi() {
    val tablet = family.approveNewDevice()
    val staleBase = family.state
    family.changeStatus(tablet, DeviceStatus.REVOKED) // v3
    val observer = family.state
    family.common.attackerDelete(NasLayout.registryFile(3))
    var tip = staleBase
    repeat(TrustLimits.MAX_JUNK_RUN + 5) { tip = staleApprove(tip, "Dispositivo $it").first } // 37 versioni di ramo
    val draft = family.authority.reissue(observer)
    val forks = (family.authority.commit(family.common, draft) as CommitResult.Fork).forks
    val merged = published(family.authority.commit(family.common, family.authority.mergeForks(draft, forks)))

    val sync = RegistrySync(family.common).refresh(observer, family.anchor())

    assertThat(sync.incomplete).isFalse()
    assertThat(sync.state!!.registry.version).isEqualTo(merged.registry.version)
    assertThat(sync.forks).isEmpty()
    assertThat(sync.tamperingSuspected).isFalse()
  }

  // --- M3: cassaforte senza blocco schermo ------------------------------------------------------------------

  @Test
  fun m3_laCassaforteNonLavoraSenzaBloccoSchermoSicuro() {
    val vault = AndroidKeystoreFamilyKeyVault(File.createTempFile("vault", null).apply { deleteOnExit() }, isDeviceSecure = { false })

    val stored = assertThrows(IllegalStateException::class.java) { vault.store(FamilyKey.generate()) }
    assertThat(stored.message).contains("blocco schermo")
    assertThrows(IllegalStateException::class.java) { vault.load(FamilyKey.generate().publicKey) }
  }

  // --- M4: kit vecchi --------------------------------------------------------------------------------------

  @Test
  fun m4_dopoLaRotazioneIlKitVecchioEElencatoDaCancellare() {
    val admin = FakeNasFileSystem("Privata-Admin")
    val oldPath = NasLayout.recoveryKitFile(family.familyId, family.familyKey.publicKey.fingerprint)
    admin.publishExclusive(oldPath, RecoveryKit.seal(family.familyKey, family.familyId, RecoveryCode.generate(), family.clock.now))
    val next = family.rotate()
    val newPath = NasLayout.recoveryKitFile(family.familyId, next.publicKey.fingerprint)
    admin.publishExclusive(newPath, RecoveryKit.seal(next, family.familyId, RecoveryCode.generate(), family.clock.now))
    admin.attackerWrite(NasLayout.recoveryKitFile(family.familyId, family.familyKey.publicKey.fingerprint).parent()!!.value + "/../strano", ByteArray(1))

    assertThat(RecoveryKitFiles.obsolete(admin, family.familyId, next.publicKey.fingerprint)).containsExactly(oldPath)
  }

  // --- M7 (nota): QR di stato con un'altra chiave ------------------------------------------------------------

  @Test
  fun m7_unQrDiStatoCheAncoraAUnAltraChiaveSiScopreSubito() {
    val kitKey = family.familyKey
    // Un dispositivo compromesso mostra un QR con la sua chiave e ha pubblicato un registro firmato da lei.
    val attackerKey = FamilyKey.generate()
    val forged = family.state.registry.copy(version = 50)
    family.common.attackerWrite(NasLayout.registryFile(50).value, Envelope.seal(DocType.FAMILY_REGISTRY, forged.toJson(), attackerKey))
    val badQr = TrustAnchor.create(family.familyId, attackerKey.publicKey.fingerprint, 50)!!

    val synced = RegistrySync(family.common).refresh(null, badQr).state!!

    assertThat(synced.acceptsRecoveredKey(kitKey.publicKey)).isFalse()
    val honest = RegistrySync(family.common).refresh(null, family.anchor()).state!!
    assertThat(honest.acceptsRecoveredKey(kitKey.publicKey)).isTrue()
    assertThat(RegistryVerifier.restore(honest.signedBytes, kitKey.publicKey.fingerprint).acceptedOrNull()).isNotNull()
  }
}
