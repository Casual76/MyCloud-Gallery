package dev.pampa.mycloudgallery.albums

import dev.pampa.mycloudgallery.albums.AlbumRowCodec.HOLD
import dev.pampa.mycloudgallery.albums.AlbumRowCodec.META
import dev.pampa.mycloudgallery.albums.AlbumRowCodec.REJECTED
import dev.pampa.mycloudgallery.albums.AlbumRowCodec.SEEN
import dev.pampa.mycloudgallery.core.albums.shared.AlbumReplicaStore
import dev.pampa.mycloudgallery.core.albums.shared.DeviceFootprint
import dev.pampa.mycloudgallery.core.albums.shared.ReplicaHold
import dev.pampa.mycloudgallery.core.albums.shared.StoredContribution
import dev.pampa.mycloudgallery.core.albums.shared.StoredEntry
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.entity.SharedAlbumEntryEntity
import dev.pampa.mycloudgallery.core.database.entity.SharedAlbumHeadMarkEntity
import dev.pampa.mycloudgallery.core.database.transaction
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * La replica degli album condivisi su Room (Fase 7, P13; "Senza di loro gli album condivisi non sopravvivono alla morte del processo"): il
 * contributo di ogni (album, dispositivo) in `shared_album_entry` (una riga per voce, piu' una riga di stato [META] e, se ci sono, la testa
 * rifiutata [REJECTED], l'attesa [HOLD] e l'ultima volta che la serie del dispositivo non aveva niente di nuovo [SEEN]) e il segno della testa
 * in `shared_album_head_mark` (anti-rollback ed equivocazione, D8). Il formato delle righe e' in [AlbumRowCodec].
 *
 * [put] sostituisce il contributo intero **in una transazione** (come chiede [AlbumReplicaStore]): scrive solo le righe cambiate e toglie le
 * sparite, cosi' una testa nuova che cambia un segmento non riscrive le ventimila voci di un album grande. Una cache in memoria, scritta dopo
 * ogni transazione riuscita, evita di rileggere e ridecodificare le voci a ogni ricalcolo della proiezione; R13 della revisione di sicurezza
 * della Fase 7: la cache tiene al piu' `MAX_CACHED_ENTRIES` voci (gli album usati meno di recente escono e si rileggono quando servono), e i
 * conti per dispositivo dei tetti ([footprint]) si fanno una volta e poi si tengono al passo, senza scorrere la tabella a ogni contributo.
 * Le chiamate si serializzano.
 */
