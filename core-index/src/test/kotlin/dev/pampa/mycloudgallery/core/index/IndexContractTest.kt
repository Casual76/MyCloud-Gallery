package dev.pampa.mycloudgallery.core.index

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.contract.BlobPut
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.HeadFetch
import dev.pampa.mycloudgallery.core.index.contract.KeepAliveResult
import dev.pampa.mycloudgallery.core.index.contract.MetaChange
import dev.pampa.mycloudgallery.core.index.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import dev.pampa.mycloudgallery.core.index.contract.SegmentFetch
import dev.pampa.mycloudgallery.core.index.contract.UnitLease
import dev.pampa.mycloudgallery.core.index.contract.UnitOutcome
import dev.pampa.mycloudgallery.core.index.contract.VideoFrame
import dev.pampa.mycloudgallery.core.index.contract.WorkItem
import dev.pampa.mycloudgallery.core.index.contract.WorkPass
import dev.pampa.mycloudgallery.core.index.contract.WorkUnitKey
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.index.contract.okOrNull
import dev.pampa.mycloudgallery.core.index.noop.ClosedWriteGate
import dev.pampa.mycloudgallery.core.index.noop.EmptyReplicaBlobs
import dev.pampa.mycloudgallery.core.index.noop.NoIndexPublisher
import dev.pampa.mycloudgallery.core.index.testing.FakeUnitPriority
import dev.pampa.mycloudgallery.core.index.testing.FakeWorkUnits
import dev.pampa.mycloudgallery.core.index.testing.InMemoryRemoteIndex
import dev.pampa.mycloudgallery.core.index.testing.MapWriteGate
import dev.pampa.mycloudgallery.core.index.testing.fakeIndexDevice
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.data.MetaFile
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentContent
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * I contratti di core-index sui fake di P0: cio' su cui P3, P4, P5, P6 e P7 lavorano in parallelo. Le implementazioni
 * vere (P3, P4) fanno passare gli stessi comportamenti.
 */
class IndexContractTest {

  private val now = 1_800_000_000_000L

  private fun file(content: ByteArray, prepared: Set<SegmentClass> = emptySet(), fileId: Long? = 1): MetaFile = MetaFile(
    stamp = NasStamp(content.size.toLong(), now, fileId),
    quickHash = QuickHashAlgorithm.ofContent(content),
    mediaId = MediaId.random(),
    versionId = VersionId.random(),
    kind = MediaKind.PHOTO,
    source = MediaSource.CAMERA,
    mime = "image/jpeg",
    prepared = SegmentClass.maskOf(prepared),
  )

  @Test
  fun laChiaveDiUnUnitaNonContieneNomiDiCartelle() {
    assertThat(WorkUnitKey("alessio", WorkPass.PREP, "2024-03", 2).resource).isEqualTo("work/1/prep/2024-03/2")
    assertThat(WorkUnitKey("alessio", WorkPass.PREP, WorkUnitKey.NO_DATE, 0).resource).isEqualTo("work/1/prep/0000-00/0")
    assertThrows(IllegalArgumentException::class.java) { WorkUnitKey("alessio", WorkPass.PREP, "2024-13", 0) }
    assertThrows(IllegalArgumentException::class.java) { WorkUnitKey("alessio", WorkPass.PREP, "Viaggi/2024", 0) }
  }

