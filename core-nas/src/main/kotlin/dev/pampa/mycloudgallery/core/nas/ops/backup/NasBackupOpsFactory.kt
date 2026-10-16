package dev.pampa.mycloudgallery.core.nas.ops.backup

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.noop.NoNasBackupOps
import dev.pampa.mycloudgallery.core.nas.ops.BackupTempReopen
import dev.pampa.mycloudgallery.core.nas.ops.CopyOutcome
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nas.ops.NasOpsPaths
import dev.pampa.mycloudgallery.core.nas.ops.NasSpaceInfo
import dev.pampa.mycloudgallery.core.nas.ops.NasTempWriter
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.SmbNasFileOps
import dev.pampa.mycloudgallery.core.nas.ops.TempFinish
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/**
 * L'unico posto in produzione dove una sessione del NAS diventa primitive del backup (D2): la chiama **solo** core-backup (il
 * cablaggio del backup, P2/P7), mai `:app` direttamente: `:app` passa la sessione a core-backup e riceve i servizi, senza vedere
 * `NasBackupOps` (il test di architettura vieta il nome fuori da core-nas e core-backup).
 */
@AppendOnlyNasAccess
object NasBackupOpsFactory {

  /** Le primitive del backup per [ownerDeviceId] sopra [session]; con una sessione che non e' SMB (un fake), nessuna scrittura. */
  fun create(session: NasSessionManager, ownerDeviceId: String): NasBackupOps = when (session) {
    is SmbNasSessionManager -> SmbNasBackupOps(SmbNasFileOps(session, ownerDeviceId))
    else -> NoNasBackupOps
  }

  /**
   * Come [create], con **tutti** i percorsi sotto [root] della share: solo per l'area di prova dei test d'integrazione e del banco
   * delle interruzioni del backup (`mcg-it/<giro>/...`, §5.3), dietro la guardia dell'area di prova.
   */
  fun createForTestArea(session: NasSessionManager, ownerDeviceId: String, root: SharePath): NasBackupOps = when (session) {
    is SmbNasSessionManager -> SmbNasBackupOps(SmbNasFileOps(session, ownerDeviceId, root))
    else -> NoNasBackupOps
  }
}

/**
 * La vista in sola aggiunta sopra le primitive SMB della 4B (scheletro di P0, completata da P2): ogni chiamata controlla che il
 * temporaneo sia **del backup** di questo dispositivo e poi passa a `SmbNasFileOps`, che resta l'unica a parlare con il server.
 * Della 4B si usano solo le primitive che aggiungono; quelle che tolgono o spostano (`rename`, `trashMove`, `deleteIfUnchanged`,
 * `rmdirIfEmpty`) qui non sono raggiungibili.
 *
 * [reopenTemp] (D5, P2): `SmbNasFileOps.reopenBackupTemp` apre il proprio temporaneo **senza crearlo** (`FILE_OPEN`, in esclusiva, senza
 * seguire un collegamento), legge la lunghezza e lo accorcia a `keepBytes` con il `SET_INFO` di fine file **sullo stesso handle**; lo
 * scrittore riparte da li'. Sopra primitive che non sono quelle SMB (il fake della 4B nei test) la ripresa non c'e': risponde un **errore
 * esplicito** (`STATUS_NOT_IMPLEMENTED`), mai un successo finto, e il backup ricomincia il file da zero (nessun danno, solo traffico).
 *
 * Il punto di ripresa ([BackupTempWriter.checkpoint]) usa `finish` dello scrittore della 4B: `FLUSH`, lunghezza sullo stesso handle e
 * timbro letto per nome (solo `FILE_READ_ATTRIBUTES`, che non entra in conflitto con l'handle esclusivo). `finish` **non chiude**
 * l'handle: lo scrittore resta usabile dopo ogni punto di ripresa (provato sul simulatore SMB, `SmbNasBackupOpsTest`).
 */
@AppendOnlyNasAccess
internal class SmbNasBackupOps(private val ops: NasFileOps) : NasBackupOps {

  init {
    require(TrustIds.isValid(ops.ownerDeviceId)) { "Il dispositivo proprietario e' un id valido." }
  }

  override val ownerDeviceId: String get() = ops.ownerDeviceId

