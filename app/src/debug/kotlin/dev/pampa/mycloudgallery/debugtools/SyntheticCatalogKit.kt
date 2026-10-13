package dev.pampa.mycloudgallery.debugtools

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.dao.TimelineIndexDao
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageSource
import dev.pampa.mycloudgallery.core.imaging.testing.FakeViewerImageSource
import dev.pampa.mycloudgallery.core.imaging.testing.SyntheticPixels
import dev.pampa.mycloudgallery.core.metadata.contract.ThumbHashCodec
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.library.testing.SyntheticMedia
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.LocationType
import dev.pampa.mycloudgallery.core.model.MediaGroupKind
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.MediaTrait
import dev.pampa.mycloudgallery.core.model.Presence
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.places.contract.PlaceNames
import dev.pampa.mycloudgallery.core.places.testing.FakePlaceData
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import dev.pampa.mycloudgallery.gallery.SyntheticLibraryKit
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SOLO debug: la Libreria sintetica (docs/fase3-progetto.md Â§5.2) scritta **in un catalogo vero**, nel database separato
 * `gallery-synthetic.db`. Le righe hanno la stessa forma che scrive la lettura del telefono: `media` con i campi derivati,
 * `media_version`, `media_location` (telefono, `_ID` finto e una versione del MediaStore che esiste solo qui),
 * `version_metadata`, `media_personal` (preferiti e nascosti), `media_group` (raffiche e coppie RAW+JPEG) e il cursore.
 * Gli `_ID` finti non sono mai usati per leggere byte: la Libreria sintetica disegna gradienti dai riferimenti sintetici.
 *
 * Scrittura con SQL diretto in una transazione (come il dataset della variante di misura di P9): 100 mila celle in poche
 * decine di secondi sull'emulatore, una volta sola (poi il database si riusa).
 */
internal object SyntheticCatalogKit : SyntheticLibraryKit {

  override val sizes: List<Int> = listOf(SyntheticLibrary.DEFAULT_COUNT, 100_000)

  override val viewerImages: ViewerImageSource by lazy { FakeViewerImageSource() }

  override val placeNames: PlaceNames by lazy { FakePlaces() }

  /** I gradienti sono [PLACEHOLDERS], uno per segnaposto: la miniatura e il suo segnaposto hanno gli stessi colori. */
  override fun pixelSeed(mediaStoreId: Long): Long = mediaStoreId % PLACEHOLDERS

  /** 3: segnaposto ThumbHash veri (prima byte finti, che il decoder vero disegnava a macchie) e rotazione in gradi (0, non 1). */
  override val version: Int = 3

  /**
   * Segnaposto **veri**: il ThumbHash dei gradienti sintetici, codificato con il codec dell'app (lo stesso che usa META).
   * I byte finti di `SyntheticLibrary` vanno bene per i fake in memoria, non per il decoder vero.
   */
  private val placeholders: List<ByteArray> by lazy {
    (0 until PLACEHOLDERS).map { k ->
      val argb = SyntheticPixels.argb(k.toLong(), PLACEHOLDER_SIDE, PLACEHOLDER_SIDE)
      val rgba = ByteArray(argb.size * 4)
      argb.forEachIndexed { i, c ->
        rgba[i * 4] = (c shr 16 and 0xFF).toByte()
        rgba[i * 4 + 1] = (c shr 8 and 0xFF).toByte()
        rgba[i * 4 + 2] = (c and 0xFF).toByte()
        rgba[i * 4 + 3] = (c ushr 24 and 0xFF).toByte()
      }
      ThumbHashCodec.encode(PLACEHOLDER_SIDE, PLACEHOLDER_SIDE, rgba).bytes()
    }
  }

  override suspend fun seed(database: GalleryDatabase, count: Int, progress: (Int, Int) -> Unit) = withContext(Dispatchers.IO) {
    val library = SyntheticLibrary(seed = SEED, count = count, placeIds = FakePlaceData.IDS)
    val all = library.media().toList()
    val sdb = database.openHelper.writableDatabase
    sdb.beginTransaction()
    try {
      write(sdb, all, count, progress)
      sdb.setTransactionSuccessful()
    } finally {
      sdb.endTransaction()
    }
    progress(count, count)
  }

