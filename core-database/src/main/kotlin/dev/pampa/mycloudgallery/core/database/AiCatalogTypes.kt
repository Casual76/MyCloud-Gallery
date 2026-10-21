package dev.pampa.mycloudgallery.core.database

import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.OcrEngineCode
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.model.VecSource

// I tipi delle operazioni del catalogo della Fase 6 (P1, schema v5): l'indice locale dei dati AI per chiave di contenuto, le chiavi delle
// celle e la cronologia della ricerca. Nessuno e' un'identita' di media salvata: i dati stanno per chiave di contenuto (D23).

/**
 * Le chiavi di contenuto di una cella della Libreria (la mappa delle celle di P6, §2.4). Una cella e' un media visibile, non nascosto,
 * non dietro la copertina del suo gruppo; [seq] e' il `rowid` di inserimento del media (il cursore di [MediaCatalog.cellContentKeys]).
 *
 * @property own le versioni del media della cella: la **corrente** per prima, poi le precedenti dalla piu' recente (se la versione
 *   corrente non ha ancora un vettore, vale quello di una precedente: E-A10).
 * @property members per ogni **altro** membro visibile e non nascosto del suo gruppo (raffica, RAW+JPEG, copie simili), le sue versioni
 *   (la corrente per prima), in ordine di inserimento (`seq`). Vuoto per un media senza gruppo.
 */
class CellContent(val seq: Long, val mediaId: MediaId, val own: List<ContentKey>, val members: List<MemberContent>) {

  /** Tutte le chiavi, senza doppioni: le correnti della cella e dei membri, poi le versioni precedenti nello stesso ordine. */
  val keys: List<ContentKey> by lazy {
    val firsts = listOfNotNull(own.firstOrNull()) + members.mapNotNull { it.keys.firstOrNull() }
    val olds = own.drop(1) + members.flatMap { it.keys.drop(1) }
    (firsts + olds).distinct()
  }

  override fun toString(): String = "CellContent($seq, ${own.size} versioni, ${members.size} membri)"
}

/** Le versioni di un membro di gruppo (la corrente per prima). */
class MemberContent(val mediaId: MediaId, val keys: List<ContentKey>)

/** Una pagina di [CellContent] in ordine di [CellContent.seq]; [next] e' il cursore della pagina dopo, o null se e' finita. */
class CellContentPage(val cells: List<CellContent>, val next: Long?)

/** Una riga di `ai_vector`: dove sta il vettore float16 nel file in sola aggiunta dello spazio, con il suo CRC32. */
data class AiVectorRow(
  val contentKey: ContentKey,
  val space: SpaceCode,
  val recipe: Int,
  /** 0 per le immagini; per un video il fotogramma (0..8). */
  val frame: Int,
  val source: VecSource,
  /** L'istante del fotogramma di un video, in millisecondi; null per le immagini. */
  val instantMs: Long?,
  val byteOffset: Long,
  val byteLength: Int,
  /** CRC32 del record, senza segno. */
  val crc: Long,
  val createdAt: Long,
) {
  init {
    require(recipe in 1..255) { "Ricetta fuori misura." }
    require(frame in 0 until MAX_FRAMES) { "Fotogramma fuori misura." }
    require(byteOffset >= 0 && byteLength > 0) { "Posizione non valida." }
    require(crc in 0..MAX_CRC) { "CRC32 non valido." }
  }

  companion object {
    /** Al piu' 9 fotogrammi per video (D11). */
    const val MAX_FRAMES: Int = 9
    private const val MAX_CRC: Long = 0xFFFF_FFFFL
  }
}

/**
 * Il testo letto di un contenuto (`ai_text`). [text] e' il testo **normalizzato** (minuscolo, senza accenti, solo parole `[a-z0-9]`
 * separate da uno spazio: `SearchText.normalize` di core-search): lo si rifiuta se non lo e' (indice e vocabolario si fidano). Puo'
 * essere vuoto: "letto, nessun testo". [lines] e [chars] misurano il testo **letto**; [layout] e' opaco per il database.
 */
class AiTextRecord(
  val contentKey: ContentKey,
  val recipe: Int,
  val engine: OcrEngineCode,
  val source: OcrSource,
  val text: String,
  val lines: Int,
  val chars: Int,
  layout: ByteArray? = null,
  val createdAt: Long,
) {
  private val rawLayout: ByteArray? = layout?.copyOf()

  val layout: ByteArray? get() = rawLayout?.copyOf()

  init {
    require(recipe in 1..255) { "Ricetta fuori misura." }
    require(lines >= 0 && chars >= 0) { "Misure negative." }
    require(isNormalized(text)) { "Il testo non e' normalizzato." }
    require(rawLayout == null || rawLayout.size <= MAX_LAYOUT_BYTES) { "Layout troppo grande." }
  }

  /** Mai il testo (D40). */
  override fun toString(): String = "AiTextRecord(r$recipe, $lines righe)"

  companion object {
    /** Il tetto del layout opaco (le righe e i riquadri di una foto). */
    const val MAX_LAYOUT_BYTES: Int = 256 * 1024

    /** Solo `a-z`, `0-9` e singoli spazi in mezzo (nessuno all'inizio o alla fine). */
    fun isNormalized(text: String): Boolean {
      var previousSpace = true
      for (c in text) {
        if (c == ' ') {
          if (previousSpace) return false
          previousSpace = true
        } else if (c in 'a'..'z' || c in '0'..'9') {
          previousSpace = false
        } else {
          return false
        }
      }
      return text.isEmpty() || !previousSpace
    }
  }
}

/** Le misure di un testo letto (regola del bit `DOCUMENT`, D34: 6 righe o 200 caratteri). */
data class AiTextStats(val lines: Int, val chars: Int)

/**
 * Le chiavi che hanno risposto a una ricerca nel testo: ogni termine e' l'elenco delle sue **alternative** (la parola e i suoi refusi
 * corretti dal vocabolario, o una frase fra virgolette); [hits] dice a quanti termini risponde ogni chiave. Tutti i termini = "sicuro",
 * almeno meta' = "meno probabile" (D27).
 */
class TextMatchCounts(val terms: Int, val hits: Map<ContentKey, Int>) {
  val all: Set<ContentKey> get() = hits.filterValues { it == terms }.keys

  val atLeastHalf: Set<ContentKey> get() = hits.filterValues { it * 2 >= terms }.keys
}

/** Una parola del vocabolario locale con in quanti testi compare. */
data class VocabularyWord(val word: String, val docs: Int)

/** Una ricerca recente (D32): testo e chip come etichette, senza miniature. */
data class SearchHistoryRecord(val id: Long, val text: String, val chipLabels: List<String>) {
  /** Mai il testo (D40). */
  override fun toString(): String = "SearchHistoryRecord($id, ${chipLabels.size} chip)"
}
