package dev.pampa.mycloudgallery.core.metadata

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.metadata.contract.ByteRange
import dev.pampa.mycloudgallery.core.metadata.contract.CaptureTimeInputs
import dev.pampa.mycloudgallery.core.metadata.contract.CaptureTimeResolver
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractCode
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.metadata.contract.FileNameDates
import dev.pampa.mycloudgallery.core.metadata.contract.GroupCandidate
import dev.pampa.mycloudgallery.core.metadata.contract.GroupingRules
import dev.pampa.mycloudgallery.core.metadata.contract.MotionPhotoLocator
import dev.pampa.mycloudgallery.core.metadata.contract.SourceClassifier
import dev.pampa.mycloudgallery.core.metadata.contract.ThumbHashCodec
import dev.pampa.mycloudgallery.core.metadata.noop.NoExifDetailsReader
import dev.pampa.mycloudgallery.core.metadata.noop.NoMetadataExtractor
import dev.pampa.mycloudgallery.core.metadata.noop.NoVideoProbe
import dev.pampa.mycloudgallery.core.metadata.testing.ByteArrayMediaBytes
import dev.pampa.mycloudgallery.core.metadata.testing.FakeMetadataExtractor
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.places.noop.NoTimeZoneLookup
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class MetadataContractTest {

  private val hints = ExtractHints(
    mime = "image/jpeg", displayName = "IMG_20240312_101530.jpg", relativePath = "DCIM/Camera/",
    msDateTakenMs = null, msWidth = 4000, msHeight = 3000, msOrientation = 0, msDurationMs = null,
    scanZone = ZoneId.of("Europe/Rome"),
  )

  @Test
  fun iByteSiLeggonoAIntervalliComeUnFile() = runTest {
    val bytes = ByteArrayMediaBytes(ByteArray(100) { it.toByte() })
    assertThat(bytes.size).isEqualTo(100L)
    assertThat(bytes.read(0L..3L)).isEqualTo(byteArrayOf(0, 1, 2, 3))
    // Oltre la fine l'intervallo si accorcia; fuori dal file o vuoto da' un array vuoto.
    assertThat(bytes.read(98L..500L)).isEqualTo(byteArrayOf(98, 99))
    assertThat(bytes.read(100L..200L)).isEmpty()
    assertThat(bytes.read(LongRange.EMPTY)).isEmpty()
    assertThat(bytes.reads).isEqualTo(4)
    assertThat(bytes.bytesRead).isEqualTo(6L)
  }

  @Test
  fun unIntervalloDiByteHaEstremiCoerenti() {
    val range = ByteRange(10, 20)
    assertThat(range.length).isEqualTo(10L)
    assertThat(range.toLongRange()).isEqualTo(10L..19L)
    assertThat(ByteRange(5, 5).toLongRange().isEmpty()).isTrue()
    assertThrows(IllegalArgumentException::class.java) { ByteRange(10, 5) }
    assertThrows(IllegalArgumentException::class.java) { ByteRange(-1, 5) }
  }

  @Test
  fun iNullObjectFallisconoChiusi() = runTest {
    val bytes = ByteArrayMediaBytes(ByteArray(8))
    assertThat(NoMetadataExtractor.extract(bytes, hints)).isEqualTo(Extraction.Unsupported(ExtractCode.UNSUPPORTED_FORMAT))
    assertThat(NoExifDetailsReader.exifDetails(bytes).model).isNull()
    assertThat(NoVideoProbe.probe(bytes)).isNull()
  }

  @Test
  fun lEstrattoreFintoRispondeAlCopione() = runTest {
    val extractor = FakeMetadataExtractor()
    extractor.script("IMG_20240312_101530.jpg", Extraction.Corrupt(ExtractCode.DECODE_FAILED))
    val bytes = ByteArrayMediaBytes(ByteArray(8))
    assertThat(extractor.extract(bytes, hints)).isEqualTo(Extraction.Corrupt(ExtractCode.DECODE_FAILED))
    assertThat(extractor.extract(bytes, hints.copy(displayName = "altro.txt"))).isInstanceOf(Extraction.NotMedia::class.java)
    assertThat(extractor.calls).isEqualTo(2)
    // Il nome del file non finisce nel toString dei suggerimenti.
    assertThat(hints.toString()).doesNotContain("IMG_2024")
    assertThat(hints.toString()).doesNotContain("DCIM")
  }

  @Test
  fun iSegnapostoDiP0DannoEsitiSicuri() {
    // La data ha sempre una fonte valida: DATE_TAKEN con fuso supposto, poi la data del file (sempre stimata).
    val now = 1_700_000_000_000L
    val rome = ZoneId.of("Europe/Rome")
    val taken = CaptureTimeResolver.resolve(
      CaptureTimeInputs(mediaStoreDateTakenMs = now - 86_400_000L * 30, scanZone = rome, nowMs = now), NoTimeZoneLookup, null,
    )
    assertThat(taken.source).isEqualTo(CaptureTimeSource.MEDIASTORE)
    assertThat(taken.confidence).isEqualTo(CaptureTimeConfidence.OFFSET_ASSUMED)
    val absurd = CaptureTimeResolver.resolve(
      CaptureTimeInputs(mediaStoreDateTakenMs = 0L, fileModifiedMs = null, scanZone = rome, nowMs = now), NoTimeZoneLookup, null,
    )
    assertThat(absurd.source).isEqualTo(CaptureTimeSource.FILE_MODIFIED)
    assertThat(absurd.isEstimated).isTrue()

    assertThat(FileNameDates.parse("IMG-20240312-WA0001.jpg")).isNull()
    assertThat(SourceClassifier.classify("external_primary", "DCIM/Screenshots/", "Screenshot_1.png", null, "image/png").source)
      .isEqualTo(MediaSource.OTHER)
    val candidate = GroupCandidate(MediaId.random(), "phone:external_primary:dcim/camera/", "a.jpg", "image/jpeg", MediaKind.PHOTO, 1L, null)
    assertThat(GroupingRules.propose(listOf(candidate, candidate))).isEmpty()
    assertThat(MotionPhotoLocator.locate(null, ByteArray(4), 100L)).isNull()
  }

  @Test
  fun ilSegnapostoSfocatoDiP0EGrigioEDecodificaAlmenoUnPixel() {
    val hash: ThumbHash = ThumbHashCodec.encode(4, 4, ByteArray(4 * 4 * 4))
    assertThat(hash).isEqualTo(ThumbHashCodec.NEUTRAL)
    assertThat(ThumbHashCodec.decode(hash, 32)).hasLength(32 * 32)
    assertThat(ThumbHashCodec.decode(hash, 0)).hasLength(1)
  }
}
