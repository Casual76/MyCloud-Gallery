package dev.pampa.mycloudgallery.core.family.p3

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.InviteParams
import dev.pampa.mycloudgallery.core.family.contract.PreflightResult
import dev.pampa.mycloudgallery.core.family.contract.ReadOnlyReason
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.registry.InMemoryApprovalSightings
import dev.pampa.mycloudgallery.core.family.registry.NasRegistryService
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.data.DeviceRole
import dev.pampa.mycloudgallery.core.trust.data.DeviceStatusReport
import dev.pampa.mycloudgallery.core.trust.data.DeviceStatusVerifier
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.core.trust.registry.CommitResult
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.FamilyAuthority
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * L'approvazione lato admin (§2.2) sul NAS ostile: codice giusto e sbagliato, chiave scansionata, finestra di
 * 15 minuti, tutti gli esiti di `commit`, E-S14 (in attesa: sola lettura, scritture rifiutate da tutti).
 */
class ApprovalTest {

  private val world = FamilyWorld()
  private val admin get() = world.admin

  private suspend fun pendingTablet(): Device {
    val tablet = world.join("Tablet di Mamma", "mamma")
    admin.service.refresh(Purpose.INTERACTIVE)
    return tablet
  }

  @Test
  fun ilCodiceDigitatoGiustoApprovaEIlDispositivoLoVede() = runTest {
    val tablet = pendingTablet()
    val request = admin.service.pendingRequests.value.single()
    assertThat(request.id).isEqualTo(tablet.id)
    assertThat(request.declaredNasUser).isEqualTo("mamma") // da confermare (B6)
    assertThat(request.windowOpen).isTrue()
    // Il codice si digita come lo si legge: minuscole, spazi e trattini vanno bene.
    val typed = tablet.shownCode.lowercase().replace("-", " ")

    val result = admin.service.approve(tablet.id, ApprovalProof.TypedCode(typed))

    assertThat(result).isEqualTo(AdminActionResult.Done)
    assertThat(admin.service.pendingRequests.value).isEmpty()
    assertThat(admin.service.devices.value.map { it.id }).contains(tablet.id)
    tablet.service.refresh(Purpose.BACKGROUND)
    assertThat(tablet.engine.status.value).isEqualTo(TrustStatus.Approved(admin = false))
    tablet.later(DAY)
    assertThat(tablet.guard.preflight(Purpose.BACKGROUND)).isInstanceOf(PreflightResult.Proceed::class.java)
    assertThat(tablet.guard.writePermit()!!.registryVersion).isEqualTo(2)
  }

  @Test
  fun laChiaveScansionataApprova() = runTest {
    val tablet = pendingTablet()

    val result = admin.service.approve(tablet.id, ApprovalProof.ScannedKey(tablet.keys.deviceSigner().publicKey))

    assertThat(result).isEqualTo(AdminActionResult.Done)
  }

  @Test
  fun unCodiceSbagliatoNonApprovaENonScriveNulla() = runTest {
    val tablet = pendingTablet()
    val before = world.common.publishes
    val wrong = tablet.shownCode.dropLast(1) + if (tablet.shownCode.last() == 'A') "B" else "A"

    assertThat(admin.service.approve(tablet.id, ApprovalProof.TypedCode(wrong))).isEqualTo(AdminActionResult.ProofMismatch)
    assertThat(admin.service.approve(tablet.id, ApprovalProof.ScannedKey(world.admin.keys.deviceSigner().publicKey)))
      .isEqualTo(AdminActionResult.ProofMismatch)

    assertThat(world.common.publishes).isEqualTo(before)
    assertThat(admin.engine.state!!.registry.version).isEqualTo(1)
  }

  @Test
  fun laFinestraDiQuindiciMinutiParteDaQuandoLAdminVedeLaRichiestaESopravviveAlRiavvio() = runTest {
    val sightings = InMemoryApprovalSightings()
    val service = NasRegistryService(admin.engine, admin.sessions, admin.writer, sightings, admin.statuses, admin.scheduler)
    val tablet = world.join("Tablet di Mamma", "mamma")
    world.clock.advance(60 * 60 * 1000) // la richiesta e' vecchia di un'ora: non conta (requestedAt lo scrive lui)
    service.refresh(Purpose.INTERACTIVE)
    assertThat(service.pendingRequests.value.single().windowOpen).isTrue()

    world.clock.advance(16 * 60 * 1000)
    // L'app dell'admin riparte: le richieste viste restano annotate.
    val restarted = NasRegistryService(admin.engine, admin.sessions, admin.writer, sightings, admin.statuses, admin.scheduler)
    restarted.refresh(Purpose.INTERACTIVE)
    assertThat(restarted.pendingRequests.value.single().windowOpen).isFalse()

    assertThat(restarted.approve(tablet.id, ApprovalProof.TypedCode(tablet.shownCode))).isEqualTo(AdminActionResult.WindowExpired)
  }

