package dev.pampa.mycloudgallery.feature.search.noop

import dev.pampa.mycloudgallery.core.search.contract.ResultOrder
import dev.pampa.mycloudgallery.feature.search.contract.SearchOrderStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Fase 6 (P8): l'ordine dei risultati in memoria ("Recenti" di partenza), finche' `:app` non lega `search_order` (P11). Non si sincronizza. */
class MemorySearchOrderStore(initial: ResultOrder = ResultOrder.RECENT) : SearchOrderStore {
  private val state = MutableStateFlow(initial)
  override val order: StateFlow<ResultOrder> = state

  override suspend fun setOrder(order: ResultOrder) {
    state.value = order
  }
}
