package dev.pampa.mycloudgallery.gallery

import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.TimelineRepository
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineView
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
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
 * E' una vista sopra quella del repository: con l'accesso completo ([access] `FULL`, [selection] null) e' la stessa vista,
 * senza costi. Con l'accesso parziale la selezione e' piccola (cio' che l'utente ha scelto), quindi la mappa si costruisce
 * dagli id selezionati, mai scorrendo l'indice intero.
 *
 * **Fail closed** (revisione di sicurezza, R5). [selection] null vuol dire "accesso completo" **oppure** "la prima lettura non
 * ha ancora detto cosa e' accessibile": le due cose si distinguono dall'[access], che il sistema dice gia' alla costruzione.
 * Con l'accesso parziale (o senza accesso) e senza una selezione la vista e' **vuota**: nemmeno per un istante, all'avvio a
 * freddo, la griglia, le intestazioni, i conteggi e i segnaposto sfocati riguardano foto a cui il permesso non da' piu'
 * accesso. La Libreria, in quel tempo, mostra lo stato di caricamento (`LibraryPhoneStatus.selectionPending`).
 */
class PartialAccessTimeline(
  private val base: TimelineRepository,
  private val selection: StateFlow<Set<MediaId>?>,
  private val access: StateFlow<MediaAccess>,
  private val scope: CoroutineScope,
) : TimelineRepository {

  private val views = ConcurrentHashMap<LibraryFilter, StateFlow<TimelineView>>()

  /**
   * L'indice intero solo quando non c'e' niente da nascondere (accesso completo e nessuna selezione); altrimenti null, come
   * "non ancora letto": chi lo leggesse da qui non vedrebbe elementi fuori dalla selezione.
   */
  override val snapshot: StateFlow<TimelineSnapshot?> by lazy {
    combine(base.snapshot, selection, access) { snapshot, chosen, media -> restrictedSnapshot(snapshot, chosen, media) }
      .stateIn(scope, SharingStarted.Eagerly, restrictedSnapshot(base.snapshot.value, selection.value, access.value))
  }

  override suspend fun start() = base.start()

  override fun view(f: LibraryFilter): StateFlow<TimelineView> = views.computeIfAbsent(f) { filter ->
    val full = base.view(filter)
    combine(full, selection, access) { view, chosen, media -> visible(view, chosen, media, filter) }
      .stateIn(scope, SharingStarted.Eagerly, visible(full.value, selection.value, access.value, filter))
  }

  private fun restrictedSnapshot(snapshot: TimelineSnapshot?, chosen: Set<MediaId>?, media: MediaAccess): TimelineSnapshot? =
    if (chosen == null && media == MediaAccess.FULL) snapshot else null

  private companion object {
    /** Cosa si vede con questa [chosen] e questo [media]: la selezione se c'e', tutto solo con l'accesso completo, altrimenti niente. */
    fun visible(view: TimelineView, chosen: Set<MediaId>?, media: MediaAccess, filter: LibraryFilter): TimelineView = when {
      chosen != null -> SubsetView(view, chosen)
      media == MediaAccess.FULL -> view
      else -> EmptyTimelineView(filter)
    }
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
