package dev.pampa.mycloudgallery.core.library.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.LibraryPreferences
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Filtro, livello e stile della Libreria, ricordati (§2.4) in un DataStore in `noBackupFilesDir` (non finiscono nel
 * backup del telefono: sono preferenze di questo dispositivo). Si salvano i **codici** stabili degli enum: un codice
 * sconosciuto (una versione piu' nuova dell'app) torna al default.
 *
 * Le `StateFlow` partono dal default e si aggiornano alla prima lettura del file (pochi millisecondi): chi non vuole un
 * fotogramma col filtro sbagliato aspetta [ready] prima di mostrare la griglia.
 */
class DataStoreLibraryPreferences(
  private val store: DataStore<Preferences>,
  scope: CoroutineScope,
) : LibraryPreferences {

  private val data: Flow<Preferences> = store.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

  override val filter: StateFlow<LibraryFilter> =
    data.map { LibraryFilter.fromCode(it[FILTER]) }.stateIn(scope, SharingStarted.Eagerly, LibraryFilter.ALL)

  override val level: StateFlow<GridLevel> =
    data.map { GridLevel.fromCode(it[LEVEL]) }.stateIn(scope, SharingStarted.Eagerly, GridLevel.DAYS)

  override val style: StateFlow<GridStyle> =
    data.map { GridStyle.fromCode(it[STYLE]) }.stateIn(scope, SharingStarted.Eagerly, GridStyle.SQUARE)

  /** Aspetta la prima lettura del file. */
  suspend fun ready() {
    data.first()
  }

  /** Avvio istantaneo: filtro, livello e stile come sono nel file (non i valori di partenza delle `StateFlow`, che arrivano un attimo dopo). */
  suspend fun read(): Triple<LibraryFilter, GridLevel, GridStyle> {
    val prefs = data.first()
    return Triple(LibraryFilter.fromCode(prefs[FILTER]), GridLevel.fromCode(prefs[LEVEL]), GridStyle.fromCode(prefs[STYLE]))
  }

  override suspend fun setFilter(filter: LibraryFilter) {
    store.edit { it[FILTER] = filter.code }
  }

  override suspend fun setLevel(level: GridLevel) {
    store.edit { it[LEVEL] = level.code }
  }

  override suspend fun setStyle(style: GridStyle) {
    store.edit { it[STYLE] = style.code }
  }

  companion object {
    private val FILTER = stringPreferencesKey("filter")
    private val LEVEL = stringPreferencesKey("level")
    private val STYLE = stringPreferencesKey("style")

    /** Il file delle preferenze in `noBackupFilesDir`. */
    fun file(context: Context): File = File(context.noBackupFilesDir, "datastore/library.preferences_pb")

    /**
     * Le preferenze su [file] (un DataStore per file e per processo: il grafo di `:app` ne crea una). [scope] tiene vive
     * le `StateFlow`; [storeScope] e' quello del DataStore, che fa I/O (di default su `Dispatchers.IO`).
     */
    fun create(
      file: File,
      scope: CoroutineScope,
      storeScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    ): DataStoreLibraryPreferences = DataStoreLibraryPreferences(
      PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = storeScope,
        produceFile = { file },
      ),
      scope,
    )
  }
}
