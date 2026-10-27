package dev.pampa.mycloudgallery.albums

import dev.pampa.mycloudgallery.albums.AlbumRowCodec.META
import dev.pampa.mycloudgallery.albums.AlbumRowCodec.REJECTED
import dev.pampa.mycloudgallery.core.albums.shared.AlbumReplicaStore
import dev.pampa.mycloudgallery.core.albums.shared.StoredContribution
import dev.pampa.mycloudgallery.core.albums.shared.StoredEntry
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.entity.SharedAlbumEntryEntity
import dev.pampa.mycloudgallery.core.database.entity.SharedAlbumHeadMarkEntity
import dev.pampa.mycloudgallery.core.database.transaction
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * La replica degli album condivisi su Room (Fase 7, P13; "Senza di loro gli album condivisi non sopravvivono alla morte del processo"): il
 * contributo di ogni (album, dispositivo) in `shared_album_entry` (una riga per voce, piu' una riga di stato [META] e, se c'e', la testa
 * rifiutata [REJECTED]) e il segno della testa in `shared_album_head_mark` (anti-rollback ed equivocazione, D8). Il formato delle righe e' in
 * [AlbumRowCodec].
 *
 * [put] sostituisce il contributo intero **in una transazione** (come chiede [AlbumReplicaStore]): scrive solo le righe cambiate e toglie le
 * sparite, cosi' una testa nuova che cambia un segmento non riscrive le ventimila voci di un album grande. Una cache in memoria, scritta dopo
 * ogni transazione riuscita, evita di rileggere e ridecodificare le voci a ogni ricalcolo della proiezione (che chiede i contributi di un
 * album a ogni modifica). Le chiamate si serializzano.
 */
class RoomAlbumReplicaStore(private val database: GalleryDatabase) : AlbumReplicaStore {
  private val dao get() = database.sharedAlbumDao()
  private val mutex = Mutex()
  private var albumsCache: LinkedHashSet<AlbumId>? = null
  private val byAlbum = HashMap<AlbumId, LinkedHashMap<String, StoredContribution>>()

  /** Righe che non si sono potute rileggere (scritte da un'altra versione): saltate. Diagnostica. */
  @Volatile var unreadableRows: Int = 0
    private set

  override suspend fun albums(): Set<AlbumId> = mutex.withLock { albumSet().toSet() }

  override suspend fun contributions(album: AlbumId): List<StoredContribution> = mutex.withLock { loaded(album).values.toList() }

  override suspend fun contribution(album: AlbumId, device: String): StoredContribution? = mutex.withLock { loaded(album)[device] }

  override suspend fun put(contribution: StoredContribution) = mutex.withLock {
    require(!contribution.device.startsWith("~")) { "Dispositivo non valido." }
    val album = contribution.album
    val previous = loaded(album)[contribution.device]
    val oldRows = previous?.let(::rowsOf)?.associateBy { it.entryKey }.orEmpty()
    val newRows = rowsOf(contribution).associateBy { it.entryKey }
    val changed = newRows.values.filter { row -> oldRows[row.entryKey] != row }
    val gone = oldRows.keys - newRows.keys
    database.transaction {
      if (gone.isNotEmpty()) AlbumRowCodec.removeKeys(database, album, contribution.device, gone)
      if (changed.isNotEmpty()) AlbumRowCodec.upsert(database, changed)
      dao.putMark(
        SharedAlbumHeadMarkEntity(album.value, contribution.device, contribution.mark.version, contribution.mark.contentHash, REGISTRY_UNKNOWN, contribution.createdAt),
      )
    }
    loaded(album)[contribution.device] = contribution
    albumSet() += album
  }

  override suspend fun forget(album: AlbumId, device: String) = mutex.withLock {
    val previous = loaded(album)[device]
    database.transaction {
      val keys = dao.entryKeysOf(album.value, device).filter { it != REJECTED }
      AlbumRowCodec.removeKeys(database, album, device, keys)
      database.openHelper.writableDatabase.execSQL(
        "DELETE FROM shared_album_head_mark WHERE album_id = ? AND device_id = ?",
        arrayOf<Any?>(album.value, device),
      )
    }
    if (previous != null) loaded(album).remove(device)
    if (loaded(album).isEmpty()) albumSet() -= album
  }

  override suspend fun footprint(device: String): Pair<Int, Int> = mutex.withLock {
    withContext(Dispatchers.IO) { AlbumRowCodec.countFor(database, device, null) to AlbumRowCodec.countFor(database, device, META) }
  }

