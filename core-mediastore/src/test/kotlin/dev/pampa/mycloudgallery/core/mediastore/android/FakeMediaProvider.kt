package dev.pampa.mycloudgallery.core.mediastore.android

import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileNotFoundException

/**
 * Un `ContentProvider` registrato come `media` per provare l'adattatore su Robolectric (docs/fase3-progetto.md §5.1).
 * Le righe stanno in un SQLite in memoria con le colonne vere del MediaProvider, cosi' selezione, argomenti, ordine e
 * limite che l'adattatore manda nel `Bundle` sono **eseguiti davvero** (un errore di SQL fallisce il test).
 *
 * Simula anche: `call` per versione e generazione (`get_version`, `get_generation`) e per il cestino
 * (`create_trash_request`), `QUERY_ARG_MATCH_PENDING`/`TRASHED` con i default della piattaforma, `deleted_files`,
 * `openFile`/`openTypedAssetFile` con il controllo di `requireOriginal` (senza, una foto si legge "oscurata").
 */
class FakeMediaProvider : ContentProvider() {

  private lateinit var db: SQLiteDatabase
  private val files = HashMap<Long, File>()

  /** Il file di una riga: un test lo allunga senza dirlo al provider (un'app che scrive e non avvisa il MediaStore, R6 della revisione 5A). */
  fun fileOf(id: Long): File? = files[id]

  val versions = HashMap<String, String>()
  val generations = HashMap<String, Long>()

  /** Se falso, aprire una foto con `requireOriginal` fallisce come senza `ACCESS_MEDIA_LOCATION`. */
  var locationGranted: Boolean = true

  /** Se vero, ogni query restituisce un cursore nullo (provider che non risponde). */
  var nullCursor: Boolean = false

  /** Se vero, la tabella `deleted_files` non si puo' interrogare. */
  var deletedFilesBroken: Boolean = false

  val queryArgs: MutableList<Bundle> = ArrayList()
  val openedUris: MutableList<Uri> = ArrayList()
  val trashRequests: MutableList<Pair<List<Uri>, Int?>> = ArrayList()

  override fun onCreate(): Boolean {
    db = SQLiteDatabase.create(null)
    db.execSQL(
      "CREATE TABLE files (_id INTEGER PRIMARY KEY, volume_name TEXT, media_type INTEGER, _display_name TEXT, " +
        "relative_path TEXT, mime_type TEXT, _size INTEGER, date_modified INTEGER, datetaken INTEGER, width INTEGER, " +
        "height INTEGER, orientation INTEGER, duration INTEGER, owner_package_name TEXT, generation_modified INTEGER, " +
        "is_pending INTEGER NOT NULL DEFAULT 0, is_trashed INTEGER NOT NULL DEFAULT 0, is_favorite INTEGER NOT NULL DEFAULT 0, " +
        "date_expires INTEGER)",
    )
    db.execSQL("CREATE TABLE deleted_files (_id INTEGER, volume_name TEXT, media_type INTEGER, deleted_generation INTEGER)")
    return true
  }

  /** Inserisce una riga con il suo contenuto (scritto in un file temporaneo). Restituisce l'`_ID`. */
  fun insertRow(
    id: Long,
    volume: String = "external_primary",
    mediaType: Int = 1,
    name: String = "IMG_$id.jpg",
    path: String? = "DCIM/Camera/",
    mime: String? = if (mediaType == 3) "video/mp4" else "image/jpeg",
    content: ByteArray = ByteArray(16) { (it + id).toByte() },
    modifiedSec: Long = 1_760_000_000L,
    taken: Long? = 1_760_000_000_000L + id,
    generation: Long? = id,
    pending: Boolean = false,
    trashed: Boolean = false,
    expiresSec: Long? = null,
  ): Long {
    val file = File.createTempFile("media-$id-", ".bin").apply { writeBytes(content); deleteOnExit() }
    files[id] = file
    db.insertOrThrow(
      "files",
      null,
      ContentValues().apply {
        put("_id", id)
        put("volume_name", volume)
        put("media_type", mediaType)
        put("_display_name", name)
        put("relative_path", path)
        put("mime_type", mime)
        put("_size", content.size.toLong())
        put("date_modified", modifiedSec)
        put("datetaken", taken)
        put("width", 4000)
        put("height", 3000)
        put("orientation", 90)
        put("duration", if (mediaType == 3) 5_000L else null)
        put("owner_package_name", "com.sec.android.app.camera")
        put("generation_modified", generation)
        put("is_pending", if (pending) 1 else 0)
        put("is_trashed", if (trashed) 1 else 0)
        put("date_expires", expiresSec)
      },
    )
    return id
  }

  fun deleteRow(id: Long, generation: Long, mediaType: Int = 1, volume: String = "external_primary") {
    db.delete("files", "_id = ?", arrayOf(id.toString()))
    db.insertOrThrow(
      "deleted_files",
      null,
      ContentValues().apply {
        put("_id", id)
        put("volume_name", volume)
        put("media_type", mediaType)
        put("deleted_generation", generation)
      },
    )
  }

