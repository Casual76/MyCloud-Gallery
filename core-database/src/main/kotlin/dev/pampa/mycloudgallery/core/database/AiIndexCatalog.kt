package dev.pampa.mycloudgallery.core.database

import androidx.room.withTransaction
import dev.pampa.mycloudgallery.core.database.dao.AiIndexDao
import dev.pampa.mycloudgallery.core.database.entity.AiTextEntity
import dev.pampa.mycloudgallery.core.database.entity.AiVectorEntity
import dev.pampa.mycloudgallery.core.database.entity.SEARCH_HISTORY_MAX
import dev.pampa.mycloudgallery.core.database.entity.SearchHistoryEntity
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.OcrEngineCode
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.model.VecSource
import dev.pampa.mycloudgallery.core.model.enumByCodeOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

/**
 * L'indice locale dei dati AI per **chiave di contenuto** (Fase 6, schema v5; D23-D25, D32): le righe dei vettori, il testo letto con
 * il suo indice FTS4, il vocabolario dei refusi e la cronologia della ricerca. Sta in `MediaCatalog.aiIndex`.
 *
 * Niente qui tocca il grafo dei media: ogni operazione e' **una transazione** del database ma non annuncia un [CatalogChange]
 * (l'indice della timeline non cambia: il bit `DOCUMENT` si scrive con `MediaCatalog.setDerivedFlags`). Vettori e testo non puntano a
 * `media`, quindi l'unione e la rinomina d'identita' non li toccano (`MediaCatalog.TABLES_WITHOUT_MEDIA_IDS`).
 *
 * Mai testo letto, chiavi o parole nei log e nei messaggi d'errore (D40).
 */
class AiIndexCatalog internal constructor(private val database: GalleryDatabase) {

  private val dao = database.aiIndexDao()
  private val historyDao = database.searchHistoryDao()

  // --- Vettori -----------------------------------------------------------------------------------------------------------

  /** Salva o sostituisce le righe (stessa chiave, spazio, ricetta e fotogramma). Una transazione sola. */
  suspend fun putVectors(rows: List<AiVectorRow>) {
    if (rows.isEmpty()) return
    database.withTransaction { dao.putVectors(rows.map { it.toEntity() }) }
  }

  /** Per ogni chiave di [keys] con un vettore nello spazio [space]: le righe (tutti i fotogrammi) della **ricetta piu' alta** (D21). */
  suspend fun vectors(keys: Collection<ContentKey>, space: SpaceCode = SpaceCode.CURRENT): Map<ContentKey, List<AiVectorRow>> {
    val result = LinkedHashMap<ContentKey, List<AiVectorRow>>()
    for (chunk in keys.distinct().chunked(AiIndexDao.MAX_KEYS)) {
      val rows = dao.vectorsOf(space.code, chunk.map { it.hex }).mapNotNull { it.toRow() }
      rows.groupBy { it.contentKey }.forEach { (key, all) ->
        val best = all.maxOf { it.recipe }
        result[key] = all.filter { it.recipe == best }.sortedBy { it.frame }
      }
    }
    return result
  }

  /**
   * Una pagina dello spazio [space] dopo la chiave [after] (null = dall'inizio): tutte le righe, ogni ricetta e fotogramma, di al piu'
   * [limit] chiavi, in ordine di chiave. [VectorPage.next] e' il cursore della pagina dopo (null = finito). Per costruire l'indice in memoria (D24).
   */
  suspend fun vectorPage(space: SpaceCode, after: ContentKey?, limit: Int): VectorPage {
    require(limit > 0) { "Pagina vuota." }
    val from = after?.hex.orEmpty()
    val bound = dao.vectorKeyBound(space.code, from, limit) ?: return VectorPage(emptyList(), null)
    val rows = dao.vectorRange(space.code, from, bound).mapNotNull { it.toRow() }
    return VectorPage(rows, ContentKey(bound))
  }

  /** Quali di [keys] hanno un vettore nello [space] di ricetta almeno [minRecipe] (per saltare cio' che e' gia' fatto). */
  suspend fun keysWithVector(keys: Collection<ContentKey>, space: SpaceCode = SpaceCode.CURRENT, minRecipe: Int = 1): Set<ContentKey> =
    keys.distinct().chunked(AiIndexDao.MAX_KEYS).flatMapTo(HashSet()) { chunk ->
      dao.keysWithVector(space.code, minRecipe, chunk.map { it.hex }).map(::ContentKey)
    }

