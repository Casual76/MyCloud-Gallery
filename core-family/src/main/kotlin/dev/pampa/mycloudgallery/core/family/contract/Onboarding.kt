package dev.pampa.mycloudgallery.core.family.contract

import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.ShareAccess
import dev.pampa.mycloudgallery.core.network.contract.DiscoveredNas
import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.rules.Preset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Le strade del passo 1 (piano 2.2-2.4). */
enum class OnboardingPath {
  /** Crea la famiglia: questo dispositivo diventa l'admin (14 passi). */
  FOUND_FAMILY,

  /** Entra con il QR mostrato dall'admin (§2.2). */
  JOIN_QR,

  /** Entra senza QR: indirizzo, utente, id della famiglia e impronta letti dalla schermata dell'admin. */
  JOIN_MANUAL,

  /** Recupera l'admin con il kit di recupero (E-S13). */
  RECOVER_ADMIN,

  /** Diventa secondo admin, con il kit, lasciando approvato il primo (proposta 15). */
  SECOND_ADMIN,
}

/**
 * I 14 passi dell'onboarding admin (§2.1), nell'ordine. Gli altri percorsi ne usano un sottoinsieme
 * ([OnboardingProgress.plan]).
 *
 * @property number il numero mostrato all'utente ("Passo 3 di 14").
 * @property skippable si puo' saltare: finisce in Impostazioni, "Da completare".
 * @property local non tocca il NAS: si puo' fare anche con il NAS spento ("Continua piu' tardi", E-O1).
 */
enum class OnboardingStep(val number: Int, val skippable: Boolean, val local: Boolean) {
  WELCOME(1, skippable = false, local = true),
  NAS_ADDRESS(2, skippable = false, local = false),
  ACCOUNT(3, skippable = false, local = false),
  AREAS(4, skippable = false, local = false),
  FOLDERS(5, skippable = true, local = false),
  BACKUP_SOURCES(6, skippable = false, local = true),
  ROLES(7, skippable = false, local = true),
  PRESET(8, skippable = false, local = true),
  PERMISSIONS(9, skippable = false, local = true),
  BATTERY(10, skippable = true, local = true),
  VPN(11, skippable = true, local = true),
  FAMILY_KEY(12, skippable = false, local = false),
  SECURITY_NOTICE(13, skippable = false, local = true),
  SUMMARY(14, skippable = false, local = true),
}

/**
 * L'avanzamento, salvato a ogni passo (DataStore in `noBackupFilesDir`) e ripreso dopo un cambio di app o la
 * morte del processo (E-O9: normale ai passi 10 e 11, che portano fuori dall'app).
 *
 * @property plan i passi del percorso scelto, nell'ordine.
 * @property startedAt quando e' iniziato (ora del dispositivo, ms): misura dell'obiettivo "< 5 minuti".
 */
data class OnboardingProgress(
  val path: OnboardingPath?,
  val plan: List<OnboardingStep>,
  val current: OnboardingStep,
  val completed: Set<OnboardingStep>,
  val skipped: Set<OnboardingStep>,
  val startedAt: Long?,
) {
  /** I passi saltati e non ancora ripresi: le voci di "Da completare" nelle Impostazioni. */
  val pending: List<OnboardingStep> get() = plan.filter { it in skipped && it !in completed }

  companion object {
    /** Prima di tutto: il benvenuto, nessun percorso scelto. */
    val START = OnboardingProgress(
      path = null,
      plan = OnboardingStep.entries.toList(),
      current = OnboardingStep.WELCOME,
      completed = emptySet(),
      skipped = emptySet(),
      startedAt = null,
    )
  }
}

/** Le qualita' delle anteprime proposte al passo 8. */
enum class PreviewQuality { COMPACT, STANDARD, HIGH }

/** Cosa l'utente consegna a un passo. Una sola porta d'ingresso ([OnboardingCoordinator.submit]). */
sealed interface StepInput {

  /** Passo 1. */
  data class ChoosePath(val path: OnboardingPath) : StepInput

  /** Passo 2: "E' la rete di casa?" (con il consenso si raccolgono ULA, gateway e dominio). */
  data class ConfirmHome(val isHome: Boolean) : StepInput

  /** Passo 2: l'indirizzo scelto (trovato in LAN, risolto sul Wi-Fi o digitato). */
  data class NasAddressChosen(val address: NasAddress) : StepInput

  /**
   * Passo 3: utente e password. **Un solo tentativo** (E-R8). La password e' un `CharArray` che il
   * coordinatore azzera appena l'ha usata; [toString] non la mostra.
   */
  class Credentials(val user: String, val password: CharArray) : StepInput {
    fun clear() = password.fill('\u0000')

    override fun toString(): String = "Credentials(nascoste)"
  }

  /** Passo 4: area comune e area privata. */
  data class Areas(val commonShare: String, val privateShare: String) : StepInput

