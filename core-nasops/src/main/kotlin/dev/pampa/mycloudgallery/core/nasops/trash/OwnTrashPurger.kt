package dev.pampa.mycloudgallery.core.nasops.trash

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nas.ops.NasOpsPaths
import dev.pampa.mycloudgallery.core.nas.ops.RmdirOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.LocalTrashRecord
import dev.pampa.mycloudgallery.core.nasops.contract.OpsStore
import dev.pampa.mycloudgallery.core.nasops.contract.TrashManifest
import dev.pampa.mycloudgallery.core.nasops.engine.CatalogPresence
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/**
 * L'eliminazione definitiva di UNA voce del **proprio** cestino (scadenza dei 30 giorni o "Elimina subito", Q8).
 *
 * Il percorso lo deriva chi chiama dal **diario locale** (`trash/<io>/files/<id>`), mai da un dato remoto: qui si controlla di nuovo che
 * sia nel proprio cestino. Poi (R3 della revisione 4B):
 *
 *  - **un file**: `deleteIfUnchanged` con un solo handle e il timbro **del diario** (dimensione, data e `fileId` dell'elemento che l'utente
 *    ha eliminato), controllato sullo stesso handle; un collegamento non si cancella;
 *  - **una cartella** (E-D10): solo con il suo **inventario** ([TrashManifest]), scritto prima della rinomina. Prima si verifica l'albero
 *    intero, senza toccare niente: si scende solo nelle cartelle dell'inventario (stessa identita') e il cui genitore sul disco e' davvero
 *    la cartella da cui si arriva (la voce `..`: un collegamento simbolico che Samba presenta come cartella porta altrove); ogni file deve
 *    essere dell'inventario, con lo stesso timbro; nessun punto di rianalisi. Poi il catalogo: se un file dell'albero e' ancora presente
 *    fuori dalla cartella cestinata, l'albero e' raggiungibile anche da fuori (un collegamento) e non si tocca. Solo allora si cancellano i
 *    file verificati, uno per handle con il loro timbro, e le cartelle rimaste vuote. Qualunque dubbio: [Result.Changed], niente
 *    cancellato, la voce resta nel cestino.
 *
 * Limite (docs/sicurezza.md, L35): un collegamento a una cartella fuori dall'albero, gia' presente quando la cartella e' stata cestinata,
 * che il server presenta come cartella vera **e** di cui non dice il genitore sul disco **e** che non contiene media che il catalogo vede
 * fuori dal cestino, resta indistinguibile da una cartella vera.
 */
