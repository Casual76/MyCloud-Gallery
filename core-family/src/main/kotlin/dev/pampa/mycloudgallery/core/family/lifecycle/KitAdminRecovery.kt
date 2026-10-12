package dev.pampa.mycloudgallery.core.family.lifecycle

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.family.contract.AdminRecovery
import dev.pampa.mycloudgallery.core.family.contract.FamilyStateQr
import dev.pampa.mycloudgallery.core.family.contract.OldAdminFate
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.RecoveryKitRef
import dev.pampa.mycloudgallery.core.family.contract.RecoveryOutcome
import dev.pampa.mycloudgallery.core.family.contract.TrustSnapshot
import dev.pampa.mycloudgallery.core.family.trust.LocalDeviceProfile
import dev.pampa.mycloudgallery.core.family.trust.TrustEngine
import dev.pampa.mycloudgallery.core.family.trust.UNEXPECTED
import dev.pampa.mycloudgallery.core.family.trust.onShare
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.crypto.EcPublicKey
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKeyVault
import dev.pampa.mycloudgallery.core.trust.crypto.KeyProvider
import dev.pampa.mycloudgallery.core.trust.crypto.RecoveryCode
import dev.pampa.mycloudgallery.core.trust.crypto.RecoveryKit
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import dev.pampa.mycloudgallery.core.trust.registry.CommitResult
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.FamilyAuthority
import dev.pampa.mycloudgallery.core.trust.registry.RegistrationRequest
import dev.pampa.mycloudgallery.core.trust.registry.RegistryDraft
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.registry.RegistrySync
import dev.pampa.mycloudgallery.core.trust.registry.TrustAnchor
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

/**
 * Il recupero dell'admin con il kit (E-S13, `docs/formato-nas.md` §4.8) e il secondo admin (§4.9, proposta
 * 15: stesso percorso con [OldAdminFate.KEEP_APPROVED]). Richiede che l'onboarding (P4) abbia gia' configurato
 * account e aree ([LocalDeviceProfile]) e aperto la sessione con la password dell'account dell'admin.
 *
 * 1. [findKits]: i kit nell'area privata (`recovery/<famiglia>/<impronta>`, solo nomi validi e intestazione
 *    coerente con il posto).
 * 2. [recover]: codice digitato (azzerato subito) → `RecoveryKit.open` con l'**ancora indipendente dal NAS**:
 *    il QR "stato della famiglia" se c'e' (M7), altrimenti l'id e l'impronta del nome del file (limite: vedi
 *    `docs/famiglia-dispositivi.md`) → registro dal NAS con quell'ancora (versione minima del QR, o 0 con
 *    avviso) → la chiave del kit deve essere quella che firma il registro (`acceptsRecoveredKey`) → chiave
 *    nella cassaforte → questo dispositivo si approva da se' come admin (prova: la sua stessa chiave) e il
 *    vecchio admin diventa perso o revocato, nella **stessa** versione.
 */