  /** Quante chiavi hanno un vettore nello [space] di ricetta almeno [minRecipe] (la copertura, D31). */
  suspend fun countVectorKeys(space: SpaceCode = SpaceCode.CURRENT, minRecipe: Int = 1): Int = dao.countVectorKeys(space.code, minRecipe)

  /** Toglie tutti i vettori di [keys] (ogni spazio). */
  suspend fun removeVectors(keys: Collection<ContentKey>) {
    database.withTransaction { keys.distinct().chunked(AiIndexDao.MAX_KEYS).forEach { dao.deleteVectors(it.map { k -> k.hex }) } }
  }

  /** Toglie lo spazio intero (D21: lo spazio vecchio, 30 giorni dopo il cambio). */
  suspend fun removeVectorSpace(space: SpaceCode) = dao.deleteSpace(space.code)

  /** Toglie le righe di una ricetta piu' vecchia di [recipe] per le chiavi che ne hanno una piu' nuova. Quante righe. */
  suspend fun removeSupersededVectors(space: SpaceCode, recipe: Int): Int = dao.deleteSupersededRecipes(space.code, recipe)

  // --- Testo letto -------------------------------------------------------------------------------------------------------

  /**
   * Salva il testo letto di una chiave (una riga per chiave). Una ricetta **piu' bassa** di quella gia' salvata non la sostituisce
   * (D7: vince la piu' alta). Nella stessa transazione cambiano l'indice FTS4 (i trigger di Room) e il vocabolario. Vero se ha scritto.
   */
  suspend fun putText(record: AiTextRecord): Boolean = database.withTransaction {
    val key = record.contentKey.hex
    val before = dao.textOf(key)
    if (before != null && before.recipe > record.recipe) return@withTransaction false
    if (before == null) {
      dao.insertText(record.toEntity())
    } else {
      // UPDATE e non REPLACE: l'FTS4 a contenuto esterno si aggiorna con i trigger di UPDATE (recursive_triggers e' spento).
      dao.updateText(
        key, record.recipe, record.engine.code, record.source.code, record.text, record.lines, record.chars, record.layout, record.createdAt,
      )
    }
    adjustVocabulary(before?.text.orEmpty(), record.text)
    true
  }

  /** Toglie il testo di [keys] (e le sue parole dal vocabolario). Quanti ne ha tolti. */
  suspend fun removeTexts(keys: Collection<ContentKey>): Int = database.withTransaction {
    var removed = 0
    for (key in keys.distinct()) {
      val before = dao.textOf(key.hex) ?: continue
      dao.deleteText(key.hex)
      adjustVocabulary(before.text, "")
      removed++
    }
    removed
  }

  suspend fun text(key: ContentKey): AiTextRecord? = dao.textOf(key.hex)?.toRecord()

  suspend fun texts(keys: Collection<ContentKey>): Map<ContentKey, AiTextRecord> =
    keys.distinct().chunked(AiIndexDao.MAX_KEYS).flatMap { chunk -> dao.textsOf(chunk.map { it.hex }) }
      .mapNotNull { it.toRecord() }.associateBy { it.contentKey }

  /** Quali di [keys] hanno un testo letto di ricetta almeno [minRecipe] (anche vuoto: letto, nessun testo). */
  suspend fun keysWithText(keys: Collection<ContentKey>, minRecipe: Int = 1): Set<ContentKey> =
    keys.distinct().chunked(AiIndexDao.MAX_KEYS).flatMapTo(HashSet()) { chunk ->
      dao.keysWithText(minRecipe, chunk.map { it.hex }).map(::ContentKey)
    }

  suspend fun countTexts(minRecipe: Int = 1): Int = dao.countTexts(minRecipe)

  /** Righe e caratteri del testo letto di [keys] (la regola del bit `DOCUMENT`, D34). */
  suspend fun textStats(keys: Collection<ContentKey>): Map<ContentKey, AiTextStats> =
    keys.distinct().chunked(AiIndexDao.MAX_KEYS).flatMap { chunk -> dao.textStats(chunk.map { it.hex }) }
      .associate { ContentKey(it.contentKey) to AiTextStats(it.lines, it.chars) }

  /** Svuota il testo (e il vocabolario). I vettori hanno [removeVectorSpace]. */
  suspend fun clearTexts() {
    database.withTransaction {
      dao.deleteAllTexts()
      dao.deleteVocabulary()
    }
  }

  // --- Ricerca nel testo (FTS4) ------------------------------------------------------------------------------------------

