package dev.pampa.mycloudgallery.core.library.contract

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import java.time.YearMonth

// Il layout della griglia (docs/fase3-progetto.md D3, D4, §2.4). Contratti di P0; il motore vero, l'ancoraggio del
// pinch e lo scrubber sono di P5. La griglia e' una `LazyColumn` di **righe precalcolate**: ogni riga e' un'intestazione
// o una fila di celle, con altezza e posizione note in anticipo (somme prefisse). Cosi' lo scrubber, il pinch ancorato
// e il ritorno dal viewer sono aritmetica in O(log n), non misure.

/** I quattro livelli della griglia, dal piu' lontano al piu' vicino. */
enum class GridLevel(val code: String) {
  YEARS("years"),
  MONTHS("months"),
  DAYS("days"),
  ALL("all"),
  ;

  /** Se le sezioni di questo livello hanno una riga di intestazione ("Tutte" non ne ha: la data sta nella barra). */
  val hasHeaders: Boolean get() = this != ALL

  companion object {
    fun fromCode(code: String?): GridLevel = entries.firstOrNull { it.code == code } ?: DAYS
  }
}

/**
 * Lo stile delle celle (D4). Di default la griglia e' [SQUARE]; [JUSTIFIED] ("proporzioni originali") fa righe alla
 * stessa altezza con larghezze date dalla proporzione, entro 1:2 e 2:1 come la cella approvata nel catalogo 1.2.
 */
enum class GridStyle(val code: String) {
  SQUARE("square"),
  JUSTIFIED("justified"),
  ;

  companion object {
    fun fromCode(code: String?): GridStyle = entries.firstOrNull { it.code == code } ?: SQUARE
  }
}

/**
 * Tutto cio' che il motore di layout deve sapere dello schermo. [columns] e' il numero di colonne del livello alla
 * larghezza data (la UI lo prende da `GalleryGridLevels.forWidth`: sul telefono da 412 dp 7, 5, 4 e 3, sul tablet da
 * 1280 dp fino a 16, 12, 10 e 8); con lo stile giustificato l'altezza della riga e' la cella quadrata del livello.
 *
 * @property newestAtBottom D1: i piu' recenti in fondo, come Apple Foto (default). Un parametro solo, cosi'
 *   cambiare idea costa una riga.
 */
@Immutable
data class GridSpec(
  val level: GridLevel,
  val style: GridStyle,
  val columns: Int,
  val widthPx: Int,
  val gapPx: Int,
  val densityDp: Float,
  val newestAtBottom: Boolean = true,
) {
  init {
    require(columns >= 1) { "Almeno una colonna." }
    require(widthPx > 0 && gapPx >= 0 && densityDp > 0f) { "Misure non valide." }
  }

  /** Il lato della cella quadrata del livello, in pixel. */
  val cellPx: Int get() = ((widthPx - gapPx * (columns - 1)) / columns).coerceAtLeast(1)
}

/** Che cosa e' una riga della griglia. */
enum class RowKind { HEADER, CELLS }

/**
 * Una sezione della griglia: un anno, un mese o un giorno (per [GridLevel.ALL] sono mesi, senza riga di
 * intestazione). Porta quello che serve alla riga di intestazione ("Marzo 2024" + luoghi) e allo scrubber.
 *
 * @property firstDay il primo giorno (`aaaammgg`) e [lastDay] l'ultimo, in ordine cronologico.
 * @property topPlaces gli id dei luoghi piu' frequenti della sezione, in ordine decrescente, per il riassunto
 *   ("Firenze e altri 2 luoghi") che `PlaceNames` trasforma in testo.
 * @property distinctPlaces quanti luoghi diversi.
 */
@Immutable
class SectionInfo(
  val level: GridLevel,
  val firstDay: Int,
  val lastDay: Int,
  val count: Int,
  val topPlaces: IntArray,
  val distinctPlaces: Int,
) {
  val firstMonth: YearMonth get() = YearMonth.of(firstDay / 10_000, (firstDay / 100) % 100)

  override fun equals(other: Any?): Boolean =
    other is SectionInfo && other.level == level && other.firstDay == firstDay && other.lastDay == lastDay &&
      other.count == count && other.distinctPlaces == distinctPlaces && other.topPlaces.contentEquals(topPlaces)

  override fun hashCode(): Int = ((level.hashCode() * 31 + firstDay) * 31 + lastDay) * 31 + count

  override fun toString(): String = "SectionInfo($level, $firstDay..$lastDay, $count)"
}

