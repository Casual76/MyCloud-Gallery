package dev.pampa.mycloudgallery.core.family.trust

import dev.pampa.mycloudgallery.core.family.contract.PreflightResult
import dev.pampa.mycloudgallery.core.family.contract.ReadOnlyReason
import dev.pampa.mycloudgallery.core.family.contract.SelfWipe
import dev.pampa.mycloudgallery.core.family.contract.StopReason
import dev.pampa.mycloudgallery.core.family.contract.TrustGuard
import dev.pampa.mycloudgallery.core.family.contract.WipePhase
import dev.pampa.mycloudgallery.core.family.contract.WipeReason
import dev.pampa.mycloudgallery.core.family.contract.WritePermit
import dev.pampa.mycloudgallery.core.family.lifecycle.LostReportWatcher
import dev.pampa.mycloudgallery.core.family.registry.DeviceStatusBook
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * La guardia della fiducia (P3, §2.3 "Autodistruzione"): [preflight] prima di **ogni** sessione di lavoro.
 *
 * 1. Un azzeramento iniziato si finisce prima di tutto (il processo puo' essere morto a meta').
 * 2. Il registro si rilegge dal NAS al massimo ogni [refreshIntervalMillis] (15 minuti, orologio monotono):
 *    nel frattempo si decide sull'ultimo registro accettato.
 * 3. Registro **accettato** che dice "perso" → azzeramento (il motore lo avvia). Segnalazione valida nella
 *    propria area privata → azzeramento (decisione 3(a), con la prova di possesso dell'account).
 * 4. Poi: in attesa → sola lettura (E-S14); dismesso → sola lettura; revocato → fermo; app vecchia → fermo;
 *    rami del registro firmati dalla famiglia → sola lettura finche' l'admin non sceglie; approvato → si lavora,
 *    con un [WritePermit], e si pubblica lo stato del dispositivo se e' passato un giorno.
 *
 * Un NAS che non risponde non fa mai azzerare ne' approvare: ferma il lavoro (`NAS_UNAVAILABLE`).
 */
class DefaultTrustGuard(
  private val engine: TrustEngine,
  private val selfWipe: SelfWipe,
  private val lostReports: LostReportWatcher,
  private val statuses: DeviceStatusBook?,
  private val monotonicMillis: () -> Long,
  private val refreshIntervalMillis: Long = REFRESH_INTERVAL_MILLIS,
) : TrustGuard {

  private val mutex = Mutex()
  private var lastRefreshAt: Long? = null

  override suspend fun preflight(purpose: Purpose): PreflightResult = mutex.withLock {
    when (selfWipe.progress.value) {
      null -> Unit
      WipePhase.DONE -> return@withLock PreflightResult.Stop(StopReason.NOT_CONFIGURED)
      else -> {
        selfWipe.resumeIfInterrupted()
        return@withLock PreflightResult.WipeStarted
      }
    }
    if (!engine.ensureLoaded()) {
      val reason = if (engine.facts.value.localStateUntrusted) StopReason.REGISTRY_UNTRUSTED else StopReason.NOT_CONFIGURED
      return@withLock PreflightResult.Stop(reason)
    }
    val me = engine.myDeviceId() ?: return@withLock PreflightResult.Stop(StopReason.NOT_CONFIGURED)

    var nasError: NasError? = null
    val now = monotonicMillis()
    val due = lastRefreshAt.let { it == null || now - it >= refreshIntervalMillis || now < it }
    if (due) {
      when (val outcome = engine.sync(purpose)) {
        is SyncOutcome.NasFailed -> nasError = outcome.error
        SyncOutcome.LocalStateUntrusted -> return@withLock PreflightResult.Stop(StopReason.REGISTRY_UNTRUSTED)
        SyncOutcome.NotConfigured -> return@withLock PreflightResult.Stop(StopReason.NOT_CONFIGURED)
        is SyncOutcome.Synced -> lastRefreshAt = now
      }
      if (selfWipe.progress.value != null) return@withLock PreflightResult.WipeStarted
      if (nasError == null && lostReports.foundForMe(purpose)) {
        selfWipe.run(WipeReason.LOST_REPORT)
        return@withLock PreflightResult.WipeStarted
      }
    }

    val state = engine.state ?: return@withLock PreflightResult.Stop(StopReason.NOT_CONFIGURED)
    if (state.mustSelfWipe(me)) {
      // Stato gia' accettato (per esempio salvato prima che il processo morisse): si azzera ora.
      selfWipe.run(WipeReason.MARKED_LOST)
      return@withLock PreflightResult.WipeStarted
    }
    if (nasError != null) return@withLock PreflightResult.Stop(StopReason.NAS_UNAVAILABLE, nasError)
    val facts = engine.facts.value
    if (facts.needsAppUpdate) return@withLock PreflightResult.Stop(StopReason.NEEDS_APP_UPDATE)
    when (state.statusOf(me)) {
      null -> PreflightResult.ReadOnly(ReadOnlyReason.AWAITING_APPROVAL)
      DeviceStatus.RETIRED -> PreflightResult.ReadOnly(ReadOnlyReason.RETIRED)
      DeviceStatus.REVOKED -> PreflightResult.Stop(StopReason.REVOKED)
      DeviceStatus.LOST -> PreflightResult.WipeStarted // gia' gestito sopra
      DeviceStatus.APPROVED -> if (facts.forkCount > 0) {
        PreflightResult.ReadOnly(ReadOnlyReason.REGISTRY_STALE)
      } else {
        if (due) statuses?.publishIfDue(purpose)
        PreflightResult.Proceed(permitFor(me) ?: return@withLock PreflightResult.ReadOnly(ReadOnlyReason.REGISTRY_STALE))
      }
    }
  }

  /** Il permesso di scrittura sull'ultimo registro accettato, senza toccare il NAS. */
  override fun writePermit(): WritePermit? {
    val me = try {
      engine.myDeviceId()
    } catch (e: Exception) {
      null
    } ?: return null
    return permitFor(me)
  }

  private fun permitFor(me: String): WritePermit? {
    if (selfWipe.progress.value != null) return null
    val state = engine.state ?: return null
    val facts = engine.facts.value
    if (state.statusOf(me) != DeviceStatus.APPROVED || facts.forkCount > 0 || facts.needsAppUpdate) return null
    return WritePermit(me, state.registry.version, facts.lastSyncAtNas ?: 0L)
  }

  companion object {
    /** Al massimo una lettura del registro ogni 15 minuti per le sessioni di lavoro (§2.3). */
    const val REFRESH_INTERVAL_MILLIS: Long = 15L * 60 * 1000
  }
}
