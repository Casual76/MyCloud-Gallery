package dev.pampa.mycloudgallery.core.albums.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import dev.pampa.mycloudgallery.core.personal.store.LocalFlag
import dev.pampa.mycloudgallery.core.personal.store.PersonalProjection
import dev.pampa.mycloudgallery.core.personal.store.ProjectionResult
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalLedger
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import java.time.ZoneId
import java.util.UUID

// Il banco dei test degli album personali sul catalogo VERO (Room in memoria, Robolectric): un database v6, il catalogo, media registrati
// dalle osservazioni (id veri) o scritti a mano con l'id che serve al test (la Libreria finta della suite di contratto ha id suoi), e gli
// store dei dati personali con la proiezione dei preferiti del catalogo vero (per l'impronta di un media).

const val T0: Long = 1_760_000_000_000L

class AlbumsRoom : AutoCloseable {
  val db: GalleryDatabase = GalleryDatabase.build(ApplicationProvider.getApplicationContext<Context>(), name = null)
  val catalog = MediaCatalog(db, ME, zone = { ZoneId.of("Europe/Rome") }) { T0 }
  private var seq = 0L

  /** Un media registrato dal catalogo con byte tutti suoi (l'impronta e' funzione di [n]). Id vero, scelto dal catalogo. */
  suspend fun record(n: Int): MediaId {
    seq++
    val fingerprint = full(n)
    val location = Location.Phone("external_primary", "DCIM/Camera/", "IMG_${seq}_$n.jpg", 100L + seq, "store-v1")
    val observation = Observation(location, FileStamp(fingerprint.size, T0), fingerprint, MediaKind.PHOTO, MediaSource.CAMERA, T0 - n * 60_000L)
    return catalog.record(observation).mediaId!!
  }

  /** Due media con gli stessi byte nati separati (impronta rapida diversa): [MediaCatalog.merge] li unisce, il minore sopravvive. */
  suspend fun twins(n: Int): Pair<MediaId, MediaId> {
    seq++
    val fingerprint = full(n)
    val other = fingerprint.copy(quickHash = QuickHash("f".repeat(64)))
    val a = catalog.record(observation(fingerprint, "a$seq")).mediaId!!
    val b = catalog.record(observation(other, "b$seq")).mediaId!!
    check(a != b) { "I gemelli dovevano nascere separati." }
    return a to b
  }

  private fun observation(fingerprint: Fingerprint, name: String): Observation {
    seq++
    val location = Location.Phone("external_primary", "DCIM/Camera/", "IMG_$name.jpg", 100L + seq, "store-v1")
    return Observation(location, FileStamp(fingerprint.size, T0), fingerprint, MediaKind.PHOTO, MediaSource.CAMERA, T0)
  }

  /** L'impronta come la mette una voce personale (dimensione e `qh1`). */
  fun fingerprintOf(n: Int): PersonalFingerprint = full(n).let { PersonalFingerprint(it.size, it.quickHash) }

  /**
   * Un media scritto a mano con l'id che serve (e l'impronta [size] / [quick]): per le suite che hanno gia' i loro id e per le Librerie
   * grandi (nessun file da leggere). [hidden] lo scrive gia' nascosto. Una transazione: le chiavi esterne sono differite.
   */
  fun insertRaw(id: MediaId, size: Long, quick: String, takenAtMs: Long, localDay: Int, hidden: Boolean = false) {
    val sql = db.openHelper.writableDatabase
    val version = UUID.nameUUIDFromBytes(("v" + id.value).toByteArray()).toString()
    sql.beginTransaction()
    try {
      sql.execSQL(
        "INSERT INTO media (media_id, kind, source, owner_account, group_id, current_version_id, created_at, updated_at, sort_time, " +
          "presence, safety, local_day, aspect_milli, duration_ms, place_id, cell_flags, thumb_hash, backup_todo, origin_device, copy_areas) " +
          "VALUES (?, 'photo', 'camera', 'alessio', NULL, ?, 0, 0, ?, 'phone_only', 'no_nas_copy', ?, 1333, NULL, NULL, 0, NULL, 0, NULL, 0)",
        arrayOf<Any?>(id.value, version, takenAtMs, localDay),
      )
      sql.execSQL(
        "INSERT INTO media_version (version_id, media_id, kind, parent_version_id, size, quick_hash, full_hash, corrections, created_at) " +
          "VALUES (?, ?, 'original', NULL, ?, ?, NULL, '', 0)",
        arrayOf<Any?>(version, id.value, size, quick),
      )
      if (hidden) sql.execSQL("INSERT INTO media_personal (media_id, favorite, hidden, updated_at) VALUES (?, 0, 1, 0)", arrayOf<Any?>(id.value))
      sql.setTransactionSuccessful()
    } finally {
      sql.endTransaction()
    }
  }