  /** Passo 5: le cartelle da escludere (percorsi relativi alla share comune, validati da chi implementa). */
  data class Folders(val excluded: Set<String>) : StepInput

  /** Passo 6. */
  data class BackupSources(val camera: Boolean, val screenshots: Boolean) : StepInput

  /** Passo 7. */
  data class Roles(val indexer: Boolean, val preparer: Boolean) : StepInput

  /** Passo 8. */
  data class PresetChoice(val preset: Preset, val quality: PreviewQuality) : StepInput

  /** Passo 9: i dialoghi dei permessi sono stati mostrati (l'esito si rilegge da Android, E-O4). */
  data object PermissionsReviewed : StepInput

  /** Passo 10: le impostazioni della batteria sono state viste (l'esito si verifica, E-P2). */
  data object BatteryReviewed : StepInput

  /** Passo 11: controllo remoto di WireGuard facoltativo e nome del tunnel di casa. */
  data class VpnSetup(val remoteControl: Boolean, val tunnelName: String?) : StepInput

  /**
   * Passo 12(c): il codice di recupero riscritto **per intero** dall'utente. Azzerato dopo l'uso.
   */
  class RecoveryCodeTyped(val code: CharArray) : StepInput {
    fun clear() = code.fill('\u0000')

    override fun toString(): String = "RecoveryCodeTyped(nascosto)"
  }

  /** Passo 13: backup esterno e checklist del FRITZ!Box, "fatto" o "dopo" (C13). */
  data class SecurityNotice(val externalBackupDone: Boolean, val routerChecklistDone: Boolean) : StepInput

  /** Passo 14: "Vai alla Libreria". */
  data object Finish : StepInput
}

/** Cosa non va in un passo: ogni voce ha la sua schermata d'errore (P6) e, dove c'e', il suo edge case. */
enum class OnboardingProblem {
  /** Passo 2: serve il Wi-Fi di casa. */
  NOT_ON_WIFI,

  /** Passo 2: la VPN e' accesa, va spenta per questo passo. */
  VPN_ACTIVE,

  /** Passo 2: il Wi-Fi non ha un prefisso ULA, "casa" non si puo' confermare. */
  NO_ULA,

  /** Android non permette la rete locale (controllato prima di "NAS spento"). */
  LOCAL_NETWORK_DENIED,

  /** E-O1: indirizzo sbagliato o NAS spento. Si puo' continuare piu' tardi. */
  NAS_NOT_FOUND,

  /** Il NAS e' acceso ma la condivisione file non risponde. */
  NAS_SERVICE_DOWN,

  /** Niente SMB 3.1.1 con cifratura: rifiutato (C3). */
  NAS_INSECURE,

  /** Il GUID non e' quello atteso (QR o configurazione): nessuna password inviata. */
  IDENTITY_MISMATCH,

  /**
   * Revisione 2, F6: dopo il login il GUID firmato dall'admin non e' quello del NAS a cui si e' mandata la password
   * (ingresso manuale o QR con un NAS che non e' quello della famiglia). La password e' arrivata a quel dispositivo:
   * va cambiata sul NAS vero prima di riprovare.
   */
  PASSWORD_SENT_TO_OTHER_NAS,

  /** L'account `admin` del NAS non si usa mai nell'app (§8). */
  ADMIN_ACCOUNT_REFUSED,

  /** E-R8: password sbagliata. L'interruttore e' aperto; "Riprova" solo su azione dell'utente. */
  AUTH_FAILED,

  /**
   * Revisione 2, F1: la password non raggiunge la soglia di `NasPasswordPolicy` (12 caratteri con 3 tipi, o una frase
   * di 16). Non e' stata mandata al NAS: va cambiata sul NAS e riscritta qui.
   */
  PASSWORD_TOO_WEAK,

  /** E-S12. */
  ACCOUNT_LOCKED,
  ACCOUNT_DISABLED,
  PASSWORD_EXPIRED,
  ACCOUNT_RESTRICTED,

  /** Il telefono non ha un Keystore hardware: le credenziali non si salvano. */
  CREDENTIALS_NOT_SAVED,

  /** E-O2: nessuna share accessibile. */
  NO_ACCESSIBLE_SHARE,

  /** E-O3: area comune assente o non scrivibile (l'app non crea share). */
  COMMON_AREA_NOT_WRITABLE,

  /** Passo 4 o 12(e): qui c'e' gia' una famiglia ("Recupera l'admin" o "Entra come familiare"). */
  FAMILY_ALREADY_EXISTS,

  /** Passo 12(c): il codice riscritto non coincide. */
  RECOVERY_CODE_MISMATCH,

  /** Passo 12(d): il kit pubblicato non si rilegge o non si apre con il codice. */
  RECOVERY_KIT_UNVERIFIED,

  /** Scrittura sul NAS non riuscita (si riprova: ogni scrittura e' idempotente). */
  NAS_WRITE_FAILED,
  NAS_FULL,

