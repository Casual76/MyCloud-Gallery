package dev.pampa.mycloudgallery.core.family.contract

import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.ShareAccess
import dev.pampa.mycloudgallery.core.network.contract.DiscoveredNas
import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.trust.TrustFormat
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

  /**
   * Fase 10, M3 (decisione 2(a) del 31/10): l'impronta o il PIN del telefono non sono stati confermati (foglio annullato, troppi
   * tentativi, foglio non disponibile adesso). La chiave di famiglia non e' stata usata: niente firmato, niente salvato. Si riprova.
   */
  FAMILY_KEY_NOT_CONFIRMED,

  /**
   * Fase 10, M3 (E-S9, E-H3): la cassaforte della chiave di famiglia su questo telefono non si apre piu' (impronte o blocco schermo
   * cambiati: Android ha invalidato la sua chiave). Niente e' perso: la chiave si riprende con il kit di recupero o dal secondo admin.
   */
  FAMILY_KEY_INVALIDATED,

  /** E-O2: nessuna share accessibile. */
  NO_ACCESSIBLE_SHARE,

  /** E-O3: area comune assente o non scrivibile (l'app non crea share). */
  COMMON_AREA_NOT_WRITABLE,

  /** Passo 4 o 12(e): qui c'e' gia' una famiglia ("Recupera l'admin" o "Entra come familiare"). */
  FAMILY_ALREADY_EXISTS,

  /**
   * Prove sul campo (punto 10): "Recupera l'admin", "Secondo admin" o "Entra come familiare" su un'area comune dove non c'e' nessuna famiglia
   * (nessun `family/registry/…1`, lo stesso controllo di "Qui c'e' gia' una famiglia"). Prima si cercavano i kit e si diceva solo "Non trovo
   * nessun kit", e l'utente finiva sulle istruzioni per cancellare una cartella che non c'era. Si propone "Crea la famiglia" o un'altra area.
   * Un errore del NAS durante il controllo non e' questo problema: si dice il suo, con "Riprova".
   */
  NO_FAMILY_IN_AREA,

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

  /**
   * "Configura di nuovo" (R7, R19 della revisione 5B): il Keystore non risponde, o le chiavi di questo telefono non si sono
   * potute togliere nemmeno ripetendo. Non si va avanti: la chiave di famiglia di un admin non si distrugge per un guasto
   * passeggero, e una chiave rimasta non si fa passare per tolta. Si riprova.
   */
  KEYS_NOT_REMOVED,

  /**
   * Fine dell'onboarding con un account o un NAS diverso da quello lasciato con "Configura di nuovo" (R6): i dati di prima
   * (catalogo, dati personali, miniature, diari) non si sono potuti togliere dal telefono. La Libreria non si apre sopra i
   * dati di un'altra persona: si riprova.
   */
  LOCAL_DATA_NOT_REMOVED,
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
 * @property fenced la share la esclude la recinzione delle prove (D33, solo debug, con un account di prova), non il NAS: non si prova
 *   nemmeno e non si puo' scegliere ([access] e' `DENIED`). La pagina lo dice con parole sue: prima diceva "Accesso negato" e chi guardava
 *   la dashboard del NAS vedeva l'accesso consentito.
 */
data class AreaCandidate(
  val share: String,
  val access: NasResult<ShareAccess>,
  val isPublic: Boolean?,
  val hasFamily: Boolean,
  val fenced: Boolean = false,
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
  /**
   * Fase 10, M3: perche' non c'e' niente da mostrare quando la chiave non si e' potuta custodire (foglio dell'impronta annullato,
   * nessun blocco schermo sicuro, ...): la pagina lo dice invece di "passo non disponibile". Null negli altri casi.
   */
  val problem: OnboardingProblem? = null,
) : AutoCloseable {
  override fun close() = groups.forEach { it.fill('\u0000') }

  override fun toString(): String = "RecoveryCodeDisplay(${groups.size} gruppi nascosti)"
}

