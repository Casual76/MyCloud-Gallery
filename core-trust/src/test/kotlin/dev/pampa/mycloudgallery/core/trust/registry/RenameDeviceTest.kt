package dev.pampa.mycloudgallery.core.trust.registry

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.rejectionOrNull
import dev.pampa.mycloudgallery.core.trust.testing.TestFamily
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * F27 delle prove sul campo (E-B7): il nome di un dispositivo sta nella voce **firmata** del registro, quindi lo cambia solo chi tiene la chiave di
 * famiglia, con una versione nuova firmata come ogni altra. Nessun formato nuovo: cambia solo il campo `name`; chiave, id, account, ruolo e stato
 * restano quelli di prima e la regola anti-regressione, che non guarda il nome, accetta la versione.
 */
class RenameDeviceTest {

  private val family = TestFamily()
  private val tablet = family.approveNewDevice(name = "Tablet profilo Mamma", nasUser = "mamma")

  private fun rename(deviceId: String, name: String) = family.commit(family.authority.renameDevice(family.state, deviceId, name))

  @Test
  fun rinominareFirmaUnaVersioneNuovaECambiaSoloIlNome() {
    val before = family.state.registry.device(tablet.deviceId)!!
    val versionBefore = family.state.registry.version

    val state = rename(tablet.deviceId, "Tablet di Mamma")

    val after = state.registry.device(tablet.deviceId)!!
    assertThat(after.name).isEqualTo("Tablet di Mamma")
    assertThat(state.registry.version).isEqualTo(versionBefore + 1)
    // Stessa identita': chiave, id, account, ruolo, stato e data dell'ultimo cambio di stato.
    assertThat(after.copy(name = before.name)).isEqualTo(before)
    // Gli altri dispositivi non si toccano.
    assertThat(state.registry.device(family.adminDevice.deviceId)).isEqualTo(family.state.registry.device(family.adminDevice.deviceId))
  }

  @Test
  fun unDispositivoCheHaGiaIlRegistroAccettaLaVersioneNuovaFirmata() {
    val oldState = family.state
    val renamed = rename(tablet.deviceId, "Tablet di Mamma")

    val verdict = RegistryVerifier.accept(renamed.signedBytes, oldState, family.anchor())

    assertThat(verdict.rejectionOrNull()).isNull()
    assertThat((verdict as Verdict.Accepted).value.registry.device(tablet.deviceId)!!.name).isEqualTo("Tablet di Mamma")
    // E un dispositivo nuovo che legge tutta la storia arriva allo stesso nome.
    val sync = RegistrySync(family.common).refresh(current = null, anchor = family.anchor())
    assertThat(sync.state!!.registry.device(tablet.deviceId)!!.name).isEqualTo("Tablet di Mamma")
  }

  @Test
  fun soloLaChiaveDiFamigliaCambiaUnNome_unDispositivoNonLoFa() {
    // Un dispositivo (anche l'admin, con la chiave del TELEFONO e non quella di famiglia) firma un registro con il nome cambiato: rifiutato.
    val forgedRegistry = family.authority.renameDevice(family.state, tablet.deviceId, "Altro nome").content.copy(version = family.state.registry.version + 1)
    val forged = Envelope.seal(DocType.FAMILY_REGISTRY, forgedRegistry.toJson(), family.adminDevice)

    assertThat(RegistryVerifier.accept(forged, family.state, family.anchor()).rejectionOrNull()).isEqualTo(RejectReason.SIGNER_NOT_APPROVED)
    assertThat(family.state.registry.device(tablet.deviceId)!!.name).isEqualTo("Tablet profilo Mamma")
  }

  @Test
  fun unNomeNonValidoNonSiFirma() {
    val id = tablet.deviceId
    for (bad in listOf("", " Tablet", "Tablet ", "A".repeat(65), "Tab\u0000let", "Tab‮olet")) {
      assertThrows("«$bad»", Exception::class.java) { family.authority.renameDevice(family.state, id, bad) }
    }
    // 64 caratteri vanno bene.
    assertThat(rename(id, "A".repeat(64)).registry.device(id)!!.name).hasLength(64)
  }

  @Test
  fun siRinominaSoloUnDispositivoPresenteEApprovato() {
    assertThrows(IllegalArgumentException::class.java) { family.authority.renameDevice(family.state, "NONESISTE", "Nome") }

    family.changeStatus(tablet, DeviceStatus.REVOKED)
    assertThrows(IllegalArgumentException::class.java) { family.authority.renameDevice(family.state, tablet.deviceId, "Nome") }
  }

  @Test
  fun lAdminPuoRinominareSeStesso() {
    val state = rename(family.adminDevice.deviceId, "Telefono di Mario")

    val admin = state.registry.device(family.adminDevice.deviceId)!!
    assertThat(admin.name).isEqualTo("Telefono di Mario")
    assertThat(admin.admin).isTrue()
    assertThat(state.canWrite(family.adminDevice.deviceId)).isTrue()
  }

  @Test
  fun laBozzaNonCambiaLoStatoFinoAllaPubblicazione() {
    val before = family.state

    family.authority.renameDevice(family.state, tablet.deviceId, "Tablet di Mamma")

    assertThat(family.state).isSameInstanceAs(before)
    assertThat(family.state.registry.device(tablet.deviceId)!!.name).isEqualTo("Tablet profilo Mamma")
  }
}
