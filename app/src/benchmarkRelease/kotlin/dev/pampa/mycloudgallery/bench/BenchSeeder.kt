package dev.pampa.mycloudgallery.bench

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.dao.TimelineIndexDao
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.LocationType
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.Presence
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.model.domain.TimelineRules
import java.util.Locale
import java.util.SplittableRandom
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.runBlocking

/** L'esito della creazione del dataset: solo numeri, finiscono nello stato letto dal collaudo. */
internal class BenchSeedReport(
  val rows: Int,
  val realMedia: Int,
  val realImages: Int,
  val realVideos: Int,
  /** Megapixel dell'immagine piu' grande dei media veri (0 senza media). */
  val maxMegapixels: Double,
  /** Vero se la riga piu' recente punta all'immagine piu' grande (il soggetto del test di zoom a 200 MP). */
  val zoomSubjectNewest: Boolean,
  val seedMs: Long,
  /** Righe lette da `TimelineIndexDao.indexCursor()` rileggendo il file con un'istanza nuova del database. */
  val indexRows: Int,
  val indexReadMs: Long,
  /** Su 200 identita' dell'indice, quante `phoneRefs` ne risolvono (devono essere tutte). */
  val phoneRefsResolved: Int,
  val phoneRefsAsked: Int,
)

/**
 * Riempie un database del catalogo (lo schema vero di `GalleryDatabase`) con 100 mila righe sintetiche (§5.2) che
 * puntano **a rotazione ai media veri del dispositivo**, nella forma che l'indice della timeline legge: `media` con i
 * campi derivati, `media_version`, `media_location` (telefono, `_ID` vero, versione del MediaStore del cursore) e
 * `version_metadata`. Tutto locale, nessuna rete.
 *
 * - Forma deterministica: seme fisso, 15 anni di storia con giorni "a raffica" (feste, viaggi) e giornate vuote, video
 *   come i media veri, screenshot 10%, WhatsApp 20%, Quick Share 2%, preferiti 2%, date stimate 3%, luoghi 60% (id veri
 *   di GeoNames per le intestazioni), fusi diversi nei "viaggi", panorami dove la proporzione lo e'.
 * - La riga piu' recente punta all'immagine piu' grande (se l'ha il dispositivo): la Libreria si apre in fondo (D1), quindi
 *   il test di zoom a 200 MP la trova nell'ultima cella senza cercarla.
 * - Mai il database vero (`gallery.db`): il nome e' [BenchDataset.DATABASE_NAME].
 * - Nessuna riga nascosta e nessun gruppo: la timeline le contiene tutte, come in una Libreria "normale".
 */
internal object BenchSeeder {

  /** Un volume e una versione del MediaStore: quella vera, cosi' `phoneRefs` (che li confronta) risolve ogni riga. */
  fun seed(context: Context, real: List<BenchRealMedia>, rows: Int, msVersion: String, now: Long): BenchSeedReport {
    val startNs = System.nanoTime()
    context.deleteDatabase(BenchDataset.DATABASE_NAME)
    val hasReal = real.isNotEmpty()
    val media = if (hasReal) real else FALLBACK_MEDIA
    val zoomIndex = media.indices.maxByOrNull { index -> if (media[index].video) -1.0 else media[index].megapixels } ?: 0
    val zoomSubject = hasReal && !media[zoomIndex].video

    val db = GalleryDatabase.build(context, BenchDataset.DATABASE_NAME)
    try {
      val sdb = db.openHelper.writableDatabase
      sdb.beginTransaction()
      try {
        writeAll(sdb, media, zoomIndex.takeIf { zoomSubject }, rows, msVersion, now)
        sdb.setTransactionSuccessful()
      } finally {
        sdb.endTransaction()
      }
    } finally {
      db.close()
    }
    val seedMs = (System.nanoTime() - startNs) / 1_000_000

    // Rilettura con un'istanza nuova, come fara' l'app: stesso file, stesso schema, le query vere di P1.
    val reopened = GalleryDatabase.build(context, BenchDataset.DATABASE_NAME)
    try {
      val dao = reopened.timelineIndexDao()
      val readStart = System.nanoTime()
      val firstIds = ArrayList<String>(CHECK_IDS)
      var count = 0
      dao.indexCursor().use { c ->
        val iId = c.getColumnIndexOrThrow("media_id")
        while (c.moveToNext()) {
          if (firstIds.size < CHECK_IDS) firstIds += c.getString(iId)
          count++
        }
      }
      val readMs = (System.nanoTime() - readStart) / 1_000_000
      // Una sola volta, su un thread di collaudo: la funzione di P1 e' `suspend`.
      val resolved = runBlocking { dao.phoneRefs(firstIds).map { it.mediaId }.toSet().size }
      return BenchSeedReport(
        rows = rows,
        realMedia = if (hasReal) real.size else 0,
        realImages = if (hasReal) real.count { !it.video } else 0,
        realVideos = if (hasReal) real.count { it.video } else 0,
        maxMegapixels = if (zoomSubject) media[zoomIndex].megapixels else 0.0,
        zoomSubjectNewest = zoomSubject,
        seedMs = seedMs,
        indexRows = count,
        indexReadMs = readMs,
        phoneRefsResolved = resolved,
        phoneRefsAsked = firstIds.size,
      )
    } finally {
      reopened.close()
    }
  }

