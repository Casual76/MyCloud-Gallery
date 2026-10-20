package dev.pampa.mycloudgallery.core.family.contract

import kotlinx.coroutines.flow.StateFlow

/**
 * Dove si trova questo dispositivo nella vita della famiglia. E' cio' che `RootViewModel` (in :app) guarda
 * per scegliere la prima schermata: onboarding, attesa dell'approvazione, Libreria, "dispositivo azzerato".
 */
sealed interface SetupState {

  /** Si sta ancora leggendo lo stato salvato: non si mostra niente (meglio di un lampo di onboarding). */
  data object Loading : SetupState

  /** Mai configurato su questo dispositivo (o profilo): si parte dal benvenuto. */
  data object NotConfigured : SetupState

  /** Configurazione iniziata e non finita: si riprende dal passo salvato (E-O9). */
  data class Onboarding(val progress: OnboardingProgress) : SetupState

  /**
   * Dispositivo registrato, in attesa che l'admin lo approvi: **sola lettura** (E-S14). [code] e' il codice
   * da far confrontare all'admin; [since] l'ora del NAS della richiesta.
   */
  data class AwaitingApproval(val code: String, val since: Long) : SetupState

  /** Approvato: la galleria e' tutta disponibile. [admin] se questo dispositivo tiene la chiave di famiglia. */
  data class Ready(val admin: Boolean) : SetupState

  /**
   * Il dispositivo si e' azzerato (segnalato come perso, E-S2): chiavi e dati locali distrutti. Per
   * riusarlo si riconfigura da capo e si riapprova. [at] = ora del dispositivo (ms).
   */
  data class Wiped(val at: Long) : SetupState

  /** Fermo per un motivo che l'utente deve conoscere (aggiornare l'app, registro in conflitto...). */
  data class Blocked(val reason: BlockReason) : SetupState
}

/** Perche' il dispositivo e' fermo ([SetupState.Blocked]). */
enum class BlockReason {
  /** Sul NAS c'e' un formato di un'app piu' nuova: aggiornare prima di continuare (E-I3). */
  NEEDS_APP_UPDATE,

  /** Il registro ha rami in conflitto firmati con la chiave di famiglia: serve l'admin. */
  REGISTRY_FORK,

  /** Un azzeramento era iniziato e non e' finito: riprende da solo al prossimo avvio. */
  WIPE_IN_PROGRESS,

  /** Telefono appena riavviato e mai sbloccato: le chiavi non si leggono ancora (BFU). */
  STORAGE_LOCKED,

  /**
   * Il Keystore non risponde ([TrustStatus.KeystoreUnavailable], R7 della revisione 5B): lo stato della famiglia c'e' ma non si
   * puo' usare. Si riprova; "Configura di nuovo" resta un'uscita secondaria, dietro la conferma.
   */
  KEYSTORE_UNAVAILABLE,
}

/** La fonte di [SetupState] (contratto P0, implementazione P4). */
interface FamilySetupRepository {
  val state: StateFlow<SetupState>
}
