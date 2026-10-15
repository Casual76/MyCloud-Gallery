package dev.pampa.mycloudgallery.core.nas.noop

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.CopyOutcome
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.DestructiveNasAccess
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nas.ops.NasSpaceInfo
import dev.pampa.mycloudgallery.core.nas.ops.NasTempWriter
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.RenameOutcome
import dev.pampa.mycloudgallery.core.nas.ops.RmdirOutcome
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// Fase 4B, null-object delle primitive di scrittura (release-safe, fuori da `testing`): nessun NAS configurato, nessuna scrittura.
// Fallisce chiuso: ogni chiamata risponde "percorso non configurato" prima di aprire un socket e non modifica niente.

private val notConfigured = NasError.NotAllowedHere(DenyReason.NOT_CONFIGURED)

/** Nessuna scrittura sul NAS: ogni chiamata e' negata. */
@DestructiveNasAccess
object NoNasFileOps : NasFileOps {
  override val ownerDeviceId: String = ""

  override suspend fun mkdirs(share: String, dir: SharePath, purpose: Purpose): NasResult<Unit> = NasResult.Err(notConfigured)

  override suspend fun rename(share: String, from: SharePath, to: SharePath, expect: NasStamp?, purpose: Purpose): NasResult<RenameOutcome> =
    NasResult.Err(notConfigured)

  override suspend fun copyToTemp(
    fromShare: String,
    from: SharePath,
    toShare: String,
    temp: SharePath,
    expect: NasStamp?,
    purpose: Purpose,
  ): NasResult<CopyOutcome> = NasResult.Err(notConfigured)

  override suspend fun openTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<NasTempWriter> = NasResult.Err(notConfigured)

  override suspend fun publishTemp(share: String, temp: SharePath, final: SharePath, purpose: Purpose): NasResult<PublishOutcome> =
    NasResult.Err(notConfigured)

  override suspend fun trashMove(
    share: String,
    path: SharePath,
    trashId: String,
    expect: NasStamp?,
    purpose: Purpose,
  ): NasResult<RenameOutcome> = NasResult.Err(notConfigured)

  override suspend fun deleteIfUnchanged(share: String, path: SharePath, size: Long, purpose: Purpose): NasResult<DeleteOutcome> =
    NasResult.Err(notConfigured)

  override suspend fun deleteOwnTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<DeleteOutcome> = NasResult.Err(notConfigured)

  override suspend fun rmdirIfEmpty(share: String, dir: SharePath, purpose: Purpose): NasResult<RmdirOutcome> = NasResult.Err(notConfigured)

  override suspend fun space(share: String, purpose: Purpose): NasResult<NasSpaceInfo> = NasResult.Err(notConfigured)
}
