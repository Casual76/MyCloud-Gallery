package dev.pampa.mycloudgallery.core.metadata

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.metadata.contract.MotionFormat
import dev.pampa.mycloudgallery.core.metadata.contract.MotionPhotoLocator
import dev.pampa.mycloudgallery.core.metadata.format.ArrayAccess
import dev.pampa.mycloudgallery.core.metadata.format.JpegScanner
import dev.pampa.mycloudgallery.core.metadata.format.SefTrailer
import dev.pampa.mycloudgallery.fixtures.ExifWriter
import dev.pampa.mycloudgallery.fixtures.ImageFixtures
import dev.pampa.mycloudgallery.fixtures.MotionFixtures
import dev.pampa.mycloudgallery.fixtures.XmpPackets
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Google v1, MicroVideo e trailer SEF (E-M5), e il formato non riconosciuto (E-M16), su file sintetici. */
class MotionPhotoLocatorTest {

  private val still = ImageFixtures.solid(64, 48, 0xB07040)
  private val video = MotionFixtures.video(3000)
  private val exif = ExifWriter.Spec().apply { dateOriginal = "2024:03:12 10:15:30"; offsetOriginal = "+01:00" }

  private fun tailOf(file: ByteArray, n: Int = 64 * 1024): ByteArray = file.copyOfRange(maxOf(0, file.size - n), file.size)

  private suspend fun xmpOf(file: ByteArray): String? = JpegScanner.scan(ArrayAccess(file)).xmp

  @Test
  fun googleV1_DallaDirectoryDelContenitore() = runTest {
    val file = MotionFixtures.googleV1(still, exif, video)
    val part = MotionPhotoLocator.locate(xmpOf(file), tailOf(file), file.size.toLong())!!
    assertThat(part.format).isEqualTo(MotionFormat.GOOGLE_V1)
    assertThat(part.videoRange.endExclusive).isEqualTo(file.size.toLong())
    assertThat(part.videoRange.length).isEqualTo(video.size.toLong())
    assertThat(file.copyOfRange(part.videoRange.start.toInt(), part.videoRange.endExclusive.toInt())).isEqualTo(video)
    assertThat(part.stillPresentationUs).isEqualTo(1_500_000L)
  }

  @Test
  fun googleV1_ScrittoComeNellaSpecifica_PrefissoCameraEVociAElementi() = runTest {
    val file = MotionFixtures.googleV1Elements(still, exif, video)
    val part = MotionPhotoLocator.locate(xmpOf(file), tailOf(file), file.size.toLong())!!
    assertThat(part.format).isEqualTo(MotionFormat.GOOGLE_V1)
    assertThat(part.videoRange.length).isEqualTo(video.size.toLong())
    // Senza MotionPhotoPresentationTimestampUs il fotogramma non e' dichiarato.
    assertThat(part.stillPresentationUs).isNull()
  }

  @Test
  fun microVideo_DallOffsetDalleFineDelFile() = runTest {
    val file = MotionFixtures.microVideo(still, exif, video)
    val part = MotionPhotoLocator.locate(xmpOf(file), tailOf(file), file.size.toLong())!!
    assertThat(part.format).isEqualTo(MotionFormat.MICRO_VIDEO)
    assertThat(part.videoRange.endExclusive).isEqualTo(file.size.toLong())
    assertThat(part.videoRange.length).isEqualTo(video.size.toLong())
    assertThat(part.stillPresentationUs).isEqualTo(1_500_000L)
  }

  @Test
  fun samsungSef_DalTrailerInFondoAlFile() {
    val file = MotionFixtures.samsungSef(still, exif, video)
    val part = MotionPhotoLocator.locate(null, tailOf(file), file.size.toLong())!!
    assertThat(part.format).isEqualTo(MotionFormat.SAMSUNG_SEF)
    // Il video non e' in fondo al file (dopo ci sono un blocco, la directory e il piede), ma i suoi byte sono esatti.
    assertThat(part.videoRange.endExclusive).isLessThan(file.size.toLong())
    assertThat(file.copyOfRange(part.videoRange.start.toInt(), part.videoRange.endExclusive.toInt())).isEqualTo(video)
  }

  @Test
  fun samsungSef_ConLaCodaPiuCortaDelBlocco_SiUsaIlNomePresunto() {
    // La coda contiene la directory ma non l'inizio del blocco del video: il nome "MotionPhoto_Data" (16) e' quello presunto.
    val file = MotionFixtures.samsungSef(still, exif, video)
    val part = MotionPhotoLocator.locate(null, tailOf(file, 200), file.size.toLong())!!
    assertThat(file.copyOfRange(part.videoRange.start.toInt(), part.videoRange.endExclusive.toInt())).isEqualTo(video)
  }

