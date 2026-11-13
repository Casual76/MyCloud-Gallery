package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.index.contract.UnitPriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Le richieste "Analizza adesso" come priorita' delle unita' (D12), **in due uscite** (XS3-1, Pacchetto XF giro 3):
 * - [visible]: le poche che passano **davanti** adesso (`AiUrgentFile.priority`, al piu' [AiUrgentFile.PRIORITY_KEYS], le meno attese per prime, a rotazione): la
 *   quota e l'ordine del servizio prioritario;
 * - [requested]: **tutte** le richieste valide (`AiUrgentFile.state`, fino a [AiUrgentFile.MAX_KEYS]): dicono quali mesi sono della classe delle richieste, cosi' i mesi
 *   di richieste che aspettano il loro turno non riempiono la finestra dei mesi ordinari.
 * Prima il selettore riceveva solo la prima uscita e la seconda classe restava fatta di 16 mesi su 64: il difetto non si vedeva in una prova che gli passava tutte
 * le richieste. La composizione con il registro vero e' provata in `UrgentPriorityCompositionTest`.
 */
internal class UrgentUnitPriority(urgent: AiUrgentFile, scope: CoroutineScope) : UnitPriority {
  override val visible: StateFlow<Set<Long>> = urgent.priority
    .map { keys -> keys.mapTo(HashSet()) { it.short } }
    .stateIn(scope, SharingStarted.Eagerly, urgent.priority.value.mapTo(HashSet()) { it.short })

  override val requested: StateFlow<Set<Long>> = urgent.state
    .map { keys -> keys.mapTo(HashSet()) { it.short } }
    .stateIn(scope, SharingStarted.Eagerly, urgent.state.value.mapTo(HashSet()) { it.short })
}
