package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Una riga dell'elenco delle Raccolte.
 *
 * @property count quanti elementi, per le raccolte-filtro; **null** per i Nascosti e gli Eliminati di recente: la riga
 *   dei Nascosti non dice mai quanti sono (E-H1: dall'esterno non si deve nemmeno capire se ce ne sono).
 */
@Immutable
data class CollectionRow(val kind: CollectionKind, val count: Int?)

/**
 * Lo stato dell'elenco: due gruppi, come una pagina di Impostazioni (regola 2 del design system). Il primo ha le
 * quattro raccolte-filtro; il secondo i due posti protetti, e gli Eliminati di recente solo dove il cestino di
 * sistema esiste (Android 11+: nessuna voce morta, D13).
 */
@Immutable
data class CollectionsUiState(
  val media: List<CollectionRow>,
  val protectedRows: List<CollectionRow>,
) {
  /** Tutte le righe, nell'ordine dell'elenco. */
  val all: List<CollectionRow> get() = media + protectedRows

  /** Se [kind] e' nell'elenco (gli Eliminati di recente possono mancare). */
  operator fun contains(kind: CollectionKind): Boolean = all.any { it.kind == kind }
}

/** Costruisce lo stato dell'elenco dai conteggi delle quattro raccolte-filtro. Pura: la provano i test su JVM. */
fun collectionsUiState(counts: Map<CollectionKind, Int>, canTrash: Boolean): CollectionsUiState = CollectionsUiState(
  media = CollectionKind.filters.map { CollectionRow(it, counts[it] ?: 0) },
  protectedRows = buildList {
    add(CollectionRow(CollectionKind.HIDDEN, count = null))
    if (canTrash) add(CollectionRow(CollectionKind.RECENTLY_DELETED, count = null))
  },
)

/** I conteggi vivi delle raccolte-filtro: si aggiornano con l'indice (una modifica esterna, un preferito nuovo). */
internal fun CollectionItemsSource.counts(): Flow<Map<CollectionKind, Int>> {
  val flows = CollectionKind.filters.map { kind -> items(kind.filter!!).view.map { view -> kind to view.size } }
  return combine(flows) { pairs -> pairs.toMap() }
}

/** I conteggi di adesso, senza aspettare il flusso: lo stato iniziale della schermata. */
internal fun CollectionItemsSource.countsNow(): Map<CollectionKind, Int> =
  CollectionKind.filters.associateWith { kind -> items(kind.filter!!).view.value.size }

/** Quante foto e quanti video ci sono in [view]: il riepilogo in fondo alla griglia ("12 foto, 3 video"). */
internal fun photoAndVideoCounts(view: TimelineView): Pair<Int, Int> {
  val snapshot = view.snapshot
  var videos = 0
  for (v in 0 until view.size) {
    if (snapshot.kindAt(view.snapshotIndex(v)) == MediaKind.VIDEO) videos++
  }
  return (view.size - videos) to videos
}

/** Se [id] e' un preferito nella vista (i bit di cella): decide se "Preferito" aggiunge o toglie. */
internal fun isFavorite(view: TimelineView, id: MediaId): Boolean {
  val viewIndex = view.viewIndexOf(id)
  if (viewIndex < 0) return false
  return view.snapshot.hasFlag(view.snapshotIndex(viewIndex), CellFlags.FAVORITE)
}
