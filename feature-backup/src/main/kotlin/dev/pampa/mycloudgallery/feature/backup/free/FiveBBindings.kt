package dev.pampa.mycloudgallery.feature.backup.free

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.Corrections
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpace
import dev.pampa.mycloudgallery.core.backup.contract.PendingTasks
import dev.pampa.mycloudgallery.core.backup.contract.VersionKeeper
import dev.pampa.mycloudgallery.feature.backup.tasks.PendingTaskActions
import dev.pampa.mycloudgallery.feature.backup.versions.CorrectionPlaces
import dev.pampa.mycloudgallery.feature.backup.versions.VersionActions

/**
 * Cio' che puo' mancare (Fase 5B, P12): i servizi della seconda meta' del backup e le porte delle schermate, dichiarati **opzionali** per
 * Dagger, cosi' `:app` compila prima che P14 li leghi (come `BackupOptionalBindingsModule` della 5A). Un solo `@Provides` per porta in `:app`
 * la accende; senza, i ViewModel dicono che la funzione non e' ancora pronta e un tocco non fa niente (fallisce chiuso).
 *
 * I servizi sono i contratti di core-backup: [FreeSpace] ("Libera spazio"), [VersionKeeper] (versioni e "Ripristina originale"), [Corrections]
 * (data, luogo, rotazione), [PendingTasks] ("Da decidere"), [BackupClock] (l'orologio monotono con cui si datano i tocchi di conferma, lo
 * stesso con cui i servizi li controllano). Le porte sono di questo modulo: [PhoneSystemDialogs], [PhoneOnlyAssessor], [PendingTaskActions],
 * [VersionActions], [CorrectionPlaces]. `BackupActions` e' gia' dichiarata da `BackupOptionalBindingsModule`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FiveBOptionalBindingsModule {

  @BindsOptionalOf
  abstract fun freeSpace(): FreeSpace

  @BindsOptionalOf
  abstract fun versionKeeper(): VersionKeeper

  @BindsOptionalOf
  abstract fun corrections(): Corrections

  @BindsOptionalOf
  abstract fun pendingTasks(): PendingTasks

  @BindsOptionalOf
  abstract fun backupClock(): BackupClock

  @BindsOptionalOf
  abstract fun phoneSystemDialogs(): PhoneSystemDialogs

  @BindsOptionalOf
  abstract fun phoneOnlyAssessor(): PhoneOnlyAssessor

  /** Correzioni 5B (LS-2): il giro di "Libera spazio" fuori dalla pagina (lavoro avviato dall'utente, notifica "Pronto", ripresa). */
  @BindsOptionalOf
  abstract fun freeSpaceRuns(): FreeSpaceRuns

  @BindsOptionalOf
  abstract fun pendingTaskActions(): PendingTaskActions

  @BindsOptionalOf
  abstract fun versionActions(): VersionActions

  @BindsOptionalOf
  abstract fun correctionPlaces(): CorrectionPlaces
}
