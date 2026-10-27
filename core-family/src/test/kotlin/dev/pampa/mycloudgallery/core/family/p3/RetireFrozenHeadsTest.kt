package dev.pampa.mycloudgallery.core.family.p3

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.registry.RetireHeads
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * R6 della revisione di sicurezza della Fase 7: dismettere un dispositivo congela le teste che questo dispositivo admin ha accettato (A7, D8:
 * "dismesso: valgono i contributi delle sue teste congelate"). Prima la dismissione non ne congelava nessuna ("per ora nessuna"), e per gli
 * album condivisi un dismesso valeva come un revocato: il vecchio telefono del creatore dismesso faceva sparire l'album a tutta la famiglia.
 */
class RetireFrozenHeadsTest {

  private val world = FamilyWorld()
  private val admin get() = world.admin

  private fun hash(n: Int): String = Base32.encode(ByteArray(32) { (it * 3 + n).toByte() })

  private suspend fun approvedTablet(): Device {
    val tablet = world.join("Tablet di Mamma", "mamma")
    admin.service.refresh(Purpose.INTERACTIVE)
    check(admin.service.approve(tablet.id, ApprovalProof.TypedCode(tablet.shownCode)) == AdminActionResult.Done)
    return tablet
  }

  @Test
  fun laDismissioneCongelaLeTesteCheGliAlbumIndicano() = runTest {
    val tablet = approvedTablet()
    val heads = (1..3).map(::hash)
    var asked: String? = null
    admin.writer.retireHeads = RetireHeads { device -> asked = device; heads + heads.first() }

    assertThat(admin.service.changeStatus(tablet.id, DeviceStatus.RETIRED)).isEqualTo(AdminActionResult.Done)

    assertThat(asked).isEqualTo(tablet.id)
    val entry = admin.engine.state!!.registry.device(tablet.id)!!
    assertThat(entry.status).isEqualTo(DeviceStatus.RETIRED)
    assertThat(entry.frozenHeads).containsExactlyElementsIn(heads.sorted()).inOrder()
  }

  @Test
  fun alPiuIlTettoDelRegistroESoloPerLaDismissione() = runTest {
    val tablet = approvedTablet()
    val many = (1..TrustLimits.MAX_FROZEN_HEADS + 10).map(::hash)
    admin.writer.retireHeads = RetireHeads { many }
    assertThat(admin.service.changeStatus(tablet.id, DeviceStatus.RETIRED)).isEqualTo(AdminActionResult.Done)
    // Le prime (le piu' importanti, nell'ordine di chi le indica), al piu' quante il registro ne tiene.
    assertThat(admin.engine.state!!.registry.device(tablet.id)!!.frozenHeads).containsExactlyElementsIn(many.take(TrustLimits.MAX_FROZEN_HEADS))
  }

  @Test
  fun unaRevocaNonCongelaNiente_eUnErroreFermaLaDismissione() = runTest {
    val tablet = approvedTablet()
    admin.writer.retireHeads = RetireHeads { error("replica non pronta") }
    // Dismettere senza sapere che cosa congelare farebbe sparire i contributi: non parte.
    assertThat(admin.service.changeStatus(tablet.id, DeviceStatus.RETIRED)).isInstanceOf(AdminActionResult.Failed::class.java)
    assertThat(admin.engine.state!!.registry.device(tablet.id)!!.status).isEqualTo(DeviceStatus.APPROVED)
    // Revocare non chiede niente: un revocato non conserva dati.
    assertThat(admin.service.changeStatus(tablet.id, DeviceStatus.REVOKED)).isEqualTo(AdminActionResult.Done)
    assertThat(admin.engine.state!!.registry.device(tablet.id)!!.frozenHeads).isEmpty()
  }
}
