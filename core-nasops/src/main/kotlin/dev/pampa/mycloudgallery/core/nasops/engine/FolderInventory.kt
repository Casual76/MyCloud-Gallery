package dev.pampa.mycloudgallery.core.nasops.engine

import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nasops.contract.TrashManifest
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/**
 * Cosa c'e' dentro una cartella da mettere nel cestino (R2 e R3 della revisione 4B): i file e i byte **veri**, contati scendendo in tutte
 * le sottocartelle, e l'inventario ([TrashManifest]) che la pulizia usera' dopo 30 giorni.
 *
 * - la **pianificazione** conta (il foglio di conferma dice "N file, X B" e la conferma si lega a quei numeri: i byte entrano nell'impronta
 *   del piano); una cartella che non si riesce a contare non si elimina;
 * - l'**esecuzione** riconta un attimo prima della rinomina e scrive l'inventario nel diario: se adesso ci sono piu' byte di quelli
 *   confermati, non si sposta niente.
 *
 * I punti di rianalisi (collegamenti, giunzioni) non si seguono; una cartella con un'identita' gia' vista (Samba presenta un collegamento
 * simbolico a una cartella come una cartella con l'identita' della destinazione) si conta una volta sola e non entra nell'inventario,
 * cosi' la pulizia non ci scendera' mai. Un file che il catalogo vede presente **fuori** dalla cartella e' raggiungibile anche da li': non
 * entra nell'inventario (la pulizia allora non svuota la cartella).
 */
internal class FolderInventory(private val reader: NasMediaReader, private val presence: CatalogPresence) {

  sealed interface Result {
    data class Counted(val files: Int, val bytes: Long, val manifest: TrashManifest) : Result

    /** Troppi file, un elenco troncato, un albero troppo profondo: non si conta (e non si elimina). */
    data object TooBig : Result

    data class Failed(val error: NasError) : Result
  }

  /**
   * Conta [root] (identita' [rootFileId], se nota). Con [checkCatalog] i file che il catalogo vede presenti fuori da [root] restano fuori
   * dall'inventario (all'esecuzione; alla pianificazione basta contare).
   */
  suspend fun take(share: String, root: SharePath, rootFileId: Long?, purpose: Purpose, checkCatalog: Boolean): Result {
    var files = 0
    var bytes = 0L
    val seenFolders = HashSet<Long>()
    val repeated = HashSet<Long>()
    val manifestFiles = HashMap<Long, TrashManifest.ManifestFile>()
    val queue = ArrayDeque<Pair<SharePath, Int>>()
    queue.add(root to 0)
    while (queue.isNotEmpty()) {
      val (dir, depth) = queue.removeFirst()
      if (depth > MAX_DEPTH) return Result.TooBig
      val entries = ArrayList<NasEntry>()
      when (val r = reader.list(share, dir, purpose) { entries += it }) {
        is NasResult.Err -> return Result.Failed(r.error)
        is NasResult.Ok -> if (r.value.cappedAt != null) return Result.TooBig
      }
      for (entry in entries) {
        if (entry.isReparsePoint) continue
        if (entry.isDirectory) {
          val id = entry.fileId
          if (id != null) {
            // Un'identita' gia' vista (o quella della cartella stessa): un collegamento dentro l'albero. Non si riconta, e non si scendera'.
            if (id == rootFileId || !seenFolders.add(id)) {
              repeated += id
              continue
            }
          }
          val sub = runCatching { dir.child(entry.name) }.getOrNull() ?: continue
          queue.add(sub to depth + 1)
        } else {
          files++
          bytes += entry.size
          if (files > MAX_FILES) return Result.TooBig
          entry.fileId?.let { manifestFiles[it] = TrashManifest.ManifestFile(entry.size, entry.modifiedAt) }
        }
      }
    }
    if (checkCatalog && manifestFiles.isNotEmpty()) {
      for ((id, paths) in presence.presentPaths(share, manifestFiles.keys)) {
        if (paths.any { !it.isWithin(root) }) manifestFiles.remove(id)
      }
    }
    return Result.Counted(files, bytes, TrashManifest(rootFileId, seenFolders - repeated, manifestFiles))
  }

  companion object {
    /** Oltre questi file una cartella non si conta e non si elimina in un colpo solo (si chiede di dividere). */
    const val MAX_FILES: Int = 100_000

    /** Oltre questa profondita' qualcosa non va (un ciclo di collegamenti). */
    const val MAX_DEPTH: Int = 64
  }
}