  @Test
  fun unaRichiestaNascostaDaUnInondazioneSiApprovaPerId() = runTest {
    val tablet = world.join("Tablet di Mamma", "mamma")
    world.common.adversarialListing = true
    world.common.attackerFlood(NasLayout.REQUESTS_DIR, 200)
    admin.service.refresh(Purpose.INTERACTIVE)
    assertThat(admin.service.pendingRequests.value.map { it.id }).doesNotContain(tablet.id)

    assertThat(admin.service.approve(tablet.id, ApprovalProof.TypedCode(tablet.shownCode))).isEqualTo(AdminActionResult.Done)
  }

  @Test
  fun soloLAdminApprova() = runTest {
    val member = world.joinApproved("Telefono di Babbo", "babbo")
    val tablet = world.join("Tablet di Mamma", "mamma")

    assertThat(member.service.approve(tablet.id, ApprovalProof.TypedCode(tablet.shownCode))).isEqualTo(AdminActionResult.NotAdmin)
    // L'admin con il telefono senza blocco schermo sicuro non apre la cassaforte (M3).
    admin.vault.deviceSecure = false
    assertThat(admin.service.approve(tablet.id, ApprovalProof.TypedCode(tablet.shownCode))).isEqualTo(AdminActionResult.NotAdmin)
  }

  // --- Esiti di commit -----------------------------------------------------------------------------------

  @Test
  fun esito_newerExists_unAltroAdminHaPubblicatoPrima_poiSiRiprova() = runTest {
    val tablet = pendingTablet()
    // Il secondo admin (stessa chiave) pubblica una versione mentre il primo ha la schermata aperta.
    val second = FamilyAuthority(world.familyKey, world.clock)
    val published = second.commit(world.common, second.updateConfig(admin.engine.state!!, world.config.copy(nasPower = "08:00-22:00")))
    check(published is CommitResult.Published)

    assertThat(admin.service.approve(tablet.id, ApprovalProof.TypedCode(tablet.shownCode))).isEqualTo(AdminActionResult.NewerExists)
    assertThat(admin.engine.state!!.registry.version).isEqualTo(2) // risincronizzato

    assertThat(admin.service.approve(tablet.id, ApprovalProof.TypedCode(tablet.shownCode))).isEqualTo(AdminActionResult.Done)
    assertThat(admin.engine.state!!.registry.config.nasPower).isEqualTo("08:00-22:00") // la modifica dell'altro resta
  }

  @Test
  fun esito_fork_ramiDelSecondoAdmin_siUnisconoELaModificaEsce() = runTest {
    val tablet = pendingTablet()
    val stale = FamilyAuthority(world.familyKey, world.clock)
    val base = admin.engine.state!!
    // Un altro admin, rimasto indietro, pubblica una v2 diversa; qualcuno nasconde la sua... e l'admin ha gia' una v2 sua.
    val babbo = world.join("Telefono di Babbo", "babbo")
    val other = world.request(babbo, "Telefono di Babbo", "babbo")
    assertThat(admin.service.approve(tablet.id, ApprovalProof.TypedCode(tablet.shownCode))).isEqualTo(AdminActionResult.Done) // v2
    world.common.attackerDelete(NasLayout.registryFile(2))
    check(stale.commit(world.common, stale.approve(base, other, ApprovalProof.ScannedKey(other.key), world.clock.now)) is CommitResult.Published) // v2'

    val result = admin.service.changeStatus(tablet.id, DeviceStatus.REVOKED)

    assertThat(result).isEqualTo(AdminActionResult.Fork)
    assertThat(admin.writer.pendingForks.value!!.forks).hasSize(1)
    assertThat(admin.service.resolveForks(recognize = true)).isEqualTo(AdminActionResult.Done)
    val state = admin.engine.state!!
    assertThat(state.statusOf(tablet.id)).isEqualTo(DeviceStatus.REVOKED)
    assertThat(state.statusOf(babbo.id)).isEqualTo(DeviceStatus.APPROVED) // ramo riconosciuto
  }

  @Test
  fun esito_blocked_troppaSpazzatura_conLElencoEsattoDeiFile() = runTest {
    val tablet = pendingTablet()
    for (v in 2L..34L) world.common.attackerWrite(NasLayout.registryFile(v), "spazzatura".toByteArray())

    assertThat(admin.service.approve(tablet.id, ApprovalProof.TypedCode(tablet.shownCode))).isEqualTo(AdminActionResult.Blocked)

    assertThat(admin.writer.blockedPaths.value).hasSize(33)
    assertThat(admin.writer.blockedPaths.value.first()).isEqualTo(NasLayout.registryFile(2))
  }

