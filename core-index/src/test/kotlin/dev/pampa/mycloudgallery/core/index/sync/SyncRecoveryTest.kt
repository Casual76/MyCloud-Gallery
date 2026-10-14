package dev.pampa.mycloudgallery.core.index.sync

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.contract.HeadFetch
import dev.pampa.mycloudgallery.core.index.contract.IndexHeadView
import dev.pampa.mycloudgallery.core.index.contract.RemoteIndex
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.index.contract.SegmentFetch
import dev.pampa.mycloudgallery.core.index.contract.SyncScope
import dev.pampa.mycloudgallery.core.index.replica.LocalReplica
import dev.pampa.mycloudgallery.core.index.replica.pack.PackDirectory
import dev.pampa.mycloudgallery.core.index.replica.store.BlobRow
import dev.pampa.mycloudgallery.core.index.replica.store.HeadApplication
import dev.pampa.mycloudgallery.core.index.replica.store.MemoryReplicaCatalog
import dev.pampa.mycloudgallery.core.index.replica.store.MemoryReplicaStore
import dev.pampa.mycloudgallery.core.index.replica.store.PackRow
import dev.pampa.mycloudgallery.core.index.replica.store.RecordRow
import dev.pampa.mycloudgallery.core.index.replica.store.ReplicaStore
import dev.pampa.mycloudgallery.core.index.replica.store.SegmentState
import dev.pampa.mycloudgallery.core.index.replica.store.StoreChange
import dev.pampa.mycloudgallery.core.index.testing.InMemoryRemoteIndex
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass.THUMB_M
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass.THUMB_S
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import java.io.File
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * §4.3, "ripresa dopo un guasto a ogni chiamata": la morte del processo prima di ogni richiesta al NAS e prima o dopo ogni
 * scrittura della replica, poi un riavvio (replica riaperta dal database, pacchi dal disco): il giro dopo arriva allo
 * stesso stato di un giro mai interrotto (record, blob, identita' nel catalogo).
 */
class SyncRecoveryTest {

  @get:Rule val tmp = TemporaryFolder()

  private class Crash : RuntimeException("morte del processo simulata")

  /** Un NAS che "muore" (eccezione) prima della chiamata numero [crashAt]. */
  private class CrashingRemote(private val inner: RemoteIndex, private val crashAt: Int) : RemoteIndex {
    var calls = 0

    private fun tick() {
      if (++calls == crashAt) throw Crash()
    }

    override suspend fun devices(share: String): RemoteResult<List<String>> = tick().let { inner.devices(share) }

    override suspend fun latestHead(share: String, deviceId: String, previous: HeadMark?): RemoteResult<HeadFetch> =
      tick().let { inner.latestHead(share, deviceId, previous) }

    override suspend fun segment(head: IndexHeadView, segment: ClassedSegment): RemoteResult<SegmentFetch> =
      tick().let { inner.segment(head, segment) }
  }

  /** Un database che "muore" alla scrittura numero [crashAt], prima ([after] falso) o dopo il commit. */
  private class CrashingStore(private val inner: ReplicaStore, private val crashAt: Int, private val after: Boolean) : ReplicaStore by inner {
    var writes = 0

    private suspend fun <T> write(block: suspend () -> T): T {
      val n = ++writes
      if (n == crashAt && !after) throw Crash()
      val result = block()
      if (n == crashAt) throw Crash()
      return result
    }

    override suspend fun applyHead(application: HeadApplication): StoreChange = write { inner.applyHead(application) }

    override suspend fun setSegmentState(share: String, deviceId: String, hash: String, state: SegmentState) =
      write { inner.setSegmentState(share, deviceId, hash, state) }

    override suspend fun applyBlobSegment(pack: PackRow, blobs: List<BlobRow>): Long? = write { inner.applyBlobSegment(pack, blobs) }

    override suspend fun forgetDevice(share: String, deviceId: String): StoreChange = write { inner.forgetDevice(share, deviceId) }

    override suspend fun forgetShare(share: String): StoreChange = write { inner.forgetShare(share) }

    override suspend fun putLocalRecords(share: String, rows: List<RecordRow>): Set<String> = write { inner.putLocalRecords(share, rows) }

    override suspend fun clearDirty(share: String, pathKeys: Collection<String>) = write { inner.clearDirty(share, pathKeys) }

    override suspend fun releasePacksOutside(keep: Set<dev.pampa.mycloudgallery.core.trust.data.SegmentClass>, maxBytes: Long): StoreChange =
      write { inner.releasePacksOutside(keep, maxBytes) }
  }

  private val nas = InMemoryRemoteIndex(nasNow = { Fx.NOW })
  private val scope = SyncScope(setOf("alessio", "test-comune"), setOf(THUMB_S, THUMB_M))

  private fun publishScenario() {
    for ((device, from) in listOf("a" to 1, "c" to 4)) {
      val range = from until from + 5
      val records = range.map { Fx.put("Foto/$it.jpg", it.toLong(), Fx.file(it, media = 100L + it, prepared = setOf(THUMB_S, THUMB_M))) }
      nas.publish(
        "alessio",
        device,
        listOf(
          Fx.meta(*records.toTypedArray()),
          Fx.images(THUMB_S, *range.map { Fx.key(it) to Fx.pixels(THUMB_S, it, device) }.toTypedArray()),
          Fx.images(THUMB_M, *range.map { Fx.key(it) to Fx.pixels(THUMB_M, it, device) }.toTypedArray()),
        ),
      )
    }
    nas.publish("alessio", "a", listOf(Fx.meta(Fx.gone("Foto/2.jpg", 50), Fx.put("Foto/4.jpg", 51, Fx.file(4, media = 3)))))
    nas.publish("test-comune", "c", listOf(Fx.meta(Fx.put("Comune/1.jpg", 1, Fx.file(70)))))
  }

  private fun engine(remote: RemoteIndex, store: ReplicaStore, replica: LocalReplica) =
    IndexSyncEngine(remote, replica, store, { null }, { DeviceStanding.APPROVED }, NasClock { Fx.NOW })

  @Test
  fun dopoUnaMorteAOgniChiamataEOgniScritturaIlGiroDopoArrivaAlloStessoStato() = runTest {
    publishScenario()
    val reference = Harness("b", tmp.newFolder("riferimento"), nas)
    reference.sync("alessio", "test-comune", classes = scope.classes)
    val expected = reference.digest("alessio", "test-comune")
    val expectedIds = reference.catalog.identities()
    assertThat(expected).isNotEmpty()

    var remoteCalls = 0
    var storeWrites = 0
    run {
      val store = MemoryReplicaStore()
      val remote = CrashingRemote(nas, Int.MAX_VALUE)
      val counting = CrashingStore(store, Int.MAX_VALUE, after = false)
      val replica = LocalReplica(counting, PackDirectory(tmp.newFolder("conta")), MemoryReplicaCatalog(), "b")
      engine(remote, counting, replica).sync(scope, Duration.INFINITE)
      remoteCalls = remote.calls
      storeWrites = counting.writes
    }
    assertThat(remoteCalls).isAtLeast(9)
    assertThat(storeWrites).isAtLeast(6)

    var cases = 0
    suspend fun check(label: String, remoteCrashAt: Int, storeCrashAt: Int, after: Boolean) {
      val dir = tmp.newFolder("caso-${cases++}")
      val store = MemoryReplicaStore()
      val catalog = MemoryReplicaCatalog()
      val crashing = CrashingStore(store, storeCrashAt, after)
      val first = LocalReplica(crashing, PackDirectory(File(dir, "packs")), catalog, "b")
      val outcome = runCatching { engine(CrashingRemote(nas, remoteCrashAt), crashing, first).sync(scope, Duration.INFINITE) }
      assertThat(outcome.exceptionOrNull()).isInstanceOf(Crash::class.java)
      // Riavvio: la replica si riapre dal database e dai pacchi su disco.
      val restarted = LocalReplica(store, PackDirectory(File(dir, "packs")), catalog, "b")
      val report = engine(nas, store, restarted).sync(scope, Duration.INFINITE)
      assertThat(report.complete).isTrue()
      assertWithMessage(label, replicaDigest(store, restarted, "alessio", "test-comune"), expected)
      assertWithMessage(label, catalog.identities(), expectedIds)
      assertThat(store.dirtyShares()).isEmpty()
    }

    for (k in 1..remoteCalls) check("NAS $k", k, Int.MAX_VALUE, after = false)
    for (k in 1..storeWrites) {
      check("database prima $k", Int.MAX_VALUE, k, after = false)
      check("database dopo $k", Int.MAX_VALUE, k, after = true)
    }
    assertThat(cases).isEqualTo(remoteCalls + 2 * storeWrites)
    println("P4: ripresa provata dopo $remoteCalls morti alle richieste al NAS e ${2 * storeWrites} alle scritture (prima e dopo)")
  }

  private fun assertWithMessage(label: String, actual: Any, expected: Any) {
    com.google.common.truth.Truth.assertWithMessage(label).that(actual).isEqualTo(expected)
  }
}
