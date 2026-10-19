package dev.pampa.mycloudgallery.core.backup.versions

import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.HashRecord
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.VerifiedCopy
import dev.pampa.mycloudgallery.core.backup.testing.BackupDeath
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataChange
import dev.pampa.mycloudgallery.core.model.CorrectionField
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale

/**
 * Il catalogo locale delle versioni in memoria (P8, per i test e il banco di P13): le stesse regole di `MediaCatalog` che contano per le
 * versioni. Una copia "verificata" vuole la dimensione e l'hash completo della versione (altrimenti rifiutata, come `attachCopies`); lo
 * spostamento di una posizione (`relocate`) tiene la verifica; una correzione crea una versione `CORRECTED` figlia dell'attuale, senza
 * posti; "Ripristina originale" rende l'originale attuale. Gli eventi "da fuori" (la lettura del telefono, la scansione del NAS) sono i
 * metodi di preparazione: [phoneChanged], [phoneReverted], [nasChanged], [nasVanished].
 */
class InMemoryVersionCatalog(private val areas: Map<String, NasArea>) : VersionCatalog {

  private class Ver(
    val id: VersionId,
    val kind: VersionKind,
    val parent: VersionId?,
    val size: Long,
    var fullHash: Sha256?,
    val quickHash: QuickHash?,
  )

  private sealed interface Place {
    class Nas(val share: String, val path: SharePath) : Place

    class Phone(val ref: PhoneRef) : Place
  }

  private class Loc(
    val id: LocationId,
    var versionId: VersionId,
    var place: Place,
    var nasStamp: NasStamp?,
    var phoneStamp: FileStamp?,
    var verifiedAt: Long?,
    var present: Boolean = true,
  )

  private class M(
    val id: MediaId,
    val mine: Boolean,
    val kind: MediaKind,
    val mime: String?,
    var current: VersionId,
    val versions: MutableList<Ver> = ArrayList(),
    val locations: MutableList<Loc> = ArrayList(),
  )

  private val lock = Any()
  private val media = LinkedHashMap<MediaId, M>()
  private var nextLocation = 1L

  /** I problemi segnati, per media (l'ultimo; null = tolto). */
  val problems: MutableMap<MediaId, BackupProblemCode?> = HashMap()

  /** Le correzioni rimaste solo nell'app (Q6 (a)). */
  val inAppCorrections: MutableList<Triple<MediaId, VersionId, MetadataChange>> = ArrayList()

  /** Le correzioni scritte come versione nuova: (media, campi). */
  val corrections: MutableList<Pair<MediaId, Set<CorrectionField>>> = ArrayList()

  /** Le chiamate a "Ripristina originale" del catalogo. */
  var restores: Int = 0
    private set

  // --- Preparazione (il catalogo come lo lascerebbero la lettura del telefono, la scansione e il backup) -----------------------------

  fun addMedia(
    kind: MediaKind = MediaKind.PHOTO,
    mime: String? = "image/jpeg",
    size: Long,
    fullHash: Sha256?,
    quickHash: QuickHash? = null,
    mine: Boolean = true,
  ): Pair<MediaId, VersionId> = synchronized(lock) {
    val id = MediaId.random()
    val v = VersionId.random()
    media[id] = M(id, mine, kind, mime, v).also { it.versions += Ver(v, VersionKind.ORIGINAL, null, size, fullHash, quickHash) }
    id to v
  }

  fun putNasCopy(mediaId: MediaId, versionId: VersionId, share: String, path: SharePath, stamp: NasStamp, verifiedAt: Long?): LocationId =
    synchronized(lock) {
      val m = media.getValue(mediaId)
      val loc = Loc(LocationId(nextLocation++), versionId, Place.Nas(share, path), stamp, null, verifiedAt)
      m.locations += loc
      loc.id
    }

  fun putPhoneCopy(mediaId: MediaId, versionId: VersionId, ref: PhoneRef, stamp: FileStamp): LocationId = synchronized(lock) {
    val loc = Loc(LocationId(nextLocation++), versionId, Place.Phone(ref), null, stamp, null)
    media.getValue(mediaId).locations += loc
    loc.id
  }

  /** La lettura del telefono vede la riga cambiata con continuita' (Samsung "Salva", E-E1): versione nuova `EDITED`, attuale. */
  fun phoneChanged(mediaId: MediaId, ref: PhoneRef, stamp: FileStamp, size: Long, fullHash: Sha256?): VersionId = synchronized(lock) {
    val m = media.getValue(mediaId)
    val loc = m.locations.first { (it.place as? Place.Phone)?.ref?.mediaStoreId == ref.mediaStoreId }
    val v = VersionId.random()
    m.versions += Ver(v, VersionKind.EDITED, loc.versionId, size, fullHash, null)
    loc.versionId = v
    loc.place = Place.Phone(ref)
    loc.phoneStamp = stamp
    m.current = v
    v
  }

