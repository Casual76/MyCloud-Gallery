package dev.pampa.mycloudgallery.core.family.p3

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.family.contract.DeviceRoleView
import dev.pampa.mycloudgallery.core.family.contract.PreflightResult
import dev.pampa.mycloudgallery.core.family.contract.TrustSnapshot
import dev.pampa.mycloudgallery.core.family.registry.DeviceStatusBook
import dev.pampa.mycloudgallery.core.family.registry.FileApprovalSightings
import dev.pampa.mycloudgallery.core.family.trust.FileTrustStateStore
import dev.pampa.mycloudgallery.core.family.trust.OwnTrustState
import dev.pampa.mycloudgallery.core.family.trust.TrustDiagCodes
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.data.DeviceRole
import dev.pampa.mycloudgallery.core.trust.data.DeviceStatusReport
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Stato dei dispositivi (tipo 8) con anti-rollback e pubblicazione al massimo giornaliera; elenco
 * "Dispositivi"; contributo alla Diagnostica (fiducia) senza dati personali; frequenza della guardia; file locali.
 */
class StatusAndDiagnosticsTest {

  private val world = FamilyWorld()

  @Test
  fun loStatoSiPubblicaAlMassimoUnaVoltaAlGiornoESiVedeNellElenco() = runTest {
    val tablet = world.joinApproved("Tablet di Mamma", "mamma")
    val before = world.common.publishes
    assertThat(tablet.statuses.publishIfDue(Purpose.BACKGROUND)).isFalse() // gia' pubblicato da refresh
    world.clock.advance(TrustLimits.DEVICE_STATUS_INTERVAL_MILLIS - 1)
    assertThat(tablet.statuses.publishIfDue(Purpose.BACKGROUND)).isFalse()
    world.clock.advance(1)
    assertThat(tablet.statuses.publishIfDue(Purpose.BACKGROUND)).isTrue()
    assertThat(world.common.publishes).isEqualTo(before + 1)
    // Un cambio di ruoli si pubblica subito.
    tablet.profile = tablet.profile.copy(roles = setOf(DeviceRole.INDEXER, DeviceRole.PREPARER))
    assertThat(tablet.statuses.publishIfDue(Purpose.BACKGROUND)).isTrue()

    world.admin.service.refresh(Purpose.INTERACTIVE)
    val view = world.admin.service.devices.value.single { it.id == tablet.id }
    assertThat(view.roles).containsExactly(DeviceRoleView.INDEXER, DeviceRoleView.PREPARER)
    assertThat(view.lastSeenAt).isEqualTo(world.clock.now)
    assertThat(view.appVersion).isEqualTo("2.0.0")
    assertThat(view.nasUser).isEqualTo("mamma")
    assertThat(world.admin.service.devices.value.single { it.isThisDevice }.admin).isTrue()
  }

  @Test
  fun unoStatoVecchioOEquivocoNonSostituisceQuelloAccettato() = runTest {
    val tablet = world.joinApproved("Tablet di Mamma", "mamma")
    world.clock.advance(TrustLimits.DEVICE_STATUS_INTERVAL_MILLIS)
    tablet.statuses.publishIfDue(Purpose.BACKGROUND) // versione 2
    world.admin.service.refresh(Purpose.INTERACTIVE)
    val seen = world.admin.statuses.latest.value[tablet.id]!!
    assertThat(seen.version).isEqualTo(2)
    val mark = world.admin.engine.mark(DeviceStatusBook.markKey(tablet.id))!!

    // Chi ha SMB non ha la chiave del tablet: puo' solo rimettere copie vecchie. La versione 1 al posto della 2
    // e al nome 3 non vale (il numero firmato non e' quello del posto) e lo stato accettato resta la 2.
    val v1 = world.common.bytesAt(NasLayout.statusFile(tablet.id, 1))!!
    world.common.attackerWrite(NasLayout.statusFile(tablet.id, 2), v1)
    world.common.attackerWrite(NasLayout.statusFile(tablet.id, 3), v1)
    world.admin.service.refresh(Purpose.INTERACTIVE)

    assertThat(world.admin.statuses.latest.value[tablet.id]!!.version).isEqualTo(2)
    assertThat(world.admin.engine.mark(DeviceStatusBook.markKey(tablet.id))).isEqualTo(mark)
    assertThat(world.admin.statuses.rejected.value).isGreaterThan(0)
  }

  @Test
  fun unoStatoCheCitaUnRegistroPiuNuovoDaLAvvisoRegistroIndietro() = runTest {
    val tablet = world.joinApproved("Tablet di Mamma", "mamma")
    world.joinApproved("Telefono di Babbo", "babbo") // v3: il tablet e' ancora alla v2
    world.clock.advance(TrustLimits.DEVICE_STATUS_INTERVAL_MILLIS)
    assertThat(world.admin.statuses.publishIfDue(Purpose.BACKGROUND)).isTrue() // l'admin dichiara la v3
    // Il tablet rilegge gli stati senza aver visto la v3 (per esempio, qualcuno gliela nasconde).
    tablet.statuses.refresh(Purpose.BACKGROUND)

    assertThat(tablet.engine.facts.value.registryBehind).isTrue()
    val items = tablet.diagnostics.collect()
    assertThat(items.map { it.code }).contains(TrustDiagCodes.REGISTRY_BEHIND)
  }

