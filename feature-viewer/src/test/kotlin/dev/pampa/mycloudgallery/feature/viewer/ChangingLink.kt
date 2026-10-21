package dev.pampa.mycloudgallery.feature.viewer

import dev.pampa.mycloudgallery.core.imaging.viewer.NasLink
import dev.pampa.mycloudgallery.core.imaging.viewer.NasNetwork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * D-15: un collegamento verso il NAS la cui rete cambia sotto il viewer, come il Wi-Fi che va e viene o la VPN che si accende. E' a consumo solo se la
 * rete e' [NasNetwork.METERED]; [changes] comincia dalla rete di adesso, come quello vero.
 */
internal class ChangingLink(initial: NasNetwork = NasNetwork.OPEN) : NasLink {
  private val flow = MutableStateFlow(initial)

  var value: NasNetwork
    get() = flow.value
    set(next) {
      flow.value = next
    }

  override fun isMetered(): Boolean = flow.value == NasNetwork.METERED

  override fun network(): NasNetwork = flow.value

  override val changes: Flow<NasNetwork> get() = flow
}
