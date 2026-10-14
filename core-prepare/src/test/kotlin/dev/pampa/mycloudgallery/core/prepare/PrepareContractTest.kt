package dev.pampa.mycloudgallery.core.prepare

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.contract.UnitOutcome
import dev.pampa.mycloudgallery.core.index.contract.WorkItem
import dev.pampa.mycloudgallery.core.index.contract.WorkPass
import dev.pampa.mycloudgallery.core.index.contract.WorkUnitKey
import dev.pampa.mycloudgallery.core.index.testing.FakeUnitPriority
import dev.pampa.mycloudgallery.core.index.testing.FakeWorkUnits
import dev.pampa.mycloudgallery.core.index.testing.fakeIndexDevice
import dev.pampa.mycloudgallery.core.metadata.testing.ByteArrayMediaBytes
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.prepare.contract.PrepBudget
import dev.pampa.mycloudgallery.core.prepare.contract.PrepFailCode
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOpen
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOutput
import dev.pampa.mycloudgallery.core.prepare.contract.PrepResult
import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import dev.pampa.mycloudgallery.core.prepare.contract.PrepareSource
import dev.pampa.mycloudgallery.core.prepare.testing.FakePrepInputs
import dev.pampa.mycloudgallery.core.prepare.testing.FakePreparer
import dev.pampa.mycloudgallery.core.prepare.testing.SimplePreparationRun
import dev.pampa.mycloudgallery.core.trust.data.MetaFile
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/** I contratti di core-prepare sui fake di P0: P6 scrive il preparatore vero, P11 lega il giro. */
class PrepareContractTest {

  private fun item(content: ByteArray, kind: MediaKind, name: String): WorkItem {
    val qh = QuickHashAlgorithm.ofContent(content)
    return WorkItem(
      contentKey = ContentKey.of(qh, content.size.toLong()),
      path = SharePath.parseMediaPath(name),
      stamp = NasStamp(content.size.toLong(), 1_800_000_000_000, 5),
      kind = kind,
      mime = null,
      mediaId = MediaId.random(),
      versionId = VersionId.random(),
      missing = setOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.FRAMES),
    )
  }

  @Test
  fun leMisureDiDefaultSonoQuelleDiD12() {
    val spec = PrepSpec()
    assertThat(listOf(spec.thumbS, spec.thumbM, spec.analysis, spec.hq)).containsExactly(200, 400, 1024, 2048).inOrder()
    assertThat(spec.frameEverySec to spec.maxFrames).isEqualTo(30 to 8)
    assertThrows(IllegalArgumentException::class.java) { PrepSpec(thumbS = 500) }
    assertThrows(IllegalArgumentException::class.java) { PrepSpec(classes = setOf(SegmentClass.META)) }
    val budget = PrepBudget(memoryClassMb = 512, cores = 8)
    assertThat(budget.heavyDecodes).isEqualTo(1)
    assertThat(budget.maxTempBytes).isEqualTo(512L * 1024 * 1024)
  }

  @Test
  fun ilPreparatoreFintoLeggeLeFotoIntereEIVideoNo() = runTest {
    val preparer = FakePreparer()
    val photo = Random(1).nextBytes(300_000)
    val video = Random(2).nextBytes(3_000_000)

    val photoResult = preparer.prepare(PrepareSource.Bytes(ByteArrayMediaBytes(photo), MediaKind.PHOTO, "image/jpeg")) as PrepResult.Done
    assertThat(photoResult.fullHash).isEqualTo(Sha256.of(photo))
    val webp = (photoResult.outputs.first { it.cls == SegmentClass.THUMB_S } as PrepOutput.Image).bytes
    assertThat(String(webp.copyOfRange(8, 12), Charsets.US_ASCII)).isEqualTo("WEBP")

    val videoBytes = ByteArrayMediaBytes(video)
    val videoResult = preparer.prepare(PrepareSource.Bytes(videoBytes, MediaKind.VIDEO, "video/mp4")) as PrepResult.Done
    assertThat(videoResult.fullHash).isNull()
    assertThat(videoBytes.bytesRead).isAtMost(2L * 65_536)
    assertThat(videoResult.outputs.map { it.cls }).contains(SegmentClass.FRAMES)

    preparer.failures[MediaKind.RAW_PHOTO] = PrepResult.Failed(PrepFailCode.DECODER_MISSING, retryable = false, decoderFamily = "sw")
    val failed = preparer.prepare(PrepareSource.Bytes(ByteArrayMediaBytes(photo), MediaKind.RAW_PHOTO, null)) as PrepResult.Failed
    assertThat(failed.decoderMissing).isTrue()
  }

  /** Il giro sui contratti: unita' → apertura → preparazione → stage → flush → finish; D14 passa di mano. */
  @Test
  fun unGiroDiPreparazioneSuiContrattiPubblicaEPassaDiMano() = runTest {
    val units = FakeWorkUnits()
    val photo = Random(3).nextBytes(10_000)
    val raw = Random(4).nextBytes(20_000)
    val photoItem = item(photo, MediaKind.PHOTO, "a.jpg")
    val rawItem = item(raw, MediaKind.RAW_PHOTO, "b.dng")
    units.offer(WorkUnitKey("test-comune", WorkPass.PREP, "2024-03", 0), listOf(photoItem, rawItem))
    val inputs = FakePrepInputs()
    inputs.opens[photoItem.contentKey.hex] = PrepOpen.Ready(PrepareSource.Bytes(ByteArrayMediaBytes(photo), MediaKind.PHOTO, null)) {}
    inputs.opens[rawItem.contentKey.hex] = PrepOpen.Ready(PrepareSource.Bytes(ByteArrayMediaBytes(raw), MediaKind.RAW_PHOTO, null)) {}
    val preparer = FakePreparer()
    preparer.failures[MediaKind.RAW_PHOTO] = PrepResult.Failed(PrepFailCode.DECODER_MISSING, retryable = false, decoderFamily = "c2_qti")
    val (publisher, replica) = fakeIndexDevice("devicea0000000000000000000")
    val run = SimplePreparationRun(units, inputs, preparer, publisher, recordFor = { item, _, _ ->
      MetaFile(
        stamp = item.stamp,
        quickHash = QuickHashAlgorithm.ofContent(if (item.kind == MediaKind.PHOTO) photo else raw),
        mediaId = item.mediaId!!,
        versionId = item.versionId!!,
        kind = item.kind,
        source = MediaSource.OTHER,
      )
    })

    val result = run.run("test-comune", 8.minutes, FakeUnitPriority())

    assertThat(result.units).isEqualTo(1)
    assertThat(result.prepared).isEqualTo(1)
    assertThat(result.handedOver).isEqualTo(1)
    assertThat(units.finished.single().second).isEqualTo(UnitOutcome.Completed(prepared = 1, failed = 1))
    assertThat(replica.locate(photoItem.contentKey.short, SegmentClass.THUMB_S)).isNotNull()
    val published = publisher.published.single()
    val rawRecord = published.records.map { it as dev.pampa.mycloudgallery.core.trust.data.MetaRecord.Present }
      .single { it.path == rawItem.path }
    assertThat(rawRecord.file.failure!!.decoder).isEqualTo("c2_qti")
  }
}
