package dev.pampa.mycloudgallery.feature.backup.noop

import dev.pampa.mycloudgallery.core.backup.contract.BackupAction
import dev.pampa.mycloudgallery.feature.backup.contract.BackupActions
import dev.pampa.mycloudgallery.feature.backup.contract.BackupScreen

/** Fase 5A, null-object delle azioni del backup: finche' `:app` non le lega (P7) un tocco non fa niente (mai un'azione per sbaglio). */
object NoBackupActions : BackupActions {
  override fun perform(action: BackupAction) = Unit

  override fun backupNow() = Unit

  override fun stop() = Unit

  override fun startFirstBackup(tonight: Boolean) = Unit

  override fun open(screen: BackupScreen) = Unit
}
