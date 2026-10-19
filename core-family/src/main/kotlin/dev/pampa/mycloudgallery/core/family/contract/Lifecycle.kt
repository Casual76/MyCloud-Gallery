package dev.pampa.mycloudgallery.core.family.contract

import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.trust.codec.Base32
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
  /**
   * I kit di recupero nell'area privata [privateShare] dell'account [account].
   *
   * [password] e' la password di [account] quando **non** e' l'account di questo dispositivo (secondo admin o recupero
   * dalle Impostazioni di un familiare: l'area privata dell'admin si apre solo con l'account dell'admin). Serve a UNA
   * sessione che legge i kit e si chiude subito; chi implementa la azzera dopo l'uso (e il chiamante comunque). Null =
   * la sessione di questo dispositivo (onboarding: l'account appena configurato e' gia' quello dell'admin).
   */
  suspend fun findKits(account: NasAccount, privateShare: String, password: CharArray? = null): NasResult<List<RecoveryKitRef>>

  /**
   * Il recupero con [kit] e il [code] del foglio. L'ancora indipendente dal NAS (revisione 2, F3) e' il QR di stato
   * [familyState] oppure, senza, [typedFingerprint]: l'impronta della famiglia **letta dal foglio** e digitata. Senza
   * nessuna delle due il recupero non parte ([RecoveryOutcome.Failed] con `RECOVERY_KIT_UNVERIFIED`): il nome del file
   * sul NAS lo sceglie chi scrive nell'area privata, quindi non e' un'ancora.
   */
  suspend fun recover(
    kit: RecoveryKitRef,
    code: CharArray,
    familyState: FamilyStateQr?,
    oldAdmin: OldAdminFate,
    typedFingerprint: String? = null,
  ): RecoveryOutcome
}

/**
 * L'impronta della famiglia scritta a mano, dal foglio del codice di recupero (revisione 2, F3): 26 caratteri base32
 * minuscoli; spazi, trattini e maiuscole si tollerano.
 *
 * Come l'ancora dell'invito manuale (`Base32.normalizeHumanInput`) si leggono anche gli alias di Crockford: O per 0, I e
 * L per 1 (verifica funzionale, ON-12b: una "o" letta dal foglio dava "kit non verificato"). Si normalizza **solo per il
 * confronto** con l'impronta attesa: gli alias stanno fuori dall'alfabeto dell'impronta (niente i, l, o, u), quindi
 * un'impronta vera resta se stessa e due impronte diverse non diventano mai uguali.
 */
object TypedFingerprint {
  const val LENGTH = 26
  private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"

  fun normalize(typed: String): String = Base32.normalizeHumanInput(typed.filter { it.isLetterOrDigit() })

  /** Vero se [typed] ha la forma di un'impronta (non dice se e' quella giusta: lo dice il kit). */
  fun isComplete(typed: String): Boolean = normalize(typed).let { it.length == LENGTH && it.all { c -> c in ALPHABET } }

  /** Per mostrarla: gruppi di 4. */
  fun grouped(fingerprint: String): String = fingerprint.chunked(GROUP).joinToString(" ")

  private const val GROUP = 4
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

  /**
   * Dopo un reset riuscito: il codice di recupero del kit **nuovo**, in 7 gruppi, da mostrare una volta con
   * `FLAG_SECURE` finche' l'utente non lo riscrive ([confirmTypedCode]). Null se non c'e' un codice da mostrare. Senza,
   * il kit nuovo sul NAS non si aprirebbe mai (P9: prima era solo su `NasFamilyReset`).
   */
  fun recoveryCodeForDisplay(): RecoveryCodeDisplay? = null

  /** Vero se [typed] (azzerato dopo l'uso) e' il codice del kit nuovo: allora lo si dimentica. */
  fun confirmTypedCode(typed: CharArray): Boolean {
    typed.fill('\u0000')
    return false
  }
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
