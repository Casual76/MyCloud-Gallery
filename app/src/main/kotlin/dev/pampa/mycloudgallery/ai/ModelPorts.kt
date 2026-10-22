package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.index.publish.IndexWriteRules
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkGate
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkVerdict
import dev.pampa.mycloudgallery.core.models.contract.ModelShelfGate
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.network.contract.NetPlace
import dev.pampa.mycloudgallery.core.network.contract.NetTransport
import dev.pampa.mycloudgallery.core.network.contract.NetworkSnapshot
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficNetwork

// Le PORTE dei modelli (P3) legate da :app (Fase 6, P11): la rete per l'HTTPS, la rete per il traffico, il cancello dello scaffale.

/** La rete di adesso per il registro del traffico: roaming, dati mobili (o Wi-Fi a consumo), Wi-Fi di casa o un altro Wi-Fi. */
fun trafficNetworkOf(snapshot: NetworkSnapshot): TrafficNetwork {
  val net = snapshot.underlying ?: return TrafficNetwork.MOBILE
  return when {
    net.roaming -> TrafficNetwork.ROAMING
    net.transport == NetTransport.CELLULAR || net.metered -> TrafficNetwork.MOBILE
    snapshot.place == NetPlace.HOME -> TrafficNetwork.HOME_WIFI
    else -> TrafficNetwork.EXTERNAL_WIFI
  }
}

/**
 * Il cancello di rete per lo scaricamento HTTPS dei modelli (§1.4, §2.7, D17): rete **validata** e **mai in roaming**; in background
 * ([ModelTrigger.RULES], [ModelTrigger.ROLE_CHANGED]) solo una rete non a consumo (Wi-Fi), mai i dati mobili; con "Scarica ora"
 * ([ModelTrigger.USER_NOW]) anche i dati mobili, ma solo dopo la conferma con la dimensione ([mobileConfirmed]: la pagina la chiede e
 * la ricorda per lo scaricamento in corso). Il NAS e la cartella dal PC hanno i loro cancelli (sessione, regole): non passano da qui.
 */
class AppModelNetworkGate(
  private val snapshot: () -> NetworkSnapshot,
  private val mobileConfirmed: () -> Boolean,
) : ModelNetworkGate {
  override suspend fun verdict(trigger: ModelTrigger, bytes: Long): ModelNetworkVerdict {
    val s = snapshot()
    val net = s.underlying ?: return ModelNetworkVerdict.Refused(OFFLINE)
    if (!net.validated || net.captivePortal) return ModelNetworkVerdict.Refused(NOT_VALIDATED)
    if (net.roaming) return ModelNetworkVerdict.Refused(ROAMING)
    val mobile = net.transport == NetTransport.CELLULAR || net.metered
    if (!mobile) return ModelNetworkVerdict.Allowed
    return when (trigger) {
      ModelTrigger.USER_NOW -> if (mobileConfirmed()) ModelNetworkVerdict.Allowed else ModelNetworkVerdict.NeedsConfirmation(bytes)
      ModelTrigger.RULES, ModelTrigger.ROLE_CHANGED -> ModelNetworkVerdict.Refused(METERED)
    }
  }

  companion object {
    const val OFFLINE = "offline"
    const val NOT_VALIDATED = "not_validated"
    const val ROAMING = "roaming"
    const val METERED = "metered"
  }
}

/**
 * Il cancello delle copie dei modelli sul NAS (D18) da `IndexWriteRules` (P11): gli stessi blocchi dell'indice (F4-D31 sulle share
 * vere, emergenza, congelamento, salto dell'ora, fiducia). Oggi lo scaffale e' spento (manca lo scrittore a flusso in core-nas, punto
 * aperto di P3): il cancello e' pronto per quando ci sara'.
 */
class RulesModelShelfGate(private val rules: () -> IndexWriteRules?) : ModelShelfGate {
  override suspend fun blockFor(share: String): String? {
    val r = rules() ?: return NO_NAS
    return r.blockFor(share)?.name
  }

  private companion object {
    const val NO_NAS = "no_nas"
  }
}
