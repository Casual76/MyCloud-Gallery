package dev.pampa.mycloudgallery.nas

import dev.pampa.mycloudgallery.core.imaging.viewer.NasLink
import dev.pampa.mycloudgallery.core.imaging.viewer.NasNetwork
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.network.contract.NasRouteGate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onStart

/**
 * Il collegamento verso il NAS come lo vede il viewer (D-15 delle prove sul campo): non solo "a consumo o no", ma **dove si puo' andare adesso**
 * (il percorso del cancello di rete, [NasRouteGate]) e **quando cambia** (il percorso e il consumo).
 *
 * Prima il viewer sapeva solo [isMetered] e lo valutava una volta: fuori casa con la VPN spenta proponeva "Servono i dati mobili" (che non servono a
 * niente, il NAS non si raggiunge) e, tornato il Wi-Fi, restava su quell'avviso finche' non si chiudeva e riapriva il media.
 *
 * @param metered il collegamento e' a consumo adesso (dati mobili, hotspot, anche attraverso la VPN).
 * @param meteredChanges [metered] a ogni suo cambiamento (di serie nessuno: il percorso cambia comunque con la rete).
 */
class AppNasLink(
  private val route: NasRouteGate,
  private val metered: () -> Boolean,
  private val meteredChanges: Flow<Boolean> = emptyFlow(),
) : NasLink {

  override fun isMetered(): Boolean = metered()

  override fun network(): NasNetwork = networkOf(route.current(), metered())

  override val changes: Flow<NasNetwork> by lazy {
    // Cominciando da quella di adesso (anche senza un cambiamento del consumo), poi a ogni cambiamento del percorso o del consumo.
    combine(route.routes, meteredChanges.onStart { emit(metered()) }) { current, isMetered -> networkOf(current, isMetered) }.distinctUntilChanged()
  }

  companion object {
    /**
     * La rete secondo il percorso e il consumo. Un percorso negato dice **perche'** (E-R3: fuori casa con la VPN spenta; E-S10: rete non
     * riconosciuta...): prima il percorso, poi il consumo. Con il percorso aperto, a consumo o no.
     */
    fun networkOf(route: NasRoute, metered: Boolean): NasNetwork = when (route) {
      is NasRoute.Allowed -> if (metered) NasNetwork.METERED else NasNetwork.OPEN
      is NasRoute.Denied -> when (route.reason) {
        DenyReason.VPN_OFF_AWAY -> NasNetwork.VPN_OFF
        DenyReason.OFFLINE -> NasNetwork.OFFLINE
        DenyReason.CAPTIVE_PORTAL,
        DenyReason.UNTRUSTED_NETWORK,
        DenyReason.FOREIGN_VPN,
        DenyReason.LOCAL_NETWORK_DENIED,
        DenyReason.NOT_CONFIGURED,
        -> NasNetwork.NOT_ALLOWED
      }
    }
  }
}
