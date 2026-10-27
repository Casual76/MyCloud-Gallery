package dev.pampa.mycloudgallery.core.library.duplicates

import dev.pampa.mycloudgallery.core.database.CopyPlace
import dev.pampa.mycloudgallery.core.database.DuplicateGroup
import dev.pampa.mycloudgallery.core.database.DuplicatePart
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.VersionLocation
import dev.pampa.mycloudgallery.core.database.VersionLocations
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaLocation
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

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
   *
   * **Il piano si rifa' sui dati di adesso** (R5 della revisione di Fase 7): per ogni gruppo si rilegge dove stanno le copie, si ripete la proposta con la
   * stessa copia da tenere scelta dall'utente e si tengono solo le copie che la proposta di adesso toglie ancora. Una copia da tenere sparita o
   * spostata (tolta dal PC, spostata nell'area comune da un'altra condivisione) fa uscire il gruppo dal piano: "mai l'ultima copia privata" vale al
   * tocco, non solo all'apertura della pagina.
   */
  suspend fun plan(proposals: Collection<GroupProposal>): CleanupPlan

  /**
   * DP-1: un giro per i doppioni **fra cartelle del NAS** (vedi [NasTwins]): mette in coda l'hash completo dei file che sembrano uguali e unisce quelli
   * provati uguali. Non e' una lettura: lo chiama la pagina all'apertura e la manutenzione notturna, mai la sola [scan].
   */
  suspend fun settle(budget: Duration): TwinReport = TwinReport.NONE

  /** DP-3: emette quando il catalogo cambia (per esempio a operazione chiusa): la pagina si rilegge. */
  fun changes(): Flow<Unit> = emptyFlow()
}

