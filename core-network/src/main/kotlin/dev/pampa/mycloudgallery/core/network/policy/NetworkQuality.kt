package dev.pampa.mycloudgallery.core.network.policy

import dev.pampa.mycloudgallery.core.rules.NetworkQualitySignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * "La rete in uso e' scadente" (E-R4), nella parte che Android sa dire: la rete sottostante e' **congestionata** o
 * **sospesa** (`NOT_CONGESTED` / `NOT_SUSPENDED` assenti, Android 11+). Non guarda le stime di banda, che
 * Android non da' in modo affidabile (docs/android.md, `NetworkQualitySignal`): la parte misurata (latenza,
 * throughput, blocchi) la da' `core-nas`.
 *
 * Lo implementa [dev.pampa.mycloudgallery.core.network.env.LiveNetworkEnvironment]; `:app` lo unisce a quello del
 * NAS con [CombinedNetworkQuality].
 */
internal class EvaluationQualitySignal(override val isPoor: StateFlow<Boolean>) : NetworkQualitySignal

/**
 * Scadente se **almeno uno** dei segnali lo dice (la rete di Android o le misure sul NAS). Il valore parte
 * subito, senza aspettare che lo scope giri.
 */
class CombinedNetworkQuality(
  signals: List<NetworkQualitySignal>,
  scope: CoroutineScope,
) : NetworkQualitySignal {

  override val isPoor: StateFlow<Boolean> =
    if (signals.isEmpty()) {
      kotlinx.coroutines.flow.MutableStateFlow(false)
    } else {
      combine(signals.map { it.isPoor }) { values -> values.any { it } }
        .stateIn(scope, SharingStarted.Eagerly, signals.any { it.isPoor.value })
    }
}
