package dev.pampa.mycloudgallery.core.family.onboarding

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.TrustSnapshot
import dev.pampa.mycloudgallery.core.family.lifecycle.problemOf
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.TrustViolation
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKeyUse
import dev.pampa.mycloudgallery.core.trust.crypto.RecoveryCode
import dev.pampa.mycloudgallery.core.trust.crypto.RecoveryKit
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.crypto.VaultResult
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.registry.FamilyAuthority
import dev.pampa.mycloudgallery.core.trust.registry.FamilyConfig
import dev.pampa.mycloudgallery.core.trust.registry.RegistrationRequest
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.registry.RegistryVerifier
import dev.pampa.mycloudgallery.core.trust.registry.SignedRegistry
import dev.pampa.mycloudgallery.core.trust.registry.TrustAnchor
import kotlinx.coroutines.CancellationException

/**
 * La famiglia che si sta fondando al passo 12, tenuta **in memoria** (la chiave sta nel `FamilyKeyVault`; il
 * codice di recupero non si salva mai: sta solo sul foglio dell'utente e qui, finche' lo schermo lo mostra).
 *
 * @property code il codice mostrato; null quando si riprende da (e), dopo che il kit e' stato verificato.
 * @property sealedKit i byte del kit pubblicato: se la scrittura va ripetuta, si ripetono gli stessi byte (idempotenza).
 * @property founding il registro di fondazione firmato: stesso discorso, finche' NAS e area comune restano quelli.
 */
internal class PendingFoundation(
  val familyId: String,
  val familyKey: FamilyKey,
  val code: RecoveryCode?,
  var kitVerified: Boolean,
) {
  var sealedKit: ByteArray? = null
  var founding: SignedRegistry? = null

  /** La configurazione con cui e' stato firmato [founding]: se l'utente cambia area o scelte, la fondazione si firma di nuovo. */
  var foundingConfig: FamilyConfig? = null
}

internal sealed interface FoundationResult {
  data class Failed(val problem: OnboardingProblem) : FoundationResult

  class Founded(val state: RegistryState) : FoundationResult
}

internal sealed interface FoundationStart {
  class Started(val pending: PendingFoundation) : FoundationStart

  data class Blocked(val problem: OnboardingProblem) : FoundationStart
}

/**
 * Il passo 12 (`docs/fase2-progetto.md` §2.1), **nell'ordine fisso** che tiene al sicuro la chiave di famiglia:
 *
 * - (a) [begin]: genera la chiave e la conserva in `FamilyKeyVault.store` (cifrata dal Keystore, dopo l'impronta o il PIN: M3);
 * - (b) [begin]: genera il codice di recupero (mai scelto dall'utente) per mostrarlo con `FLAG_SECURE`;
 * - (c) [run]: l'utente lo **riscrive per intero**; se non coincide non si fa altro;
 * - (d) [run]: `RecoveryKit.seal` → `publishExclusive` nell'area privata → **rilettura e `open` con il codice
 *   digitato**; solo se si riapre il kit esiste davvero, e solo allora si va avanti;
 * - (e) [run]: richiesta del fondatore → `FamilyAuthority.found` → `publishFounding` → rilettura e verifica
 *   con l'ancora (id, impronta, versione 1).
 *
 * Perche' il kit viene PRIMA della fondazione: una famiglia fondata con una chiave che non si puo' recuperare
 * e' una famiglia che si perde al primo telefono rotto (E-S13). Se si interrompe fra (d) ed (e) si riprende da (e).
 *
 * Ogni scrittura e' **idempotente**: `ALREADY_EXISTS` con gli stessi byte vale come successo; un kit che si
 * riapre col codice digitato, o un registro di fondazione firmato dalla stessa chiave e della stessa famiglia,
 * valgono come "l'ho gia' scritto io" (un tentativo precedente interrotto). `ALREADY_EXISTS` in (e) con
 * qualunque altro contenuto vuol dire che qui c'e' gia' un'altra famiglia ([OnboardingProblem.FAMILY_ALREADY_EXISTS]).
 */
internal class FamilyFoundation(private val ports: OnboardingPorts) {