/**
 * Il layout calcolato: la geometria di ogni riga, in pixel. Una **posizione** e' l'ordine di lettura sullo schermo
 * (0 = la prima cella in alto a sinistra); con `newestAtBottom` la posizione `p` corrisponde alla posizione
 * `size - 1 - p` della vista ([viewIndexAt]), cioe' la vista e' letta dalla fine.
 *
 * Tutte le funzioni sono O(1) o O(log n) e non allocano: le chiama il percorso di scorrimento.
 */
@Stable
interface GridLayout {

  val spec: GridSpec

  /** Quante righe (intestazioni comprese). */
  val rowCount: Int

  /** Quante celle in tutto (= `TimelineView.size`). */
  val itemCount: Int

  /** L'altezza totale in pixel (somma delle righe e degli spazi). */
  val totalHeight: Int

  val sections: List<SectionInfo>

  fun rowKind(row: Int): RowKind

  /** La posizione verticale del bordo alto della riga, in pixel: somma prefissa. */
  fun rowTop(row: Int): Int

  fun rowHeight(row: Int): Int

  /** Le posizioni delle celle di una riga di celle (vuoto per un'intestazione). */
  fun itemsOf(row: Int): IntRange

  /** La riga che contiene la cella in posizione [position]. */
  fun rowOfItem(position: Int): Int

  /** La sezione (indice in [sections]) a cui appartiene la riga. */
  fun sectionOfRow(row: Int): Int

  /** Il bordo sinistro e la larghezza della cella in posizione [position], in pixel. */
  fun itemLeft(position: Int): Int

  fun itemWidth(position: Int): Int

  /** La cella (posizione) alla coordinata orizzontale [x] della riga, o -1 se cade in uno spazio o in un'intestazione. */
  fun itemAt(row: Int, x: Float): Int

  /** La riga alla coordinata verticale [y] (contenuto, non schermo): ricerca binaria sulle somme prefisse. */
  fun rowAtOffset(y: Int): Int

  /** La posizione nella vista dell'elemento in posizione [position] (tiene conto di `newestAtBottom`). */
  fun viewIndexAt(position: Int): Int

  /** L'inverso di [viewIndexAt]. */
  fun positionOfViewIndex(viewIndex: Int): Int
}

/** Il motore di layout: dalla vista e dallo schermo al [GridLayout]. Puro e senza stato: si precalcola fuori dal thread principale. */
interface GridLayoutEngine {
  fun layout(v: TimelineView, s: GridSpec): GridLayout
}

/**
 * Il punto della griglia che deve restare sotto il dito quando il layout cambia (pinch fra i livelli, cambio di stile,
 * rotazione, ritorno dal viewer): la cella, dove nella cella, e dove sullo schermo stava il dito.
 *
 * @property position la cella (posizione di lettura).
 * @property fx , [fy] il punto dentro la cella, come frazioni di larghezza e altezza (0..1).
 * @property screenY l'ordinata del dito rispetto al bordo alto dell'area visibile, in pixel.
 */
@Immutable
data class Anchor(val position: Int, val fx: Float, val fy: Float, val screenY: Float)

/**
 * Dove portare lo scorrimento perche' un'[Anchor] resti sotto il dito nel nuovo layout: la riga e lo scostamento da
 * passare **nello stesso fotogramma** a `LazyListState.requestScrollToItem(row, offsetPx)`.
 */
@Immutable
data class ScrollTarget(val row: Int, val offsetPx: Int)

/** L'ancoraggio del pinch e del ritorno dal viewer (D3). In O(log n): nessuna misura. */
interface GridAnchors {

  /** L'ancora per un dito appoggiato alla riga [row] del layout [l], a [x] orizzontale e [yInRow] dal bordo alto della riga. */
  fun anchorAt(l: GridLayout, row: Int, x: Float, yInRow: Float, screenY: Float): Anchor

  /** Lo scorrimento che, nel layout [l2], rimette [a] sotto lo stesso punto dello schermo. */
  fun place(l2: GridLayout, a: Anchor): ScrollTarget
}

/** Lo scrubber: la mappa fra la frazione della barra laterale e le righe, con la bolla "marzo 2024". */
interface ScrubberModel {

  /** La riga alla frazione [fraction] (0..1) dell'altezza del contenuto. */
  fun rowAt(l: GridLayout, fraction: Float): Int

  /** La frazione (0..1) a cui sta la riga [row]. */
  fun fractionOf(l: GridLayout, row: Int): Float

  /** Il mese da scrivere nella bolla per la riga [row]. */
  fun label(l: GridLayout, row: Int): YearMonth
}
