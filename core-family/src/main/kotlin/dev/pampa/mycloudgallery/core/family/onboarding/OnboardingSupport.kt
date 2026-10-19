package dev.pampa.mycloudgallery.core.family.onboarding

import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.TrustViolation
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.registry.ConfigDefaults
import dev.pampa.mycloudgallery.core.trust.registry.FamilyConfig
import dev.pampa.mycloudgallery.core.trust.registry.NasGuids
import dev.pampa.mycloudgallery.core.trust.registry.NasPowerRule
import dev.pampa.mycloudgallery.core.trust.registry.UlaPrefix as TrustUlaPrefix

/**
 * Da un errore del NAS (§3.4) al problema che la schermata dell'onboarding sa spiegare. Ogni errore di rete
 * ha il suo messaggio: "NAS spento" si dice solo se il permesso di rete locale c'e' ([NasError.LocalNetworkBlocked]
 * viene prima) e solo se non c'e' una causa migliore.
 */
internal object NasProblems {

  /**
   * @param joining nell'ingresso con invito un percorso negato vuol dire "non sei a casa" ([OnboardingProblem.NOT_AT_HOME]);
   *   nella creazione della famiglia vuol dire "serve il Wi-Fi di casa" ([OnboardingProblem.NOT_ON_WIFI]).
   */
  fun reach(error: NasError, joining: Boolean = false): OnboardingProblem = when (error) {
    is NasError.Auth -> auth(error.reason)
    is NasError.AuthBlocked -> auth(error.reason)
    is NasError.InsecureSession -> OnboardingProblem.NAS_INSECURE
    NasError.IdentityMismatch -> OnboardingProblem.IDENTITY_MISMATCH
    NasError.LocalNetworkBlocked -> OnboardingProblem.LOCAL_NETWORK_DENIED
    is NasError.NotAllowedHere -> denied(error.reason, joining)
    NasError.ServiceDown -> OnboardingProblem.NAS_SERVICE_DOWN
    NasError.Timeout, NasError.Stalled, NasError.SessionExpired, NasError.NotFound, is NasError.Unexpected -> OnboardingProblem.NAS_NOT_FOUND
    NasError.PermissionDenied, is NasError.ShareMissing -> OnboardingProblem.NO_ACCESSIBLE_SHARE
    NasError.NasFull, NasError.QuotaExceeded -> OnboardingProblem.NAS_FULL
    NasError.FileBusy, NasError.Exists -> OnboardingProblem.NAS_WRITE_FAILED
    NasError.CredentialsMissing -> OnboardingProblem.AUTH_FAILED
  }

  /** Come [reach], ma per un errore capitato mentre si scrive sul NAS: un guasto di rete e' "scrittura non riuscita" (si riprova). */
  fun write(error: NasError): OnboardingProblem = when (error) {
    NasError.Timeout, NasError.Stalled, NasError.SessionExpired, NasError.NotFound, NasError.ServiceDown, is NasError.Unexpected,
    NasError.PermissionDenied, is NasError.ShareMissing,
    -> OnboardingProblem.NAS_WRITE_FAILED
    else -> reach(error)
  }

  /** E-O1: il NAS non si trova (spento, indirizzo sbagliato, servizio fermo): si puo' continuare piu' tardi. */
  fun isDeferrable(problem: OnboardingProblem): Boolean =
    problem == OnboardingProblem.NAS_NOT_FOUND || problem == OnboardingProblem.NAS_SERVICE_DOWN

  private fun auth(reason: AuthBlockReason): OnboardingProblem = when (reason) {
    AuthBlockReason.AUTH_FAILED -> OnboardingProblem.AUTH_FAILED
    AuthBlockReason.ACCOUNT_LOCKED -> OnboardingProblem.ACCOUNT_LOCKED
    AuthBlockReason.ACCOUNT_DISABLED -> OnboardingProblem.ACCOUNT_DISABLED
    AuthBlockReason.PASSWORD_EXPIRED -> OnboardingProblem.PASSWORD_EXPIRED
    AuthBlockReason.ACCOUNT_RESTRICTED -> OnboardingProblem.ACCOUNT_RESTRICTED
  }

  private fun denied(reason: DenyReason, joining: Boolean): OnboardingProblem = when (reason) {
    DenyReason.FOREIGN_VPN -> OnboardingProblem.VPN_ACTIVE
    DenyReason.LOCAL_NETWORK_DENIED -> OnboardingProblem.LOCAL_NETWORK_DENIED
    else -> if (joining) OnboardingProblem.NOT_AT_HOME else OnboardingProblem.NOT_ON_WIFI
  }
}

