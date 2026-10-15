package dev.pampa.mycloudgallery.core.nasops.testing

import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nas.ops.RmdirOutcome
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/**
 * Elimina definitivamente una voce del **proprio** cestino: un file con `deleteIfUnchanged`, una cartella cestinata (E-D10) file per
 * file dal fondo, poi le cartelle vuote con `rmdirIfEmpty`. Il percorso e' sempre quello del diario locale; qui non si legge niente
 * dal NAS per decidere cosa eliminare, solo l'elenco di cio' che sta sotto la voce che il diario gia' indica.
 */
internal class TrashTreePurger(private val fileOps: NasFileOps, private val reader: NasMediaReader) {

  sealed interface Result {
    data object Done : Result

    data object Locked : Result

    /** Un file ha cambiato dimensione dall'elenco: non si tocca, resta nel cestino (E-D4). */
    data object Changed : Result

    data class Error(val error: NasError) : Result
  }

  suspend fun purge(share: String, root: SharePath, isFolder: Boolean, size: Long, purpose: Purpose): Result {
    if (!isFolder) {
      return when (val r = fileOps.deleteIfUnchanged(share, root, size, purpose)) {
        is NasResult.Err -> Result.Error(r.error)
        is NasResult.Ok -> when (r.value) {
          DeleteOutcome.Deleted, DeleteOutcome.Missing -> Result.Done
          is DeleteOutcome.SizeChanged, DeleteOutcome.NotAFile -> Result.Changed
          DeleteOutcome.Locked -> Result.Locked
        }
      }
    }
    return purgeFolder(share, root, purpose)
  }

  private suspend fun purgeFolder(share: String, dir: SharePath, purpose: Purpose): Result {
    val children = ArrayList<NasEntry>()
    when (val listed = reader.list(share, dir, purpose) { children += it }) {
      is NasResult.Err -> return if (listed.error == NasError.NotFound) Result.Done else Result.Error(listed.error)
      is NasResult.Ok -> if (listed.value.cappedAt != null) return Result.Changed
    }
    for (child in children) {
      val path = runCatching { dir.child(child.name) }.getOrNull() ?: return Result.Changed
      val result = if (child.isDirectory) purgeFolder(share, path, purpose) else purge(share, path, isFolder = false, size = child.size, purpose = purpose)
      if (result != Result.Done) return result
    }
    return when (val r = fileOps.rmdirIfEmpty(share, dir, purpose)) {
      is NasResult.Err -> Result.Error(r.error)
      is NasResult.Ok -> when (r.value) {
        RmdirOutcome.Removed, RmdirOutcome.Missing -> Result.Done
        RmdirOutcome.NotEmpty -> Result.Changed
        RmdirOutcome.Locked -> Result.Locked
      }
    }
  }
}
