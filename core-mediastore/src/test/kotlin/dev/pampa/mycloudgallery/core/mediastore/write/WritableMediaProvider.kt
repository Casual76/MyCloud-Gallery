package dev.pampa.mycloudgallery.core.mediastore.write

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileNotFoundException
import org.robolectric.Robolectric

/**
 * Un `ContentProvider` registrato come `media` che **scrive**: la parte del MediaProvider che "Scarica sul telefono" usa
 * (P8). Le righe stanno in un SQLite in memoria con le colonne vere; il contenuto in file temporanei.
 *
 * Simula cio' che il provider vero fa e che il writer deve reggere:
 * - `insert` in `images`/`video` con `RELATIVE_PATH`, `DISPLAY_NAME` e `IS_PENDING`; un nome gia' occupato (senza
 *   distinguere maiuscole, anche da una riga in attesa) viene **rinominato dal provider** in "nome (1).ext";
 * - le righe in attesa e cestinate escluse dalle query salvo `QUERY_ARG_MATCH_*` (default della piattaforma);
 * - `openFile` in scrittura, `update` di `IS_PENDING`, `delete` con il file;
 * - guasti: [failOpens] (la riga non si apre in scrittura), [failDeletes] (il processo e' morto prima di ripulire),
 *   [failUpdates] (la pubblicazione non riesce), [nullQueries] (il provider non risponde).
 */
class WritableMediaProvider : ContentProvider() {

  private lateinit var db: SQLiteDatabase
  private val files = HashMap<Long, File>()
  private var nextId = 100L

  var versions: String = "v1"

  /** Se valorizzato, `openFile` lancia questo errore. */
  var failOpens: Throwable? = null

  /** Se vero, `delete` non fa niente (un processo morto non ripulisce) e risponde 0. */
  var failDeletes: Boolean = false

  /** Se vero, `update` non riesce. */
  var failUpdates: Boolean = false

  /** Se vero, ogni query risponde con un cursore nullo. */
  var nullQueries: Boolean = false

  /** Le righe inserite (id e nome) in ordine, per i test. */
  val inserted: MutableList<Pair<Long, String>> = ArrayList()

  /** Chiamato dentro `insert` prima di scegliere il nome: un altro scrittore che arriva per primo (la gara sul nome). */
  var beforeInsert: (() -> Unit)? = null

  override fun onCreate(): Boolean {
    db = SQLiteDatabase.create(null)
    db.execSQL(
      "CREATE TABLE files (_id INTEGER PRIMARY KEY, volume_name TEXT, media_type INTEGER, _display_name TEXT, " +
        "relative_path TEXT, mime_type TEXT, _size INTEGER, date_modified INTEGER, datetaken INTEGER, width INTEGER, " +
        "height INTEGER, orientation INTEGER, duration INTEGER, owner_package_name TEXT, generation_modified INTEGER, " +
        "is_pending INTEGER NOT NULL DEFAULT 0, is_trashed INTEGER NOT NULL DEFAULT 0, is_favorite INTEGER NOT NULL DEFAULT 0, " +
        "date_expires INTEGER)",
    )
    return true
  }

  /** Una riga che c'era gia' (di una sessione precedente o di un'altra app), con il suo contenuto. */
  fun seed(
    name: String,
    path: String = "Pictures/MyCloud Gallery/",
    mime: String = "image/jpeg",
    content: ByteArray = ByteArray(8),
    pending: Boolean = false,
    trashed: Boolean = false,
  ): Long {
    val id = nextId++
    files[id] = File.createTempFile("seed-$id-", ".bin").apply { writeBytes(content); deleteOnExit() }
    db.insertOrThrow(
      "files",
      null,
      ContentValues().apply {
        put("_id", id)
        put("volume_name", "external_primary")
        put("media_type", if (mime.startsWith("video/")) 3 else 1)
        put("_display_name", name)
        put("relative_path", path)
        put("mime_type", mime)
        put("_size", content.size.toLong())
        put("is_pending", if (pending) 1 else 0)
        put("is_trashed", if (trashed) 1 else 0)
      },
    )
    return id
  }

  /** Una riga com'e' nel provider. */
  data class Row(val id: Long, val name: String, val pending: Boolean, val trashed: Boolean)

  /** Tutte le righe, in attesa e cestinate comprese. */
  fun allRows(): List<Row> =
    db.rawQuery("SELECT _id, _display_name, is_pending, is_trashed FROM files ORDER BY _id", null).use {
      buildList { while (it.moveToNext()) add(Row(it.getLong(0), it.getString(1), it.getInt(2) == 1, it.getInt(3) == 1)) }
    }

  /** Le righe visibili alle altre app (non in attesa, non cestinate): `(id, nome)`. */
  fun visibleRows(): List<Pair<Long, String>> = allRows().filter { !it.pending && !it.trashed }.map { it.id to it.name }

  fun pendingIds(): List<Long> = allRows().filter { it.pending }.map { it.id }

  fun contentOf(id: Long): ByteArray? = files[id]?.readBytes()

