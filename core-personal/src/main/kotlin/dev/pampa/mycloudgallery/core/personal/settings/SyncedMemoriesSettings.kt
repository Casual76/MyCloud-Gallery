package dev.pampa.mycloudgallery.core.personal.settings

import dev.pampa.mycloudgallery.core.common.files.QuietWrites
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesRecords
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import dev.pampa.mycloudgallery.core.personal.contract.MemoryLessLevel
import dev.pampa.mycloudgallery.core.personal.contract.PersonalChange
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Le scelte sui ricordi (Fase 9, P6, D10, D11, Q9; docs/fase9-progetto.md §2.3, E-MEM8, E-MEM9, E-MEM22) sopra lo store dei dati personali:
 * "visto" e "Non mostrare" per ricordo, "Mostra meno" e "Mai" per persona, "Mai" per periodo, "Ricordi con le persone". Viaggiano fra i
 * dispositivi dello stesso account **dentro i tipi esistenti** `MEMORY` e `SETTING` (nessun tipo nuovo, nessuna versione nuova del formato: un'app
 * della Fase 8 conserva i campi senza capirli), come le altre impostazioni sincronizzate ([SyncedFacesSettings], [SyncedSearchSettings]):
 *
 * - **Vale subito, anche senza l'ora del NAS.** Si legge [PersonalStore.effectiveEntries]: lo stato unito con sopra la scelta fatta qui ancora
 *   "da datare" (E-MEM22). Una scelta fatta fuori casa non ha l'ora e non e' nello stato che si pubblica; la sincronizzazione viene dopo e la voce
 *   prende l'ora alla prima volta senza perdere l'ordine. [current] legge lo store **adesso**, senza aspettare che [choices] (un flusso su un
 *   altro thread) si sia mosso: chi guarda le scelte prima di ogni passo (il giro dei ricordi) non ne vede una vecchia.
 * - **Fra due dispositivi vince l'ultima scrittura per ora del NAS** ([dev.pampa.mycloudgallery.core.personal.contract.PersonalMerge]), uguale
 *   ovunque; una lapide e' la scelta tolta. Togliere una scelta che non c'e' non scrive niente (nessuna lapide a vuoto).
 * - **La sola regola di lettura** e' [MemoriesRecords.read]: un valore che non si capisce (un'app piu' nuova, dati ostili) vale "nessuna scelta" e
 *   non rovina le altre voci. Chiavi e valori sono quelli di [MemoriesRecords]; `markSeen` passa da [PersonalMemories] (il giorno avanza solo in avanti).
 * - **Mai un nome, un titolo o un luogo** nelle voci: id di ricordi (hash, D9), id casuali di persone, giorni, codici e flag.
 *
 * Questa classe non scrive niente nei log.
 */
class SyncedMemoriesSettings(
  private val store: PersonalStore,
  scope: CoroutineScope,
) : MemoriesPersonalSettings {

  private val memories = PersonalMemories(store)

  override val choices: StateFlow<MemoriesChoices> = store.effectiveEntries
    .map { MemoriesRecords.read(it) }
    .distinctUntilChanged()
    .stateIn(scope, SharingStarted.Eagerly, MemoriesRecords.read(store.effectiveEntries.value))

  override fun current(): MemoriesChoices = MemoriesRecords.read(store.effectiveEntries.value)

  override suspend fun markSeen(memoryId: String, day: Int) {
    // L'id e il giorno si controllano **prima** di leggere lo stato (un id malformato non e' mai "gia' visto").
    MemoriesRecords.seenKey(memoryId)
    MemoriesRecords.seenValue(day)
    memories.markSeen(memoryId, day)
  }

  override suspend fun setDismissed(memoryId: String, dismissed: Boolean) {
    val key = MemoriesRecords.dismissedKey(memoryId)
    if (!dismissed && !isLive(key)) return
    QuietWrites.attempt("ricordo scartato") { store.set(key, if (dismissed) PersonalValue.Flag(true) else PersonalValue.Removed) }
  }

  override suspend fun setLess(person: PersonId, level: MemoryLessLevel?) {
    val key = MemoriesRecords.lessKey(person)
    if (level == null && !isLive(key)) return
    QuietWrites.attempt("ricordi di una persona") { store.set(key, MemoriesRecords.lessValue(level)) }
  }

  override suspend fun setNeverDays(days: MemoryDays, never: Boolean) {
    val key = MemoriesRecords.neverKey(days)
    if (!never && !isLive(key)) return
    QuietWrites.attempt("giorni senza ricordi") { store.set(key, if (never) PersonalValue.Flag(true) else PersonalValue.Removed) }
  }

  override suspend fun setPeopleMemories(on: Boolean) {
    QuietWrites.attempt("ricordi con le persone") { store.set(MemoriesRecords.peopleKey, PersonalValue.Flag(on)) }
  }

  /**
   * D11, D37: **tutte** le voci `memories_less:person:*` diventano lapidi, anche quelle di persone che questo dispositivo non conosce (un altro
   * dispositivo le ha scritte; le persone non ci sono piu'). Quante, nello stato di adesso (anche le scelte ancora "da datare").
   */
  override suspend fun dropAllPeopleChoices(): Int {
    val keys = store.effective().filter { (key, entry) ->
      key.kind == PersonalKind.SETTING && key.subject == PersonalFields.SETTINGS_SUBJECT &&
        PersonalFields.memoriesLessPersonOf(key.field) != null && entry.value != PersonalValue.Removed
    }.keys.toList()
    // Un lotto solo: le scelte si azzerano tutte o nessuna. Con la memoria piena non si e' azzerato niente e si dice 0.
    if (keys.isEmpty()) return 0
    val saved = QuietWrites.attempt("scelte sulle persone dei ricordi") { store.setAll(keys.map { PersonalChange(it, PersonalValue.Removed) }) }
    return if (saved) keys.size else 0
  }

  /** Se la voce [key] c'e' e non e' una lapide, nello stato di adesso. */
  private fun isLive(key: PersonalKey): Boolean {
    val entry: PersonalEntry = store.effectiveEntries.value[key] ?: return false
    return entry.value != PersonalValue.Removed
  }
}
