package dev.pampa.mycloudgallery.core.library.membership

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.library.contract.FilterExtras
import dev.pampa.mycloudgallery.core.library.contract.FilterMembership
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext

/**
 * Fase grafica (G2b): le correzioni di Fotocamera e Messaggistica lette dal catalogo. Sola lettura, SQL su tabelle stabili (`media`,
 * `media_version`, `media_location`), come `VisibilityQueries`: nessuna colonna nuova, nessuna migrazione.
 *
 * - **Fotocamera** ([LibraryFilter.CAMERA]): `minus` = i media `camera` il cui dispositivo d'origine (`media.origin_device`, §14.3 del modello
 *   dei dati) non e' questo: ne' `phone` (una copia sul telefono) ne' `backup:<la cartella di questo dispositivo>` (il media lasciato solo sul NAS
 *   da "Libera spazio"). In pratica i file del NAS in una cartella `DCIM/Camera/` copiata a mano: il backup non ne scrive. Senza la cartella di
 *   questo dispositivo (backup mai configurato, impostazioni non ancora lette) un media `camera` solo sul NAS non si puo' attribuire: resta fuori.
 * - **Messaggistica** ([LibraryFilter.MESSAGING]): `plus` = i media con una posizione in una cartella di Telegram o di Signal
 *   ([MessagingFolders]), sul telefono (anche se la copia non c'e' piu') o nella copia del backup.
 *
 * Si ricalcola quando l'indice cambia, **al piu' una volta ogni [settleMs]** (la prima subito): durante una sincronizzazione lunga l'indice si
 * ripubblica spesso, e una lettura ogni volta peserebbe. Sempre fuori dal thread principale ([io]). Un errore del database lascia la vista com'e'
 * la dice la cella (correzione vuota), non la blocca.
 *
 * @param ownBackupFolder la cartella del backup di questo dispositivo (`DeviceFolder.name`, con o senza `Backup/`), o null se non c'e'.
 */
class CatalogFilterMembership(
  private val database: GalleryDatabase,
  private val snapshots: Flow<TimelineSnapshot?>,
  private val ownBackupFolder: Flow<String?>,
  private val io: CoroutineDispatcher = Dispatchers.IO,
  private val settleMs: Long = DEFAULT_SETTLE_MS,
) : FilterMembership {

  override fun extras(filter: LibraryFilter): Flow<FilterExtras> = when (filter) {
    LibraryFilter.CAMERA -> throttled(combine(changes(), ownBackupFolder.map { originLabel(it) }.distinctUntilChanged()) { _, origin -> origin }) { origin ->
      FilterExtras(minus = cameraNotFromHere(origin))
    }
    LibraryFilter.MESSAGING -> throttled(changes()) { FilterExtras(plus = messagingMedia()) }
    else -> flowOf(FilterExtras.NONE)
  }

  /** La sequenza dell'indice: cambia a ogni pubblicazione. */
  private fun changes(): Flow<Long> = snapshots.filterNotNull().map { it.seq }.distinctUntilChanged()

  private fun <T> throttled(trigger: Flow<T>, read: (T) -> FilterExtras): Flow<FilterExtras> =
    trigger.conflate()
      .map { value ->
        try {
          withContext(io) { read(value) }
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          FilterExtras.NONE
        }
      }
      // La prima subito, poi al piu' una ogni [settleMs] (FAB-11): l'attesa viene **dopo** l'emissione. Con `onEach` cadeva prima, e anche la prima correzione arrivava dopo 2,5 s.
      // Mentre il flusso aspetta, `conflate()` tiene solo l'ultimo cambiamento dell'indice.
      .transform { value ->
        emit(value)
        delay(settleMs)
      }

  private fun db(): SupportSQLiteDatabase = database.openHelper.readableDatabase

  /** I media `camera` che non vengono da questo dispositivo ([own] = `backup:<cartella>`, o null). */
  internal fun cameraNotFromHere(own: String?): Set<MediaId> {
    val query = SimpleSQLiteQuery(CAMERA_SQL, arrayOf<Any?>(CAMERA_SOURCE, PHONE_ORIGIN, own ?: PHONE_ORIGIN))
    val out = HashSet<MediaId>()
    db().query(query).use { c ->
      while (c.moveToNext()) out += MediaId(c.getString(0))
    }
    return out
  }

  /** I media con una posizione in una cartella di Telegram o di Signal. */
  internal fun messagingMedia(): Set<MediaId> {
    val out = HashSet<MediaId>()
    db().query(SimpleSQLiteQuery(MESSAGING_SQL)).use { c ->
      while (c.moveToNext()) {
        val media = c.getString(0)
        val matches = when (c.getString(1)) {
          PHONE_TYPE -> MessagingFolders.isPhoneFolder(c.getString(2))
          NAS_TYPE -> MessagingFolders.isBackupFile(c.getString(3))
          else -> false
        }
        if (matches) out += MediaId(media)
      }
    }
    return out
  }

  companion object {
    const val DEFAULT_SETTLE_MS: Long = 2_500L

    private const val CAMERA_SOURCE = "camera"
    private const val PHONE_ORIGIN = "phone"
    private const val PHONE_TYPE = "phone"
    private const val NAS_TYPE = "nas"
    private const val BACKUP_PREFIX = "backup:"

    /** Usa l'indice (`source`, `sort_time`) della tabella `media`. */
    internal const val CAMERA_SQL: String =
      "SELECT media_id FROM media WHERE source = ? AND (origin_device IS NULL OR origin_device NOT IN (?, ?))"

    /** Il filtro grezzo (`LIKE` non distingue le maiuscole in ASCII); la regola vera e' [MessagingFolders]. */
    internal val MESSAGING_SQL: String = run {
      val phone = MessagingFolders.CATALOG_HINTS.joinToString(" OR ") { "l.relative_path LIKE '%$it%'" }
      val nas = MessagingFolders.CATALOG_HINTS.joinToString(" OR ") { "l.nas_path LIKE '%$it%'" }
      "SELECT v.media_id, l.location_type, l.relative_path, l.nas_path FROM media_location l " +
        "JOIN media_version v ON v.version_id = l.version_id " +
        "WHERE (l.location_type = '$PHONE_TYPE' AND ($phone)) OR (l.location_type = '$NAS_TYPE' AND ($nas))"
    }

    /**
     * L'etichetta `origin_device` della cartella del backup di questo dispositivo: `backup:` e la cartella in forma di confronto (NFC, minuscolo,
     * senza `Backup/`), come `OriginDevice.ofNasKey` e `DeviceNormalizer`. Null senza cartella.
     */
    fun originLabel(folder: String?): String? {
      val trimmed = folder?.trim()?.trim('/') ?: return null
      val name = if (trimmed.startsWith("Backup/", ignoreCase = true)) trimmed.substring("Backup/".length).trim('/') else trimmed
      if (name.isEmpty()) return null
      return BACKUP_PREFIX + Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }
  }
}
