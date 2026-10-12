package dev.pampa.mycloudgallery.core.network.gate

import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.network.contract.HomeCandidate
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkAccess
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.network.contract.NasRouteGate
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.env.NetworkPathSource
import dev.pampa.mycloudgallery.core.network.home.HomeCandidates
import dev.pampa.mycloudgallery.core.network.policy.NetworkEvaluation
import dev.pampa.mycloudgallery.core.network.policy.PathDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Il cancello di rete (P2): trasforma la decisione della politica in un [NasRoute] con il socket legato.
 *
 * **E' l'unico posto che crea un [NasRoute.Allowed]** (il costruttore e' pubblico solo per i fake). Una rete non
 * riconosciuta da' [NasRoute.Denied] e il tipo non ha un socket da usare: non parte nemmeno la connessione TCP
 * (E-S10).
 *
 * Il valore di [routes] cambia **solo** quando cambia la sostanza (la via, la rete del legame, il motivo del
 * rifiuto): chi tiene una sessione aperta confronta `binding.networkId` e la chiude al primo cambio (§3.1,
 * "Invalidazione"). Per questo l'istanza di `Allowed` resta la stessa finche' il percorso non cambia.
 *
 * [current] non si fida dell'ultimo valore emesso: rivaluta con gli ultimi dati (rete, impronta, permesso), cosi'
 * un percorso appena caduto non risulta mai consentito.
 */
class DefaultNasRouteGate(
  private val paths: NetworkPathSource,
  private val bindings: BindingFactory,
  scope: CoroutineScope,
) : NasRouteGate {

  private val state = MutableStateFlow<NasRoute>(NasRoute.Denied(DenyReason.OFFLINE))

  override val routes: StateFlow<NasRoute> = state

  init {
    apply(paths.evaluateNow())
    scope.launch { paths.evaluation.collect { apply(it) } }
  }

  override fun current(): NasRoute {
    apply(paths.evaluateNow())
    return state.value
  }

  @Synchronized
  private fun apply(evaluation: NetworkEvaluation) {
    val decision = evaluation.decision
    val current = state.value
    when (decision) {
      is PathDecision.Denied -> {
        if (current is NasRoute.Denied && current.reason == decision.reason) return
        state.value = NasRoute.Denied(decision.reason)
      }
      is PathDecision.Allowed -> {
        if (current is NasRoute.Allowed && current.via == decision.via && current.binding.networkId == decision.networkId) return
        val binding = bindings.bindingFor(decision.networkId)
        // La rete e' sparita fra la valutazione e adesso: nessun percorso.
        state.value = if (binding == null) NasRoute.Denied(DenyReason.OFFLINE) else NasRoute.Allowed(decision.via, binding)
      }
    }
  }

  /**
   * Il percorso per il passo 2 dell'onboarding, prima che esista un'impronta: il Wi-Fi da cui e' stato letto
   * [candidate], senza VPN. Qualunque altra situazione e' `Denied`:
   *  - il Wi-Fi non e' piu' quello (o e' sparito);
   *  - dietro una pagina di accesso;
   *  - una VPN accesa: senza impronta non si puo' dire se e' quella di casa, e il tunnel cattura il traffico anche
   *    di un socket legato al Wi-Fi (l'onboarding chiede di spegnerla per questo passo);
   *  - la rete e' cambiata dopo la lettura (ULA, gateway, dominio): il candidato non e' piu' quello;
   *  - rete locale negata (target 37).
   *
   * Un candidato senza ULA ([HomeCandidate.canBeConfirmed] falso) puo' comunque usare il percorso: decidere se
   * confermare "casa" e' dell'interfaccia, e senza impronta salvata l'app non riconoscera' mai la LAN.
   */
  override fun onboardingRoute(candidate: HomeCandidate): NasRoute {
    val observation = paths.observationNow()
    if (observation.networks.isEmpty()) return NasRoute.Denied(DenyReason.OFFLINE)
    val lan = observation.byId(candidate.networkId)?.takeIf { it.isLan } ?: return NasRoute.Denied(DenyReason.UNTRUSTED_NETWORK)
    if (lan.captivePortal) return NasRoute.Denied(DenyReason.CAPTIVE_PORTAL)
    if (observation.vpns.isNotEmpty()) return NasRoute.Denied(DenyReason.FOREIGN_VPN)
    if (HomeCandidates.from(lan) != candidate) return NasRoute.Denied(DenyReason.UNTRUSTED_NETWORK)
    if (paths.localNetworkNow() == LocalNetworkAccess.DENIED) return NasRoute.Denied(DenyReason.LOCAL_NETWORK_DENIED)
    val binding = bindings.bindingFor(lan.networkId) ?: return NasRoute.Denied(DenyReason.OFFLINE)
    return NasRoute.Allowed(Via.HOME_LAN, binding)
  }
}