  /** Ingresso: QR non valido, di un'altra famiglia (E-O5), scaduto. */
  INVITE_INVALID,
  INVITE_OTHER_FAMILY,
  INVITE_EXPIRED,

  /** Ingresso: non si e' a casa (ULA diverso da quello del QR) ne' sulla VPN di casa. */
  NOT_AT_HOME,

  /** Il registro letto non e' fidato (firma, rollback, equivocazione). */
  REGISTRY_UNTRUSTED,

  /** Sul NAS c'e' un formato di un'app piu' nuova (E-I3). */
  NEEDS_APP_UPDATE,

  /** Il passo chiesto non e' quello corrente, o non si puo' saltare. */
  STEP_NOT_AVAILABLE,
}

/** Avvisi che non fermano il passo. */
enum class OnboardingWarning {
  /** Una share scelta e' pubblica. */
  SHARE_IS_PUBLIC,

  /** L'account apre anche le share private di altri. */
  ACCOUNT_SEES_OTHER_PRIVATE_SHARES,

  /** Il telefono non ha un blocco schermo (E-S3). */
  NO_SCREEN_LOCK,

  /** Ingresso senza QR: versione minima del registro 0, con avviso in Diagnostica. */
  NO_MIN_REGISTRY_VERSION,
}

/** L'esito di un passo. */
sealed interface StepResult {
  /** Fatto. [next] null = onboarding finito. */
  data class Advanced(val next: OnboardingStep?, val warnings: Set<OnboardingWarning> = emptySet()) : StepResult

  /** Il passo non e' riuscito; si resta li'. */
  data class Problem(val problem: OnboardingProblem) : StepResult
}

/**
 * Un'area candidata al passo 4: una share provata con `ShareProbe`.
 *
 * @property hasFamily nella share c'e' gia' `family/registry/...1`: "Qui c'e' gia' una famiglia".
 */
data class AreaCandidate(
  val share: String,
  val access: NasResult<ShareAccess>,
  val isPublic: Boolean?,
  val hasFamily: Boolean,
)

/**
 * Il codice di recupero da mostrare al passo 12(b), in 7 gruppi, con `FLAG_SECURE`. Si chiude ([close])
 * appena la schermata esce: i caratteri si azzerano.
 */
class RecoveryCodeDisplay(
  val groups: List<CharArray>,
  /**
   * L'impronta della chiave di famiglia (26 caratteri), da scrivere **sul foglio** accanto al codice (revisione 2, F3):
   * nel recupero senza QR di stato e' l'ancora indipendente dal NAS. Non e' un segreto. Null se non c'e' niente da
   * mostrare.
   */
  val familyFingerprint: String? = null,
) : AutoCloseable {
  override fun close() = groups.forEach { it.fill('\u0000') }

  override fun toString(): String = "RecoveryCodeDisplay(${groups.size} gruppi nascosti)"
}

/**
 * La logica dei 14 passi (P4), persistente e riprendibile. Le schermate (P6) parlano solo con questa
 * interfaccia: consegnano un [StepInput], ricevono uno [StepResult], seguono [progress].
 *
 * Regole che valgono per ogni implementazione (§2.1): ogni scrittura sul NAS e' idempotente
 * (`ALREADY_EXISTS` con gli stessi byte vale come successo); SMB solo con l'app in primo piano; le
 * credenziali si salvano solo dopo il successo dell'autenticazione.
 */
interface OnboardingCoordinator {
  val progress: StateFlow<OnboardingProgress>

  suspend fun submit(input: StepInput): StepResult

  /** Salta un passo saltabile ([OnboardingStep.skippable]): finisce in "Da completare". */
  suspend fun skip(step: OnboardingStep): StepResult

  /** Torna al passo precedente, senza disfare cio' che e' gia' scritto sul NAS. */
  suspend fun back()

  /** Riapre un passo saltato (dalle Impostazioni, "Da completare"). */
  suspend fun resume(step: OnboardingStep): StepResult

  /** Passo 2: il candidato a "casa" letto dal Wi-Fi attuale (null senza Wi-Fi). */
  suspend fun homeCandidate(): HomeCandidate?

  /** Passo 2: i NAS annunciati in LAN (solo durante l'onboarding). */
  fun discoverNas(): Flow<DiscoveredNas>

  /** Passo 4: le share proposte (`<utente>`, `Public`, `NAS` e quelle digitate in [extra]), gia' provate. */
  suspend fun proposeAreas(extra: List<String> = emptyList()): List<AreaCandidate>

  /** Passo 5: la proposta di esclusioni (copertine, musica, film), limitata e annullabile. */
  suspend fun proposeFolders(): FolderProposal

  /** Passo 12(b): il codice di recupero appena generato, da mostrare una volta. */
  suspend fun recoveryCode(): RecoveryCodeDisplay
}
