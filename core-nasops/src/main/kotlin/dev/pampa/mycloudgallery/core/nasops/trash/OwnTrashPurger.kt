package dev.pampa.mycloudgallery.core.nasops.trash

import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nas.ops.NasOpsPaths
import dev.pampa.mycloudgallery.core.nas.ops.RmdirOutcome
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/**
 * L'eliminazione definitiva di UNA voce del **proprio** cestino (scadenza dei 30 giorni o "Elimina subito", Q8): un file con
 * `deleteIfUnchanged` (un solo handle, dimensione controllata sullo stesso), una cartella cestinata (E-D10) dal fondo, file per file
 * con la dimensione appena letta, poi le cartelle vuote.
 *
 * Il percorso [root] lo deriva chi chiama dal **diario locale** (`trash/<io>/files/<id>`), mai da un dato remoto: qui si controlla di
 * nuovo che sia nel proprio cestino. Dentro una cartella cestinata si leggono dal NAS solo i nomi dei figli, che restano sotto [root]
 * (`SharePath.child`); un collegamento (punto di rianalisi) non si segue e non si tocca: la cartella resta, la voce anche.
 */
internal class OwnTrashPurger(private val ownerDeviceId: String, private val fileOps: NasFileOps, private val reader: NasMediaReader) {

  sealed interface Result {
    data object Done : Result

    data object Locked : Result

    /** Qualcosa non e' come il diario dice (dimensione diversa, un collegamento, un elenco troncato): non si tocca, resta nel cestino. */
    data object Changed : Result

    data class Error(val error: NasError) : Result
  }

  suspend fun purge(share: String, root: SharePath, isFolder: Boolean, size: Long, purpose: Purpose): Result {
    require(NasOpsPaths.isTrashItem(root) && NasOpsPaths.isOwnTrashTree(root, ownerDeviceId)) { "Si elimina solo dal proprio cestino." }
    return if (isFolder) folder(share, root, purpose, depth = 0) else file(share, root, size, purpose)
  }

  private suspend fun file(share: String, path: SharePath, size: Long, purpose: Purpose): Result =
    when (val r = fileOps.deleteIfUnchanged(share, path, size, purpose)) {
      is NasResult.Err -> Result.Error(r.error)
      is NasResult.Ok -> when (r.value) {
        DeleteOutcome.Deleted, DeleteOutcome.Missing -> Result.Done
        is DeleteOutcome.SizeChanged, DeleteOutcome.NotAFile -> Result.Changed
        DeleteOutcome.Locked -> Result.Locked
      }
    }

  private suspend fun folder(share: String, dir: SharePath, purpose: Purpose, depth: Int): Result {
    if (depth > MAX_DEPTH) return Result.Changed
    val children = ArrayList<NasEntry>()
    when (val listed = reader.list(share, dir, purpose) { children += it }) {
      // La cartella non c'e' (piu'): niente da eliminare, come un file gia' sparito.
      is NasResult.Err -> return if (listed.error == NasError.NotFound) Result.Done else Result.Error(listed.error)
      is NasResult.Ok -> if (listed.value.cappedAt != null) return Result.Changed
    }
    for (child in children) {
      if (child.isReparsePoint) return Result.Changed
      val path = runCatching { dir.child(child.name) }.getOrNull() ?: return Result.Changed
      val result = if (child.isDirectory) folder(share, path, purpose, depth + 1) else file(share, path, child.size, purpose)
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

  private companion object {
    /** Le cartelle cestinate sono cartelle dell'utente: oltre questa profondita' qualcosa non va (un ciclo), ci si ferma. */
    const val MAX_DEPTH = 64
  }
}
