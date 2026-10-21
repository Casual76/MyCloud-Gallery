package dev.pampa.mycloudgallery.core.family.testing

import dev.pampa.mycloudgallery.core.family.contract.RolesChange
import dev.pampa.mycloudgallery.core.family.contract.RolesSettings
import dev.pampa.mycloudgallery.core.trust.data.DeviceRole
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fase 6 (P0): i ruoli in memoria, per i test di P5, P8 e P11. Registra quante volte lo stato firmato sarebbe stato ripubblicato
 * ([republishCount]); [republishFails] simula il NAS che non risponde (il cambio resta salvato).
 */
class FakeRolesSettings(initial: Set<DeviceRole> = emptySet(), var configured: Boolean = true) : RolesSettings {
  override val roles: MutableStateFlow<Set<DeviceRole>> = MutableStateFlow(initial)
  var republishCount: Int = 0
    private set
  var republishFails: Boolean = false

  override suspend fun setRoles(indexer: Boolean, preparer: Boolean): RolesChange {
    if (!configured) return RolesChange.NotConfigured
    val wanted = buildSet {
      if (indexer) add(DeviceRole.INDEXER)
      if (preparer) add(DeviceRole.PREPARER)
    }
    if (wanted == roles.value) return RolesChange.Unchanged
    roles.value = wanted
    if (!republishFails) republishCount++
    return RolesChange.Saved(wanted, republished = !republishFails)
  }
}