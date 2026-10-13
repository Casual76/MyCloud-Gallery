package dev.pampa.mycloudgallery.core.mediastore.android

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import dev.pampa.mycloudgallery.core.mediastore.android.AndroidMediaStoreSource.Companion.BASE_SELECTION
import dev.pampa.mycloudgallery.core.mediastore.android.AndroidMediaStoreSource.Companion.COL_DATE_EXPIRES
import dev.pampa.mycloudgallery.core.mediastore.android.AndroidMediaStoreSource.Companion.COL_ID
import dev.pampa.mycloudgallery.core.mediastore.android.AndroidMediaStoreSource.Companion.COL_MEDIA_TYPE
import dev.pampa.mycloudgallery.core.mediastore.android.AndroidMediaStoreSource.Companion.COL_MIME_TYPE
import dev.pampa.mycloudgallery.core.mediastore.android.AndroidMediaStoreSource.Companion.COL_VOLUME_NAME
import dev.pampa.mycloudgallery.core.mediastore.android.AndroidMediaStoreSource.Companion.MEDIA_TYPE_VIDEO
import dev.pampa.mycloudgallery.core.mediastore.contract.RecentlyDeletedSource
import dev.pampa.mycloudgallery.core.mediastore.contract.TrashedItem
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.isValidMediaStoreVolume
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Il cestino **di sistema** (`IS_TRASHED`, `QUERY_ARG_MATCH_TRASHED`, Android 11+) per "Eliminati di recente" (D14, D17),
 * su tutti i volumi esterni, dai cestinati piu' di recente. Non e' il cestino di Samsung Gallery, che il sistema non
 * espone (E-P11).
 *
 * `DATE_EXPIRES` (in secondi) e' quando il sistema cancellera' il file; l'istante del cestino non e' una colonna e si
 * ricava come scadenza meno [SYSTEM_TRASH_DAYS] giorni (il valore di AOSP): e' una stima, serve solo all'ordine.
 */
class AndroidRecentlyDeletedSource(context: Context) : RecentlyDeletedSource {

  private val resolver: ContentResolver = context.applicationContext.contentResolver

  override suspend fun list(): List<TrashedItem> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
    val args = Bundle().apply {
      putString(ContentResolver.QUERY_ARG_SQL_SELECTION, BASE_SELECTION)
      putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "$COL_DATE_EXPIRES DESC, $COL_ID DESC")
      putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
    }
    val projection = arrayOf(COL_ID, COL_VOLUME_NAME, COL_MEDIA_TYPE, COL_MIME_TYPE, COL_DATE_EXPIRES)
    val cursor = resolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, args, null)
      ?: throw IOException("Il MediaStore non ha risposto.")
    cursor.use { c ->
      val id = c.getColumnIndexOrThrow(COL_ID)
      val volume = c.getColumnIndexOrThrow(COL_VOLUME_NAME)
      val type = c.getColumnIndexOrThrow(COL_MEDIA_TYPE)
      val mime = c.getColumnIndexOrThrow(COL_MIME_TYPE)
      val expires = c.getColumnIndexOrThrow(COL_DATE_EXPIRES)
      val out = ArrayList<TrashedItem>(c.count)
      while (c.moveToNext()) {
        val rowId = c.getLong(id)
        val volumeName = c.getString(volume)
        if (rowId <= 0 || volumeName == null || !isValidMediaStoreVolume(volumeName)) continue
        val expiresAtMs = if (c.isNull(expires)) 0L else TimeUnit.SECONDS.toMillis(c.getLong(expires))
        out += TrashedItem(
          ref = MediaRef.Phone(volumeName, rowId, c.getString(mime)),
          isVideo = c.getInt(type) == MEDIA_TYPE_VIDEO,
          trashedAtMs = (expiresAtMs - TimeUnit.DAYS.toMillis(SYSTEM_TRASH_DAYS)).coerceAtLeast(0),
          expiresAtMs = expiresAtMs,
        )
      }
      return out
    }
  }

  companion object {
    /** Quanto tiene il cestino di sistema (AOSP); T-09 (e) misura la durata reale. */
    const val SYSTEM_TRASH_DAYS: Long = 30
  }
}
