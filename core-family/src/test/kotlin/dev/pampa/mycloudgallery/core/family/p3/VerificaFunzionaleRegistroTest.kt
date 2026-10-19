package dev.pampa.mycloudgallery.core.family.p3

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.InviteParams
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.testing.FakeRegistryService
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La verifica funzionale delle fasi 1-5A (voci ON) dal lato del registro vero: la configurazione firmata che cambia
 * dopo la fondazione (ON-8) e la finestra d'approvazione, uguale nel registro vero e in quello finto (ON-2).
 */
class VerificaFunzionaleRegistroTest {

  @Test
  fun on8_leEsclusioniDellAdminSiFirmanoELeLeggonoGliAltri() = runTest {
    val world = FamilyWorld()
    val admin = world.admin
    admin.engine.ensureLoaded()
    val tablet = world.joinApproved("Tablet di Mamma", "mamma")

    assertThat(admin.service.updateCommonExclusions(setOf("Musica", "Film/Serie"))).isEqualTo(AdminActionResult.Done)
    assertThat(admin.engine.state!!.registry.config.commonExclusions).containsExactly("Film/Serie", "Musica").inOrder()
    // Gli altri dispositivi le leggono dal registro firmato (la scansione usa quelle).
    tablet.service.refresh(Purpose.BACKGROUND)
    assertThat(tablet.engine.state!!.registry.config.commonExclusions).containsExactly("Film/Serie", "Musica").inOrder()
    // Le stesse di prima: niente da firmare.
    val version = admin.engine.state!!.registry.version
    assertThat(admin.service.updateCommonExclusions(setOf("Musica", "Film/Serie"))).isEqualTo(AdminActionResult.Done)
    assertThat(admin.engine.state!!.registry.version).isEqualTo(version)
    // Chi non tiene la chiave di famiglia non firma.
    assertThat(tablet.service.updateCommonExclusions(setOf("Altro"))).isEqualTo(AdminActionResult.NotAdmin)
  }

  /**
   * RE-5: il NAS cambia IP. L'admin lo corregge e l'invito nuovo deve portare quello nuovo: l'indirizzo dell'invito viene dalla
   * configurazione **firmata**, che prima nessuno aggiornava.
   */
  @Test
  fun re5_l_indirizzoDelNasCambiatoDallAdminEntraNellaConfigurazioneFirmataENelNuovoInvito() = runTest {
    val world = FamilyWorld()
    val admin = world.admin
    admin.engine.ensureLoaded()
    val tablet = world.joinApproved("Tablet di Mamma", "mamma")
    val params = InviteParams("mamma2", "Telefono di Nonna", "mamma2", "night")
    assertThat(admin.service.createInvite(params).nasIp).isEqualTo("192.168.178.58")

    assertThat(admin.service.updateNasAddress("192.168.178.77")).isEqualTo(AdminActionResult.Done)

    assertThat(admin.engine.state!!.registry.config.nasHost).isEqualTo("192.168.178.77")
    val invite = admin.service.createInvite(params)
    assertThat(invite.nasIp).isEqualTo("192.168.178.77")
    // L'identita' del NAS non cambia con l'indirizzo: stesso GUID di prima.
    assertThat(invite.nasGuid).isEqualTo(world.config.nasGuid)
    // Gli altri dispositivi leggono la configurazione dal registro firmato.
    tablet.service.refresh(Purpose.BACKGROUND)
    assertThat(tablet.engine.state!!.registry.config.nasHost).isEqualTo("192.168.178.77")

    // Lo stesso indirizzo non firma una versione nuova; un indirizzo che il formato non accetta non firma niente.
    val version = admin.engine.state!!.registry.version
    assertThat(admin.service.updateNasAddress("192.168.178.77")).isEqualTo(AdminActionResult.Done)
    assertThat(admin.engine.state!!.registry.version).isEqualTo(version)
    assertThat(admin.service.updateNasAddress("non-un-indirizzo")).isInstanceOf(AdminActionResult.Failed::class.java)
    assertThat(admin.engine.state!!.registry.version).isEqualTo(version)
  }

