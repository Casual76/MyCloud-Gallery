package dev.pampa.mycloudgallery.core.index.p3

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.compact.CompactOutcome
import dev.pampa.mycloudgallery.core.index.compact.CompactionPolicy
import dev.pampa.mycloudgallery.core.index.compact.Compactor
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.IndexWriteGate
import dev.pampa.mycloudgallery.core.index.contract.MetaChange
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.index.publish.IndexWriteRules
import dev.pampa.mycloudgallery.core.index.publish.ShareLocks
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentContent
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** La compattazione (D5): stesso stato per chi legge, meno segmenti, lapidi tolte solo con tutti i `meta`, regole. */
open class CompactorTest {

  private val share = "test-comune"
  private val world = TestWorld()
  private val phone = world.device()
  private val files = (1..40).map { world.file(share, "Foto/IMG_$it.jpg") }

  /** I byte dell'ultimo blob messo nell'outbox per (classe, chiave): lo stato che chi legge deve trovare. */
  private val newest = HashMap<Pair<SegmentClass, ContentKey>, String>()

  private fun compactor(
    enabled: Boolean = true,
    policy: CompactionPolicy = CompactionPolicy(minSegments = 4),
    gate: IndexWriteGate = phone.gate,
  ) = Compactor(
    identity = phone.identity,
    clock = world.clock,
    fileSystem = { world.nas.share(it) },
    store = phone.store,
    remote = world.reader(),
    rules = IndexWriteRules(phone.flags, gate),
    locks = ShareLocks(),
    enabled = enabled,
    policy = policy,
    io = Dispatchers.Unconfined,
  )

  /** Dodici pubblicazioni piccole: percorsi nuovi, riscritti, tolti; miniature ripubblicate con byte diversi. */
  private suspend fun history(random: Random = Random(5)) {
    val publisher = phone.publisher()
    repeat(12) { round ->
      val picked = files.shuffled(random).take(6)
      val changes = picked.mapIndexed { i, f ->
        if (i == 0 && round > 2) MetaChange.Remove(f.path) else f.put(setOf(SegmentClass.THUMB_S))
      }.filter { it !is MetaChange.Remove || phone.store.knownPaths(share, setOf(dev.pampa.mycloudgallery.core.trust.data.IndexKeys.metaHex(it.path))).isNotEmpty() }
      val thumbs = picked.drop(1).map { f ->
        val bytes = Images.webp(marker = byteArrayOf(round.toByte()) + f.marker().copyOf(7))
        newest[SegmentClass.THUMB_S to f.contentKey] = bytes.hex()
        dev.pampa.mycloudgallery.core.index.contract.BlobPut.Image(SegmentClass.THUMB_S, f.contentKey, bytes)
      }
      publisher.stage(share, changes, thumbs)
      publisher.flush(share, FlushReason.UNIT_FINISHED)
    }
    world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS)
  }

  @Test
  fun lostessoStato_menoSegmenti_eNessunaLapide() = runTest {
    history()
    val before = ReaderState.read(world, share, phone.id)
    assertThat(before.segments).isEqualTo(24)

    val outcome = compactor().compactIfNeeded(share) as CompactOutcome.Compacted
    val after = ReaderState.read(world, share, phone.id)

    assertThat(after.headVersion).isEqualTo(outcome.headVersion)
    assertThat(after.segments).isEqualTo(2)
    assertThat(after.records).isEqualTo(before.records)
    // Per ogni (classe, chiave) restano i byte dell'ultima pubblicazione, che chi leggeva prima aveva gia'.
    assertThat(after.blobs.keys).isEqualTo(before.blobs.keys)
    for ((key, bytes) in after.blobs) {
      assertThat(bytes).containsExactly(newest.getValue(key))
      assertThat(before.blobs.getValue(key)).contains(newest.getValue(key))
    }
    // Tutti i segmenti meta riscritti: le lapidi non servono piu'.
    val metas = metaRecordsOfHead()
    assertThat(metas.none { it is MetaRecord.Gone }).isTrue()

    world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS)
    assertThat(compactor().compactIfNeeded(share)).isEqualTo(CompactOutcome.NotNeeded)
    // Il pubblicatore continua dalla testa compattata.
    val publisher = phone.publisher()
    publisher.stage(share, listOf(files[0].put()), emptyList())
    publisher.flush(share, FlushReason.UNIT_FINISHED)
    assertThat(ReaderState.read(world, share, phone.id).records.keys).containsAtLeastElementsIn(after.records.keys)
  }

  @Test
  fun compattazioneParziale_tieneLeLapidi_eLoStatoNonCambia() = runTest {
    history()
    val before = ReaderState.read(world, share, phone.id)

    val outcome = compactor(policy = CompactionPolicy(minSegments = 4, maxInputBytes = 8_000)).compactIfNeeded(share)
    assertThat(outcome).isInstanceOf(CompactOutcome.Compacted::class.java)
    val after = ReaderState.read(world, share, phone.id)

    assertThat(after.records).isEqualTo(before.records)
    assertThat(after.segments).isLessThan(before.segments)
  }

  @Test
  fun spentaBloccataOTroppoPresto() = runTest {
    history()
    assertThat(compactor(enabled = false).compactIfNeeded(share)).isEqualTo(CompactOutcome.Disabled)
    assertThat(compactor(gate = { WriteBlock.FROZEN }).compactIfNeeded(share)).isEqualTo(CompactOutcome.Blocked(WriteBlock.FROZEN))
    assertThat(compactor(gate = { WriteBlock.CLOCK_JUMP }).compactIfNeeded(share)).isEqualTo(CompactOutcome.Blocked(WriteBlock.CLOCK_JUMP))

    val publisher = phone.publisher()
    publisher.stage(share, listOf(files[1].put()), emptyList())
    publisher.flush(share, FlushReason.UNIT_FINISHED)
    assertThat(compactor().compactIfNeeded(share)).isInstanceOf(CompactOutcome.TooSoon::class.java)
  }

  @Test
  fun unSegmentoProprioRovinatoSulNas_nessunaTestaNuova() = runTest {
    history()
    val head = phone.store.ownHead(share)!!
    val victim = head.segments.first { it.segment.cls == SegmentClass.META }
    world.nas.share(share).put(NasLayout.segmentFile(phone.id, "index", victim.hash), "rovinato".toByteArray())

    val outcome = compactor().compactIfNeeded(share)

    assertThat(outcome).isEqualTo(CompactOutcome.Aborted(RejectReason.HASH_MISMATCH))
    assertThat(phone.store.ownHead(share)!!.version).isEqualTo(head.version)
    assertThat(headVersions(world, share, phone.id).last()).isEqualTo(head.version)
  }

  private suspend fun metaRecordsOfHead(): List<MetaRecord> {
    val remote = world.reader()
    val fetch = remote.latestHead(share, phone.id, null) as dev.pampa.mycloudgallery.core.index.contract.RemoteResult.Ok
    val head = (fetch.value as dev.pampa.mycloudgallery.core.index.contract.HeadFetch.Accepted).head
    return head.segments.filter { it.cls == SegmentClass.META }.flatMap { segment ->
      val r = remote.segment(head, segment) as dev.pampa.mycloudgallery.core.index.contract.RemoteResult.Ok
      ((r.value as dev.pampa.mycloudgallery.core.index.contract.SegmentFetch.Ok).content as SegmentContent.Meta).records
    }
  }
}
