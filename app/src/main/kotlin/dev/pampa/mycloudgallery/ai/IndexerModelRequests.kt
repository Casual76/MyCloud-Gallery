package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Il valore salvato segue le regole; solo un vero cambio del ruolo e' una richiesta esplicita. */
internal suspend fun watchIndexerModels(
  roles: Flow<Boolean>, allowed: () -> Boolean,
  needed: suspend (ModelTrigger) -> Boolean,
  request: suspend (ModelTrigger) -> Unit,
) {
  var previous: Boolean? = null
  roles.distinctUntilChanged().collect { on ->
    val trigger = if (previous == false) ModelTrigger.ROLE_CHANGED else ModelTrigger.RULES
    previous = on
    if (on && allowed() && needed(trigger)) request(trigger)
  }
}
