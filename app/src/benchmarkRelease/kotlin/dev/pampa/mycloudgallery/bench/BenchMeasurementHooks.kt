package dev.pampa.mycloudgallery.bench

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneLibrarySync
import dev.pampa.mycloudgallery.di.MeasurementHooks
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Il collegamento della variante di misura con l'app (docs/bench/README.md §6): la famiglia "pronta", la lettura del
 * telefono ferma e il database del dataset sintetico. Viene da un binding Hilt esplicito ([BenchHooksModule]) e **non**
 * da `ServiceLoader` (revisione di sicurezza, R6): questo file sta solo in `src/benchmarkRelease`, quindi solo
 * `benchmarkRelease` e `nonMinifiedRelease` lo hanno; la release no, e nemmeno la debug.
 */
@Singleton
class BenchMeasurementHooks @Inject constructor() : MeasurementHooks {
  override val family: FamilySetupRepository = BenchFamilySetupRepository()
  override val phoneSync: PhoneLibrarySync = BenchPhoneLibrarySync()
  override val databaseName: String = BenchDataset.DATABASE_NAME
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BenchHooksModule {
  @Binds
  abstract fun measurementHooks(hooks: BenchMeasurementHooks): MeasurementHooks
}
