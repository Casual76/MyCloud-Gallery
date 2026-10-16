package dev.pampa.mycloudgallery.core.backup.safety

import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.BackupGate
import dev.pampa.mycloudgallery.core.backup.contract.BackupStore
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.contract.PrivateCopyGuardian
import dev.pampa.mycloudgallery.core.backup.noop.NoPrivateCopyGuardian
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOpsFactory
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/**
 * Il **punto d'ingresso** del guardiano delle copie private (D9), come [dev.pampa.mycloudgallery.core.backup.upload.BackupEngineFactory]
 * lo e' per il motore (Fase 5A, P7): costruisce le primitive in sola aggiunta dalla sessione del NAS e consegna il servizio. Serve perche'
 * `:app` non nomini mai le primitive del backup (D2: il test di architettura vieta il loro nome fuori da core-nas e core-backup) e
 * non possa quindi mai dare a un servizio del backup una primitiva che toglie o sostituisce un file.
 */
object BackupSafetyFactory {

  /**
   * Il guardiano per il dispositivo [deviceId] sopra [session]. Con una sessione che non e' SMB (un fake, nessun NAS configurato) le
   * primitive non scrivono niente e il guardiano che ne esce non fa niente ([NoPrivateCopyGuardian]).
   *
   * @param privateShare l'area privata del proprietario (`LocalDeviceProfile.privateShare`), null = nessuna.
   * @param commonShare l'area comune della famiglia, se nota.
   * @param destinationRoot D13: la radice delle destinazioni nella build debug con gli account di prova (`mcg-campo`), null = nessuna.
   */
  fun createGuardian(
    session: NasSessionManager,
    deviceId: String,
    store: BackupStore,
    catalog: BackupCatalog,
    phone: PhoneBytes,
    gate: BackupGate,
    clock: BackupClock,
    privateShare: () -> String?,
    commonShare: () -> String? = { null },
    destinationRoot: () -> SharePath? = { null },
    purpose: Purpose = Purpose.BACKGROUND,
  ): PrivateCopyGuardian {
    val ops = NasBackupOpsFactory.create(session, deviceId)
    if (ops.ownerDeviceId != deviceId) return NoPrivateCopyGuardian
    return BackupPrivateCopyGuardian(
      deviceId = deviceId,
      ops = ops,
      reader = session.mediaReader(),
      store = store,
      catalog = catalog,
      phone = phone,
      gate = gate,
      clock = clock,
      privateShare = privateShare,
      commonShare = commonShare,
      destinationRoot = destinationRoot,
      purpose = purpose,
    )
  }
}
