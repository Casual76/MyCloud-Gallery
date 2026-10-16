package dev.pampa.mycloudgallery.backup

import dev.pampa.mycloudgallery.core.backup.contract.BackupSettings
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Senza backup (nessun NAS nella Libreria, varianti di misura): nessuna sorgente, nessuna cartella, e niente si salva. Fallisce chiuso. */
object NoBackupSettings : BackupSettings {
  override val sources: StateFlow<BackupSources> = MutableStateFlow(BackupSources.NONE)

  override val deviceFolder: StateFlow<DeviceFolder?> = MutableStateFlow<DeviceFolder?>(null)

  override suspend fun setSources(sources: BackupSources) = Unit

  override suspend fun chooseFolder(folder: DeviceFolder) = Unit
}
