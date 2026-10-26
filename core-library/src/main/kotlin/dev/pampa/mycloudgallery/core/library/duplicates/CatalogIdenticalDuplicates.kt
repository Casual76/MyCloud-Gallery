package dev.pampa.mycloudgallery.core.library.duplicates

import dev.pampa.mycloudgallery.core.database.CopyPlace
import dev.pampa.mycloudgallery.core.database.DuplicateGroup
import dev.pampa.mycloudgallery.core.database.DuplicatePart
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaLocation
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import kotlin.coroutines.cancellation.CancellationException

// Fase 7, P8 (docs/fase7-progetto.md D25, §2.1.1): i DOPPIONI IDENTICI sul catalogo vero. Solo letture: nessuna cancellazione parte da qui.

/**
 * I doppioni identici e il piano di pulizia. Nessuna eccezione verso fuori per un errore di lettura: [IdenticalScan.failed] lo dice.
 */
interface IdenticalDuplicates {

  /** Tutti i gruppi di doppioni identici dei media **visibili** (mai un nascosto, D22), ognuno con la sua proposta (D25). */
  suspend fun scan(): IdenticalScan

  /**
   * Il piano che porta nei cestini le copie da togliere di [proposals] (solo quelle `CLEANABLE`). I media diventati nascosti dopo la lettura
   * sono fuori dal piano (fallire chiusi). Non cancella niente: lo fa il foglio di eliminazione, dopo la conferma dell'utente.
   */
  suspend fun plan(proposals: Collection<GroupProposal>): CleanupPlan
}

/** L'esito di una lettura dei doppioni identici. */
class IdenticalScan(val proposals: List<GroupProposal>, val failed: Boolean = false) {

  /** I gruppi con qualcosa da togliere. */
  val cleanable: List<GroupProposal> get() = proposals.filter { it.status == IdenticalStatus.CLEANABLE }

  /** I gruppi non ancora provati identici (D27). */
  val toVerify: List<GroupProposal> get() = proposals.filter { it.status == IdenticalStatus.TO_VERIFY }

  /** Quante copie la pulizia di tutti i gruppi toglierebbe. */
  val copiesToRemove: Int get() = proposals.sumOf { it.remove.size }

  /** Quanti byte libererebbe la pulizia di tutti i gruppi. */
  val reclaimableBytes: Long get() = proposals.sumOf { it.reclaimableBytes }

  override fun toString(): String = "IdenticalScan(groups=${proposals.size}, cleanable=${cleanable.size}, failed=$failed)"

  companion object {
    val EMPTY: IdenticalScan = IdenticalScan(emptyList())
  }
}

/** Nessun doppione (nessun catalogo). */
object NoIdenticalDuplicates : IdenticalDuplicates {
  override suspend fun scan(): IdenticalScan = IdenticalScan.EMPTY

  override suspend fun plan(proposals: Collection<GroupProposal>): CleanupPlan = CleanupPlan.EMPTY
}

/**
 * La lettura sul catalogo (`AlbumCatalog.duplicates`, P1: 3.016 gruppi su 100 mila media in 72-87 ms). Il filtro dei nascosti e' **l'ultimo
 * passo** prima di consegnare un gruppo, da [visible] (`VisibleMedia`, D22): un media nascosto, o membro di un gruppo nascosto, non compare
 * mai, e se la lettura dei nascosti fallisce non compare niente.
 *
 * @param sharedAlbumsRoot la cartella degli album condivisi dell'area comune (Q1 del 22/10), per riconoscere le copie che sono elementi di
 *   un album.
 */