class KitAdminRecovery(
  private val engine: TrustEngine,
  private val sessions: NasSessionManager,
  private val keys: KeyProvider,
  private val familyKeys: FamilyKeyVault,
  private val nasClock: NasClock,
  /** Le letture con un account diverso da quello del dispositivo (P9: una sessione usa e getta); null = solo la propria. */
  private val foreignShares: ForeignShareAccess? = null,
) : AdminRecovery {

  /**
   * I kit letti con l'account di un altro (l'admin): l'area privata si apre solo con la sua password, che non si
   * conserva. Il kit e' cifrato con il codice di recupero, quindi tenerne i byte in memoria fino a [recover] non
   * espone niente; si dimenticano a ogni nuova ricerca.
   */
  private val foreignKits = ConcurrentHashMap<RecoveryKitRef, ByteArray>()

  override suspend fun findKits(account: NasAccount, privateShare: String, password: CharArray?): NasResult<List<RecoveryKitRef>> =
    try {
      foreignKits.clear()
      val foreign = foreignShares
      val listed = if (password != null && foreign != null) {
        foreign.withShare(account, password, privateShare) { fs -> listKits(fs, privateShare) }
      } else {
        sessions.onShare(privateShare, Purpose.ONBOARDING) { fs -> listKits(fs, privateShare) }
      }
      when (listed) {
        is NasResult.Err -> listed
        is NasResult.Ok -> {
          if (password != null && foreign != null) listed.value.forEach { (ref, bytes) -> foreignKits[ref] = bytes }
          NasResult.Ok(listed.value.map { it.first }.sortedByDescending { it.createdAt })
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      NasResult.Err(UNEXPECTED)
    } finally {
      password?.fill('\u0000')
    }

  /** I kit validi di una cartella `recovery/`, con i loro byte (letti comunque per l'intestazione). */
  private fun listKits(fs: NasFileSystem, privateShare: String): List<Pair<RecoveryKitRef, ByteArray>> {
    val found = ArrayList<Pair<RecoveryKitRef, ByteArray>>()
    val families = fs.list(NasLayout.RECOVERY_DIR, TrustLimits.MAX_LIST_REQUESTS).names.filter(TrustIds::isValid).sorted()
    for (familyId in families) {
      val dir = NasLayout.RECOVERY_DIR.child(familyId)
      val prints = fs.list(dir, TrustLimits.MAX_LIST_REQUESTS).names.filter(::isFingerprint).sorted()
      for (fingerprint in prints) {
        if (found.size >= TrustLimits.MAX_LIST_REQUESTS) break
        val blob = fs.read(dir.child(fingerprint), TrustLimits.MAX_RECOVERY_KIT_BYTES) as? NasRead.Data ?: continue
        val header = RecoveryKit.inspect(blob.bytes).acceptedOrNull() ?: continue
        if (header.familyId != familyId || header.keyFingerprint != fingerprint) continue
        found += RecoveryKitRef(privateShare, "$familyId/$fingerprint", header.createdAt) to blob.bytes
      }
    }
    return found
  }

  override suspend fun recover(
    kit: RecoveryKitRef,
    code: CharArray,
    familyState: FamilyStateQr?,
    oldAdmin: OldAdminFate,
  ): RecoveryOutcome {
    val parsed = try {
      RecoveryCode.parse(String(code))
    } finally {
      code.fill('\u0000')
    } ?: return RecoveryOutcome.WrongCode
    val profile = engine.profile() ?: return RecoveryOutcome.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)
    val (pathFamily, pathFingerprint) = parseRef(kit) ?: return RecoveryOutcome.Failed(OnboardingProblem.RECOVERY_KIT_UNVERIFIED)
    // L'ancora indipendente dal NAS: il QR di stato (M7). Senza, l'id e l'impronta del nome del file.
    val expectedFamily = familyState?.familyId ?: pathFamily
    val expectedFingerprint = familyState?.familyKeyFingerprint ?: pathFingerprint

    // Un kit letto con l'account dell'admin (findKits con la sua password) non si rilegge: la password non c'e' piu'.
    val blob = foreignKits[kit] ?: when (val read = guarded { sessions.onShare(kit.share, Purpose.ONBOARDING) { fs -> fs.read(NasLayout.recoveryKitFile(pathFamily, pathFingerprint), TrustLimits.MAX_RECOVERY_KIT_BYTES) } }) {
      is NasResult.Err -> return RecoveryOutcome.Failed(problemOf(read.error))
      is NasResult.Ok -> (read.value as? NasRead.Data)?.bytes ?: return RecoveryOutcome.Failed(OnboardingProblem.RECOVERY_KIT_UNVERIFIED)
    }
    val opened = when (val verdict = RecoveryKit.open(blob, parsed, expectedFamily, expectedFingerprint)) {
      is Verdict.Accepted -> verdict.value
      is Verdict.Rejected -> return if (verdict.reason == RejectReason.BAD_SIGNATURE) {
        RecoveryOutcome.WrongCode
      } else {
        RecoveryOutcome.Failed(if (verdict.reason == RejectReason.NEWER_FORMAT) OnboardingProblem.NEEDS_APP_UPDATE else OnboardingProblem.RECOVERY_KIT_UNVERIFIED)
      }
    }
    val familyKey = opened.familyKey
    val anchor = TrustAnchor.create(opened.familyId, familyKey.publicKey.fingerprint, familyState?.registryVersion ?: 0L)
      ?: return RecoveryOutcome.Failed(OnboardingProblem.REGISTRY_UNTRUSTED)

    // Il registro dal NAS con quell'ancora; la chiave del kit deve essere quella che lo firma.
    var state = when (val synced = syncWith(profile, anchor)) {
      is Synced.Ok -> synced.state
      is Synced.Problem -> return RecoveryOutcome.Failed(synced.problem)
    }
    if (familyState != null && state.registry.version == familyState.registryVersion && state.contentHash != familyState.registryHash) {
      return RecoveryOutcome.Failed(OnboardingProblem.REGISTRY_UNTRUSTED) // stessa versione del QR, altro contenuto
    }
    if (!state.acceptsRecoveredKey(familyKey.publicKey)) return RecoveryOutcome.Failed(OnboardingProblem.REGISTRY_UNTRUSTED)

    // La chiave nella cassaforte (blocco schermo sicuro obbligatorio, M3).
    try {
      familyKeys.store(familyKey)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return RecoveryOutcome.Failed(OnboardingProblem.CREDENTIALS_NOT_SAVED)
    }

    val authority = FamilyAuthority(familyKey, nasClock)
    repeat(COMMIT_ATTEMPTS) {
      val draft = try {
        selfApproval(state, authority, profile, oldAdmin) ?: return RecoveryOutcome.Failed(OnboardingProblem.REGISTRY_UNTRUSTED)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.e(TAG, e) { "modifica del registro non preparata" }
        return RecoveryOutcome.Failed(OnboardingProblem.REGISTRY_UNTRUSTED)
      }
      val committed = guarded { sessions.onShare(profile.commonShare, Purpose.ONBOARDING) { fs -> authority.commit(fs, draft) } }
      when (committed) {
        is NasResult.Err -> return RecoveryOutcome.Failed(problemOf(committed.error))
        is NasResult.Ok -> when (val result = committed.value) {
          is CommitResult.Published -> {
            engine.install(snapshotOf(result.state, anchor))
            return RecoveryOutcome.Recovered
          }
          CommitResult.NewerExists -> state = when (val again = syncWith(profile, anchor, state)) {
            is Synced.Ok -> again.state
            is Synced.Problem -> return RecoveryOutcome.Failed(again.problem)
          }
          CommitResult.NeedsAppUpdate -> return RecoveryOutcome.Failed(OnboardingProblem.NEEDS_APP_UPDATE)
          // Rami o cartella inondata: decide l'admin dalle Impostazioni, non un recupero automatico.
          is CommitResult.Fork -> return RecoveryOutcome.Failed(OnboardingProblem.REGISTRY_UNTRUSTED)
          is CommitResult.Blocked -> return RecoveryOutcome.Failed(OnboardingProblem.NAS_WRITE_FAILED)
        }
      }
    }
    return RecoveryOutcome.Failed(OnboardingProblem.NAS_WRITE_FAILED)
  }

  /**
   * La modifica del recupero: questo dispositivo approvato come admin (se non c'e', con la richiesta firmata
   * dalla sua chiave e la sua chiave come prova; se c'e' gia' approvato, solo il segno di admin), e gli altri
   * admin approvati come scelto dall'utente. Null se questo dispositivo e' gia' nel registro ma non approvato
   * (chiave revocata o persa: si riconfigura con una chiave nuova).
   */
  private fun selfApproval(state: RegistryState, authority: FamilyAuthority, profile: LocalDeviceProfile, oldAdmin: OldAdminFate): RegistryDraft? {
    val signer = keys.deviceSigner()
    val me = TrustIds.deviceIdOf(signer.publicKey)
    val now = nasClock.nowMillis()
    val existing = state.registry.device(me)
    var draft = when {
      existing == null -> {
        val bytes = RegistrationRequest.sign(signer, state.registry.familyId, profile.deviceName, profile.deviceModel, profile.nasUser, now)
        val request = RegistrationRequest.verify(bytes, state.registry.familyId, me).acceptedOrNull() ?: return null
        authority.approve(state, request, ApprovalProof.ScannedKey(signer.publicKey), firstSeenAt = now, admin = true)
      }
      existing.status != DeviceStatus.APPROVED -> return null
      existing.admin -> authority.edit(state)
      else -> authority.markAdmin(authority.edit(state), me)
    }
    val fate = when (oldAdmin) {
      OldAdminFate.LOST -> DeviceStatus.LOST
      OldAdminFate.REVOKED -> DeviceStatus.REVOKED
      OldAdminFate.KEEP_APPROVED -> null
    }
    if (fate != null) {
      // Il vecchio admin: ogni altro dispositivo admin ancora approvato (la schermata li mostra prima, P6/P7).
      for (other in state.registry.devices.filter { it.id != me && it.admin && it.status == DeviceStatus.APPROVED }) {
        draft = authority.changeStatus(draft, other.id, fate)
      }
    }
    return draft
  }

  private sealed interface Synced {
    class Ok(val state: RegistryState) : Synced

    class Problem(val problem: OnboardingProblem) : Synced
  }

  private suspend fun syncWith(profile: LocalDeviceProfile, anchor: TrustAnchor, current: RegistryState? = null): Synced {
    val read = guarded { sessions.onShare(profile.commonShare, Purpose.ONBOARDING) { fs -> RegistrySync(fs).refresh(current, anchor) } }
    val result = when (read) {
      is NasResult.Err -> return Synced.Problem(problemOf(read.error))
      is NasResult.Ok -> read.value
    }
    if (result.needsAppUpdate) return Synced.Problem(OnboardingProblem.NEEDS_APP_UPDATE)
    return result.state?.let { Synced.Ok(it) } ?: Synced.Problem(OnboardingProblem.REGISTRY_UNTRUSTED)
  }

  private suspend fun <T> guarded(block: suspend () -> NasResult<T>): NasResult<T> = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    NasResult.Err(UNEXPECTED)
  }

  private companion object {
    const val TAG = "AdminRecovery"
    const val COMMIT_ATTEMPTS = 3

    fun isFingerprint(name: String): Boolean =
      name.length == EcPublicKey.FINGERPRINT_CHARS && name.all { it in "0123456789abcdefghjkmnpqrstvwxyz" }

    fun parseRef(kit: RecoveryKitRef): Pair<String, String>? {
      val parts = kit.fileName.split('/')
      if (parts.size != 2 || !TrustIds.isValid(parts[0]) || !isFingerprint(parts[1])) return null
      return parts[0] to parts[1]
    }
  }
}

