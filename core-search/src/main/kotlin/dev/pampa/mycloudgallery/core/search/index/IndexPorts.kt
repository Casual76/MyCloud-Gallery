package dev.pampa.mycloudgallery.core.search.index

import dev.pampa.mycloudgallery.core.database.AiTextRecord
import dev.pampa.mycloudgallery.core.database.CellContent
import dev.pampa.mycloudgallery.core.database.CellContentPage
import dev.pampa.mycloudgallery.core.database.TextMatchCounts
import dev.pampa.mycloudgallery.core.database.VocabularyWord
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.concurrent.atomic.AtomicInteger

// Le PORTE dell'indice locale verso il catalogo (P1), legate da :app (P11) con una riga ciascuna sopra `MediaCatalog` e
// `MediaCatalog.aiIndex`. Il motore non apre il database da se': cosi' i test girano sulla JVM senza Robolectric e il confine resta
// quello del §1.1 (core-search vede core-database solo per i tipi).

/**
 * Il testo letto in `ai_text`, con l'FTS4 e il vocabolario (P1, `AiIndexCatalog`): [put] = `putText`, [remove] = `removeTexts`,
 * [matches] = `textMatches`, [wordCounts], [vocabularyPage]. Il vocabolario contiene **anche le parole dei nascosti**: il motore lo
 * usa solo per correggere i refusi, mai per proporre parole (D29).
 */
interface TextRowStore {
  suspend fun put(record: AiTextRecord): Boolean

  suspend fun remove(keys: Collection<ContentKey>)

  suspend fun matches(terms: List<List<String>>): TextMatchCounts

  suspend fun wordCounts(words: Collection<String>): Map<String, Int>

  suspend fun vocabularyPage(after: String, limit: Int): List<VocabularyWord>
}

/**
 * Le chiavi di contenuto delle celle (P1): [page] = `MediaCatalog.cellContentKeys(afterSeq, limit)` (**a pagine**: la lettura intera
 * supera i 300 ms; `afterSeq` e' il `rowid` di `media`), [cellsOf] = `cellContentKeysOf(ids)` per gli id di un `CatalogChange`.
 * Le celle non hanno mai nascosti ne' membri nascosti (P1, D29).
 */
interface CellKeySource {
  suspend fun page(afterSeq: Long, limit: Int): CellContentPage

  suspend fun cellsOf(ids: Collection<MediaId>): List<CellContent>
}

/**
 * Riavere cio' che l'indice ha perso (file rovinato, registro rotto): :app lo lega alla replica `ai` (che rifonde e riconsegna le voci
 * di quelle chiavi) e al calcolo (che rifa' cio' che era di questo dispositivo: `AiResults.saved` non lo dice piu' salvato).
 */
interface IndexRepair {
  suspend fun redeliver(keys: Collection<ContentKey>)

  suspend fun redeliverAll()

  companion object {
    val NONE: IndexRepair = object : IndexRepair {
      override suspend fun redeliver(keys: Collection<ContentKey>) = Unit

      override suspend fun redeliverAll() = Unit
    }
  }
}

/** I contatori dell'indice per la Diagnostica (solo numeri, D40): ricostruzioni (`SEARCH_INDEX_REBUILT`) e record rovinati. */
class SearchIndexCounters {
  val rebuilt: AtomicInteger = AtomicInteger()
  val corruptRecords: AtomicInteger = AtomicInteger()
  val compactions: AtomicInteger = AtomicInteger()
}
