package dev.pampa.mycloudgallery.core.mediastore.android

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import android.provider.MediaStore
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dev.pampa.mycloudgallery.core.mediastore.contract.DeletedRow
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreSource
import dev.pampa.mycloudgallery.core.mediastore.contract.RowType
import dev.pampa.mycloudgallery.core.mediastore.sync.MediaStoreRowLookup
import dev.pampa.mycloudgallery.core.mediastore.sync.OriginalBytesUnavailableException
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.isValidMediaStoreVolume
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * Il MediaStore vero, **sottile**: traduce [MediaStoreSource] in `ContentResolver.query` con `Bundle`, `call` per versione e
 * generazione (`MediaStore.getVersion`, `getGeneration`) e `openFileDescriptor`. Nessuna logica di sincronizzazione qui.
 *
 * - **Una sola collezione** (`MediaStore.Files`, `media_type` immagine o video), cosi' foto e video arrivano in un solo
 *   ordine, dai piu' recenti: `datetaken DESC, _id DESC`. La ripresa dopo una riga ([query] con `afterId`) e' a chiave
 *   (`datetaken`, `_id`): non salta e non ripete righe anche se nel frattempo ne arrivano altre. Se la riga di ripresa
 *   non esiste piu' si riparte dall'inizio (le righe gia' fatte passano dalla scorciatoia del timbro).
 * - `IS_PENDING` e cestinate escluse (`QUERY_ARG_MATCH_*`, su API 29 `is_pending = 0`).
 * - Un cursore **nullo** e' un errore (`IOException`), mai "nessuna riga": un MediaStore che non risponde non deve far
 *   dedurre assenze.
 * - [deletedSince]: `content://media/<volume>/deleted_files` (URI letterale, estensione S ≥ 23) **piu'** le righe finite
 *   nel cestino di sistema dopo la generazione: entrambe non sono piu' visibili. Null su API 29.
 * - [open]: le foto solo con `ACCESS_MEDIA_LOCATION` e `setRequireOriginal` (D9); senza, [OriginalBytesUnavailableException]
 *   invece di byte oscurati. Con il permesso anche i video si leggono originali.
 * - [changes]: `ContentObserver` su immagini e video di tutti i volumi, con i discendenti, accorpato a [changeWindow].
 *
 * @param volumeNames i volumi montati (di default `MediaStore.getExternalVolumeNames`); i test lo sostituiscono.
 * @param deletedFilesSupported se `deleted_files` esiste (estensione S ≥ 23, docs/android.md tema 4).
 */
class AndroidMediaStoreSource(
  context: Context,
  private val volumeNames: () -> Set<String> = { MediaStore.getExternalVolumeNames(context.applicationContext) },
  private val deletedFilesSupported: () -> Boolean = { hasDeletedFilesApi() },
  private val hasMediaLocation: () -> Boolean = {
    ContextCompat.checkSelfPermission(context.applicationContext, Manifest.permission.ACCESS_MEDIA_LOCATION) ==
      PackageManager.PERMISSION_GRANTED
  },
  private val changeWindow: Duration = 1500.milliseconds,
  private val visibilityChanged: () -> Unit = {},
) : MediaStoreSource, MediaStoreRowLookup {

  private val appContext = context.applicationContext
  private val resolver: ContentResolver = appContext.contentResolver

  /** La chiave d'ordine (`datetaken`) delle ultime righe restituite: la ripresa nello stesso processo non interroga. */
  private val lastKeys = object : LinkedHashMap<String, Long?>(KEY_CACHE, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long?>?): Boolean = size > KEY_CACHE
  }

  override suspend fun volumes(): Set<String> = volumeNames().filter(::isValidMediaStoreVolume).toSet()

  override suspend fun version(volume: String): String {
    requireVolume(volume)
    val version: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      MediaStore.getVersion(appContext, volume)
    } else {
      MediaStore.getVersion(appContext)
    }
    return version ?: throw IOException("Versione del MediaStore non disponibile (volume non montato?).")
  }

  override suspend fun generation(volume: String): Long? {
    requireVolume(volume)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) MediaStore.getGeneration(appContext, volume) else null
  }

  override suspend fun query(volume: String, sinceGeneration: Long?, afterId: Long?, limit: Int): List<MediaStoreRow> {
    requireVolume(volume)
    val selection = StringBuilder(BASE_SELECTION)
    val args = ArrayList<String>()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) selection.append(" AND ").append(COL_IS_PENDING).append(" = 0")
    if (sinceGeneration != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      selection.append(" AND ").append(COL_GENERATION_MODIFIED).append(" > ?")
      args += sinceGeneration.toString()
    }
    if (afterId != null) {
      val known = keyOf(volume, afterId)
      if (known != null) {
        val taken = known.taken
        if (taken != null) {
          selection.append(" AND ($COL_DATE_TAKEN < ? OR ($COL_DATE_TAKEN = ? AND $COL_ID < ?) OR $COL_DATE_TAKEN IS NULL)")
          args += taken.toString()
          args += taken.toString()
          args += afterId.toString()
        } else {
          selection.append(" AND $COL_DATE_TAKEN IS NULL AND $COL_ID < ?")
          args += afterId.toString()
        }
      }
    }
    val rows = queryRows(volume, selection.toString(), args, SORT_ORDER, limit, matchTrashed = MATCH_EXCLUDE_VALUE)
    rows.lastOrNull()?.let { remember(it) }
    return rows
  }

  override suspend fun rowsById(volume: String, ids: Collection<Long>): List<MediaStoreRow> {
    requireVolume(volume)
    val out = ArrayList<MediaStoreRow>()
    for (chunk in ids.distinct().chunked(MAX_IDS_PER_QUERY)) {
      val placeholders = chunk.joinToString(",") { "?" }
      val selection = StringBuilder(BASE_SELECTION).append(" AND $COL_ID IN ($placeholders)")
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) selection.append(" AND ").append(COL_IS_PENDING).append(" = 0")
      out += queryRows(volume, selection.toString(), chunk.map { it.toString() }, null, null, matchTrashed = MATCH_EXCLUDE_VALUE)
    }
    return out
  }

  override suspend fun deletedSince(volume: String, generation: Long): List<DeletedRow>? {
    requireVolume(volume)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val out = ArrayList<DeletedRow>()
    var answered = false
    if (deletedFilesSupported()) {
      try {
        out += queryDeletedFiles(volume, generation)
        answered = true
      } catch (e: SecurityException) {
        throw e
      } catch (e: Exception) {
        // Estensione presente ma tabella non interrogabile: resta la riconciliazione quotidiana.
      }
    }
    try {
      out += queryTrashedSince(volume, generation)
      answered = true
    } catch (e: SecurityException) {
      throw e
    } catch (e: Exception) {
      // idem
    }
    return if (answered) out else null
  }

  override suspend fun allIds(volume: String): LongArray {
    requireVolume(volume)
    val selection = StringBuilder(BASE_SELECTION)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) selection.append(" AND ").append(COL_IS_PENDING).append(" = 0")
    val cursor = resolver.query(filesUri(volume), arrayOf(COL_ID), queryArgs(selection.toString(), emptyList(), null, null, MATCH_EXCLUDE_VALUE), null)
      ?: throw IOException("Il MediaStore non ha risposto.")
    cursor.use { c ->
      val out = LongArray(c.count)
      var n = 0
      val idIndex = c.getColumnIndexOrThrow(COL_ID)
      while (c.moveToNext()) {
        val id = c.getLong(idIndex)
        if (id > 0) out[n++] = id
      }
      return if (n == out.size) out else out.copyOf(n)
    }
  }

  override suspend fun open(row: MediaStoreRow): MediaBytes {
    requireVolume(row.volume)
    val uri = itemUri(row.volume, row.id, row.type)
    val original = hasMediaLocation()
    if (row.type == RowType.IMAGE && !original) throw OriginalBytesUnavailableException()
    val target = if (original) MediaStore.setRequireOriginal(uri) else uri
    return try {
      ContentUriMediaBytes.open(resolver, target)
    } catch (e: UnsupportedOperationException) {
      // Il permesso della posizione e' sparito fra il controllo e l'apertura.
      throw OriginalBytesUnavailableException()
    } catch (e: SecurityException) {
      if (row.type == RowType.IMAGE && !hasMediaLocation()) throw OriginalBytesUnavailableException()
      throw e
    }
  }

  override fun changes(): Flow<Unit> = callbackFlow {
    val observer = object : ContentObserver(null) {
      override fun onChange(selfChange: Boolean) {
        visibilityChanged()
        trySend(Unit)
      }
    }
    resolver.registerContentObserver(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer)
    resolver.registerContentObserver(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer)
    awaitClose { resolver.unregisterContentObserver(observer) }
  }.coalesce(changeWindow)

  // --- Interni -----------------------------------------------------------------------------------------

  private class ScanKey(val taken: Long?)

  /**
   * Ricorda la chiave d'ordine dell'ultima riga data. **Solo se ha una data**: la riga la normalizza (`datetaken` 0 o negativo
   * diventa null), ma per la ripresa 0 e NULL sono due cose diverse (0 viene prima dei NULL nell'ordine decrescente). Con null non si
   * sa quale delle due fosse: non si ricorda, e [keyOf] la rilegge dal MediaStore con il valore vero (GA-5).
   */
  private fun remember(row: MediaStoreRow) {
    synchronized(lastKeys) {
      val name = keyName(row.volume, row.id)
      val taken = row.dateTakenMs
      if (taken != null) lastKeys[name] = taken else lastKeys.remove(name)
    }
  }

  /** La chiave d'ordine della riga [id]: dalla memoria, o dal MediaStore (anche se nel frattempo e' stata cestinata). */
  private fun keyOf(volume: String, id: Long): ScanKey? {
    synchronized(lastKeys) {
      if (lastKeys.containsKey(keyName(volume, id))) return ScanKey(lastKeys[keyName(volume, id)])
    }
    val args = queryArgs("$COL_ID = ?", listOf(id.toString()), null, null, MATCH_INCLUDE_VALUE, matchPending = MATCH_INCLUDE_VALUE)
    val cursor = resolver.query(filesUri(volume), arrayOf(COL_ID, COL_DATE_TAKEN), args, null) ?: return null
    cursor.use { c ->
      if (!c.moveToFirst()) return null
      val index = c.getColumnIndexOrThrow(COL_DATE_TAKEN)
      return ScanKey(if (c.isNull(index)) null else c.getLong(index))
    }
  }

  private fun queryRows(
    volume: String,
    selection: String,
    args: List<String>,
    sort: String?,
    limit: Int?,
    matchTrashed: Int,
  ): List<MediaStoreRow> {
    // Il limite: con il Bundle da Android 11; su Android 10 solo come parametro dell'URI. Mai entrambi: il MediaProvider
    // rifiuta un limite "abusivo" nell'URI insieme a quello richiesto (provato sull'emulatore, API 37).
    val legacyLimit = limit != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.R
    val uri = if (legacyLimit) filesUri(volume).buildUpon().appendQueryParameter(PARAM_LIMIT, limit.toString()).build() else filesUri(volume)
    val cursor = resolver.query(uri, projection(), queryArgs(selection, args, sort, if (legacyLimit) null else limit, matchTrashed), null)
      ?: throw IOException("Il MediaStore non ha risposto.")
    cursor.use { c ->
      val columns = Columns(c)
      val out = ArrayList<MediaStoreRow>(minOf(c.count, limit ?: c.count).coerceAtLeast(0))
      while (c.moveToNext()) {
        if (limit != null && out.size >= limit) break
        columns.read(c, volume)?.let { out += it }
      }
      return out
    }
  }

  private fun queryDeletedFiles(volume: String, generation: Long): List<DeletedRow> {
    val uri = "content://${MediaStore.AUTHORITY}/$volume/$DELETED_FILES".toUri()
    val args = Bundle().apply {
      putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "$COL_DELETED_GENERATION > ?")
      putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(generation.toString()))
    }
    val cursor = resolver.query(uri, null, args, null) ?: throw IOException("deleted_files non ha risposto.")
    cursor.use { c ->
      val idIndex = c.getColumnIndex(COL_ID)
      val genIndex = c.getColumnIndex(COL_DELETED_GENERATION)
      val typeIndex = c.getColumnIndex(COL_MEDIA_TYPE)
      if (idIndex < 0 || genIndex < 0) throw IOException("deleted_files senza le colonne attese.")
      val out = ArrayList<DeletedRow>(c.count)
      while (c.moveToNext()) {
        val id = c.getLong(idIndex)
        if (id <= 0) continue
        if (typeIndex >= 0 && !c.isNull(typeIndex)) {
          val type = c.getInt(typeIndex)
          if (type != MEDIA_TYPE_IMAGE && type != MEDIA_TYPE_VIDEO) continue
        }
        out += DeletedRow(volume, id, if (c.isNull(genIndex)) null else c.getLong(genIndex))
      }
      return out
    }
  }

  private fun queryTrashedSince(volume: String, generation: Long): List<DeletedRow> {
    val args = queryArgs(
      "$BASE_SELECTION AND $COL_GENERATION_MODIFIED > ?",
      listOf(generation.toString()),
      null,
      null,
      MATCH_ONLY_VALUE,
    )
    val cursor = resolver.query(filesUri(volume), arrayOf(COL_ID, COL_GENERATION_MODIFIED), args, null)
      ?: throw IOException("Il MediaStore non ha risposto.")
    cursor.use { c ->
      val idIndex = c.getColumnIndexOrThrow(COL_ID)
      val genIndex = c.getColumnIndexOrThrow(COL_GENERATION_MODIFIED)
      val out = ArrayList<DeletedRow>(c.count)
      while (c.moveToNext()) {
        val id = c.getLong(idIndex)
        if (id > 0) out += DeletedRow(volume, id, if (c.isNull(genIndex)) null else c.getLong(genIndex))
      }
      return out
    }
  }

  private fun queryArgs(
    selection: String,
    args: List<String>,
    sort: String?,
    limit: Int?,
    matchTrashed: Int,
    matchPending: Int = MATCH_EXCLUDE_VALUE,
  ): Bundle = Bundle().apply {
    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
    if (sort != null) putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
    if (limit != null) putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      putInt(MediaStore.QUERY_ARG_MATCH_PENDING, matchPending)
      putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, matchTrashed)
      putInt(MediaStore.QUERY_ARG_MATCH_FAVORITE, MediaStore.MATCH_INCLUDE)
    }
  }

  private fun projection(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) PROJECTION + COL_GENERATION_MODIFIED else PROJECTION

  /** Gli indici delle colonne, letti una volta per cursore. */
  private class Columns(c: Cursor) {
    private val id = c.getColumnIndexOrThrow(COL_ID)
    private val mediaType = c.getColumnIndexOrThrow(COL_MEDIA_TYPE)
    private val name = c.getColumnIndexOrThrow(COL_DISPLAY_NAME)
    private val path = c.getColumnIndexOrThrow(COL_RELATIVE_PATH)
    private val mime = c.getColumnIndexOrThrow(COL_MIME_TYPE)
    private val size = c.getColumnIndexOrThrow(COL_SIZE)
    private val modified = c.getColumnIndexOrThrow(COL_DATE_MODIFIED)
    private val taken = c.getColumnIndexOrThrow(COL_DATE_TAKEN)
    private val width = c.getColumnIndexOrThrow(COL_WIDTH)
    private val height = c.getColumnIndexOrThrow(COL_HEIGHT)
    private val orientation = c.getColumnIndexOrThrow(COL_ORIENTATION)
    private val duration = c.getColumnIndexOrThrow(COL_DURATION)
    private val owner = c.getColumnIndexOrThrow(COL_OWNER_PACKAGE_NAME)
    private val generation = c.getColumnIndex(COL_GENERATION_MODIFIED)

    fun read(c: Cursor, volume: String): MediaStoreRow? {
      val rowId = c.getLong(id)
      if (rowId <= 0) return null
      val type = when (c.getInt(mediaType)) {
        MEDIA_TYPE_IMAGE -> RowType.IMAGE
        MEDIA_TYPE_VIDEO -> RowType.VIDEO
        else -> return null
      }
      val displayName = c.getString(name) ?: return null
      return MediaStoreRow(
        volume = volume,
        id = rowId,
        type = type,
        displayName = displayName,
        relativePath = c.getString(path) ?: "",
        mime = c.getString(mime),
        size = c.longOrNull(size)?.coerceAtLeast(0) ?: 0L,
        // DATE_MODIFIED e' in secondi.
        dateModifiedMs = (c.longOrNull(modified) ?: 0L) * 1000L,
        dateTakenMs = c.longOrNull(taken)?.takeIf { it > 0 },
        width = c.intOrNull(width)?.takeIf { it > 0 },
        height = c.intOrNull(height)?.takeIf { it > 0 },
        orientation = c.intOrNull(orientation),
        durationMs = c.longOrNull(duration)?.takeIf { it >= 0 },
        generationModified = if (generation >= 0) c.longOrNull(generation) else null,
        ownerPackage = c.getString(owner),
      )
    }

    private fun Cursor.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)

    private fun Cursor.intOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)
  }

  companion object {
    /** Le colonne del MediaStore che la Libreria usa (nomi fissi della piattaforma). */
    const val COL_ID = "_id"
    const val COL_MEDIA_TYPE = "media_type"
    const val COL_DISPLAY_NAME = "_display_name"
    const val COL_RELATIVE_PATH = "relative_path"
    const val COL_MIME_TYPE = "mime_type"
    const val COL_SIZE = "_size"
    const val COL_DATE_MODIFIED = "date_modified"
    const val COL_DATE_TAKEN = "datetaken"
    const val COL_WIDTH = "width"
    const val COL_HEIGHT = "height"
    const val COL_ORIENTATION = "orientation"
    const val COL_DURATION = "duration"
    const val COL_OWNER_PACKAGE_NAME = "owner_package_name"
    const val COL_GENERATION_MODIFIED = "generation_modified"
    const val COL_IS_PENDING = "is_pending"
    const val COL_IS_TRASHED = "is_trashed"
    const val COL_DATE_EXPIRES = "date_expires"
    const val COL_VOLUME_NAME = "volume_name"
    const val COL_DELETED_GENERATION = "deleted_generation"
    const val DELETED_FILES = "deleted_files"

    const val MEDIA_TYPE_IMAGE = 1
    const val MEDIA_TYPE_VIDEO = 3

    /** Solo immagini e video. */
    const val BASE_SELECTION = "$COL_MEDIA_TYPE IN ($MEDIA_TYPE_IMAGE,$MEDIA_TYPE_VIDEO)"

    /** Dalle piu' recenti; a parita' di data, l'`_ID` piu' alto prima (le righe senza data in fondo). */
    const val SORT_ORDER = "$COL_DATE_TAKEN DESC, $COL_ID DESC"

    /** Il parametro `limit` dell'URI (nascosto, `MediaStore.PARAM_LIMIT`): solo su API 29, dove `QUERY_ARG_LIMIT` non basta. */
    private const val PARAM_LIMIT = "limit"

    // I valori di MediaStore.MATCH_* (API 30), scritti qui per non dipendere dal livello di API.
    internal const val MATCH_EXCLUDE_VALUE = 2
    internal const val MATCH_INCLUDE_VALUE = 1
    internal const val MATCH_ONLY_VALUE = 3

    private const val MAX_IDS_PER_QUERY = 400
    private const val KEY_CACHE = 64

    private val PROJECTION = arrayOf(
      COL_ID, COL_MEDIA_TYPE, COL_DISPLAY_NAME, COL_RELATIVE_PATH, COL_MIME_TYPE, COL_SIZE, COL_DATE_MODIFIED,
      COL_DATE_TAKEN, COL_WIDTH, COL_HEIGHT, COL_ORIENTATION, COL_DURATION, COL_OWNER_PACKAGE_NAME,
    )

    private fun keyName(volume: String, id: Long): String = "$volume/$id"

    private fun requireVolume(volume: String) {
      require(isValidMediaStoreVolume(volume)) { "Nome di volume MediaStore non valido." }
    }

    internal fun filesUri(volume: String): Uri = MediaStore.Files.getContentUri(volume)

    /** L'URI della riga nella collezione del suo tipo (quello che si condivide e si apre). */
    internal fun itemUri(volume: String, id: Long, type: RowType): Uri {
      val collection = if (type == RowType.VIDEO) MediaStore.Video.Media.getContentUri(volume) else MediaStore.Images.Media.getContentUri(volume)
      return ContentUris.withAppendedId(collection, id)
    }

    /** `deleted_files` esiste con l'estensione S ≥ 23 (docs/android.md: "37.1 / S Extensions 23"). */
    @ChecksSdkIntAtLeast(api = 23, extension = Build.VERSION_CODES.S)
    fun hasDeletedFilesApi(): Boolean =
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 23
  }
}

/**
 * Accorpa i segnali: al primo aspetta [window] e poi emette **una** volta, qualunque sia il numero di segnali arrivati
 * nel frattempo. A differenza di un `debounce`, una raffica lunga (50 foto) non rimanda all'infinito: un'emissione per
 * finestra, cosi' le modifiche esterne si vedono entro pochi secondi (T-13).
 */
internal fun Flow<Unit>.coalesce(window: Duration): Flow<Unit> = channelFlow {
  val signals = Channel<Unit>(Channel.CONFLATED)
  launch {
    collect { signals.trySend(Unit) }
    signals.close()
  }
  for (signal in signals) {
    delay(window)
    signals.tryReceive()
    send(Unit)
  }
}
