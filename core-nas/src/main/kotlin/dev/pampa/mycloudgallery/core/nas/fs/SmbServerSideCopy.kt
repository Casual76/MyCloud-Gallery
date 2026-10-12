package dev.pampa.mycloudgallery.core.nas.fs

import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.session.FailureKind
import dev.pampa.mycloudgallery.core.nas.session.NasOpException
import dev.pampa.mycloudgallery.core.nas.session.SmbErrors
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.smb.SmbCopyChunk
import dev.pampa.mycloudgallery.core.nas.smb.SmbStatusException
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.IOException
import java.security.SecureRandom

/**
 * Copia lato server, anche **tra share diverse** (decisioni.md "Copia lato server"; research/smb-vpn.md §1.7;
 * misurata dallo spike sul NAS: 124–148 MB/s): i byte non passano dal telefono. `FSCTL_SRV_REQUEST_RESUME_KEY`
 * sulla sorgente, poi `FSCTL_SRV_COPYCHUNK_WRITE` sulla destinazione, 16 tratti da 1 MiB per richiesta (i limiti
 * di Samba usati dallo spike).
 *
 * Come `publishExclusive`: si copia in un temporaneo `tmp/<deviceId>-<casuale>` della share di destinazione, si
 * verifica la lunghezza e si rinomina **senza sovrascrivere**. Nome gia' presente → `NasError.Exists` e il
 * temporaneo si cancella; sorgente assente → `NasError.NotFound`. Nessuna operazione distruttiva sulla sorgente.
 */
class SmbServerSideCopy(
  private val manager: SmbNasSessionManager,
  private val purpose: Purpose = Purpose.BACKGROUND,
  private val random: SecureRandom = SecureRandom(),
) {

  /** Copia [from] della share [fromShare] in [to] della share [toShare]; il risultato e' il numero di byte. */
  suspend fun copy(fromShare: String, from: SharePath, toShare: String, to: SharePath): NasResult<Long> =
    manager.runOp(toShare, purpose, idempotent = false, touchesDisk = true) { ctx ->
      val source = try {
        ctx.otherShare(fromShare).openRead(from.smb())
      } catch (e: SmbStatusException) {
        if (e.status in ABSENT) throw NasOpException(NasError.NotFound)
        if (e.status == NtStatus.BAD_NETWORK_NAME) throw NasOpException(NasError.ShareMissing(fromShare))
        throw e
      }
      source.use {
        val size = source.declaredSize
        if (size < 0) throw NasOpException(NasError.Unexpected(null))
        val key = source.resumeKey()
        try {
          val (_, file) = FsOps.createTemp(ctx) { newTempName(manager.deviceId(), random) }
          var renamed = false
          try {
            ctx.startTransfer()
            var offset = 0L
            while (offset < size) {
              val chunks = ArrayList<SmbCopyChunk>(CHUNKS_PER_REQUEST)
              var next = offset
              while (chunks.size < CHUNKS_PER_REQUEST && next < size) {
                val length = minOf(CHUNK_BYTES, size - next)
                chunks += SmbCopyChunk(next, next, length)
                next += length
              }
              val written = file.copyChunks(key, chunks)
              if (written <= 0) throw NasOpException(NasError.Unexpected(null))
              offset += written
              ctx.progress(written.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
            file.flush()
            if (file.length() != size) throw NasOpException(NasError.Unexpected(null))
            try {
              FsOps.renameCreatingParents(ctx, file, to)
            } catch (e: SmbStatusException) {
              if (e.status == NtStatus.OBJECT_NAME_COLLISION) throw NasOpException(NasError.Exists)
              throw e
            }
            renamed = true
            size
          } finally {
            if (!renamed) {
              try {
                file.deleteOnClose()
              } catch (_: IOException) {
                // connessione persa: il temporaneo lo toglie NasHousekeeping
              }
            }
            file.close()
          }
        } catch (e: SmbStatusException) {
          if (e.status != NtStatus.DISK_FULL) throw e
          val space = try {
            ctx.share.space()
          } catch (_: IOException) {
            null
          }
          throw NasOpException(SmbErrors.diskFull(space), FailureKind.FINAL)
        }
      }
    }

  private companion object {
    const val CHUNK_BYTES = 1024L * 1024
    const val CHUNKS_PER_REQUEST = 16
  }
}
