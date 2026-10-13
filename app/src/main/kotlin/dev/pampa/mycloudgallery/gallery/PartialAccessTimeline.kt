package dev.pampa.mycloudgallery.gallery

import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.TimelineRepository
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * L'accesso parziale (E-P1, "foto selezionate"): la Libreria mostra **solo gli elementi accessibili adesso**, cioe'
 * quelli che la sincronizzazione ha visto nella selezione dell'ultima lettura (`DefaultPhoneLibrarySync.partialSelection`).
 * Il catalogo non segna assenze per gli altri (E-P1, E-P15): restano li', semplicemente non si mostrano finche'
 * l'accesso non torna completo.
 *
 * E' una vista sopra quella del repository: con l'accesso completo ([selection] null) e' la stessa vista, senza costi.
 * Con l'accesso parziale la selezione e' piccola (cio' che l'utente ha scelto), quindi la mappa si costruisce dagli id
 * selezionati, mai scorrendo l'indice intero.
 */
class PartialAccessTimeline(
  private val base: TimelineRepository,
  private val selection: StateFlow<Set<MediaId>?>,
  private val scope: CoroutineScope,
) : TimelineRepository {

  private val views = ConcurrentHashMap<LibraryFilter, StateFlow<TimelineView>>()

  override val snapshot: StateFlow<TimelineSnapshot?> get() = base.snapshot

  override suspend fun start() = base.start()

  override fun view(f: LibraryFilter): StateFlow<TimelineView> = views.computeIfAbsent(f) { filter ->
    val full = base.view(filter)
    combine(full, selection) { view, chosen -> if (chosen == null) view else SubsetView(view, chosen) }
      .stateIn(scope, SharingStarted.Eagerly, full.value.let { v -> selection.value?.let { SubsetView(v, it) } ?: v })
  }
}

/** Gli elementi di [base] che stanno in [chosen], nello stesso ordine. */
internal class SubsetView(private val base: TimelineView, chosen: Set<MediaId>) : TimelineView {

  /** Le posizioni nella vista di base, in ordine. */
  private val map: IntArray = chosen.mapNotNull { id -> base.viewIndexOf(id).takeIf { it >= 0 } }.sorted().toIntArray()

  override val snapshot: TimelineSnapshot get() = base.snapshot
  override val filter: LibraryFilter get() = base.filter
  override val size: Int get() = map.size

  override fun snapshotIndex(viewIndex: Int): Int = base.snapshotIndex(map[viewIndex])

  override fun viewIndexOf(id: MediaId): Int {
    val inBase = base.viewIndexOf(id)
    if (inBase < 0) return -1
    val found = map.binarySearch(inBase)
    return if (found >= 0) found else -1
  }
}