/**
 * Il NAS e le aree che l'onboarding ha salvato (passi 2-4, o letti dall'invito), senza segreti. Servono a riprendere
 * il recupero dell'admin dopo la morte del processo (verifica funzionale, ON-3: prima stavano solo nel ViewModel) e a
 * scrivere il percorso esatto della cartella della famiglia nella guida "ricominciare da zero" (ON-6). Null i campi
 * che l'onboarding non ha ancora.
 */
data class SavedNas(val address: NasAddress, val user: String?, val commonShare: String?, val privateShare: String?) {
  /**
   * La cartella della famiglia sul NAS come la scrive Esplora file di Windows (ON-6, "ricominciare da zero"), per esempio
   * `\\192.168.178.58\NAS\.mycloudgallery\family`. Null senza area comune o senza un indirizzo che Windows capisca.
   */
  fun familyFolderForWindows(): String? {
    val share = commonShare ?: return null
    val host = address.ip.takeIf { ':' !in it } ?: address.name ?: return null
    return "\\\\$host\\$share\\${TrustFormat.ROOT_DIR}\\$FAMILY_FOLDER"
  }

  /** Mai indirizzi, utenti o nomi di share in un log (§4.2). */
  override fun toString(): String = "SavedNas(utente=${user != null}, aree=${commonShare != null}/${privateShare != null})"

  private companion object {
    /** La cartella della famiglia nella cartella dell'app (`docs/formato-nas.md` §2.2). */
    const val FAMILY_FOLDER = "family"
  }
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

  /**
   * Riapre un passo saltato (dal riepilogo o dalle Impostazioni, "Da completare"). A onboarding finito riapre anche un
   * passo **locale** gia' fatto (6-11, 13, se e' nel piano): dalle Impostazioni si rifanno il controllo di WireGuard e la
   * checklist del backup (verifica funzionale, ON-7). Mai un passo del NAS gia' fatto (indirizzo, account, aree, chiave).
   */
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

  /** Il NAS e le aree salvati (ON-3, ON-6), o null prima del passo 2. */
  suspend fun savedNas(): SavedNas?

  /**
   * Vero se il recupero dell'admin (o il secondo admin) e' a meta': aree scelte, ma il registro non dice ancora "approvato,
   * admin". Le pagine del recupero si riaprono da qui anche dopo la morte del processo (verifica funzionale, ON-3).
   */
  suspend fun recoveryPending(): Boolean

  /**
   * "Configura di nuovo" su un dispositivo revocato, dismesso o rimasto nella famiglia di prima dopo un reset (verifica
   * funzionale, ON-4 e ON-5): si dimenticano avanzamento, account e password del NAS, stato fidato, impronta di casa,
   * chiave del dispositivo (al rientro ne nasce una nuova, con un id nuovo: E-S2, E-O7) e chiave di famiglia, se c'era.
   * Parte solo da un gesto confermato dall'utente. Dopo, il dispositivo e' "mai configurato": benvenuto.
   *
   * **Le chiavi non si fingono tolte** (R19): una distruzione che fallisce si ripete, e se non riesce e' un
   * [OnboardingProblem.KEYS_NOT_REMOVED] e niente altro si tocca. **Un Keystore che non risponde** (R7) fa lo stesso prima
   * di toccare qualsiasi cosa: la chiave di famiglia di un admin non si distrugge per un guasto passeggero.
   *
   * **I dati locali** (foto del telefono, nascosti, preferiti, miniature, catalogo) restano finche' chi rientra e' la stessa
   * persona sullo stesso NAS; se il rientro e' con un altro account o un altro NAS, alla fine dell'onboarding, prima che la
   * Libreria si apra, si tolgono (R6, [OnboardingProblem.LOCAL_DATA_NOT_REMOVED]). Mai le foto del telefono ne' il NAS.
   */
  suspend fun startOver(): StepResult

  /**
   * Vero se questo dispositivo tiene (o potrebbe tenere) la chiave di famiglia, cioe' e' un admin: "Configura di nuovo" la
   * distrugge, e solo il kit di recupero con il suo codice la riporta (R7). Nel dubbio (Keystore che non risponde) vale
   * vero: l'avviso in piu' non costa niente, la chiave persa si'.
   */
  suspend fun holdsFamilyKey(): Boolean
}