  /** La lettura del telefono vede la riga tornata a una versione nota (Samsung "Ripristina originale", E-E2). */
  fun phoneReverted(mediaId: MediaId, ref: PhoneRef, stamp: FileStamp, to: VersionId) {
    synchronized(lock) {
      val m = media.getValue(mediaId)
      val loc = m.locations.first { (it.place as? Place.Phone)?.ref?.mediaStoreId == ref.mediaStoreId }
      loc.versionId = to
      loc.phoneStamp = stamp
      m.current = to
    }
  }

  /** Una riga nuova del telefono attribuita al media come sua versione (l'editor che salva una copia dentro una sessione, E-X3). */
  fun phoneCopyAsVersion(mediaId: MediaId, ref: PhoneRef, stamp: FileStamp, size: Long): VersionId = synchronized(lock) {
    val m = media.getValue(mediaId)
    val v = VersionId.random()
    m.versions += Ver(v, VersionKind.EDITED, m.current, size, null, null)
    m.locations += Loc(LocationId(nextLocation++), v, Place.Phone(ref), null, stamp, null)
    m.current = v
    v
  }

  /** Una versione nuova senza posti (un editor esterno su una copia in cache, E-E3, il cui file e' preparato). */
  fun addVersion(mediaId: MediaId, kind: VersionKind, size: Long, fullHash: Sha256?, current: Boolean = true): VersionId = synchronized(lock) {
    val m = media.getValue(mediaId)
    val v = VersionId.random()
    m.versions += Ver(v, kind, m.current, size, fullHash, null)
    if (current) m.current = v
    v
  }

  /** La scansione vede il file del NAS cambiato al suo posto (il PC, E-E6, E-N2): versione nuova `EDITED`, attuale, verifica tolta. */
  fun nasChanged(mediaId: MediaId, share: String, path: SharePath, stamp: NasStamp): VersionId = synchronized(lock) {
    val m = media.getValue(mediaId)
    val loc = m.locations.first { it.isNasAt(share, path) }
    val v = VersionId.random()
    m.versions += Ver(v, VersionKind.EDITED, loc.versionId, stamp.size, null, null)
    loc.versionId = v
    loc.nasStamp = stamp
    loc.verifiedAt = null
    m.current = v
    v
  }

  /** La scansione non trova piu' il file (il PC l'ha tolto): la posizione e' assente. */
  fun nasVanished(mediaId: MediaId, share: String, path: SharePath) {
    synchronized(lock) { media.getValue(mediaId).locations.filter { it.isNasAt(share, path) }.forEach { it.present = false } }
  }

  private fun Loc.isNasAt(share: String, path: SharePath): Boolean {
    val p = place as? Place.Nas ?: return false
    return p.share.equals(share, ignoreCase = true) && p.path.value.lowercase(Locale.ROOT) == path.value.lowercase(Locale.ROOT)
  }

  /** E-P12: la lettura del telefono ritrova la riga [oldId] con un `_ID` e una versione del MediaStore nuovi. */
  fun phoneRenumbered(mediaId: MediaId, oldId: Long, ref: PhoneRef) {
    synchronized(lock) {
      media.getValue(mediaId).locations.filter { (it.place as? Place.Phone)?.ref?.mediaStoreId == oldId }.forEach { it.place = Place.Phone(ref) }
    }
  }

  /** Le copie di [versionId] non sono piu' verificate (una rilettura che non e' tornata, una verifica mai fatta). */
  fun unverify(mediaId: MediaId, versionId: VersionId) {
    synchronized(lock) { media.getValue(mediaId).locations.filter { it.versionId == versionId }.forEach { it.verifiedAt = null } }
  }

  /** La versione attuale di un media. */
  fun currentOf(mediaId: MediaId): VersionId = synchronized(lock) { media.getValue(mediaId).current }

  /** Dove sta sul NAS (share, percorso, verificata) ogni copia presente di [versionId]. */
  fun nasCopiesOf(mediaId: MediaId, versionId: VersionId): List<Triple<String, SharePath, Long?>> = synchronized(lock) {
    media.getValue(mediaId).locations.filter { it.versionId == versionId && it.present }
      .mapNotNull { l -> (l.place as? Place.Nas)?.let { Triple(it.share, it.path, l.verifiedAt) } }
  }

  /** Tutti i media. */
  fun allMedia(): List<MediaId> = synchronized(lock) { media.keys.toList() }

  // --- La porta ------------------------------------------------------------------------------------------------------------------

  override suspend fun media(mediaId: MediaId): VersionedMedia? = synchronized(lock) {
    val m = media[mediaId] ?: return null
    VersionedMedia(
      mediaId = m.id,
      mine = m.mine,
      kind = m.kind,
      mime = m.mime,
      currentVersionId = m.current,
      versions = m.versions.map { v ->
        val locs = m.locations.filter { it.versionId == v.id }
        VersionFacts(
          versionId = v.id,
          kind = v.kind,
          parentId = v.parent,
          size = v.size,
          fullHash = v.fullHash,
          quickHash = v.quickHash,
          nas = locs.mapNotNull { l ->
            val p = l.place as? Place.Nas ?: return@mapNotNull null
            NasVersionCopy(l.id, p.share, p.path, l.nasStamp!!, areaOf(p.share), l.verifiedAt, l.present)
          },
          phone = locs.mapNotNull { l ->
            val p = l.place as? Place.Phone ?: return@mapNotNull null
            PhoneVersionCopy(l.id, p.ref, l.phoneStamp!!, l.present)
          },
        )
      },
    )
  }

