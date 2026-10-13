package dev.pampa.mycloudgallery.core.library.contract

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import kotlinx.coroutines.flow.StateFlow

// L'indice della timeline (docs/fase3-progetto.md D2, §1.2 `core-library`). Contratti di P0; l'indice vero a colonne,
// l'istantanea su file e il repository sono di P5. Niente Paging 3 (D2): la Libreria ha accesso casuale a tutta la
// timeline in memoria (100 mila elementi occupano circa 8 MB).

/**
 * Le funzioni della Libreria accese o spente in Fase 3.
 *
 * **D15: i badge di presenza (solo telefono, solo NAS, al sicuro, in caricamento) restano spenti fino alla Fase 4.**
 * Senza il NAS ogni media sarebbe "solo telefono": sarebbe rumore su ogni cella. Il flag e' una costante: lo
 * accende la Fase 4 cambiando una riga, e chi disegna le celle la legge invece di dedurre dalla fase.
 */
object LibraryFeatures {
  const val presenceBadges: Boolean = false
}

/**
 * L'indice della timeline a **colonne** (una per attributo), immutabile. Il valore `i` va da 0 a `size - 1`
 * nell'ordine dell'indice: **0 e' il piu' recente** (`sort_time DESC, media_id DESC`, la query del catalogo). Il
 * senso di lettura sullo schermo (D1: i piu' recenti in fondo) lo decide il layout, non l'indice.
 *
 * Costa circa 80 byte per elemento. **Non contiene mai i nascosti** (E-H1), ne' i membri di un gruppo che non lo
 * rappresentano (D20): ogni elemento e' una cella. Le colonne si leggono per indice, senza oggetti per riga: e' il
 * percorso caldo dello scorrimento.
 */
@Stable
interface TimelineSnapshot {

  /** Quanti elementi. */
  val size: Int

  /** La sequenza del catalogo (`change_seq`) da cui l'indice e' stato costruito: cresce a ogni scrittura. */
  val seq: Long

  /** L'identita' dell'elemento (crea un `MediaId`: non si usa nel percorso caldo, solo per aprire e selezionare). */
  fun idAt(i: Int): MediaId

  /** L'istante di scatto in millisecondi UTC (`sort_time`). */
  fun sortTimeAt(i: Int): Long

  /** Il giorno **nell'ora del posto** dello scatto, come `aaaammgg` (E-P5): le sezioni e le intestazioni. */
  fun localDayAt(i: Int): Int

  fun kindAt(i: Int): MediaKind

  fun sourceAt(i: Int): MediaSource

  /** I bit di `CellFlags`. */
  fun flagsAt(i: Int): Int

  /** La proporzione larghezza/altezza **gia' orientata**, in millesimi (1000 = quadrata). */
  fun aspectMilliAt(i: Int): Int

  /** La durata di un video in millisecondi, 0 per tutto il resto. */
  fun durationMsAt(i: Int): Long

  /** L'id GeoNames del luogo (`place_id`), 0 se non c'e'. Il nome lo risolve la UI con `PlaceNames`. */
  fun placeAt(i: Int): Int

  /** Il segnaposto sfocato, se c'e'. */
  fun thumbHashAt(i: Int): ThumbHash?

  /** Quanti media rappresenta la cella: 1, o i membri di una raffica o di una coppia RAW+JPEG (copertina). */
  fun groupSizeAt(i: Int): Int

  /** La posizione di [id] nell'indice, o -1 se non c'e'. */
  fun indexOf(id: MediaId): Int

  /** Se la cella ha il bit [flag] di [CellFlags]. */
  fun hasFlag(i: Int, flag: Int): Boolean = CellFlags.has(flagsAt(i), flag)
}

/**
 * Il filtro rapido della Libreria (D16), ricordato fra un'apertura e l'altra.
 * "Documenti" arriva con la Fase 6, perche' serve capire il contenuto.
 */
enum class LibraryFilter(val code: String) {
  /** Tutto. */
  ALL("all"),

  /** Senza screenshot e WhatsApp: la galleria "come l'ha scattata la fotocamera". */
  WITHOUT_SCREENSHOTS_AND_WHATSAPP("no_screenshots_whatsapp"),

