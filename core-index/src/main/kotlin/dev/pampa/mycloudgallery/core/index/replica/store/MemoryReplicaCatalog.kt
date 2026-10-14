package dev.pampa.mycloudgallery.core.index.replica.store

import dev.pampa.mycloudgallery.core.index.replica.IdentityRules
import dev.pampa.mycloudgallery.core.index.replica.LocalBinding
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.domain.MergeRules

/**
 * Un catalogo in memoria con cio' che serve a provare D6, D7, D8 ed E-I18: posizioni del NAS per (share, percorso),
 * media con i loro contenuti, alias delle unioni (`MergeRules`: sopravvive il minore), preferiti e album che seguono
 * l'alias. La scansione si simula con [scan], i media del telefono con [addPhoneMedia]. Nessun dato su disco.
 */
class MemoryReplicaCatalog : ReplicaCatalog {

  private class Place(
    var mediaId: MediaId,
    /** Impronta letta qui dalla scansione (D8). */
    var readHere: ContentKey?,
    var listing: NasStamp?,
    var fromScan: Boolean,
    var fromIndex: Boolean,
  )

  private val lock = Any()
  private val places = HashMap<Pair<String, String>, Place>()
  private val contents = HashMap<MediaId, MutableSet<ContentKey>>()
  private val aliases = HashMap<MediaId, MediaId>()
  private val favorites = HashSet<MediaId>()
  private val albums = HashMap<String, MutableSet<MediaId>>()

  /** Gli esiti di D7 visti finora, per i test. */
  val outcomes: MutableMap<IdentityOutcome, Int> = HashMap()

  /** La scansione ha elencato e rilevato il file: timbro, impronta letta qui e (se nuovo) l'id assegnato qui. */
  fun scan(share: String, pathKey: String, listing: NasStamp, readHere: ContentKey?, newId: MediaId = MediaId.random()): MediaId =
    synchronized(lock) {
      val place = places[share to pathKey]
      if (place == null) {
        places[share to pathKey] = Place(newId, readHere, listing, fromScan = true, fromIndex = false)
        if (readHere != null) contents.getOrPut(newId) { HashSet() } += readHere
        newId
      } else {
        place.listing = listing
        place.readHere = readHere ?: place.readHere
        place.fromScan = true
        resolveLocked(place.mediaId)
      }
    }

  /** Un media del telefono (nessuna posizione del NAS) con il suo contenuto. */
  fun addPhoneMedia(id: MediaId, content: ContentKey) {
    synchronized(lock) { contents.getOrPut(id) { HashSet() } += content }
  }

  fun favorite(id: MediaId) {
    synchronized(lock) { favorites += resolveLocked(id) }
  }

  fun isFavorite(id: MediaId): Boolean = synchronized(lock) { resolveLocked(id) in favorites.map { resolveLocked(it) } }

  fun addToAlbum(album: String, id: MediaId) {
    synchronized(lock) { albums.getOrPut(album) { HashSet() } += id }
  }

  fun albumMembers(album: String): Set<MediaId> = synchronized(lock) { albums[album].orEmpty().map { resolveLocked(it) }.toSet() }

  fun resolve(id: MediaId): MediaId = synchronized(lock) { resolveLocked(id) }

  /** Il media del file, se c'e' (alias risolti). */
  fun mediaAt(share: String, pathKey: String): MediaId? = synchronized(lock) { places[share to pathKey]?.let { resolveLocked(it.mediaId) } }

  /** Tutte le posizioni con il loro media (alias risolti): la vista "identita'" da confrontare fra due repliche (E-I7). */
  fun identities(): Map<Pair<String, String>, MediaId> = synchronized(lock) { places.mapValues { resolveLocked(it.value.mediaId) } }

  override suspend fun listingStamps(share: String, pathKeys: Collection<String>): Map<String, NasStamp> = synchronized(lock) {
    buildMap { pathKeys.forEach { key -> places[share to key]?.listing?.let { put(key, it) } } }
  }

  override suspend fun applyMerged(share: String, files: List<MergedFile>): List<IdentityOutcome> = synchronized(lock) {
    files.map { file -> applyOne(share, file).also { outcomes.merge(it, 1, Int::plus) } }
  }

  override suspend fun dropShare(share: String) {
    synchronized(lock) { places.entries.removeAll { it.key.first == share && !it.value.fromScan } }
  }

  private fun applyOne(share: String, file: MergedFile): IdentityOutcome {
    val key = share to file.pathKey
    val place = places[key]
    val remote = file.identity
    if (file.winner == null || remote == null) {
      if (place != null && !place.fromScan) places.remove(key) else place?.fromIndex = false
      return IdentityOutcome.REMOVED
    }
    val local = place?.let { LocalBinding(resolveLocked(it.mediaId), it.readHere) }
    val remoteResolved = resolveLocked(remote.mediaId)
    if (local != null && local.mediaId == remoteResolved) {
      place.fromIndex = true
      return IdentityOutcome.ALREADY
    }
    val outcome = IdentityRules.decide(remote.copy(mediaId = remoteResolved), local, contents[remoteResolved]?.toSet())
    when (outcome) {
      IdentityOutcome.ADOPTED -> {
        places[key] = Place(remoteResolved, null, null, fromScan = false, fromIndex = true)
        contents.getOrPut(remoteResolved) { HashSet() } += remote.content
      }
      IdentityOutcome.CONVERGED -> {
        val loser = requireNotNull(local).mediaId
        val survivor = MergeRules.survivorOf(loser, remoteResolved)
        aliases[loser] = survivor
        contents.getOrPut(survivor) { HashSet() } += contents.remove(loser).orEmpty() + remote.content
        if (favorites.remove(loser)) favorites += survivor
        place!!.mediaId = survivor
        place.fromIndex = true
      }
      IdentityOutcome.KEPT_LOCAL -> place!!.fromIndex = true
      IdentityOutcome.CONFLICT, IdentityOutcome.DEFERRED -> {
        if (place == null) {
          val separate = IdentityRules.separateId(share, file.pathKey, remote.content)
          places[key] = Place(separate, null, null, fromScan = false, fromIndex = true)
          contents.getOrPut(separate) { HashSet() } += remote.content
        } else {
          place.fromIndex = true
        }
      }
      IdentityOutcome.ALREADY, IdentityOutcome.REMOVED -> Unit
    }
    return outcome
  }

  private fun resolveLocked(id: MediaId): MediaId {
    var current = id
    var guard = 0
    while (true) {
      val next = aliases[current] ?: return current
      current = next
      check(++guard < 64) { "Catena di alias troppo lunga." }
    }
  }
}
