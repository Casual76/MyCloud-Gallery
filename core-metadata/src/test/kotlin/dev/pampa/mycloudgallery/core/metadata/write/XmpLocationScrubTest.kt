package dev.pampa.mycloudgallery.core.metadata.write

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.metadata.strip.LocationFixtures
import dev.pampa.mycloudgallery.core.metadata.strip.LocationTraces
import dev.pampa.mycloudgallery.core.metadata.strip.StripFixtures
import org.junit.Test

/**
 * Correzioni 5B (R12, R13): la pulizia del luogo nell'XMP di una foto corretta ([XmpLocationScrub]). Sul posto (stessa lunghezza), in qualunque
 * spazio dei nomi (anche `drone-dji:GpsLatitude`), elementi interi con quello che contengono, i nomi di luogo testuali; il resto resta. E un XMP
 * costruito apposta non blocca piu' un thread: la scansione e' lineare, con un tetto di dimensione e uno di tempo.
 */
class XmpLocationScrubTest {

  private fun scrub(text: String): Pair<String, XmpScrubbed> {
    val result = XmpLocationScrub.scrub(text.toByteArray(Charsets.UTF_8))
    return String(result.bytes, Charsets.UTF_8) to result
  }

  @Test
  fun attributiEdElementiDelLuogoSparisconoConLaStessaLunghezzaEIlRestoResta() {
    val xmp = StripFixtures.xmpWithGps()
    val (clean, result) = scrub(xmp)

    assertThat(clean.length).isEqualTo(xmp.length)
    assertThat(result.removed).isEqualTo(5)
    assertThat(clean).doesNotContain("GPS")
    assertThat(clean).contains("dc:creator=\"Mario\"")
    assertThat(clean).contains("<rdf:Description")
    assertThat(clean).contains("</rdf:Description>")
    assertThat(LocationTraces.scanXmp(result.bytes)).isEmpty()
    assertThat(LocationTraces.scanXmp(xmp.toByteArray())).isNotEmpty()
  }

  @Test
  fun nomiDiDroniEDiLuogoInQualunqueSpazioDeiNomi() {
    val (clean, _) = scrub(LocationFixtures.djiXmp())
    assertThat(clean).doesNotContain("GpsLatitude")
    assertThat(clean).doesNotContain("GpsLongtitude")
    assertThat(clean).doesNotContain("AbsoluteAltitude")
    assertThat(clean).doesNotContain("+45.4642")
    assertThat(clean).contains("tiff:Make=\"DJI\"")
    assertThat(clean).contains("drone-dji:FlightYawDegree")

    val iptc = "<x:xmpmeta><rdf:Description rdf:about=\"\" photoshop:City=\"Milano\" photoshop:Country=\"Italia\" dc:format=\"image/jpeg\">" +
      "<Iptc4xmpExt:LocationShown><rdf:Bag><rdf:li rdf:parseType=\"Resource\"><Iptc4xmpExt:City>Milano</Iptc4xmpExt:City>" +
      "<Iptc4xmpExt:GPSLatitude>45,27.852N</Iptc4xmpExt:GPSLatitude></rdf:li></rdf:Bag></Iptc4xmpExt:LocationShown>" +
      "<Iptc4xmpCore:Location>Duomo</Iptc4xmpCore:Location></rdf:Description></x:xmpmeta>"
    val (cleanIptc, result) = scrub(iptc)
    assertThat(cleanIptc).doesNotContain("Milano")
    assertThat(cleanIptc).doesNotContain("Italia")
    assertThat(cleanIptc).doesNotContain("Duomo")
    assertThat(cleanIptc).doesNotContain("45,27")
    assertThat(cleanIptc).contains("dc:format=\"image/jpeg\"")
    assertThat(result.removed).isEqualTo(4)
  }

  @Test
  fun unTestoCheNominaIlGpsSenzaEssereUnaProprietaNonSiTocca() {
    val text = "<dc:description>Fotocamera con GPS integrato</dc:description> <x a=\"GPS Camera\"/>"
    val (clean, result) = scrub(text)
    assertThat(result.removed).isEqualTo(0)
    assertThat(clean).isEqualTo(text)
  }

  @Test
  fun unXmpCostruitoApposta_SiFermaSubitoInvecheDiBloccareUnThread() {
    // R13: `<GPSA<GPSA…` senza `>`: prima un'espressione regolare quadratica, ore su 4 MiB.
    val hostile = "<GPSA".repeat(200_000).toByteArray() // ~1 MiB
    val started = System.nanoTime()
    val result = XmpLocationScrub.scrub(hostile)
    val millis = (System.nanoTime() - started) / 1_000_000
    assertThat(result.unsupported).isTrue()
    assertThat(millis).isLessThan(1_000L)
    // Oltre il tetto di dimensione non si guarda nemmeno.
    assertThat(XmpLocationScrub.scrub(ByteArray(XmpLocationScrub.MAX_PACKET + 1) { 'a'.code.toByte() }).unsupported).isTrue()
    // Attributi senza chiusura e molti nomi uno dietro l'altro: lineare anche cosi'.
    val quotes = (" exif:GPSLatitude=\"" + "x".repeat(100)).repeat(5_000).toByteArray()
    val t2 = System.nanoTime()
    XmpLocationScrub.scrub(quotes)
    assertThat((System.nanoTime() - t2) / 1_000_000).isLessThan(1_000L)
  }

  @Test
  fun unPacchettoUtf16ConIlLuogoNonSiRiscrive() {
    val text = "<x a:GPSLatitude=\"1\"/>"
    val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
    assertThat(XmpLocationScrub.scrub(utf16).unsupported).isTrue()
    val plain = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "<x a:Model=\"1\"/>".toByteArray(Charsets.UTF_16LE)
    assertThat(XmpLocationScrub.scrub(plain).unsupported).isFalse()
  }
}
