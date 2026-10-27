package dev.pampa.mycloudgallery.albums

import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.entity.SharedAlbumEntryEntity
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.data.AlbumDocs
import dev.pampa.mycloudgallery.core.trust.data.AlbumEntry
import dev.pampa.mycloudgallery.core.trust.data.AlbumField
import dev.pampa.mycloudgallery.core.trust.data.AlbumSegmentContext
import dev.pampa.mycloudgallery.core.trust.data.AlbumValue

// COME LE VOCI DEGLI ALBUM CONDIVISI STANNO NELLE RIGHE DI `shared_album_entry` (Fase 7, P13). La tabella di P1 ha le colonne per cui si
// cerca (album, dispositivo, chiave, campo, ora, account, chiave di contenuto, lapide) e un corpo opaco per il database: la replica di P2 e il
// pubblicatore tengono qualcosa in piu' (il segmento di ogni voce e il segno "retrodatata", i segmenti di una testa, l'esclusione di un
// contributo, la testa rifiutata; il numero d'ordine delle mie modifiche e quelle in coda). Senza toccare lo schema v6 (una sola migrazione per
// la fase, D34) il corpo e' **una riga di intestazione** (testo ASCII) seguita dal JSON canonico della voce (`AlbumDocs.encodeEntry`, il formato
// del NAS: si rilegge con tutte le regole di chi legge, `AlbumDocs.decodeEntry`). Le righe che non sono voci hanno la chiave che comincia con
// `~` (una chiave vera sono 64 cifre esadecimali) e si saltano in lettura. Una riga che non si rilegge (scritta da un'altra versione) si salta:
// il giro dopo la riscarica dal NAS (replica) o la ripubblicazione la ridice (pubblicatore).

internal object AlbumRowCodec {
  /** Il "dispositivo" delle righe del pubblicatore (le mie voci pubblicate e in coda): non e' mai un id di dispositivo vero. */
  const val OWN = "~own"

  /** La riga con lo stato di un contributo o del mio pubblicatore. */
  const val META = "~meta"

  /** La riga con l'ultima testa rifiutata di (album, dispositivo). */
  const val REJECTED = "~rejected"

  /** R2: la riga con l'attesa di un contributo che adesso non si legge (testa, ora del NAS del prossimo tentativo, tentativi). */
  const val HOLD = "~hold"

  /** R7: la riga con l'ultima ora del NAS in cui la serie del dispositivo non aveva niente di nuovo per chi legge (colonna `at`). */
  const val SEEN = "~seen"

  /** Il prefisso delle modifiche in coda del pubblicatore (la chiave che segue e' quella della voce). */
  const val STAGED = "~s:"

  /**
   * L'ora con cui si rilegge una modifica in coda ancora "da datare" (l'ora del NAS la prende alla pubblicazione): la piu' alta che il formato
   * ammette, cosi' nessuna regola sull'ora (la storia dei livelli non va oltre l'ora della voce) rifiuta un valore che il pubblicatore ha
   * gia' ammesso.
   */
  const val UNDATED_AT: Long = dev.pampa.mycloudgallery.core.trust.TrustLimits.MAX_PLAUSIBLE_TIME_MILLIS

  /** Il tipo della voce per la colonna `field` (`item:<id>` diventa `item`). */
  fun typeOf(field: AlbumField): String = if (field is AlbumField.Item) "item" else field.wire

  /** Il corpo: intestazione, a capo, JSON canonico (null = non si puo' scrivere: la voce non e' ammessa dal formato). */
  fun body(header: String, entry: AlbumEntry): ByteArray? {
    val json = AlbumDocs.entryBytesOrNull(entry) ?: return null
    return (header + "\n").toByteArray(Charsets.US_ASCII) + json
  }

  /** L'intestazione e il JSON di un corpo, o null se non ha la forma attesa. */
  fun split(body: ByteArray): Pair<String, ByteArray>? {
    val nl = body.indexOf('\n'.code.toByte())
    if (nl <= 0) return null
    return String(body, 0, nl, Charsets.US_ASCII) to body.copyOfRange(nl + 1, body.size)
  }

