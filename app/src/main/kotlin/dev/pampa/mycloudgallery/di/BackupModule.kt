package dev.pampa.mycloudgallery.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.backup.NoBackupSettings
import dev.pampa.mycloudgallery.core.backup.contract.BackupSettings
import dev.pampa.mycloudgallery.core.backup.contract.BackupSetupStep
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.feature.backup.contract.BackupActions
import dev.pampa.mycloudgallery.feature.backup.contract.BackupCoverPorts
import dev.pampa.mycloudgallery.feature.backup.contract.SourceNewestMedia
import dev.pampa.mycloudgallery.feature.backup.contract.BackupOverview
import dev.pampa.mycloudgallery.feature.backup.contract.BackupRules
import dev.pampa.mycloudgallery.feature.backup.contract.DeviceFolderProposals
import dev.pampa.mycloudgallery.feature.backup.contract.PhoneFolders
import dev.pampa.mycloudgallery.feature.backup.noop.NoBackupActions
import dev.pampa.mycloudgallery.feature.backup.noop.NoBackupOverview
import dev.pampa.mycloudgallery.feature.backup.noop.NoBackupRules
import dev.pampa.mycloudgallery.feature.backup.noop.NoDeviceFolderProposals
import dev.pampa.mycloudgallery.feature.backup.noop.NoPhoneFolders
import dev.pampa.mycloudgallery.feature.library.collections.CollectionItemsSource
import dev.pampa.mycloudgallery.feature.settings.backup.BackupEntryStatus
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Le porte del backup (Fase 5A, P7) legate alle implementazioni vere, tutte dentro un solo [dev.pampa.mycloudgallery.backup.BackupGraph]
 * per processo (docs/fase5-progetto.md §1.4). Le dichiarano come opzionali `feature-backup` e `feature-settings`: un `@Provides` per porta
 * le accende. Senza backup (nessun NAS nella Libreria, varianti di misura) valgono i null-object, che falliscono chiusi: le schermate
 * dicono "il backup non e' pronto" e nessun tocco scrive niente.
 */
@Module
@InstallIn(SingletonComponent::class)
object BackupModule {

  @Provides fun backupOverview(gallery: GalleryGraph): BackupOverview = gallery.backupUi?.overview ?: NoBackupOverview

  @Provides fun backupEntryStatus(gallery: GalleryGraph): BackupEntryStatus = gallery.backupUi?.overview ?: NoBackupEntry

  @Provides fun backupActions(gallery: GalleryGraph): BackupActions = gallery.backupUi?.actions ?: NoBackupActions

  @Provides fun backupSettings(gallery: GalleryGraph): BackupSettings = gallery.backup?.settings ?: NoBackupSettings

  @Provides fun phoneFolders(gallery: GalleryGraph): PhoneFolders = gallery.backupUi?.phoneFolders ?: NoPhoneFolders

  @Provides fun backupRules(gallery: GalleryGraph): BackupRules = gallery.backupUi?.rules ?: NoBackupRules

  @Provides fun folderProposals(gallery: GalleryGraph): DeviceFolderProposals = gallery.backupUi?.proposals ?: NoDeviceFolderProposals

  /**
   * I3 (fase I): le copertine delle sorgenti nella pagina Backup e nelle sue impostazioni. Le stesse porte delle Raccolte: la Libreria visibile (mai un
   * nascosto) e la cache delle miniature della griglia; le ultime foto di ogni cartella dal catalogo. Senza backup, nessuna foto (la piastrella).
   */
  @Provides fun backupCoverPorts(gallery: GalleryGraph, items: CollectionItemsSource, loader: ThumbnailLoader): BackupCoverPorts =
    BackupCoverPorts(newest = gallery.backupUi?.newestMedia ?: NoNewestMedia, library = items.items(LibraryFilter.ALL), loader = loader)
}

/** Senza backup: nessuna foto per le copertine. */
private val NoNewestMedia = SourceNewestMedia { _, _ -> emptyMap() }

/** Senza backup: la voce delle Impostazioni dice "non configurato" (e non compare, perche' `openBackup` e' null). */
private object NoBackupEntry : BackupEntryStatus {
  override val status: StateFlow<BackupStatus> = MutableStateFlow<BackupStatus>(BackupStatus.NotConfigured(setOf(BackupSetupStep.NAS)))
}
