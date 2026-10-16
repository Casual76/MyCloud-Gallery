package dev.pampa.mycloudgallery.feature.backup.testing

import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.SourceStatus
import dev.pampa.mycloudgallery.feature.backup.contract.BackupOverview
import dev.pampa.mycloudgallery.feature.backup.contract.BackupRules
import dev.pampa.mycloudgallery.feature.backup.contract.DeviceFolderProposal
import dev.pampa.mycloudgallery.feature.backup.contract.DeviceFolderProposals
import dev.pampa.mycloudgallery.feature.backup.contract.PhoneFolder
import dev.pampa.mycloudgallery.feature.backup.contract.PhoneFolders
import kotlinx.coroutines.flow.MutableStateFlow

// Fase 5A (P5): i finti delle porte di feature-backup, per i test delle schermate e degli screenshot (package `testing`: mai nella release,
// `tools/check.ps1` lo cerca nel dex). Gli stati li sceglie il test; niente rete, niente NAS.

/** Lo stato del backup e delle sue sorgenti, deciso dal test. */
class FakeBackupOverview(
  status: BackupStatus = BackupStatus.TestOnly,
  sources: List<SourceStatus> = BackupSamples.sources,
) : BackupOverview {
  override val status: MutableStateFlow<BackupStatus> = MutableStateFlow(status)

  override val sources: MutableStateFlow<List<SourceStatus>> = MutableStateFlow(sources)
}

/** Le cartelle del telefono, con una lettura che il test puo' far fallire. */
class FakePhoneFolders(var folders: List<PhoneFolder> = BackupSamples.phoneFolders, var failing: Boolean = false) : PhoneFolders {
  var reads: Int = 0
    private set

  override suspend fun folders(): List<PhoneFolder> {
    reads++
    check(!failing) { "cartelle non leggibili" }
    return folders
  }
}

/** "Proteggi subito le foto nuove" in memoria. */
class FakeBackupRules(on: Boolean = true) : BackupRules {
  override val protectNewPhotos: MutableStateFlow<Boolean> = MutableStateFlow(on)

  override suspend fun setProtectNewPhotos(on: Boolean) {
    protectNewPhotos.value = on
  }
}

/** La proposta per la cartella del dispositivo, decisa dal test. */
class FakeDeviceFolderProposals(var proposal: DeviceFolderProposal? = BackupSamples.freshFolderProposal) : DeviceFolderProposals {
  override suspend fun propose(): DeviceFolderProposal? = proposal
}