  override suspend fun rejectedHead(album: AlbumId, device: String): HeadMark? = mutex.withLock {
    val body = withContext(Dispatchers.IO) { AlbumRowCodec.serviceBody(database, album, device, REJECTED) } ?: return@withLock null
    val lines = AlbumRowCodec.metaLines(body)
    val version = lines["version"]?.firstOrNull()?.toLongOrNull() ?: return@withLock null
    val hash = lines["hash"]?.firstOrNull() ?: return@withLock null
    HeadMark(version, hash)
  }

  override suspend fun setRejectedHead(album: AlbumId, device: String, mark: HeadMark?) = mutex.withLock {
    require(!device.startsWith("~")) { "Dispositivo non valido." }
    if (mark == null) {
      dao.removeEntries(album.value, device, listOf(REJECTED))
    } else {
      dao.upsertEntries(
        listOf(AlbumRowCodec.metaRow(album, device, REJECTED, mark.version, "", false, listOf("x1", "version=${mark.version}", "hash=${mark.contentHash}"))),
      )
    }
  }

  /** L'azzeramento senza togliere il database (prove): via tutto, anche dalla memoria. */
  suspend fun clearAll() = mutex.withLock {
    database.transaction {
      database.openHelper.writableDatabase.execSQL("DELETE FROM shared_album_entry WHERE device_id != ?", arrayOf<Any?>(AlbumRowCodec.OWN))
      dao.deleteAllMarks()
    }
    byAlbum.clear()
    albumsCache = null
  }

  // --- Lettura e traduzione --------------------------------------------------------------------------------------------

  private suspend fun albumSet(): LinkedHashSet<AlbumId> = albumsCache ?: withContext(Dispatchers.IO) {
    LinkedHashSet(AlbumRowCodec.albumsWith(database, META, null))
  }.also { albumsCache = it }

  private suspend fun loaded(album: AlbumId): LinkedHashMap<String, StoredContribution> = byAlbum[album] ?: run {
    val rows = dao.entries(album.value).filter { it.deviceId != AlbumRowCodec.OWN }.groupBy { it.deviceId }
    val out = LinkedHashMap<String, StoredContribution>()
    for ((device, list) in rows) {
      val meta = list.firstOrNull { it.entryKey == META } ?: continue
      decode(album, device, meta, list)?.let { out[device] = it }
    }
    byAlbum[album] = out
    out
  }

  private fun decode(album: AlbumId, device: String, meta: SharedAlbumEntryEntity, rows: List<SharedAlbumEntryEntity>): StoredContribution? {
    val lines = AlbumRowCodec.metaLines(meta)
    val account = runCatching { AccountId(meta.account) }.getOrNull() ?: return null.also { unreadableRows++ }
    val version = lines["version"]?.firstOrNull()?.toLongOrNull() ?: return null.also { unreadableRows++ }
    val hash = lines["hash"]?.firstOrNull() ?: return null.also { unreadableRows++ }
    val segments = lines["seg"].orEmpty().mapNotNull { s ->
      val colon = s.lastIndexOf(':')
      if (colon <= 0) null else s.substring(colon + 1).toLongOrNull()?.let { SegmentRef(s.substring(0, colon), it) }
    }
    val entries = ArrayList<StoredEntry>()
    for (row in rows) {
      if (row.entryKey.startsWith("~")) continue
      val (header, json) = AlbumRowCodec.split(row.body) ?: run { unreadableRows++; null } ?: continue
      val parts = header.split(' ')
      if (parts.size != 3 || parts[0] != "r1") {
        unreadableRows++
        continue
      }
      val entry = AlbumRowCodec.entry(json, album, device, row.account) ?: run { unreadableRows++; null } ?: continue
      entries += StoredEntry(entry, parts[1], parts[2] == "1")
    }
    entries.sortBy { it.entry.key }
    return StoredContribution(album, device, account, HeadMark(version, hash), meta.at, segments, entries, barred = meta.tombstone)
  }

  /** Le righe di un contributo: lo stato e una riga per voce (una voce che il formato non ammette non c'e' nella replica, quindi non capita). */
  private fun rowsOf(c: StoredContribution): List<SharedAlbumEntryEntity> {
    val meta = AlbumRowCodec.metaRow(
      c.album, c.device, META, c.createdAt, c.account.value, c.barred,
      listOf("m1", "version=${c.mark.version}", "hash=${c.mark.contentHash}") + c.segments.map { "seg=${it.hash}:${it.size}" },
    )
    val entries = c.entries.mapNotNull { stored ->
      val body = AlbumRowCodec.body("r1 ${stored.segment} ${if (stored.retrodated) 1 else 0}", stored.entry) ?: return@mapNotNull null
      AlbumRowCodec.entryRow(c.device, stored.entry, body)
    }
    return listOf(meta) + entries
  }

  private companion object {
    /** La replica di P2 non tiene la versione del registro citata dalla testa: la colonna resta a 0. */
    const val REGISTRY_UNKNOWN = 0L
  }
}
