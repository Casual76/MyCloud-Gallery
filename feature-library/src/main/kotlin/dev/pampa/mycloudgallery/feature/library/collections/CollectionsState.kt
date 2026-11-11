package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind

/**
 * Una riga dell'elenco delle Raccolte.
 *
 * @property count quanti elementi, per le raccolte-filtro; **null** per i Nascosti e gli Eliminati di recente: la riga
 *   dei Nascosti non dice mai quanti sono (E-H1: dall'esterno non si deve nemmeno capire se ce ne sono).
 */
@Immutable
data class CollectionRow(val kind: CollectionKind, val count: Int?)

/**
 * Le righe che la scheda fa da sola, prima di diventare sezioni ([builtinSections], D33): i due posti protetti, cioe' i Nascosti (sezione Nascosti) e
 * gli Eliminati di recente (sezione Da tenere al sicuro), questi solo dove il cestino di sistema esiste (Android 11+: nessuna voce morta, D13). In
 * produzione `:app` toglie "Da tenere al sicuro" con un fornitore vuoto (gli Eliminati di recente stanno nell'Utility). Lo stato della scheda intera,
 * con le sezioni dei fornitori, l'ordine e la visibilita', e' [CollectionsLayoutState].
 *
 * CS-17 (fase H): le quattro raccolte-filtro della vecchia sezione "Telefono" (Preferiti, Fotocamera, Video, Screenshot, Messaggistica) non ci sono
 * piu': dalla G2 stanno nella scheda Album, e la scheda non le costruisce ne' le conta.
 */
@Immutable
data class CollectionsUiState(val protectedRows: List<CollectionRow>) {
  /** Se [kind] e' nell'elenco (gli Eliminati di recente possono mancare). */
  operator fun contains(kind: CollectionKind): Boolean = protectedRows.any { it.kind == kind }
}

/** Le righe dei posti protetti. Pura: la provano i test su JVM. */
fun collectionsUiState(canTrash: Boolean): CollectionsUiState = CollectionsUiState(
  protectedRows = buildList {
    add(CollectionRow(CollectionKind.HIDDEN, count = null))
    if (canTrash) add(CollectionRow(CollectionKind.RECENTLY_DELETED, count = null))
  },
)

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