class CatalogIdenticalDuplicates(
  private val catalog: MediaCatalog,
  private val visible: VisibleMedia,
  private val sharedAlbumsRoot: String = IdenticalCleanup.SHARED_ALBUMS_ROOT,
  private val maxGroups: Int = MAX_GROUPS,
) : IdenticalDuplicates {

  override suspend fun scan(): IdenticalScan {
    try {
      val raw = ArrayList<DuplicateGroup>()
      var after: String? = null
      while (raw.size < maxGroups) {
        val page = catalog.albums.duplicates(after)
        raw += page.groups
        after = page.next ?: break
      }
      if (raw.isEmpty()) return IdenticalScan.EMPTY
      val shown = visible.visible(raw.map { it.media }.distinct())
      val groups = raw.filter { it.media in shown }
      if (groups.isEmpty()) return IdenticalScan.EMPTY
      val facts = catalog.albums.facts(groups.map { it.media }.distinct())
      val safeCache = HashMap<MediaId, Boolean>()
      val proposals = groups.map { group ->
        val safe = group.copies.any { it.place == CopyPlace.MY_PRIVATE && it.verifiedAt != null } ||
          (group.part == DuplicatePart.PHONE && safeCache.getOrPut(group.media) { isSafe(group.media) })
        IdenticalCleanup.propose(toGroup(group, facts[group.media]?.isVideo == true, safe))
      }
      return IdenticalScan(proposals.sortedWith(PROPOSAL_ORDER))
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      return IdenticalScan(emptyList(), failed = true)
    }
  }

  override suspend fun plan(proposals: Collection<GroupProposal>): CleanupPlan {
    val cleanable = proposals.filter { it.status == IdenticalStatus.CLEANABLE && it.remove.isNotEmpty() }
    if (cleanable.isEmpty()) return CleanupPlan.EMPTY
    val shown = try {
      visible.visible(cleanable.map { it.group.media }.distinct())
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      emptySet()
    }
    val phone = LinkedHashMap<LocationId, PhoneTrashRef>()
    val nas = LinkedHashMap<LocationId, NasTrashRef>()
    var groups = 0
    var unsafePhone = 0
    var skipped = 0
    for (proposal in cleanable) {
      val group = proposal.group
      if (group.media !in shown) continue
      val version = catalog.versionLocations(group.media).firstOrNull { it.versionId == group.version }
      val known: Map<LocationId, MediaLocation> = version?.locations?.associate { it.location.id to it.location }.orEmpty()
      val mime = version?.let { runCatching { catalog.versionMetadata(it.versionId)?.mimeType }.getOrNull() }
      var touched = false
      for (copy in proposal.remove) {
        val located = known[copy.location]
        if (located == null || !located.isPresent) {
          skipped++
          continue
        }
        when (val where = located.location) {
          is Location.Phone -> {
            val id = where.mediaStoreId
            if (id == null) {
              skipped++
            } else if (phone.putIfAbsent(copy.location, PhoneTrashRef(MediaRef.Phone(where.volume, id, mime), copy.size)) == null) {
              touched = true
              if (!group.safe) unsafePhone++
            }
          }
          is Location.Nas -> {
            val ref = NasTrashRef(where.share, where.path.value, located.stamp.size, located.stamp.modifiedAtMillis, where.fileId, copy.area)
            if (nas.putIfAbsent(copy.location, ref) == null) touched = true
          }
        }
      }
      if (touched) groups++
    }
    return CleanupPlan(groups, phone.values.toList(), nas.values.toList(), unsafePhone, skipped)
  }

  /** Il media ha una copia privata verificata ("al sicuro")? Una lettura per media, solo per i gruppi del telefono. */
  private suspend fun isSafe(media: MediaId): Boolean = catalog.mediaSafety(media)?.status == SafetyStatus.SAFE

  private fun toGroup(group: DuplicateGroup, isVideo: Boolean, safe: Boolean): IdenticalGroup {
    val copies = group.copies.map { copy ->
      val area = when (copy.place) {
        CopyPlace.PHONE -> CopyArea.PHONE
        CopyPlace.MY_PRIVATE -> CopyArea.MY_PRIVATE
        CopyPlace.COMMON -> CopyArea.COMMON
        CopyPlace.OTHERS -> CopyArea.OTHERS
      }
      IdentCopy(
        location = copy.location,
        area = area,
        share = copy.share,
        path = copy.path,
        size = copy.size,
        modifiedAt = copy.modifiedAt,
        verifiedAt = copy.verifiedAt,
        backupFolder = if (area == CopyArea.MY_PRIVATE) IdenticalCleanup.backupFolderOf(copy.path) else null,
        inSharedAlbum = area == CopyArea.COMMON && IdenticalCleanup.isInSharedAlbumFolder(copy.path, sharedAlbumsRoot),
      )
    }
    return IdenticalGroup(
      media = group.media,
      version = group.version,
      part = if (group.part == DuplicatePart.PHONE) CopyPart.PHONE else CopyPart.NAS,
      fullHash = group.fullHash,
      copies = copies,
      safe = safe,
      isVideo = isVideo,
    )
  }

  private companion object {
    const val MAX_GROUPS = 20_000

    /** Prima i gruppi da pulire (quelli che liberano di piu'), poi i "da verificare", poi quelli senza niente da togliere. */
    val PROPOSAL_ORDER: Comparator<GroupProposal> = compareBy<GroupProposal>(
      { it.status.ordinal },
      { -it.reclaimableBytes },
      { it.group.media.value },
      { it.group.part.ordinal },
    )
  }
}
