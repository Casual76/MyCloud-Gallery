package dev.pampa.mycloudgallery.feature.library.utility

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.core.library.duplicates.IdenticalDuplicates
import dev.pampa.mycloudgallery.core.library.duplicates.SimilarShots
import dev.pampa.mycloudgallery.feature.library.trash.NasTrashPort

/**
 * Cio' che puo' mancare (Fase 7, P8): le porte dell'Utility e degli Eliminati di recente, dichiarate **opzionali** per Dagger, cosi' `:app` compila
 * prima che P13 le leghi (come `BackupOptionalBindingsModule` della 5A). Un solo `@Provides` per porta in `:app` la accende; senza, le pagine usano i
 * null-object e dicono che non c'e' niente (Doppioni vuoti, nessun cestino del NAS).
 *
 * Elenco (le lega P13): [IdenticalDuplicates] e [SimilarShots] (core-library, `Catalog...` sopra il catalogo), [NasTrashPort] (sopra `NasTrash` e
 * `NasOperations`), [FullHashSwitch] (sopra `FullHashPersonalSettings` di core-personal), [CleanupNasPort] (sopra `NasOperations`, `OpRequest.Trash`)
 * e [DuplicatesNames] (il nome dei familiari, facoltativo). `FullHashNightly` (core-library) lo usa il lavoro notturno di `:app`, non le schermate.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class OptionalUtilityBindingsModule {

  @BindsOptionalOf
  abstract fun identical(): IdenticalDuplicates

  @BindsOptionalOf
  abstract fun similar(): SimilarShots

  @BindsOptionalOf
  abstract fun nasTrash(): NasTrashPort

  @BindsOptionalOf
  abstract fun fullHashSwitch(): FullHashSwitch

  @BindsOptionalOf
  abstract fun cleanupNas(): CleanupNasPort

  @BindsOptionalOf
  abstract fun names(): DuplicatesNames

  @BindsOptionalOf
  abstract fun phoneCheck(): PhoneCopyCheck
}