  /**
   * Le chiavi che rispondono ai [terms] (D25, D27). Ogni termine e' l'elenco delle sue **alternative**: una parola, una frase fra
   * virgolette, la parola corretta dal vocabolario; una chiave risponde al termine se contiene una qualunque alternativa.
   *
   * **Mai sintassi `MATCH` dall'utente** (E-Q9): ogni alternativa si riduce a `[a-z0-9 ]` (il resto diventa uno spazio) e va al
   * database come **una frase fra virgolette**, quindi non ha operatori, colonne, prefissi ne' `NEAR`. Al piu' [MAX_TERMS] termini e
   * [MAX_ALTERNATIVES] alternative ciascuno, [MAX_TERM_CHARS] caratteri l'una: il resto si tronca.
   */
  suspend fun textMatches(terms: List<List<String>>): TextMatchCounts {
    val cleaned = terms.take(MAX_TERMS).map { alternatives ->
      alternatives.take(MAX_ALTERNATIVES).map(::literal).filter { it.isNotEmpty() }.distinct()
    }.filter { it.isNotEmpty() }
    if (cleaned.isEmpty()) return TextMatchCounts(0, emptyMap())
    val hits = HashMap<ContentKey, Int>()
    for (alternatives in cleaned) {
      val keys = HashSet<String>()
      for (phrase in alternatives) keys += dao.matchPhrase("\"$phrase\"")
      for (key in keys) hits.merge(ContentKey(key), 1, Int::plus)
    }
    return TextMatchCounts(cleaned.size, hits)
  }

  // --- Vocabolario -------------------------------------------------------------------------------------------------------

  /** Per ogni parola di [words] presente, in quanti testi compare. */
  suspend fun wordCounts(words: Collection<String>): Map<String, Int> =
    words.distinct().chunked(AiIndexDao.MAX_KEYS).flatMap { dao.wordsOf(it) }.associate { it.word to it.docs }

  /** Una pagina del vocabolario dopo [after] (vuota = dall'inizio), alfabetica: per costruire l'indice dei refusi (D25). */
  suspend fun vocabularyPage(after: String, limit: Int): List<VocabularyWord> =
    dao.vocabularyPage(after, limit).map { VocabularyWord(it.word, it.docs) }

  /** Le parole che cominciano per [prefix] (almeno una lettera), alfabetiche. **Mai** proposte nella schermata Cerca (D29). */
  suspend fun completeWords(prefix: String, limit: Int): List<VocabularyWord> {
    val p = literal(prefix).replace(" ", "")
    if (p.isEmpty() || limit <= 0) return emptyList()
    // Il primo elemento dopo tutti i prefissi: l'ultima lettera avanza di uno (le parole sono `[a-z0-9]`).
    val to = p.dropLast(1) + (p.last() + 1)
    return dao.wordsBetween(p, to, limit).map { VocabularyWord(it.word, it.docs) }
  }

  suspend fun vocabularySize(): Int = dao.vocabularySize()

  private suspend fun adjustVocabulary(before: String, after: String) {
    val old = wordsOf(before)
    val new = wordsOf(after)
    for (word in new - old) {
      dao.createWord(word)
      dao.addToWord(word, 1)
    }
    for (word in old - new) dao.addToWord(word, -1)
    if (old.any { it !in new }) dao.deleteEmptyWords()
  }

  /** Le parole **distinte** di un testo normalizzato che entrano nel vocabolario (al piu' [MAX_WORD_CHARS] lettere). */
  private fun wordsOf(text: String): Set<String> =
    if (text.isEmpty()) emptySet() else text.split(' ').filterTo(LinkedHashSet()) { it.isNotEmpty() && it.length <= MAX_WORD_CHARS }

  // --- Cronologia della ricerca (D32) ------------------------------------------------------------------------------------

  /**
   * Registra una ricerca: la stessa (stesso testo) resta una volta sola, la piu' recente per prima, e al massimo [SEARCH_HISTORY_MAX]
   * voci. Testo vuoto: niente. Il testo si tronca a [MAX_HISTORY_TEXT], i chip a [MAX_HISTORY_CHIPS] etichette. [persons]: le persone dei chip "Persona" da cui
   * nasce la ricerca (R2, correzioni D), al piu' [MAX_HISTORY_PERSONS].
   */
  suspend fun recordSearch(text: String, chipLabels: List<String>, persons: List<PersonId> = emptyList()) {
    val trimmed = text.trim().take(MAX_HISTORY_TEXT)
    if (trimmed.isEmpty()) return
    val chips = chipLabels.take(MAX_HISTORY_CHIPS).joinToString("\n") { it.replace('\n', ' ').trim().take(MAX_CHIP_LABEL) }
    database.withTransaction {
      historyDao.deleteByText(trimmed)
      historyDao.insert(SearchHistoryEntity(text = trimmed, chips = chips, persons = ownersOf(persons)))
      historyDao.trim(SEARCH_HISTORY_MAX)
    }
  }

