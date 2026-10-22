package dev.pampa.mycloudgallery.core.family.contract

import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import kotlinx.coroutines.flow.StateFlow

/** Perche' quello che si sa del registro potrebbe essere vecchio. */
enum class StaleReason {
  /** Il NAS non si raggiunge da un po': si lavora con l'ultimo registro accettato. */
  NOT_REFRESHED,

  /** Sul NAS c'e' una versione piu' nuova che non si e' potuta verificare (Diagnostica: `newerUnverified`). */
  NEWER_UNVERIFIED,

  /** Troppi file nella cartella del registro: elenco troncato. */
  LISTING_TRUNCATED,

  /** Il registro e' di un'app piu' nuova (E-I3). */
  NEEDS_APP_UPDATE,

  /** Allarme di equivocazione: due contenuti con la stessa versione. */
  EQUIVOCATION,
}

/** Lo stato di QUESTO dispositivo nel registro firmato. */
sealed interface TrustStatus {
  data object NotConfigured : TrustStatus

  /** Registrato, in attesa: [code] e' quello da mostrare all'admin. */
  data class Pending(val code: String) : TrustStatus

  data class Approved(val admin: Boolean) : TrustStatus

  data object Retired : TrustStatus

  data object Revoked : TrustStatus

  /** Segnato come perso: al prossimo controllo si azzera (C15). */
  data object Lost : TrustStatus

  data class Stale(val reason: StaleReason) : TrustStatus

  /**
   * C'e' uno stato della famiglia salvato, ma il Keystore non risponde (R7 della revisione 5B: per esempio dopo un aggiornamento
   * di sistema): non si sa quale sia la chiave di questo dispositivo, quindi nemmeno se e' approvato. **Chiuso**: niente fiducia,
   * niente scritture firmate, niente approvazioni. Non e' "non configurato": la chiave, la chiave di famiglia e lo stato ci sono
   * ancora, e quando il Keystore torna a rispondere lo stato si ricalcola uguale a prima.
   */
  data object KeystoreUnavailable : TrustStatus
}

/** Il ruolo del dispositivo, come si mostra nell'elenco. */
enum class DeviceRoleView { INDEXER, PREPARER }

/**
 * Un dispositivo della famiglia, per l'elenco "Dispositivi" (L11). Tutti i testi vengono dal registro
 * firmato (gia' validati da `core-trust`) o dal `DeviceStatus` pubblicato dal dispositivo.
 *
 * @property lastSeenAt ultima attivita' (ora del NAS), se il dispositivo ha pubblicato il proprio stato.
 * @property lookAlikeOf l'id di un altro dispositivo con un nome che "sembra uguale" (reinstallazione, E-O7).
 */
data class DeviceView(
  val id: String,
  val name: String,
  val model: String,
  val nasUser: String,
  val admin: Boolean,
  val status: DeviceStatus,
  val roles: Set<DeviceRoleView>,
  val lastSeenAt: Long?,
  val appVersion: String?,
  val isThisDevice: Boolean,
  val lookAlikeOf: String? = null,
)

/**
 * Una richiesta d'ingresso in attesa, per l'admin (§2.2). L'account NAS e' **dichiarato** dal richiedente:
 * l'admin lo conferma (B6).
 *
 * @property firstSeenAt quando l'app dell'admin l'ha vista la prima volta (ora del NAS): la finestra di 15
 *   minuti parte da qui, non da `requestedAt` (lo scrive il richiedente).
 */
data class PendingRequestView(
  val id: String,
  val name: String,
  val model: String,
  val declaredNasUser: String,
  val requestedAt: Long,
  val firstSeenAt: Long,
  val windowOpen: Boolean,
  val lookAlikeOf: String? = null,
)

/** L'esito di un'azione dell'admin sul registro (approvare, cambiare stato). Mappa i `CommitResult`. */
sealed interface AdminActionResult {
  data object Done : AdminActionResult

  /** C'e' una versione piu' nuova (un altro admin): risincronizzare e riprovare. */
  data object NewerExists : AdminActionResult

  /** Rami in conflitto firmati con la chiave di famiglia: schermata dedicata. */
  data object Fork : AdminActionResult

  /** Troppi file non validi nella cartella del registro: vanno tolti dal PC. */
  data object Blocked : AdminActionResult

  data object NeedsAppUpdate : AdminActionResult

  /** La finestra di 15 minuti per approvare e' scaduta: il dispositivo nuovo rifa' la richiesta. */
  data object WindowExpired : AdminActionResult

  /** Il codice digitato (o la chiave scansionata) non corrisponde alla richiesta. */
  data object ProofMismatch : AdminActionResult

