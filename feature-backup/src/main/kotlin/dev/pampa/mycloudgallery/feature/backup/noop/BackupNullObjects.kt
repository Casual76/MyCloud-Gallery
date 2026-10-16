package dev.pampa.mycloudgallery.feature.backup.noop

import dev.pampa.mycloudgallery.core.backup.contract.BackupSetupStep
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.SourceStatus
import dev.pampa.mycloudgallery.feature.backup.contract.BackupOverview
import dev.pampa.mycloudgallery.feature.backup.contract.BackupRules
import dev.pampa.mycloudgallery.feature.backup.contract.DeviceFolderProposal
import dev.pampa.mycloudgallery.feature.backup.contract.DeviceFolderProposals
import dev.pampa.mycloudgallery.feature.backup.contract.PhoneFolder
import dev.pampa.mycloudgallery.feature.backup.contract.PhoneFolders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Fase 5A (P5): i null-object delle porte di feature-backup, release-safe. Quello che `:app` lega finche' P7 non porta il backup vero:
// falliscono chiusi, le schermate dicono "il backup non e' ancora pronto" e nessun tocco scrive niente.

/** Nessun backup: lo stato dice "non configurato" (manca il NAS) e non ci sono sorgenti. */
object NoBackupOverview : BackupOverview {
  override val status: StateFlow<BackupStatus> =
    MutableStateFlow<BackupStatus>(BackupStatus.NotConfigured(setOf(BackupSetupStep.NAS))).asStateFlow()

  override val sources: StateFlow<List<SourceStatus>> = MutableStateFlow<List<SourceStatus>>(emptyList()).asStateFlow()
}

/** Nessuna cartella del telefono da offrire. */
object NoPhoneFolders : PhoneFolders {
  override suspend fun folders(): List<PhoneFolder> = emptyList()
}

/** Nessuna regola da toccare: la protezione delle foto nuove resta spenta e non si salva. */
object NoBackupRules : BackupRules {
  override val protectNewPhotos: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

  override suspend fun setProtectNewPhotos(on: Boolean) = Unit
}

/** Nessuna proposta: la cartella si sceglie quando il primo backup e' pronto. */
object NoDeviceFolderProposals : DeviceFolderProposals {
  override suspend fun propose(): DeviceFolderProposal? = null
}
