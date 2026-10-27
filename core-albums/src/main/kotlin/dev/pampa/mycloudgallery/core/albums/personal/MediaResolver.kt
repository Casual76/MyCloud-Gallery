package dev.pampa.mycloudgallery.core.albums.personal

import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint

/**
 * Risolve i riferimenti delle voci personali nel catalogo di QUESTO dispositivo (D5, come `CatalogPersonalProjection`): prima per id (un
 * id assorbito da un'unione vale il sopravvissuto), poi, se l'id qui non c'e', per impronta (dimensione e `qh1`) **solo se indica un
 * media solo**: due media con la stessa impronta rapida (L32: differiscono solo nel mezzo) non si confondono mai. Un riferimento che non
 * si risolve e' "in attesa" ([MediaResolution.of] null): la voce resta nello stato, e torna a valere quando il media arriva.
 *
 * Le letture sono a blocchi (`AlbumCatalog.facts` risolve gli alias di 500 id per volta), cosi' un album da migliaia di elementi non
 * costa migliaia di domande. R19 della revisione di sicurezza della Fase 7: anche le impronte si cercano a blocchi
 * ([FINGERPRINTS_PER_QUERY] per domanda, sull'indice `(size, quick_hash)`), non una domanda per impronta: centomila voci con impronte inventate
 * da un altro dispositivo dell'account non tengono il lucchetto di scrittura per centomila letture. Va chiamato dentro la transazione che
 * poi scrive le righe: un'unione fra la lettura e la scrittura lascerebbe una riga su un media che non c'e' piu'.
 */
internal class MediaResolver(private val catalog: MediaCatalog, private val database: GalleryDatabase) {

  suspend fun resolve(refs: Collection<MediaRefKey>): MediaResolution {
    if (refs.isEmpty()) return MediaResolution.NONE
    val asked = LinkedHashMap<MediaRefKey, MediaId>()
    for (ref in refs) parse(ref.subject)?.let { asked[ref] = it }
    val facts = catalog.albums.facts(asked.values.toSet())
    val missing = refs.filter { ref -> asked[ref]?.let { facts[it]?.media } == null }.mapNotNullTo(LinkedHashSet()) { it.fingerprint }
    val byFingerprint = uniqueWith(missing)
    val out = HashMap<MediaRefKey, MediaId?>(refs.size * 2)
    for (ref in refs) out[ref] = asked[ref]?.let { facts[it]?.media } ?: ref.fingerprint?.let { byFingerprint[it] }
    return MediaResolution(out)
  }

  /** Per ogni impronta l'unico media che ha una versione con quell'impronta (null se nessuno o piu' d'uno), a blocchi di domande. */
  private suspend fun uniqueWith(fingerprints: Set<PersonalFingerprint>): Map<PersonalFingerprint, MediaId?> {
    if (fingerprints.isEmpty()) return emptyMap()
    val found = HashMap<PersonalFingerprint, MutableSet<String>>()
    for (chunk in fingerprints.chunked(FINGERPRINTS_PER_QUERY)) {
      val where = chunk.joinToString(" OR ") { "(size = ? AND quick_hash = ?)" }
      val args = chunk.flatMap { listOf<Any?>(it.size, it.quickHash.hex) }.toTypedArray()
      database.openHelper.readableDatabase.query("SELECT media_id, size, quick_hash FROM media_version WHERE $where", args).use { cursor ->
        while (cursor.moveToNext()) {
          val key = chunk.firstOrNull { it.size == cursor.getLong(1) && it.quickHash.hex == cursor.getString(2) } ?: continue
          found.getOrPut(key) { HashSet() } += cursor.getString(0)
        }
      }
    }
    // Gli id trovati come il catalogo li ha adesso (un id assorbito vale il sopravvissuto), a blocchi.
    val real = catalog.albums.facts(found.values.flatten().mapNotNullTo(HashSet()) { parse(it) })
    return fingerprints.associateWith { fp ->
      found[fp]?.mapNotNull { id -> parse(id)?.let { real[it]?.media } }?.distinct()?.singleOrNull()
    }
  }

  private fun parse(subject: String): MediaId? = try {
    MediaId.parse(subject)
  } catch (e: IllegalArgumentException) {
    null
  }

  private companion object {
    /** Due variabili per impronta, sotto le 999 di SQLite su API 29. */
    const val FINGERPRINTS_PER_QUERY = 400
  }
}
