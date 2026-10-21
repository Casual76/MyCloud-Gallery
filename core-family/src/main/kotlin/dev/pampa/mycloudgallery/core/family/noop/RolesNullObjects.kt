package dev.pampa.mycloudgallery.core.family.noop

import dev.pampa.mycloudgallery.core.family.contract.RolesChange
import dev.pampa.mycloudgallery.core.family.contract.RolesSettings
import dev.pampa.mycloudgallery.core.trust.data.DeviceRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Fase 6 (P0): nessun ruolo e nessun cambio (dispositivo non configurato). Fallisce chiuso: senza ruoli niente calcola. */
object NoRolesSettings : RolesSettings {
  override val roles: StateFlow<Set<DeviceRole>> = MutableStateFlow(emptySet())

  override suspend fun setRoles(indexer: Boolean, preparer: Boolean): RolesChange = RolesChange.NotConfigured
}