  private fun writeAll(
    sdb: SupportSQLiteDatabase,
    media: List<BenchRealMedia>,
    zoomIndex: Int?,
    rows: Int,
    msVersion: String,
    now: Long,
  ) {
    val tMedia = BenchTable(
      sdb, "media",
      listOf(
        "media_id", "kind", "source", "owner_account", "group_id", "current_version_id", "created_at", "updated_at", "sort_time",
        "presence", "safety", "local_day", "aspect_milli", "duration_ms", "place_id", "cell_flags", "thumb_hash",
      ),
    )
    val tVersion = BenchTable(
      sdb, "media_version",
      listOf("version_id", "media_id", "kind", "parent_version_id", "size", "quick_hash", "full_hash", "corrections", "created_at"),
    )
    val tLocation = BenchTable(
      sdb, "media_location",
      listOf(
        "version_id", "location_type", "location_key", "folder_key", "state", "volume", "relative_path", "display_name",
        "media_store_id", "media_store_version", "generation", "account_id", "share_name", "share_key", "nas_path", "size",
        "modified_at", "absent_since", "verified_at", "last_seen_at",
      ),
    )
    val tMeta = BenchTable(
      sdb, "version_metadata",
      listOf(
        "version_id", "mime_type", "width", "height", "orientation", "duration_ms", "taken_at_utc", "taken_offset_min",
        "taken_confidence", "taken_source", "latitude", "longitude", "altitude", "updated_at",
      ),
    )

    val rnd = SplittableRandom(SEED)
    val zone = TimeZone.getDefault()
    val newest = now - 60_000L
    val spanMs = (15 * 365.25 * DAY_MS).toLong()
    // I giorni "a raffica" (feste, viaggi): il 38% delle righe cade in 240 giorni; il resto in 2.600 giorni "normali" su
    // 5.479: meta' delle giornate dei 15 anni restano vuote, come in una libreria vera.
    val hotDays = LongArray(HOT_DAYS) { newest - 3_600_000L - (rnd.nextDouble() * spanMs).toLong() }
    val activeDays = LongArray(ACTIVE_DAYS) { newest - 3_600_000L - (rnd.nextDouble() * spanMs).toLong() }

    for (i in 0 until rows) {
      val realIndex = if (i == 0 && zoomIndex != null) zoomIndex else i % media.size
      val m = media[realIndex]

      val sortTime = if (i == 0 && zoomIndex != null) {
        newest
      } else {
        val day = if (rnd.nextInt(100) < HOT_SHARE) hotDays[rnd.nextInt(HOT_DAYS)] else activeDays[rnd.nextInt(ACTIVE_DAYS)]
        (day - rnd.nextInt(DAY_MS.toInt())).coerceAtMost(newest - 1_000L)
      }
      val offsetMin = if (rnd.nextInt(100) < TRIP_SHARE) TRIP_OFFSETS[rnd.nextInt(TRIP_OFFSETS.size)] else zone.getOffset(sortTime) / 60_000
      val localDay = TimelineRules.localDay(sortTime, offsetMin)

      val kind = when {
        m.video -> MediaKind.VIDEO
        m.mime == "image/gif" -> MediaKind.ANIMATED_IMAGE
        m.mime.contains("dng") -> MediaKind.RAW_PHOTO
        else -> MediaKind.PHOTO
      }
      val source = when ((i.toLong() * 2_654_435_761L % 100).toInt()) {
        in 0 until 10 -> MediaSource.SCREENSHOT
        in 10 until 30 -> MediaSource.WHATSAPP
        in 30 until 32 -> MediaSource.QUICK_SHARE
        else -> MediaSource.CAMERA
      }

      val swap = m.rotation == 90 || m.rotation == 270
      val shownW = if (swap) m.height else m.width
      val shownH = if (swap) m.width else m.height
      val aspect = (shownW * 1000L / shownH.coerceAtLeast(1)).toInt().coerceIn(TimelineRules.MIN_ASPECT_MILLI, TimelineRules.MAX_ASPECT_MILLI)

      var flags = 0
      val placeId = if (rnd.nextInt(100) < PLACE_SHARE) PLACES[rnd.nextInt(PLACES.size)] else null
      if (placeId != null) flags = flags or CellFlags.HAS_PLACE
      if (rnd.nextInt(100) < FAVORITE_SHARE) flags = flags or CellFlags.FAVORITE
      if (rnd.nextInt(100) < ESTIMATED_SHARE) flags = flags or CellFlags.DATE_ESTIMATED
      if (aspect >= PANORAMA_ASPECT_MILLI) flags = flags or CellFlags.PANORAMA

      val mediaId = uuid(MEDIA_TAG, i)
      val versionId = uuid(VERSION_TAG, i)
      val folder = FOLDERS.getValue(source)
      val displayName = "%06d_%s".format(Locale.ROOT, i, safeName(m.name))
      val locationKey = "phone:${BenchDeviceMedia.VOLUME}:" + (folder + displayName).lowercase(Locale.ROOT)
      val folderKey = "phone:${BenchDeviceMedia.VOLUME}:" + folder.lowercase(Locale.ROOT)
      val duration = if (m.video) m.durationMs else null

      tMedia.text("media_id", mediaId)
      tMedia.text("kind", kind.code)
      tMedia.text("source", source.code)
      tMedia.text("owner_account", null)
      tMedia.text("group_id", null)
      tMedia.text("current_version_id", versionId)
      tMedia.long("created_at", now)
      tMedia.long("updated_at", now)
      tMedia.long("sort_time", sortTime)
      tMedia.text("presence", Presence.PHONE_ONLY.code)
      tMedia.text("safety", SafetyStatus.NO_NAS_COPY.code)
      tMedia.int("local_day", localDay)
      tMedia.int("aspect_milli", aspect)
      tMedia.long("duration_ms", duration)
      tMedia.int("place_id", placeId)
      tMedia.int("cell_flags", flags)
      tMedia.blob("thumb_hash", null)
      tMedia.commit()

      tVersion.text("version_id", versionId)
      tVersion.text("media_id", mediaId)
      tVersion.text("kind", VersionKind.ORIGINAL.code)
      tVersion.text("parent_version_id", null)
      tVersion.long("size", m.size)
      tVersion.text("quick_hash", "bench-%08x".format(Locale.ROOT, i))
      tVersion.text("full_hash", null)
      tVersion.text("corrections", "")
      tVersion.long("created_at", sortTime)
      tVersion.commit()

      tLocation.text("version_id", versionId)
      tLocation.text("location_type", LocationType.PHONE.code)
      tLocation.text("location_key", locationKey)
      tLocation.text("folder_key", folderKey)
      tLocation.text("state", LocationState.PRESENT.code)
      tLocation.text("volume", BenchDeviceMedia.VOLUME)
      tLocation.text("relative_path", folder)
      tLocation.text("display_name", displayName)
      tLocation.long("media_store_id", m.id)
      tLocation.text("media_store_version", msVersion)
      tLocation.long("generation", null)
      tLocation.text("account_id", null)
      tLocation.text("share_name", null)
      tLocation.text("share_key", null)
      tLocation.text("nas_path", null)
      tLocation.long("size", m.size)
      tLocation.long("modified_at", sortTime)
      tLocation.long("absent_since", null)
      tLocation.long("verified_at", null)
      tLocation.long("last_seen_at", now)
      tLocation.commit()

      tMeta.text("version_id", versionId)
      tMeta.text("mime_type", m.mime)
      tMeta.int("width", m.width)
      tMeta.int("height", m.height)
      tMeta.int("orientation", exifOrientation(m.rotation))
      tMeta.long("duration_ms", duration)
      tMeta.long("taken_at_utc", sortTime)
      tMeta.int("taken_offset_min", offsetMin)
      tMeta.text("taken_confidence", "exact")
      tMeta.text("taken_source", "exif_offset")
      tMeta.real("latitude", null)
      tMeta.real("longitude", null)
      tMeta.real("altitude", null)
      tMeta.long("updated_at", now)
      tMeta.commit()
    }
    tMedia.close()
    tVersion.close()
    tLocation.close()
    tMeta.close()

    // Il cursore del MediaStore: `phoneRefs` unisce le posizioni al cursore e vuole la stessa `ms_version`.
    val tCursor = BenchTable(
      sdb, "media_store_cursor",
      listOf("volume", "ms_version", "generation", "deleted_generation", "scan_epoch", "scan_resume_after_id", "last_full_scan_at", "last_reconcile_at"),
    )
    tCursor.text("volume", BenchDeviceMedia.VOLUME)
    tCursor.text("ms_version", msVersion)
    tCursor.long("generation", null)
    tCursor.long("deleted_generation", null)
    tCursor.long("scan_epoch", null)
    tCursor.long("scan_resume_after_id", null)
    tCursor.long("last_full_scan_at", now)
    tCursor.long("last_reconcile_at", now)
    tCursor.commit()
    tCursor.close()

    val tMeta2 = BenchTable(sdb, "catalog_meta", listOf("key", "value"))
    tMeta2.text("key", TimelineIndexDao.CHANGE_SEQ)
    tMeta2.long("value", 1L)
    tMeta2.commit()
    tMeta2.close()
  }

