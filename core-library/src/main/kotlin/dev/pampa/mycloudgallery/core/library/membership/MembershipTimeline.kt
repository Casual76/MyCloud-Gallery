package dev.pampa.mycloudgallery.core.library.membership

import dev.pampa.mycloudgallery.core.library.contract.FilterExtras
import dev.pampa.mycloudgallery.core.library.contract.FilterMembership
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.TimelineRepository
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.index.AdjustedTimelineView
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * Fase grafica (G2b): il repository della timeline con le correzioni del catalogo per i filtri che la cella non basta a decidere (Fotocamera,
 * Messaggistica: [LibraryFilter.needsMembership]). Per tutti gli altri filtri e' il repository di sotto, la stessa `StateFlow`: la Libreria non
 * paga niente.
 *
 * Sta **sotto** l'accesso parziale (`PartialAccessTimeline` di `:app`): le correzioni si applicano all'indice intero e la selezione dell'utente
 * resta l'ultima parola (un id aggiunto da qui che non e' fra quelli accessibili non si vede).
 */
class MembershipTimeline(
  private val base: TimelineRepository,
  private val membership: FilterMembership,
  private val scope: CoroutineScope,
) : TimelineRepository {

  private val views = ConcurrentHashMap<LibraryFilter, StateFlow<TimelineView>>()

  override val snapshot: StateFlow<TimelineSnapshot?> get() = base.snapshot

  override suspend fun start() = base.start()

  override fun view(f: LibraryFilter): StateFlow<TimelineView> {
    if (!f.needsMembership) return base.view(f)
    return views.computeIfAbsent(f) { filter ->
      val full = base.view(filter)
      val extras = membership.extras(filter).onStart { emit(FilterExtras.NONE) }.distinctUntilChanged()
      // Si parte da quello che la cella sa (subito, senza aspettare il catalogo); le correzioni arrivano dopo.
      combine(full, extras) { view, more -> AdjustedTimelineView.of(view, more) }
        .stateIn(scope, SharingStarted.Eagerly, full.value)
    }
  }
}