  @Test
  fun laDiagnosticaDellaFiduciaEsportaSoloNumeriFlagEdEnum() = runTest {
    val tablet = world.joinApproved("Tablet di Mamma", "mamma")
    world.common.attackerWrite(NasLayout.registryFile(3), "spazzatura".toByteArray())
    world.common.attackerWrite(NasLayout.registryFile(4), world.common.bytesAt(NasLayout.registryFile(1))!!.also { it[4] = 9 })
    tablet.service.refresh(Purpose.BACKGROUND)

    val items = tablet.diagnostics.collect()

    assertThat(items.all { it.section == DiagSection.TRUST }).isTrue()
    val codes = items.map { it.code }
    assertThat(codes).containsAtLeast(TrustDiagCodes.STATE, TrustDiagCodes.REJECTED, TrustDiagCodes.NEEDS_APP_UPDATE, TrustDiagCodes.NEWER_UNVERIFIED)
    val state = items.single { it.code == TrustDiagCodes.STATE }
    assertThat(state.export["state"]).isEqualTo(ExportValue.Enum.of(OwnTrustState.STALE))
    assertThat(state.export["registryVersion"]).isEqualTo(ExportValue.Num(2))
    // Nessun nome, account, id o percorso nell'esportazione (per costruzione del tipo, verificato qui).
    val exported = items.flatMap { it.export.values }.joinToString { it.toString() }
    for (secret in listOf("Mamma", "mamma", "admin2", tablet.id, world.familyId, COMMON, "192.168")) {
      assertThat(exported).doesNotContain(secret)
    }
    assertThat(TrustDiagCodes.ALL.map { it.id }.toSet()).hasSize(TrustDiagCodes.ALL.size)
  }

  @Test
  fun laGuardiaRileggeIlRegistroAlMassimoOgniQuindiciMinuti() = runTest {
    val tablet = world.joinApproved("Tablet di Mamma", "mamma")
    tablet.later(DAY)
    assertThat(tablet.guard.preflight(Purpose.BACKGROUND)).isInstanceOf(PreflightResult.Proceed::class.java)
    val reads = world.common.reads

    tablet.later(14 * 60 * 1000)
    tablet.guard.preflight(Purpose.BACKGROUND)
    assertThat(world.common.reads).isEqualTo(reads) // niente NAS

    tablet.later(60 * 1000)
    tablet.guard.preflight(Purpose.BACKGROUND)
    assertThat(world.common.reads).isGreaterThan(reads)
  }

  @Test
  fun dismessoSolaLetturaRevocatoFermo() = runTest {
    val tablet = world.joinApproved("Tablet di Mamma", "mamma")
    world.admin.service.changeStatus(tablet.id, DeviceStatus.RETIRED)
    tablet.later(DAY)
    assertThat(tablet.guard.preflight(Purpose.BACKGROUND))
      .isEqualTo(PreflightResult.ReadOnly(dev.pampa.mycloudgallery.core.family.contract.ReadOnlyReason.RETIRED))
    world.admin.service.changeStatus(tablet.id, DeviceStatus.REVOKED)
    tablet.later(DAY)
    assertThat(tablet.guard.preflight(Purpose.BACKGROUND))
      .isEqualTo(PreflightResult.Stop(dev.pampa.mycloudgallery.core.family.contract.StopReason.REVOKED))
    assertThat(tablet.keys.hasDeviceKey()).isTrue() // revocato non vuol dire perso: niente azzeramento
    // L'admin non torna mai indietro e non cambia se stesso.
    assertThat(world.admin.service.changeStatus(tablet.id, DeviceStatus.APPROVED))
      .isInstanceOf(dev.pampa.mycloudgallery.core.family.contract.AdminActionResult.Failed::class.java)
    assertThat(world.admin.service.changeStatus(world.admin.id, DeviceStatus.LOST))
      .isInstanceOf(dev.pampa.mycloudgallery.core.family.contract.AdminActionResult.Failed::class.java)
  }

  // --- File locali -------------------------------------------------------------------------------------

  @Test
  fun loStatoFidatoSuFileFaAndataERitornoEUnFileRovinatoValeNessuno() = runTest {
    val dir = Files.createTempDirectory("trust").toFile()
    val file = File(dir, "trust-state")
    val store = FileTrustStateStore(file)
    world.admin.engine.ensureLoaded()
    val state = world.admin.engine.state!!
    val snapshot = TrustSnapshot(state.signedBytes, state.signerKey.fingerprint, world.familyId, world.familyKey.publicKey.fingerprint, 1, mapOf("status/x" to HeadMark(3, "h")))

    store.save(snapshot)
    val back = FileTrustStateStore(file).load()!!

    assertThat(back.registryBytes).isEqualTo(snapshot.registryBytes)
    assertThat(back.ownHeads).isEqualTo(snapshot.ownHeads)
    assertThat(back.minRegistryVersion).isEqualTo(1)
    file.writeBytes(file.readBytes().copyOf(30))
    assertThat(FileTrustStateStore(file).load()).isNull()
    store.clear()
    assertThat(file.exists()).isFalse()
    dir.deleteRecursively()
  }

  @Test
  fun leRichiesteVisteSopravvivonoSuFile() = runTest {
    val dir = Files.createTempDirectory("seen").toFile()
    val file = File(dir, "approval-sightings")
    val id = TrustIds.random()
    assertThat(FileApprovalSightings(file).observe(id, 100)).isEqualTo(100)
    assertThat(FileApprovalSightings(file).observe(id, 999)).isEqualTo(100)
    FileApprovalSightings(file).retain(emptySet())
    assertThat(FileApprovalSightings(file).observe(id, 999)).isEqualTo(999)
    dir.deleteRecursively()
  }

  @Test
  fun unoStatoFirmatoContieneSoloIlNecessario() {
    // Lo stato non porta nomi: solo id, versioni, ruoli e ore (L11).
    val report = DeviceStatusReport(world.familyId, TrustIds.random(), 1, "2.0.0", emptySet(), world.clock.now, 1)
    assertThat(report.roles).isEmpty()
  }

  private companion object {
    const val DAY = 24L * 60 * 60 * 1000
  }
}