internal class OwnTrashPurger(
  private val ownerDeviceId: String,
  private val fileOps: NasFileOps,
  private val reader: NasMediaReader,
  private val store: OpsStore,
  private val presence: CatalogPresence,
) {

  sealed interface Result {
    data object Done : Result

    data object Locked : Result

    /** Qualcosa non e' come il diario dice (timbro diverso, un collegamento, un file in piu', un elenco troncato): non si tocca, resta. */
    data object Changed : Result

    data class Error(val error: NasError) : Result
  }

  /** Elimina la voce [entry] del diario locale: il suo percorso nel cestino ([LocalTrashRecord.trashedPath]) e niente altro. */
  suspend fun purge(entry: LocalTrashRecord, purpose: Purpose): Result {
    val root = entry.trashedPath
    require(NasOpsPaths.isTrashItem(root) && NasOpsPaths.isOwnTrashTree(root, ownerDeviceId)) { "Si elimina solo dal proprio cestino." }
    return if (entry.isFolder) folder(entry, purpose) else file(entry.shareName, root, fileStamp(entry), purpose)
  }

  /** Il timbro del file della voce: quello dell'elemento dell'operazione che l'ha cestinato (diario), con la dimensione della voce. */
  private suspend fun fileStamp(entry: LocalTrashRecord): NasStamp {
    val item = entry.opId?.let { op -> store.items(op).firstOrNull { it.trashId == entry.trashId } }
    val stamp = item?.stamp
    return if (stamp != null && stamp.size == entry.size) stamp else NasStamp(entry.size, UNKNOWN_TIME, null)
  }

  private suspend fun file(share: String, path: SharePath, stamp: NasStamp, purpose: Purpose): Result {
    val expect = stamp.takeIf { it.modifiedAtMillis != UNKNOWN_TIME }
    return when (val r = fileOps.deleteIfUnchanged(share, path, stamp.size, purpose, expect)) {
      is NasResult.Err -> Result.Error(r.error)
      is NasResult.Ok -> when (r.value) {
        DeleteOutcome.Deleted, DeleteOutcome.Missing -> Result.Done
        is DeleteOutcome.SizeChanged, DeleteOutcome.NotAFile -> Result.Changed
        DeleteOutcome.Locked -> Result.Locked
      }
    }
  }

  /** Cio' che la verifica ha trovato: i file da cancellare (con il timbro) e le cartelle da togliere, dal fondo. */
  private class Verified(val files: MutableList<Pair<SharePath, NasStamp>> = ArrayList(), val dirs: MutableList<SharePath> = ArrayList())

  private suspend fun folder(entry: LocalTrashRecord, purpose: Purpose): Result {
    val share = entry.shareName
    val root = entry.trashedPath
    // Senza inventario (voce di prima, file perso) una cartella non si svuota mai: nessuna base per dire cosa e' dell'utente.
    val manifest = store.trashManifest(entry.trashId) ?: return when (val r = reader.stat(share, root, purpose)) {
      is NasResult.Ok -> if (r.value == null) Result.Done else Result.Changed
      is NasResult.Err -> if (r.error == NasError.NotFound) Result.Done else Result.Error(r.error)
    }
    // La cartella stessa: c'e', e' una cartella, non un collegamento, con l'identita' dell'inventario.
    val self = when (val r = reader.stat(share, root, purpose)) {
      is NasResult.Ok -> r.value ?: return Result.Done
      is NasResult.Err -> return if (r.error == NasError.NotFound) Result.Done else Result.Error(r.error)
    }
    if (!self.isDirectory || self.isReparsePoint) return Result.Changed
    if (manifest.rootFileId != null && self.fileId != null && manifest.rootFileId != self.fileId) return Result.Changed
    // Il genitore sul disco della cartella cestinata e' la cartella `files` del proprio cestino (non quello di una destinazione).
    val filesDir = checkNotNull(root.parent())
    val filesDirId = when (val r = reader.stat(share, filesDir, purpose)) {
      is NasResult.Ok -> r.value?.fileId
      is NasResult.Err -> return Result.Error(r.error)
    }
    parentMismatch(share, root, filesDirId, purpose)?.let { return it }

    // 1. Verifica dell'albero intero: niente si cancella finche' tutto non torna.
    val verified = Verified()
    verify(share, root, self.fileId, manifest, purpose, depth = 0, verified)?.let { return it }

    // 2. Il catalogo: un file dell'albero ancora visibile fuori dalla cartella cestinata vuol dire un collegamento (o un file che l'utente
    //    vede ancora): non si tocca niente. I percorsi d'origine della cartella non contano (il catalogo puo' non aver ancora rivisto la share).
    val ids = verified.files.mapNotNull { it.second.fileId }
    if (ids.isNotEmpty()) {
      val visible = presence.presentPaths(share, ids)
      val original = entry.originalPath
      if (visible.values.any { paths -> paths.any { !it.isWithin(original) } }) return Result.Changed
    }

    // 3. Solo adesso: i file verificati, uno per handle con il timbro del diario; poi le cartelle rimaste vuote, dal fondo.
    for ((path, stamp) in verified.files) {
      val result = file(share, path, stamp, purpose)
      if (result != Result.Done) return result
    }
    for (dir in verified.dirs) {
      when (val r = fileOps.rmdirIfEmpty(share, dir, purpose)) {
        is NasResult.Err -> return Result.Error(r.error)
        is NasResult.Ok -> when (r.value) {
          RmdirOutcome.Removed, RmdirOutcome.Missing -> Unit
          RmdirOutcome.NotEmpty -> return Result.Changed
          RmdirOutcome.Locked -> return Result.Locked
        }
      }
    }
    return Result.Done
  }

  /** Il genitore sul disco di [dir] (voce `..`) non e' [expected]: un collegamento. Null se torna (o se il server non lo dice). */
  private suspend fun parentMismatch(share: String, dir: SharePath, expected: Long?, purpose: Purpose): Result? =
    when (val r = fileOps.parentIdentity(share, dir, purpose)) {
      is NasResult.Err -> Result.Error(r.error)
      is NasResult.Ok -> {
        val parent = r.value
        if (parent != null && expected != null && parent != expected) Result.Changed else null
      }
    }

  /** Verifica [dir] (identita' [dirId]) e quello che contiene; null se e' tutto dell'inventario. */
  private suspend fun verify(
    share: String,
    dir: SharePath,
    dirId: Long?,
    manifest: TrashManifest,
    purpose: Purpose,
    depth: Int,
    out: Verified,
  ): Result? {
    if (depth > MAX_DEPTH) return Result.Changed
    val children = ArrayList<NasEntry>()
    when (val listed = reader.list(share, dir, purpose) { children += it }) {
      is NasResult.Err -> return Result.Error(listed.error)
      is NasResult.Ok -> if (listed.value.cappedAt != null) return Result.Changed
    }
    for (child in children) {
      if (child.isReparsePoint) return Result.Changed
      val path = runCatching { dir.child(child.name) }.getOrNull() ?: return Result.Changed
      val id = child.fileId ?: return Result.Changed
      if (child.isDirectory) {
        // Solo le cartelle spostate con lei, e solo se sul disco stanno davvero qui dentro.
        if (id !in manifest.folders || id == dirId) return Result.Changed
        parentMismatch(share, path, dirId, purpose)?.let { return it }
        verify(share, path, id, manifest, purpose, depth + 1, out)?.let { return it }
      } else {
        val known = manifest.files[id] ?: return Result.Changed
        if (known.size != child.size || known.modifiedAt != child.modifiedAt) return Result.Changed
        out.files += path to NasStamp(known.size, known.modifiedAt, id)
      }
    }
    out.dirs += dir
    return null
  }

  private companion object {
    /** Le cartelle cestinate sono cartelle dell'utente: oltre questa profondita' qualcosa non va (un ciclo), ci si ferma. */
    const val MAX_DEPTH = 64

    /** Segnaposto: data non nota (voce senza elemento nel diario), si controlla solo la dimensione. */
    const val UNKNOWN_TIME = Long.MIN_VALUE
  }
}
