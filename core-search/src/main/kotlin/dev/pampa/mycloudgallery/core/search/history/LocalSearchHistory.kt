package dev.pampa.mycloudgallery.core.search.history

import dev.pampa.mycloudgallery.core.database.SearchHistoryRecord
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.search.contract.HistoryEntry
import dev.pampa.mycloudgallery.core.search.contract.SearchChip
import dev.pampa.mycloudgallery.core.search.contract.SearchHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * La tabella `search_history` (P1, `AiIndexCatalog`): [record] = `recordSearch`, [delete] = `deleteSearch`, [clear] =
 * `clearSearchHistory`, [changes] = `searchHistoryChanges` (viva). Legata da :app.
 */
interface HistoryStore {
  /** [persons]: gli id delle persone dei chip "Persona" da cui nasce la ricerca (correzioni D, R2). */
  suspend fun record(text: String, chipLabels: List<String>, persons: List<PersonId> = emptyList())

  suspend fun delete(id: Long)

  suspend fun clear()

  fun changes(limit: Int): Flow<List<SearchHistoryRecord>>

  /**
   * Le voci **adesso**, in una lettura sola (`AiIndexCatalog.searchHistory`), o null se questo deposito non sa farla: allora la cancellazione dei
   * nomi (Fase 8, [LocalSearchHistory.purge]) legge l'ultimo elenco vivo. La lettura sola e' piu' sicura: [changes] emette prima un elenco vuoto e
   * poi quello vero, quindi subito dopo l'avvio l'elenco vivo potrebbe essere ancora vuoto.
   */
  suspend fun records(limit: Int): List<SearchHistoryRecord>? = null
}

/**
 * LA CRONOLOGIA LOCALE (D32): al massimo 50 voci, testo ed etichette dei chip, **senza miniature**; mai sincronizzata, mai nei dati
 * personali ne' nell'esportazione della Diagnostica; tolta dall'azzeramento (:app). Il chip "Simili a" non si registra (nomina un
 * media, che potrebbe essere nascosto dopo: D29); una ricerca senza testo nemmeno.
 *
 * Correzioni D (R2): ogni voce ricorda anche gli id delle persone dei suoi chip "Persona" ([personsOf]); i nomi si tolgono per id e per nome ([purge]).
 */
class LocalSearchHistory(private val store: HistoryStore, scope: CoroutineScope) : SearchHistory {

  override val entries: StateFlow<List<HistoryEntry>> = store.changes(SearchHistory.MAX_ENTRIES)
    .map { rows -> rows.take(SearchHistory.MAX_ENTRIES).map { HistoryEntry(it.id, it.text, it.chipLabels, it.persons) } }
    .stateIn(scope, SharingStarted.Eagerly, emptyList())

  override suspend fun record(text: String, chips: List<SearchChip>) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return
    store.record(trimmed, labelsOf(chips), personsOf(chips))
  }

  override suspend fun delete(id: Long) = store.delete(id)

  override suspend fun clear() = store.clear()

  /**
   * Fase 8 (D29 passo 4) e correzioni D (R2): toglie le voci che nominano una persona, per id ([persons]) o per nome ([HistoryNames]). Legge le voci adesso dal
   * deposito; se non sa farlo, l'ultimo elenco vivo. Ogni voce che non si riesce a togliere fa fallire la chiamata (l'eccezione sale: chi cancella riprova,
   * e' idempotente).
   */
  override suspend fun purge(names: Collection<String>, persons: Collection<PersonId>): Int {
    val wanted = HistoryNames.wanted(names, persons)
    if (wanted.isEmpty) return 0
    return removeWhere { row -> HistoryNames.contains(row.text, row.chipLabels, wanted, row.persons) }
  }

  /** R2: ogni ricerca nata da un chip "Persona" (per quando i nomi non si leggono piu'). */
  override suspend fun purgePersonSearches(): Int = removeWhere { it.persons.isNotEmpty() }

  private suspend fun removeWhere(unwanted: (SearchHistoryRecord) -> Boolean): Int {
    val rows = store.records(SearchHistory.MAX_ENTRIES) ?: entries.value.map { SearchHistoryRecord(it.id, it.text, it.chipLabels, it.persons) }
    var removed = 0
    for (row in rows) {
      if (unwanted(row)) {
        store.delete(row.id)
        removed++
      }
    }
    return removed
  }

  companion object {
    /** Le etichette da salvare: senza "Simili a" (D29). */
    fun labelsOf(chips: List<SearchChip>): List<String> = chips.filter { it !is SearchChip.Similar }.map { it.label }

    /** Le persone dei chip "Persona" (R2): gli id, senza ripetizioni, nell'ordine dei chip. */
    fun personsOf(chips: List<SearchChip>): List<PersonId> = chips.filterIsInstance<SearchChip.Person>().flatMap { it.option.ids }.distinct()
  }
}
