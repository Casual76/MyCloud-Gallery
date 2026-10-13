package dev.pampa.mycloudgallery.bench

import android.content.Context
import android.provider.MediaStore

/**
 * Un media vero del dispositivo, come lo descrive il MediaStore: serve a dare alle righe sintetiche un file che si
 * decodifica davvero (§2.6: "puntano a rotazione ai media reali del dispositivo, decodifica realistica, tutto locale").
 * Niente nome o percorso nei log o nello stato scritto fuori dal processo: sono i file dell'utente.
 */
internal class BenchRealMedia(
  val id: Long,
  val video: Boolean,
  val mime: String,
  val width: Int,
  val height: Int,
  /** Gradi EXIF/MediaStore (0, 90, 180, 270): la proporzione mostrata scambia i lati per 90 e 270. */
  val rotation: Int,
  val durationMs: Long,
  val size: Long,
  val dateTakenMs: Long,
  val name: String,
) {
  /** Megapixel della decodifica a tutta risoluzione (serve a riconoscere la foto da 200 MP del test di zoom). */
  val megapixels: Double get() = width.toDouble() * height.toDouble() / 1_000_000.0
}

/**
 * Legge i media veri del telefono (immagini e video del volume principale, non in attesa e non nel cestino) dal
 * MediaStore. Serve il permesso di lettura dei media: senza, [load] lancia `SecurityException` e l'inizializzatore
 * scrive lo stato "errore" (il collaudo concede i permessi con `pm grant` prima del primo avvio).
 */
internal object BenchDeviceMedia {

  const val VOLUME: String = MediaStore.VOLUME_EXTERNAL_PRIMARY

  /** Tetto di sicurezza: oltre non serve a variare la decodifica e allungherebbe solo la scrittura. */
  private const val MAX_ROWS = 30_000

  fun load(context: Context): List<BenchRealMedia> {
    val uri = MediaStore.Files.getContentUri(VOLUME)
    val columns = arrayOf(
      MediaStore.Files.FileColumns._ID,
      MediaStore.Files.FileColumns.MEDIA_TYPE,
      MediaStore.MediaColumns.MIME_TYPE,
      MediaStore.MediaColumns.WIDTH,
      MediaStore.MediaColumns.HEIGHT,
      MediaStore.MediaColumns.ORIENTATION,
      MediaStore.MediaColumns.DURATION,
      MediaStore.MediaColumns.SIZE,
      MediaStore.MediaColumns.DATE_TAKEN,
      MediaStore.MediaColumns.DATE_ADDED,
      MediaStore.MediaColumns.DISPLAY_NAME,
    )
    val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR " +
      "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND " +
      "${MediaStore.MediaColumns.IS_PENDING} = 0 AND ${MediaStore.MediaColumns.SIZE} > 0"
    val found = ArrayList<BenchRealMedia>()
    context.contentResolver.query(uri, columns, selection, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
      val iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
      val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
      val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
      val iW = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
      val iH = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
      val iO = c.getColumnIndexOrThrow(MediaStore.MediaColumns.ORIENTATION)
      val iD = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
      val iS = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
      val iTaken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
      val iAdded = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
      val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
      while (c.moveToNext() && found.size < MAX_ROWS) {
        val video = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        val mime = c.getString(iMime) ?: continue
        val taken = c.getLong(iTaken).takeIf { it > 0 } ?: (c.getLong(iAdded) * 1000L)
        found += BenchRealMedia(
          id = c.getLong(iId),
          video = video,
          mime = mime,
          width = c.getInt(iW).takeIf { it > 0 } ?: DEFAULT_WIDTH,
          height = c.getInt(iH).takeIf { it > 0 } ?: DEFAULT_HEIGHT,
          rotation = c.getInt(iO),
          durationMs = if (video) c.getLong(iD).coerceAtLeast(0) else 0L,
          size = c.getLong(iS),
          dateTakenMs = taken,
          name = c.getString(iName) ?: "media",
        )
      }
    }
    return found
  }

  /** La versione del MediaStore del volume: gli `_ID` valgono solo con questa (E-P22), e il cursore del catalogo la porta. */
  fun version(context: Context): String = try {
    MediaStore.getVersion(context, VOLUME)
  } catch (e: Exception) {
    "unknown"
  }

  private const val DEFAULT_WIDTH = 4000
  private const val DEFAULT_HEIGHT = 3000
}
