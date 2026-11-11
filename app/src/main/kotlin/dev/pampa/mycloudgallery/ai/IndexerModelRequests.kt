package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Il valore salvato segue le regole; solo un vero cambio del ruolo e' una richiesta esplicita. */
internal suspend fun watchIndexerModels(
  roles: Flow<Boolean>, allowed: () -> Boolean,
  needed: suspend (ModelTrigger) -> Boolean,
  firstRequestDone: suspend () -> Boolean = { true },
  markFirstRequestDone: suspend () -> Unit = {},
  firstRequest: (suspend () -> Unit)? = null,
  request: suspend (ModelTrigger) -> Unit,
) {
  var previous: Boolean? = null
  roles.distinctUntilChanged().collect { on ->
    val initial = previous == null
    val trigger = if (previous == false) ModelTrigger.ROLE_CHANGED else ModelTrigger.RULES
    previous = on
    try {
      if (on && allowed()) {
        if (initial && !firstRequestDone()) {
          // Non ripristina un modello rimosso prima di aggiornare l'app.
          if (needed(ModelTrigger.RULES)) { if (firstRequest != null) firstRequest() else request(ModelTrigger.ROLE_CHANGED) }
          markFirstRequestDone()
        } else if (needed(trigger)) request(trigger)
      }
    } catch (e: TimeoutCancellationException) {
      // Solo la scadenza locale dell'accodamento: il segno resta assente e il
      // prossimo valore utile del ruolo (o il prossimo processo) puo riprovare.
      // La cancellazione del chiamante, invece, deve continuare a propagarsi.
      currentCoroutineContext().ensureActive()
    }
  }
}
