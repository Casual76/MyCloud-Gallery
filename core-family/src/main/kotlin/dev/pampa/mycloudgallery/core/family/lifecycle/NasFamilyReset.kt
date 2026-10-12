package dev.pampa.mycloudgallery.core.family.lifecycle

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.family.contract.FamilyReset
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.RecoveryCodeDisplay
import dev.pampa.mycloudgallery.core.family.contract.ResetOutcome
import dev.pampa.mycloudgallery.core.family.trust.TrustEngine
import dev.pampa.mycloudgallery.core.family.trust.LocalDeviceProfile
import dev.pampa.mycloudgallery.core.family.trust.onShare
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKeyVault
import dev.pampa.mycloudgallery.core.trust.crypto.KeyProvider
import dev.pampa.mycloudgallery.core.trust.crypto.RecoveryCode
import dev.pampa.mycloudgallery.core.trust.crypto.RecoveryKit
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.registry.FamilyAuthority
import dev.pampa.mycloudgallery.core.trust.registry.FamilyConfig
import dev.pampa.mycloudgallery.core.trust.registry.RegistrationRequest
import dev.pampa.mycloudgallery.core.trust.registry.RegistryVerifier
import dev.pampa.mycloudgallery.core.trust.registry.TrustAnchor
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Il reset della famiglia (`docs/formato-nas.md` §4.10, E-S13): chiave di famiglia persa senza kit o rubata, o
 * un ramo firmato che l'admin non riconosce. Dopo la doppia conferma, in quest'ordine:
 *
 * 1. chiave di famiglia **nuova** nella cassaforte (se poi qualcosa fallisce, si rimette quella vecchia);
 * 2. **kit nuovo** con un codice nuovo nell'area privata dell'admin, riletto e aperto prima di andare avanti
 *    (come il passo 12(d) dell'onboarding);
 * 3. **id di famiglia nuovo** e fondazione nella stessa cartella del registro, dopo le versioni della famiglia
 *    vecchia (`FamilyAuthority.refound`): questo dispositivo e' il primo admin approvato;
 * 4. lo stato fidato passa alla famiglia nuova.
 *
 * I dati vecchi vengono ignorati, non cancellati. Gli altri dispositivi si riconfigurano con il QR nuovo (che
 * porta come versione minima quella della fondazione) e si riapprovano. Il codice del kit nuovo va mostrato
 * (`FLAG_SECURE`) e fatto riscrivere: il contratto [FamilyReset] non lo restituisce, quindi lo tiene
 * [recoveryCodeForDisplay] finche' [confirmTypedCode] non conferma la riscrittura.
 */
class NasFamilyReset(
  private val engine: TrustEngine,
  private val sessions: NasSessionManager,
  private val keys: KeyProvider,
  private val familyKeys: FamilyKeyVault,
  private val nasClock: NasClock,
  private val random: SecureRandom = SecureRandom(),
) : FamilyReset {

  private val mutex = Mutex()
  private var pendingCode: RecoveryCode? = null

  override suspend fun reset(confirmedTwice: Boolean): ResetOutcome = mutex.withLock {
    if (!confirmedTwice) return@withLock ResetOutcome.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)
    if (!engine.ensureLoaded()) return@withLock ResetOutcome.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)
    val old = engine.state ?: return@withLock ResetOutcome.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)
    val profile = engine.profile() ?: return@withLock ResetOutcome.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)
    val privateShare = profile.privateShare ?: return@withLock ResetOutcome.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)
    val oldKey = try {
      familyKeys.load(old.trustedFamilyKey)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    }

    // 1. Chiave nuova nella cassaforte (fallisce senza blocco schermo sicuro, M3).
    val newKey = FamilyKey.generate(random)
    val newFamilyId = TrustIds.random(random)
    try {
      familyKeys.store(newKey)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return@withLock ResetOutcome.Failed(OnboardingProblem.CREDENTIALS_NOT_SAVED)
    }
    val outcome = try {
      found(newKey, newFamilyId, privateShare, profile.commonShare, old.registry.version, old.registry.config, profile)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.e(TAG, e) { "reset non riuscito" }
      ResetOutcome.Failed(OnboardingProblem.NAS_WRITE_FAILED)
    }
    if (outcome is ResetOutcome.Failed && oldKey != null) {
      try {
        familyKeys.store(oldKey) // si torna a poter amministrare la famiglia vecchia
      } catch (e: Exception) {
        GalleryLog.e(TAG, e) { "chiave di famiglia vecchia non rimessa" }
      }
    }
    outcome
  }

  private suspend fun found(
    newKey: FamilyKey,
    newFamilyId: String,
    privateShare: String,
    commonShare: String,
    knownVersion: Long,
    config: FamilyConfig,
    profile: LocalDeviceProfile,
  ): ResetOutcome {
    val now = nasClock.nowMillis()
    // 2. Kit nuovo, riletto e aperto con il codice prima della fondazione.
    val code = RecoveryCode.generate(random)
    val blob = RecoveryKit.seal(newKey, newFamilyId, code, now, random)
    val kitPath = NasLayout.recoveryKitFile(newFamilyId, newKey.publicKey.fingerprint)
    val kit = sessions.onShare(privateShare, Purpose.INTERACTIVE) { fs ->
      val published = fs.publishExclusive(kitPath, blob)
      val back = (fs.read(kitPath, TrustLimits.MAX_RECOVERY_KIT_BYTES) as? NasRead.Data)?.bytes
      published == PublishResult.CREATED && back != null &&
        RecoveryKit.open(back, code, newFamilyId, newKey.publicKey.fingerprint) is Verdict.Accepted
    }
    when (kit) {
      is NasResult.Err -> return ResetOutcome.Failed(problemOf(kit.error))
      is NasResult.Ok -> if (!kit.value) return ResetOutcome.Failed(OnboardingProblem.RECOVERY_KIT_UNVERIFIED)
    }

    // 3. Fondazione della famiglia nuova dopo le versioni di quella vecchia.
    val signer = keys.deviceSigner()
    val me = TrustIds.deviceIdOf(signer.publicKey)
    val request = RegistrationRequest.verify(
      RegistrationRequest.sign(signer, newFamilyId, profile.deviceName, profile.deviceModel, profile.nasUser, now),
      newFamilyId,
      me,
    ).acceptedOrNull() ?: return ResetOutcome.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)
    val authority = FamilyAuthority(newKey, nasClock)
    val founded = sessions.onShare(commonShare, Purpose.INTERACTIVE) { fs -> authority.refound(fs, newFamilyId, config, request, knownVersion) }
    val signed = when (founded) {
      is NasResult.Err -> return ResetOutcome.Failed(problemOf(founded.error))
      is NasResult.Ok -> founded.value ?: return ResetOutcome.Failed(OnboardingProblem.NAS_WRITE_FAILED)
    }

    // 4. Lo stato fidato passa alla famiglia nuova: l'ancora del QR nuovo parte dalla fondazione.
    val anchor = TrustAnchor.create(newFamilyId, newKey.publicKey.fingerprint, signed.registry.version)
      ?: return ResetOutcome.Failed(OnboardingProblem.REGISTRY_UNTRUSTED)
    val state = RegistryVerifier.accept(signed.bytes, null, anchor).acceptedOrNull()
      ?: return ResetOutcome.Failed(OnboardingProblem.REGISTRY_UNTRUSTED)
    engine.install(snapshotOf(state, anchor))
    pendingCode = code
    return ResetOutcome.Done(newFamilyId)
  }

  /** Il codice del kit nuovo in 7 gruppi, da mostrare con `FLAG_SECURE` (null se non c'e' un reset da confermare). */
  fun recoveryCodeForDisplay(): RecoveryCodeDisplay? {
    val code = pendingCode ?: return null
    return RecoveryCodeDisplay(code.toDisplayString().split('-').map { it.toCharArray() })
  }

  /** Vero se [typed] (azzerato dopo l'uso) e' il codice del kit nuovo: allora lo si dimentica. */
  fun confirmTypedCode(typed: CharArray): Boolean {
    val code = pendingCode
    val parsed = try {
      RecoveryCode.parse(String(typed))
    } finally {
      typed.fill('\u0000')
    }
    val ok = code != null && parsed != null && parsed.toDisplayString() == code.toDisplayString()
    if (ok) pendingCode = null
    return ok
  }

  private companion object {
    const val TAG = "FamilyReset"
  }
}
