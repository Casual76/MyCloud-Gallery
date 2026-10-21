package dev.pampa.mycloudgallery.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import dev.pampa.mycloudgallery.core.database.entity.AiTextEntity
import dev.pampa.mycloudgallery.core.database.entity.AiVectorEntity
import dev.pampa.mycloudgallery.core.database.entity.AiVocabularyEntity
import dev.pampa.mycloudgallery.core.database.entity.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * L'indice locale dei dati AI (schema v5, D23-D25): le righe dei vettori, il testo letto con il suo FTS4 e il vocabolario.
 * Tutto **per chiave di contenuto** (`ContentKey.hex`). Le scritture passano da `MediaCatalog` (il testo e il vocabolario devono
 * cambiare insieme, in una transazione); qui le primitive.
 *
 * Le liste di chiavi sono al massimo [MAX_KEYS] per chiamata (limite delle variabili di SQLite su API 29): `MediaCatalog` divide da solo.
 */
@Dao
interface AiIndexDao {

  // --- Vettori (le righe di indice del file dei vettori) ------------------------------------------------------------

  @Upsert
  suspend fun putVectors(rows: List<AiVectorEntity>)

  /** Le righe di [keys] nello spazio [space], con ogni ricetta e fotogramma (la piu' alta la sceglie chi legge). */
  @Query("SELECT * FROM ai_vector WHERE space = :space AND content_key IN (:keys) ORDER BY content_key, recipe DESC, frame")
  suspend fun vectorsOf(space: String, keys: List<String>): List<AiVectorEntity>

  /**
   * L'ultima chiave di una pagina di [limit] chiavi dello spazio [space] dopo [afterKey] (vuota = dall'inizio), o null se non ce ne sono:
   * i fotogrammi e le ricette di una chiave stanno sempre tutti nella stessa pagina ([vectorRange]).
   */
  @Query(
    "SELECT MAX(content_key) FROM (SELECT DISTINCT content_key FROM ai_vector WHERE space = :space AND content_key > :afterKey " +
      "ORDER BY content_key LIMIT :limit)",
  )
  suspend fun vectorKeyBound(space: String, afterKey: String, limit: Int): String?

  /** Le righe dello spazio [space] per le chiavi in `(afterKey, upToKey]`, in ordine di chiave, ricetta e fotogramma (la chiave primaria). */
  @Query(
    "SELECT * FROM ai_vector WHERE content_key > :afterKey AND content_key <= :upToKey AND space = :space " +
      "ORDER BY content_key, space, recipe, frame",
  )
  suspend fun vectorRange(space: String, afterKey: String, upToKey: String): List<AiVectorEntity>

  /** Le chiavi di [keys] con un vettore nello spazio [space] di ricetta almeno [minRecipe] (per saltare cio' che e' gia' fatto). */
  @Query("SELECT DISTINCT content_key FROM ai_vector WHERE space = :space AND recipe >= :minRecipe AND content_key IN (:keys)")
  suspend fun keysWithVector(space: String, minRecipe: Int, keys: List<String>): List<String>

  /** Quante chiavi hanno un vettore nello spazio [space] di ricetta almeno [minRecipe] (la copertura, D31). */
  @Query("SELECT COUNT(DISTINCT content_key) FROM ai_vector WHERE space = :space AND recipe >= :minRecipe")
  suspend fun countVectorKeys(space: String, minRecipe: Int): Int

  @Query("SELECT COUNT(*) FROM ai_vector WHERE space = :space")
  suspend fun countVectorRows(space: String): Int

  /** Toglie tutti i vettori di [keys] (ogni spazio): il contenuto non e' piu' in nessun posto. */
  @Query("DELETE FROM ai_vector WHERE content_key IN (:keys)")
  suspend fun deleteVectors(keys: List<String>)

  /** Toglie i vettori di uno spazio intero (D21: lo spazio vecchio, 30 giorni dopo il cambio). */
  @Query("DELETE FROM ai_vector WHERE space = :space")
  suspend fun deleteSpace(space: String)

  /** Toglie le righe di una ricetta piu' vecchia di [recipe] per le chiavi che ne hanno una piu' nuova nello stesso spazio. */
  @Query(
    "DELETE FROM ai_vector WHERE space = :space AND recipe < :recipe AND EXISTS " +
      "(SELECT 1 FROM ai_vector n WHERE n.content_key = ai_vector.content_key AND n.space = ai_vector.space AND n.recipe >= :recipe)",
  )
  suspend fun deleteSupersededRecipes(space: String, recipe: Int): Int

  // --- Testo letto ------------------------------------------------------------------------------------------------------

  @Query("SELECT * FROM ai_text WHERE content_key = :key")
  suspend fun textOf(key: String): AiTextEntity?

  @Query("SELECT * FROM ai_text WHERE content_key IN (:keys)")
  suspend fun textsOf(keys: List<String>): List<AiTextEntity>

  @Insert
  suspend fun insertText(row: AiTextEntity): Long

  /** `UPDATE` e non `INSERT OR REPLACE`: i trigger del contenuto esterno dell'FTS4 non scattano sulla cancellazione implicita. */
  @Query(
    "UPDATE ai_text SET recipe = :recipe, engine = :engine, source = :source, text = :text, lines = :lines, chars = :chars, " +
      "layout = :layout, created_at = :createdAt WHERE content_key = :key",
  )
  suspend fun updateText(
    key: String,
    recipe: Int,
    engine: String,
    source: String,
    text: String,
    lines: Int,
    chars: Int,
    layout: ByteArray?,
    createdAt: Long,
  ): Int