  @Test
  fun ilPubblicatoreAssegnaISeqTieneLOutboxSeBloccatoEAggiornaSubitoLaReplica() = runTest {
    val gate = MapWriteGate()
    gate.block("alessio", WriteBlock.LOCAL_ONLY)
    val (publisher, replica) = fakeIndexDevice("devicea0000000000000000000", gate = gate)
    val bytes = ByteArray(500) { 7 }
    val meta = file(bytes)
    val key = meta.contentKey
    val path = SharePath.parseMediaPath("Foto/a.jpg")

    publisher.stage("alessio", listOf(MetaChange.Put(path, meta), MetaChange.Remove(SharePath.parseMediaPath("Foto/b.jpg"))),
      listOf(BlobPut.Image(SegmentClass.THUMB_S, key, byteArrayOf(1, 2, 3))))

    assertThat(publisher.lastSeq("alessio")).isEqualTo(2)
    assertThat(publisher.pending.value.records).isEqualTo(2)
    // D31: niente testa, ma la replica locale ha gia' la miniatura e il record.
    assertThat(publisher.flush("alessio", FlushReason.UNIT_FINISHED)).isEqualTo(PublishOutcome.Blocked(WriteBlock.LOCAL_ONLY))
    assertThat(replica.locate(key.short, SegmentClass.THUMB_S)).isNotNull()
    assertThat(replica.matching("alessio", mapOf(path to meta.stamp))).containsKey(path)
    assertThat(publisher.pending.value.isEmpty).isFalse()

    gate.block("alessio", null)
    val outcome = publisher.flush("alessio", FlushReason.UNIT_FINISHED)
    assertThat(outcome).isInstanceOf(PublishOutcome.Published::class.java)
    assertThat(publisher.pending.value.isEmpty).isTrue()
    assertThat(publisher.flush("alessio", FlushReason.PERIODIC)).isEqualTo(PublishOutcome.NothingPending)
    assertThat(NoIndexPublisher.flush("alessio", FlushReason.PERIODIC)).isEqualTo(PublishOutcome.Blocked(WriteBlock.EMERGENCY_STOP))
    assertThat(ClosedWriteGate.blockFor("test")).isEqualTo(WriteBlock.EMERGENCY_STOP)
  }

  @Test
  fun unSecondoDispositivoLeggeTesteESegmentiPubblicati() = runTest {
    val remote = InMemoryRemoteIndex()
    val (publisher, _) = fakeIndexDevice("devicea0000000000000000000", remote = remote)
    val meta = file(ByteArray(300) { 1 })
    publisher.stage(
      "test-comune",
      listOf(MetaChange.Put(SharePath.parseMediaPath("a.jpg"), meta)),
      listOf(
        BlobPut.Image(SegmentClass.THUMB_S, meta.contentKey, byteArrayOf(9, 9)),
        BlobPut.Frames(meta.contentKey, listOf(VideoFrame(0, byteArrayOf(1)), VideoFrame(30_000, byteArrayOf(2)))),
      ),
    )
    publisher.flush("test-comune", FlushReason.UNIT_FINISHED)

    assertThat(remote.devices("test-comune").okOrNull()).containsExactly("devicea0000000000000000000")
    val head = (remote.latestHead("test-comune", "devicea0000000000000000000", null).okOrNull() as HeadFetch.Accepted).head
    assertThat(head.segments.map { it.cls }).containsExactly(SegmentClass.META, SegmentClass.THUMB_S, SegmentClass.FRAMES)
    val metaSegment = head.segments.single { it.cls == SegmentClass.META }
    val content = (remote.segment(head, metaSegment).okOrNull() as SegmentFetch.Ok).content as SegmentContent.Meta
    assertThat((content.records.single() as MetaRecord.Present).file).isEqualTo(meta)

    // Nessuna novita': la stessa testa dopo il suo HeadMark.
    assertThat(remote.latestHead("test-comune", "devicea0000000000000000000", head.mark).okOrNull()).isEqualTo(HeadFetch.NoChange)
    // E-I5: un HeadMark piu' alto di cio' che si trova e' un rollback; E-I17: un segmento sparito resta "mancante".
    remote.wipeShare("test-comune")
    assertThat(remote.latestHead("test-comune", "devicea0000000000000000000", head.mark).okOrNull())
      .isEqualTo(HeadFetch.Rejected(RejectReason.ROLLBACK))
    remote.dropSegment(metaSegment.ref.hash)
    assertThat(remote.segment(head, metaSegment).okOrNull()).isEqualTo(SegmentFetch.Missing)
  }

