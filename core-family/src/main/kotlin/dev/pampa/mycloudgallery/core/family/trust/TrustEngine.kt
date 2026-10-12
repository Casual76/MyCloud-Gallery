package dev.pampa.mycloudgallery.core.family.trust

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.family.contract.SelfWipe
import dev.pampa.mycloudgallery.core.family.contract.StaleReason
import dev.pampa.mycloudgallery.core.family.contract.TrustSnapshot
import dev.pampa.mycloudgallery.core.family.contract.TrustStateStore
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.family.contract.WipeReason
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.KeyProvider
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalCodes
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.registry.RegistrySync
import dev.pampa.mycloudgallery.core.trust.registry.RegistryVerifier
import dev.pampa.mycloudgallery.core.trust.registry.TrustAnchor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Cio' che si sa del registro dopo l'ultima lettura, per lo stato, la guardia e la Diagnostica. Solo numeri,
 * flag ed enum: niente nomi letti dal NAS.
 *
 * @property manualAnchor configurato senza QR (versione minima 0): la Diagnostica lo dice (§2.2).
 * @property lastSyncFailed l'ultima lettura non ha raggiunto il NAS ([lastError]).
 */
data class TrustFacts(
  val registryVersion: Long? = null,
  val deviceCount: Int = 0,
  val rejected: Map<RejectReason, Int> = emptyMap(),
  val listingTruncated: Boolean = false,
  val needsAppUpdate: Boolean = false,
  val forkCount: Int = 0,
  val trustedVersionReplaced: Boolean = false,
  val incomplete: Boolean = false,
  val healed: Boolean = false,
  val manualAnchor: Boolean = false,
  val localStateUntrusted: Boolean = false,
  val lastSyncFailed: Boolean = false,
  val lastError: NasError? = null,
  val lastSyncAtNas: Long? = null,
  val registryBehind: Boolean = false,
) {
  val newerUnverified: Boolean get() = rejected.values.sum() > 0
}

/** L'esito di una lettura del registro. */
sealed interface SyncOutcome {
  /** Nessuno stato salvato o nessuna chiave del dispositivo. */
  data object NotConfigured : SyncOutcome

  /** Lo stato salvato in locale non si riverifica (file scambiato o rovinato): si lavora chiusi. */
  data object LocalStateUntrusted : SyncOutcome

  data class NasFailed(val error: NasError) : SyncOutcome

  class Synced(val result: RegistrySync.Result) : SyncOutcome
}

/**
 * Il motore della fiducia (P3): tiene in memoria l'ultimo registro **accettato** (ricaricato dal
 * [TrustStateStore] con `RegistryVerifier.restore`), lo aggiorna dal NAS con `RegistrySync` e salva ogni
 * stato nuovo prima di usarlo. Calcola lo stato di questo dispositivo ([status]) e i fatti per la Diagnostica
 * ([facts]).
 *
 * **L'unico punto in cui un dato remoto fa azzerare**: dopo ogni lettura, se il registro *accettato* dice che
 * questo dispositivo e' perso ([RegistryState.mustSelfWipe]) parte [SelfWipe]. Un registro non firmato dalla
 * chiave attesa, un rollback, un'equivocazione o una regressione non diventano mai lo stato accettato
 * (`RegistryVerifier`, `RegistrySync`): restano rifiuti e allarmi, quindi non fanno azzerare (test obbligatorio).
 */