  fun rowOf(id: Long): Map<String, Any?>? =
    db.rawQuery("SELECT * FROM files WHERE _id = ?", arrayOf(id.toString())).use {
      if (!it.moveToFirst()) null else (0 until it.columnCount).associate { c -> it.getColumnName(c) to it.getString(c) }
    }

  override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor? {
    if (nullQueries) return null
    val args = queryArgs ?: Bundle()
    val where = ArrayList<String>()
    val whereArgs = ArrayList<String>()
    args.getString(ContentResolver.QUERY_ARG_SQL_SELECTION)?.let { where += "($it)" }
    args.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)?.let { whereArgs += it }
    // Un URI di riga (.../files/<id>) interroga quella riga.
    uri.lastPathSegment?.toLongOrNull()?.let {
      where += "_id = ?"
      whereArgs += it.toString()
    }
    where += matchClause("is_pending", args.getInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_DEFAULT))
    where += matchClause("is_trashed", args.getInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_DEFAULT))
    return db.query(
      "files",
      projection?.let { Array(it.size) { i -> it[i] } },
      where.filter { it.isNotEmpty() }.joinToString(" AND ").ifEmpty { null },
      whereArgs.toTypedArray(),
      null,
      null,
      args.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER),
    )
  }

  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
    query(
      uri,
      projection,
      Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs?.map { it }?.toTypedArray())
      },
      null,
    )

  private fun matchClause(column: String, match: Int): String = when (match) {
    MediaStore.MATCH_INCLUDE -> ""
    MediaStore.MATCH_ONLY -> "$column = 1"
    else -> "$column = 0"
  }

  override fun insert(uri: Uri, values: ContentValues?): Uri? {
    val v = values ?: return null
    beforeInsert?.let { hook ->
      beforeInsert = null
      hook()
    }
    val type = when (uri.pathSegments.getOrNull(1)) {
      "images" -> 1
      "video" -> 3
      else -> 0
    }
    val path = v.getAsString("relative_path") ?: "Pictures/"
    val name = unique(path, v.getAsString("_display_name") ?: "file")
    val id = nextId++
    files[id] = File.createTempFile("w-$id-", ".bin").apply { deleteOnExit() }
    db.insertOrThrow(
      "files",
      null,
      ContentValues().apply {
        put("_id", id)
        put("volume_name", uri.pathSegments.firstOrNull() ?: "external_primary")
        put("media_type", type)
        put("_display_name", name)
        put("relative_path", path)
        put("mime_type", v.getAsString("mime_type"))
        put("_size", 0L)
        put("is_pending", v.getAsInteger("is_pending") ?: 0)
        put("is_trashed", 0)
      },
    )
    inserted += id to name
    return Uri.parse("content://media/${uri.pathSegments.firstOrNull() ?: "external_primary"}/${uri.pathSegments.getOrNull(1) ?: "file"}/$id")
  }

  /** Come il MediaProvider: un nome occupato (senza distinguere maiuscole, anche in attesa o cestinato) diventa "nome (1).ext". */
  private fun unique(path: String, wanted: String): String {
    fun taken(name: String): Boolean =
      db.rawQuery("SELECT 1 FROM files WHERE relative_path = ? AND _display_name = ? COLLATE NOCASE", arrayOf(path, name)).use { it.moveToFirst() }
    if (!taken(wanted)) return wanted
    val dot = wanted.lastIndexOf('.')
    val base = if (dot > 0) wanted.substring(0, dot) else wanted
    val ext = if (dot > 0) wanted.substring(dot) else ""
    var n = 1
    while (true) {
      val candidate = "$base ($n)$ext"
      if (!taken(candidate)) return candidate
      n++
    }
  }

  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
    if (failUpdates) return 0
    val id = uri.lastPathSegment?.toLongOrNull() ?: return 0
    val v = values ?: return 0
    val set = ContentValues()
    v.getAsInteger("is_pending")?.let { set.put("is_pending", it) }
    if (set.size() == 0) return 0
    if (v.getAsInteger("is_pending") == 0) {
      files[id]?.let { set.put("_size", it.length()) }
    }
    return db.update("files", set, "_id = ?", arrayOf(id.toString()))
  }

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
    if (failDeletes) return 0
    val id = uri.lastPathSegment?.toLongOrNull() ?: return 0
    files.remove(id)?.delete()
    return db.delete("files", "_id = ?", arrayOf(id.toString()))
  }

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    failOpens?.let { throw if (it is FileNotFoundException) it else FileNotFoundException().apply { initCause(it) } }
    val id = uri.lastPathSegment?.toLongOrNull() ?: throw FileNotFoundException("URI senza _ID")
    val file = files[id] ?: throw FileNotFoundException("Riga sparita")
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
  }

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
    "get_version" -> Bundle().apply { putString(Intent.EXTRA_TEXT, versions) }
    else -> null
  }

  override fun getType(uri: Uri): String? = null

  companion object {
    /** Registra il provider per l'autorita' `media` (una sola volta per test). */
    fun register(): WritableMediaProvider {
      val info = ProviderInfo().apply { authority = MediaStore.AUTHORITY }
      return Robolectric.buildContentProvider(WritableMediaProvider::class.java).create(info).get()
    }
  }
}