  /** Come [insertRaw] per [count] media in una transazione sola (le Librerie grandi): [id], [size] e [quick] dicono il media numero n. */
  fun insertRawMany(count: Int, id: (Int) -> MediaId, size: (Int) -> Long, quick: (Int) -> String) {
    val sql = db.openHelper.writableDatabase
    sql.beginTransaction()
    try {
      val media = sql.compileStatement(
        "INSERT INTO media (media_id, kind, source, owner_account, group_id, current_version_id, created_at, updated_at, sort_time, " +
          "presence, safety, local_day, aspect_milli, duration_ms, place_id, cell_flags, thumb_hash, backup_todo, origin_device, copy_areas) " +
          "VALUES (?, 'photo', 'camera', 'alessio', NULL, ?, 0, 0, ?, 'phone_only', 'no_nas_copy', ?, 1333, NULL, NULL, 0, NULL, 0, NULL, 0)",
      )
      val version = sql.compileStatement(
        "INSERT INTO media_version (version_id, media_id, kind, parent_version_id, size, quick_hash, full_hash, corrections, created_at) " +
          "VALUES (?, ?, 'original', NULL, ?, ?, NULL, '', 0)",
      )
      for (n in 0 until count) {
        val mediaId = id(n).value
        val versionId = UUID.nameUUIDFromBytes(("v" + mediaId).toByteArray()).toString()
        media.clearBindings()
        media.bindString(1, mediaId)
        media.bindString(2, versionId)
        media.bindLong(3, T0 + n)
        media.bindLong(4, (20_260_101 + n % 28).toLong())
        media.executeInsert()
        version.clearBindings()
        version.bindString(1, versionId)
        version.bindString(2, mediaId)
        version.bindLong(3, size(n))
        version.bindString(4, quick(n))
        version.executeInsert()
      }
      sql.setTransactionSuccessful()
    } finally {
      sql.endTransaction()
    }
  }

  fun setHidden(id: MediaId, hidden: Boolean) {
    db.openHelper.writableDatabase.execSQL(
      "INSERT OR REPLACE INTO media_personal (media_id, favorite, hidden, updated_at) VALUES (?, 0, ?, 0)",
      arrayOf<Any?>(id.value, if (hidden) 1 else 0),
    )
  }

  /** Lo store di un dispositivo (autore nuovo) sopra il catalogo di questa Libreria, con l'ora del NAS che [nasNow] dice. */
  fun store(author: String = TrustIds.random(), nasNow: () -> Long? = { T0 }): LedgerPersonalStore =
    LedgerPersonalStore(author, nasNow, InMemoryPersonalLedger(), RoomFingerprints(this))

  override fun close() {
    db.close()
  }

  companion object {
    val ME: AccountId = AccountId.of("alessio")

    fun full(n: Int): Fingerprint =
      Fingerprint(size = 1_000L + n, quickHash = QuickHash("%064x".format(n + 1)), fullHash = Sha256("%064x".format(n + 1_000_000)))

    fun day(n: Int): Int = 20_260_101 + n % 28
  }
}

/**
 * La proiezione dei dati personali per i test: non porta niente nel catalogo (i preferiti e i nascosti non c'entrano con gli album) ma sa
 * l'impronta di un media e ritrova il media di un'impronta, come `CatalogPersonalProjection`, anche per i media scritti a mano.
 */
class RoomFingerprints(private val room: AlbumsRoom) : PersonalProjection {
  override suspend fun apply(entry: PersonalEntry): ProjectionResult = ProjectionResult.NOT_PROJECTED

  override suspend fun fingerprintOf(key: PersonalKey): PersonalFingerprint? = null

  override suspend fun localFlags(): List<LocalFlag> = emptyList()

  override suspend fun fingerprintOfMedia(mediaId: String): PersonalFingerprint? {
    val id = runCatching { MediaId.parse(mediaId) }.getOrNull() ?: return null
    val media = room.catalog.resolve(id) ?: return null
    val current = room.db.mediaDao().get(media.value)?.currentVersionId ?: return null
    val version = room.db.versionDao().forMedia(media.value).firstOrNull { it.versionId == current } ?: return null
    return PersonalFingerprint(version.size, QuickHash(version.quickHash))
  }

  override suspend fun mediaWithFingerprint(fingerprint: PersonalFingerprint): MediaId? =
    room.db.versionDao().byQuickFingerprint(fingerprint.size, fingerprint.quickHash.hex)
      .mapNotNull { room.catalog.resolve(MediaId(it.mediaId)) }.distinct().singleOrNull()
}
