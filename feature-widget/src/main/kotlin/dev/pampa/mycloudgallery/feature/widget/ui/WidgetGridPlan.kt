package dev.pampa.mycloudgallery.feature.widget.ui

import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize

/**
 * Come si dispongono le celle di un widget (D3), come **dati puri**: gruppi di righe di indici di cella. Glance sa dare lo stesso peso ai figli (`defaultWeight`) e
 * nient'altro, quindi le misure diverse si ottengono **annidando**: ogni gruppo prende la stessa parte dell'altezza e le sue righe se la dividono in parti uguali.
 *
 * - **piccola**: la cella 0 sola (la foto, o la copertina del ricordo);
 * - **larga**: una riga con tutte le celle (fino a 4: la copertina e tre foto, o quattro foto);
 * - **grande**: la cella 0 in un gruppo da sola (mezza altezza: la copertina, o la prima foto) e le altre (fino a 6) in due righe da tre nell'altra meta'.
 *
 * Con meno celle del massimo (una foto saltata perche' non ha la miniatura locale, il tetto dei 2 MiB) il disegno si accorcia senza buchi: una riga corta resta corta
 * (le celle prendono la loro parte, il resto e' vuoto), mai un segnaposto.
 */
internal object WidgetGridPlan {

  /** I gruppi di righe di indici per la misura [size] con [count] celle (0..7). Vuoto se non ci sono celle. */
  fun groups(size: WidgetSize, count: Int): List<List<List<Int>>> {
    val n = count.coerceIn(0, size.cells)
    if (n == 0) return emptyList()
    return when (size) {
      WidgetSize.SMALL -> listOf(listOf(listOf(0)))
      WidgetSize.WIDE -> listOf(listOf((0 until n).toList()))
      WidgetSize.LARGE -> {
        val rest = (1 until n).toList()
        if (rest.isEmpty()) listOf(listOf(listOf(0))) else listOf(listOf(listOf(0)), rest.chunked(COLUMNS))
      }
    }
  }

  /** Quante celle per riga nella misura grande (sotto la copertina). */
  const val COLUMNS: Int = 3

  /**
   * Quanti posti vuoti aggiungere a una riga di [rowSize] celle del gruppo [groupIndex] per tenere le colonne allineate: solo le righe da tre della misura grande (il
   * gruppo 1); una riga della misura larga che ha meno di quattro celle ne prende la parte intera, senza buchi.
   */
  fun paddingOf(size: WidgetSize, groupIndex: Int, rowSize: Int): Int =
    if (size == WidgetSize.LARGE && groupIndex == 1) (COLUMNS - rowSize).coerceAtLeast(0) else 0

  /** Le celle di ogni riga di [groups], nell'ordine (per i test: ogni indice compare una volta sola). */
  fun flatten(groups: List<List<List<Int>>>): List<Int> = groups.flatten().flatten()
}
