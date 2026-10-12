package dev.pampa.mycloudgallery.core.family.contract

import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import kotlinx.coroutines.flow.StateFlow

/** Perche' il dispositivo si azzera. */
enum class WipeReason {
  /** Segnato come perso in un registro firmato e accettato (C15). */
  MARKED_LOST,

  /** Un `LostReport` valido di un dispositivo dello stesso account (decisione 3a). */
  LOST_REPORT,
}

/**
 * Le fasi dell'azzeramento, **in quest'ordine** (§2.3): prima le chiavi, poi i dati. Un fake che registra
 * l'ordine e' parte del test obbligatorio di P3.
 */
enum class WipePhase {
  /** (0) Segno "azzeramento in corso": se il processo muore, al riavvio si riprende da qui. */
  MARKED,

  /** (1) Chiave del dispositivo distrutta. */
  DEVICE_KEY_DESTROYED,

  /** (2) Chiave di famiglia distrutta (se admin). */
  FAMILY_KEY_DESTROYED,

  /** (3) `CredentialVault.wipeAll`. */
  CREDENTIALS_WIPED,

  /** (4) Sessioni NAS invalidate. */
  SESSIONS_CLOSED,

  /** (5) `LocalDataEraser`: replica, miniature, cache. */
  LOCAL_DATA_ERASED,

  /** (6) Lavori di WorkManager cancellati. */
  WORK_CANCELLED,

  /** (7) `SetupState.Wiped`. */
  DONE,
}

/** L'azzeramento di questo dispositivo (P3). Riprende da dove si era fermato se il processo muore. */
interface SelfWipe {
  /** L'ultima fase completata, o null se non c'e' un azzeramento in corso. */
  val progress: StateFlow<WipePhase?>

  suspend fun run(reason: WipeReason): WipePhase

  /** All'avvio: se un azzeramento era iniziato, lo finisce. */
  suspend fun resumeIfInterrupted()
}

/**
 * Cancella i dati locali dell'app (replica, miniature, cache, DataStore): lo implementa `:app`, che sa dove
 * sono. Vero se e' andato tutto bene; un errore si riprova al riavvio.
 */
fun interface LocalDataEraser {
  suspend fun eraseAll(): Boolean
}

/** Un kit di recupero trovato in `recovery/` dell'area privata (solo nomi validi). */
data class RecoveryKitRef(val share: String, val fileName: String, val createdAt: Long?)

/** Cosa fare del vecchio admin dopo il recupero (scelta dell'utente, §2.3). */
enum class OldAdminFate { LOST, REVOKED, KEEP_APPROVED }

sealed interface RecoveryOutcome {
  /** Questo dispositivo e' admin, con una chiave nuova, approvato da se'. */
  data object Recovered : RecoveryOutcome

  /** Il codice non apre il kit. */
  data object WrongCode : RecoveryOutcome

  data class Failed(val problem: OnboardingProblem) : RecoveryOutcome
}

/**
 * Il recupero dell'admin con il kit e il secondo admin (P3, E-S13, proposta 15). Il codice e' un `CharArray`
 * azzerato dopo l'uso; [familyState] e' il QR "stato della famiglia" letto da un altro dispositivo, se c'e'
 * (altrimenti versione minima 0, con avviso).
 */
interface AdminRecovery {
  suspend fun findKits(account: NasAccount, privateShare: String): NasResult<List<RecoveryKitRef>>

  suspend fun recover(
    kit: RecoveryKitRef,
    code: CharArray,
    familyState: FamilyStateQr?,
    oldAdmin: OldAdminFate,
  ): RecoveryOutcome
}

sealed interface ResetOutcome {
  /** Famiglia rifondata: id, chiave e kit nuovi. Gli altri dispositivi si riconfigurano con il QR nuovo. */
  data class Done(val newFamilyId: String) : ResetOutcome

  data class Failed(val problem: OnboardingProblem) : ResetOutcome
}

/**
 * Il reset della famiglia (doppia conferma, `formato-nas.md` §4.10): i dati vecchi vengono ignorati, non
 * cancellati. [confirmedTwice] e' l'esito della seconda conferma dell'interfaccia: senza, non parte niente.
 */
interface FamilyReset {
  suspend fun reset(confirmedTwice: Boolean): ResetOutcome
}

/**
 * La segnalazione di smarrimento (P3, decisione 3a): l'admin cambia lo stato in `LOST`; il proprietario puo'
 * pubblicare un `LostReport` firmato da un altro dispositivo dello stesso account, che l'admin converte.
 */
interface LostReporting {
  /** Proprietario: pubblica la segnalazione per [targetDeviceId] (un altro proprio dispositivo). */
  suspend fun reportOwnDeviceLost(targetDeviceId: String): AdminActionResult

  /** Admin: converte le segnalazioni valide in stato `LOST` nel registro. */
  suspend fun convertReports(): AdminActionResult
}

/** Perche' si chiede un controllo del registro presto. */
enum class SyncReason { APP_OPENED, ARRIVED_HOME, INVITE_WINDOW, ADMIN_ACTION, PERIODIC }

/**
 * La pianificazione dei controlli del registro (contratto P0, implementazione P9 con WorkManager:
 * `TrustSyncWorker` una volta al giorno, piu' spesso nella finestra d'invito, all'apertura e all'arrivo a casa).
 */
interface TrustSyncScheduler {
  fun requestSoon(reason: SyncReason)

  /** Apre (o estende) la finestra d'invito fino a [untilNasMs], ora del NAS: controlli frequenti (E-R15). */
  fun setInviteWindow(untilNasMs: Long)
}

/** Perche' si propone di escludere una cartella (passo 5, E-N14, E-N15). */
enum class ExclusionReason { MUSIC, MOVIES, SMALL_COVERS, SYSTEM_FOLDER, APP_FOLDER }

/** Una cartella proposta per l'esclusione (percorso relativo alla share, gia' validato). */
data class ExcludedFolder(val path: String, val reason: ExclusionReason)

/**
 * La proposta di esclusioni: limitata (2 livelli, 5000 voci) e annullabile.
 *
 * @property scanned quante voci sono state guardate; [truncated] se il limite e' stato raggiunto.
 */
data class FolderProposal(val excluded: List<ExcludedFolder>, val scanned: Int, val truncated: Boolean) {
  companion object {
    val EMPTY = FolderProposal(emptyList(), scanned = 0, truncated = false)
  }
}

/** Il sondaggio delle cartelle (P4): sola lettura, mai i dischi senza motivo (si fa durante l'onboarding). */
interface FolderSurvey {
  suspend fun propose(share: String): FolderProposal
}
