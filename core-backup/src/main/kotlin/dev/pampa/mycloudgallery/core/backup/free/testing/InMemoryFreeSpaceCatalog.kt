package dev.pampa.mycloudgallery.core.backup.free.testing

import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.free.FreeSpaceCatalog
import dev.pampa.mycloudgallery.core.backup.free.FreeSpaceCursor
import dev.pampa.mycloudgallery.core.backup.free.FreeSpaceMedia
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.Media
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaLocation
import dev.pampa.mycloudgallery.core.model.Sha256

// Fase 5B, P9: il catalogo di "Libera spazio" in memoria, con il grafo vero dei media (`Media` di core-model: piu' versioni, piu' posizioni,
// verifiche), per i test di P9, P13 e P14. Le stesse regole del catalogo vero che contano qui: `markVerified` confronta lo SHA-256 letto con
// quello della versione (diverso = la posizione perde la verifica, E-B8); una copia liberata diventa assente. Sopravvive alla "morte del
// processo": e' il database. Mai nella release: tools/check.ps1 vieta i package `testing` nel dex.

class InMemoryFreeSpaceCatalog : FreeSpaceCatalog {

  private val lock = Any()
  private val byId = LinkedHashMap<MediaId, FreeSpaceMedia>()

  /** Le chiamate di [markVerified] con il loro esito (posizione, vero/falso). */
  val verifications: MutableList<Pair<LocationId, Boolean>> = java.util.Collections.synchronizedList(ArrayList())

  /** Le copie del telefono che Android ha tolto, nell'ordine in cui il catalogo le ha sapute. */
  val freed: MutableList<LocationId> = java.util.Collections.synchronizedList(ArrayList())

  /** Le pagine chieste (per provare che il piano scorre il catalogo a pagine). */
  @Volatile var pages: Int = 0
    private set

  fun put(media: FreeSpaceMedia): FreeSpaceMedia = synchronized(lock) { media.also { byId[it.id] = it } }

  fun get(id: MediaId): FreeSpaceMedia? = synchronized(lock) { byId[id] }

  fun all(): List<FreeSpaceMedia> = synchronized(lock) { byId.values.toList() }

  /** Cambia il grafo di un media (la scansione del NAS o la lettura del telefono hanno visto qualcosa). */
  fun change(id: MediaId, block: (Media) -> Media) {
    synchronized(lock) {
      val fm = byId.getValue(id)
      byId[id] = FreeSpaceMedia(block(fm.media), fm.localDay, fm.sortTime, fm.problem, fm.mime)
    }
  }

  /** Il problema del backup di un media (`BACKUP_PROBLEM`). */
  fun setProblem(id: MediaId, problem: BackupProblemCode?) {
    synchronized(lock) {
      val fm = byId.getValue(id)
      byId[id] = FreeSpaceMedia(fm.media, fm.localDay, fm.sortTime, problem, fm.mime)
    }
  }

  /** La posizione [location] com'e' adesso nel catalogo. */
  fun location(location: LocationId): MediaLocation? = synchronized(lock) {
    byId.values.asSequence().flatMap { it.media.locations }.firstOrNull { it.id == location }
  }

  override suspend fun phoneMedia(after: FreeSpaceCursor?, limit: Int): List<FreeSpaceMedia> = synchronized(lock) {
    pages++
    byId.values.asSequence()
      .filter { fm -> fm.media.locations.any { it.isPhone && it.isPresent } }
      .sortedWith(compareByDescending<FreeSpaceMedia> { it.sortTime }.thenByDescending { it.id })
      .filter { fm -> after == null || fm.sortTime < after.sortTime || (fm.sortTime == after.sortTime && fm.id < after.mediaId) }
      .take(limit)
      .toList()
  }

  override suspend fun media(ids: Collection<MediaId>): List<FreeSpaceMedia> = synchronized(lock) { ids.mapNotNull { byId[it] } }

  override suspend fun markVerified(location: LocationId, readBack: Sha256, atMillis: Long): Boolean = synchronized(lock) {
    val fm = byId.values.firstOrNull { m -> m.media.locations.any { it.id == location } } ?: return@synchronized false
    val loc = fm.media.location(location)
    val ok = fm.media.version(loc.versionId).fingerprint.fullHash == readBack
    val updated = fm.media.copy(locations = fm.media.locations.map { if (it.id == location) it.copy(verifiedAtMillis = if (ok) atMillis else null) else it })
    byId[fm.id] = FreeSpaceMedia(updated, fm.localDay, fm.sortTime, fm.problem, fm.mime)
    verifications += location to ok
    ok
  }

  override suspend fun freedFromPhone(locations: List<LocationId>) {
    synchronized(lock) {
      for (location in locations) {
        val fm = byId.values.firstOrNull { m -> m.media.locations.any { it.id == location } } ?: continue
        val updated = fm.media.copy(
          locations = fm.media.locations.map {
            if (it.id == location && it.state != LocationState.ABSENT) it.copy(state = LocationState.ABSENT, absentSinceMillis = 0) else it
          },
        )
        byId[fm.id] = FreeSpaceMedia(updated, fm.localDay, fm.sortTime, fm.problem, fm.mime)
        freed += location
      }
    }
  }
}
