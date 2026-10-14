package dev.pampa.mycloudgallery.bench

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.index.contract.ReplicaBlobs
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneLibrarySync
import dev.pampa.mycloudgallery.di.MeasurementHooks
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Il collegamento della variante di misura con l'app (docs/bench/README.md §6): la famiglia "pronta", la lettura del
 * telefono ferma e il database del dataset sintetico. Viene da un binding Hilt esplicito ([BenchHooksModule]) e **non**
 * da `ServiceLoader` (revisione di sicurezza, R6): questo file sta solo in `src/benchmarkRelease`, quindi solo
 * `benchmarkRelease` e `nonMinifiedRelease` lo hanno; la release no, e nemmeno la debug.
 *
 * **Il profilo** (P10): il database che l'app apre dipende dal profilo scelto dal collaudo ([BenchDataset.profileOf]):
 * `gallery-bench.db` per la libreria mista, `gallery-bench-nasfirst.db` per le due misure della prima pagina del NAS.
 *
 * **La replica sintetica** ([replica]) serve le miniature dei media solo-NAS: dal P11 arriva all'app come `ReplicaBlobs`
 * ([replicaBlobs], docs/bench/README.md §6-bis) e la griglia la legge con `NasThumbnailSource`.
 */
@Singleton
class BenchMeasurementHooks @Inject constructor(
  @ApplicationContext private val context: Context,
) : MeasurementHooks {

  internal val profile: BenchProfile = BenchDataset.profileOf(context)

  override val family: FamilySetupRepository = BenchFamilySetupRepository()
  override val phoneSync: PhoneLibrarySync = BenchPhoneLibrarySync()
  override val databaseName: String = profile.databaseName

  /** I blob WebP del NAS sintetico, con la stessa forma di `ReplicaBlobs`; `null` se il dataset non e' ancora stato creato. */
  internal val replica: BenchReplicaStore? by lazy { BenchReplicaStore.open(context, profile) }

  private val blobs: ReplicaBlobs? by lazy { replica?.let { BenchReplicaBlobs(it) } }

  override val replicaBlobs: ReplicaBlobs? get() = blobs
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BenchHooksModule {
  @Binds
  abstract fun measurementHooks(hooks: BenchMeasurementHooks): MeasurementHooks
}
