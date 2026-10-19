package dev.pampa.mycloudgallery.core.nas.noop

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.CopyOutcome
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.NasSpaceInfo
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.backup.AppendOnlyNasAccess
import dev.pampa.mycloudgallery.core.nas.ops.backup.BackupTempWriter
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import dev.pampa.mycloudgallery.core.nas.ops.backup.ReopenOutcome
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// Fase 5A, null-object delle primitive del backup (release-safe, fuori da `testing`): nessun NAS configurato, nessuna scrittura.
// Fallisce chiuso come `NoNasFileOps`: ogni chiamata risponde "percorso non configurato" prima di aprire un socket.

private val backupNotConfigured = NasError.NotAllowedHere(DenyReason.NOT_CONFIGURED)

/** Nessuna scrittura del backup sul NAS: ogni chiamata e' negata. */
@AppendOnlyNasAccess
object NoNasBackupOps : NasBackupOps {
  override val ownerDeviceId: String = ""

  override suspend fun mkdirs(share: String, dir: SharePath, purpose: Purpose): NasResult<Unit> = NasResult.Err(backupNotConfigured)

  override suspend fun openTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<BackupTempWriter> =
    NasResult.Err(backupNotConfigured)

  override suspend fun reopenTemp(share: String, temp: SharePath, keepBytes: Long, tempId: Long, purpose: Purpose): NasResult<ReopenOutcome> =
    NasResult.Err(backupNotConfigured)

  override suspend fun copyToTemp(
    fromShare: String,
    from: SharePath,
    toShare: String,
    temp: SharePath,
    expect: NasStamp?,
    purpose: Purpose,
  ): NasResult<CopyOutcome> = NasResult.Err(backupNotConfigured)

  override suspend fun publishTemp(share: String, temp: SharePath, final: SharePath, purpose: Purpose): NasResult<PublishOutcome> =
    NasResult.Err(backupNotConfigured)

  override suspend fun deleteOwnTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<DeleteOutcome> =
    NasResult.Err(backupNotConfigured)

  override suspend fun space(share: String, purpose: Purpose): NasResult<NasSpaceInfo> = NasResult.Err(backupNotConfigured)
}