  private fun uuid(tag: Long, i: Int): String = UUID(UUID_HI or tag, i.toLong()).toString()

  private fun safeName(name: String): String {
    val clean = name.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }.take(80)
    return clean.ifEmpty { "media.jpg" }
  }

  private fun exifOrientation(rotation: Int): Int = when (rotation) {
    90 -> 6
    180 -> 3
    270 -> 8
    else -> 1
  }

  // Senza media veri la variante si avvia lo stesso (il dataset ha la sua forma), ma le celle restano senza immagine:
  // lo stato dice `mediaReali = 0` e il collaudo lo segnala.
  private val FALLBACK_MEDIA: List<BenchRealMedia> = listOf(
    BenchRealMedia(1L, false, "image/jpeg", 4000, 3000, 0, 0L, 2_000_000L, 0L, "media.jpg"),
    BenchRealMedia(2L, false, "image/jpeg", 3000, 4000, 0, 0L, 2_000_000L, 0L, "media.jpg"),
    BenchRealMedia(3L, false, "image/jpeg", 4000, 2250, 0, 0L, 2_000_000L, 0L, "media.jpg"),
    BenchRealMedia(4L, true, "video/mp4", 1920, 1080, 0, 15_000L, 8_000_000L, 0L, "media.mp4"),
  )

  private val FOLDERS: Map<MediaSource, String> = mapOf(
    MediaSource.CAMERA to "DCIM/Camera/",
    MediaSource.SCREENSHOT to "DCIM/Screenshots/",
    MediaSource.WHATSAPP to "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/",
    MediaSource.QUICK_SHARE to "Download/Quick Share/",
    MediaSource.OTHER to "Pictures/",
  )

  /** Id veri di GeoNames (Roma, Milano, Napoli, Torino, Firenze, Bologna, Venezia, Palermo, Genova, Bari, Oslo, Londra, Parigi, Lisbona, Monaco, Madrid, Barcellona, Tokyo, New York). */
  private val PLACES: IntArray = intArrayOf(
    3169070, 3173435, 3172394, 3165524, 3176959, 3181928, 3164603, 2523920, 3176219, 3182351,
    3143244, 2643743, 2988507, 2267057, 2867714, 3117735, 3128760, 1850147, 5128581,
  )

  /** Fusi dei "viaggi" (E-M14): UTC-5, UTC-4, UTC+1, UTC+2, UTC+9. */
  private val TRIP_OFFSETS: IntArray = intArrayOf(-300, -240, 60, 120, 540)

  private const val SEED: Long = 0x4D43474C4942L
  private const val UUID_HI: Long = 0x4D43_4742_0000_0000L
  private const val MEDIA_TAG: Long = 1L
  private const val VERSION_TAG: Long = 2L
  private const val DAY_MS: Long = 86_400_000L
  private const val HOT_DAYS: Int = 240
  private const val ACTIVE_DAYS: Int = 2_600
  private const val HOT_SHARE: Int = 38
  private const val TRIP_SHARE: Int = 10
  private const val PLACE_SHARE: Int = 60
  private const val FAVORITE_SHARE: Int = 2
  private const val ESTIMATED_SHARE: Int = 3
  private const val PANORAMA_ASPECT_MILLI: Int = 2500
  private const val CHECK_IDS: Int = 200
}
