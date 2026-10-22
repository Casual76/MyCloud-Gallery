package dev.pampa.mycloudgallery.ai

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.index.ai.AiAppliedMark
import dev.pampa.mycloudgallery.core.index.ai.AiHeadApplication
import dev.pampa.mycloudgallery.core.index.ai.AiKey
import dev.pampa.mycloudgallery.core.index.ai.AiLiveEntry
import dev.pampa.mycloudgallery.core.index.ai.AiPublishCommit
import dev.pampa.mycloudgallery.core.index.ai.AiPublishStore
import dev.pampa.mycloudgallery.core.index.ai.AiReplicaEntry
import dev.pampa.mycloudgallery.core.index.ai.AiReplicaStore
import dev.pampa.mycloudgallery.core.index.ai.AiRowState
import dev.pampa.mycloudgallery.core.index.ai.AiSegmentRow
import dev.pampa.mycloudgallery.core.index.ai.AiStaged
import dev.pampa.mycloudgallery.core.index.ai.InMemoryAiPublishStore
import dev.pampa.mycloudgallery.core.index.ai.InMemoryAiReplicaStore
import dev.pampa.mycloudgallery.core.index.replica.store.HeadRow
import dev.pampa.mycloudgallery.core.index.replica.store.SegmentState
import dev.pampa.mycloudgallery.core.index.store.OwnHead
import dev.pampa.mycloudgallery.core.index.store.OwnSegment
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gli adattatori Room delle porte dei dati AI (P11) contro le implementazioni in memoria di P2 (`InMemoryAiPublishStore`,
 * `InMemoryAiReplicaStore`, il riferimento delle suite di P2): la stessa sequenza di operazioni da' le stesse risposte. Le sequenze
 * sono quelle di `AiShareTablesTest` (core-database): stage/move/discard/commit e applyHead/forgetDevice/commitApplied/forgetShare.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RoomAiStoresTest {

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val database = GalleryDatabase.build(app, null)

  @After
  fun close() = database.close()

  private fun key(n: Int) = ContentKey("%064x".format(n))

  private fun hash(n: Int) = dev.pampa.mycloudgallery.core.trust.crypto.Hashes.sha256Name(byteArrayOf(n.toByte(), 42))

  private fun staged(n: Int, cls: AiClass = AiClass.VEC) = AiStaged(cls, key(n), 1, byteArrayOf(n.toByte(), 7, 9))

  private suspend fun publishScript(store: AiPublishStore): List<Any?> {
    val out = ArrayList<Any?>()
    val rows = store.stage("Test-Comune", AiRowState.OUTBOX, listOf(staged(1), staged(2), staged(3, AiClass.OCR)))
    out.add(rows.map { it.state to it.staged.digest })
    store.stage("test", AiRowState.HELD, listOf(staged(4)))
    out.add(store.pending())
    out.add(store.refs("test-comune", AiRowState.OUTBOX).map { it.key to it.digest })
    store.move("test-comune", setOf(rows[1].id), AiRowState.HELD)
    out.add(store.rows("test-comune", AiRowState.HELD).map { it.staged.key })
    out.add(store.pending())
    val segment = OwnSegment(ClassedSegment(SegmentClass.VEC, SegmentRef(hash(1), 100)), 1)
    val head = OwnHead(3, HeadMark(3, hash(9)), 1_000, listOf(segment))
    store.commit("test-comune", AiPublishCommit(head, setOf(rows[0].id), listOf(segment), listOf(AiLiveEntry(AiKey(key(1), AiClass.VEC), 1, rows[0].staged.digest, hash(1)))))
    out.add(store.ownHead("test-comune"))
    out.add(store.live("test-comune", listOf(AiKey(key(1), AiClass.VEC), AiKey(key(2), AiClass.VEC))))
    // Revisione A (R14): tutte le voci vive, e una compattazione che ne toglie una.
    out.add(store.liveAll("test-comune").sortedBy { it.key.contentKey.hex })
    store.commit("test-comune", AiPublishCommit(head, emptySet(), emptyList(), emptyList(), dropped = listOf(AiKey(key(1), AiClass.VEC))))
    out.add(store.liveAll("test-comune"))
    store.discard("test-comune", setOf(rows[2].id))
    out.add(store.pending())
    out.add(store.shares().sorted())
    return out
  }

  private fun entry(device: String, segment: String, n: Int, recipe: Int = 1) =
    AiReplicaEntry(device, segment, AiKey(key(n), AiClass.VEC), recipe, "d$n", byteArrayOf(n.toByte()))

  private suspend fun replicaScript(store: AiReplicaStore): List<Any?> {
    val out = ArrayList<Any?>()
    val head = HeadRow("test-comune", "dev-a", 1, hash(5), 2, 1_000)
    val segA = AiSegmentRow("dev-a", hash(1), AiClass.VEC, 10, SegmentState.APPLIED, 2)
    out.add(store.applyHead(AiHeadApplication(head, listOf(segA), mapOf(hash(1) to listOf(entry("dev-a", hash(1), 1), entry("dev-a", hash(1), 2))))))
    val headB = HeadRow("test-comune", "dev-b", 1, hash(6), 2, 1_000)
    val segB = AiSegmentRow("dev-b", hash(2), AiClass.VEC, 10, SegmentState.APPLIED, 1)
    out.add(store.applyHead(AiHeadApplication(headB, listOf(segB), mapOf(hash(2) to listOf(entry("dev-b", hash(2), 2, recipe = 2))))))
    out.add(store.heads("test-comune"))
    out.add(store.segments("test-comune", "dev-a"))
    out.add(store.candidates("test-comune", listOf(AiKey(key(2), AiClass.VEC))).sortedBy { it.deviceId })
    out.add(store.body("test-comune", store.candidates("test-comune", listOf(AiKey(key(1), AiClass.VEC))).single())?.toList())
    out.add(store.keys("test-comune").sortedBy { it.contentKey.hex })
    out.add(store.dirty("test-comune").sortedBy { it.contentKey.hex })
    val marks = listOf(AiAppliedMark(AiKey(key(1), AiClass.VEC), "dev-a", 1, "d1"), AiAppliedMark(AiKey(key(2), AiClass.VEC), "dev-b", 2, "d2"))
    store.commitApplied("test-comune", listOf(AiKey(key(1), AiClass.VEC), AiKey(key(2), AiClass.VEC)), marks)
    out.add(store.dirty("test-comune"))
    out.add(store.applied("test-comune", listOf(AiKey(key(2), AiClass.VEC))))
    out.add(store.forgetDevice("test-comune", "dev-b"))
    out.add(store.candidates("test-comune", listOf(AiKey(key(2), AiClass.VEC))))
    out.add(store.dirty("test-comune").sortedBy { it.contentKey.hex })
    // Un segmento non piu' elencato esce con le sue voci.
    out.add(store.applyHead(AiHeadApplication(head.copy(version = 2), emptyList(), emptyMap())))
    out.add(store.keys("test-comune"))
    out.add(store.allApplied("test-comune").sortedBy { it.key.contentKey.hex })
    out.add(store.shares().sorted())
    store.forgetShare("test-comune")
    out.add(store.heads("test-comune"))
    out.add(store.allApplied("test-comune"))
    return out
  }

  @Test
  fun ilPubblicatoreSuRoomRispondeComeQuelloInMemoria() = runBlocking {
    assertThat(publishScript(RoomAiPublishStore(database)).map(::normalize)).isEqualTo(publishScript(InMemoryAiPublishStore()).map(::normalize))
  }

  @Test
  fun laReplicaSuRoomRispondeComeQuellaInMemoria() = runBlocking {
    assertThat(replicaScript(RoomAiReplicaStore(database)).map(::normalize)).isEqualTo(replicaScript(InMemoryAiReplicaStore()).map(::normalize))
  }

  /** Gli insiemi si confrontano come insiemi; il resto com'e' (le classi dati hanno `equals`). */
  private fun normalize(value: Any?): Any? = when (value) {
    is Set<*> -> value.toSet()
    is Map<*, *> -> value.toMap()
    is OwnHead -> listOf(value.version, value.mark, value.publishedAtNas, value.segments)
    else -> value
  }
}
