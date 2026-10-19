package dev.pampa.mycloudgallery.debugtools.probes

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * SOLO build debug (Fase 5B, P0-B): una riga del telefono nelle cartelle di prova, con le colonne che le prove T-09 e T-16 guardano. Non esce mai
 * nel resoconto: il resoconto ha solo numeri ricavati da queste righe.
 */
internal class ProbeRow(
  val id: Long,
  val isVideo: Boolean,
  val mime: String?,
  val size: Long,
  val dateModifiedSec: Long,
  val dateTakenMs: Long?,
  val generationModified: Long?,
  val isTrashed: Boolean,
  val dateExpiresSec: Long?,
  val relativePath: String,
  val originalDocumentId: String?,
  val documentId: String?,
  val instanceId: String?,
) {
  /** Cio' che identifica "lo stesso file" quando l'`_ID` puo' cambiare: dimensione e data di modifica. */
  val fingerprint: Pair<Long, Long> get() = size to dateModifiedSec

  override fun toString(): String = "ProbeRow($size B)"
}

/**
 * SOLO build debug (Fase 5B, P0-B): la **recinzione** delle prove guidate sul telefono (D13 applicata alle prove T): l'unico punto da cui le prove
 * leggono il MediaStore, e legge **solo** `DCIM/mcg-prova/` e `Pictures/mcg-prova/` (le cartelle che lo script di semina `tools/chaos/s25.ps1 -Mode
 * seed` riempie). Nessuna prova tocca una riga fuori da queste due cartelle: le richieste di sistema (cestino, scrittura) partono da qui e
 * da nessun'altra parte.
 */
internal class FieldProbePhone(context: Context) {

  private val resolver: ContentResolver = context.applicationContext.contentResolver
  private val appContext: Context = context.applicationContext

  /** Le righe delle cartelle di prova, anche quelle nel cestino di sistema; le righe `IS_PENDING` no. Vuoto senza permessi o prima di Android 11. */
  fun rows(): List<ProbeRow> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
    val args = Bundle().apply {
      putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?")
      putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, FOLDERS.toTypedArray())
      putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns._ID} ASC")
      putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
      putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_EXCLUDE)
    }
    val projection = arrayOf(
      MediaStore.MediaColumns._ID,
      MediaStore.MediaColumns.MIME_TYPE,
      MediaStore.MediaColumns.SIZE,
      MediaStore.MediaColumns.DATE_MODIFIED,
      MediaStore.MediaColumns.DATE_TAKEN,
      MediaStore.MediaColumns.GENERATION_MODIFIED,
      MediaStore.MediaColumns.IS_TRASHED,
      MediaStore.MediaColumns.DATE_EXPIRES,
      MediaStore.MediaColumns.RELATIVE_PATH,
      MediaStore.MediaColumns.ORIGINAL_DOCUMENT_ID,
      MediaStore.MediaColumns.DOCUMENT_ID,
      MediaStore.MediaColumns.INSTANCE_ID,
    )
    val out = ArrayList<ProbeRow>()
    val cursor = try {
      resolver.query(MediaStore.Files.getContentUri(VOLUME), projection, args, null)
    } catch (_: SecurityException) {
      null
    } ?: return out
    cursor.use {
      while (it.moveToNext()) {
        val mime = it.getString(1)
        val path = it.getString(8) ?: continue
        if (path !in FOLDERS) continue // la recinzione vale due volte: anche se il provider rispondesse di piu'
        out += ProbeRow(
          id = it.getLong(0),
          isVideo = mime?.lowercase(Locale.ROOT)?.startsWith("video/") == true,
          mime = mime,
          size = it.getLong(2),
          dateModifiedSec = it.getLong(3),
          dateTakenMs = if (it.isNull(4)) null else it.getLong(4),
          generationModified = if (it.isNull(5)) null else it.getLong(5),
          isTrashed = it.getInt(6) == 1,
          dateExpiresSec = if (it.isNull(7)) null else it.getLong(7),
          relativePath = path,
          originalDocumentId = it.getString(9),
          documentId = it.getString(10),
          instanceId = it.getString(11),
        )
      }
    }
    return out
  }

  /** La generazione del volume adesso, o 0. */
  fun generation(): Long = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) MediaStore.getGeneration(appContext, VOLUME) else 0L
  } catch (_: RuntimeException) {
    0L
  }

  /** Il riferimento alla riga, quello che le richieste di sistema e il riscrittore vogliono. */
  fun refOf(row: ProbeRow): MediaRef.Phone = MediaRef.Phone(VOLUME, row.id, row.mime)

  /** L'URI `content://media` della riga (la collezione del tipo). */
  fun uriOf(row: ProbeRow): Uri {
    val collection = if (row.isVideo) MediaStore.Video.Media.getContentUri(VOLUME) else MediaStore.Images.Media.getContentUri(VOLUME)
    return ContentUris.withAppendedId(collection, row.id)
  }

  /** I byte della riga: le foto con `setRequireOriginal` (posizione compresa, D9). Lo chiude chi lo usa. */
  @Throws(IOException::class)
  fun open(row: ProbeRow): InputStream {
    val uri = uriOf(row)
    val target = if (row.isVideo) uri else MediaStore.setRequireOriginal(uri)
    return resolver.openInputStream(target) ?: throw IOException("Nessun flusso.")
  }

  companion object {
    const val VOLUME: String = "external_primary"

    /** Le due cartelle di prova (D13, `DebugBackupFence`): le sole che le prove toccano. */
    val FOLDERS: List<String> = listOf("DCIM/mcg-prova/", "Pictures/mcg-prova/")
  }
}
