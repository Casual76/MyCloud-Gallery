package dev.pampa.mycloudgallery.core.network.env

import dev.pampa.mycloudgallery.core.network.contract.NetworkEnvironment
import dev.pampa.mycloudgallery.core.rules.DeviceReading
import dev.pampa.mycloudgallery.core.rules.DeviceStateSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Le letture del dispositivo per il motore delle regole, con la **rete letta dall'ambiente di rete** e non dalla rete
 * predefinita di Android.
 *
 * `AndroidDeviceStateSource` legge alimentazione, batteria e temperatura (e anche la rete predefinita). Per la rete
 * questo adattatore usa invece [NetworkEnvironment.toNetworkState], che e' l'unica definizione di "casa" e di "VPN di
 * casa" in tutta l'app:
 *  - una VPN estranea (E-S16) non e' "via VPN" per le regole;
 *  - con due reti (E-R12) conta quella su cui poggia il socket verso il NAS, non quella del sistema;
 *  - a casa senza internet (router senza WAN) il NAS e' in LAN e la rete conta come valida.
 *
 * Si collega cosi' in `:app`: `ComposedSystemStateProvider(device = EnvironmentDeviceStateSource(androidSource,
 * environment), home = environment, quality = ...)`.
 *
 * @param power la sorgente che legge l'alimentazione (di solito `AndroidDeviceStateSource`): della sua lettura si
 *   usa solo la parte di alimentazione.
 */
class EnvironmentDeviceStateSource(
  private val power: DeviceStateSource,
  private val environment: NetworkEnvironment,
) : DeviceStateSource {

  override fun read(): DeviceReading = DeviceReading(power = power.read().power, network = environment.toNetworkState())

  override fun changes(): Flow<Unit> = merge(power.changes(), environment.snapshot.map { })
}
