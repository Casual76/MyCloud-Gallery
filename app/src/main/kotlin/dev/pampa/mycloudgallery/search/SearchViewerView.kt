package dev.pampa.mycloudgallery.search

import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.search.contract.SearchData
import dev.pampa.mycloudgallery.core.search.engine.LiveResults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * La vista che il viewer scorre quando si apre un risultato di Cerca (E-Q13, CE-4): parte dai risultati com'erano e **segue la Libreria viva**
 * ([LiveResults.followView]). Un media nascosto o eliminato altrove, a viewer aperto, esce anche da qui: prima il viewer riceveva un flusso fermo e la foto
 * restava nel pager mentre la griglia dietro la toglieva.
 *
 * Il flusso lavora solo mentre il viewer lo osserva ([SharingStarted.WhileSubscribed]): chiuso il viewer non resta niente in ascolto dell'istantanea.
 */
fun liveResultsView(results: OrderedTimelineView, data: SearchData, scope: CoroutineScope): StateFlow<TimelineView> =
  LiveResults.followView(results, data).stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), results)

/** Dopo l'ultimo osservatore (il viewer chiuso) il flusso aspetta un attimo, per una ricomposizione o una rotazione, poi smette. */
private const val STOP_AFTER_MS: Long = 5_000