  /** Rilegge una voce con le regole di chi legge (album, autore e account devono tornare). */
  fun entry(json: ByteArray, album: AlbumId, author: String, account: String): AlbumEntry? {
    val context = runCatching { AlbumSegmentContext(album, author, AccountId(account)) }.getOrNull() ?: return null
    return (AlbumDocs.decodeEntry(json, context) as? Verdict.Accepted)?.value
  }

  /** La riga di una voce vera (la chiave e' quella della voce). */
  fun entryRow(device: String, entry: AlbumEntry, body: ByteArray): SharedAlbumEntryEntity = SharedAlbumEntryEntity(
    albumId = entry.album.value,
    deviceId = device,
    entryKey = entry.key.hex,
    field = typeOf(entry.field),
    at = entry.at,
    account = entry.account.value,
    contentKey = (entry.value as? AlbumValue.Item)?.contentKey?.hex,
    tombstone = entry.value == null,
    body = body,
  )

  /** Una riga di servizio (stato, testa rifiutata): chiave e campo uguali, nessuna chiave di contenuto. */
  fun metaRow(album: AlbumId, device: String, key: String, at: Long, account: String, flag: Boolean, lines: List<String>): SharedAlbumEntryEntity =
    SharedAlbumEntryEntity(
      albumId = album.value,
      deviceId = device,
      entryKey = key,
      field = key,
      at = at,
      account = account,
      contentKey = null,
      tombstone = flag,
      body = lines.joinToString("\n").toByteArray(Charsets.UTF_8),
    )

  /** Le righe `chiave=valore` di una riga di servizio (la prima riga e' la versione). */
  fun metaLines(row: SharedAlbumEntryEntity): Map<String, List<String>> = metaLines(row.body)

  /** Le righe `chiave=valore` di un corpo di servizio (la prima riga e' la versione). */
  fun metaLines(body: ByteArray): Map<String, List<String>> {
    val out = LinkedHashMap<String, MutableList<String>>()
    String(body, Charsets.UTF_8).split('\n').drop(1).forEach { line ->
      val eq = line.indexOf('=')
      if (eq > 0) out.getOrPut(line.substring(0, eq)) { ArrayList() } += line.substring(eq + 1)
    }
    return out
  }

  /** Gli id distinti degli album che hanno righe [key] per il dispositivo [device] (o per tutti tranne [OWN] se null). */
  fun albumsWith(database: GalleryDatabase, key: String, device: String?): Set<AlbumId> {
    val sql = if (device == null) {
      "SELECT DISTINCT album_id FROM shared_album_entry WHERE entry_key = ? AND device_id != ?"
    } else {
      "SELECT DISTINCT album_id FROM shared_album_entry WHERE entry_key = ? AND device_id = ?"
    }
    val out = LinkedHashSet<AlbumId>()
    database.openHelper.readableDatabase.query(sql, arrayOf(key, device ?: OWN)).use { c ->
      while (c.moveToNext()) runCatching { AlbumId(c.getString(0)) }.getOrNull()?.let(out::add)
    }
    return out
  }

  /** Quante righe con una data chiave (o tutte le voci vere, [key] null) ha il dispositivo [device] in tutti gli album. */
  fun countFor(database: GalleryDatabase, device: String, key: String?): Int {
    val sql = if (key == null) {
      "SELECT COUNT(*) FROM shared_album_entry WHERE device_id = ? AND substr(entry_key, 1, 1) != '~'"
    } else {
      "SELECT COUNT(*) FROM shared_album_entry WHERE device_id = ? AND entry_key = ?"
    }
    val args: Array<Any?> = if (key == null) arrayOf(device) else arrayOf(device, key)
    database.openHelper.readableDatabase.query(sql, args).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
  }

  /** I segmenti elencati da una riga di stato (`seg=<impronta>:<byte>`). */
  fun segmentsOf(lines: Map<String, List<String>>): List<dev.pampa.mycloudgallery.core.trust.data.SegmentRef> = lines["seg"].orEmpty().mapNotNull { s ->
    val colon = s.lastIndexOf(':')
    if (colon <= 0) null else s.substring(colon + 1).toLongOrNull()?.let { dev.pampa.mycloudgallery.core.trust.data.SegmentRef(s.substring(0, colon), it) }
  }

