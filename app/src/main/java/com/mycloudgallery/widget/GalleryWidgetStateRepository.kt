package com.mycloudgallery.widget

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Legge e scrive lo stato del widget nel DataStore dedicato.
 * Usato da [GalleryWidget] per rendere il contenuto e da [SyncWorker] per aggiornare i dati.
 */
object GalleryWidgetStateRepository {

    private val Context.widgetDataStore by preferencesDataStore(name = "gallery_widget_state")

    private val KEY_LAST_SYNC = longPreferencesKey("last_sync_ts")
    private val KEY_PENDING_UPLOADS = intPreferencesKey("pending_uploads")

    /** Legge lo stato corrente per il widget. */
    suspend fun getState(context: Context): WidgetState {
        val prefs = context.widgetDataStore.data.first()
        val lastSyncTs = prefs[KEY_LAST_SYNC] ?: 0L
        val pendingUploads = prefs[KEY_PENDING_UPLOADS] ?: 0

        val lastSyncText = when {
            lastSyncTs == 0L -> "Mai sincronizzato"
            else -> {
                val elapsed = System.currentTimeMillis() - lastSyncTs
                when {
                    elapsed < TimeUnit.MINUTES.toMillis(2) -> "Ultima sync: adesso"
                    elapsed < TimeUnit.HOURS.toMillis(1) -> {
                        val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
                        "Ultima sync: ${minutes}m fa"
                    }
                    elapsed < TimeUnit.DAYS.toMillis(1) -> {
                        val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
                        "Ultima sync: ${hours}h fa"
                    }
                    else -> {
                        val fmt = SimpleDateFormat("d MMM", Locale.ITALIAN)
                        "Ultima sync: ${fmt.format(Date(lastSyncTs))}"
                    }
                }
            }
        }

        return WidgetState(
            lastSyncText = lastSyncText,
            pendingUploads = pendingUploads,
        )
    }

    /** Aggiorna il timestamp dell'ultima sync e il conteggio upload pendenti. */
    suspend fun update(context: Context, pendingUploads: Int) {
        context.widgetDataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                set(KEY_LAST_SYNC, System.currentTimeMillis())
                set(KEY_PENDING_UPLOADS, pendingUploads)
            }
        }
    }
}
