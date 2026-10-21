package dev.pampa.mycloudgallery.core.prepare

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.contract.VideoFrame
import dev.pampa.mycloudgallery.core.index.contract.WorkItem
import dev.pampa.mycloudgallery.core.index.contract.WorkPass
import dev.pampa.mycloudgallery.core.index.contract.WorkUnitKey
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.index.testing.FakeUnitPriority
import dev.pampa.mycloudgallery.core.index.testing.FakeWorkUnits
import dev.pampa.mycloudgallery.core.index.testing.MapWriteGate
import dev.pampa.mycloudgallery.core.index.testing.fakeIndexDevice
import dev.pampa.mycloudgallery.core.metadata.testing.ByteArrayMediaBytes
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.prepare.contract.AnalysisSink
import dev.pampa.mycloudgallery.core.prepare.contract.PrepBudget
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOpen
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOutput
import dev.pampa.mycloudgallery.core.prepare.contract.PrepResult
import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import dev.pampa.mycloudgallery.core.prepare.contract.PrepareSource
import dev.pampa.mycloudgallery.core.prepare.contract.Preparer
import dev.pampa.mycloudgallery.core.prepare.run.DefaultPreparationRun
import dev.pampa.mycloudgallery.core.prepare.testing.FakePrepInputs
import dev.pampa.mycloudgallery.core.prepare.testing.MapPrepRecords
import dev.pampa.mycloudgallery.core.trust.data.MetaFile
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 6 (D10, P5): la preparazione consegna `analysis` e `frames` alla porta [AnalysisSink] (il buffer degli indicizzatori), anche
 * quando la share e' "solo locale" (F4-D31), senza mai bloccarsi o fallire per colpa del buffer. Le altre classi non arrivano mai.
 */
class AnalysisSinkUseTest {
  private val share = "test-comune"
  private var counter = 0

  private class Media(val bytes: ByteArray, val item: WorkItem, val base: MetaFile)

  private fun media(kind: MediaKind, missing: Set<SegmentClass>): Media {
    val bytes = Random(counter++).nextBytes(3_000)
    val qh = QuickHashAlgorithm.ofContent(bytes)
    val stamp = NasStamp(bytes.size.toLong(), 1_800_000_000_000L + counter, 5)
    val mediaId = MediaId.random()
    val versionId = VersionId.random()
    val item = WorkItem(ContentKey.of(qh, bytes.size.toLong()), SharePath.parseMediaPath("2024/m$counter.jpg"), stamp, kind, null, mediaId, versionId, missing)
    return Media(bytes, item, MetaFile(stamp = stamp, quickHash = qh, mediaId = mediaId, versionId = versionId, kind = kind, source = MediaSource.OTHER))
  }

  private val webp = byteArrayOf(0x52, 0x49, 0x46, 0x46, 1, 2, 3, 4, 0x57, 0x45, 0x42, 0x50)

  /** Prepara foto e video: S, M, analisi, HQ per le foto; S e fotogrammi per i video. */
  private class AllClasses(val webp: ByteArray) : Preparer {
    override suspend fun prepare(source: PrepareSource, spec: PrepSpec): PrepResult {
      val bytes = (source as PrepareSource.Bytes).bytes.read(0L until source.bytes.size)
      val outputs = spec.classes.map { cls ->
        if (cls == SegmentClass.FRAMES) {
          PrepOutput.Frames(listOf(VideoFrame(0, byteArrayOf(-1, -40, -1, 1)), VideoFrame(30_000, byteArrayOf(-1, -40, -1, 2))))
        } else {
          PrepOutput.Image(cls, if (cls == SegmentClass.ANALYSIS) byteArrayOf(-1, -40, -1, 9) else webp, 100, 80)
        }
      }
      return PrepResult.Done(outputs, null, null, null, QuickHashAlgorithm.ofContent(bytes))
    }
  }

  private suspend fun run(sink: AnalysisSink, gate: MapWriteGate = MapWriteGate()): Pair<List<Media>, Int> {
    val photo = media(MediaKind.PHOTO, setOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.ANALYSIS, SegmentClass.HQ))
    val video = media(MediaKind.VIDEO, setOf(SegmentClass.THUMB_S, SegmentClass.FRAMES))
    val units = FakeWorkUnits()
    val (publisher, _) = fakeIndexDevice("devicea0000000000000000000", gate = gate)
    val inputs = FakePrepInputs()
    val records = MapPrepRecords()
    for (m in listOf(photo, video)) {
      records.records[m.item.contentKey.hex] = m.base
      inputs.opens[m.item.contentKey.hex] = PrepOpen.Ready(PrepareSource.Bytes(ByteArrayMediaBytes(m.bytes), m.item.kind, null)) {}
    }
    units.offer(WorkUnitKey(share, WorkPass.PREP, "2024-03", 0), listOf(photo.item, video.item))
    val run = DefaultPreparationRun(
      workUnits = units, inputs = inputs, preparer = AllClasses(webp), publisher = publisher, records = records,
      limits = PrepBudget(256, 8), decoderFamily = "c2_me", dispatcher = Dispatchers.Default, analysisSink = sink,
    )
    val result = run.run(share, 8.minutes, FakeUnitPriority())
    return listOf(photo, video) to result.prepared
  }

  @Test
  fun analisiEFotogrammiArrivanoAlBuffer_ancheConLaShareSoloLocale() = runTest {
    val got = java.util.concurrent.ConcurrentHashMap<ContentKey, List<SegmentClass>>()
    val gate = MapWriteGate().apply { block(share, WriteBlock.LOCAL_ONLY) }
    val (medias, prepared) = run(AnalysisSink { key, outputs -> got[key] = outputs.map { it.cls } }, gate)
    assertThat(prepared).isEqualTo(2)
    assertThat(got[medias[0].item.contentKey]).containsExactly(SegmentClass.ANALYSIS)
    assertThat(got[medias[1].item.contentKey]).containsExactly(SegmentClass.FRAMES)
  }

  @Test
  fun unBufferCheFallisceNonFermaLaPreparazione() = runTest {
    val (_, prepared) = run(AnalysisSink { _, _ -> throw IllegalStateException("buffer rotto") })
    assertThat(prepared).isEqualTo(2)
  }
}
