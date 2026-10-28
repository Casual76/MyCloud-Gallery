package dev.pampa.mycloudgallery.bench

import java.nio.ByteBuffer
import dev.pampa.mycloudgallery.core.index.contract.SpaceEstimate
import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.index.contract.BlobLocation
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

  /**
   * Avvio istantaneo (28/10): la replica si apre **al primo uso** (la prima miniatura solo-NAS, su un thread di lavoro), non quando la
   * galleria si costruisce. Aprirla legge l'indice sintetico (132 mila voci): sull'S25 erano 60 ms sul thread principale, dentro
   * `Application.onCreate`, che la release non ha (lavoro solo della variante di misura, che gonfiava l'avvio misurato). Se il dataset
   * non c'e' (nessun indice sul disco) resta null, come prima.
   */
  private val lazyBlobs: ReplicaBlobs by lazy { LazyReplicaBlobs { blobs } }

  override val replicaBlobs: ReplicaBlobs?
    get() = if (BenchReplicaStore.indexFile(context, profile).exists()) lazyBlobs else null

  init {
    // La replica comincia ad aprirsi subito, su un thread suo: la prima miniatura solo-NAS la trova di solito gia' aperta.
    Thread({ runCatching { blobs } }, "bench-replica").apply { isDaemon = true }.start()
  }
}

/** Una replica che si apre alla prima chiamata (vedi [BenchMeasurementHooks.replicaBlobs]); se non si apre, nessun blob. */
private class LazyReplicaBlobs(open: () -> ReplicaBlobs?) : ReplicaBlobs {
  private val target: ReplicaBlobs? by lazy(open)

  override fun locate(contentKey: Long, cls: SegmentClass): BlobLocation? = target?.locate(contentKey, cls)

  override fun read(location: BlobLocation): ByteBuffer = target?.read(location) ?: throw java.io.IOException("replica sintetica non aperta")

  override suspend fun estimate(quality: QualityLevel): SpaceEstimate = target?.estimate(quality) ?: SpaceEstimate(0L, 0)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BenchHooksModule {
  @Binds
  abstract fun measurementHooks(hooks: BenchMeasurementHooks): MeasurementHooks
}