  private fun areaOf(share: String): NasArea? = areas.entries.firstOrNull { it.key.equals(share, ignoreCase = true) }?.value

  override suspend fun recordFullHash(mediaId: MediaId, versionId: VersionId, sha256: Sha256): HashRecord = synchronized(lock) {
    val v = media[mediaId]?.versions?.firstOrNull { it.id == versionId } ?: return HashRecord.UNKNOWN_VERSION
    when (v.fullHash) {
      null -> {
        v.fullHash = sha256
        HashRecord.RECORDED
      }
      sha256 -> HashRecord.SAME
      else -> HashRecord.CONFLICT
    }
  }

  override suspend fun attachVerifiedCopy(copy: VerifiedCopy): Boolean = synchronized(lock) {
    val m = media[copy.mediaId] ?: return false
    val v = m.versions.firstOrNull { it.id == copy.versionId } ?: return false
    // Come `MediaCatalog.attachCopies`: dimensione della versione e hash completo noto, altrimenti rifiutata.
    if (copy.stamp.size != v.size || v.fullHash == null) return false
    val existing = m.locations.firstOrNull { it.isNasAt(copy.share, copy.path) }
    if (existing != null) {
      existing.versionId = copy.versionId
      existing.nasStamp = copy.stamp
      existing.verifiedAt = copy.verifiedAtMillis
      existing.present = true
    } else {
      m.locations += Loc(LocationId(nextLocation++), copy.versionId, Place.Nas(copy.share, copy.path), copy.stamp, null, copy.verifiedAtMillis)
    }
    true
  }

  override suspend fun relocate(locationId: LocationId, share: String, path: SharePath, stamp: NasStamp) {
    synchronized(lock) {
      val loc = media.values.flatMap { it.locations }.first { it.id == locationId }
      loc.place = Place.Nas(share, path)
      loc.nasStamp = stamp
      loc.present = true
    }
  }

  override suspend fun restoreOriginal(mediaId: MediaId) {
    synchronized(lock) {
      val m = media.getValue(mediaId)
      m.current = m.versions.first { it.kind == VersionKind.ORIGINAL }.id
      restores++
    }
  }

  override suspend fun correct(mediaId: MediaId, fields: Set<CorrectionField>, size: Long, quickHash: QuickHash, sha256: Sha256): VersionId? =
    synchronized(lock) {
      val m = media[mediaId] ?: return null
      val v = VersionId.random()
      m.versions += Ver(v, VersionKind.CORRECTED, m.current, size, sha256, quickHash)
      m.current = v
      corrections += mediaId to fields
      v
    }

  override suspend fun correctInApp(mediaId: MediaId, versionId: VersionId, change: MetadataChange): Boolean = synchronized(lock) {
    if (media[mediaId] == null) return false
    inAppCorrections += Triple(mediaId, versionId, change)
    true
  }

  override suspend fun markProblem(mediaId: MediaId, problem: BackupProblemCode?) {
    synchronized(lock) { problems[mediaId] = problem }
  }
}

/** Il catalogo che muore: le sue scritture contano come passi della [BackupDeath]. */
class DyingVersionCatalog(private val inner: VersionCatalog, private val death: BackupDeath) : VersionCatalog by inner {
  override suspend fun recordFullHash(mediaId: MediaId, versionId: VersionId, sha256: Sha256): HashRecord =
    death.around { inner.recordFullHash(mediaId, versionId, sha256) }

  override suspend fun attachVerifiedCopy(copy: VerifiedCopy): Boolean = death.around { inner.attachVerifiedCopy(copy) }

  override suspend fun relocate(locationId: LocationId, share: String, path: SharePath, stamp: NasStamp) =
    death.around { inner.relocate(locationId, share, path, stamp) }

  override suspend fun restoreOriginal(mediaId: MediaId) = death.around { inner.restoreOriginal(mediaId) }

  override suspend fun correct(mediaId: MediaId, fields: Set<CorrectionField>, size: Long, quickHash: QuickHash, sha256: Sha256): VersionId? =
    death.around { inner.correct(mediaId, fields, size, quickHash, sha256) }

  override suspend fun correctInApp(mediaId: MediaId, versionId: VersionId, change: MetadataChange): Boolean =
    death.around { inner.correctInApp(mediaId, versionId, change) }

  override suspend fun markProblem(mediaId: MediaId, problem: BackupProblemCode?) = death.around { inner.markProblem(mediaId, problem) }
}
