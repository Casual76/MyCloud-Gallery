package dev.pampa.mycloudgallery.core.index.noop

import dev.pampa.mycloudgallery.core.index.contract.BlobLocation
import dev.pampa.mycloudgallery.core.index.contract.BlobPut
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.HeadFetch
import dev.pampa.mycloudgallery.core.index.contract.IndexHeadView
import dev.pampa.mycloudgallery.core.index.contract.IndexPublisher
import dev.pampa.mycloudgallery.core.index.contract.IndexSync
import dev.pampa.mycloudgallery.core.index.contract.IndexSyncStatus
import dev.pampa.mycloudgallery.core.index.contract.IndexWriteGate
import dev.pampa.mycloudgallery.core.index.contract.KeepAliveResult
import dev.pampa.mycloudgallery.core.index.contract.MetaChange
import dev.pampa.mycloudgallery.core.index.contract.OwnIndexSink
import dev.pampa.mycloudgallery.core.index.contract.PendingCounts
import dev.pampa.mycloudgallery.core.index.contract.ProvisionalThumbnails
import dev.pampa.mycloudgallery.core.index.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.RemoteIndex
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.index.contract.ReplicaBlobs
import dev.pampa.mycloudgallery.core.index.contract.ReplicaQuality
import dev.pampa.mycloudgallery.core.index.contract.ReplicaQualityState
import dev.pampa.mycloudgallery.core.index.contract.ReplicaRecords
import dev.pampa.mycloudgallery.core.index.contract.SegmentFetch
import dev.pampa.mycloudgallery.core.index.contract.SpaceEstimate
import dev.pampa.mycloudgallery.core.index.contract.SyncReport
import dev.pampa.mycloudgallery.core.index.contract.SyncScope
import dev.pampa.mycloudgallery.core.index.contract.UnitClaim
import dev.pampa.mycloudgallery.core.index.contract.UnitOutcome
import dev.pampa.mycloudgallery.core.index.contract.UnitPriority
import dev.pampa.mycloudgallery.core.index.contract.WorkPass
import dev.pampa.mycloudgallery.core.index.contract.WorkUnits
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// I null-object di core-index (release-safe, fuori da `testing`): nessun indice, nessuna scrittura sul NAS, nessun
// dato remoto. Falliscono CHIUSI: niente si pubblica, niente si scarica, niente si prenota.

/** Nessuna scrittura nell'indice: tutto e' bloccato come in un'emergenza. */
object ClosedWriteGate : IndexWriteGate {
  override suspend fun blockFor(share: String): WriteBlock = WriteBlock.EMERGENCY_STOP
}

/** Non pubblica niente e non tiene niente: l'outbox e' sempre vuota. */
object NoIndexPublisher : IndexPublisher {
  override suspend fun stage(share: String, changes: List<MetaChange>, blobs: List<BlobPut>) = Unit

  override suspend fun flush(share: String, reason: FlushReason): PublishOutcome = PublishOutcome.Blocked(WriteBlock.EMERGENCY_STOP)

  override val pending: StateFlow<PendingCounts> = MutableStateFlow(PendingCounts.EMPTY)
}

object NoOwnIndexSink : OwnIndexSink {
  override suspend fun applyOwn(share: String, records: List<MetaRecord>, blobs: List<BlobPut>) = Unit
}

/** Nessuna unita' di lavoro. */
object NoWorkUnits : WorkUnits {
  override suspend fun next(share: String, pass: WorkPass, priority: UnitPriority): UnitClaim? = null

  override suspend fun keepAlive(claim: UnitClaim): KeepAliveResult = KeepAliveResult.Lost

  override suspend fun finish(claim: UnitClaim, outcome: UnitOutcome) = Unit
}

/** Nessun indice remoto raggiungibile. */
object NoRemoteIndex : RemoteIndex {
  override suspend fun devices(share: String): RemoteResult<List<String>> = RemoteResult.Failed(RemoteFailure.NOT_ALLOWED)

  override suspend fun latestHead(share: String, deviceId: String, previous: HeadMark?): RemoteResult<HeadFetch> =
    RemoteResult.Failed(RemoteFailure.NOT_ALLOWED)

  override suspend fun segment(head: IndexHeadView, segment: ClassedSegment): RemoteResult<SegmentFetch> =
    RemoteResult.Failed(RemoteFailure.NOT_ALLOWED)
}

/** Nessuna sincronizzazione: lo stato resta "fermo" e ogni giro e' vuoto. */
object NoIndexSync : IndexSync {
  override val status: StateFlow<IndexSyncStatus> = MutableStateFlow(IndexSyncStatus.Idle)

  override suspend fun sync(scope: SyncScope, budget: Duration): SyncReport = SyncReport.EMPTY
}

/** Una replica vuota: nessun blob, nessuna stima. */
object EmptyReplicaBlobs : ReplicaBlobs {
  override fun locate(contentKey: Long, cls: SegmentClass): BlobLocation? = null

  override fun read(location: BlobLocation): ByteBuffer = throw IOException("replica vuota")

  override suspend fun estimate(quality: QualityLevel): SpaceEstimate = SpaceEstimate(0, 0)
}

/** La qualita' di default, immobile. */
object FixedReplicaQuality : ReplicaQuality {
  override val state: StateFlow<ReplicaQualityState> =
    MutableStateFlow(ReplicaQualityState(QualityLevel.DEFAULT, emptyMap(), currentBytes = 0, converging = false))

  override suspend fun set(level: QualityLevel) = Unit
}

/** Nessun record: la scansione rileva tutto da se'. */
object NoReplicaRecords : ReplicaRecords {
  override suspend fun matching(share: String, stamps: Map<SharePath, NasStamp>): Map<SharePath, MetaRecord.Present> = emptyMap()
}

/** Nessuna miniatura provvisoria: la griglia mostra il segnaposto. */
object NoProvisionalThumbnails : ProvisionalThumbnails {
  override suspend fun put(contentKey: ContentKey, jpeg: ByteArray) = Unit

  override fun get(contentKey: Long): ByteArray? = null

  override suspend fun forget(contentKey: Long) = Unit
}
