package dev.pampa.mycloudgallery.core.trust

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.registry.CommitResult
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.FamilyAuthority
import dev.pampa.mycloudgallery.core.trust.registry.RegistrationRequest
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.registry.RegistrySync
import dev.pampa.mycloudgallery.core.trust.testing.TestFamily
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import dev.pampa.mycloudgallery.core.trust.testing.newSigner
import org.junit.Test

/**
 * Terzo giro della revisione 1.4 ("Verifica del secondo giro" in `docs/review/1.4-core-trust.md`), chiuso nel
 * pacchetto P3 della Fase 2: N2 (parte residua), R2, R3, R4. Ogni test riproduce lo scenario della voce e
 * fallisce se si reintroduce la debolezza.
 */
class ReviewRound3Test {

  private val family = TestFamily()

  /** Il secondo admin (stessa chiave di famiglia) rimasto indietro. */
  private val stale = FamilyAuthority(family.familyKey, family.clock)

  private fun published(result: CommitResult): RegistryState {
    assertThat(result).isInstanceOf(CommitResult.Published::class.java)
    return (result as CommitResult.Published).state
  }

  private fun staleVersions(base: RegistryState, count: Int): RegistryState {
    var tip = base
    repeat(count) { i -> tip = published(stale.commit(family.common, stale.updateConfig(tip, family.config.copy(nasHost = "192.168.178.${20 + i % 200}")))) }
    return tip
  }

  // --- N2 (parte residua): buco + elenco inondato --------------------------------------------------------

  @Test
  fun n2bis_unBucoConLElencoInondatoNonFaPubblicareSottoUnaVersionePiuNuova() {
    val tablet = family.approveNewDevice() // v2
    val adminA = family.state
    val v4 = staleVersions(adminA, 2) // l'admin B: v3, v4; i dispositivi arrivano alla v4
    family.common.attackerDelete(NasLayout.registryFile(3))
    family.common.adversarialListing = true
    family.common.attackerFlood(NasLayout.REGISTRY_DIR, TrustLimits.MAX_LIST_REGISTRY + 100) // la v4 esce dall'elenco

    val attempt = family.authority.commit(family.common, family.authority.changeStatus(adminA, tablet.deviceId, DeviceStatus.REVOKED))

    assertThat(attempt).isEqualTo(CommitResult.NewerExists) // letta per nome subito dopo il buco
    assertThat(v4.registry.version).isEqualTo(4)
  }

  @Test
  fun n2bis_unBucoPiuLargoDelControlloConLElencoInondatoNonPubblicaInSilenzio() {
    val tablet = family.approveNewDevice() // v2
    val adminA = family.state
    staleVersions(adminA, TrustLimits.MAX_JUNK_RUN + 2) // v3 … v36
    for (v in 3L..(3L + TrustLimits.MAX_JUNK_RUN)) family.common.attackerDelete(NasLayout.registryFile(v)) // buco 3…35
    family.common.adversarialListing = true
    family.common.attackerFlood(NasLayout.REGISTRY_DIR, TrustLimits.MAX_LIST_REGISTRY + 100)

    val result = family.authority.commit(family.common, family.authority.changeStatus(adminA, tablet.deviceId, DeviceStatus.REVOKED))

    // Non si puo' sapere se oltre c'e' qualcosa: si pubblica (il ramo dell'admin riceve la revoca) ma lo si dice.
    assertThat(published(result).registry.version).isEqualTo(3)
    assertThat((result as CommitResult.Published).unverifiedBeyond).isTrue()
  }

  @Test
  fun n2bis_senzaInondazioneLaPubblicazioneNonHaAvvisi() {
    val tablet = family.approveNewDevice()
    val result = family.authority.commit(family.common, family.authority.changeStatus(family.state, tablet.deviceId, DeviceStatus.REVOKED))

    assertThat((result as CommitResult.Published).unverifiedBeyond).isFalse()
  }

  // --- R4: tetto alla spazzatura negli indizi -----------------------------------------------------------

  @Test
  fun r4_gliIndiziOltreIlNomeLiberoHannoUnTettoAllaSpazzatura() {
    val tablet = family.approveNewDevice() // v2: il nome libero e' 3
    val junk = 900 // sotto il tetto dell'elenco: e' la spazzatura, non il troncamento, a fermare
    // Nel caso vero ogni file puo' arrivare al tetto del registro (256 KiB): conta il numero di letture.
    for (i in 0 until junk) family.common.attackerWrite(NasLayout.registryFile(100L + i).value, ByteArray(64) { 7 })
    val before = family.common.reads

    val result = family.authority.commit(family.common, family.authority.changeStatus(family.state, tablet.deviceId, DeviceStatus.REVOKED))

    val reads = family.common.reads - before
    assertThat(reads).isLessThan(3 * TrustLimits.MAX_JUNK_RUN + 10) // base + nome libero + 32 per nome + 33 indizi
    assertThat(published(result).registry.version).isEqualTo(3)
    assertThat((result as CommitResult.Published).unverifiedBeyond).isTrue()
  }

  // --- R2: un ramo con una rotazione -------------------------------------------------------------------

  /** Il telefono revoca il tablet (v3); qualcuno cancella la v3; il secondo admin, alla v2, annuncia una rotazione (v3'). */
  private fun rotationForkScenario(): Triple<RegistryState, RegistryState, String> {
    val tablet = family.approveNewDevice(name = "Tablet", nasUser = "mamma") // v2
    val staleBase = family.state
    family.changeStatus(tablet, DeviceStatus.REVOKED) // v3
    val phone = family.state
    family.common.attackerDelete(NasLayout.registryFile(3))
    published(stale.commit(family.common, stale.rotateTo(staleBase, FamilyKey.generate()))) // v3' con successorKey
    return Triple(phone, staleBase, tablet.deviceId)
  }