  /** Questo dispositivo non tiene la chiave di famiglia. */
  data object NotAdmin : AdminActionResult

  /** Il NAS non ha risposto, o ha rifiutato la scrittura. */
  data class Failed(val error: NasError) : AdminActionResult
}

/** Cosa scegliere per invitare un familiare (Impostazioni → Famiglia → "Configura un familiare"). */
data class InviteParams(
  val nasUser: String,
  val deviceName: String,
  val privateShare: String,
  val presetName: String?,
)

/**
 * Il registro della famiglia visto dall'app (P3): stato proprio, dispositivi, richieste, azioni dell'admin.
 * Ogni azione che scrive passa da `FamilyAuthority.commit` di `core-trust` e parte solo da un gesto locale
 * confermato (E-S11).
 */
interface RegistryService {
  val status: StateFlow<TrustStatus>
  val devices: StateFlow<List<DeviceView>>
  val pendingRequests: StateFlow<List<PendingRequestView>>

  /** Rilegge il registro dal NAS (in background al massimo una volta al giorno, piu' spesso nella finestra d'invito). */
  suspend fun refresh(purpose: Purpose)

  suspend fun approve(requestId: String, proof: ApprovalProof): AdminActionResult

  suspend fun changeStatus(deviceId: String, status: DeviceStatus): AdminActionResult

  /** Crea l'invito e apre la finestra d'invito di 24 h (`TrustSyncScheduler.setInviteWindow`). */
  suspend fun createInvite(params: InviteParams): FamilyInvite

  /**
   * Le cartelle da escludere dell'area comune, cambiate dall'admin dopo la fondazione (il passo 5 ripreso da "Da
   * completare", verifica funzionale ON-8): stanno nella configurazione **firmata**, quindi si firma e si pubblica una
   * versione nuova del registro (`FamilyAuthority.updateConfig` → `commit`). [NotAdmin][AdminActionResult.NotAdmin] su
   * un dispositivo che non tiene la chiave di famiglia. I percorsi arrivano gia' validati (`SharePath.parseMediaPath`).
   */
  suspend fun updateCommonExclusions(excluded: Set<String>): AdminActionResult

  /**
   * L'indirizzo del NAS cambiato dall'admin (E-R7: il NAS ha preso un altro IP): sta nella configurazione **firmata**, da cui
   * `createInvite` prende l'indirizzo del QR. Prima cambiava solo in locale e l'invito nuovo portava ancora quello vecchio (verifica
   * funzionale, RE-5). Stesso controllo dell'admin di ogni azione firmata ([updateCommonExclusions]): [NotAdmin][AdminActionResult.NotAdmin]
   * su un dispositivo che non tiene la chiave di famiglia, che l'indirizzo lo cambia solo per se'. [ip] e' un IP letterale (quello che
   * l'utente ha digitato e la pagina ha verificato, GUID compreso); lo stesso di prima non firma niente.
   */
  suspend fun updateNasAddress(ip: String): AdminActionResult

  /**
   * I prefissi ULA di casa (la rete di casa raccolta di nuovo dopo un reset del router, E-R16) nella configurazione firmata, da cui
   * `createInvite` prende "dove e' casa" per i nuovi dispositivi. Stesso controllo dell'admin di [updateNasAddress]. I prefissi arrivano
   * dall'impronta confermata dall'utente; quelli che non sono un /48 ULA non entrano, e senza nessun prefisso valido non si firma niente.
   */
  suspend fun updateHomeUla(prefixes: Set<String>): AdminActionResult

  /**
   * "Rinomina questo dispositivo" (F27 delle prove sul campo, E-B7): il nome che gli altri dispositivi e la lista dei dispositivi mostrano sta nella
   * voce **firmata** del registro, quindi lo cambia solo chi tiene la chiave di famiglia: si firma e si pubblica una versione nuova del registro
   * (`FamilyAuthority.renameDevice` → `commit`), senza campi ne' formati nuovi. [AdminActionResult.NotAdmin] su un dispositivo che non la tiene (la
   * richiesta firmata dal dispositivo e approvata dall'admin sarebbe un documento nuovo: resta da decidere). Un nome uguale o che "sembra uguale"
   * (`LookAlike`) a quello di un altro dispositivo e' rifiutato ([AdminActionResult.Failed] con `NasError.Exists`); lo stesso nome di prima non
   * firma niente. **Non cambia la cartella del backup** (E-B7: si sceglie una volta sola).
   */
  suspend fun renameDevice(deviceId: String, name: String): AdminActionResult = AdminActionResult.NotAdmin
}