/**
 * Un errore del NAS letto fuori dai passi (per esempio la ricerca dei kit di recupero) come problema da mostrare, con le
 * stesse regole dei passi: le schermate non dicono "nessun kit" quando il NAS non ha risposto (verifica funzionale, ON-9).
 */
fun NasError.asOnboardingProblem(): OnboardingProblem = NasProblems.reach(this)

/**
 * Esegue [block] sul file system della share [share], dentro una sessione dell'onboarding. Le chiamate di
 * `NasFileSystem` sono bloccanti e, a differenza di `withShare`, lanciano [NasIoException]: qui diventano un
 * [NasResult], cosi' un errore del NAS non esce mai come eccezione verso le schermate.
 */
internal suspend fun <T> NasSessionManager.onShare(share: String, block: (NasFileSystem) -> T): NasResult<T> =
  when (val outer = withShare(share, Purpose.ONBOARDING) { scope ->
    try {
      NasResult.Ok(block(scope.fileSystem))
    } catch (e: NasIoException) {
      NasResult.Err(e.error)
    }
  }) {
    is NasResult.Ok -> outer.value
    is NasResult.Err -> NasResult.Err(outer.error)
  }

/**
 * Dai dati dell'onboarding alla [FamilyConfig] firmata al passo 12(e) (`docs/formato-nas.md` §5.3): IP (e nome) del
 * NAS, GUID, prefissi ULA di casa, area comune, esclusioni, alimentazione ("sempre acceso", la scelta del 12/10) e
 * preset e qualita' di default. **Un solo punto**, e ogni campo passa dalla forma del formato (una sola scrittura):
 * un GUID che il NAS ha dato in un'altra forma, o un nome che non e' un nome di host, non entrano nel registro
 * invece di far fallire la fondazione (il dispositivo li ha comunque memorizzati in locale).
 */
internal object FamilyConfigFactory {

  /** L'indirizzo come lo vuole il registro: un IPv6 letterale va tra parentesi quadre. */
  fun hostOf(nas: NasFacts): String = hostOf(nas.ip)

  fun hostOf(ip: String): String = if (':' in ip) "[$ip]" else ip

  /** Il contrario di [hostOf]: dall'host firmato all'IP letterale. */
  fun ipOf(host: String): String = host.removePrefix("[").removeSuffix("]")

  /** Il GUID nella forma del formato (UUID minuscolo di 36 caratteri), o null se il NAS lo ha dato in un'altra. */
  fun guidOf(raw: String): String? = raw.lowercase(java.util.Locale.ROOT).takeIf(NasGuids::isValid)

  fun create(snapshot: OnboardingSnapshot, nas: NasFacts, commonShare: String): FamilyConfig {
    val name = nas.name?.takeIf { validHostName(it) }
    val ula = snapshot.homeConfirmed?.ulaPrefixes.orEmpty()
      .mapNotNull(TrustUlaPrefix::canonical).distinct().sorted().take(TrustLimits.MAX_HOME_ULA)
    val choices = snapshot.choices
    return FamilyConfig(
      nasHost = hostOf(nas),
      commonShare = commonShare,
      nasName = name,
      nasGuid = guidOf(nas.guid),
      homeUla = ula,
      nasPower = NasPowerRule.ALWAYS,
      commonExclusions = snapshot.excluded.sorted(),
      defaults = ConfigDefaults(
        preset = choices.preset.name.lowercase(java.util.Locale.ROOT),
        quality = choices.quality.name.lowercase(java.util.Locale.ROOT),
      ),
    )
  }

  private fun validHostName(text: String): Boolean =
    try {
      TextRules.hostName(text)
      true
    } catch (e: TrustViolation) {
      false
    }
}

/** I passi e i percorsi: quali passi fa ciascun percorso e cosa viene dopo (puro, senza I/O). */
internal object OnboardingPlans {

  private val FOUND = OnboardingStep.entries.toList()

  /** Entrare con un invito (QR o manuale): indirizzo e account li fa `JoinFlow`, l'area viene dall'invito. */
  private val JOIN = listOf(
    OnboardingStep.WELCOME,
    OnboardingStep.NAS_ADDRESS,
    OnboardingStep.ACCOUNT,
    OnboardingStep.ROLES,
    OnboardingStep.PRESET,
    OnboardingStep.PERMISSIONS,
    OnboardingStep.BATTERY,
    OnboardingStep.VPN,
    OnboardingStep.SUMMARY,
  )

