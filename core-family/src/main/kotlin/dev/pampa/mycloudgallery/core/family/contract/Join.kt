package dev.pampa.mycloudgallery.core.family.contract

/**
 * A che punto e' l'ingresso di un dispositivo nuovo (§2.2, lato nuovo dispositivo). Le schermate di P6
 * mostrano esattamente questo stato.
 */
sealed interface JoinStep {

  /** Invito letto e valido: riepilogo da mostrare prima di continuare. */
  data class InviteAccepted(val invite: FamilyInvite) : JoinStep

  /** Casa riconosciuta (o VPN di casa) e GUID del NAS uguale a quello dell'invito: si chiede la password. */
  data object NeedsPassword : JoinStep

  /** Password giusta, registro fidato letto: si chiede il nome del dispositivo (o profilo). */
  data class NeedsName(val suggestedName: String) : JoinStep

  /**
   * Richiesta pubblicata: si mostra [code] (da far confrontare all'admin) e il QR della chiave
   * ([deviceKeyQr], testo `MCGK1:`). Fino all'approvazione: sola lettura (E-S14).
   */
  data class AwaitingApproval(val code: String, val deviceKeyQr: String) : JoinStep

  /** L'admin ha approvato. */
  data object Approved : JoinStep

  /** Fermo per un problema (stessi casi dell'onboarding: E-O5, E-R8, E-S12, NOT_AT_HOME...). */
  data class Failed(val problem: OnboardingProblem) : JoinStep
}

/**
 * L'ingresso con invito (P4). Un solo tentativo di password (E-R8): dopo un errore d'autenticazione si
 * riprova solo su azione dell'utente, con una password nuova.
 */
interface JoinFlow {
  suspend fun start(invite: FamilyInvite): JoinStep

  /** La password dell'account NAS: azzerata dal flusso appena usata. */
  suspend fun authenticate(password: CharArray): JoinStep

  suspend fun register(name: String): JoinStep
}
