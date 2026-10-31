package dev.pampa.mycloudgallery.core.family.p3

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.FamilyStateQr
import dev.pampa.mycloudgallery.core.family.contract.InviteParams
import dev.pampa.mycloudgallery.core.family.contract.OldAdminFate
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.RecoveryOutcome
import dev.pampa.mycloudgallery.core.family.contract.ResetOutcome
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKeyUse
import dev.pampa.mycloudgallery.core.trust.crypto.VaultRefusal
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 10, correzione 1.4 M3 (decisione 2(a) dell'utente del 31/10): la chiave di famiglia chiede l'impronta o il PIN a **ogni** uso, sul
 * NAS ostile con la cassaforte in memoria e il suo foglio finto ([MemoryFamilyKeyVault]).
 *
 * - il lavoro in background (controllo del registro, guardia) non tocca mai la cassaforte, nemmeno con la versione fidata sostituita
 *   (N3): la ripubblicazione e' un tasto dell'admin;
 * - annullare il foglio non firma e non pubblica niente, e l'esito lo dice ([AdminActionResult.KeyRefused]);
 * - un foglio per gesto, e nessun foglio quando non c'e' niente da firmare (codice sbagliato, finestra scaduta, stessa configurazione,
 *   invito, nessuna segnalazione);
 * - chiave del Keystore invalidata (E-S9, E-H3): un messaggio, nessuna perdita, e il kit di recupero rimette tutto a posto;
 * - recupero e reset con il foglio annullato non cambiano niente.
 */
class ChiaveDiFamigliaOgniUsoTest {

  private val world = FamilyWorld()
  private val admin get() = world.admin
  private val account = NasAccount(NasAddress("192.168.178.58"), "admin2", "slot", "0f8fad5b-d9cb-469f-a165-70867728950e")

  private fun stateQr(device: Device): FamilyStateQr {
    val state = device.engine.state!!
    val anchor = state.toAnchor()!!
    return FamilyStateQr(anchor.familyId, anchor.familyKeyFingerprint, state.registry.version, state.contentHash)
  }

  // --- Il lavoro in background ------------------------------------------------------------------------------

  @Test
  fun ilLavoroInBackgroundNonToccaMaiLaChiaveDiFamiglia_nemmenoConLaVersioneFidataSostituita() = runTest {
    val tablet = world.joinApproved("Tablet di Mamma", "mamma")
    admin.service.refresh(Purpose.BACKGROUND)
    val trusted = admin.engine.state!!.registry.version
    val touches = admin.vault.touches

    // Qualcuno sostituisce la versione fidata con spazzatura (N3): prima l'admin la ripubblicava da solo "se la cassaforte si apre".
    world.common.attackerDelete(NasLayout.registryFile(trusted))
    world.common.attackerWrite(NasLayout.registryFile(trusted), "spazzatura".toByteArray())
    admin.service.refresh(Purpose.BACKGROUND)
    admin.later(DAY)
    admin.guard.preflight(Purpose.BACKGROUND)
    admin.service.refresh(Purpose.BACKGROUND)
    // Nemmeno un controllo interattivo (la pagina dei dispositivi che si apre) firma da solo.
    admin.service.refresh(Purpose.INTERACTIVE)

    assertThat(admin.vault.touches).isEqualTo(touches)
    assertThat(admin.service.reissueDue.value).isTrue()
    assertThat(world.common.exists(NasLayout.registryFile(trusted + 1))).isFalse()
    // Il familiare non e' admin: per lui non c'e' niente da ripubblicare.
    tablet.service.refresh(Purpose.BACKGROUND)
    assertThat(tablet.service.reissueDue.value).isFalse()
    assertThat(tablet.vault.touches).isEqualTo(0)

    // Il tasto "Ripubblica": un foglio, la versione nuova, l'allarme spento.
    assertThat(admin.service.reissue()).isEqualTo(AdminActionResult.Done)
    assertThat(admin.vault.touches).isEqualTo(touches + 1)
    assertThat(admin.vault.uses.last()).isEqualTo(FamilyKeyUse.SIGN)
    assertThat(admin.service.reissueDue.value).isFalse()
    assertThat(admin.engine.state!!.registry.version).isGreaterThan(trusted)
  }

  // --- Annullare non firma ---------------------------------------------------------------------------------

  @Test
  fun annullareIlFoglio_nonFirmaENonPubblicaNiente_eLEsitoLoDice() = runTest {
    val tablet = world.joinApproved("Tablet di Mamma", "mamma")
    val version = admin.engine.state!!.registry.version
    val publishes = world.common.publishes

    admin.vault.sheet = VaultRefusal.CANCELLED
    val cancelled = AdminActionResult.KeyRefused(VaultRefusal.CANCELLED)
    assertThat(admin.service.changeStatus(tablet.id, DeviceStatus.REVOKED)).isEqualTo(cancelled)
    assertThat(admin.service.updateNasAddress("192.168.178.77")).isEqualTo(cancelled)
    assertThat(admin.service.renameDevice(tablet.id, "Tablet della cucina")).isEqualTo(cancelled)
    assertThat(admin.service.updateHomeUla(setOf("fd99:3456:789a::/48"))).isEqualTo(cancelled)
    admin.vault.sheet = VaultRefusal.LOCKED_OUT
    assertThat(admin.service.changeStatus(tablet.id, DeviceStatus.REVOKED)).isEqualTo(AdminActionResult.KeyRefused(VaultRefusal.LOCKED_OUT))
    admin.vault.sheet = VaultRefusal.UNAVAILABLE
    assertThat(admin.service.changeStatus(tablet.id, DeviceStatus.REVOKED)).isEqualTo(AdminActionResult.KeyRefused(VaultRefusal.UNAVAILABLE))

    assertThat(world.common.publishes).isEqualTo(publishes)
    assertThat(world.common.exists(NasLayout.registryFile(version + 1))).isFalse()
    assertThat(admin.engine.state!!.registry.version).isEqualTo(version)
    assertThat(admin.engine.state!!.statusOf(tablet.id)).isEqualTo(DeviceStatus.APPROVED)

    // Confermato, si firma.
    admin.vault.sheet = null
    assertThat(admin.service.changeStatus(tablet.id, DeviceStatus.REVOKED)).isEqualTo(AdminActionResult.Done)
    assertThat(admin.engine.state!!.statusOf(tablet.id)).isEqualTo(DeviceStatus.REVOKED)
  }

  @Test
  fun approvare_codiceSbagliatoNessunFoglio_annullatoNienteApprovato_confermatoUnFoglio() = runTest {
    val tablet = world.join("Tablet di Mamma", "mamma")
    admin.service.refresh(Purpose.INTERACTIVE)
    val sheets = admin.vault.sheets
    val wrong = tablet.shownCode.dropLast(1) + if (tablet.shownCode.last() == 'A') "B" else "A"

    assertThat(admin.service.approve(tablet.id, ApprovalProof.TypedCode(wrong))).isEqualTo(AdminActionResult.ProofMismatch)
    assertThat(admin.vault.sheets).isEqualTo(sheets)

    admin.vault.sheet = VaultRefusal.CANCELLED
    assertThat(admin.service.approve(tablet.id, ApprovalProof.TypedCode(tablet.shownCode)))
      .isEqualTo(AdminActionResult.KeyRefused(VaultRefusal.CANCELLED))
    assertThat(admin.engine.state!!.registry.device(tablet.id)).isNull()
    assertThat(admin.service.pendingRequests.value.map { it.id }).contains(tablet.id)

    admin.vault.sheet = null
    assertThat(admin.service.approve(tablet.id, ApprovalProof.TypedCode(tablet.shownCode))).isEqualTo(AdminActionResult.Done)
    assertThat(admin.vault.sheets).isEqualTo(sheets + 2)
  }

  // --- Un foglio per gesto, nessun foglio senza firma -----------------------------------------------------------

  @Test
  fun nienteDaFirmare_nessunFoglio_ancheLInvitoELeSegnalazioniAssenti() = runTest {
    admin.engine.ensureLoaded()

    assertThat(admin.service.updateNasAddress(world.config.nasHost)).isEqualTo(AdminActionResult.Done)
    assertThat(admin.service.updateCommonExclusions(world.config.commonExclusions.toSet())).isEqualTo(AdminActionResult.Done)
    assertThat(admin.service.createInvite(InviteParams("mamma", "Tablet di Mamma", "mamma", null)).familyId).isEqualTo(world.familyId)
    assertThat(admin.lostReporting.convertReports()).isEqualTo(AdminActionResult.Done)

    assertThat(admin.vault.touches).isEqualTo(0)
  }

  @Test
  fun dueSegnalazioniConvertiteInsieme_unSoloFoglio() = runTest {
    val tablet = world.joinApproved("Tablet di Mamma", "mamma", privateShare = null)
    val old = world.joinApproved("Telefono vecchio di Mamma", "mamma", privateShare = null)
    val phone = world.joinApproved("Telefono di Mamma", "mamma")
    phone.service.refresh(Purpose.BACKGROUND)
    assertThat(phone.lostReporting.reportOwnDeviceLost(tablet.id)).isEqualTo(AdminActionResult.Done)
    assertThat(phone.lostReporting.reportOwnDeviceLost(old.id)).isEqualTo(AdminActionResult.Done)
    val sheets = admin.vault.sheets

    assertThat(admin.lostReporting.convertReports()).isEqualTo(AdminActionResult.Done)

    assertThat(admin.vault.sheets).isEqualTo(sheets + 1)
    assertThat(admin.engine.state!!.statusOf(tablet.id)).isEqualTo(DeviceStatus.LOST)
    assertThat(admin.engine.state!!.statusOf(old.id)).isEqualTo(DeviceStatus.LOST)
  }

  // --- Biometria cambiata: chiave invalidata ------------------------------------------------------------------

  @Test
  fun chiaveInvalidata_lEsitoLoDice_nienteSiPerde_eIlKitRimetteTuttoAPosto() = runTest {
    val tablet = world.joinApproved("Tablet di Mamma", "mamma")
    admin.vault.invalidated = true

    assertThat(admin.service.changeStatus(tablet.id, DeviceStatus.REVOKED)).isEqualTo(AdminActionResult.KeyRefused(VaultRefusal.INVALIDATED))
    assertThat(admin.vault.key).isNotNull() // niente si cancella da solo

    // Il kit di recupero, dallo stesso telefono: la chiave torna nella cassaforte (con il foglio) e l'admin firma di nuovo.
    val kits = (admin.recovery.findKits(account, "admin2") as NasResult.Ok).value
    val outcome = admin.recovery.recover(kits.single(), world.recoveryCode.toDisplayString().toCharArray(), stateQr(tablet), OldAdminFate.KEEP_APPROVED)
    assertThat(outcome).isEqualTo(RecoveryOutcome.Recovered)
    assertThat(admin.vault.invalidated).isFalse()
    assertThat(admin.service.changeStatus(tablet.id, DeviceStatus.REVOKED)).isEqualTo(AdminActionResult.Done)
  }

  @Test
  fun recuperoDellAdminConIlFoglioAnnullato_nienteFirmato_nienteSalvato() = runTest {
    val tablet = world.joinApproved("Tablet di Mamma", "mamma")
    val phone = Device(world, "Telefono nuovo di Admin", "admin2", privateShare = "admin2")
    val version = admin.engine.state!!.registry.version
    phone.vault.sheet = VaultRefusal.CANCELLED

    val kits = (phone.recovery.findKits(account, "admin2") as NasResult.Ok).value
    val outcome = phone.recovery.recover(kits.single(), world.recoveryCode.toDisplayString().toCharArray(), stateQr(tablet), OldAdminFate.LOST)

    assertThat(outcome).isEqualTo(RecoveryOutcome.Failed(OnboardingProblem.FAMILY_KEY_NOT_CONFIRMED))
    assertThat(phone.vault.key).isNull()
    assertThat(world.common.exists(NasLayout.registryFile(version + 1))).isFalse()
  }

  @Test
  fun resetConIlFoglioAnnullato_nienteCambia_confermato_unSoloFoglio() = runTest {
    admin.engine.ensureLoaded()
    admin.vault.sheet = VaultRefusal.CANCELLED

    assertThat(admin.reset.reset(confirmedTwice = true)).isEqualTo(ResetOutcome.Failed(OnboardingProblem.FAMILY_KEY_NOT_CONFIRMED))
    assertThat(admin.vault.key!!.publicKey).isEqualTo(world.familyKey.publicKey)
    assertThat(admin.engine.state!!.registry.familyId).isEqualTo(world.familyId)

    // Confermato: un solo foglio (la chiave vecchia non si apre piu' per poterla rimettere: si tiene la cassaforte cifrata).
    admin.vault.sheet = null
    val sheets = admin.vault.sheets
    assertThat(admin.reset.reset(confirmedTwice = true)).isInstanceOf(ResetOutcome.Done::class.java)
    assertThat(admin.vault.uses.drop(sheets)).containsExactly(FamilyKeyUse.SAVE)
  }

  private companion object {
    const val DAY = 24L * 60 * 60 * 1000
  }
}
