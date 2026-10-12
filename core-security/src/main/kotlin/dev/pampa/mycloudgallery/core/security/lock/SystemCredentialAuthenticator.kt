package dev.pampa.mycloudgallery.core.security.lock

/** Il telefono puo' autenticare l'utente con la credenziale di sistema? */
enum class AuthAvailability {
  /** Si': impronta, volto, PIN, sequenza o password del telefono. */
  AVAILABLE,

  /**
   * Il telefono non ha nessun blocco schermo (ne' biometria): non c'e' modo di sbloccare. Con il blocco
   * app attivo l'app resta chiusa (fail closed) e propone di aprire le impostazioni di sicurezza.
   */
  NO_SCREEN_LOCK,

  /** L'hardware biometrico non risponde adesso (o serve un aggiornamento di sicurezza): riprovare. */
  TEMPORARILY_UNAVAILABLE,

  /** Il sistema non supporta questa combinazione di autenticatori. */
  UNSUPPORTED,
}

/** Esito di uno sblocco. */
sealed interface AuthResult {

  data object Success : AuthResult

  /** L'utente ha chiuso la schermata di sistema. Si resta bloccati; ci si riprova con il pulsante. */
  data object Cancelled : AuthResult

  /** Il sistema ha chiuso la schermata (l'app e' andata in secondo piano, rotazione...). Si riprova al ritorno. */
  data object Interrupted : AuthResult

  /** Troppi tentativi sbagliati: il SISTEMA ha bloccato l'autenticazione (E-S4). L'app non fa niente, nemmeno cancella. */
  data class LockedOut(val permanent: Boolean) : AuthResult

  /** Non si puo' autenticare (vedi [AuthAvailability]). */
  data class NotAvailable(val reason: AuthAvailability) : AuthResult

  /** Altro errore del sistema, probabilmente passeggero. [code] e' il codice di BiometricPrompt, per la Diagnostica. */
  data class Error(val code: Int) : AuthResult
}

/** Testi della schermata di sistema. Nessun dato sensibile: li vede chiunque abbia il telefono in mano. */
data class AuthPromptText(val title: String, val subtitle: String? = null)

/**
 * Chi autentica l'utente con la credenziale di sistema. Dietro un'interfaccia perche' il gate si prova con
 * un falso nei test unitari; l'implementazione vera e' [BiometricPromptAuthenticator].
 */
interface SystemCredentialAuthenticator {

  fun availability(): AuthAvailability

  /** Mostra la schermata di sistema e aspetta l'esito. Da chiamare dal thread principale. Annullabile. */
  suspend fun authenticate(text: AuthPromptText): AuthResult
}