class TrustEngine(
  private val sessions: NasSessionManager,
  private val store: TrustStateStore,
  private val keys: KeyProvider,
  private val profiles: LocalDeviceProfileSource,
  private val nasClock: NasClock,
  private val selfWipe: SelfWipe,
) {

  private val mutex = Mutex()
  private var snapshot: TrustSnapshot? = null
  private var loadedBytesHash: Int? = null

  @Volatile
  var state: RegistryState? = null
    private set

  private val _facts = MutableStateFlow(TrustFacts())
  private val _status = MutableStateFlow<TrustStatus>(TrustStatus.NotConfigured)

  val facts: StateFlow<TrustFacts> = _facts.asStateFlow()
  val status: StateFlow<TrustStatus> = _status.asStateFlow()

  /** L'ora del NAS (`SessionNasClock`): per finestre, scadenze e firme. */
  fun nasNow(): Long = nasClock.nowMillis()

  /** L'id di questo dispositivo, se ha una chiave; non la crea mai. */
  fun myDeviceId(): String? = if (keys.hasDeviceKey()) TrustIds.deviceIdOf(keys.deviceSigner().publicKey) else null

  suspend fun profile(): LocalDeviceProfile? = profiles.load()

  /** I segni anti-rollback delle serie lette (`TrustSnapshot.ownHeads`), per chiave. */
  fun mark(key: String): HeadMark? = snapshot?.ownHeads?.get(key)

  /**
   * Ricarica lo stato salvato se e' cambiato (l'onboarding di P4 lo scrive all'ingresso). Vero se c'e' uno
   * stato fidato in memoria.
   */
  suspend fun ensureLoaded(): Boolean = mutex.withLock { loadLocked() != null }

  /** Rilegge il registro dal NAS (area comune del profilo), salva e ricalcola. */
  suspend fun sync(purpose: Purpose): SyncOutcome {
    val outcome = mutex.withLock { syncLocked(purpose) }
    afterChange()
    return outcome
  }

  /** Dopo una pubblicazione dell'admin: lo stato appena pubblicato (gia' accettato) diventa quello fidato. */
  suspend fun adopt(newState: RegistryState) {
    mutex.withLock {
      val current = state
      if (current != null && newState.registry.version <= current.registry.version && newState.contentHash != current.contentHash) return@withLock
      saveLocked(newState, snapshot?.ownHeads.orEmpty())
      _facts.update { it.copy(registryVersion = newState.registry.version, deviceCount = newState.registry.devices.size) }
    }
    afterChange()
  }

  /**
   * Una famiglia nuova per questo dispositivo (recupero dell'admin, reset): [newSnapshot] sostituisce tutto,
   * segni compresi.
   */
  suspend fun install(newSnapshot: TrustSnapshot) {
    mutex.withLock {
      store.save(newSnapshot)
      snapshot = null
      loadedBytesHash = null
      state = null
      loadLocked()
    }
    afterChange()
  }

  /** Aggiorna i segni anti-rollback (stato dei dispositivi) e li salva. */
  suspend fun updateMarks(changes: Map<String, HeadMark>) {
    if (changes.isEmpty()) return
    mutex.withLock {
      val current = state ?: return@withLock
      saveLocked(current, snapshot?.ownHeads.orEmpty() + changes)
    }
  }

  fun noteRegistryBehind(behind: Boolean) {
    _facts.update { it.copy(registryBehind = behind) }
  }

  // ------------------------------------------------------------------------------------------------------

  private suspend fun loadLocked(): RegistryState? {
    val saved = store.load()
    if (saved == null) {
      snapshot = null
      state = null
      loadedBytesHash = null
      return null
    }
    val bytes = saved.registryBytes
    val hash = bytes.contentHashCode()
    if (state != null && hash == loadedBytesHash && saved.familyId == snapshot?.familyId) {
      snapshot = saved
      return state
    }
    return when (val verdict = RegistryVerifier.restore(bytes, saved.signerFingerprint)) {
      is Verdict.Accepted -> {
        if (verdict.value.registry.familyId != saved.familyId) {
          markUntrusted()
          null
        } else {
          snapshot = saved
          state = verdict.value
          loadedBytesHash = hash
          _facts.update {
            it.copy(
              registryVersion = verdict.value.registry.version,
              deviceCount = verdict.value.registry.devices.size,
              manualAnchor = saved.minRegistryVersion <= 0L,
              localStateUntrusted = false,
            )
          }
          verdict.value
        }
      }
      is Verdict.Rejected -> {
        markUntrusted()
        null
      }
    }
  }

  private fun markUntrusted() {
    GalleryLog.w(TAG) { "stato locale del registro non riverificabile" }
    snapshot = null
    state = null
    loadedBytesHash = null
    _facts.update { it.copy(localStateUntrusted = true) }
  }

  private suspend fun syncLocked(purpose: Purpose): SyncOutcome {
    val current = loadLocked()
    val saved = snapshot
    if (current == null || saved == null) {
      return if (_facts.value.localStateUntrusted) SyncOutcome.LocalStateUntrusted else SyncOutcome.NotConfigured
    }
    val anchor = TrustAnchor.create(saved.familyId, saved.familyKeyFingerprint, saved.minRegistryVersion.coerceAtLeast(0))
      ?: return SyncOutcome.LocalStateUntrusted
    val share = profiles.load()?.commonShare ?: current.registry.config.commonShare
    val result = try {
      sessions.onShare(share, purpose) { fs -> RegistrySync(fs).refresh(current, anchor) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      NasResult.Err(UNEXPECTED)
    }
    return when (result) {
      is NasResult.Err -> {
        _facts.update { it.copy(lastSyncFailed = true, lastError = result.error) }
        SyncOutcome.NasFailed(result.error)
      }
      is NasResult.Ok -> {
        val sync = result.value
        val accepted = sync.state
        if (accepted != null && sync.changed) saveLocked(accepted, saved.ownHeads)
        val now = nasClock.nowMillis()
        _facts.update {
          it.copy(
            registryVersion = accepted?.registry?.version,
            deviceCount = accepted?.registry?.devices?.size ?: 0,
            rejected = sync.rejected.asMap(),
            listingTruncated = sync.listingTruncated,
            needsAppUpdate = sync.needsAppUpdate,
            forkCount = sync.forks.size,
            trustedVersionReplaced = sync.trustedVersionReplaced,
            incomplete = sync.incomplete,
            healed = sync.healed,
            lastSyncFailed = false,
            lastError = null,
            lastSyncAtNas = now,
          )
        }
        SyncOutcome.Synced(sync)
      }
    }
  }

  /** Salva [newState] (con i segni) e poi lo rende quello in memoria: mai usare uno stato non salvato. */
  private suspend fun saveLocked(newState: RegistryState, marks: Map<String, HeadMark>) {
    val previous = snapshot ?: return
    val next = TrustSnapshot(
      registryBytes = newState.signedBytes,
      signerFingerprint = newState.signerKey.fingerprint,
      familyId = previous.familyId,
      familyKeyFingerprint = previous.familyKeyFingerprint,
      // L'ancora resta quella di partenza: l'anti-rollback lo fa il registro salvato (RegistrySync non torna mai
      // indietro), e "versione minima 0" resta l'indizio di un ingresso senza QR per la Diagnostica.
      minRegistryVersion = previous.minRegistryVersion,
      ownHeads = marks,
    )
    store.save(next)
    snapshot = next
    state = newState
    loadedBytesHash = next.registryBytes.contentHashCode()
  }

  /** Dopo ogni cambio: lo stato di questo dispositivo e, se il registro accettato dice "perso", l'azzeramento. */
  private suspend fun afterChange() {
    val current = state
    val me = try {
      myDeviceId()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    }
    if (current != null && me != null && current.mustSelfWipe(me) && selfWipe.progress.value == null) {
      GalleryLog.w(TAG) { "il registro firmato dice che questo dispositivo e' perso: azzeramento" }
      _status.value = TrustStatus.Lost
      selfWipe.run(WipeReason.MARKED_LOST)
    }
    _status.value = computeStatus(current, me)
  }

  private fun computeStatus(current: RegistryState?, me: String?): TrustStatus {
    if (selfWipe.progress.value != null) return TrustStatus.Lost
    if (current == null || me == null) return TrustStatus.NotConfigured
    val entry = current.registry.device(me)
      ?: return TrustStatus.Pending(keys.deviceSigner().publicKey.let(ApprovalCodes::of))
    return when (entry.status) {
      DeviceStatus.LOST -> TrustStatus.Lost
      DeviceStatus.REVOKED -> TrustStatus.Revoked
      DeviceStatus.RETIRED -> TrustStatus.Retired
      DeviceStatus.APPROVED -> staleReason(_facts.value)?.let { TrustStatus.Stale(it) } ?: TrustStatus.Approved(entry.admin)
    }
  }

  /** Recompute (per esempio dopo la lettura degli stati dei dispositivi). */
  fun refreshStatus() {
    val me = try {
      myDeviceId()
    } catch (e: Exception) {
      null
    }
    _status.value = computeStatus(state, me)
  }

  private companion object {
    const val TAG = "TrustEngine"

    /** Il motivo piu' serio per cui un dispositivo approvato potrebbe lavorare con un registro vecchio. */
    fun staleReason(f: TrustFacts): StaleReason? = when {
      f.needsAppUpdate -> StaleReason.NEEDS_APP_UPDATE
      f.forkCount > 0 -> StaleReason.EQUIVOCATION
      f.listingTruncated -> StaleReason.LISTING_TRUNCATED
      f.newerUnverified || f.trustedVersionReplaced || f.incomplete || f.registryBehind -> StaleReason.NEWER_UNVERIFIED
      f.lastSyncFailed -> StaleReason.NOT_REFRESHED
      else -> null
    }
  }
}