  private fun write(sdb: SupportSQLiteDatabase, all: List<SyntheticMedia>, cells: Int, progress: (Int, Int) -> Unit) {
    val now = System.currentTimeMillis()
    val media = sdb.compileStatement(
      "INSERT INTO media (media_id, kind, source, owner_account, group_id, current_version_id, created_at, updated_at, sort_time, " +
        "presence, safety, local_day, aspect_milli, duration_ms, place_id, cell_flags, thumb_hash) VALUES (?,?,?,NULL,?,?,?,?,?,?,?,?,?,?,?,?,?)",
    )
    val version = sdb.compileStatement(
      "INSERT INTO media_version (version_id, media_id, kind, parent_version_id, size, quick_hash, full_hash, corrections, created_at) " +
        "VALUES (?,?,?,NULL,?,?,NULL,'',?)",
    )
    val location = sdb.compileStatement(
      "INSERT INTO media_location (version_id, location_type, location_key, folder_key, state, volume, relative_path, display_name, " +
        "media_store_id, media_store_version, generation, account_id, share_name, share_key, nas_path, size, modified_at, absent_since, " +
        "verified_at, last_seen_at) VALUES (?,?,?,?,?,?,?,?,?,?,NULL,NULL,NULL,NULL,NULL,?,?,NULL,NULL,?)",
    )
    val metadata = sdb.compileStatement(
      "INSERT INTO version_metadata (version_id, mime_type, width, height, orientation, duration_ms, taken_at_utc, taken_offset_min, " +
        "taken_confidence, taken_source, latitude, longitude, altitude, updated_at, mirrored, traits, place_id, place_dataset, thumb_hash) " +
        "VALUES (?,?,?,?,0,?,?,?,?,?,NULL,NULL,NULL,?,0,?,?,?,?)",
    )
    val personal = sdb.compileStatement(
      "INSERT INTO media_personal (media_id, favorite, hidden, updated_at, favorite_changed_at, hidden_changed_at) VALUES (?,?,?,?,?,?)",
    )
    val groups = LinkedHashMap<String, String?>() // gruppo -> copertina
    all.forEachIndexed { i, m ->
      val versionId = versionIdOf(i)
      val mediaStoreId = (i + 1).toLong()
      val folder = FOLDERS.getValue(m.source)
      val name = String.format(Locale.ROOT, "SYN_%06d.%s", i, if (m.kind.isVideo) "mp4" else "jpg")
      val aspect = m.aspectMilli.coerceAtLeast(1)
      val width = if (aspect >= 1000) LONG_SIDE else LONG_SIDE * aspect / 1000
      val height = if (aspect >= 1000) LONG_SIDE * 1000 / aspect else LONG_SIDE
      val size = 2_000_000L + (i % 997) * 1_000L
      val groupId = m.groupId?.value
      if (groupId != null) if (m.groupCover) groups[groupId] = m.id.value else groups.putIfAbsent(groupId, null)
      // I membri che non rappresentano il gruppo stanno fuori dall'indice (D20), come li scrive il catalogo.
      val memberFlag = if (groupId != null && !m.groupCover) CellFlags.GROUP_MEMBER else 0

      media.bind(
        m.id.value, m.kind.code, m.source.code, groupId, versionId, now, now, m.sortTimeMs,
        Presence.PHONE_ONLY.code, SafetyStatus.NO_NAS_COPY.code, m.localDay.toLong(), aspect.toLong(),
        if (m.kind == MediaKind.VIDEO) m.durationMs else null, m.placeId.takeIf { it > 0 }?.toLong(),
        (m.flags or memberFlag).toLong(), placeholders[(mediaStoreId % PLACEHOLDERS).toInt()],
      )
      version.bind(versionId, m.id.value, VersionKind.ORIGINAL.code, size, String.format(Locale.ROOT, "%064x", i), m.sortTimeMs)
      location.bind(
        versionId, LocationType.PHONE.code, "phone:$VOLUME:" + (folder + name).lowercase(Locale.ROOT),
        "phone:$VOLUME:" + folder.lowercase(Locale.ROOT), LocationState.PRESENT.code, VOLUME, folder, name, mediaStoreId,
        MS_VERSION, size, m.sortTimeMs, now,
      )
      val estimated = CellFlags.has(m.flags, CellFlags.DATE_ESTIMATED)
      var traits = 0
      if (CellFlags.has(m.flags, CellFlags.PANORAMA)) traits = traits or MediaTrait.PANORAMA.bit
      if (CellFlags.has(m.flags, CellFlags.ULTRA_HDR)) traits = traits or MediaTrait.ULTRA_HDR.bit
      metadata.bind(
        versionId, if (m.kind.isVideo) "video/mp4" else "image/jpeg", width.toLong(), height.toLong(),
        if (m.kind == MediaKind.VIDEO) m.durationMs else null, m.sortTimeMs, m.zoneOffsetMinutes.toLong(),
        (if (estimated) CaptureTimeConfidence.ESTIMATED else CaptureTimeConfidence.EXACT).code,
        (if (estimated) CaptureTimeSource.FILE_NAME else CaptureTimeSource.EXIF_WITH_OFFSET).code,
        now, traits.toLong(), m.placeId.takeIf { it > 0 }?.toLong(), if (m.placeId > 0) PLACES_DATASET else null,
        placeholders[(mediaStoreId % PLACEHOLDERS).toInt()],
      )
      val favorite = CellFlags.has(m.flags, CellFlags.FAVORITE)
      if (favorite || m.hidden) personal.bind(m.id.value, if (favorite) 1L else 0L, if (m.hidden) 1L else 0L, now, now, now)
      if (i % PROGRESS_EVERY == 0) progress(minOf(i, cells), cells)
    }
    val group = sdb.compileStatement("INSERT INTO media_group (group_id, kind, cover_media_id, created_at) VALUES (?,?,?,?)")
    for ((id, cover) in groups) group.bind(id, MediaGroupKind.BURST.code, cover, now)
    sdb.compileStatement(
      "INSERT OR REPLACE INTO media_store_cursor (volume, ms_version, generation, deleted_generation, scan_epoch, scan_resume_after_id, " +
        "last_full_scan_at, last_reconcile_at) VALUES (?,?,NULL,NULL,NULL,NULL,?,?)",
    ).bind(VOLUME, MS_VERSION, now, now)
    sdb.compileStatement("INSERT OR REPLACE INTO catalog_meta (`key`, value) VALUES (?,?)").bind(TimelineIndexDao.CHANGE_SEQ, 1L)
  }

