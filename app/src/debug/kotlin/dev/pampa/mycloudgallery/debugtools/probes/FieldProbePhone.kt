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
 * SOLO build debug (D-3 delle prove sul campo): dove stanno le righe che la prova ha cestinato, solo conteggi. [inFolders] nelle cartelle di
 * prova (al loro posto, anche se cestinate), [inGalleryTrash] nel Cestino di Galleria con lo stesso `_ID`, [elsewhere] in un'altra cartella,
 * [gone] non piu' visibili all'app con quell'`_ID`; [trashed] quante, fra quelle trovate, hanno `IS_TRASHED = 1`.
 */
internal data class ProbeWhere(val inFolders: Int, val inGalleryTrash: Int, val elsewhere: Int, val trashed: Int, val gone: Int) {
  /** Uscite dalle cartelle di prova: nel Cestino di Galleria, altrove o sparite. */
  val outOfFolders: Int get() = inGalleryTrash + elsewhere + gone
}

/**
 * SOLO build debug (Fase 5B, LA-7 della verifica funzionale): una riga di riferimento salvata su file fra un passaggio e l'altro di una prova a
 * piu' tempi. **Solo numeri**: l'`_ID`, la dimensione, la data di modifica, la data di scatto e la generazione, separati da tabulazioni. Quello
 * che T-09 e T-16 confrontano dopo (`FieldProbeMath.diff`, `compareView`) e' proprio questo; il resto della riga si rilegge dal telefono.
 */
internal object ProbeRowMarks {

  fun encode(row: ProbeRow): String =
    listOf(row.id.toString(), row.size.toString(), row.dateModifiedSec.toString(), row.dateTakenMs?.toString().orEmpty(), row.generationModified?.toString().orEmpty())
      .joinToString("\t")

  /** La riga di riferimento di [line], con i soli campi che il confronto usa (gli altri hanno un valore neutro); null se la riga non ha la forma. */
  fun decode(line: String): ProbeRow? {
    val parts = line.split('\t')
    if (parts.size != FIELDS) return null
    val id = parts[0].toLongOrNull() ?: return null
    val size = parts[1].toLongOrNull() ?: return null
    val modified = parts[2].toLongOrNull() ?: return null
    val taken = parts[3].takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: return null }
    val generation = parts[4].takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: return null }
    return ProbeRow(
      id = id, isVideo = false, mime = null, size = size, dateModifiedSec = modified, dateTakenMs = taken, generationModified = generation,
      isTrashed = false, dateExpiresSec = null, relativePath = "", originalDocumentId = null, documentId = null, instanceId = null,
    )
  }

  private const val FIELDS = 5
}

/**
 * SOLO build debug (Fase 5B, P0-B): la **recinzione** delle prove guidate sul telefono (D13 applicata alle prove T): l'unico punto da cui le prove
 * leggono il MediaStore, e legge **solo** `DCIM/mcg-prova/` e `Pictures/mcg-prova/` (le cartelle che lo script di semina `tools/chaos/s25.ps1 -Mode
 * backup -Step seed` riempie). Nessuna prova tocca una riga fuori da queste due cartelle: le richieste di sistema (cestino, scrittura) partono da
 * qui e da nessun'altra parte. L'unica lettura fuori dalle cartelle e' [whereAre]: per `_ID`, solo delle righe che la prova stessa ha preso da
 * qui e mandato nel cestino, e ne restituisce solo dove stanno a grandi linee (D-3), mai un percorso.
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

  /**
   * D-3 delle prove sul campo: dove stanno adesso le righe [ids] che la prova ha cestinato (prese dalle cartelle di prova), cercate per `_ID` in
   * tutto il volume, anche cestinate, anche fuori dai media: nelle cartelle di prova, nel Cestino di Galleria (`Android/.Trash/`), altrove, o
   * sparite per l'app. Solo i conteggi escono di qui: nessun percorso, nessun nome.
   */
  fun whereAre(ids: Collection<Long>): ProbeWhere {
    if (ids.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ProbeWhere(0, 0, 0, 0, 0)
    val wanted = ids.distinct().take(MAX_WHERE)
    val args = Bundle().apply {
      putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns._ID} IN (${wanted.joinToString(",") { "?" }})")
      putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, wanted.map { it.toString() }.toTypedArray())
      putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
      putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
    }
    val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED, MediaStore.MediaColumns.RELATIVE_PATH)
    var inFolders = 0
    var inGalleryTrash = 0
    var elsewhere = 0
    var trashed = 0
    val cursor = try {
      resolver.query(MediaStore.Files.getContentUri(VOLUME), projection, args, null)
    } catch (_: SecurityException) {
      null
    } catch (_: IllegalArgumentException) {
      null
    }
    cursor?.use {
      while (it.moveToNext()) {
        val path = it.getString(2).orEmpty()
        if (it.getInt(1) == 1) trashed++
        when {
          path in FOLDERS -> inFolders++
          path.startsWith(GALLERY_TRASH_PREFIX, ignoreCase = true) -> inGalleryTrash++
          else -> elsewhere++
        }
      }
    }
    val found = inFolders + inGalleryTrash + elsewhere
    return ProbeWhere(inFolders = inFolders, inGalleryTrash = inGalleryTrash, elsewhere = elsewhere, trashed = trashed, gone = wanted.size - found)
  }

  /** La generazione del volume adesso, o 0. */
  fun generation(): Long = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) MediaStore.getGeneration(appContext, VOLUME) else 0L
  } catch (_: RuntimeException) {
    0L
  }

  /** Il riferimento alla riga, quello che le richieste di sistema e il riscrittore vogliono. */
  fun refOf(row: ProbeRow): MediaRef.Phone = MediaRef.Phone(VOLUME, row.id, row.mime)

  /** VE-2: il riferimento **senza MIME**, come quello con cui i fogli della 5B chiedono il dialogo di sistema (`AppPhoneSystemDialogs`). */
  fun untypedRefOf(row: ProbeRow): MediaRef.Phone = MediaRef.Phone(VOLUME, row.id, null)

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

    /** Il Cestino di Samsung Gallery e degli altri cestini di One UI (`Android/.Trash/<pacchetto>/`). */
    const val GALLERY_TRASH_PREFIX: String = "Android/.Trash/"

    /** Quante righe al massimo si cercano per `_ID` (le variabili di una query SQLite). */
    private const val MAX_WHERE = 900
  }
}
