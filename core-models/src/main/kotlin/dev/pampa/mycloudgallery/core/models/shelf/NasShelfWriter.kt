package dev.pampa.mycloudgallery.core.models.shelf

import dev.pampa.mycloudgallery.core.models.contract.ShelfDelete
import dev.pampa.mycloudgallery.core.models.contract.ShelfSource
import dev.pampa.mycloudgallery.core.models.contract.ShelfWrite
import dev.pampa.mycloudgallery.core.models.contract.ShelfWriter
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.models.ModelCopyDelete
import dev.pampa.mycloudgallery.core.nas.ops.models.ModelCopyWrite
import dev.pampa.mycloudgallery.core.nas.ops.models.NasModelCopySource
import dev.pampa.mycloudgallery.core.nas.ops.models.NasModelShelfOps
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/**
 * Lo [ShelfWriter] vero (MO-4, D18): la scrittura a flusso di core-nas ([NasModelShelfOps]) dietro la porta dello scaffale. Rifiuta ogni
 * percorso che non sia `devices/<ownerDeviceId>/models/<sha256>` (la propria cartella, un'impronta): e' il contratto della porta, e core-nas
 * non ne ha un altro. Temporaneo invisibile, lunghezza verificata e rinomina senza sostituire sono di core-nas.
 */
class NasShelfWriter(
  private val ops: NasModelShelfOps,
  private val purpose: Purpose = Purpose.BACKGROUND,
) : ShelfWriter {

  override val ownerDeviceId: String get() = ops.ownerDeviceId

  override suspend fun publishExclusive(share: String, path: SharePath, source: ShelfSource): ShelfWrite {
    val sha = ownModelSha(path) ?: return ShelfWrite.Refused(NOT_OWN_MODEL)
    val copy = object : NasModelCopySource {
      override val size: Long get() = source.size

      override fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int = source.read(offset, buf, off, len)
    }
    return when (val r = ops.publishCopy(share, sha, copy, purpose)) {
      is NasResult.Ok -> if (r.value == ModelCopyWrite.Written) ShelfWrite.Written else ShelfWrite.Exists
      is NasResult.Err -> when (r.error) {
        NasError.NasFull, NasError.QuotaExceeded -> ShelfWrite.NoSpace
        NasError.PermissionDenied -> ShelfWrite.Refused(PERMISSION)
        else -> ShelfWrite.NetworkError
      }
    }
  }

  override suspend fun deleteOwn(share: String, path: SharePath, size: Long): ShelfDelete {
    val sha = ownModelSha(path) ?: return ShelfDelete.Failed
    return when (val r = ops.deleteOwnCopy(share, sha, size, purpose)) {
      is NasResult.Err -> ShelfDelete.Failed
      is NasResult.Ok -> when (r.value) {
        ModelCopyDelete.Deleted -> ShelfDelete.Deleted
        ModelCopyDelete.Missing -> ShelfDelete.Missing
        ModelCopyDelete.SizeChanged -> ShelfDelete.Changed
        ModelCopyDelete.NotAFile, ModelCopyDelete.Locked -> ShelfDelete.Failed
      }
    }
  }

  /** L'impronta, se [path] e' esattamente la copia di un modello nella cartella di questo dispositivo; altrimenti null. */
  private fun ownModelSha(path: SharePath): String? {
    val name = path.components.lastOrNull() ?: return null
    if (!HEX64.matches(name)) return null
    return name.takeIf { path == NasLayout.modelCopy(ownerDeviceId, name) }
  }

  private companion object {
    const val NOT_OWN_MODEL = "not_own_model"
    const val PERMISSION = "permission"
    val HEX64 = Regex("^[0-9a-f]{64}$")
  }
}