  fun trashRow(id: Long, generation: Long) {
    db.update("files", ContentValues().apply { put("is_trashed", 1); put("generation_modified", generation) }, "_id = ?", arrayOf(id.toString()))
  }

  override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor? {
    val args = queryArgs ?: Bundle()
    this.queryArgs += args
    if (nullCursor) return null
    val segments = uri.pathSegments
    val volume = segments.firstOrNull() ?: throw IllegalArgumentException("URI senza volume")
    if (segments.getOrNull(1) == "deleted_files") {
      if (deletedFilesBroken) throw IllegalArgumentException("deleted_files non supportata")
      return select("deleted_files", volume, projection, args, applyMatch = false, uri)
    }
    return select("files", volume, projection, args, applyMatch = true, uri)
  }

  private fun select(table: String, volume: String, projection: Array<out String>?, args: Bundle, applyMatch: Boolean, uri: Uri): Cursor {
    val where = ArrayList<String>()
    val whereArgs = ArrayList<String>()
    args.getString(ContentResolver.QUERY_ARG_SQL_SELECTION)?.let { where += "($it)" }
    args.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)?.let { whereArgs += it }
    if (volume != MediaStore.VOLUME_EXTERNAL) {
      where += "volume_name = ?"
      whereArgs += volume
    }
    // Una collezione di tipo (images/media, video/media) vede solo il suo tipo.
    when (uri.pathSegments.getOrNull(1)) {
      "images" -> where += "media_type = 1"
      "video" -> where += "media_type = 3"
    }
    if (applyMatch) {
      where += matchClause("is_pending", args.getInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_DEFAULT))
      where += matchClause("is_trashed", args.getInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_DEFAULT))
    }
    // Come il MediaProvider vero (DatabaseUtils.recoverAbusiveLimit): limite nell'URI e nel Bundle insieme e' un errore.
    if (uri.getQueryParameter("limit") != null && args.containsKey(ContentResolver.QUERY_ARG_LIMIT)) {
      throw IllegalArgumentException("Abusive limit conflicts with requested limit")
    }
    val limit = if (args.containsKey(ContentResolver.QUERY_ARG_LIMIT)) args.getInt(ContentResolver.QUERY_ARG_LIMIT).toString() else uri.getQueryParameter("limit")
    return db.query(
      table,
      projection?.let { Array(it.size) { i -> it[i] } },
      where.filter { it.isNotEmpty() }.joinToString(" AND ").ifEmpty { null },
      whereArgs.toTypedArray(),
      null,
      null,
      args.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER),
      limit,
    )
  }

  /** I default della piattaforma: pending e cestinate escluse. */
  private fun matchClause(column: String, match: Int): String = when (match) {
    MediaStore.MATCH_INCLUDE -> ""
    MediaStore.MATCH_ONLY -> "$column = 1"
    else -> "$column = 0"
  }

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
    GET_VERSION_CALL -> Bundle().apply { putString(Intent.EXTRA_TEXT, versions[extras?.getString(Intent.EXTRA_TEXT)]) }
    GET_GENERATION_CALL -> Bundle().apply { putLong(Intent.EXTRA_INDEX, generations[extras?.getString(Intent.EXTRA_TEXT)] ?: 0L) }
    CREATE_TRASH_REQUEST_CALL -> {
      @Suppress("DEPRECATION")
      val clip = extras?.getParcelable<ClipData>(EXTRA_CLIP_DATA)
      val uris = (0 until (clip?.itemCount ?: 0)).map { clip!!.getItemAt(it).uri }
      @Suppress("DEPRECATION")
      val values = extras?.getParcelable<ContentValues>(EXTRA_CONTENT_VALUES)
      trashRequests += uris to values?.getAsInteger(MediaStore.MediaColumns.IS_TRASHED)
      val pending = PendingIntent.getActivity(context, 0, Intent("dev.pampa.TRASH"), PendingIntent.FLAG_IMMUTABLE)
      Bundle().apply { putParcelable(EXTRA_RESULT, pending) }
    }
    else -> null
  }

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    openedUris += uri
    val id = uri.lastPathSegment?.toLongOrNull() ?: throw FileNotFoundException("URI senza _ID")
    val file = files[id] ?: throw FileNotFoundException("Riga sparita")
    if (uri.getBooleanQueryParameter("requireOriginal", false) && !locationGranted) {
      throw UnsupportedOperationException("Serve ACCESS_MEDIA_LOCATION per l'originale")
    }
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
  }

  override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?): AssetFileDescriptor =
    AssetFileDescriptor(openFile(uri, "r"), 0, AssetFileDescriptor.UNKNOWN_LENGTH)

  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
    query(
      uri,
      projection,
      Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs?.map { it }?.toTypedArray())
        putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
      },
      null,
    )

  override fun getType(uri: Uri): String? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

  private companion object {
    // Le chiavi (nascoste) che MediaStore usa con `call`.
    const val GET_VERSION_CALL = "get_version"
    const val GET_GENERATION_CALL = "get_generation"
    const val CREATE_TRASH_REQUEST_CALL = "create_trash_request"
    const val EXTRA_CLIP_DATA = "clip_data"
    const val EXTRA_CONTENT_VALUES = "content_values"
    const val EXTRA_RESULT = "result"
  }
}
