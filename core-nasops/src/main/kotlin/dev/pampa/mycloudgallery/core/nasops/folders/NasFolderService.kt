package dev.pampa.mycloudgallery.core.nasops.folders

import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.NasOpsPaths
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderEntry
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderState
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolders
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.engine.ShareAccessView
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart

/**
 * "Cartelle NAS" (§1.5, P12 su proposta di P0-B): l'elenco di una cartella del NAS per la UI, in **sola lettura**. Le azioni (crea,
 * rinomina, sposta, elimina) passano da `NasOperations` con la loro conferma; da qui escono solo [NasFolderEntry] con il timbro visto,
 * che diventano un `NasItem` solo se l'utente li sceglie.
 *
 * Cosa non si mostra mai (E-N8, E-N16): la cartella dell'app, i nomi che iniziano con un punto, i nascosti e di sistema, i collegamenti
 * (punti di rianalisi, E-N10) e i nomi non validi (E-N6). In sola lettura per l'account (E-N9) le azioni si spengono con la spiegazione
 * (`readOnly`). Senza NAS resta l'ultimo elenco buono (`Offline`).
 */
class NasFolderService internal constructor(
  private val reader: NasMediaReader,
  private val clocks: OpClocks,
  private val access: ShareAccessView = ShareAccessView.ALL_WRITABLE,
  /** Quanti file contiene una cartella, se il catalogo lo sa (null = non lo dice). */
  private val fileCount: suspend (String, SharePath) -> Int? = { _, _ -> null },
) : NasFolders {

  private class Cached(val entries: List<NasFolderEntry>, val at: Long?)

  private val lock = Any()
  private val cache = HashMap<String, Cached>()
  private val flows = HashMap<String, MutableStateFlow<NasFolderState>>()

  private fun keyOf(share: String, dir: SharePath?) = share.lowercase(Locale.ROOT) + "/" + dir?.value.orEmpty().lowercase(Locale.ROOT)

  private fun flowOf(share: String, dir: SharePath?): MutableStateFlow<NasFolderState> =
    synchronized(lock) { flows.getOrPut(keyOf(share, dir)) { MutableStateFlow(NasFolderState.Loading) } }

  override fun observe(share: String, dir: SharePath?): Flow<NasFolderState> {
    val flow = flowOf(share, dir)
    // Alla prima osservazione si legge dal NAS; dopo, [refresh] aggiorna lo stesso flusso.
    return flow.onStart { if (flow.value == NasFolderState.Loading) flow.value = load(share, dir) }
  }

  override suspend fun refresh(share: String, dir: SharePath?) {
    val flow = flowOf(share, dir)
    flow.value = load(share, dir)
  }

  private suspend fun load(share: String, dir: SharePath?): NasFolderState {
    // La cartella dell'app non si elenca mai, nemmeno se un percorso ci punta.
    if (dir != null && !NasOpsPaths.isMediaPath(dir)) return NasFolderState.Denied
    val raw = ArrayList<NasEntry>()
    val result = reader.list(share, dir, Purpose.INTERACTIVE) { page ->
      if (raw.size < MAX_SHOWN) raw += page.take(MAX_SHOWN - raw.size)
    }
    return when (result) {
      is NasResult.Ok -> {
        val entries = raw.asSequence()
          .filterNot { it.name.startsWith(".") || it.isHidden || it.isSystem || it.isReparsePoint }
          .mapNotNull { entry ->
            val path = runCatching { dir?.child(entry.name) ?: SharePath.of(entry.name) }.getOrNull() ?: return@mapNotNull null
            if (!NasOpsPaths.isMediaPath(path)) return@mapNotNull null
            NasFolderEntry(path, entry.isDirectory, entry.size, entry.modifiedAt, entry.fileId)
          }
          .toList()
          .map { if (it.isFolder) it.copy(fileCount = fileCount(share, it.path)) else it }
          .sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase(Locale.ROOT) }))
        val at = clocks.nasNowMs()
        synchronized(lock) { cache[keyOf(share, dir)] = Cached(entries, at) }
        NasFolderState.Listing(entries, readOnly = access.readOnly(share), cachedAt = null)
      }
      is NasResult.Err -> when (result.error) {
        NasError.PermissionDenied -> NasFolderState.Denied
        NasError.NotFound -> NasFolderState.Error(OpErrorCode.SOURCE_MISSING)
        is NasError.ShareMissing -> NasFolderState.Error(OpErrorCode.UNREACHABLE)
        is NasError.Unexpected -> NasFolderState.Error(OpErrorCode.UNEXPECTED)
        else -> synchronized(lock) { cache[keyOf(share, dir)] }.let { NasFolderState.Offline(it?.entries, it?.at) }
      }
    }
  }

  private companion object {
    /** Le voci mostrate al massimo per cartella (8.910 file in una cartella dell'inventario: si mostrano tutti). */
    const val MAX_SHOWN = 50_000
  }
}
