package dev.pampa.mycloudgallery.core.family.p3

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * R19 della revisione di sicurezza della Fase 5B (`docs/review/5b-fase5b.md`), dal lato del registro: la chiave di famiglia da
 * sola non basta a firmare, il dispositivo deve essere anche segnato admin nel registro firmato.
 */
class CorrezioniAdmin5BTest {

  @Test
  fun r19_unaChiaveDiFamigliaRimastaSuUnDispositivoNonAdminNonFirmaLaConfigurazione() = runTest {
    val world = FamilyWorld()
    val admin = world.admin
    admin.engine.ensureLoaded()
    val tablet = world.joinApproved("Tablet di Mamma", "mamma")
    // Un ex admin rientrato come familiare: approvato, con un id nuovo, e la chiave di famiglia ancora nella cassaforte
    // (una distruzione fallita in silenzio, prima della correzione).
    tablet.vault.key = world.familyKey
    tablet.engine.ensureLoaded()
    val version = tablet.engine.state!!.registry.version

    assertThat(tablet.service.updateCommonExclusions(setOf("Altro"))).isEqualTo(AdminActionResult.NotAdmin)
    assertThat(tablet.service.updateNasAddress("192.168.178.77")).isEqualTo(AdminActionResult.NotAdmin)
    assertThat(tablet.writer.adminBase()).isNull()
    assertThat(tablet.engine.state!!.registry.version).isEqualTo(version)

    // L'admin vero continua a firmare.
    assertThat(admin.service.updateCommonExclusions(setOf("Musica"))).isEqualTo(AdminActionResult.Done)
    assertThat(admin.writer.adminContext()).isInstanceOf(dev.pampa.mycloudgallery.core.family.registry.AdminUnlock.Open::class.java)
  }
}
