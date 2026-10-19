package dev.pampa.mycloudgallery.core.family.p3

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
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
