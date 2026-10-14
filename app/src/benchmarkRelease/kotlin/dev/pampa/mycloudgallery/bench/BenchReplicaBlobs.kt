package dev.pampa.mycloudgallery.bench

import dev.pampa.mycloudgallery.core.index.contract.BlobLocation
import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import dev.pampa.mycloudgallery.core.index.contract.ReplicaBlobs
import dev.pampa.mycloudgallery.core.index.contract.SpaceEstimate
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.nio.ByteBuffer

/**
 * La replica sintetica ([BenchReplicaStore]) vista come la replica vera (`ReplicaBlobs` di core-index, D15): l'adattatore
 * di docs/bench/README.md §6-bis, aggiunto con P11 quando `:app` ha cominciato a vedere `core-index`. Le miniature dei media
 * solo-NAS del dataset arrivano cosi' alla griglia tramite `NasThumbnailSource`, senza NAS.
 */
internal class BenchReplicaBlobs(private val store: BenchReplicaStore) : ReplicaBlobs {

  override fun locate(contentKey: Long, cls: SegmentClass): BlobLocation? =
    store.locate(contentKey, cls.code)?.let { BlobLocation(it.packId, it.offset, it.length, cls) }

  override fun read(location: BlobLocation): ByteBuffer =
    store.read(BenchBlobLocation(location.packId, location.offset, location.length, location.cls.code))

  override suspend fun estimate(quality: QualityLevel): SpaceEstimate = SpaceEstimate(store.packBytes, store.blobCount)
}