  SCREENSHOTS("screenshots"),

  WHATSAPP("whatsapp"),

  VIDEOS("videos"),

  FAVORITES("favorites"),
  ;

  /**
   * Se una cella con questi attributi passa il filtro. E' la definizione di ogni filtro (e' qui perche' la
   * leggono sia l'indice sia la Raccolta filtrata con lo stesso significato).
   */
  fun accepts(kind: MediaKind, source: MediaSource, flags: Int): Boolean = when (this) {
    ALL -> true
    WITHOUT_SCREENSHOTS_AND_WHATSAPP -> source != MediaSource.SCREENSHOT && source != MediaSource.WHATSAPP
    SCREENSHOTS -> source == MediaSource.SCREENSHOT
    WHATSAPP -> source == MediaSource.WHATSAPP
    VIDEOS -> kind == MediaKind.VIDEO
    FAVORITES -> CellFlags.has(flags, CellFlags.FAVORITE)
  }

  companion object {
    /** Il filtro per codice salvato; sconosciuto = [ALL] (una versione piu' nuova dell'app ne ha altri). */
    fun fromCode(code: String?): LibraryFilter = entries.firstOrNull { it.code == code } ?: ALL
  }
}

/**
 * Una timeline con un filtro applicato: la lista che la griglia e il viewer scorrono. Il viewer segue **la stessa
 * vista** della Libreria (stesso filtro): scorrere nel viewer e' scorrere la griglia.
 *
 * [size] e' il numero di elementi che passano il filtro; la posizione `v` nella vista corrisponde alla posizione
 * [snapshotIndex] di `v` nell'indice. Senza filtro la mappa e' l'identita'.
 */
@Stable
interface TimelineView {
  val snapshot: TimelineSnapshot
  val filter: LibraryFilter
  val size: Int

  /** La posizione nell'indice dell'elemento `viewIndex` della vista. */
  fun snapshotIndex(viewIndex: Int): Int

  /** La posizione di [id] nella vista, o -1 se non c'e' (o non passa il filtro). */
  fun viewIndexOf(id: MediaId): Int
}

/**
 * Il repository della timeline. Parte dall'istantanea su file (avvio a freddo sotto il secondo), poi riconcilia il
 * catalogo con `change_seq` e si aggiorna a pezzi dal flusso dei cambiamenti. [view] da' la vista per un filtro;
 * le viste con lo stesso filtro condividono lo stesso lavoro.
 */
interface TimelineRepository {

  /** L'indice corrente: null finche' non e' stato costruito o letto dall'istantanea. */
  val snapshot: StateFlow<TimelineSnapshot?>

  /** Avvia la lettura (istantanea, poi riconciliazione). Idempotente; mai sul thread principale. */
  suspend fun start()

  fun view(f: LibraryFilter): StateFlow<TimelineView>
}

/** Le preferenze della Libreria, ricordate in `noBackupFilesDir` (DataStore): filtro, livello e stile. */
interface LibraryPreferences {
  val filter: StateFlow<LibraryFilter>
  val level: StateFlow<GridLevel>
  val style: StateFlow<GridStyle>

  suspend fun setFilter(filter: LibraryFilter)

  suspend fun setLevel(level: GridLevel)

  suspend fun setStyle(style: GridStyle)
}

/**
 * Azioni sui dati personali, **locali** (preferiti e nascosti, in `media_personal`): legate all'identita' del media,
 * sopravvivono a id, cestino, modifiche e unioni (in un'unione il nascosto vince). Dalla 4.7 si sincronizzano per
 * campo. Un nascosto sparisce dall'indice e dall'istantanea (E-H1).
 */
interface LocalMediaActions {
  suspend fun setFavorite(ids: Set<MediaId>, on: Boolean)

  suspend fun setHidden(ids: Set<MediaId>, on: Boolean)
}

/** Un valore che la UI riceve e confronta: l'insieme dei media selezionati, immutabile. */
@Immutable
data class SelectionSet(val ids: Set<MediaId> = emptySet()) {
  val size: Int get() = ids.size
  val isEmpty: Boolean get() = ids.isEmpty()

  operator fun contains(id: MediaId): Boolean = id in ids
}
