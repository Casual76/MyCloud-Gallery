package dev.pampa.mycloudgallery.core.model

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test

/** I tipi della Fase 3 (schema v2): luogo, segnaposto, riferimenti ai byte, flag della cella, orientamento. */
class TimelineTypesTest {

  @Test
  fun ilLuogoESensibileComeIlGps() {
    val place = PlaceRef(3_176_959, 1)
    val metadata = MediaMetadata(place = place)

    assertThat(place.toString()).doesNotContain("3176959")
    assertThat(metadata.toString()).doesNotContain("3176959")
    assertThat(PlaceRef::class.java.isAnnotationPresent(Sensitive::class.java)).isTrue()
    assertThat(place).isEqualTo(PlaceRef(3_176_959, 1))
    assertThat(place).isNotEqualTo(PlaceRef(3_176_959, 2))
    assertThrows(IllegalArgumentException::class.java) { PlaceRef(0, 1) }
    assertThrows(IllegalArgumentException::class.java) { PlaceRef(1, 0) }
  }

  @Test
  fun ilThumbHashEBase64CanonicoDiAlMassimo48Caratteri() {
    val bytes = ByteArray(25) { (it * 7).toByte() }
    val hash = ThumbHash.of(bytes)
    assertThat(hash.bytes().toList()).isEqualTo(bytes.toList())
    assertThat(hash.base64.length).isAtMost(ThumbHash.MAX_LENGTH)
    assertThat(hash.toString()).doesNotContain(hash.base64)
    assertThat(ThumbHash.of(ByteArray(36)).base64).hasLength(48)

    assertThrows(IllegalArgumentException::class.java) { ThumbHash.of(ByteArray(37)) }
    assertThrows(IllegalArgumentException::class.java) { ThumbHash("") }
    assertThrows(IllegalArgumentException::class.java) { ThumbHash("YWJj ZA==") }
    assertThrows(IllegalArgumentException::class.java) { ThumbHash("YWJjZA") } // senza riempimento
    assertThrows(IllegalArgumentException::class.java) { ThumbHash("YWJjZB==") } // bit di troppo: non canonico
    assertThrows(IllegalArgumentException::class.java) { ThumbHash("YW_jZA==") } // alfabeto URL
  }

  @Test
  fun iRiferimentiAiByteSonoValidati() {
    assertThat(MediaRef.Phone("external_primary", 42, "image/jpeg").mediaStoreId).isEqualTo(42)
    assertThrows(IllegalArgumentException::class.java) { MediaRef.Phone("External Primary", 42, null) }
    assertThrows(IllegalArgumentException::class.java) { MediaRef.Phone("external_primary", 0, null) }
    assertThrows(IllegalArgumentException::class.java) { MediaRef.Synthetic(1, 0, 10) }
    val ref: MediaRef = MediaRef.Synthetic(7, 4000, 3000)
    assertThat(ref).isInstanceOf(MediaRef.Synthetic::class.java)
  }

  /** I bit dei flag finiscono nell'istantanea su file: uno a testa, mai cambiati. */
  @Test
  fun iFlagDellaCellaHannoBitStabiliEUnici() {
    CellFlags.ALL.forEach { (name, bit) -> assertWithMessage(name).that(Integer.bitCount(bit)).isEqualTo(1) }
    assertThat(CellFlags.ALL.values.toSet()).hasSize(CellFlags.ALL.size)
    assertThat(CellFlags.ALL).containsExactly(
      "EDITED", 1, "DATE_ESTIMATED", 2, "UNREADABLE", 4, "HAS_PLACE", 8, "MOTION", 16,
      "PANORAMA", 32, "ULTRA_HDR", 64, "FAVORITE", 128, "GROUP_MEMBER", 256, "GROUP_COVER", 512,
    ).inOrder()
    assertThat(CellFlags.has(CellFlags.FAVORITE or CellFlags.MOTION, CellFlags.MOTION)).isTrue()
    assertThat(CellFlags.has(CellFlags.FAVORITE, CellFlags.MOTION)).isFalse()
  }

  /** E-V3, E-M15: gli 8 valori EXIF sono rotazione + specchio; 5 e 7 scambiano i lati come 90 e 270. */
  @Test
  fun gliOttoOrientamentiExifSonoRotazioneEspecchio() {
    val swaps = (1..8).associateWith { MediaMetadata.orientationOf(it).swapsSides }
    assertThat(swaps).containsExactly(1, false, 2, false, 3, false, 4, false, 5, true, 6, true, 7, true, 8, true)
    assertThat((1..8).filter { MediaMetadata.orientationOf(it).mirrored }).containsExactly(2, 4, 5, 7)
    assertThat(MediaMetadata.orientationOf(0)).isEqualTo(ExifOrientation.NORMAL)
    assertThat(MediaMetadata.orientationOf(9)).isEqualTo(ExifOrientation.NORMAL)

    ExifOrientation.entries.forEach { o ->
      val metadata = MediaMetadata(orientationDegrees = o.degrees, mirrored = o.mirrored)
      assertThat(metadata.orientation).isEqualTo(o)
    }
  }
}
