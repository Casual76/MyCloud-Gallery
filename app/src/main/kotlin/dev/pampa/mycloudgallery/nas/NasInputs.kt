package dev.pampa.mycloudgallery.nas

import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.family.contract.TrustGuard
import dev.pampa.mycloudgallery.core.index.sync.DeviceStanding
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.session.SessionNasClock
import dev.pampa.mycloudgallery.core.network.contract.NasRouteGate
import dev.pampa.mycloudgallery.core.rules.ActivityRulesEngine
import dev.pampa.mycloudgallery.core.rules.SystemStateProvider
import dev.pampa.mycloudgallery.core.rules.run.NasFreezeSignal
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficLedger
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** Un'area del NAS di questo dispositivo: la share e se e' l'area privata o quella comune. */
data class NasShareArea(val share: String, val area: NasArea) {
  override fun toString(): String = "NasShareArea($area)"
}

/**
 * Cio' che il grafo del NAS ([NasGraph]) riceve dal grafo dell'app della Fase 2 (`AppGraph`): sessione SMB, ora del NAS,
 * fiducia e registro, percorso, regole, consumi, configurazione dell'onboarding. Funzioni e non valori: l'account, le aree
 * e la chiave del dispositivo cambiano con l'onboarding e con un azzeramento.
 *
 * @property shares le aree configurate (privata e comune), vuoto finche' l'onboarding non le ha scelte.
 * @property commonExclusions le cartelle dell'area comune escluse (configurazione firmata, D11), percorsi di media.
 * @property metered il collegamento verso il NAS e' a consumo (dati mobili, hotspot): D23, E-V2.
 * @property meteredChanges D-15: [metered] a ogni suo cambiamento (la rete sottostante cambia da Wi-Fi a dati mobili e viceversa): il viewer segue la
 *   rete di adesso invece di tenere quella di quando ha calcolato il suo stato. Di serie nessun cambiamento.
 */
class NasInputs(
  val flags: FeatureFlags,
  val session: NasSessionManager,
  val nasClock: SessionNasClock,
  val account: () -> String?,
  val shares: () -> List<NasShareArea>,
  val commonExclusions: () -> List<String>,
  val deviceId: () -> String?,
  val signer: () -> Signer?,
  val registry: () -> RegistryState?,
  val trust: TrustGuard,
  val route: NasRouteGate,
  val rules: ActivityRulesEngine,
  val stateProvider: SystemStateProvider,
  val ledger: TrafficLedger,
  val metered: () -> Boolean,
  val freezeSignal: MutableNasFreezeSignal,
  val lastDiskTouchAt: StateFlow<Long?>,
  val meteredChanges: Flow<Boolean> = emptyFlow(),
  /**
   * Fase 10 (P13, §1.4, §2.8): il manifest firmato ferma le scritture (`ReleaseStatus.ReadOnly` o `Stopped`, o lo stato non ancora letto). Entra in **ogni** cancello
   * come l'emergenza "Ferma le scritture sul NAS": indice, album, volti, dati personali e modelli (`NasGraph.writeGate`), operazioni sui file (`opsGate`), backup e
   * file in arrivo (`BackupGraph`). Di serie mai (i test che non lo provano).
   */
  val releaseWritesStopped: () -> Boolean = { false },
) {
  /** Lo stato di un dispositivo nel registro accettato (E-I15): i dati di revocati, persi e sconosciuti spariscono. */
  fun standing(deviceId: String): DeviceStanding = when (registry()?.registry?.device(deviceId)?.status) {
    DeviceStatus.APPROVED -> DeviceStanding.APPROVED
    DeviceStatus.RETIRED -> DeviceStanding.RETIRED
    DeviceStatus.REVOKED -> DeviceStanding.REVOKED
    DeviceStatus.LOST -> DeviceStanding.LOST
    null -> DeviceStanding.UNKNOWN
  }
}

/**
 * "Una share del NAS e' congelata" per il motore delle regole (P9, `NasFreezeSignal`): lo crea `AppGraph` (il motore nasce
 * prima della galleria) e lo aggiorna [NasGraph] dallo stato di congelamento delle scansioni. Con piu' share congelate vale
 * la ragione piu' grave (sparizioni prima di cambiamenti, poi "non piu' media").
 */
class MutableNasFreezeSignal : NasFreezeSignal {
  private val mutable = MutableStateFlow<FreezeReason?>(null)
  override val frozen: StateFlow<FreezeReason?> = mutable

  fun update(reasons: Collection<FreezeReason>) {
    mutable.value = reasons.minByOrNull { SEVERITY.indexOf(it) }
  }

  private companion object {
    val SEVERITY = listOf(FreezeReason.VANISHED, FreezeReason.CHANGED, FreezeReason.NOT_MEDIA)
  }
}