  /**
   * (a) e (b). La chiave non si puo' conservare se il telefono non ha un blocco schermo sicuro (la cassaforte lo pretende) ne' senza
   * l'impronta o il PIN dell'admin sul foglio di sistema (Fase 10, M3): il tasto "Crea la chiave" e' il gesto che lo chiede.
   */
  suspend fun begin(): FoundationStart {
    val familyId = TrustIds.random(ports.random)
    val key = FamilyKey.generate(ports.random)
    val stored = try {
      ports.familyKeys.store(key, FamilyKeyUse.SAVE)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "chiave di famiglia non conservata (${e.javaClass.simpleName})" }
      return FoundationStart.Blocked(OnboardingProblem.CREDENTIALS_NOT_SAVED)
    }
    if (stored is VaultResult.Refused) {
      GalleryLog.w(TAG) { "chiave di famiglia non conservata (${stored.reason})" }
      return FoundationStart.Blocked(problemOf(stored.reason))
    }
    return FoundationStart.Started(PendingFoundation(familyId, key, RecoveryCode.generate(ports.random), kitVerified = false))
  }

  suspend fun run(
    snapshot: OnboardingSnapshot,
    pending: PendingFoundation,
    typedCode: CharArray,
    onKitVerified: suspend () -> Unit,
  ): FoundationResult {
    val nas = snapshot.nas ?: return FoundationResult.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)
    val user = snapshot.nasUser ?: return FoundationResult.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)
    val common = snapshot.commonShare ?: return FoundationResult.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)
    val private = snapshot.privateShare ?: return FoundationResult.Failed(OnboardingProblem.STEP_NOT_AVAILABLE)
    val fingerprint = pending.familyKey.publicKey.fingerprint

    if (!pending.kitVerified) {
      val failure = publishAndVerifyKit(pending, typedCode, private, fingerprint)
      if (failure != null) return FoundationResult.Failed(failure)
      pending.kitVerified = true
      onKitVerified()
    }
    return try {
      found(pending, snapshot, nas, user, common, fingerprint)
    } catch (e: TrustViolation) {
      GalleryLog.w(TAG) { "fondazione non valida (${e.reason})" }
      FoundationResult.Failed(OnboardingProblem.NAS_WRITE_FAILED)
    }
  }

  // --- (c) e (d) --------------------------------------------------------------------------------------

  private suspend fun publishAndVerifyKit(
    pending: PendingFoundation,
    typedCode: CharArray,
    privateShare: String,
    fingerprint: String,
  ): OnboardingProblem? {
    val shown = pending.code ?: return OnboardingProblem.RECOVERY_CODE_MISMATCH
    val typed = RecoveryCode.parse(String(typedCode)) ?: return OnboardingProblem.RECOVERY_CODE_MISMATCH
    if (typed.toDisplayString() != shown.toDisplayString()) return OnboardingProblem.RECOVERY_CODE_MISMATCH

    val kit = pending.sealedKit
      ?: RecoveryKit.seal(pending.familyKey, pending.familyId, typed, ports.nasClock.nowMillis()).also { pending.sealedKit = it }
    val path = NasLayout.recoveryKitFile(pending.familyId, fingerprint)
    val written = ports.sessions.onShare(privateShare) { fs ->
      fs.publishExclusive(path, kit)
      fs.read(path, TrustLimits.MAX_RECOVERY_KIT_BYTES)
    }
    val read = when (written) {
      is NasResult.Err -> return NasProblems.write(written.error)
      is NasResult.Ok -> written.value
    }
    // Rilettura e apertura con il codice DIGITATO: e' la prova che il foglio e il file si aprono a vicenda.
    val bytes = (read as? NasRead.Data)?.bytes ?: return OnboardingProblem.RECOVERY_KIT_UNVERIFIED
    val opened = RecoveryKit.open(bytes, typed, pending.familyId, fingerprint) as? Verdict.Accepted
      ?: return OnboardingProblem.RECOVERY_KIT_UNVERIFIED
    if (opened.value.familyKey.publicKey != pending.familyKey.publicKey) return OnboardingProblem.RECOVERY_KIT_UNVERIFIED
    return null
  }

  // --- (e) ----------------------------------------------------------------------------------------------

  private suspend fun found(
    pending: PendingFoundation,
    snapshot: OnboardingSnapshot,
    nas: NasFacts,
    user: String,
    common: String,
    fingerprint: String,
  ): FoundationResult {
    val device = ports.deviceKeys.deviceSigner()
    val description = ports.device.describe(user)
    val config = FamilyConfigFactory.create(snapshot, nas, common)
    val signed = pending.founding?.takeIf { pending.foundingConfig == config } ?: run {
      val request = RegistrationRequest(
        familyId = pending.familyId,
        deviceId = TrustIds.deviceIdOf(device.publicKey),
        key = device.publicKey,
        name = displayOr(description.defaultName, FALLBACK_NAME),
        model = displayOr(description.model, FALLBACK_MODEL),
        nasUser = user,
        requestedAt = ports.nasClock.nowMillis(),
      )
      FamilyAuthority(pending.familyKey, ports.nasClock)
        .found(pending.familyId, config, request)
        .also {
          pending.founding = it
          pending.foundingConfig = config
        }
    }
    val anchor = TrustAnchor.create(pending.familyId, fingerprint, 1) ?: return FoundationResult.Failed(OnboardingProblem.NAS_WRITE_FAILED)

    val written = ports.sessions.onShare(common) { fs ->
      val result = FamilyAuthority.publishFounding(fs, signed)
      result to fs.read(NasLayout.registryFile(1), MAX_REGISTRY_FILE_BYTES)
    }
    val (result, read) = when (written) {
      is NasResult.Err -> return FoundationResult.Failed(NasProblems.write(written.error))
      is NasResult.Ok -> written.value
    }
    val existing = (read as? NasRead.Data)?.bytes ?: return FoundationResult.Failed(
      if (result == PublishResult.ALREADY_EXISTS) OnboardingProblem.FAMILY_ALREADY_EXISTS else OnboardingProblem.NAS_WRITE_FAILED,
    )
    // Rilettura e verifica con l'ancora: id, impronta della chiave di famiglia, versione 1.
    val state = (RegistryVerifier.accept(existing, null, anchor) as? Verdict.Accepted)?.value
    if (state == null || state.signerKey != pending.familyKey.publicKey || state.registry.familyId != pending.familyId) {
      // Qualcosa che non e' la nostra fondazione sta al suo posto: un'altra famiglia, o un file messo a mano.
      return FoundationResult.Failed(
        if (result == PublishResult.ALREADY_EXISTS) OnboardingProblem.FAMILY_ALREADY_EXISTS else OnboardingProblem.NAS_WRITE_FAILED,
      )
    }
    // ALREADY_EXISTS con i nostri byte, o con una nostra fondazione precedente firmata di nuovo: stesso risultato.
    saveTrust(state, pending.familyId, fingerprint)
    return FoundationResult.Founded(state)
  }

  private suspend fun saveTrust(state: RegistryState, familyId: String, fingerprint: String) {
    ports.trustState.save(
      TrustSnapshot(
        registryBytes = state.signedBytes,
        signerFingerprint = state.signerKey.fingerprint,
        familyId = familyId,
        familyKeyFingerprint = fingerprint,
        minRegistryVersion = state.registry.version,
        ownHeads = emptyMap(),
      ),
    )
    try {
      ports.registry.refresh(Purpose.ONBOARDING)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Il registro locale e' gia' salvato: il prossimo controllo lo riallinea.
      GalleryLog.w(TAG) { "registro non riletto subito (${e.javaClass.simpleName})" }
    }
  }

  private fun displayOr(text: String, fallback: String): String =
    try {
      TextRules.displayText(text, TrustLimits.MAX_DISPLAY_TEXT_CHARS)
    } catch (e: TrustViolation) {
      fallback
    }

  companion object {
    private const val TAG = "FamilyFoundation"
    private const val FALLBACK_NAME = "Dispositivo admin"
    private const val FALLBACK_MODEL = "Android"

    /** Un po' piu' del tetto del payload del registro: intestazione e firma (la lettura difensiva e' di `core-trust`). */
    const val MAX_REGISTRY_FILE_BYTES: Int = TrustLimits.MAX_REGISTRY_PAYLOAD + 512
  }
}
