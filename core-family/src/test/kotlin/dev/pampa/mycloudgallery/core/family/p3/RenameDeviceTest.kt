package dev.pampa.mycloudgallery.core.family.p3

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * F27 delle prove sul campo (E-B7): "Rinomina questo dispositivo". Il nome sta nella voce firmata del registro, quindi lo cambia l'admin con una
 * versione nuova firmata (`FamilyAuthority.renameDevice` → `commit`, stesse regole di ogni azione dell'admin); un dispositivo che non tiene la
 * chiave di famiglia non firma niente. Nessun formato nuovo. La cartella del backup non c'entra (`DefaultBackupSettingsTest`, E-B7).
 */
class RenameDeviceTest {

  private val world = FamilyWorld()
  private val admin get() = world.admin

  @Test
  fun lAdminRinominaSeStessoEIlNomeNuovoArrivaAgliAltriDispositivi() = runTest {
    val tablet = world.joinApproved("Tablet di Mamma", "mamma")
    val versionBefore = admin.engine.state!!.registry.version

    val result = admin.service.renameDevice(admin.id, "Telefono di Mario")

    assertThat(result).isEqualTo(AdminActionResult.Done)
    val entry = admin.engine.state!!.registry.device(admin.id)!!
    assertThat(entry.name).isEqualTo("Telefono di Mario")
    // Identita' e ruolo non cambiano: e' lo stesso dispositivo, con un'etichetta diversa.
    assertThat(entry.admin).isTrue()
    assertThat(entry.status).isEqualTo(DeviceStatus.APPROVED)
    assertThat(admin.engine.state!!.registry.version).isEqualTo(versionBefore + 1)
    assertThat(admin.service.devices.value.single { it.id == admin.id }.name).isEqualTo("Telefono di Mario")
    // Il tablet rilegge il registro firmato e lo accetta: vede il nome nuovo.
    tablet.service.refresh(Purpose.BACKGROUND)
    assertThat(tablet.service.devices.value.single { it.id == admin.id }.name).isEqualTo("Telefono di Mario")
  }

  @Test
  fun unDispositivoCheNonTieneLaChiaveDiFamigliaNonFirmaNienteENonScriveSulNas() = runTest {
    val tablet = world.joinApproved("Tablet di Mamma", "mamma")
    val before = world.common.publishes

    val result = tablet.service.renameDevice(tablet.id, "Il mio tablet")

    assertThat(result).isEqualTo(AdminActionResult.NotAdmin)
    assertThat(world.common.publishes).isEqualTo(before)
    assertThat(tablet.engine.state!!.registry.device(tablet.id)!!.name).isEqualTo("Tablet di Mamma")
    // Nemmeno l'admin cambia il nome degli altri per sbaglio: l'unico nome e' quello di prima.
    assertThat(admin.engine.state!!.registry.device(tablet.id)!!.name).isEqualTo("Tablet di Mamma")
  }

  @Test
  fun unNomeUgualeOChePareUgualeADiUnAltroDispositivoERifiutatoENonSiScrive() = runTest {
    world.joinApproved("Tablet di Mamma", "mamma")
    val before = world.common.publishes

    // Maiuscole e spazi in piu' non lo rendono diverso (LookAlike).
    assertThat(admin.service.renameDevice(admin.id, "tablet  di MAMMA")).isEqualTo(AdminActionResult.Failed(NasError.Exists))
    assertThat(admin.service.renameDevice(admin.id, "Tablet di Mamma")).isEqualTo(AdminActionResult.Failed(NasError.Exists))

    assertThat(world.common.publishes).isEqualTo(before)
    assertThat(admin.engine.state!!.registry.device(admin.id)!!.name).isEqualTo("Telefono di Admin")
  }

  @Test
  fun lo_stessoNomeDiPrimaNonFirmaNienteELoSbagliatoNeppure() = runTest {
    val before = world.common.publishes

    assertThat(admin.service.renameDevice(admin.id, "Telefono di Admin")).isEqualTo(AdminActionResult.Done)
    assertThat(world.common.publishes).isEqualTo(before)

    // Un nome fuori formato (spazi ai bordi, vuoto) non arriva a firmare: la pagina lo controlla prima, ma anche qui non passa.
    assertThat(admin.service.renameDevice(admin.id, " Telefono")).isInstanceOf(AdminActionResult.Failed::class.java)
    assertThat(admin.service.renameDevice(admin.id, "")).isInstanceOf(AdminActionResult.Failed::class.java)
    assertThat(world.common.publishes).isEqualTo(before)
  }

  @Test
  fun unDispositivoSconosciutoOGiaRevocatoNonSiRinomina() = runTest {
    val tablet = world.joinApproved("Tablet di Mamma", "mamma")
    admin.service.refresh(Purpose.INTERACTIVE)
    assertThat(admin.service.renameDevice("NONESISTE", "Nome")).isEqualTo(AdminActionResult.Failed(NasError.NotFound))

    assertThat(admin.service.changeStatus(tablet.id, DeviceStatus.REVOKED)).isEqualTo(AdminActionResult.Done)
    val before = world.common.publishes
    assertThat(admin.service.renameDevice(tablet.id, "Nome nuovo")).isEqualTo(AdminActionResult.Failed(NasError.PermissionDenied))
    assertThat(world.common.publishes).isEqualTo(before)
  }
}
