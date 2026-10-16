package dev.pampa.mycloudgallery.core.backup.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// DOVE SI SALVANO LE IMPOSTAZIONI DEL BACKUP (Fase 5A, P4; docs/fase5-progetto.md §2.1, D7).
//
// Sono impostazioni **del dispositivo**, non personali (come il preset delle regole e la qualita' delle anteprime): ogni telefono ha le
// sue cartelle e la sua cartella sul NAS. Niente passa da `core-personal` (che sincronizza i dati fra i dispositivi dell'utente) e niente
// si scrive sul NAS. `:app` lega questa porta a un `DataStore<Preferences>` in `noBackupFilesDir/datastore`, come `rule_settings`:
// l'azzeramento dell'app lo cancella con gli altri file locali. core-backup non dipende da DataStore (nessuna dipendenza nuova), quindi
// l'adattatore sta in `:app`.

/**
 * Cio' che si salva. Solo testo: [sourceIds] sono gli [dev.pampa.mycloudgallery.core.backup.contract.BackupSource.id] (null = mai scelte)
 * e [deviceFolder] il nome della cartella del dispositivo sul NAS (null = non ancora scelta).
 */
data class StoredBackupSettings(
  val sourceIds: Set<String>? = null,
  val deviceFolder: String? = null,
) {
  /** Il nome della cartella e' un dato dell'utente, gli id delle sorgenti sono percorsi del telefono: mai nei log. */
  override fun toString(): String = "StoredBackupSettings"
}

/** Il magazzino delle impostazioni del backup. Un solo valore per dispositivo (o profilo del tablet). */
interface BackupSettingsStorage {

  /** Il valore salvato, riemesso a ogni modifica. Un archivio illeggibile o danneggiato vale "niente di salvato". */
  val data: Flow<StoredBackupSettings>

  /** Legge, modifica e salva in modo atomico; restituisce il valore salvato. */
  suspend fun update(transform: (StoredBackupSettings) -> StoredBackupSettings): StoredBackupSettings
}

/** Il magazzino in memoria (test; e un ripiego se l'archivio vero non c'e'): si perde col processo. */
class MemoryBackupSettingsStorage(initial: StoredBackupSettings = StoredBackupSettings()) : BackupSettingsStorage {
  private val state = MutableStateFlow(initial)

  override val data: Flow<StoredBackupSettings> = state.asStateFlow()

  override suspend fun update(transform: (StoredBackupSettings) -> StoredBackupSettings): StoredBackupSettings {
    while (true) {
      val before = state.value
      val after = transform(before)
      if (state.compareAndSet(before, after)) return after
    }
  }
}