  @Test
  fun r2_unRamoConUnaRotazioneSiScavalcaELaModificaEsce() {
    val (phone, _, tablet) = rotationForkScenario()
    val observer = phone // un altro dispositivo del ramo dell'admin, che aveva visto la v3
    val draft = family.authority.changeStatus(phone, tablet, DeviceStatus.LOST)
    val forks = (family.authority.commit(family.common, draft) as CommitResult.Fork).forks
    assertThat(forks.single().registry.successorKey).isNotNull()

    // Prima della correzione `mergeForks` lanciava ("serve il reset") e `commit` restituiva sempre Fork.
    val out = published(family.authority.commit(family.common, family.authority.mergeForks(draft, forks)))

    assertThat(out.statusOf(tablet)).isEqualTo(DeviceStatus.LOST)
    assertThat(out.trustedFamilyKey).isEqualTo(family.familyKey.publicKey) // nessuna rotazione adottata
    // Un dispositivo del ramo dell'admin riceve la modifica.
    val device = RegistrySync(family.common).refresh(observer, family.anchor())
    assertThat(device.state!!.registry.version).isEqualTo(out.registry.version)
    assertThat(device.state!!.mustSelfWipe(tablet)).isTrue()
  }

  @Test
  fun r2_ancheIlRifiutoDiUnRamoConRotazioneFaUscireLaModifica() {
    val (phone, _, tablet) = rotationForkScenario()
    val draft = family.authority.changeStatus(phone, tablet, DeviceStatus.LOST)
    val forks = (family.authority.commit(family.common, draft) as CommitResult.Fork).forks

    val out = published(family.authority.commit(family.common, family.authority.rejectForks(draft, forks)))

    assertThat(out.statusOf(tablet)).isEqualTo(DeviceStatus.LOST)
  }

  // --- R3: stesso dispositivo, due rami, due account -------------------------------------------------

  /**
   * Il fondatore approva il tablet con l'account "mamma" (v2); qualcuno cancella la v2; il secondo admin,
   * rimasto alla v1, approva la STESSA chiave con l'account "babbo" (v2'): il dispositivo compromesso ha
   * firmato due richieste.
   */
  private fun twoAccountsScenario(): Triple<RegistryState, RegistryState, String> {
    val staleBase = family.state // v1
    val tablet = newSigner()
    val asMamma = family.verified(family.request(tablet, "Tablet", "mamma"))
    family.commit(family.authority.approve(family.state, asMamma, family.typed(asMamma), family.clock.now)) // v2
    val phone = family.state
    family.common.attackerDelete(NasLayout.registryFile(2))
    val asBabbo = family.verified(RegistrationRequest.sign(tablet, family.familyId, "Tablet", "SM-X200", "babbo", family.clock.now))
    val staleTip = published(stale.commit(family.common, stale.approve(staleBase, asBabbo, family.typed(asBabbo), family.clock.now))) // v2'
    return Triple(phone, staleTip, tablet.deviceId)
  }

  @Test
  fun r3_unDispositivoConDueAccountInDueRamiSiRevocaSenzaReset() {
    val (phone, staleTip, tablet) = twoAccountsScenario()

    // La revoca contiene gia' il ramo: l'account puo' cambiare a un dispositivo che diventa revocato.
    val out = published(family.authority.commit(family.common, family.authority.changeStatus(phone, tablet, DeviceStatus.REVOKED)))

    assertThat(out.statusOf(tablet)).isEqualTo(DeviceStatus.REVOKED)
    val onStaleBranch = RegistrySync(family.common).refresh(staleTip, family.anchor())
    assertThat(onStaleBranch.state!!.registry.version).isEqualTo(out.registry.version)
    assertThat(onStaleBranch.state!!.canWrite(tablet)).isFalse()
  }

  @Test
  fun r3_unireORifiutareIRamiConDueAccountNonLanciaEIlDispositivoEsceRevocato() {
    val (phone, staleTip, tablet) = twoAccountsScenario()
    val other = newSigner()
    val request = family.verified(family.request(other, "Telefono di Nonna", "nonna"))
    val draft = family.authority.approve(phone, request, family.typed(request), family.clock.now)
    val forks = (family.authority.commit(family.common, draft) as CommitResult.Fork).forks

    val merged = published(family.authority.commit(family.common, family.authority.mergeForks(draft, forks)))

    assertThat(merged.statusOf(tablet)).isEqualTo(DeviceStatus.REVOKED)
    assertThat(merged.registry.device(tablet)!!.admin).isFalse()
    assertThat(merged.canWrite(other.deviceId)).isTrue()
    assertThat(RegistrySync(family.common).refresh(staleTip, family.anchor()).state!!.canWrite(tablet)).isFalse()
  }

  @Test
  fun r3_lAccountDiUnDispositivoCheValeAncoraNonCambia() {
    val (phone, staleTip, tablet) = twoAccountsScenario()
    // Il ramo con "babbo" approvato non e' accettato da chi ha visto "mamma" approvato (e viceversa).
    val sync = RegistrySync(family.common).refresh(phone, family.anchor())
    assertThat(sync.state!!.registry.version).isEqualTo(phone.registry.version)
    assertThat(sync.tamperingSuspected).isTrue()
    assertThat(staleTip.registry.device(tablet)!!.nasUser).isEqualTo("babbo")
  }
}
