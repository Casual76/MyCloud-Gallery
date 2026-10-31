package dev.pampa.mycloudgallery.feature.map.noop

import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.map.contract.MapOnlineChoice
import dev.pampa.mycloudgallery.core.map.contract.MapSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fase 9 (P4): le impostazioni della mappa finche' `:app` non lega il DataStore vero (`DataStoreMapSettings`, P13). **Falliscono chiuse**: la mappa dettagliata non c'e'
 * ([MapOnline.Unavailable]: nessuna riga che la offra, nessuna richiesta), "Solo sul Wi-Fi" acceso, nessuna camera, e **non salvano niente** (nessun file, nessun dato).
 */
object NoMapSettings : MapSettings {
  override val online: StateFlow<MapOnline> = MutableStateFlow<MapOnline>(MapOnline.Unavailable).asStateFlow()

  override suspend fun enable(choice: MapOnlineChoice) = Unit

  override suspend fun disable() = Unit

  override val wifiOnly: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

  override suspend fun setWifiOnly(on: Boolean) = Unit

  override val lastCamera: StateFlow<CameraSpot?> = MutableStateFlow<CameraSpot?>(null).asStateFlow()

  override suspend fun saveCamera(spot: CameraSpot?) = Unit

  override suspend fun clear() = Unit
}
