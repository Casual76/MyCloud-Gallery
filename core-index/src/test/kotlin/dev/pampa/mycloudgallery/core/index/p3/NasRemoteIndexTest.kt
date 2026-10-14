package dev.pampa.mycloudgallery.core.index.p3

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.HeadFetch
import dev.pampa.mycloudgallery.core.index.contract.IndexHeadView
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.index.contract.SegmentFetch
import dev.pampa.mycloudgallery.core.index.publish.NasRemoteIndex
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.DeviceHead
import dev.pampa.mycloudgallery.core.trust.data.HeadKind
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentContent
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Il `RemoteIndex` vero: dispositivi dal registro, teste per nome, segmenti col codec stretto, errori del NAS. */
class NasRemoteIndexTest {

  private val share = "test-comune"
  private val world = TestWorld()
  private val phone = world.device("Telefono")
  private val files = (1..3).map { world.file(share, "Foto/IMG_$it.jpg") }

  private suspend fun publishSome() {
    val publisher = phone.publisher()
    publisher.stage(share, files.map { it.put(setOf(SegmentClass.THUMB_S)) }, files.map { it.thumb() })
    publisher.flush(share, FlushReason.UNIT_FINISHED)
  }

  private fun ok(result: RemoteResult<*>): Any? = (result as RemoteResult.Ok<*>).value

  /** Un dispositivo approvato ma compromesso firma una testa che elenca un segmento scritto a mano. */
  private fun hostileHead(segment: ByteArray, cls: SegmentClass, declared: SegmentRef = SegmentRef(Hashes.sha256Name(segment), segment.size.toLong())) {
    world.nas.share(share).put(NasLayout.segmentFile(phone.id, "index", declared.hash), segment)
    val head = DeviceHead(
      world.trust.familyId, phone.id, share, HeadKind.INDEX, 1, world.trust.state.registry.version, world.clock.nowMillis(),
      listOf(ClassedSegment(cls, declared)),
    ).sign(phone.signer)
    world.nas.share(share).put(NasLayout.headFile(phone.id, "index", 1), head)
  }

  private suspend fun onlySegment(remote: NasRemoteIndex = world.reader()): Pair<IndexHeadView, SegmentFetch> {
    val head = (ok(remote.latestHead(share, phone.id, null)) as HeadFetch.Accepted).head
    return head to ok(remote.segment(head, head.segments.single())) as SegmentFetch
  }

  @Test
  fun dispositiviTesteESegmenti() = runTest {
    val remote = world.reader()
    assertThat(ok(remote.latestHead(share, phone.id, null))).isEqualTo(HeadFetch.None)
    publishSome()

    assertThat(ok(remote.devices(share)) as List<*>).contains(phone.id)
    val head = (ok(remote.latestHead(share, phone.id, null)) as HeadFetch.Accepted).head
    assertThat(head.segments.map { it.cls }).containsExactly(SegmentClass.META, SegmentClass.THUMB_S)
    assertThat(ok(remote.latestHead(share, phone.id, head.mark))).isEqualTo(HeadFetch.NoChange)
    val meta = head.segments.single { it.cls == SegmentClass.META }
    val content = (ok(remote.segment(head, meta)) as SegmentFetch.Ok).content as SegmentContent.Meta
    assertThat(content.records.map { it.path }).containsExactlyElementsIn(files.map { it.path })
  }

  @Test
  fun segmentiOstiliDiUnDispositivoApprovato_rifiutati() = runTest {
    hostileHead("MCGB".toByteArray() + byteArrayOf(1, 1, 0, 0, 0, 0, 0, 0x10, 0), SegmentClass.META)
    assertThat(onlySegment().second).isEqualTo(SegmentFetch.Rejected(RejectReason.MALFORMED))
  }

  @Test
  fun classeDiversaDaQuellaDellaTesta_rifiutata() = runTest {
    val thumbs = dev.pampa.mycloudgallery.core.trust.data.IndexSegments.blobs(SegmentClass.THUMB_S, listOf(files[0].contentKey to Images.webp()))
    hostileHead(thumbs.bytes, SegmentClass.HQ)
    assertThat(onlySegment().second).isEqualTo(SegmentFetch.Rejected(RejectReason.WRONG_TYPE))
  }

  @Test
  fun segmentoElencatoMaAssente_oPiuGrandeDelDichiarato() = runTest {
    val absent = SegmentRef(Hashes.sha256Name("mai scritto".toByteArray()), 100)
    val head = DeviceHead(world.trust.familyId, phone.id, share, HeadKind.INDEX, 1, world.trust.state.registry.version, world.clock.nowMillis(), listOf(ClassedSegment(SegmentClass.META, absent)))
      .sign(phone.signer)
    world.nas.share(share).put(NasLayout.headFile(phone.id, "index", 1), head)
    assertThat(onlySegment().second).isEqualTo(SegmentFetch.Missing)

    world.nas.share(share).put(NasLayout.segmentFile(phone.id, "index", absent.hash), ByteArray(5000))
    assertThat(onlySegment().second).isEqualTo(SegmentFetch.Rejected(RejectReason.HASH_MISMATCH))
  }

  @Test
  fun testeDiRevocatiOSconosciuti_eNessunRegistro() = runTest {
    publishSome()
    val stranger = TrustIds.random()
    assertThat(ok(world.reader().latestHead(share, stranger, null))).isEqualTo(HeadFetch.None)
    world.trust.changeStatus(phone.signer, DeviceStatus.REVOKED)

    assertThat(ok(world.reader().latestHead(share, phone.id, null))).isEqualTo(HeadFetch.Rejected(RejectReason.SIGNER_REVOKED))
    assertThat(ok(world.reader().devices(share)) as List<*>).doesNotContain(phone.id)
    val noRegistry = NasRemoteIndex({ world.nas.share(it) }, { null }, "test", Dispatchers.Unconfined)
    assertThat(noRegistry.devices(share)).isEqualTo(RemoteResult.Failed(RemoteFailure.NOT_ALLOWED))
  }

  @Test
  fun erroriDelNas_comeRemoteFailure() = runTest {
    publishSome()
    val plan = FaultPlan()
    val remote = NasRemoteIndex({ FaultyFileSystem(world.nas.share(it), plan) }, { world.trust.state }, "test", Dispatchers.Unconfined)
    val errors = mapOf(
      NasError.Timeout to RemoteFailure.UNREACHABLE,
      NasError.PermissionDenied to RemoteFailure.ACCESS_DENIED,
      NasError.ShareMissing(share) to RemoteFailure.SHARE_MISSING,
      NasError.Auth(dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason.AUTH_FAILED) to RemoteFailure.NOT_ALLOWED,
    )
    for ((error, failure) in errors) {
      plan.failWhen = { _, _ -> error }
      assertThat(remote.latestHead(share, phone.id, null)).isEqualTo(RemoteResult.Failed(failure))
      assertThat(remote.devices(share)).isEqualTo(RemoteResult.Failed(failure))
    }
  }

  @Test
  fun budgetDelleLetture_finito() = runTest {
    publishSome()
    val remote = world.reader()
    repeat(1024) { remote.latestHead(share, TrustIds.random(), null) }
    assertThat(remote.latestHead(share, phone.id, null)).isEqualTo(RemoteResult.Failed(RemoteFailure.OTHER))
    remote.startRound()
    assertThat(ok(remote.latestHead(share, phone.id, null))).isInstanceOf(HeadFetch.Accepted::class.java)
  }
}
