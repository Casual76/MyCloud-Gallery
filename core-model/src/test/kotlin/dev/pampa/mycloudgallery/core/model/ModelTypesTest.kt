package dev.pampa.mycloudgallery.core.model

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.Fixtures.T0
import dev.pampa.mycloudgallery.core.model.Fixtures.full
import dev.pampa.mycloudgallery.core.model.Fixtures.located
import dev.pampa.mycloudgallery.core.model.Fixtures.media
import dev.pampa.mycloudgallery.core.model.Fixtures.nas
import dev.pampa.mycloudgallery.core.model.Fixtures.original
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDateTime

/** Invarianti dei tipi del modello: cio' che non deve poter esistere non si costruisce. */
class ModelTypesTest {

  @Test
  fun unMediaHaUnSoloOriginaleEVersioniEPosizioniProprie() {
    val v1 = original(full(1))
    val alsoOriginal = original(full(2))
    val foreign = original(full(3))

    assertThrows(IllegalArgumentException::class.java) { media(listOf(v1, alsoOriginal), emptyList()) }
    assertThrows(IllegalArgumentException::class.java) { media(listOf(v1), emptyList(), current = foreign.id) }
    assertThrows(IllegalArgumentException::class.java) { media(listOf(v1), listOf(located(1, foreign, nas("a.jpg")))) }
    assertThrows(IllegalArgumentException::class.java) {
      media(listOf(v1), listOf(located(1, v1, nas("Foto/A.jpg")), located(2, v1, nas("foto/a.JPG"))))
    }
    assertThrows(IllegalArgumentException::class.java) { media(emptyList(), emptyList(), current = v1.id) }
  }

  @Test
  fun unaVersioneRispettaLaSuaNatura() {
    val parent = VersionId.random()
    assertThrows(IllegalArgumentException::class.java) { MediaVersion(VersionId.random(), VersionKind.ORIGINAL, parent, full(1), T0) }
    assertThrows(IllegalArgumentException::class.java) { MediaVersion(VersionId.random(), VersionKind.EDITED, null, full(1), T0) }
    assertThrows(IllegalArgumentException::class.java) { MediaVersion(VersionId.random(), VersionKind.CORRECTED, parent, full(1), T0) }
    assertThrows(IllegalArgumentException::class.java) {
      MediaVersion(VersionId.random(), VersionKind.EDITED, parent, full(1), T0, setOf(CorrectionField.DATE))
    }
    val dateOnly = MediaVersion(VersionId.random(), VersionKind.CORRECTED, parent, full(1), T0, setOf(CorrectionField.DATE, CorrectionField.LOCATION))
    assertThat(dateOnly.isMetadataOnlyCorrection).isTrue()
  }

  /** E-M14: foto in ora locale e video in UTC dello stesso viaggio si ordinano per istante. */
  @Test
  fun laDataDiScattoOrdinaPerIstanteEMostraLOraDelPosto() {
    // Oslo, estate: UTC+2. Foto alle 10:00 locali, video alle 08:30 UTC (= 10:30 locali).
    val photo = CaptureTime(1_783_065_600_000L, 120, CaptureTimeConfidence.EXACT, CaptureTimeSource.EXIF_WITH_OFFSET)
    val video = CaptureTime(1_783_067_400_000L, 120, CaptureTimeConfidence.OFFSET_ASSUMED, CaptureTimeSource.VIDEO_UTC)

    assertThat(photo.localDateTime).isEqualTo(LocalDateTime.of(2026, 7, 3, 10, 0))
    assertThat(video.localDateTime).isEqualTo(LocalDateTime.of(2026, 7, 3, 10, 30))
    assertThat(listOf(video, photo).sortedBy { it.utcMillis }).containsExactly(photo, video).inOrder()
    assertThat(photo.isEstimated).isFalse()
  }

  @Test
  fun leDateStimateSonoDichiarate() {
    assertThrows(IllegalArgumentException::class.java) {
      CaptureTime(T0, 0, CaptureTimeConfidence.EXACT, CaptureTimeSource.FILE_MODIFIED)
    }
    assertThrows(IllegalArgumentException::class.java) {
      CaptureTime(T0, 19 * 60, CaptureTimeConfidence.EXACT, CaptureTimeSource.EXIF_WITH_OFFSET)
    }
    assertThat(CaptureTime(T0, 60, CaptureTimeConfidence.ESTIMATED, CaptureTimeSource.FILE_NAME).isEstimated).isTrue()
  }

  @Test
  fun laPosizioneGpsNonFinisceNeiLogENelToString() {
    val point = GeoPoint(45.4642, 9.19)
    val metadata = MediaMetadata(location = point)

    assertThat(point.toString()).doesNotContain("45")
    assertThat(metadata.toString()).doesNotContain("45.46")
    assertThat(metadata.toString()).doesNotContain("9.19")
    assertThat(GeoPoint::class.java.isAnnotationPresent(Sensitive::class.java)).isTrue()
    assertThrows(IllegalArgumentException::class.java) { GeoPoint(91.0, 0.0) }
  }

  // --- Vettori (E-I4) ------------------------------------------------------------------------------

  @Test
  fun loStessoModelloConDimensioneOPrecisioneDiverseEUnAltroSpazio() {
    val base = EmbeddingSpace(EmbeddingSpace.EMBEDDING_GEMMA2_FLOAT_V1, 768, VectorPrecision.FLOAT32)
    assertThat(base).isNotEqualTo(base.copy(dimension = 256))
    assertThat(base).isNotEqualTo(base.copy(precision = VectorPrecision.FLOAT16))
    assertThat(base.vectorBytes).isEqualTo(768 * 4)
    assertThrows(IllegalArgumentException::class.java) { EmbeddingSpace("Gemma 2!", 768, VectorPrecision.FLOAT32) }
  }

  @Test
  fun unVettoreDellaDimensioneSbagliataERifiutatoEIByteSonoCopiati() {
    val space = EmbeddingSpace(EmbeddingSpace.EMBEDDING_GEMMA2_FLOAT_V1, 3, VectorPrecision.FLOAT32)
    assertThrows(IllegalArgumentException::class.java) { EmbeddingVector(space, 0, ByteArray(8), null) }

    val vector = EmbeddingVector.ofFloats(space, 0, floatArrayOf(1f, -2.5f, 0.125f), null)
    assertThat(vector.toFloats().toList()).containsExactly(1f, -2.5f, 0.125f).inOrder()
    // Little-endian: 1.0f = 0x3F800000 -> 00 00 80 3F.
    assertThat(vector.bytes.copyOfRange(0, 4).toList()).containsExactly(0.toByte(), 0.toByte(), 0x80.toByte(), 0x3F.toByte()).inOrder()

    val leaked = vector.bytes
    leaked[0] = 99
    assertThat(vector.bytes[0]).isEqualTo(0.toByte())
  }

  @Test
  fun nelleUnioniPreferitoONascostoVinconoSempre() {
    assertThat(PersonalFlags(favorite = true).mergedWith(PersonalFlags(hidden = true))).isEqualTo(PersonalFlags(favorite = true, hidden = true))
    assertThat(PersonalFlags().mergedWith(PersonalFlags())).isEqualTo(PersonalFlags())
  }
}
