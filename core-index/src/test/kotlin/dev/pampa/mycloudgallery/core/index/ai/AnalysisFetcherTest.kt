package dev.pampa.mycloudgallery.core.index.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.contract.BlobPut
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.MetaChange
import dev.pampa.mycloudgallery.core.index.contract.SyncScope
import dev.pampa.mycloudgallery.core.index.contract.VideoFrame
import dev.pampa.mycloudgallery.core.index.p3.Images
import dev.pampa.mycloudgallery.core.index.p3.TestFile
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.DeviceHead
import dev.pampa.mycloudgallery.core.trust.data.HeadKind
import dev.pampa.mycloudgallery.core.trust.data.HeadSeries
import dev.pampa.mycloudgallery.core.trust.data.IndexSegments
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Un buffer in memoria con un tetto di voci (falso = pieno, come sotto 1 GiB libero). */
class MemoryBuffer(private val capacity: Int = Int.MAX_VALUE) : AiBufferSink {
  val blobs = LinkedHashMap<Pair<ContentKey, SegmentClass>, ByteArray>()

  override suspend fun put(key: ContentKey, cls: SegmentClass, bytes: ByteArray): Boolean {
    if (blobs.size >= capacity) return false
    blobs[key to cls] = bytes.copyOf()
    return true
  }
}

/**
 * Lo scaricamento delle anteprime nel buffer (D10; contratto `AnalysisFetcher`): solo `analysis`, `frames` e `hq`, solo blob
 * **attestati** (R1 della 4A: il dispositivo che ha pubblicato il blob ha, nella stessa share, un record con la stessa chiave intera),
 * traffico registrato; l'HQ gia' nella replica non costa traffico.
 */
class AnalysisFetcherTest {

  @get:Rule val tmp = TemporaryFolder()

  private val share = "test-comune"

  /** Il preparatore pubblica record e anteprime (analisi, HQ, e i fotogrammi per [video]). */
  private suspend fun prepareWithPreviews(device: AiDevice, files: List<TestFile>, share: String = this.share, video: TestFile? = null) {
    val publisher = device.indexPublisher()
    val classes = setOf(SegmentClass.THUMB_S, SegmentClass.ANALYSIS, SegmentClass.HQ)
    val blobs = files.flatMap { f -> listOf(f.thumb(SegmentClass.THUMB_S), f.thumb(SegmentClass.ANALYSIS), BlobPut.Image(SegmentClass.HQ, f.contentKey, Images.webp(marker = f.marker()))) }
    val frames = video?.let { listOf(BlobPut.Frames(it.contentKey, listOf(VideoFrame(0, Images.jpeg(marker = it.marker())), VideoFrame(30_000, Images.jpeg(marker = it.marker()))))) }.orEmpty()
    val changes = (files + listOfNotNull(video)).map { MetaChange.Put(it.path, it.meta(classes + if (it === video) setOf(SegmentClass.FRAMES) else emptySet())) }
    publisher.stage(share, changes, blobs + frames)
    publisher.flush(share, FlushReason.UNIT_FINISHED)
  }

  private fun fetcher(reader: AiDevice, traffic: MutableList<Long>, local: Boolean = false) =
    IndexAnalysisFetcher(reader.remote(), reader.replicaStore, { traffic += it }, if (local) reader.replica else null)

  @Test
  fun soloBlobAttestati_ilTrafficoERegistrato_eIByteSonoQuelliPubblicati() = runTest {
    val ai = AiWorld(tmp.root)
    val preparer = ai.device("Tablet")
    val files = (1..5).map { ai.file(share, "Foto/IMG_$it.jpg") }
    val video = ai.file(share, "Video/VID_1.mp4")
    prepareWithPreviews(preparer, files, video = video)
    val reader = ai.device("Telefono")
    reader.sync()
    val traffic = ArrayList<Long>()
    val buffer = MemoryBuffer()

    val report = fetcher(reader, traffic).fetch(share, files.map { it.contentKey } + video.contentKey, setOf(SegmentClass.ANALYSIS, SegmentClass.FRAMES), buffer, Duration.INFINITE)

    assertThat(report.fetched).isEqualTo(6)
    assertThat(report.missing).isEqualTo(6) // nessun fotogramma per le 5 foto, nessuna analisi per il video
    assertThat(report.stoppedEarly).isFalse()
    assertThat(report.bytes).isGreaterThan(0)
    assertThat(traffic.sum()).isEqualTo(report.bytes)
    for (f in files) assertThat(buffer.blobs[f.contentKey to SegmentClass.ANALYSIS]!!.contentEquals(f.thumb(SegmentClass.ANALYSIS).bytes)).isTrue()
    assertThat(buffer.blobs).containsKey(video.contentKey to SegmentClass.FRAMES)
    // Mai i byte originali: nel buffer ci sono solo le classi chieste.
    assertThat(buffer.blobs.keys.map { it.second }.toSet()).containsExactly(SegmentClass.ANALYSIS, SegmentClass.FRAMES)
  }

