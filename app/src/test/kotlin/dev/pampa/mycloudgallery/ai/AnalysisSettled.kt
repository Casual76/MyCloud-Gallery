package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.feature.search.contract.AnalysisFlow
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisThumb
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * XS2-1: il nastro esce **prima neutro** (tutte le anteprime `Quiet`, nello stesso istante in cui cambia la visibilita') e **poi risolto** (le anteprime di cio' che la
 * Libreria mostra). Una prova che vuole lo stato finale non puo' fermarsi alla prima emissione col numero giusto di posti: quella puo' essere il neutro. Qui si prende
 * la prima con [slots] posti; se mostra qualcosa e' gia' risolta (il neutro non mostra mai niente), altrimenti si aspetta [graceMillis] una emissione **diversa**
 * (se il mondo non mostra niente il risolto e' uguale al neutro e non ne esce un'altra: vale quello).
 */
internal suspend fun StateFlow<AnalysisFlow>.settled(slots: Int, graceMillis: Long = 700): AnalysisFlow {
  val early = withTimeout(30_000) { first { it.recent.size == slots } }
  if (early.shownIds().isNotEmpty()) return early
  return withTimeoutOrNull(graceMillis) { first { it != early && it.recent.size == slots } } ?: early
}

internal fun AnalysisFlow.shownIds() = (listOfNotNull(current) + recent + next).filterIsInstance<AnalysisThumb.Shown>().map { it.id }