/** Lo stato fidato di una famiglia appena (ri)entrata: ancora, registro accettato, nessun segno. */
internal fun snapshotOf(state: RegistryState, anchor: TrustAnchor): TrustSnapshot = TrustSnapshot(
  registryBytes = state.signedBytes,
  signerFingerprint = state.signerKey.fingerprint,
  familyId = state.registry.familyId,
  familyKeyFingerprint = anchor.familyKeyFingerprint,
  minRegistryVersion = anchor.minRegistryVersion,
  ownHeads = emptyMap(),
)

/** Un errore NAS come problema da mostrare nell'onboarding o nelle Impostazioni. */
internal fun problemOf(error: NasError): OnboardingProblem = when (error) {
  is NasError.Auth -> when (error.reason) {
    AuthBlockReason.AUTH_FAILED -> OnboardingProblem.AUTH_FAILED
    AuthBlockReason.ACCOUNT_LOCKED -> OnboardingProblem.ACCOUNT_LOCKED
    AuthBlockReason.ACCOUNT_DISABLED -> OnboardingProblem.ACCOUNT_DISABLED
    AuthBlockReason.PASSWORD_EXPIRED -> OnboardingProblem.PASSWORD_EXPIRED
    AuthBlockReason.ACCOUNT_RESTRICTED -> OnboardingProblem.ACCOUNT_RESTRICTED
  }
  is NasError.AuthBlocked -> OnboardingProblem.AUTH_FAILED
  is NasError.InsecureSession -> OnboardingProblem.NAS_INSECURE
  NasError.IdentityMismatch -> OnboardingProblem.IDENTITY_MISMATCH
  is NasError.NotAllowedHere -> OnboardingProblem.NOT_AT_HOME
  NasError.LocalNetworkBlocked -> OnboardingProblem.LOCAL_NETWORK_DENIED
  NasError.ServiceDown -> OnboardingProblem.NAS_SERVICE_DOWN
  NasError.Timeout -> OnboardingProblem.NAS_NOT_FOUND
  NasError.NasFull, NasError.QuotaExceeded -> OnboardingProblem.NAS_FULL
  else -> OnboardingProblem.NAS_WRITE_FAILED
}

/**
 * Una share letta con un account **diverso** da quello di questo dispositivo, con una password digitata dall'utente e
 * mai salvata: serve al secondo admin (e al recupero dalle Impostazioni) per leggere i kit nell'area privata
 * dell'admin. Chi implementa (`:app`, P9) apre una sessione usa e getta con tutti i controlli del §3.1 (percorso,
 * GUID, cifratura, interruttore di quell'account), la chiude subito dopo [block] e azzera [password].
 */
interface ForeignShareAccess {
  suspend fun <T> withShare(account: NasAccount, password: CharArray, share: String, block: (NasFileSystem) -> T): NasResult<T>
}