  @Query("DELETE FROM ai_text WHERE content_key = :key")
  suspend fun deleteText(key: String): Int

  /** Le chiavi di [keys] con un testo letto di ricetta almeno [minRecipe] (anche vuoto: letto, nessun testo). */
  @Query("SELECT content_key FROM ai_text WHERE recipe >= :minRecipe AND content_key IN (:keys)")
  suspend fun keysWithText(minRecipe: Int, keys: List<String>): List<String>

  @Query("SELECT COUNT(*) FROM ai_text WHERE recipe >= :minRecipe")
  suspend fun countTexts(minRecipe: Int): Int

  /** Il testo normalizzato e le misure delle chiavi [keys] (per il bit `DOCUMENT`, D34, senza il riquadro delle righe). */
  @Query("SELECT content_key AS content_key, lines AS lines, chars AS chars FROM ai_text WHERE content_key IN (:keys)")
  suspend fun textStats(keys: List<String>): List<TextStatsRow>

  /**
   * Le chiavi dei testi che contengono la frase [expression] (D25). [expression] e' **sempre** costruita da `MediaCatalog`:
   * una frase fra virgolette doppie di parole `[a-z0-9]` ("la parola" o "due parole"), mai testo dell'utente com'e'.
   */
  @Query("SELECT content_key FROM ai_text WHERE text_id IN (SELECT rowid FROM ai_text_fts WHERE ai_text_fts MATCH :expression)")
  suspend fun matchPhrase(expression: String): List<String>

  // --- Vocabolario ------------------------------------------------------------------------------------------------------

  @Query("INSERT OR IGNORE INTO ai_vocabulary (word, docs) VALUES (:word, 0)")
  suspend fun createWord(word: String)

  @Query("UPDATE ai_vocabulary SET docs = docs + :delta WHERE word = :word")
  suspend fun addToWord(word: String, delta: Int)

  @Query("DELETE FROM ai_vocabulary WHERE docs <= 0")
  suspend fun deleteEmptyWords(): Int

  @Query("SELECT * FROM ai_vocabulary WHERE word IN (:words)")
  suspend fun wordsOf(words: List<String>): List<AiVocabularyEntity>

  /** Una pagina del vocabolario dopo [afterWord] (vuota = dall'inizio), in ordine alfabetico: la costruzione dell'indice dei refusi. */
  @Query("SELECT * FROM ai_vocabulary WHERE word > :afterWord ORDER BY word LIMIT :limit")
  suspend fun vocabularyPage(afterWord: String, limit: Int): List<AiVocabularyEntity>

  /** Le parole in `[from, to)` (un prefisso e il suo estremo superiore), alfabetiche: i completamenti dal vocabolario. */
  @Query("SELECT * FROM ai_vocabulary WHERE word >= :from AND word < :to ORDER BY word LIMIT :limit")
  suspend fun wordsBetween(from: String, to: String, limit: Int): List<AiVocabularyEntity>

  @Query("SELECT COUNT(*) FROM ai_vocabulary")
  suspend fun vocabularySize(): Int

  // --- Azzeramento ------------------------------------------------------------------------------------------------------

  /** Svuota l'indice del testo e il vocabolario (i vettori hanno `deleteSpace`). I trigger tengono l'FTS4 allineato. */
  @Query("DELETE FROM ai_text")
  suspend fun deleteAllTexts()

  @Query("DELETE FROM ai_vocabulary")
  suspend fun deleteVocabulary()

  companion object {
    /** Limite prudente di chiavi per chiamata (SQLite su API 29 accetta 999 variabili). */
    const val MAX_KEYS: Int = 500
  }
}

/** Le misure di un testo letto (D34). */
data class TextStatsRow(
  @ColumnInfo(name = "content_key") val contentKey: String,
  @ColumnInfo(name = "lines") val lines: Int,
  @ColumnInfo(name = "chars") val chars: Int,
)

/** La cronologia locale della ricerca (D32): le primitive; il tetto di 50 e la ricerca unica per testo stanno in `MediaCatalog`. */
@Dao
interface SearchHistoryDao {

  /** Dalla piu' recente. */
  @Query("SELECT * FROM search_history ORDER BY id DESC LIMIT :limit")
  suspend fun recent(limit: Int): List<SearchHistoryEntity>

  /** Come [recent], vivo: Room lo riemette a ogni cambiamento della tabella. */
  @Query("SELECT * FROM search_history ORDER BY id DESC LIMIT :limit")
  fun observe(limit: Int): Flow<List<SearchHistoryEntity>>

  @Insert
  suspend fun insert(row: SearchHistoryEntity): Long

  @Query("DELETE FROM search_history WHERE text = :text")
  suspend fun deleteByText(text: String)

  @Query("DELETE FROM search_history WHERE id = :id")
  suspend fun delete(id: Long)

  @Query("DELETE FROM search_history")
  suspend fun deleteAll()

  /** Tiene le [max] righe piu' recenti. */
  @Query("DELETE FROM search_history WHERE id NOT IN (SELECT id FROM search_history ORDER BY id DESC LIMIT :max)")
  suspend fun trim(max: Int)

  @Query("SELECT COUNT(*) FROM search_history")
  suspend fun count(): Int
}
