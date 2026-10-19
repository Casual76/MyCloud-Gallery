package dev.pampa.mycloudgallery.debugtools.probes

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
 * Un `ContentProvider` registrato come `media` per provare le prove guidate su Robolectric: le righe stanno in un SQLite in memoria con le colonne
 * vere (comprese `ORIGINAL_DOCUMENT_ID`, `DOCUMENT_ID`, `INSTANCE_ID`), selezione e argomenti si eseguono davvero, e un contenuto per riga in un file
 * temporaneo (apribile in lettura e scrittura). Le azioni "di Samsung Gallery" (cestinare, ripristinare, riscrivere un file) le fa il test.
 */
class ProbeMediaProvider : ContentProvider() {

  private lateinit var db: SQLiteDatabase
  private val files = HashMap<Long, File>()
  private var generation = 100L

  /** Quanti permessi di scrittura ha dato il dialogo (il test li imposta): senza, aprire in scrittura lancia `SecurityException`. */
  var allowWrite: Boolean = true

  /** Le richieste di cestino/scrittura ricevute da `MediaStore.create...Request`. */
  val requests: MutableList<Pair<String, List<Uri>>> = ArrayList()

  /** Le selezioni ricevute, per provare la recinzione. */
  val selections: MutableList<Pair<String?, List<String>>> = ArrayList()

  override fun onCreate(): Boolean {
    db = SQLiteDatabase.create(null)
    db.execSQL(
      "CREATE TABLE files (_id INTEGER PRIMARY KEY, volume_name TEXT, media_type INTEGER, _display_name TEXT, relative_path TEXT, mime_type TEXT, " +
        "_size INTEGER, date_modified INTEGER, datetaken INTEGER, generation_modified INTEGER, is_pending INTEGER NOT NULL DEFAULT 0, " +
        "is_trashed INTEGER NOT NULL DEFAULT 0, date_expires INTEGER, original_document_id TEXT, document_id TEXT, instance_id TEXT)",
    )
    return true
  }

  fun insertRow(
    id: Long,
    path: String,
    mime: String = "image/jpeg",
    content: ByteArray = ByteArray(16) { (it + id).toByte() },
    modifiedSec: Long = 1_760_000_000L,
    taken: Long? = 1_760_000_000_000L + id,
    pending: Boolean = false,
  ): Long {
    val file = File.createTempFile("probe-$id-", ".bin").apply { writeBytes(content); deleteOnExit() }
    files[id] = file
    db.insertOrThrow(
      "files",
      null,
      ContentValues().apply {
        put("_id", id)
        put("volume_name", "external_primary")
        put("media_type", if (mime.startsWith("video/")) 3 else 1)
        put("_display_name", "IMG_$id")
        put("relative_path", path)
        put("mime_type", mime)
        put("_size", content.size.toLong())
        put("date_modified", modifiedSec)
        put("datetaken", taken)
        put("generation_modified", ++generation)
        put("is_pending", if (pending) 1 else 0)
      },
    )
    return id
  }

  fun trashRow(id: Long, expiresSec: Long?) {
    db.update(
      "files",
      ContentValues().apply { put("is_trashed", 1); put("date_expires", expiresSec); put("generation_modified", ++generation) },
      "_id = ?",
      arrayOf(id.toString()),
    )
  }

  fun restoreRow(id: Long) {
    db.update("files", ContentValues().apply { put("is_trashed", 0); putNull("date_expires"); put("generation_modified", ++generation) }, "_id = ?", arrayOf(id.toString()))
  }

  /** Samsung "cestina": la riga sparisce dal MediaStore. */
  fun vanishRow(id: Long) {
    db.delete("files", "_id = ?", arrayOf(id.toString()))
    generation++
  }

  /** Il file torna con un `_ID` nuovo e gli stessi byte, la stessa dimensione e la stessa data di modifica (ripristino dalla Galleria). */
  fun returnWithNewId(oldId: Long, newId: Long, path: String, content: ByteArray, modifiedSec: Long) {
    insertRow(newId, path, content = content, modifiedSec = modifiedSec)
  }

  /** Samsung "Salva": stessa riga, byte nuovi, dimensione e data di modifica nuove. */
  fun overwrite(id: Long, content: ByteArray, modifiedSec: Long) {
    files.getValue(id).writeBytes(content)
    db.update(
      "files",
      ContentValues().apply { put("_size", content.size.toLong()); put("date_modified", modifiedSec); put("generation_modified", ++generation) },
      "_id = ?",
      arrayOf(id.toString()),
    )
  }

  fun setOriginalDocumentId(id: Long, value: String?) {
    db.update("files", ContentValues().apply { put("original_document_id", value); put("document_id", value); put("instance_id", value) }, "_id = ?", arrayOf(id.toString()))
  }

  fun contentOf(id: Long): ByteArray? = files[id]?.readBytes()

  override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor? {
    val args = queryArgs ?: Bundle()
    selections += args.getString(ContentResolver.QUERY_ARG_SQL_SELECTION) to (args.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)?.toList() ?: emptyList())
    val where = ArrayList<String>()
    val whereArgs = ArrayList<String>()
    args.getString(ContentResolver.QUERY_ARG_SQL_SELECTION)?.let { where += "($it)" }
    args.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)?.let { whereArgs += it }
    uri.lastPathSegment?.toLongOrNull()?.let {
      where += "_id = ?"
      whereArgs += it.toString()
    }
    where += clause("is_pending", args.getInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_DEFAULT))
    where += clause("is_trashed", args.getInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_DEFAULT))
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

  private fun clause(column: String, match: Int): String = when (match) {
    MediaStore.MATCH_INCLUDE -> ""
    MediaStore.MATCH_ONLY -> "$column = 1"
    else -> "$column = 0"
  }

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
    "get_generation" -> Bundle().apply { putLong(Intent.EXTRA_INDEX, generation) }
    "get_version" -> Bundle().apply { putString(Intent.EXTRA_TEXT, "v1") }
    else -> null
  }

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    val id = uri.lastPathSegment?.toLongOrNull() ?: throw FileNotFoundException("URI senza _ID")
    val file = files[id] ?: throw FileNotFoundException("Riga sparita")
    if ('w' in mode && !allowWrite) throw SecurityException("Nessun permesso di scrittura.")
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
  }

  override fun getType(uri: Uri): String? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

  companion object {
    fun register(): ProbeMediaProvider {
      val info = ProviderInfo().apply { authority = MediaStore.AUTHORITY }
      return Robolectric.buildContentProvider(ProbeMediaProvider::class.java).create(info).get()
    }
  }
}
