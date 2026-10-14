package dev.pampa.mycloudgallery.core.nasmedia

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractCode
import dev.pampa.mycloudgallery.core.metadata.contract.ExtractHints
import dev.pampa.mycloudgallery.core.metadata.contract.Extraction
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataExtractor
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasMetaResult
import dev.pampa.mycloudgallery.core.nasmedia.detect.Detection
import dev.pampa.mycloudgallery.core.nasmedia.detect.NasDetector
import dev.pampa.mycloudgallery.core.nasmedia.detect.ReadBudget
import dev.pampa.mycloudgallery.core.nasmedia.testing.HeadSniffingExtractor
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Il rilevamento di un file (§2.3): `qh1` da testa e coda, metadati, miniatura EXIF, tetti. Robolectric solo perche'
 * `NasMediaBytes` nomina `MediaDataSource`.
 */
@RunWith(AndroidJUnit4::class)
class NasDetectorTest {

  private val nas = InMemoryNasMedia()
  private val jpegHead = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte())
  private val mp4Head = byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D)

  init {
    nas.addShare("s")
  }

  private suspend fun open(path: String): NasRandomFile =
    (nas.reader.open("s", SharePath.parse(path), Purpose.BACKGROUND) as NasResult.Ok).value

  private suspend fun detect(
    path: String,
    extractor: MetadataExtractor = HeadSniffingExtractor(),
    budget: ReadBudget? = null,
    modifiedAt: Long = 1_700_000_000_000,
  ): Detection {
    val name = path.substringAfterLast('/')
    val folder = path.substringBeforeLast('/', "").ifEmpty { null }
    return open(path).use { NasDetector(extractor) { ROME }.detect(it, name, folder, modifiedAt, budget) }
  }

  private fun jpegBytes(size: Int, seed: Int = 1): ByteArray = ByteArray(size) { i -> if (i < 4) jpegHead[i] else ((i * 31 + seed) % 251).toByte() }

  @Test
  fun unaFotoPiccolaHaLImprontaDelSuoContenuto() = runTest {
    val bytes = jpegBytes(100_000)
    nas.putFile("s", "Camera/a.jpg", NasContent.of(bytes))
    val d = detect("Camera/a.jpg") as Detection.Media
    assertThat(d.fingerprint).isEqualTo(Fingerprint(100_000, QuickHashAlgorithm.ofContent(bytes)))
    assertThat(d.fingerprint.fullHash).isNull()
    assertThat(d.bytesRead).isEqualTo(100_000)
    assertThat(nas.bytesRead.get()).isEqualTo(100_000)
    assertThat((d.meta as NasMetaResult.Read).kind).isEqualTo(MediaKind.PHOTO)
  }

  @Test
  fun unaFotoGrandeLeggeSoloTestaECodaEL_improntaEQuellaGiusta() = runTest {
    val content = NasContent.virtual(300_000_000, seed = 5, head = jpegHead)
    nas.putFile("s", "Camera/big.jpg", content)
    val d = detect("Camera/big.jpg") as Detection.Media
    val file = open("Camera/big.jpg")
    val regions = QuickHashAlgorithm.regions(300_000_000)
    val expected = QuickHashAlgorithm.compute(300_000_000, file.readRange(regions[0]), file.readRange(regions[1]))
    file.close()
    assertThat(d.fingerprint.quickHash).isEqualTo(expected)
    // Testa 64 KiB + coda fino a 128 KiB: mai di piu' per una foto che l'estrattore legge dalla testa.
    assertThat(d.bytesRead).isAtMost(64L * 1024 + 128L * 1024)
    assertThat(d.stamp.size).isEqualTo(300_000_000)
  }

  @Test
  fun unVideoDa9GBNonSiScaricaMai() = runTest {
    nas.putFile("s", "Video/v.mp4", NasContent.virtual(9_400_000_000L, seed = 9, head = mp4Head))
    val before = nas.bytesRead.get()
    val d = detect("Video/v.mp4") as Detection.Media
    assertThat(d.bytesRead).isAtMost(64L * 1024 + 128L * 1024)
    assertThat(nas.bytesRead.get() - before).isEqualTo(d.bytesRead)
    assertThat((d.meta as NasMetaResult.Read).kind).isEqualTo(MediaKind.VIDEO)
    assertThat(nas.opens.get()).isEqualTo(1)
  }

  @Test
  fun zeroByteENonMediaNonEntranoInLibreria() = runTest {
    nas.putFile("s", "Download/vuoto.jpg", NasContent.of(ByteArray(0)))
    nas.putFile("s", "Download/finto.jpg", NasContent.virtual(50_000, seed = 3))
    val empty = detect("Download/vuoto.jpg") as Detection.NotMedia
    assertThat(empty.code).isEqualTo(ExtractCode.ZERO_BYTES)
    assertThat(empty.bytesRead).isEqualTo(0)
    val fake = detect("Download/finto.jpg") as Detection.NotMedia
    assertThat(fake.code).isEqualTo(ExtractCode.NOT_A_MEDIA_FILE)
  }

  @Test
  fun ilTettoDelGiroFermaIlRilevamentoESenzaSuperarlo() = runTest {
    nas.putFile("s", "Camera/a.jpg", NasContent.virtual(1_000_000, seed = 4, head = jpegHead))
    val budget = ReadBudget(100_000)
    val d = detect("Camera/a.jpg", budget = budget)
    assertThat(d).isInstanceOf(Detection.CapReached::class.java)
    assertThat(budget.taken).isAtMost(100_000)
    assertThat(nas.bytesRead.get()).isAtMost(100_000)
  }

  @Test
  fun ilTettoVaRispettatoAncheConDetectionInParallelo() = runTest {
    repeat(30) { i -> nas.putFile("s", "Camera/p$i.jpg", NasContent.virtual(2_000_000, seed = 100L + i, head = jpegHead)) }
    val budget = ReadBudget(1_000_000)
    val results = (0 until 30).map { i -> async { detect("Camera/p$i.jpg", budget = budget) } }.awaitAll()
    assertThat(budget.taken).isAtMost(1_000_000)
    assertThat(nas.bytesRead.get()).isAtMost(1_000_000)
    assertThat(results.count { it is Detection.Media }).isAtLeast(3)
    assertThat(results.count { it is Detection.CapReached }).isAtLeast(1)
    // I byte contati dal budget sono quelli letti davvero.
    assertThat(budget.taken).isEqualTo(nas.bytesRead.get())
  }

  @Test
  fun unErroreDelNasNonEUnFileRovinato() = runTest {
    nas.putFile("s", "Camera/a.jpg", NasContent.virtual(1_000_000, seed = 4, head = jpegHead))
    nas.fault = FakeNasFault { op, _, _ -> if (op == FakeNasOp.READ) NasError.FileBusy else null }
    val d = detect("Camera/a.jpg")
    assertThat(d).isInstanceOf(Detection.Unreadable::class.java)
    assertThat((d as Detection.Unreadable).error).isEqualTo(NasError.FileBusy)
    nas.fault = null
  }

  @Test
  fun unEstrattoreCheLeggeTuttoViene_fermatoDalTettoPerFileENonDichiaraRovinato() = runTest {
    nas.putFile("s", "Camera/a.png", NasContent.virtual(10_000_000, seed = 6, head = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
    // Come il ripiego di decodifica completa di DefaultMetadataExtractor: legge tutto in pezzi e, se fallisce, dice "rovinato".
    val greedy = scripted { bytes, _ ->
      try {
        var pos = 0L
        while (pos < bytes.size) {
          bytes.read(pos until minOf(bytes.size, pos + 1_000_000))
          pos += 1_000_000
        }
        Extraction.Read(MediaMetadata(), MediaKind.PHOTO)
      } catch (_: IOException) {
        Extraction.Corrupt(ExtractCode.DECODE_FAILED)
      }
    }
    val d = detect("Camera/a.png", extractor = greedy) as Detection.Media
    assertThat(d.meta).isNull() // metadati "da completare", non Failed
    assertThat(d.bytesRead).isAtMost(NasDetector.IMAGE_LIMIT)
    assertThat(d.fingerprint.size).isEqualTo(10_000_000)
  }

  @Test
  fun unFileRovinatoUnFormatoNonSupportatoESonoCasiNormali() = runTest {
    nas.putFile("s", "Camera/a.jpg", NasContent.virtual(200_000, seed = 4, head = jpegHead))
    val corrupt = scripted { _, _ -> Extraction.Corrupt(ExtractCode.TRUNCATED) }
    val unsupported = scripted { _, _ -> Extraction.Unsupported(ExtractCode.UNSUPPORTED_FORMAT) }
    assertThat((detect("Camera/a.jpg", corrupt) as Detection.Media).meta).isEqualTo(NasMetaResult.Failed("TRUNCATED"))
    assertThat((detect("Camera/a.jpg", unsupported) as Detection.Media).meta).isEqualTo(NasMetaResult.Unsupported)
  }

  @Test
  fun l_estrattoreRiceveNomeCartellaEDataDiModificaDelNas() = runTest {
    nas.putFile("s", "Vacanze/Spagna/IMG_1.jpg", NasContent.virtual(200_000, seed = 4, head = jpegHead))
    var seen: ExtractHints? = null
    val spy = scripted { _, hints ->
      seen = hints
      Extraction.Read(MediaMetadata(), MediaKind.PHOTO)
    }
    detect("Vacanze/Spagna/IMG_1.jpg", spy, modifiedAt = 1_650_000_000_000)
    val hints = checkNotNull(seen)
    assertThat(hints.displayName).isEqualTo("IMG_1.jpg")
    assertThat(hints.relativePath).isEqualTo("Vacanze/Spagna/")
    assertThat(hints.msDateModifiedMs).isEqualTo(1_650_000_000_000)
    assertThat(hints.mime).isEqualTo("image/jpeg")
    assertThat(hints.scanZone).isEqualTo(ROME)
    assertThat(hints.msDateTakenMs).isNull()
  }

  // --- La miniatura EXIF (D17) -------------------------------------------------------------------------------------------

  private val thumb = Jpegs.thumbnail(160, 120, padding = 3_000)

  private fun extractorOf(width: Int, height: Int, degrees: Int = 0, kind: MediaKind = MediaKind.PHOTO) =
    scripted { _, _ -> Extraction.Read(MediaMetadata(width = width, height = height, orientationDegrees = degrees), kind) }

  @Test
  fun laMiniaturaExifDiventaLaMiniaturaProvvisoria() = runTest {
    nas.putFile("s", "Camera/a.jpg", NasContent.of(Jpegs.withExif(4000, 3000, thumb)))
    val d = detect("Camera/a.jpg", extractorOf(4000, 3000)) as Detection.Media
    assertThat(d.provisionalJpeg).isEqualTo(thumb)
  }

  @Test
  fun laMiniaturaProvvisoriaPortaLOrientamentoDellaFoto() = runTest {
    nas.putFile("s", "Camera/a.jpg", NasContent.of(Jpegs.withExif(4000, 3000, thumb, orientation = 6)))
    val d = detect("Camera/a.jpg", extractorOf(4000, 3000, degrees = 90)) as Detection.Media
    val provisional = checkNotNull(d.provisionalJpeg)
    assertThat(provisional.size).isEqualTo(thumb.size + 36)
    assertThat(provisional[31]).isEqualTo(6.toByte())
  }

  @Test
  fun unaMiniaturaConUnaProporzioneDiversaDallaFotoNonSiUsa() = runTest {
    nas.putFile("s", "Camera/a.jpg", NasContent.of(Jpegs.withExif(4000, 2250, thumb)))
    val d = detect("Camera/a.jpg", extractorOf(4000, 2250)) as Detection.Media
    assertThat(d.provisionalJpeg).isNull()
  }

  @Test
  fun unVideoOUnFileSenzaExifNonHaMiniaturaProvvisoria() = runTest {
    nas.putFile("s", "Camera/a.jpg", NasContent.of(Jpegs.withExif(4000, 3000, thumb)))
    assertThat((detect("Camera/a.jpg", extractorOf(4000, 3000, kind = MediaKind.VIDEO)) as Detection.Media).provisionalJpeg).isNull()
    nas.putFile("s", "Camera/b.jpg", NasContent.of(Jpegs.withExif(4000, 3000, thumbnail = null)))
    assertThat((detect("Camera/b.jpg", extractorOf(4000, 3000)) as Detection.Media).provisionalJpeg).isNull()
  }
}
