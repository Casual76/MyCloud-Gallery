package dev.pampa.mycloudgallery.core.network.env

import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprint
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprintStore
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkAccess
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkPermission
import dev.pampa.mycloudgallery.core.network.contract.NetPlace
import dev.pampa.mycloudgallery.core.network.contract.NetworkEnvironment
import dev.pampa.mycloudgallery.core.network.contract.NetworkSnapshot
import dev.pampa.mycloudgallery.core.network.link.NetworkObservation
import dev.pampa.mycloudgallery.core.network.policy.EvaluationQualitySignal
import dev.pampa.mycloudgallery.core.network.policy.NetworkEvaluation
import dev.pampa.mycloudgallery.core.network.policy.NetworkPolicy
import dev.pampa.mycloudgallery.core.rules.NetworkQualitySignal
import dev.pampa.mycloudgallery.core.rules.NetworkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Da dove arrivano le reti: su Android i `NetworkCallback` ([AndroidNetworkObserver]), nei test un valore scritto a mano. */
interface NetworkObservationSource {
  val observation: StateFlow<NetworkObservation>

  /** Comincia a osservare. Idempotente. */
  fun start()
}

/**
 * La parte dell'ambiente di rete che serve al cancello, al Wake-on-LAN e alla Diagnostica: il percorso e le reti
 * da cui e' stato ricavato.
 */
interface NetworkPathSource {
  /** L'esito corrente, che cambia con la rete, con l'impronta di casa e con il permesso di rete locale. */
  val evaluation: StateFlow<NetworkEvaluation>

  /** Rivaluta adesso con gli ultimi dati (senza aspettare che lo scope giri) e restituisce l'esito. */
  fun evaluateNow(): NetworkEvaluation

  fun observationNow(): NetworkObservation

  fun fingerprintNow(): HomeFingerprint?

  fun localNetworkNow(): LocalNetworkAccess
}

/**
 * L'ambiente di rete vero (P2): unisce le reti osservate, l'impronta di casa e il permesso di rete locale, e li
 * passa alla [NetworkPolicy]. E' l'unico posto che decide "casa", "VPN di casa" e "rete estranea".
 *
 * Implementa `HomeNetworkSignal` (`isAtHome`) e produce lo [NetworkState] delle regole ([toNetworkState]): "casa"
 * ha una sola definizione in tutta l'app.
 *
 * Al costruttore comincia a osservare ([NetworkObservationSource.start]) e a seguire l'impronta nello [scope] dato
 * (quello dell'applicazione). Le letture "adesso" ([evaluateNow]) non dipendono dallo scope: sono sempre fresche.
 *
 * @param clockMillis orologio monotono in millisecondi, per `NetworkSnapshot.at`.
 */
class LiveNetworkEnvironment(
  private val source: NetworkObservationSource,
  private val fingerprints: HomeFingerprintStore,
  private val localNetwork: LocalNetworkPermission,
  scope: CoroutineScope,
  private val clockMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) : NetworkEnvironment, NetworkPathSource {

  private val evaluations = MutableStateFlow(NetworkEvaluation.INITIAL)
  private val snapshots = MutableStateFlow(NetworkSnapshot.OFFLINE)
  private val home = MutableStateFlow(false)
  private val poor = MutableStateFlow(false)

  override val evaluation: StateFlow<NetworkEvaluation> = evaluations
  override val snapshot: StateFlow<NetworkSnapshot> = snapshots
  override val isAtHome: StateFlow<Boolean> = home

  /** La parte di E-R4 che Android sa dire (rete congestionata o sospesa). */
  val quality: NetworkQualitySignal = EvaluationQualitySignal(poor)

  init {
    source.start()
    refresh()
    scope.launch {
      combine(source.observation, fingerprints.fingerprint) { _, _ -> }.collect { refresh() }
    }
  }

  override fun toNetworkState(): NetworkState = NetworkPolicy.toNetworkState(refresh().snapshot)

  override fun evaluateNow(): NetworkEvaluation = refresh()

  override fun observationNow(): NetworkObservation = source.observation.value

  override fun fingerprintNow(): HomeFingerprint? = fingerprints.fingerprint.value

  override fun localNetworkNow(): LocalNetworkAccess = localNetwork.status()

  /**
   * Da chiamare quando cambia qualcosa che l'ambiente non osserva da solo: il permesso di rete locale (dopo la
   * risposta alla richiesta a runtime, o al ritorno in primo piano).
   */
  fun onPermissionsChanged() {
    refresh()
  }

  @Synchronized
  private fun refresh(): NetworkEvaluation {
    val fresh = NetworkPolicy.evaluate(
      observation = source.observation.value,
      fingerprint = fingerprints.fingerprint.value,
      localNetwork = localNetwork.status(),
      at = clockMillis(),
    )
    val previous = evaluations.value
    if (!fresh.sameAs(previous)) {
      evaluations.value = fresh
      snapshots.value = fresh.snapshot
      home.value = fresh.snapshot.place == NetPlace.HOME
      poor.value = fresh.poor
    }
    return evaluations.value
  }

  private companion object {
    const val NANOS_PER_MILLI = 1_000_000L
  }
}