  @Test
  fun esito_needsAppUpdate_registroDiUnAppPiuNuova() = runTest {
    val tablet = pendingTablet()
    // Una versione valida in tutto tranne il formato (2): un'app piu' nuova.
    val newer = world.common.bytesAt(NasLayout.registryFile(1))!!.also { it[4] = 2 }
    world.common.attackerWrite(NasLayout.registryFile(2), newer)

    assertThat(admin.service.approve(tablet.id, ApprovalProof.TypedCode(tablet.shownCode))).isEqualTo(AdminActionResult.NeedsAppUpdate)
  }

  @Test
  fun esito_failed_ilNasNonRisponde() = runTest {
    val tablet = pendingTablet()
    world.common.failWith = NasError.Timeout

    assertThat(admin.service.approve(tablet.id, ApprovalProof.TypedCode(tablet.shownCode))).isEqualTo(AdminActionResult.Failed(NasError.Timeout))
    world.common.failWith = null
    assertThat(admin.service.approve(tablet.id, ApprovalProof.TypedCode(tablet.shownCode))).isEqualTo(AdminActionResult.Done)
  }

  // --- E-S14 ---------------------------------------------------------------------------------------------

  @Test
  fun e_s14_inAttesaSolaLetturaENessunoAccettaCioCheScrive() = runTest {
    val tablet = world.join("Tablet di Mamma", "mamma")

    assertThat(tablet.engine.status.value).isInstanceOf(TrustStatus.Pending::class.java)
    assertThat(tablet.guard.preflight(Purpose.BACKGROUND)).isEqualTo(PreflightResult.ReadOnly(ReadOnlyReason.AWAITING_APPROVAL))
    assertThat(tablet.guard.writePermit()).isNull()
    assertThat(tablet.statuses.publishIfDue(Purpose.BACKGROUND)).isFalse()
    // Anche se scrivesse (un'app modificata), nessuno lo accetterebbe.
    val forged = DeviceStatusReport(world.familyId, tablet.id, 1, "2.0.0", setOf(DeviceRole.INDEXER), world.clock.now, 1).sign(tablet.keys.deviceSigner())
    world.common.attackerWrite(NasLayout.statusFile(tablet.id, 1), forged)
    val verdict = DeviceStatusVerifier.accept(forged, admin.engine.state ?: run { admin.engine.ensureLoaded(); admin.engine.state!! }, tablet.id, 1, null, world.clock.now)
    assertThat(verdict).isInstanceOf(Verdict.Rejected::class.java)
  }

  // --- Invito ---------------------------------------------------------------------------------------------

  @Test
  fun lInvitoPortaLAncoraAttualeEApreLaFinestra() = runTest {
    admin.engine.ensureLoaded()
    val invite = admin.service.createInvite(InviteParams("mamma", "Tablet — profilo Mamma", "mamma", "night"))

    assertThat(invite.familyId).isEqualTo(world.familyId)
    assertThat(invite.familyKeyFingerprint).isEqualTo(world.familyKey.publicKey.fingerprint)
    assertThat(invite.minRegistryVersion).isEqualTo(1)
    assertThat(invite.nasIp).isEqualTo("192.168.178.58")
    assertThat(invite.nasGuid).isEqualTo(world.config.nasGuid)
    assertThat(invite.expiresAtNas).isEqualTo(world.clock.now + NasRegistryService.INVITE_VALIDITY_MILLIS)
    assertThat(admin.scheduler.inviteWindowUntil).isEqualTo(invite.expiresAtNas)
    assertThrows(IllegalArgumentException::class.java) {
      kotlinx.coroutines.runBlocking { admin.service.createInvite(InviteParams("ma mma", "Tablet", "mamma", null)) }
    }
    // Revisione 2, F8: l'area privata non puo' essere l'area comune.
    assertThrows(IllegalArgumentException::class.java) {
      kotlinx.coroutines.runBlocking { admin.service.createInvite(InviteParams("mamma", "Tablet", world.config.commonShare.uppercase(), null)) }
    }
    val copy = invite.copy(privateShare = invite.commonShare)
    assertThrows(dev.pampa.mycloudgallery.core.trust.TrustViolation::class.java) { dev.pampa.mycloudgallery.core.family.invite.InviteRules.check(copy, manual = false) }
  }

  private companion object {
    const val DAY = 24L * 60 * 60 * 1000
  }
}
