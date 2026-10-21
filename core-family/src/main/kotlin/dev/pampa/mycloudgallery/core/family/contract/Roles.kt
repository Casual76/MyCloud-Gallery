package dev.pampa.mycloudgallery.core.family.contract

import dev.pampa.mycloudgallery.core.trust.data.DeviceRole
import kotlinx.coroutines.flow.StateFlow

// Fase 6 (docs/fase6-progetto.md §1.3 "core-family", D15, risposta Q3): i ruoli di QUESTO dispositivo, modificabili dopo
// l'onboarding (oggi si scelgono solo al passo 7 e in Impostazioni sono in sola lettura). Firma di P0; l'implementazione
// (`core-family/.../roles/`) e la voce in Impostazioni → Ricerca e modelli sono di P8.
//
// Il ruolo Indicizzatore lo sceglie ogni utente per i propri dispositivi (Q3). Il passo 7 dell'onboarding resta la proposta
// iniziale (telefono Indicizzatore e Preparatore, tablet solo Preparatore).

/** Com'e' andato un cambio di ruoli. */
sealed interface RolesChange {
  /** Salvato e ripubblicato subito nello stato firmato del dispositivo ([republished] falso: lo si ripubblica al prossimo giro). */
  data class Saved(val roles: Set<DeviceRole>, val republished: Boolean) : RolesChange

  /** Niente da cambiare: i ruoli erano gia' questi. */
  data object Unchanged : RolesChange

  /** Il dispositivo non e' configurato (onboarding non finito): niente si salva. */
  data object NotConfigured : RolesChange
}

/**
 * I ruoli di questo dispositivo (D15). [setRoles] scrive le scelte locali (oggi nell'istantanea dell'onboarding) e ripubblica subito
 * lo stato firmato del dispositivo (cambio di ruoli, formato §5.10). Spegnere Indicizzatore ferma il calcolo al media successivo e
 * rilascia le prenotazioni (E-A13): lo fa chi osserva [roles] (`AiGraph` di :app, P11), non questo contratto.
 */
interface RolesSettings {
  /** I ruoli correnti (vuoto prima dell'onboarding). */
  val roles: StateFlow<Set<DeviceRole>>

  suspend fun setRoles(indexer: Boolean, preparer: Boolean): RolesChange
}