class RoomAlbumReplicaStore(
  private val database: GalleryDatabase,
  /** R13: le voci tenute in memoria al piu' (gli album oltre si rileggono da Room quando servono). */
  private val maxCachedEntries: Int = MAX_CACHED_ENTRIES,
) : AlbumReplicaStore {
  private val dao get() = database.sharedAlbumDao()
  private val mutex = Mutex()
  private var albumsCache: LinkedHashSet<AlbumId>? = null

  /** Gli album in memoria, dal meno al piu' usato di recente (R13), e quante voci tengono in tutto. */
  private val byAlbum = LinkedHashMap<AlbumId, LinkedHashMap<String, StoredContribution>>(16, 0.75f, true)
  private var cachedEntries = 0

  /** I conti per dispositivo (voci, album, byte), fatti alla prima richiesta e tenuti al passo da [put] e [forget]. */
  private var footprints: HashMap<String, DeviceFootprint>? = null

  /** Le attese (R2) e le ore "visto" (R7), lette una volta e tenute al passo: un giro le chiede per ogni contributo. */
  private var holds: HashMap<Pair<AlbumId, String>, ReplicaHold>? = null
  private var seen: HashMap<Pair<AlbumId, String>, Long>? = null

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
    cachedEntries += contribution.entries.size - (previous?.entries?.size ?: 0)
    footprints?.let { all -> all[contribution.device] = (all[contribution.device] ?: DeviceFootprint.NONE).replaced(previous, contribution) }
    albumSet() += album
    trimCache(album)
  }

  override suspend fun forget(album: AlbumId, device: String) = mutex.withLock {
    val previous = loaded(album)[device]
    database.transaction {
      // La testa rifiutata e l'ultima volta che la serie e' stata vista (R7) restano: dicono qualcosa del dispositivo, non del contributo tolto.
      val keys = dao.entryKeysOf(album.value, device).filter { it != REJECTED && it != SEEN }
      AlbumRowCodec.removeKeys(database, album, device, keys)
      database.openHelper.writableDatabase.execSQL(
        "DELETE FROM shared_album_head_mark WHERE album_id = ? AND device_id = ?",
        arrayOf<Any?>(album.value, device),
      )
    }
    holds?.remove(album to device)
    if (previous != null) {
      loaded(album).remove(device)
      cachedEntries -= previous.entries.size
      footprints?.let { all -> all[device] = (all[device] ?: DeviceFootprint.NONE).replaced(previous, null) }
    }
    if (loaded(album).isEmpty()) albumSet() -= album
  }

  override suspend fun footprint(device: String): DeviceFootprint = mutex.withLock {
    val all = footprints ?: withContext(Dispatchers.IO) { AlbumRowCodec.footprints(database) }.also { footprints = it }
    all[device] ?: DeviceFootprint.NONE
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

  override suspend fun hold(album: AlbumId, device: String): ReplicaHold? = mutex.withLock { holdRows()[album to device] }

  override suspend fun setHold(album: AlbumId, device: String, hold: ReplicaHold?): Unit = mutex.withLock {
    require(!device.startsWith("~")) { "Dispositivo non valido." }
    if (hold == null) {
      if (holdRows().containsKey(album to device)) dao.removeEntries(album.value, device, listOf(HOLD))
      holdRows().remove(album to device)
      Unit
    } else {
      val lines = listOf("h1", "version=${hold.mark?.version ?: ""}", "hash=${hold.mark?.contentHash ?: ""}", "retry=${hold.retryAt}", "attempts=${hold.attempts}")
      dao.upsertEntries(listOf(AlbumRowCodec.metaRow(album, device, HOLD, hold.retryAt, "", false, lines)))
      holdRows()[album to device] = hold
    }
  }

  override suspend fun seenAt(album: AlbumId, device: String): Long? = mutex.withLock { seenRows()[album to device] }

  override suspend fun setSeenAt(album: AlbumId, device: String, at: Long) = mutex.withLock {
    require(!device.startsWith("~")) { "Dispositivo non valido." }
    dao.upsertEntries(listOf(AlbumRowCodec.metaRow(album, device, SEEN, at, "", false, listOf("v1"))))
    seenRows()[album to device] = at
  }

  /** L'azzeramento senza togliere il database (prove): via tutto, anche dalla memoria. */
  suspend fun clearAll() = mutex.withLock {
    database.transaction {
      database.openHelper.writableDatabase.execSQL("DELETE FROM shared_album_entry WHERE device_id != ?", arrayOf<Any?>(AlbumRowCodec.OWN))
      dao.deleteAllMarks()
    }
    byAlbum.clear()
    cachedEntries = 0
    footprints = null
    holds = null
    seen = null
    albumsCache = null
  }

  // --- Lettura e traduzione --------------------------------------------------------------------------------------------

  private suspend fun holdRows(): HashMap<Pair<AlbumId, String>, ReplicaHold> = holds ?: withContext(Dispatchers.IO) {
    val out = HashMap<Pair<AlbumId, String>, ReplicaHold>()
    for ((key, row) in AlbumRowCodec.serviceRows(database, HOLD)) {
      val lines = AlbumRowCodec.metaLines(row)
      val retry = lines["retry"]?.firstOrNull()?.toLongOrNull()
      val attempts = lines["attempts"]?.firstOrNull()?.toIntOrNull()
      if (retry == null || attempts == null) {
        unreadableRows++
        continue
      }
      val version = lines["version"]?.firstOrNull()?.toLongOrNull()
      val hash = lines["hash"]?.firstOrNull()?.takeIf { it.isNotEmpty() }
      out[key] = ReplicaHold(if (version != null && hash != null) HeadMark(version, hash) else null, retry, attempts)
    }
    out
  }.also { holds = it }

  private suspend fun seenRows(): HashMap<Pair<AlbumId, String>, Long> = seen ?: withContext(Dispatchers.IO) {
    HashMap(AlbumRowCodec.serviceRows(database, SEEN).mapValues { it.value.at })
  }.also { seen = it }

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
    cachedEntries += out.values.sumOf { it.entries.size }
    trimCache(album)
    out
  }

  /** R13: la cache non supera [maxCachedEntries] voci; escono gli album usati meno di recente, mai [keep] (quello appena chiesto). */
  private fun trimCache(keep: AlbumId) {
    if (cachedEntries <= maxCachedEntries) return
    val it = byAlbum.entries.iterator()
    while (cachedEntries > maxCachedEntries && it.hasNext()) {
      val (album, contributions) = it.next()
      if (album == keep) continue
      cachedEntries -= contributions.values.sumOf { c -> c.entries.size }
      it.remove()
    }
  }

  /** Quanti album e quante voci tiene adesso la cache (prove di R13). */
  internal fun cacheSize(): Pair<Int, Int> = byAlbum.size to cachedEntries

  private fun decode(album: AlbumId, device: String, meta: SharedAlbumEntryEntity, rows: List<SharedAlbumEntryEntity>): StoredContribution? {
    val lines = AlbumRowCodec.metaLines(meta)
    val account = runCatching { AccountId(meta.account) }.getOrNull() ?: return null.also { unreadableRows++ }
    val version = lines["version"]?.firstOrNull()?.toLongOrNull() ?: return null.also { unreadableRows++ }
    val hash = lines["hash"]?.firstOrNull() ?: return null.also { unreadableRows++ }
    val segments = AlbumRowCodec.segmentsOf(lines)
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

    /** R13: le voci tenute in memoria al piu' (gli album oltre si rileggono da Room quando servono). */
    const val MAX_CACHED_ENTRIES = 50_000
  }
}

/** I conti di un dispositivo dopo che il suo contributo [before] (null se non c'era) diventa [after] (null se si toglie). */
private fun DeviceFootprint.replaced(before: StoredContribution?, after: StoredContribution?): DeviceFootprint = DeviceFootprint(
  entries = entries - (before?.entries?.size ?: 0) + (after?.entries?.size ?: 0),
  albums = albums - (if (before != null) 1 else 0) + (if (after != null) 1 else 0),
  bytes = bytes - (before?.bytes ?: 0L) + (after?.bytes ?: 0L),
)