/** L'esito di una lettura dei doppioni identici. */
class IdenticalScan(
  val proposals: List<GroupProposal>,
  val failed: Boolean = false,
  /** DP-1: insiemi di file del NAS che sembrano uguali e aspettano l'hash completo: non sono ancora doppioni provati, e la pagina lo dice. */
  val verifying: Int = 0,
  /** UT-1: la Libreria non e' leggibile per intero (accesso alle sole foto selezionate, o non ancora letta): nessun gruppo si propone. */
  val restricted: Boolean = false,
) {

  /** I gruppi con qualcosa da togliere. */
  val cleanable: List<GroupProposal> get() = proposals.filter { it.status == IdenticalStatus.CLEANABLE }

  /** I gruppi non ancora provati identici (D27). */
  val toVerify: List<GroupProposal> get() = proposals.filter { it.status == IdenticalStatus.TO_VERIFY }

  /** Quante copie la pulizia di tutti i gruppi toglierebbe. */
  val copiesToRemove: Int get() = proposals.sumOf { it.remove.size }

  /** Quanti byte libererebbe la pulizia di tutti i gruppi. */
  val reclaimableBytes: Long get() = proposals.sumOf { it.reclaimableBytes }

  override fun toString(): String = "IdenticalScan(groups=${proposals.size}, cleanable=${cleanable.size}, failed=$failed, verifying=$verifying, restricted=$restricted)"

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
 * @param sharedAlbums i media attestati dagli album condivisi (DP-2): una copia dell'area comune di un media che un album condiviso mostra e' un elemento
 *   dell'album **anche se il file sta fuori da `Album condivisi/`** (un riferimento, E-SH11; una cartella adottata, E-SH16). Se non risponde, ogni copia
 *   dell'area comune vale come elemento di un album: fallire chiusi.
 * @param twins DP-1: chi porta i file uguali fra cartelle del NAS a essere un media con due copie; null = i doppioni del NAS nascono solo come prima.
 */
class CatalogIdenticalDuplicates(
  private val catalog: MediaCatalog,
  private val visible: VisibleMedia,
  private val sharedAlbumsRoot: String = IdenticalCleanup.SHARED_ALBUMS_ROOT,
  private val maxGroups: Int = MAX_GROUPS,
  private val sharedAlbums: AlbumMembership = AlbumMembership { emptySet() },
  private val twins: NasTwins? = null,
) : IdenticalDuplicates {

  override suspend fun scan(): IdenticalScan {
    try {
      // UT-1: l'istantanea della Libreria e' quella dell'accesso ai media (null con le sole foto selezionate, o prima della prima lettura): da sola
      // vince su `VisibleMedia.visible`, che legge solo il database. Nessun gruppo, e la pagina lo dice. Fallire chiusi, come i simili.
      if (visible.snapshot.value == null) return IdenticalScan(emptyList(), restricted = true)
      val verifying = verifyingCount()
      val raw = ArrayList<DuplicateGroup>()
      var after: String? = null
      while (raw.size < maxGroups) {
        val page = catalog.albums.duplicates(after)
        raw += page.groups
        after = page.next ?: break
      }
      if (raw.isEmpty()) return IdenticalScan(emptyList(), verifying = verifying)
      val shown = visible.visible(raw.map { it.media }.distinct())
      val groups = raw.filter { it.media in shown }
      if (groups.isEmpty()) return IdenticalScan(emptyList(), verifying = verifying)
      val facts = catalog.albums.facts(groups.map { it.media }.distinct())
      val members = sharedMembers(groups.map { it.media }.distinct())
      val safeCache = HashMap<MediaId, Boolean>()
      val proposals = groups.map { group ->
        val safe = group.copies.any { it.place == CopyPlace.MY_PRIVATE && it.verifiedAt != null } ||
          (group.part == DuplicatePart.PHONE && safeCache.getOrPut(group.media) { isSafe(group.media) })
        IdenticalCleanup.propose(toGroup(group, facts[group.media]?.isVideo == true, safe, members))
      }
      return IdenticalScan(proposals.sortedWith(PROPOSAL_ORDER), verifying = verifying)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      return IdenticalScan(emptyList(), failed = true)
    }
  }

  override suspend fun settle(budget: Duration): TwinReport = twins?.settle(budget) ?: TwinReport.NONE

  /** Il catalogo cambia (operazione chiusa, scansione) o la Libreria diventa leggibile (UT-1): la pagina si rilegge. */
  override fun changes(): Flow<Unit> = merge(catalog.changes.map { }, visible.snapshot.drop(1).map { })

  override suspend fun plan(proposals: Collection<GroupProposal>): CleanupPlan {
    val cleanable = proposals.filter { it.status == IdenticalStatus.CLEANABLE && it.remove.isNotEmpty() }
    if (cleanable.isEmpty() || visible.snapshot.value == null) return CleanupPlan.EMPTY
    val shown = try {
      visible.visible(cleanable.map { it.group.media }.distinct())
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      emptySet()
    }
    val members = sharedMembers(cleanable.map { it.group.media }.distinct())
    val phone = LinkedHashMap<LocationId, PhoneTrashRef>()
    val nas = LinkedHashMap<LocationId, NasTrashRef>()
    var groups = 0
    var unsafePhone = 0
    var skipped = 0
    for (proposal in cleanable) {
      val group = proposal.group
      if (group.media !in shown) continue
      val version = catalog.versionLocations(group.media).firstOrNull { it.versionId == group.version }
      // R5: la proposta si rifa' sui dati di adesso. La copia da tenere puo' essere sparita o spostata dopo la lettura della pagina: allora quello che
      // la proposta toglieva puo' essere diventato l'ultima copia privata, o l'unica verificata.
      val fresh = freshProposal(proposal, version, members)
      if (fresh == null) {
        skipped += proposal.remove.size
        continue
      }
      val known: Map<LocationId, MediaLocation> = version?.locations?.associate { it.location.id to it.location }.orEmpty()
      val mime = version?.let { runCatching { catalog.versionMetadata(it.versionId)?.mimeType }.getOrNull() }
      var touched = false
      for (copy in fresh) {
        val located = known[copy.location]
        if (located == null || !located.isPresent) {
          skipped++
          continue
        }
        when (val where = located.location) {
          is Location.Phone -> {
            val id = where.mediaStoreId
            val msVersion = where.mediaStoreVersion
            if (id == null || msVersion == null) {
              skipped++
            } else if (phone.putIfAbsent(copy.location, PhoneTrashRef(MediaRef.Phone(where.volume, id, mime), located.stamp, msVersion, copy.location)) == null) {
              touched = true
              if (!group.safe) unsafePhone++
            }
          }
          is Location.Nas -> {
            val ref = NasTrashRef(where.share, where.path.value, located.stamp.size, located.stamp.modifiedAtMillis, where.fileId, copy.area, copy.location)
            if (nas.putIfAbsent(copy.location, ref) == null) touched = true
          }
        }
      }
      if (touched) groups++
    }
    return CleanupPlan(groups, phone.values.toList(), nas.values.toList(), unsafePhone, skipped)
  }

  /**
   * Le copie da togliere di [proposal] che la proposta di **adesso** toglie ancora, o null se il gruppo non si pulisce piu'. La proposta di adesso e'
   * `IdenticalCleanup.propose` sulle copie presenti adesso, con la copia da tenere scelta dall'utente (se c'e' ancora): stesse regole "mai" di quando si e'
   * aperta la pagina. Se la proposta diceva "al sicuro" (una copia privata verificata), dopo la pulizia ne deve restare una.
   */
  private fun freshProposal(proposal: GroupProposal, version: VersionLocations?, members: SharedMembers): List<IdentCopy>? {
    version ?: return null
    val old = proposal.group
    val present = version.locations.filter { it.location.isPresent && sameSide(it.location.location, old.part) }
    if (present.size < 2) return null
    val now = IdenticalGroup(
      media = old.media,
      version = old.version,
      part = old.part,
      fullHash = version.fullHash,
      copies = present.map { toCopy(it, members.has(old.media)) },
      safe = old.safe,
      isVideo = old.isVideo,
    )
    val fresh = IdenticalCleanup.propose(now, proposal.keep?.location)
    if (fresh.status != IdenticalStatus.CLEANABLE) return null
    val wanted = proposal.remove.mapTo(HashSet()) { it.location }
    val remove = fresh.remove.filter { it.location in wanted }
    if (remove.isEmpty()) return null
    // "Al sicuro" alla lettura, nessuna copia verificata dopo la pulizia: la copia che lo garantiva e' sparita o cambiata.
    if (old.part == CopyPart.NAS && old.safe && now.copies.none { copy -> copy.isSafeCopy && remove.none { it.location == copy.location } }) return null
    return remove
  }

  private fun sameSide(location: Location, part: CopyPart): Boolean =
    if (part == CopyPart.PHONE) location is Location.Phone else location is Location.Nas

  /** Il media ha una copia privata verificata ("al sicuro")? Una lettura per media, solo per i gruppi del telefono. */
  private suspend fun isSafe(media: MediaId): Boolean = catalog.mediaSafety(media)?.status == SafetyStatus.SAFE

  /** Quanti insiemi di file del NAS aspettano l'hash completo (DP-1); 0 se non si sa. */
  private suspend fun verifyingCount(): Int = try {
    twins?.waiting() ?: 0
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    0
  }

  /** I media attestati dagli album condivisi fra [media]; se non si riesce a saperlo (DP-2) ogni media vale come attestato: fallire chiusi. */
  private suspend fun sharedMembers(media: Collection<MediaId>): SharedMembers = try {
    SharedMembers(sharedAlbums.inAlbums(media), unknown = false)
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    SharedMembers(emptySet(), unknown = true)
  }

  /** I media che un album condiviso attesta; [unknown]: non si e' riusciti a leggerlo, quindi tutti. */
  private class SharedMembers(private val media: Set<MediaId>, private val unknown: Boolean) {
    fun has(id: MediaId): Boolean = unknown || id in media
  }

  private fun toGroup(group: DuplicateGroup, isVideo: Boolean, safe: Boolean, members: SharedMembers): IdenticalGroup {
    val copies = group.copies.map { copy ->
      val area = when (copy.place) {
        CopyPlace.PHONE -> CopyArea.PHONE
        CopyPlace.MY_PRIVATE -> CopyArea.MY_PRIVATE
        CopyPlace.COMMON -> CopyArea.COMMON
        CopyPlace.OTHERS -> CopyArea.OTHERS
      }
      identCopy(copy.location, area, copy.share, copy.path, copy.size, copy.modifiedAt, copy.verifiedAt, members.has(group.media))
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

  /** Una copia come la rilegge il catalogo adesso (per rifare la proposta al piano). */
  private fun toCopy(located: VersionLocation, sharedMedia: Boolean): IdentCopy {
    val media = located.location
    return when (val where = media.location) {
      is Location.Phone ->
        identCopy(media.id, CopyArea.PHONE, null, where.relativePath + where.displayName, media.stamp.size, media.stamp.modifiedAtMillis, media.verifiedAtMillis, sharedMedia)
      is Location.Nas -> {
        val area = when (located.area) {
          NasArea.PRIVATE -> CopyArea.MY_PRIVATE
          NasArea.COMMON -> CopyArea.COMMON
          null -> CopyArea.OTHERS
        }
        identCopy(media.id, area, where.share, where.path.value, media.stamp.size, media.stamp.modifiedAtMillis, media.verifiedAtMillis, sharedMedia)
      }
    }
  }

  private fun identCopy(
    location: LocationId,
    area: CopyArea,
    share: String?,
    path: String,
    size: Long,
    modifiedAt: Long,
    verifiedAt: Long?,
    sharedMedia: Boolean,
  ): IdentCopy = IdentCopy(
    location = location,
    area = area,
    share = share,
    path = path,
    size = size,
    modifiedAt = modifiedAt,
    verifiedAt = verifiedAt,
    backupFolder = if (area == CopyArea.MY_PRIVATE) IdenticalCleanup.backupFolderOf(path) else null,
    inSharedAlbum = area == CopyArea.COMMON && (sharedMedia || IdenticalCleanup.isInSharedAlbumFolder(path, sharedAlbumsRoot)),
    guardian = area == CopyArea.MY_PRIVATE && IdenticalCleanup.isGuardianCopy(path),
  )

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
