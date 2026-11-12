package dev.pampa.mycloudgallery.nas

import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.nas.contract.NasFolderPreviews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * I4 (fase I): le anteprime di una pagina di Cartelle NAS lette dal catalogo, **una volta per pagina** e fuori dal thread principale. Nessuna chiamata al
 * NAS, nessuna tabella nuova: le posizioni del NAS hanno gia' la chiave di posto (`location_key`, unica) e la chiave della cartella (`folder_key`,
 * indicizzata per gli elenchi di Cartelle NAS).
 *
 * - **I file**: le chiavi di posto dei file della pagina, a blocchi, verso il media della loro versione. Solo posizioni non assenti, **mai i nascosti** (ne'
 *   i gruppi con un nascosto dentro: lo stesso predicato della riga dei nascosti, [CatalogHidden.HIDDEN_VERSION_SQL]).
 * - **Le cartelle**: un intervallo sull'indice di `folder_key` (tutto cio' che sta sotto la cartella aperta, non i suoi file diretti), dal piu' recente,
 *   al piu' [FOLDER_ROWS] righe; ogni riga va alla sottocartella da cui discende, e ognuna tiene le sue prime quattro. Una cartella che ha solo foto
 *   vecchie oltre quel tetto resta con la sua piastrella.
 *
 * Il catalogo dice quali media ci sono; se si vedono lo decide poi la porta delle miniature, che chiede solo cio' che e' nell'istantanea **visibile** della
 * Libreria (niente Nascosti, niente aree che l'utente non vede).
 */
internal object CatalogNasPreviews {

  /** Le query sui file si fanno a blocchi (il limite delle variabili di SQLite). */
  private const val CHUNK = 500

  /** Quante righe al piu' legge la query delle copertine delle sottocartelle (dalle piu' recenti). */
  private const val FOLDER_ROWS = 4_000

  suspend fun read(
    database: GalleryDatabase,
    account: AccountId,
    share: String,
    folder: SharePath?,
    files: Collection<SharePath>,
    folders: Collection<SharePath>,
  ): NasFolderPreviews = withContext(Dispatchers.IO) {
    NasFolderPreviews(files = filesOf(database, account, share, files), folders = coversOf(database, account, share, folder, folders))
  }

  private fun filesOf(database: GalleryDatabase, account: AccountId, share: String, files: Collection<SharePath>): Map<SharePath, MediaId> {
    if (files.isEmpty()) return emptyMap()
    val byKey = HashMap<String, SharePath>(files.size * 2)
    for (file in files) {
      val key = runCatching { Location.Nas(account, share, NasPath.of(file.value)).key.value }.getOrNull() ?: continue
      byKey[key] = file
    }
    val out = HashMap<SharePath, MediaId>(byKey.size * 2)
    for (chunk in byKey.keys.chunked(CHUNK)) {
      val marks = chunk.joinToString(",") { "?" }
      database.openHelper.readableDatabase.query(
        "SELECT l.location_key, v.media_id FROM media_location l JOIN media_version v ON v.version_id = l.version_id " +
          "WHERE l.location_key IN ($marks) AND l.state != 'absent' AND NOT ${CatalogHidden.HIDDEN_VERSION_SQL}",
        chunk.toTypedArray<Any?>(),
      ).use { c ->
        while (c.moveToNext()) {
          val file = byKey[c.getString(0)] ?: continue
          out[file] = MediaId(c.getString(1))
        }
      }
    }
    return out
  }

  private fun coversOf(
    database: GalleryDatabase,
    account: AccountId,
    share: String,
    folder: SharePath?,
    folders: Collection<SharePath>,
  ): Map<SharePath, List<MediaId>> {
    if (folders.isEmpty()) return emptyMap()
    val parent = runCatching { folder?.let { NasPath.of(it.value) } }.getOrNull()
    val base = Location.Nas.folderKey(account, share, parent)
    // Le chiavi delle sottocartelle sono la chiave della cartella aperta, il separatore (non alla radice) e il nome: tutto cio' che sta sotto ha la
    // chiave della sua sottocartella, o quella seguita da '/'.
    val prefix = if (parent == null) base else "$base/"
    val bySub = HashMap<String, SharePath>(folders.size * 2)
    for (sub in folders) {
      val key = runCatching { Location.Nas.folderKey(account, share, NasPath.of(sub.value)) }.getOrNull() ?: continue
      if (key.startsWith(prefix)) bySub[key] = sub
    }
    if (bySub.isEmpty()) return emptyMap()
    val out = HashMap<SharePath, MutableList<MediaId>>(bySub.size * 2)
    var full = 0
    database.openHelper.readableDatabase.query(
      "SELECT l.folder_key, v.media_id FROM media_location l JOIN media_version v ON v.version_id = l.version_id " +
        "JOIN media m ON m.media_id = v.media_id " +
        "WHERE l.folder_key > ? AND l.folder_key < ? AND l.state != 'absent' AND NOT ${CatalogHidden.HIDDEN_VERSION_SQL} " +
        "ORDER BY m.sort_time DESC LIMIT $FOLDER_ROWS",
      arrayOf<Any?>(prefix, prefix + '￿'),
    ).use { c ->
      while (c.moveToNext() && full < bySub.size) {
        val first = c.getString(0).removePrefix(prefix).substringBefore('/')
        val sub = bySub[prefix + first] ?: continue
        val list = out.getOrPut(sub) { ArrayList(NasFolderPreviews.FOLDER_COVER_COUNT) }
        if (list.size >= NasFolderPreviews.FOLDER_COVER_COUNT) continue
        val media = MediaId(c.getString(1))
        if (media in list) continue
        list += media
        if (list.size == NasFolderPreviews.FOLDER_COVER_COUNT) full++
      }
    }
    return out
  }
}