  /** D6: fra piu' record con il timbro giusto vince chi ha piu' classi preparate, poi l'id di dispositivo minore. */
  @Test
  fun laReplicaSceglieIlRecordVincenteConLaRegolaD6() = runTest {
    val (_, replica) = fakeIndexDevice("devicez0000000000000000000")
    val path = SharePath.parseMediaPath("a.jpg")
    val content = ByteArray(100)
    val poor = file(content)
    val rich = poor.copy(prepared = SegmentClass.maskOf(listOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M)))
    replica.putRecord("alessio", "deviceb0000000000000000000", MetaRecord.Present(path, 1, poor))
    replica.putRecord("alessio", "devicec0000000000000000000", MetaRecord.Present(path, 1, rich))

    assertThat(replica.matching("alessio", mapOf(path to poor.stamp)).getValue(path).file).isEqualTo(rich)
    // E-N12: timbro diverso (il PC ha cambiato il file) → nessun record valido.
    assertThat(replica.matching("alessio", mapOf(path to poor.stamp.copy(modifiedAtMillis = now + 1)))).isEmpty()
    // Una lapide piu' recente dello stesso dispositivo toglie il suo record.
    replica.putRecord("alessio", "devicec0000000000000000000", MetaRecord.Gone(path, 2))
    assertThat(replica.matching("alessio", mapOf(path to poor.stamp)).getValue(path).file).isEqualTo(poor)
    assertThat(EmptyReplicaBlobs.locate(1, SegmentClass.THUMB_S)).isNull()
    assertThat(replica.estimate(QualityLevel.HIGH).items).isEqualTo(0)
  }

  @Test
  fun leUnitaVisibiliVengonoPrimaEPoiIlMesePiuRecente() = runTest {
    val units = FakeWorkUnits()
    val visible = file(ByteArray(10) { 1 })
    fun item(meta: MetaFile) = WorkItem(meta.contentKey, SharePath.parseMediaPath("x.jpg"), meta.stamp, MediaKind.PHOTO, null, null, null, setOf(SegmentClass.THUMB_S))
    units.offer(WorkUnitKey("alessio", WorkPass.PREP, "2019-01", 0), listOf(item(visible)))
    units.offer(WorkUnitKey("alessio", WorkPass.PREP, "2024-05", 0), listOf(item(file(ByteArray(10) { 2 }))))
    units.offer(WorkUnitKey("alessio", WorkPass.PREP, "2023-05", 0), listOf(item(file(ByteArray(10) { 3 }))))

    val priority = FakeUnitPriority(setOf(visible.contentKey.short))
    val first = units.next("alessio", WorkPass.PREP, priority)!!
    assertThat(first.key.month).isEqualTo("2019-01")
    assertThat(first.lease).isInstanceOf(UnitLease.Held::class.java)
    assertThat(units.next("alessio", WorkPass.PREP, priority)!!.key.month).isEqualTo("2024-05")

    val renewed = (units.keepAlive(first) as KeepAliveResult.Renewed).claim
    assertThat((renewed.lease as UnitLease.Held).generation).isEqualTo(2)
    units.finish(renewed, UnitOutcome.Completed(prepared = 1, failed = 0))
    assertThat(units.next("alessio", WorkPass.PREP, priority)!!.key.month).isEqualTo("2023-05")
    assertThat(units.next("alessio", WorkPass.PREP, priority)).isNull()
  }

  @Test
  fun iBlobRifiutanoClassiEFotogrammiSbagliati() {
    val key = ContentKey.of(QuickHashAlgorithm.ofContent(ByteArray(1)), 1)
    assertThrows(IllegalArgumentException::class.java) { BlobPut.Image(SegmentClass.META, key, byteArrayOf(1)) }
    assertThrows(IllegalArgumentException::class.java) { BlobPut.Frames(key, emptyList()) }
    assertThrows(IllegalArgumentException::class.java) { BlobPut.Frames(key, List(10) { VideoFrame(it, byteArrayOf(1)) }) }
  }
}
