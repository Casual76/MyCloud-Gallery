package dev.pampa.mycloudgallery.feature.backup.di

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.core.backup.contract.BackupSettings
import dev.pampa.mycloudgallery.feature.backup.contract.BackupActions
import dev.pampa.mycloudgallery.feature.backup.contract.BackupCoverPorts
import dev.pampa.mycloudgallery.feature.backup.contract.BackupOverview
import dev.pampa.mycloudgallery.feature.backup.contract.BackupRules
import dev.pampa.mycloudgallery.feature.backup.contract.DeviceFolderProposals
import dev.pampa.mycloudgallery.feature.backup.contract.PhoneFolders

/**
 * Cio' che puo' mancare (Fase 5A, P5): le porte delle schermate del backup, dichiarate **opzionali** per Dagger, cosi' `:app` compila
 * prima che P7 le leghi (come `SettingsOptionalBindingsModule`). Un solo `@Provides` per porta in `:app` la accende; senza, i ViewModel
 * usano i null-object di `noop` e le schermate dicono che il backup non e' ancora pronto.
 *
 * Elenco (le lega P7): [BackupOverview] (stato e sorgenti), [BackupActions] (navigazione, permessi, "Fai ora", "Ferma"),
 * [BackupSettings] (sorgenti e cartella del dispositivo, di core-backup), [PhoneFolders] (le cartelle del telefono), [BackupRules]
 * ("Proteggi subito le foto nuove", D34) e [DeviceFolderProposals] (la cartella del dispositivo, D7).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackupOptionalBindingsModule {

  @BindsOptionalOf
  abstract fun overview(): BackupOverview

  @BindsOptionalOf
  abstract fun actions(): BackupActions

  @BindsOptionalOf
  abstract fun settings(): BackupSettings

  @BindsOptionalOf
  abstract fun phoneFolders(): PhoneFolders

  @BindsOptionalOf
  abstract fun rules(): BackupRules

  @BindsOptionalOf
  abstract fun folderProposals(): DeviceFolderProposals

  /** I3 (fase I): le copertine delle sorgenti (le ultime foto, la Libreria visibile, la cache delle miniature). */
  @BindsOptionalOf
  abstract fun coverPorts(): BackupCoverPorts
}