  /** Lega i valori in ordine (null, testo, numeri, byte) ed esegue. */
  private fun SupportSQLiteStatement.bind(vararg values: Any?) {
    clearBindings()
    values.forEachIndexed { i, value ->
      val index = i + 1
      when (value) {
        null -> bindNull(index)
        is String -> bindString(index, value)
        is Long -> bindLong(index, value)
        is Int -> bindLong(index, value.toLong())
        is ByteArray -> bindBlob(index, value)
        else -> error("Tipo non previsto.")
      }
    }
    executeInsert()
  }

  private fun versionIdOf(i: Int): String = java.util.UUID(VERSION_HI, i.toLong()).toString()

  private const val SEED = 1L
  private const val VOLUME = "external_primary"

  /** Una versione del MediaStore che esiste solo in questo database: gli `_ID` finti non valgono per nient'altro. */
  private const val MS_VERSION = "libreria-sintetica"
  private const val LONG_SIDE = 4_000
  private const val PLACES_DATASET = 1L
  private const val PROGRESS_EVERY = 2_000
  private const val PLACEHOLDERS = 256
  private const val PLACEHOLDER_SIDE = 16
  private const val VERSION_HI = 0x5359_4E54_4845_5449L

  private val FOLDERS: Map<MediaSource, String> = mapOf(
    MediaSource.CAMERA to "DCIM/Camera/",
    MediaSource.SCREENSHOT to "DCIM/Screenshots/",
    MediaSource.WHATSAPP to "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/",
    MediaSource.QUICK_SHARE to "Download/Quick Share/",
    MediaSource.OTHER to "Pictures/",
  )
}
