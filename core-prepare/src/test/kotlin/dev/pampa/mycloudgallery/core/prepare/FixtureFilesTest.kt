package dev.pampa.mycloudgallery.core.prepare

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.metadata.extract.DefaultMetadataExtractor
import dev.pampa.mycloudgallery.core.metadata.testing.FakeVideoProbe
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import dev.pampa.mycloudgallery.core.prepare.video.BlockCache
import dev.pampa.mycloudgallery.core.prepare.video.Mp4Scout
import dev.pampa.mycloudgallery.fixtures.PrepareFixtures
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** I campioni nuovi di `tools/fixtures` (P6) letti da lettori che non sono i miei: la struttura e' quella che dichiarano. */
@RunWith(RobolectricTestRunner::class)
class FixtureFilesTest {

  private fun extractor() = DefaultMetadataExtractor(
    FakePlaces(), FakePlaces(), FakeVideoProbe(null), previewDecoder = null,
    clock = { 1_790_000_000_000L }, blocking = Dispatchers.Unconfined,
  )

  private fun hints(name: String, mime: String) = ExtractHints(
    mime = mime, displayName = name, relativePath = "DCIM/Camera/", msDateTakenMs = null, msWidth = null, msHeight = null,
    msOrientation = null, msDurationMs = null, scanZone = ZoneId.of("Europe/Rome"),
  )

  @Test
  fun ilDngPiccoloEUnDngPerIlLettoreDeiMetadati() = runTest {
    for (orientation in listOf(1, 6, 8)) {
      val bytes = PrepareFixtures.smallDng(128, 96, orientation)
      val result = extractor().extract(CountingBytes(bytes), hints("a.dng", "image/x-adobe-dng"))
      assertThat(result).isInstanceOf(Extraction.Read::class.java)
      result as Extraction.Read
      assertThat(result.kind).isEqualTo(MediaKind.RAW_PHOTO)
      assertThat(result.metadata.width).isEqualTo(128)
      assertThat(result.metadata.height).isEqualTo(96)
      assertThat(result.metadata.orientation.exif).isEqualTo(orientation)
    }
    // 128 x 96 a 16 bit: 24.576 byte di dati grezzi, il resto e' l'indice dei tag.
    val bytes = PrepareFixtures.smallDng(128, 96, 1)
    assertThat(bytes.size).isGreaterThan(24_576)
    assertThat(bytes.size).isLessThan(30_000)
  }

  @Test
  fun unVideoVirtualeEDescrittoDaUnIndiceCoerenteConIlSuoPayload() {
    val mp4 = PrepareFixtures.video(PrepareFixtures.VideoSpec().apply { durationMs = 10_000; keyframeBytes = 5_000; deltaFrameBytes = 500 })
    val whole = mp4.toBytes()
    assertThat(whole.size.toLong()).isEqualTo(mp4.size())
    // I campioni si susseguono senza buchi dentro il mdat e finiscono dove comincia il moov (qui in coda).
    var expected = mp4.sampleOffset(0)
    for (i in 0 until mp4.sampleCount()) {
      assertThat(mp4.sampleOffset(i)).isEqualTo(expected)
      expected += mp4.sampleSize(i)
      assertThat(mp4.isSync(i)).isEqualTo(i % 60 == 0)
    }
    assertThat(expected).isEqualTo(mp4.moovOffset)
    assertThat(mp4.moovOffset + mp4.moovSize).isEqualTo(mp4.size())
    assertThat(mp4.closestSync(1_000)).isEqualTo(0)
    assertThat(mp4.closestSync(1_900)).isEqualTo(60)
    // Le letture parziali coincidono con il file intero, anche a cavallo dei riquadri.
    for (from in listOf(0L, 20L, 31L, 32L, mp4.moovOffset - 3, mp4.moovOffset + 5)) {
      val part = mp4.read(from, 100)
      assertThat(part.toList()).isEqualTo(whole.copyOfRange(from.toInt(), minOf(whole.size, from.toInt() + 100)).toList())
    }
    assertThat(mp4.read(mp4.size(), 10)).isEmpty()
    assertThat(String(whole, 4, 4, Charsets.ISO_8859_1)).isEqualTo("ftyp")
  }

  @Test
  fun conTroppiRiquadriDiPrimoLivelloNonSiDichiaraMancanteLIndice() = runTest {
    // ftyp, 300 riquadri `free` vuoti e poi il moov: l'esploratore si ferma al tetto e lascia decidere il sistema.
    val ftyp = byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
      0, 0, 2, 0, 'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), '4'.code.toByte(), '1'.code.toByte())
    val free = byteArrayOf(0, 0, 0, 8, 'f'.code.toByte(), 'r'.code.toByte(), 'e'.code.toByte(), 'e'.code.toByte())
    val many = ftyp + free.let { box -> ByteArray(box.size * 300) { box[it % box.size] } }
    val facts = Mp4Scout.scout(BlockCache(CountingBytes(many), 256 * 1024, 16), 1L shl 26)
    assertThat(facts.isoBmff).isTrue()
    assertThat(facts.hasMoov).isTrue()
    assertThat(facts.sampleEntry).isNull()
    // Pochi riquadri e poi la fine del file senza moov: li' si' che e' una registrazione interrotta.
    val few = ftyp + free.let { box -> ByteArray(box.size * 10) { box[it % box.size] } }
    assertThat(Mp4Scout.scout(BlockCache(CountingBytes(few), 256 * 1024, 16), 1L shl 26).hasMoov).isFalse()
  }
}