  /** Dalla piu' recente, al piu' [limit] (e comunque [SEARCH_HISTORY_MAX]). */
  suspend fun searchHistory(limit: Int = SEARCH_HISTORY_MAX): List<SearchHistoryRecord> =
    historyDao.recent(limit.coerceIn(0, SEARCH_HISTORY_MAX)).map { it.toRecord() }

  /** Come [searchHistory], vivo: riemesso a ogni cambiamento. */
  fun searchHistoryChanges(limit: Int = SEARCH_HISTORY_MAX): Flow<List<SearchHistoryRecord>> =
    historyDao.observe(limit.coerceIn(0, SEARCH_HISTORY_MAX)).map { rows -> rows.map { it.toRecord() } }

  suspend fun deleteSearch(id: Long) = historyDao.delete(id)

  /** "Cancella tutte" e l'azzeramento. */
  suspend fun clearSearchHistory() = historyDao.deleteAll()

  // --- Conversioni -------------------------------------------------------------------------------------------------------

  private fun AiVectorRow.toEntity() = AiVectorEntity(
    contentKey.hex, space.code, recipe, frame, source.code, instantMs, byteOffset, byteLength, crc, createdAt,
  )

  /** Un codice che questa versione non conosce (scritto da un'altra) non e' "per me": la riga si salta. */
  private fun AiVectorEntity.toRow(): AiVectorRow? {
    val spaceCode = enumByCodeOrNull<SpaceCode>(space) ?: return null
    val sourceCode = enumByCodeOrNull<VecSource>(source) ?: return null
    return AiVectorRow(ContentKey(contentKey), spaceCode, recipe, frame, sourceCode, instantMs, byteOffset, byteLength, crc, createdAt)
  }

  private fun AiTextRecord.toEntity() = AiTextEntity(
    contentKey = contentKey.hex, recipe = recipe, engine = engine.code, source = source.code, text = text, lines = lines, chars = chars,
    layout = layout, createdAt = createdAt,
  )

  private fun AiTextEntity.toRecord(): AiTextRecord? {
    val engineCode = enumByCodeOrNull<OcrEngineCode>(engine) ?: return null
    val sourceCode = enumByCodeOrNull<OcrSource>(source) ?: return null
    return AiTextRecord(ContentKey(contentKey), recipe, engineCode, sourceCode, text, lines, chars, layout, createdAt)
  }

  private fun SearchHistoryEntity.toRecord() =
    SearchHistoryRecord(id, text, if (chips.isEmpty()) emptyList() else chips.split('\n'), personsOf(persons))

  /** Gli id delle persone come stanno nella riga: UUID canonici separati da una virgola, senza ripetizioni. */
  private fun ownersOf(persons: List<PersonId>): String = persons.distinct().take(MAX_HISTORY_PERSONS).joinToString(",") { it.value }

  /** Gli id di una riga; un valore che non e' un id si scarta (un database toccato a mano non rompe la lettura). */
  private fun personsOf(stored: String): List<PersonId> = if (stored.isEmpty()) emptyList() else stored.split(',').mapNotNull { PersonId.parse(it) }

  /** Un termine in minuscolo ridotto a `[a-z0-9 ]` con spazi singoli, al piu' [MAX_TERM_CHARS] caratteri (mai testo dell'utente com'e' in una `MATCH`). */
  private fun literal(term: String): String {
    val sb = StringBuilder(minOf(term.length, MAX_TERM_CHARS))
    var space = true
    for (c in term.take(MAX_TERM_CHARS).lowercase(Locale.ROOT)) {
      if (c in 'a'..'z' || c in '0'..'9') {
        sb.append(c)
        space = false
      } else if (!space) {
        sb.append(' ')
        space = true
      }
    }
    return sb.toString().trimEnd()
  }

  companion object {
    const val MAX_TERMS: Int = 16
    const val MAX_ALTERNATIVES: Int = 8
    const val MAX_TERM_CHARS: Int = 200
    const val MAX_WORD_CHARS: Int = 64
    const val MAX_HISTORY_TEXT: Int = 200
    const val MAX_HISTORY_CHIPS: Int = 16
    const val MAX_CHIP_LABEL: Int = 80
    const val MAX_HISTORY_PERSONS: Int = 64
  }
}

/** Una pagina di righe di vettori in ordine di chiave; [next] e' il cursore della pagina dopo, o null se e' finita. */
class VectorPage(val rows: List<AiVectorRow>, val next: ContentKey?)
