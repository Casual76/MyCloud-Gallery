package dev.pampa.mycloudgallery.search

import dev.pampa.mycloudgallery.core.database.AiTextRecord
import dev.pampa.mycloudgallery.core.database.CellContent
import dev.pampa.mycloudgallery.core.database.CellContentPage
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.SearchHistoryRecord
import dev.pampa.mycloudgallery.core.database.TextMatchCounts
import dev.pampa.mycloudgallery.core.database.VocabularyWord
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.EmbeddingSpace
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.places.contract.PlaceId
import dev.pampa.mycloudgallery.core.places.contract.ReverseGeocoder
import dev.pampa.mycloudgallery.core.search.contract.PlaceLookup
import dev.pampa.mycloudgallery.core.search.contract.PlaceName
import dev.pampa.mycloudgallery.core.search.contract.SearchData
import dev.pampa.mycloudgallery.core.search.history.HistoryStore
import dev.pampa.mycloudgallery.core.search.index.CellKeySource
import dev.pampa.mycloudgallery.core.search.index.IndexRepair
import dev.pampa.mycloudgallery.core.search.index.TextRowStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

// Le PORTE del motore di ricerca (P6, `core-search/.../index/IndexPorts.kt`, `history/`) sopra il catalogo (P1: `MediaCatalog` e
// `MediaCatalog.aiIndex`), una riga ciascuna (Fase 6, P11). Il catalogo nasce con la famiglia pronta (`GalleryGraph.core`): finche' non
// c'e' le porte rispondono "niente" (nessuna parola, nessuna cella, nessuna cronologia) invece di aspettare.

/** Il testo letto in `ai_text` con l'FTS4 e il vocabolario (P1, `AiIndexCatalog`). */
class CatalogTextRowStore(private val catalog: suspend () -> MediaCatalog?) : TextRowStore {
  override suspend fun put(record: AiTextRecord): Boolean = catalog()?.aiIndex?.putText(record) ?: false

  override suspend fun remove(keys: Collection<ContentKey>) {
    catalog()?.aiIndex?.removeTexts(keys)
  }

  override suspend fun matches(terms: List<List<String>>): TextMatchCounts =
    catalog()?.aiIndex?.textMatches(terms) ?: TextMatchCounts(terms.size, emptyMap())

  override suspend fun wordCounts(words: Collection<String>): Map<String, Int> = catalog()?.aiIndex?.wordCounts(words).orEmpty()

  override suspend fun vocabularyPage(after: String, limit: Int): List<VocabularyWord> =
    catalog()?.aiIndex?.vocabularyPage(after, limit).orEmpty()
}

/** Le chiavi di contenuto delle celle, a pagine (P1: `cellContentKeys`, `cellContentKeysOf`). */
class CatalogCellKeySource(private val catalog: suspend () -> MediaCatalog?) : CellKeySource {
  override suspend fun page(afterSeq: Long, limit: Int): CellContentPage =
    catalog()?.cellContentKeys(afterSeq, limit) ?: CellContentPage(emptyList(), null)

  override suspend fun cellsOf(ids: Collection<MediaId>): List<CellContent> = catalog()?.cellContentKeysOf(ids).orEmpty()
}

/** La cronologia locale (D32): `search_history` del catalogo. Mai sincronizzata; l'azzeramento la toglie con il database. */
class CatalogHistoryStore(private val catalog: suspend () -> MediaCatalog?) : HistoryStore {
  override suspend fun record(text: String, chipLabels: List<String>) {
    catalog()?.aiIndex?.recordSearch(text, chipLabels)
  }

  override suspend fun delete(id: Long) {
    catalog()?.aiIndex?.deleteSearch(id)
  }

  override suspend fun clear() {
    catalog()?.aiIndex?.clearSearchHistory()
  }

  override fun changes(limit: Int): Flow<List<SearchHistoryRecord>> = flow {
    val index = catalog()?.aiIndex
    if (index == null) emit(emptyList()) else emitAll(index.searchHistoryChanges(limit))
  }
}

/**
 * Cio' che il motore legge della Libreria (contratto `SearchData` di P0): l'istantanea della timeline (senza nascosti ne' membri di
 * gruppo) e i nascosti **adesso** (il filtro finale, D29). Vettori e testo li legge `IndexSearchData` dall'indice locale.
 */
class CatalogSearchData(
  override val snapshot: StateFlow<TimelineSnapshot?>,
  private val database: GalleryDatabase,
  private val catalog: suspend () -> MediaCatalog?,
) : SearchData {
  override suspend fun vectors(ids: Collection<MediaId>, space: EmbeddingSpace): Map<MediaId, List<FloatArray>> = emptyMap()

  override suspend fun texts(ids: Collection<MediaId>): Map<MediaId, String> = emptyMap()

  /** Un media e' nascosto se lo e' lui o il media in cui e' stato unito; senza catalogo, per prudenza, tutti (fail closed). */
  override suspend fun hidden(ids: Collection<MediaId>): Set<MediaId> {
    val cat = catalog() ?: return ids.toSet()
    val personal = database.personalDao()
    return ids.filterTo(HashSet()) { id ->
      val resolved = cat.resolve(id) ?: id
      personal.get(resolved.value)?.hidden == true || personal.get(id.value)?.hidden == true
    }
  }
}

/** I nomi dei luoghi per id GeoNames (core-places): solo per il dizionario dei luoghi visibili della Libreria. */
class GeocoderPlaceLookup(private val geocoder: ReverseGeocoder) : PlaceLookup {
  override fun place(id: Int): PlaceName? =
    geocoder.place(PlaceId(id))?.let { PlaceName(it.name, it.admin1, it.countryName) }
}

/**
 * Chi ripara l'indice (P6): la replica `ai` (rifonde e riconsegna le voci di quelle chiavi) e, per il calcolo, niente (rifa' da solo:
 * `AiResults.saved` non dice piu' salvato). Lo lega `AiGraph` quando la replica c'e'; prima, nessuno.
 */
class DelegatingIndexRepair : IndexRepair {
  @Volatile var target: IndexRepair = IndexRepair.NONE

  override suspend fun redeliver(keys: Collection<ContentKey>) = target.redeliver(keys)

  override suspend fun redeliverAll() = target.redeliverAll()
}
