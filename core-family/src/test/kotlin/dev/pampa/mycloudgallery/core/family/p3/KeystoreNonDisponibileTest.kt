package dev.pampa.mycloudgallery.core.family.p3

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.PreflightResult
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.trust.OwnTrustState
import dev.pampa.mycloudgallery.core.family.trust.TrustDiagnostics
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * R7 della revisione 5B, il residuo: un Keystore che non risponde (per esempio dopo un aggiornamento di sistema) dava "non
 * configurato", e la pagina "fuori dalla famiglia" offriva "Configura di nuovo" per un guasto passeggero. Ora il motore della
 * fiducia lo dice per quello che e' ([TrustStatus.KeystoreUnavailable]), resta chiuso (niente fiducia, niente scritture firmate,
 * niente approvazioni) e con il Keystore guarito torna lo stato di prima.
 */
class KeystoreNonDisponibileTest {

  private val world = FamilyWorld()
  private val admin = world.admin

  private suspend fun loadedAdmin(): Device {
    admin.engine.ensureLoaded()
    admin.engine.refreshStatus()
    assertThat(admin.engine.status.value).isEqualTo(TrustStatus.Approved(admin = true))
    return admin
  }

  @Test
  fun r7_conLoStatoSalvatoUnKeystoreCheNonRispondeNonENonConfigurato() = runTest {
    val device = loadedAdmin()
    device.keys.broken = true

    device.engine.refreshStatus()
    assertThat(device.engine.status.value).isEqualTo(TrustStatus.KeystoreUnavailable)
    // Anche dopo una rilettura del registro dal NAS (il calcolo dopo ogni cambio), e senza eccezioni che escono.
    device.service.refresh(Purpose.BACKGROUND)
    assertThat(device.engine.status.value).isEqualTo(TrustStatus.KeystoreUnavailable)
    assertThat(TrustDiagnostics.ownState(device.engine.status.value)).isEqualTo(OwnTrustState.KEYSTORE_UNAVAILABLE)
    // Niente si e' toccato: nessun azzeramento, la chiave di famiglia e lo stato fidato restano.
    assertThat(device.selfWipe.progress.value).isNull()
    assertThat(device.vault.key).isNotNull()
    assertThat(device.store.snapshot).isNotNull()
  }

  @Test
  fun r7_unDispositivoInAttesaConIlKeystoreGuastoNonEInAttesaNeNonConfigurato() = runTest {
    val tablet = world.join("Tablet di prova", "familiare")
    assertThat(tablet.engine.status.value).isInstanceOf(TrustStatus.Pending::class.java)
    tablet.keys.broken = true

    tablet.engine.refreshStatus()

    // Il codice d'approvazione viene dalla chiave: senza Keystore non c'e', e non si inventa.
    assertThat(tablet.engine.status.value).isEqualTo(TrustStatus.KeystoreUnavailable)
  }

  @Test
  fun r7_senzaStatoSalvatoIlKeystoreNonConta_NonConfigurato() = runTest {
    val fresh = Device(world, "Telefono nuovo", "familiare", privateShare = "familiare")
    fresh.keys.broken = true

    fresh.engine.ensureLoaded()
    fresh.engine.refreshStatus()

    assertThat(fresh.engine.status.value).isEqualTo(TrustStatus.NotConfigured)
  }

  @Test
  fun r7_conIlKeystoreGuaritoRiprovaRidaLoStatoDiPrima() = runTest {
    val device = loadedAdmin()
    device.keys.broken = true
    device.engine.refreshStatus()
    assertThat(device.engine.status.value).isEqualTo(TrustStatus.KeystoreUnavailable)

    // "Riprova": lo stato si ricalcola chiedendo di nuovo la chiave.
    device.keys.broken = false
    device.engine.refreshStatus()

    assertThat(device.engine.status.value).isEqualTo(TrustStatus.Approved(admin = true))
    assertThat(device.guard.writePermit()).isNotNull()
  }

  @Test
  fun r7_conIlKeystoreCheNonRispondeNessunaScritturaFirmataNeApprovazione() = runTest {
    val device = loadedAdmin()
    val tablet = world.join("Tablet di prova", "familiare")
    val tabletId = tablet.id
    val code = tablet.shownCode
    device.service.refresh(Purpose.INTERACTIVE)
    val before = world.common.paths().toSet()
    val privateBefore = world.server.share("admin2").paths().toSet()

    device.keys.broken = true
    device.engine.refreshStatus()

    // Nessun permesso di scrittura, nessuna sessione di lavoro che scrive.
    assertThat(device.guard.writePermit()).isNull()
    assertThat(runCatching { device.guard.preflight(Purpose.BACKGROUND) }.getOrNull()).isNotInstanceOf(PreflightResult.Proceed::class.java)
    // Nessuna approvazione e nessun cambio di stato firmato con la chiave di famiglia (che e' ancora nella cassaforte).
    assertThat(device.service.approve(tabletId, ApprovalProof.TypedCode(code))).isEqualTo(AdminActionResult.NotAdmin)
    assertThat(device.service.changeStatus(tabletId, DeviceStatus.REVOKED)).isEqualTo(AdminActionResult.NotAdmin)
    // Nessuno stato del dispositivo pubblicato, e le richieste in attesa non si mostrano a chi non si sa se e' l'admin.
    assertThat(device.statuses.publishIfDue(Purpose.BACKGROUND)).isFalse()
    device.service.refresh(Purpose.INTERACTIVE)
    assertThat(device.service.pendingRequests.value).isEmpty()

    assertThat(world.common.paths().toSet()).isEqualTo(before)
    assertThat(world.server.share("admin2").paths().toSet()).isEqualTo(privateBefore)
    assertThat(device.engine.state!!.registry.device(tabletId)).isNull()
  }

  @Test
  fun r7_lAvvioConIlKeystoreCheNonRispondeNonAzzeraENonLancia() = runTest {
    // F7: chiave sparita con lo stato fidato presente = azzeramento interrotto da finire. Con il Keystore che non risponde non si
    // sa se la chiave c'e': prima l'eccezione fermava l'avvio (e il dispositivo restava "non configurato"); ora non si azzera
    // niente e l'avvio va avanti.
    val device = loadedAdmin()
    device.keys.broken = true

    device.selfWipe.resumeIfInterrupted()
    device.engine.ensureLoaded()
    device.engine.refreshStatus()

    assertThat(device.selfWipe.progress.value).isNull()
    assertThat(device.log.all()).doesNotContain("keys:destroyDeviceKey")
    assertThat(device.vault.key).isNotNull()
    assertThat(device.engine.status.value).isEqualTo(TrustStatus.KeystoreUnavailable)

    // Il Keystore guarisce e la chiave non c'e' davvero piu': al prossimo avvio l'azzeramento interrotto si finisce, come sempre.
    device.keys.broken = false
    device.keys.destroyDeviceKey()
    device.selfWipe.resumeIfInterrupted()
    assertThat(device.store.snapshot).isNull()
  }
}
