package dev.pampa.mycloudgallery.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.settings.BackupSettingsStorage
import dev.pampa.mycloudgallery.core.backup.settings.BackupSourceCodec
import dev.pampa.mycloudgallery.core.backup.settings.StoredBackupSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Dove si salvano le impostazioni del backup (Fase 5A, P7; docs/fase5-progetto.md §2.1, D7): un `DataStore<Preferences>` in
 * `noBackupFilesDir/datastore`, come `rule_settings`. Sono impostazioni **del dispositivo** (non personali): niente passa da
 * `core-personal` e niente si scrive sul NAS. L'azzeramento dell'app le cancella ([clear]).
 *
 * **Sorgenti iniziali.** Finche' l'utente non ha mai scelto, le sorgenti sono quelle del passo 6 dell'onboarding (fotocamera e
 * screenshot, [initialSources]) e non una lista vuota: la prima volta che ne tocca una, la scelta si salva per intero. Un archivio
 * illeggibile o danneggiato vale "niente di salvato" (lo garantisce il `ReplaceFileCorruptionHandler` con cui nasce il DataStore).
 *
 * @param initialSources le sorgenti del passo 6 dell'onboarding, null = l'onboarding non ha ancora risposto (nessuna sorgente: fallire
 *   chiusi).
 */
class DataStoreBackupSettingsStorage(
  private val dataStore: DataStore<Preferences>,
  private val initialSources: Flow<BackupSources?>,
) : BackupSettingsStorage {

  override val data: Flow<StoredBackupSettings> = combine(dataStore.data.map(::read), initialSources) { stored, onboarding ->
    // "Mai scelte" (nessuna chiave) + il passo 6 dell'onboarding = le sorgenti proposte; scelte una volta, vale la scelta.
    if (stored.sourceIds == null && onboarding != null) stored.copy(sourceIds = BackupSourceCodec.encode(onboarding)) else stored
  }

  override suspend fun update(transform: (StoredBackupSettings) -> StoredBackupSettings): StoredBackupSettings {
    val onboarding = initialSources.first()
    var saved = StoredBackupSettings()
    dataStore.edit { preferences ->
      val raw = read(preferences)
      // La trasformazione vede cio' che l'utente vede (le sorgenti proposte comprese), cosi' una scelta che parte da quelle e' coerente.
      val shown = if (raw.sourceIds == null && onboarding != null) raw.copy(sourceIds = BackupSourceCodec.encode(onboarding)) else raw
      val next = transform(shown)
      write(preferences, next)
      saved = next
    }
    return saved
  }

  /**
   * Fase 6 (P13, D47): le installazioni di prima dei ritagli del soggetto non hanno la sorgente "Ritagli"; **una volta sola** la si aggiunge a
   * quelle che l'utente aveva scelto (`BackupSources.withCutouts`). Chi non aveva scelto niente (nessuna chiave: vale l'onboarding, che i
   * ritagli li ha gia') o aveva spento tutto (insieme vuoto: "il backup non e' voluto") non cambia, e chi poi la spegne non la rivede: la
   * proposta e' ricordata, anche se non ha cambiato niente. [cutouts] e' la sorgente "Ritagli" (in debug con un account di prova quella
   * recintata di D13: `CutoutFence.sourceFor`); [isCutouts] riconosce una cartella dei ritagli fra quelle scelte (`CutoutFence.isCutouts`: la sorgente
   * di serie e quella recintata, ma senza nominare quest'ultima in release). Vero se ha aggiunto la sorgente.
   */
  suspend fun offerCutoutsOnce(cutouts: BackupSource.Folder, isCutouts: (BackupSource) -> Boolean = { it == cutouts }): Boolean {
    var added = false
    dataStore.edit { preferences ->
      if (preferences[CUTOUTS_OFFERED] == true) return@edit
      preferences[CUTOUTS_OFFERED] = true
      val ids = preferences[SOURCES] ?: return@edit
      val sources = BackupSourceCodec.decode(ids)
      if (sources.isEmpty || sources.sources.any(isCutouts)) return@edit
      preferences[SOURCES] = BackupSourceCodec.encode(BackupSources(sources.sources + cutouts))
      added = true
    }
    return added
  }

  /** L'azzeramento: la cartella del dispositivo e le sorgenti scelte appartengono alla famiglia che si lascia. */
  suspend fun clear() {
    dataStore.edit { it.clear() }
  }

  private fun read(preferences: Preferences): StoredBackupSettings = StoredBackupSettings(
    sourceIds = preferences[SOURCES],
    deviceFolder = preferences[DEVICE_FOLDER]?.takeIf { it.isNotBlank() },
  )

  private fun write(preferences: androidx.datastore.preferences.core.MutablePreferences, settings: StoredBackupSettings) {
    val sources = settings.sourceIds
    if (sources == null) preferences.remove(SOURCES) else preferences[SOURCES] = sources
    val folder = settings.deviceFolder
    if (folder == null) preferences.remove(DEVICE_FOLDER) else preferences[DEVICE_FOLDER] = folder
  }

  private companion object {
    val SOURCES = stringSetPreferencesKey("backup_source_ids")
    val DEVICE_FOLDER = stringPreferencesKey("backup_device_folder")

    /** La proposta dei ritagli e' gia' stata fatta (una volta per installazione, [offerCutoutsOnce]). */
    val CUTOUTS_OFFERED = booleanPreferencesKey("backup_cutouts_offered")
  }
}