  @Test
  fun unBlobSenzaRecordDelSuoDispositivo_oDiUnAltraShare_nonSiUsa() = runTest {
    val ai = AiWorld(tmp.root)
    val preparer = ai.device("Tablet")
    val rogue = ai.device("Compromesso")
    val shared = ai.file(share, "Foto/IMG_1.jpg")
    val other = ai.file("test", "Foto/IMG_2.jpg")
    // Il record del file comune c'e' (del preparatore), ma l'anteprima la pubblica un dispositivo senza record suo.
    preparer.prepare(listOf(shared))
    val fake = IndexSegments.blobs(SegmentClass.ANALYSIS, listOf(shared.contentKey to Images.jpeg(marker = ByteArray(8) { 9 })))
    val fs = ai.world.nas.share(share)
    fs.publishExclusive(NasLayout.segmentFile(rogue.id, "index", fake.ref.hash), fake.bytes)
    val state = ai.world.trust.state
    HeadSeries.publish(fs, state, rogue.base.signer, HeadKind.INDEX, 0, rogue.base.account) { v ->
      DeviceHead(state.registry.familyId, rogue.id, share, HeadKind.INDEX, v, state.registry.version, ai.clock.nowMillis(), listOf(ClassedSegment(SegmentClass.ANALYSIS, fake.ref)))
        .sign(rogue.base.signer)
    }
    // Il file dell'area privata ha anteprima e record, ma nell'altra share.
    prepareWithPreviews(preparer, listOf(other), share = "test")
    val reader = ai.device("Telefono")
    reader.sync(share, "test")
    val traffic = ArrayList<Long>()
    val buffer = MemoryBuffer()

    val report = fetcher(reader, traffic).fetch(share, listOf(shared.contentKey, other.contentKey), setOf(SegmentClass.ANALYSIS), buffer, Duration.INFINITE)

    assertThat(report.fetched).isEqualTo(0)
    assertThat(report.missing).isEqualTo(2)
    assertThat(buffer.blobs).isEmpty()
    // Un segmento senza nessuna chiave attestata non si scarica nemmeno.
    assertThat(traffic).isEmpty()
  }

  @Test
  fun lHqGiaNellaReplicaNonCostaTraffico() = runTest {
    val ai = AiWorld(tmp.root)
    val preparer = ai.device("Tablet")
    val files = (1..3).map { ai.file(share, "Foto/IMG_$it.jpg") }
    prepareWithPreviews(preparer, files)
    val reader = ai.device("Telefono")
    reader.replica.keptClasses = setOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.HQ)
    reader.engine().sync(SyncScope(setOf(share), setOf(SegmentClass.THUMB_S, SegmentClass.HQ)), Duration.INFINITE)
    val traffic = ArrayList<Long>()
    val fetcher = fetcher(reader, traffic, local = true)
    val buffer = MemoryBuffer()

    val report = fetcher.fetch(share, files.map { it.contentKey }, setOf(SegmentClass.HQ), buffer, Duration.INFINITE)

    assertThat(report.fetched).isEqualTo(3)
    assertThat(report.bytes).isEqualTo(0)
    assertThat(traffic).isEmpty()
    assertThat(fetcher.segmentReads.get()).isEqualTo(0)
  }

  @Test
  fun bufferPienoFermaLoScaricamento_eUnSegmentoGiaScaricatoNonSiRiscarica() = runTest {
    val ai = AiWorld(tmp.root)
    val preparer = ai.device("Tablet")
    val files = (1..6).map { ai.file(share, "Foto/IMG_$it.jpg") }
    prepareWithPreviews(preparer, files)
    val reader = ai.device("Telefono")
    reader.sync()
    val traffic = ArrayList<Long>()
    val fetcher = fetcher(reader, traffic)
    val keys = files.map { it.contentKey }

    val full = fetcher.fetch(share, keys, setOf(SegmentClass.ANALYSIS), MemoryBuffer(capacity = 2), Duration.INFINITE)
    assertThat(full.fetched).isEqualTo(2)
    assertThat(full.stoppedEarly).isTrue()
    assertThat(full.missing).isEqualTo(0) // quelli non guardati non sono "mancanti"

    // Il lotto dopo: lo stesso segmento, gia' in memoria.
    val rest = fetcher.fetch(share, keys, setOf(SegmentClass.ANALYSIS), MemoryBuffer(), Duration.INFINITE)
    assertThat(rest.fetched).isEqualTo(6)
    assertThat(fetcher.segmentReads.get()).isEqualTo(1)
    assertThat(traffic).hasSize(1)
  }

  @Test
  fun soloAnalysisFramesEHq() = runTest {
    val ai = AiWorld(tmp.root)
    val reader = ai.device("Telefono")
    val fetcher = fetcher(reader, ArrayList())
    assertThrows(IllegalArgumentException::class.java) {
      kotlinx.coroutines.runBlocking { fetcher.fetch(share, emptyList(), setOf(SegmentClass.THUMB_S), MemoryBuffer(), Duration.INFINITE) }
    }
    assertThrows(IllegalArgumentException::class.java) {
      kotlinx.coroutines.runBlocking { fetcher.fetch(share, emptyList(), setOf(SegmentClass.META), MemoryBuffer(), Duration.INFINITE) }
    }
  }
}
