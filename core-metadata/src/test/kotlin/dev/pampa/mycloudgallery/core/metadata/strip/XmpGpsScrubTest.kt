package dev.pampa.mycloudgallery.core.metadata.strip

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Fase 5B, P11 (D26): la pulizia dell'XMP sul posto. Stessa lunghezza, nessuna proprieta' GPS, il resto intatto, in qualunque forma. */
class XmpGpsScrubTest {

  private fun scrub(text: String): Pair<String, XmpScrub> {
    val result = XmpGpsScrub.scrub(text.toByteArray(Charsets.ISO_8859_1))
    return String(result.bytes, Charsets.ISO_8859_1) to result
  }

  @Test
  fun attributiEdElementiSiCancellanoConLaStessaLunghezza() {
    val xmp = StripFixtures.xmpWithGps()
    val (clean, result) = scrub(xmp)

    assertThat(clean.length).isEqualTo(xmp.length)
    assertThat(result.removed).isEqualTo(5) // 2 attributi + 3 elementi (uno autochiuso)
    assertThat(XmpGpsScrub.hasGps(result.bytes)).isFalse()
    assertThat(XmpGpsScrub.hasGps(xmp.toByteArray(Charsets.ISO_8859_1))).isTrue()
    assertThat(clean).doesNotContain("GPS")
    assertThat(clean).contains("dc:creator=\"Mario\"")
    assertThat(clean).contains("<rdf:Description")
    assertThat(clean).contains("</rdf:Description>")
  }

  @Test
  fun tuttiIPrefissiEIModiDiScrittura() {
    val variants = listOf(
      "<a x:GPSLatitude=\"1\" />",
      "<a GPSLatitude='1' />",
      "<GPSLongitude>9</GPSLongitude>",
      "<foo:GPSAltitudeRef>0</foo:GPSAltitudeRef>",
      "<a  exif:GPSTimeStamp = \"2024-03-12T09:15:30Z\" b=\"c\"/>",
      "<exif:GPSDestLatitude/>",
    )
    for (xmp in variants) {
      val (clean, _) = scrub(xmp)
      assertThat(clean.length).isEqualTo(xmp.length)
      assertThat(XmpGpsScrub.hasGps(clean.toByteArray(Charsets.ISO_8859_1))).isFalse()
      assertThat(clean).doesNotContain("GPS")
    }
  }

  @Test
  fun unTestoCheNomina_Gps_SenzaEssereUnaProprietaNonScatta() {
    val text = "<dc:description>Fotocamera con GPS integrato</dc:description> <x a=\"GPS Camera\"/>"
    val (clean, result) = scrub(text)

    assertThat(result.removed).isEqualTo(0)
    assertThat(clean).isEqualTo(text)
    assertThat(XmpGpsScrub.hasGps(text.toByteArray(Charsets.ISO_8859_1))).isFalse()
  }

  @Test
  fun unPacchettoUtf16ConGpsNonSiRiscriveESiFerma() {
    val text = "<x a:GPSLatitude=\"1\"/>"
    val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)

    val result = XmpGpsScrub.scrub(utf16)

    assertThat(result.unsupported).isTrue()
    assertThat(XmpGpsScrub.hasGps(utf16)).isTrue()
    // Senza GPS dentro, un UTF-16 e' un pacchetto qualunque.
    val plain = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "<x a:Model=\"1\"/>".toByteArray(Charsets.UTF_16LE)
    assertThat(XmpGpsScrub.scrub(plain).unsupported).isFalse()
  }
}
