package dev.pampa.mycloudgallery.albums

import dev.pampa.mycloudgallery.albums.AlbumRowCodec.META
import dev.pampa.mycloudgallery.albums.AlbumRowCodec.OWN
import dev.pampa.mycloudgallery.albums.AlbumRowCodec.STAGED
import dev.pampa.mycloudgallery.core.albums.shared.AlbumPublishStore
import dev.pampa.mycloudgallery.core.albums.shared.OwnContribution
import dev.pampa.mycloudgallery.core.albums.shared.PublishedEntry
import dev.pampa.mycloudgallery.core.albums.shared.StagedEntry
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.entity.SharedAlbumEntryEntity
import dev.pampa.mycloudgallery.core.database.transaction
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.trust.data.AlbumEntry
import dev.pampa.mycloudgallery.core.trust.data.AlbumKey
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Il mio contributo durevole su Room (Fase 7, P13; D6, §2.5 passo 6): le voci gia' dentro una testa pubblicata, le modifiche in coda con il
 * loro numero d'ordine (il diario della condivisione lo salva: ENTRY_STAGED -> PUBLISHED) e lo stato dell'ultima testa, in
 * `shared_album_entry` con il "dispositivo" [OWN] (le righe della replica hanno l'id vero del dispositivo: la mia testa riletta dal NAS e il
 * mio stato non si confondono). Il formato delle righe e' in [AlbumRowCodec].
 *
 * [save] sostituisce lo stato di un album in una transazione scrivendo solo le righe cambiate; una cache in memoria (scritta dopo ogni
 * transazione riuscita) evita di ridecodificare le voci a ogni modifica (il pubblicatore rilegge lo stato a ogni `stage`).
 *
 * [author] da' il dispositivo e l'account con cui si rileggono le modifiche **in coda** (una modifica non porta ne' autore ne' account: li
 * aggiunge il pubblicatore alla pubblicazione); senza famiglia pronta non si accoda niente (il pubblicatore lo sa gia').
 */
class RoomAlbumPublishStore(
  private val database: GalleryDatabase,
  private val author: () -> Pair<String, AccountId>?,
) : AlbumPublishStore {
  private val dao get() = database.sharedAlbumDao()
  private val mutex = Mutex()
  private var albumsCache: LinkedHashSet<AlbumId>? = null
  private val states = HashMap<AlbumId, OwnContribution?>()

  /** Righe che non si sono potute rileggere: saltate (la ripubblicazione ridice lo stato intero). Diagnostica. */
  @Volatile var unreadableRows: Int = 0
    private set

  override suspend fun albums(): Set<AlbumId> = mutex.withLock { albumSet().toSet() }

  override suspend fun load(album: AlbumId): OwnContribution? = mutex.withLock { loaded(album) }

  override suspend fun save(state: OwnContribution) = mutex.withLock {
    val album = state.album
    val previous = loaded(album)
    val oldRows = previous?.let(::rowsOf)?.associateBy { it.entryKey }.orEmpty()
    val newRows = rowsOf(state).associateBy { it.entryKey }
    val changed = newRows.values.filter { row -> oldRows[row.entryKey] != row }
    val gone = oldRows.keys - newRows.keys
    database.transaction {
      if (gone.isNotEmpty()) AlbumRowCodec.removeKeys(database, album, OWN, gone)
      if (changed.isNotEmpty()) AlbumRowCodec.upsert(database, changed)
    }
    states[album] = state
    albumSet() += album
  }

  /** L'azzeramento senza togliere il database (prove). */
  suspend fun clearAll() = mutex.withLock {
    database.openHelper.writableDatabase.execSQL("DELETE FROM shared_album_entry WHERE device_id = ?", arrayOf<Any?>(OWN))
    states.clear()
    albumsCache = null
  }

  private suspend fun albumSet(): LinkedHashSet<AlbumId> = albumsCache ?: withContext(Dispatchers.IO) {
    LinkedHashSet(AlbumRowCodec.albumsWith(database, META, OWN))
  }.also { albumsCache = it }

  private suspend fun loaded(album: AlbumId): OwnContribution? {
    if (states.containsKey(album)) return states[album]
    val rows = dao.entriesOf(album.value, OWN)
    val state = rows.firstOrNull { it.entryKey == META }?.let { decode(album, it, rows) }
    states[album] = state
    return state
  }

  private fun decode(album: AlbumId, meta: SharedAlbumEntryEntity, rows: List<SharedAlbumEntryEntity>): OwnContribution? {
    val lines = AlbumRowCodec.metaLines(meta)
    fun long(name: String): Long? = lines[name]?.firstOrNull()?.toLongOrNull()
    val seq = long("seq") ?: return null.also { unreadableRows++ }
    val markVersion = long("markVersion")
    val markHash = lines["markHash"]?.firstOrNull()?.takeIf { it.isNotEmpty() }
    val published = LinkedHashMap<AlbumKey, PublishedEntry>()
    val staged = LinkedHashMap<AlbumKey, StagedEntry>()
    for (row in rows) {
      if (row.entryKey == META) continue
      val (header, json) = AlbumRowCodec.split(row.body) ?: run { unreadableRows++; null } ?: continue
      val parts = header.split(' ')
      when {
        parts.size == 3 && parts[0] == "p1" -> {
          val rowSeq = parts[1].toLongOrNull()
          val entry = AlbumRowCodec.entry(json, album, parts[2], row.account)
          if (rowSeq == null || entry == null) unreadableRows++ else published[entry.key] = PublishedEntry(entry, rowSeq)
        }
        parts.size == 4 && parts[0] == "s1" && row.entryKey.startsWith(STAGED) -> {
          val rowSeq = parts[1].toLongOrNull()
          val at = if (parts[2] == "-") null else parts[2].toLongOrNull()
          val entry = AlbumRowCodec.entry(json, album, parts[3], row.account)
          if (rowSeq == null || entry == null || (parts[2] != "-" && at == null)) {
            unreadableRows++
          } else {
            staged[entry.key] = StagedEntry(rowSeq, entry.field, entry.value, at)
          }
        }
        else -> unreadableRows++
      }
    }
    return OwnContribution(
      album = album,
      seq = seq,
      upTo = long("upTo") ?: 0,
      headVersion = long("headVersion") ?: 0,
      headMark = if (markVersion != null && markHash != null) HeadMark(markVersion, markHash) else null,
      headCreatedAt = long("headCreatedAt"),
      segments = lines["seg"].orEmpty().toSet(),
      published = published,
      staged = staged,
    )
  }

  private fun rowsOf(state: OwnContribution): List<SharedAlbumEntryEntity> {
    val meta = AlbumRowCodec.metaRow(
      state.album, OWN, META, state.headCreatedAt ?: 0, "", false,
      listOf(
        "o1", "seq=${state.seq}", "upTo=${state.upTo}", "headVersion=${state.headVersion}",
        "markVersion=${state.headMark?.version ?: ""}", "markHash=${state.headMark?.contentHash ?: ""}",
        "headCreatedAt=${state.headCreatedAt ?: ""}",
      ) + state.segments.sorted().map { "seg=$it" },
    )
    val published = state.published.values.map { p ->
      val body = checkNotNull(AlbumRowCodec.body("p1 ${p.seq} ${p.entry.author}", p.entry)) { "Voce pubblicata non ammessa dal formato." }
      AlbumRowCodec.entryRow(OWN, p.entry, body)
    }
    val staged = if (state.staged.isEmpty()) emptyList() else {
      val (device, account) = checkNotNull(author()) { "Famiglia non pronta: nessun autore per le modifiche in coda." }
      state.staged.map { (key, s) ->
        val entry = AlbumEntry(state.album, s.field, s.at ?: AlbumRowCodec.UNDATED_AT, device, account, s.value)
        check(entry.key == key) { "Chiave diversa dal campo." }
        val body = checkNotNull(AlbumRowCodec.body("s1 ${s.seq} ${s.at ?: "-"} $device", entry)) { "Modifica non ammessa dal formato." }
        AlbumRowCodec.entryRow(OWN, entry, body).let { row ->
          SharedAlbumEntryEntity(row.albumId, row.deviceId, STAGED + key.hex, row.field, s.at ?: 0, row.account, row.contentKey, row.tombstone, row.body)
        }
      }
    }
    return listOf(meta) + published + staged
  }
}