  @Test
  fun samsungSef_ConIlBloccoDa12ByteCheContieneUnPuntatore() {
    val file = MotionFixtures.samsungSefPointer(still, exif, video)
    val part = MotionPhotoLocator.locate(null, tailOf(file), file.size.toLong())!!
    assertThat(part.format).isEqualTo(MotionFormat.SAMSUNG_SEF)
    assertThat(file.copyOfRange(part.videoRange.start.toInt(), part.videoRange.endExclusive.toInt())).isEqualTo(video)
  }

  @Test
  fun laDirectoryDelTrailerEQuellaDiExifToolInOrdineEDimensioni() {
    val file = MotionFixtures.samsungSef(still, exif, video)
    val dir = SefTrailer.parse(tailOf(file), file.size.toLong())!!
    assertThat(dir.entries.map { it.type }).containsExactly(0x0A01, 0x0A30, 0x0A31).inOrder()
    for (e in dir.entries) {
      // Ogni blocco sta prima della directory, ed e' lungo quanto dichiarato.
      assertThat(dir.blockStart(e) + e.size).isAtMost(dir.directoryStart)
      assertThat(dir.blockStart(e)).isAtLeast(0L)
    }
    assertThat(dir.directoryStart).isLessThan(file.size.toLong())
  }

  @Test
  fun googleHaLaPrecedenzaSuSef() = runTest {
    val google = MotionFixtures.googleV1(still, exif, video)
    // Un file con l'XMP di Google e anche il trailer di Samsung: vale l'XMP.
    val sef = MotionFixtures.samsungSef(ImageFixtures.solid(64, 48, 1), exif, video)
    val both = google + sef.copyOfRange(sef.size - 600, sef.size)
    val part = MotionPhotoLocator.locate(xmpOf(google), tailOf(both), both.size.toLong())
    assertThat(part?.format).isEqualTo(MotionFormat.GOOGLE_V1)
  }

  @Test
  fun unFormatoNonRiconosciutoDaNull_E_M16() = runTest {
    // Dichiarata ma senza dove: null.
    val flagOnly = ImageFixtures.jpeg(still, exif, XmpPackets.motionFlagOnly())
    assertThat(MotionPhotoLocator.locate(xmpOf(flagOnly), tailOf(flagOnly), flagOnly.size.toLong())).isNull()
    // Nessun XMP e nessun trailer.
    val plain = ImageFixtures.jpeg(still, exif, null)
    assertThat(MotionPhotoLocator.locate(null, tailOf(plain), plain.size.toLong())).isNull()
    // Lunghezza della voce maggiore del file: il video e' stato tolto.
    val removed = ImageFixtures.jpeg(still, exif, XmpPackets.googleMotionAttributes(90_000, 0))
    assertThat(MotionPhotoLocator.locate(xmpOf(removed), tailOf(removed), removed.size.toLong())).isNull()
    // Valori assurdi.
    for (offset in listOf(0L, -5L, 1L, 7L, 1_000_000L)) {
      val xmp = XmpPackets.microVideo(offset, 0)
      assertThat(MotionPhotoLocator.locate(xmp, ByteArray(10), 5_000L)).isNull()
    }
    assertThat(MotionPhotoLocator.locate(null, ByteArray(0), 0L)).isNull()
    assertThat(MotionPhotoLocator.locate("", ByteArray(4), 100L)).isNull()
  }

  @Test
  fun unTrailerSeftRovinatoDaNullESenzaEccezioni() {
    val file = MotionFixtures.samsungSef(still, exif, video)
    val tail = tailOf(file)
    // "SEFT" in fondo ma la lunghezza non torna.
    val badLength = tail.copyOf()
    badLength[badLength.size - 8] = 0xFF.toByte()
    badLength[badLength.size - 7] = 0xFF.toByte()
    assertThat(MotionPhotoLocator.locate(null, badLength, file.size.toLong())).isNull()
    // Intestazione "SEFH" distrutta.
    val dir = SefTrailer.parse(tail, file.size.toLong())!!
    val noHeader = tail.copyOf()
    val at = (dir.directoryStart - (file.size - tail.size)).toInt()
    noHeader[at] = 'X'.code.toByte()
    assertThat(MotionPhotoLocator.locate(null, noHeader, file.size.toLong())).isNull()
    // Coda troppo corta per contenere la directory.
    assertThat(MotionPhotoLocator.locate(null, tailOf(file, 12), file.size.toLong())).isNull()
    // Numero di voci assurdo.
    val manyEntries = tail.copyOf()
    manyEntries[at + 8] = 0xFF.toByte()
    manyEntries[at + 9] = 0xFF.toByte()
    assertThat(MotionPhotoLocator.locate(null, manyEntries, file.size.toLong())).isNull()
    // Byte casuali che finiscono per caso con SEFT.
    val noise = ByteArray(300) { (it * 31).toByte() }
    noise[noise.size - 4] = 'S'.code.toByte(); noise[noise.size - 3] = 'E'.code.toByte(); noise[noise.size - 2] = 'F'.code.toByte(); noise[noise.size - 1] = 'T'.code.toByte()
    assertThat(MotionPhotoLocator.locate(null, noise, 10_000L)).isNull()
  }
}
