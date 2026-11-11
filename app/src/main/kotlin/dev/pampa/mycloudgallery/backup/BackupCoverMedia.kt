package dev.pampa.mycloudgallery.backup

import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.metadata.contract.SourceClassifier
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.feature.backup.contract.SourceNewestMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * I3 (fase I): le ultime foto di ogni sorgente del backup, per le copertine della pagina Backup e delle sue impostazioni («la nostra mostra
 * solo il testo»). Una lettura del catalogo **gia' scritto** (le righe del MediaStore che il catalogo ha letto, come [CatalogPhoneFolders]), dal
 * piu' recente, che si ferma quando ogni sorgente ha le sue; mai una seconda lettura del MediaStore.
 *
 * Solo lettura e solo identita': gli id possono essere di media nascosti o eliminati. Chi li mostra (`BackupCovers` di feature-backup) li passa
 * dalla Libreria visibile, che non contiene mai un nascosto, e chiede le miniature alla stessa cache della griglia. Nessuna decisione del backup
 * passa di qui. Le cartelle della fotocamera e degli screenshot si riconoscono dal percorso (O-2), come nelle impostazioni.
 */
class CatalogSourceNewest(private val database: GalleryDatabase) : SourceNewestMedia {

  override suspend fun newest(sources: List<BackupSource>, perSource: Int): Map<String, List<MediaId>> = withContext(Dispatchers.IO) {
    if (sources.isEmpty() || perSource <= 0) return@withContext emptyMap()
    val wanted = sources.mapTo(HashSet()) { it.id }
    val out = HashMap<String, MutableList<MediaId>>()
    var full = 0
    database.openHelper.readableDatabase.query(
      "SELECT m.media_id, l.volume, l.relative_path FROM media m JOIN media_location l ON l.version_id = m.current_version_id " +
        "WHERE l.location_type = 'phone' AND l.state = 'present' AND l.volume IS NOT NULL AND l.relative_path IS NOT NULL " +
        "ORDER BY m.sort_time DESC, m.media_id DESC LIMIT ?",
      arrayOf<Any?>(MAX_ROWS),
    ).use { c ->
      while (full < wanted.size && c.moveToNext()) {
        val id = sourceIdOf(c.getString(1), c.getString(2)) ?: continue
        if (id !in wanted) continue
        val list = out.getOrPut(id) { ArrayList(perSource) }
        if (list.size >= perSource) continue
        val media = runCatching { MediaId(c.getString(0)) }.getOrNull() ?: continue
        if (media in list) continue
        list += media
        if (list.size == perSource) full++
      }
    }
    out
  }

  private fun sourceIdOf(volume: String, path: String): String? {
    if (volume.isBlank() || path.isBlank()) return null
    return when (SourceClassifier.folderOwner(path)) {
      MediaSource.CAMERA -> BackupSource.Camera.id
      MediaSource.SCREENSHOT -> BackupSource.Screenshots.id
      else -> BackupSource.Folder(volume, path).id
    }
  }

  private companion object {
    /** Al piu' tante righe, dal piu' recente: una cartella senza foto recenti resta con la piastrella della famiglia. */
    const val MAX_ROWS = 20_000
  }
}