  private fun requireBackupTemp(temp: SharePath) {
    require(NasBackupPaths.isBackupTempOf(temp, ownerDeviceId)) { "Un temporaneo del backup di questo dispositivo." }
  }

  override suspend fun mkdirs(share: String, dir: SharePath, purpose: Purpose): NasResult<Unit> {
    require(NasOpsPaths.isMediaPath(dir)) { "mkdirs vale solo fuori da .mycloudgallery/." }
    return ops.mkdirs(share, dir, purpose)
  }

  override suspend fun openTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<BackupTempWriter> {
    requireBackupTemp(temp)
    return when (val opened = ops.openTemp(share, temp, purpose)) {
      is NasResult.Err -> opened
      is NasResult.Ok -> NasResult.Ok(Writer(opened.value, start = 0L))
    }
  }

  override suspend fun reopenTemp(share: String, temp: SharePath, keepBytes: Long, purpose: Purpose): NasResult<ReopenOutcome> {
    requireBackupTemp(temp)
    require(keepBytes >= 0) { "Punto di ripresa negativo." }
    val smb = ops as? SmbNasFileOps ?: return NasResult.Err(NasError.Unexpected(STATUS_NOT_IMPLEMENTED))
    return when (val reopened = smb.reopenBackupTemp(share, temp, keepBytes, purpose)) {
      is NasResult.Err -> reopened
      is NasResult.Ok -> NasResult.Ok(
        when (val r = reopened.value) {
          is BackupTempReopen.Reopened -> ReopenOutcome.Reopened(Writer(r.writer, start = keepBytes))
          BackupTempReopen.Missing -> ReopenOutcome.Missing
          is BackupTempReopen.TooShort -> ReopenOutcome.TooShort(r.actual)
          BackupTempReopen.Locked -> ReopenOutcome.Locked
        },
      )
    }
  }

  override suspend fun copyToTemp(
    fromShare: String,
    from: SharePath,
    toShare: String,
    temp: SharePath,
    expect: NasStamp?,
    purpose: Purpose,
  ): NasResult<CopyOutcome> {
    requireBackupTemp(temp)
    return ops.copyToTemp(fromShare, from, toShare, temp, expect, purpose)
  }

  override suspend fun publishTemp(share: String, temp: SharePath, final: SharePath, purpose: Purpose): NasResult<PublishOutcome> {
    requireBackupTemp(temp)
    require(NasOpsPaths.isMediaPath(final)) { "La destinazione e' un percorso di media." }
    return ops.publishTemp(share, temp, final, purpose)
  }

  override suspend fun deleteOwnTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<DeleteOutcome> {
    requireBackupTemp(temp)
    return ops.deleteOwnTemp(share, temp, purpose)
  }

  override suspend fun space(share: String, purpose: Purpose): NasResult<NasSpaceInfo> = ops.space(share, purpose)

  /**
   * Lo scrittore sopra quello della 4B. Il punto di ripresa usa `finish` della 4B (svuota e legge la lunghezza sullo stesso handle,
   * senza chiudere): una lunghezza diversa da quella scritta si restituisce com'e', e il chiamante non la salva come punto di ripresa.
   */
  private class Writer(private val inner: NasTempWriter, start: Long) : BackupTempWriter {
    @Volatile override var length: Long = start
      private set

    override val temp: SharePath get() = inner.temp

    override suspend fun write(offset: Long, buf: ByteArray, off: Int, len: Int): NasResult<Unit> {
      require(offset in 0..length) { "Scrittura oltre la fine del temporaneo." }
      val result = inner.write(offset, buf, off, len)
      if (result is NasResult.Ok) length = maxOf(length, offset + len)
      return result
    }

    override suspend fun checkpoint(): NasResult<Long> = when (val r = inner.finish(length)) {
      is NasResult.Err -> r
      is NasResult.Ok -> NasResult.Ok(
        when (val f = r.value) {
          is TempFinish.Complete -> f.stamp.size
          is TempFinish.SizeMismatch -> f.actual
        },
      )
    }

    override suspend fun finish(expectedSize: Long): NasResult<TempFinish> = inner.finish(expectedSize)

    override fun close() = inner.close()
  }

  private companion object {
    /** `STATUS_NOT_IMPLEMENTED`: sopra primitive che non sono quelle SMB la ripresa non c'e'. */
    const val STATUS_NOT_IMPLEMENTED: Long = 0xC0000002L
  }
}