  /** Recupero dell'admin e secondo admin: come l'ingresso, ma le aree si scelgono (il kit sta nell'area privata). */
  private val RECOVER = listOf(
    OnboardingStep.WELCOME,
    OnboardingStep.NAS_ADDRESS,
    OnboardingStep.ACCOUNT,
    OnboardingStep.AREAS,
    OnboardingStep.ROLES,
    OnboardingStep.PRESET,
    OnboardingStep.PERMISSIONS,
    OnboardingStep.BATTERY,
    OnboardingStep.VPN,
    OnboardingStep.SUMMARY,
  )

  /** I passi che hanno bisogno del NAS (non `local`) e che "Continua piu' tardi" (E-O1) rimanda. */
  val NAS_DEPENDENT: Set<OnboardingStep> = OnboardingStep.entries.filter { !it.local }.toSet()

  fun planFor(path: OnboardingPath?): List<OnboardingStep> = when (path) {
    null, OnboardingPath.FOUND_FAMILY -> FOUND
    OnboardingPath.JOIN_QR, OnboardingPath.JOIN_MANUAL -> JOIN
    OnboardingPath.RECOVER_ADMIN, OnboardingPath.SECOND_ADMIN -> RECOVER
  }

  /** Vero per i percorsi che fanno amministratore questo dispositivo. */
  fun isAdminPath(path: OnboardingPath?): Boolean =
    path == OnboardingPath.FOUND_FAMILY || path == OnboardingPath.RECOVER_ADMIN || path == OnboardingPath.SECOND_ADMIN

  /**
   * Il passo che viene dopo [done]. Nel percorso normale e' il primo non ancora fatto ne' saltato: se l'utente e'
   * tornato indietro e rifa' un passo, si salta quelli gia' fatti. Quando si riprende un passo saltato
   * ([resuming]) e' il prossimo da completare (le voci di "Da completare"), non il seguente. Null = non c'e'
   * altro: l'onboarding e' finito (o, riprendendo, non resta niente da completare).
   */
  fun nextAfter(
    plan: List<OnboardingStep>,
    completed: Set<OnboardingStep>,
    skipped: Set<OnboardingStep>,
    done: OnboardingStep,
    resuming: Boolean,
  ): OnboardingStep? {
    val index = plan.indexOf(done)
    return if (resuming) {
      plan.drop(index + 1).firstOrNull { it in skipped && it !in completed }
    } else {
      plan.drop(index + 1).firstOrNull { it !in completed && it !in skipped }
    }
  }
}

/**
 * Fa avanzare uno [OnboardingSnapshot]: [step] diventa fatto (o, con [keepPending], visto ma non verificato: resta
 * in "Da completare") e [OnboardingProgress.current] passa al successivo. Puro: lo usano sia il coordinatore
 * sia l'ingresso con invito, dentro lo stesso `store.update`.
 *
 * @return il nuovo snapshot e il passo che viene dopo (null = non c'e' altro).
 */
internal fun OnboardingSnapshot.completing(step: OnboardingStep, keepPending: Boolean = false): Pair<OnboardingSnapshot, OnboardingStep?> {
  val p = progress
  val completed = if (keepPending) p.completed - step else p.completed + step
  val skipped = if (keepPending) p.skipped + step else p.skipped - step
  val next = OnboardingPlans.nextAfter(p.plan, completed, skipped, step, resuming)
  val advanced = copy(
    progress = p.copy(completed = completed, skipped = skipped, current = next ?: p.plan.last()),
    resuming = resuming && next != null,
    deferrable = false,
  )
  return advanced to next
}

/**
 * L'ultima coppia utente/password che il NAS ha rifiutato, riconosciuta da un'impronta (SHA-256, solo in memoria,
 * mai salvata): la stessa coppia non torna al NAS (E-R8, E-S12). Un account che si blocca per un doppio tocco e'
 * peggio di un messaggio d'errore.
 */
internal class RejectedCredentials {

  private var fingerprint: ByteArray? = null

  fun isRepeat(user: String, password: CharArray): Boolean = fingerprint?.contentEquals(fingerprintOf(user, password)) == true

  fun remember(user: String, password: CharArray) {
    fingerprint = fingerprintOf(user, password)
  }

  fun clear() {
    fingerprint = null
  }

  private fun fingerprintOf(user: String, password: CharArray): ByteArray {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.update(user.lowercase(java.util.Locale.ROOT).toByteArray(Charsets.UTF_8))
    digest.update(0)
    val bytes = String(password).toByteArray(Charsets.UTF_8)
    digest.update(bytes)
    bytes.fill(0)
    return digest.digest()
  }
}
