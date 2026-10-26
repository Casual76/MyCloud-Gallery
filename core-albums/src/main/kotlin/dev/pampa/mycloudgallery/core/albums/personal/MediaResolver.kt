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
 * costa migliaia di domande. Va chiamato dentro la transazione che poi scrive le righe: un'unione fra la lettura e la scrittura
 * lascerebbe una riga su un media che non c'e' piu'.
 */
internal class MediaResolver(private val catalog: MediaCatalog, private val database: GalleryDatabase) {

  suspend fun resolve(refs: Collection<MediaRefKey>): MediaResolution {
    if (refs.isEmpty()) return MediaResolution.NONE
    val asked = LinkedHashMap<MediaRefKey, MediaId>()
    for (ref in refs) parse(ref.subject)?.let { asked[ref] = it }
    val facts = catalog.albums.facts(asked.values.toSet())
    val byFingerprint = HashMap<PersonalFingerprint, MediaId?>()
    val out = HashMap<MediaRefKey, MediaId?>(refs.size * 2)
    for (ref in refs) {
      val byId = asked[ref]?.let { facts[it]?.media }
      out[ref] = byId ?: ref.fingerprint?.let { fingerprint ->
        if (!byFingerprint.containsKey(fingerprint)) byFingerprint[fingerprint] = uniqueWith(fingerprint)
        byFingerprint[fingerprint]
      }
    }
    return MediaResolution(out)
  }

  /** L'unico media che ha una versione con questa impronta, o null se non ce n'e' nessuno o ce n'e' piu' d'uno. */
  private suspend fun uniqueWith(fingerprint: PersonalFingerprint): MediaId? =
    database.versionDao().byQuickFingerprint(fingerprint.size, fingerprint.quickHash.hex)
      .mapNotNull { catalog.resolve(MediaId(it.mediaId)) }
      .distinct()
      .singleOrNull()

  private fun parse(subject: String): MediaId? = try {
    MediaId.parse(subject)
  } catch (e: IllegalArgumentException) {
    null
  }
}
