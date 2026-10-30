package dev.pampa.mycloudgallery.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

// Schema v5 (Fase 6, D23, D25, D32, D37; `docs/modello-dati.md` §13): l'indice locale dei dati AI e la cronologia della ricerca.
//
// **Nessuna di queste tabelle punta a `media`** (D37): vettori e testo stanno per **chiave di contenuto** (`ContentKey.hex`, 64 cifre:
// SHA-256 di `qh1` e dimensione), da qualunque origine (telefono, area privata, area comune). Il collegamento a un media passa dalle
// versioni (`media_version.size`, `quick_hash`): `MediaCatalog.cellContentKeys`. Per questo l'unione e la rinomina d'identita' non le
// toccano (`MediaCatalog.TABLES_WITHOUT_MEDIA_IDS`). I codici (spazio, sorgente, motore) sono quelli di `StableCode` di core-model,
// scritti come testo: un codice sconosciuto non lo scrive mai il database (E-I20).

/**
 * Un vettore di ricerca (D23, D24): la **riga di indice** di un vettore float16 che sta nel file in sola aggiunta
 * `noBackupFilesDir/search/vectors-<spazio>.bin` (e' l'unica copia dei vettori: Room tiene solo dove sono e il CRC del record).
 *
 * - `space`: `SpaceCode.code` (modello, dimensione, precisione): due spazi convivono (reindicizzazione graduale, D21) e una query
 *   non li mescola mai (E-I4);
 * - `recipe`: come si sono preparati gli ingressi (1..255): una ricetta nuova resta nello stesso spazio, il vettore vecchio vale
 *   finche' non arriva il nuovo;
 * - `frame`: 0 per le immagini; per un video il fotogramma (al piu' 9, D11), con l'istante in `instant_ms`;
 * - `source`: `VecSource.code`;
 * - `byte_offset`, `byte_length`, `crc`: dove sta il record nel file e il suo CRC32 (senza segno, in un `Long`).
 */
@Entity(
  tableName = "ai_vector",
  primaryKeys = ["content_key", "space", "recipe", "frame"],
  // La copertura per (spazio, ricetta) e la scansione di uno spazio senza passare dalle chiavi.
  indices = [Index(value = ["space", "recipe"])],
)
data class AiVectorEntity(
  @ColumnInfo(name = "content_key") val contentKey: String,
  @ColumnInfo(name = "space") val space: String,
  @ColumnInfo(name = "recipe") val recipe: Int,
  @ColumnInfo(name = "frame") val frame: Int,
  @ColumnInfo(name = "source") val source: String,
  @ColumnInfo(name = "instant_ms") val instantMs: Long?,
  @ColumnInfo(name = "byte_offset") val byteOffset: Long,
  @ColumnInfo(name = "byte_length") val byteLength: Int,
  @ColumnInfo(name = "crc") val crc: Long,
  @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Il testo letto di un contenuto (D23, D25), una riga per chiave di contenuto.
 *
 * - `text`: il testo **gia' normalizzato** (`SearchText.normalize` di core-search: minuscolo, senza accenti, parole separate da
 *   spazi); e' quello che indicizza [AiTextFtsEntity] e da cui si ricava il vocabolario. Puo' essere vuoto (letto, nessun testo:
 *   "non rileggere");
 * - `layout`: il testo letto con le righe e i riquadri per il testo dal vivo (D43), in un formato **opaco** per il database (lo
 *   scrivono e lo leggono P6/P12); NULL se non conservato;
 * - `lines`, `chars`: righe e caratteri del testo **letto** (non del normalizzato): la regola del bit `DOCUMENT` (D34: 6 righe o 200
 *   caratteri) senza rileggere il testo;
 * - `recipe`, `engine` (`OcrEngineCode.code`), `source` (`OcrSource.code`): come e' stato letto.
 *
 * Il rowid (`text_id`) e' la chiave del contenuto esterno della tabella FTS4: un testo cambia con `UPDATE` (mai `INSERT OR REPLACE`:
 * con `recursive_triggers` spento la cancellazione implicita non aggiornerebbe l'indice del testo).
 */
@Entity(
  tableName = "ai_text",
  indices = [Index(value = ["content_key"], unique = true)],
)
class AiTextEntity(
  @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "text_id") val textId: Long = 0,
  @ColumnInfo(name = "content_key") val contentKey: String,
  @ColumnInfo(name = "recipe") val recipe: Int,
  @ColumnInfo(name = "engine") val engine: String,
  @ColumnInfo(name = "source") val source: String,
  @ColumnInfo(name = "text") val text: String,
  @ColumnInfo(name = "lines") val lines: Int,
  @ColumnInfo(name = "chars") val chars: Int,
  @ColumnInfo(name = "layout", typeAffinity = ColumnInfo.BLOB) val layout: ByteArray?,
  @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * L'indice del testo (D25): FTS4 di Room a contenuto esterno su `ai_text.text`, tokenizzatore `simple` (la normalizzazione e' nostra,
 * uguale su ogni dispositivo e in Robolectric). Room crea i tre trigger che lo tengono allineato. Le query passano da
 * `AiIndexDao.matchTerm`: i termini diventano sempre frasi letterali, mai sintassi `MATCH` dall'utente (E-Q9).
 */
@Fts4(contentEntity = AiTextEntity::class, tokenizer = FtsOptions.TOKENIZER_SIMPLE)
@Entity(tableName = "ai_text_fts")
class AiTextFtsEntity(
  @ColumnInfo(name = "text") val text: String,
)

/**
 * Il vocabolario locale delle parole lette (D25), per i refusi e i completamenti: per ogni parola in quanti testi compare (`docs`).
 * Lo tiene `MediaCatalog.putText`/`removeText` nella stessa transazione del testo; una parola a 0 esce. Contiene anche le parole dei
 * media nascosti (D29): chi lo legge non le propone mai nella schermata Cerca.
 */
@Entity(tableName = "ai_vocabulary")
data class AiVocabularyEntity(
  @PrimaryKey @ColumnInfo(name = "word") val word: String,
  @ColumnInfo(name = "docs") val docs: Int,
)

/**
 * Una ricerca recente (D32): testo e chip come etichette (separate da `\n`), **senza miniature**. Mai sincronizzata, mai nei dati
 * personali ne' nell'esportazione della Diagnostica, tolta dall'azzeramento. Niente orario: l'ordine e' quello dell'id (mai
 * riusato, `AUTOINCREMENT`), la piu' recente per ultima; la stessa ricerca (stesso testo) e' una riga sola. Al massimo
 * [SEARCH_HISTORY_MAX] righe.
 */
@Entity(tableName = "search_history", indices = [Index(value = ["text"])])
data class SearchHistoryEntity(
  @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
  @ColumnInfo(name = "text") val text: String,
  @ColumnInfo(name = "chips") val chips: String,
  /** Fase 8, correzioni D (R2): gli id delle persone dei chip "Persona" (UUID canonici separati da una virgola), vuota se non ce n'erano. */
  @ColumnInfo(name = "persons", defaultValue = "''") val persons: String = "",
)

/** Il tetto della cronologia (`SearchHistory.MAX_ENTRIES` di core-search, che core-database non vede: un test li confronta). */
const val SEARCH_HISTORY_MAX: Int = 50
