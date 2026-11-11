package dev.pampa.mycloudgallery.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Anche il ruolo gia' salvato dall'onboarding richiede il modello; KEEP e rete non a consumo restano al pianificatore. */
internal suspend fun watchIndexerModels(roles: Flow<Boolean>, allowed: () -> Boolean, request: () -> Unit) {
  roles.distinctUntilChanged().collect { on -> if (on && allowed()) request() }
}