  @Test
  fun re5_laNuovaImprontaDiCasaDellAdminEntraNellaConfigurazioneFirmataENelNuovoInvito() = runTest {
    val world = FamilyWorld()
    val admin = world.admin
    admin.engine.ensureLoaded()
    val params = InviteParams("mamma", "Tablet di Mamma", "mamma", "night")
    assertThat(admin.service.createInvite(params).homeUla).containsExactly("fd12:3456:789a::/48")

    // Il router e' stato reimpostato: un ULA nuovo (anche in una forma non canonica, che si riporta al formato).
    assertThat(admin.service.updateHomeUla(setOf("FD99:1234:5678::/48"))).isEqualTo(AdminActionResult.Done)

    assertThat(admin.engine.state!!.registry.config.homeUla).containsExactly("fd99:1234:5678::/48")
    assertThat(admin.service.createInvite(params).homeUla).containsExactly("fd99:1234:5678::/48")

    // Un prefisso che non e' un /48 ULA non entra mai; senza nessun prefisso valido non si firma niente (un invito senza ULA e' invalido).
    val version = admin.engine.state!!.registry.version
    assertThat(admin.service.updateHomeUla(setOf("2001:db8::/48", "fd99:1234:5678:1::/64"))).isInstanceOf(AdminActionResult.Failed::class.java)
    assertThat(admin.service.updateHomeUla(emptySet())).isInstanceOf(AdminActionResult.Failed::class.java)
    assertThat(admin.engine.state!!.registry.version).isEqualTo(version)
    assertThat(admin.engine.state!!.registry.config.homeUla).containsExactly("fd99:1234:5678::/48")
  }

  /** Il controllo e' quello di ogni azione firmata dell'admin: chi non tiene la chiave di famiglia non firma niente. */
  @Test
  fun re5_chiNonEAdminNonFirmaNullaECambiaSoloPerSe() = runTest {
    val world = FamilyWorld()
    world.admin.engine.ensureLoaded()
    val tablet = world.joinApproved("Tablet di Mamma", "mamma")
    val before = tablet.engine.state!!.registry.version

    assertThat(tablet.service.updateNasAddress("192.168.178.77")).isEqualTo(AdminActionResult.NotAdmin)
    assertThat(tablet.service.updateHomeUla(setOf("fd99:1234:5678::/48"))).isEqualTo(AdminActionResult.NotAdmin)

    world.admin.service.refresh(Purpose.BACKGROUND)
    assertThat(world.admin.engine.state!!.registry.version).isEqualTo(before)
    assertThat(world.admin.engine.state!!.registry.config.nasHost).isEqualTo("192.168.178.58")
    assertThat(world.admin.engine.state!!.registry.config.homeUla).containsExactly("fd12:3456:789a::/48")
  }

  @Test
  fun on2_laFinestraDiQuindiciMinutiEUgualeNelRegistroVeroEInQuelloFinto() = runTest {
    for (minutes in listOf(14L, 16L)) {
      // Il vero: la finestra parte da quando l'app dell'admin vede la richiesta.
      val world = FamilyWorld()
      val admin = world.admin
      admin.engine.ensureLoaded()
      val tablet = world.join("Tablet di Mamma", "mamma")
      admin.service.refresh(Purpose.INTERACTIVE)
      val seen = admin.service.pendingRequests.value.single()
      world.clock.advance(minutes * MINUTE)
      val real = admin.service.approve(tablet.id, ApprovalProof.TypedCode(tablet.shownCode))

      // Il finto, con la stessa ora del NAS e la stessa prima vista.
      val fake = FakeRegistryService(pending = listOf(seen))
      fake.nasNow = seen.firstSeenAt + minutes * MINUTE
      val faked = fake.approve(seen.id, ApprovalProof.TypedCode(tablet.shownCode))

      if (minutes > 15) {
        assertThat(real).isEqualTo(AdminActionResult.WindowExpired)
        assertThat(faked).isEqualTo(AdminActionResult.WindowExpired)
      } else {
        assertThat(real).isEqualTo(AdminActionResult.Done)
        assertThat(faked).isEqualTo(AdminActionResult.Done)
      }
    }
  }

  @Test
  fun ilRegistroFintoCambiaStatoSoloRileggendoComeQuelloVero() = runTest {
    val fake = FakeRegistryService(status = TrustStatus.Approved(admin = false))
    fake.statusOnRefresh += TrustStatus.Revoked
    assertThat(fake.status.value).isEqualTo(TrustStatus.Approved(admin = false))
    fake.refresh(Purpose.BACKGROUND)
    assertThat(fake.status.value).isEqualTo(TrustStatus.Revoked)

    // Il vero: revocato dall'admin, il tablet lo scopre alla prossima lettura del registro.
    val world = FamilyWorld()
    world.admin.engine.ensureLoaded()
    val tablet = world.joinApproved("Tablet di Mamma", "mamma")
    world.admin.service.refresh(Purpose.INTERACTIVE)
    assertThat(world.admin.service.changeStatus(tablet.id, DeviceStatus.REVOKED))
      .isEqualTo(AdminActionResult.Done)
    assertThat(tablet.service.status.value).isEqualTo(TrustStatus.Approved(admin = false))
    tablet.service.refresh(Purpose.BACKGROUND)
    assertThat(tablet.service.status.value).isEqualTo(TrustStatus.Revoked)
  }

  private companion object {
    const val MINUTE = 60 * 1000L
  }
}