  /**
   * R13: voci, album e byte di ogni dispositivo nella replica, in due letture (un conto raggruppato e le righe di stato), da tenere poi al
   * passo in memoria: i tetti per dispositivo non scorrono piu' la tabella a ogni contributo.
   */
  fun footprints(database: GalleryDatabase): HashMap<String, dev.pampa.mycloudgallery.core.albums.shared.DeviceFootprint> {
    val entries = HashMap<String, Int>()
    database.openHelper.readableDatabase.query(
      "SELECT device_id, COUNT(*) FROM shared_album_entry WHERE device_id != ? AND substr(entry_key, 1, 1) != '~' GROUP BY device_id",
      arrayOf<Any?>(OWN),
    ).use { c -> while (c.moveToNext()) entries[c.getString(0)] = c.getInt(1) }
    val albums = HashMap<String, Int>()
    val bytes = HashMap<String, Long>()
    database.openHelper.readableDatabase.query(
      "SELECT device_id, body FROM shared_album_entry WHERE device_id != ? AND entry_key = ?",
      arrayOf<Any?>(OWN, META),
    ).use { c ->
      while (c.moveToNext()) {
        val device = c.getString(0)
        albums[device] = (albums[device] ?: 0) + 1
        bytes[device] = (bytes[device] ?: 0L) + segmentsOf(metaLines(c.getBlob(1))).sumOf { it.size }
      }
    }
    val out = HashMap<String, dev.pampa.mycloudgallery.core.albums.shared.DeviceFootprint>()
    for (device in entries.keys + albums.keys) {
      out[device] = dev.pampa.mycloudgallery.core.albums.shared.DeviceFootprint(entries[device] ?: 0, albums[device] ?: 0, bytes[device] ?: 0L)
    }
    return out
  }

  /**
   * Le righe di servizio [key] di tutti gli album e dispositivi (tranne [OWN]): (album, dispositivo) -> riga. Per le cache delle attese e dei
   * "visto" della replica (un giro le chiede per ogni contributo).
   */
  fun serviceRows(database: GalleryDatabase, key: String): Map<Pair<AlbumId, String>, SharedAlbumEntryEntity> {
    val out = HashMap<Pair<AlbumId, String>, SharedAlbumEntryEntity>()
    val sql = "SELECT album_id, device_id, at, body FROM shared_album_entry WHERE entry_key = ? AND device_id != ?"
    database.openHelper.readableDatabase.query(sql, arrayOf<Any?>(key, OWN)).use { c ->
      while (c.moveToNext()) {
        val album = runCatching { AlbumId(c.getString(0)) }.getOrNull() ?: continue
        val device = c.getString(1)
        out[album to device] = SharedAlbumEntryEntity(
          albumId = album.value, deviceId = device, entryKey = key, field = key, at = c.getLong(2), account = "", contentKey = null, tombstone = false,
          body = c.getBlob(3),
        )
      }
    }
    return out
  }

  /** Quante righe di servizio [key] ci sono in tutta la replica (non quelle del mio pubblicatore). */
  fun countKey(database: GalleryDatabase, key: String): Int {
    database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM shared_album_entry WHERE entry_key = ? AND device_id != ?", arrayOf<Any?>(key, OWN)).use { c ->
      return if (c.moveToFirst()) c.getInt(0) else 0
    }
  }

  /** Il corpo della riga di servizio [key] di (album, dispositivo), o null. */
  fun serviceBody(database: GalleryDatabase, album: AlbumId, device: String, key: String): ByteArray? {
    val sql = "SELECT body FROM shared_album_entry WHERE album_id = ? AND device_id = ? AND entry_key = ?"
    database.openHelper.readableDatabase.query(sql, arrayOf<Any?>(album.value, device, key)).use { c ->
      return if (c.moveToFirst()) c.getBlob(0) else null
    }
  }

  /** Toglie righe a blocchi (il limite delle variabili di SQLite su API 29 e' 999). */
  suspend fun removeKeys(database: GalleryDatabase, album: AlbumId, device: String, keys: Collection<String>) {
    keys.chunked(CHUNK).forEach { database.sharedAlbumDao().removeEntries(album.value, device, it) }
  }

  /** Scrive righe a blocchi. */
  suspend fun upsert(database: GalleryDatabase, rows: Collection<SharedAlbumEntryEntity>) {
    rows.chunked(CHUNK).forEach { database.sharedAlbumDao().upsertEntries(it) }
  }

  private const val CHUNK = 500
}
