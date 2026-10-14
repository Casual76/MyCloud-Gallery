package dev.pampa.mycloudgallery.core.nasmedia

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.metadata.extract.DefaultMetadataExtractor
import dev.pampa.mycloudgallery.core.metadata.extract.Preview
import dev.pampa.mycloudgallery.core.metadata.extract.PreviewDecoder
import dev.pampa.mycloudgallery.core.metadata.testing.FakeVideoProbe
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasMetaResult
import dev.pampa.mycloudgallery.core.nasmedia.detect.Detection
import dev.pampa.mycloudgallery.core.nasmedia.detect.NasDetector
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Il rilevamento con l'estrattore **vero** della Fase 3 (`DefaultMetadataExtractor`) sopra `NasMediaBytes`: quanti byte
 * legge davvero dal NAS una foto, e che cosa succede se qualcuno configura l'estrattore per rileggere tutta l'immagine.
 */
@RunWith(AndroidJUnit4::class)
class NasDetectorRealExtractorTest {

  private val nas = InMemoryNasMedia(nasNow = { 2_000_000_000_000L })
  private val places = FakePlaces()
  private val thumb = Jpegs.thumbnail(160, 120, padding = 6_000)

  init {
    nas.addShare("s")
  }

  /** Un decodificatore finto: le miniature in memoria si decodificano; il file intero si legge tutto dalla `MediaDataSource`. */
  private class ReadingDecoder : PreviewDecoder {
    var streamed = 0L
    var streamFailure: Throwable? = null

    private fun preview() = Preview(8, 6, ByteArray(8 * 6 * 4) { 120 })

    override fun decode(data: ByteArray, maxSide: Int): Preview? = preview()

    override fun decode(bytes: MediaBytes, maxSide: Int): Preview? {
      val source = bytes.dataSource()
      val buffer = ByteArray(32 * 1024)
      var position = 0L
      try {
        while (position < bytes.size) {
          val n = source.readAt(position, buffer, 0, buffer.size)
          if (n <= 0) break
          position += n
          streamed = position
        }
      } catch (e: java.io.IOException) {
        streamFailure = e
        return null
      }
      return preview()
    }
  }

  private fun extractor(decoder: PreviewDecoder, maxFullDecodeBytes: Long) = DefaultMetadataExtractor(
    geocoder = places,
    zones = places,
    videoProbe = FakeVideoProbe(null),
    previewDecoder = decoder,
    clock = { 2_000_000_000_000L },
    blocking = Dispatchers.Unconfined,
    maxFullDecodeBytes = maxFullDecodeBytes,
  )

  private suspend fun open(path: String): NasRandomFile =
    (nas.reader.open("s", SharePath.parse(path), Purpose.BACKGROUND) as NasResult.Ok).value

  @Test
  fun unaFotoConLEstrattoreVeroLeggeSoloTestaECodaEProduceLaMiniaturaProvvisoria() = runTest {
    // 3 MB con un EXIF vero: dimensioni nel SOF, orientamento 6 e la miniatura 160x120 nell'IFD1.
    val jpeg = Jpegs.withExif(4000, 3000, thumb, orientation = 6, bodyBytes = 3_000_000)
    nas.putFile("s", "Camera/IMG_1.jpg", NasContent.of(jpeg), modifiedAt = 1_700_000_000_000)
    val decoder = ReadingDecoder()
    val detector = NasDetector(extractor(decoder, maxFullDecodeBytes = 0)) { ROME }

    val d = open("Camera/IMG_1.jpg").use { detector.detect(it, "IMG_1.jpg", "Camera", 1_700_000_000_000) } as Detection.Media

    val meta = d.meta as NasMetaResult.Read
    assertThat(meta.kind).isEqualTo(MediaKind.PHOTO)
    assertThat(meta.metadata.width).isEqualTo(4000)
    assertThat(meta.metadata.height).isEqualTo(3000)
    assertThat(meta.metadata.orientationDegrees).isEqualTo(90)
    assertThat(meta.metadata.mimeType).isEqualTo("image/jpeg")
    assertThat(meta.metadata.thumbHash).isNotNull() // dalla miniatura incorporata: nessun file letto per intero
    // La miniatura provvisoria porta l'orientamento 6 della foto (APP1 con una voce).
    assertThat(d.provisionalJpeg).isNotNull()
    assertThat(d.provisionalJpeg!!.size).isEqualTo(thumb.size + 36)
    // 3 MB di file, ~190 KB letti: testa (64 KiB) piu' la pagina della coda.
    assertThat(d.bytesRead).isAtMost(64L * 1024 + 128L * 1024)
    assertThat(nas.bytesRead.get()).isEqualTo(d.bytesRead)
    assertThat(decoder.streamed).isEqualTo(0)
  }

  @Test
  fun seQualcunoConfiguraLEstrattoreARileggereTuttaLImmagineIlTettoLoFermaENonDiventaRovinato() = runTest {
    // Una foto senza miniatura valida: il ripiego dell'estrattore decodificherebbe l'intero file dal NAS (16 MiB di default).
    val jpeg = Jpegs.withExif(4000, 3000, thumbnail = null, bodyBytes = 6_000_000)
    nas.putFile("s", "Camera/IMG_2.jpg", NasContent.of(jpeg), modifiedAt = 1_700_000_000_000)
    val decoder = ReadingDecoder()
    val detector = NasDetector(extractor(decoder, maxFullDecodeBytes = 16L * 1024 * 1024)) { ROME }

    val d = open("Camera/IMG_2.jpg").use { detector.detect(it, "IMG_2.jpg", "Camera", 1_700_000_000_000) } as Detection.Media

    // Il tetto per file (2 MiB) ha fermato la lettura: l'estrattore ha detto "rovinato", ma il file non lo e'.
    assertThat(decoder.streamFailure).isNotNull()
    assertThat(d.meta).isNull()
    assertThat(d.bytesRead).isAtMost(NasDetector.IMAGE_LIMIT)
    assertThat(d.fingerprint.size).isEqualTo(jpeg.size.toLong())
  }

  @Test
  fun unFileTroncatoEUnMediaRovinatoNonUnErroreDelNas() = runTest {
    val jpeg = Jpegs.withExif(4000, 3000, thumb, bodyBytes = 300_000)
    // Tagliato a meta': il SOS c'e' ma il file non finisce con l'EOI.
    nas.putFile("s", "Camera/IMG_3.jpg", NasContent.of(jpeg.copyOf(150_000)), modifiedAt = 1_700_000_000_000)
    val detector = NasDetector(extractor(ReadingDecoder(), maxFullDecodeBytes = 0)) { ROME }
    val d = open("Camera/IMG_3.jpg").use { detector.detect(it, "IMG_3.jpg", "Camera", 1_700_000_000_000) } as Detection.Media
    assertThat(d.meta).isEqualTo(NasMetaResult.Failed("TRUNCATED"))
  }

  @Test
  fun unFileDiTestoChiamatoJpgNonEUnMedia() = runTest {
    nas.putFile("s", "Camera/testo.jpg", NasContent.of("niente da vedere qui".repeat(500).toByteArray()), modifiedAt = 1_700_000_000_000)
    val detector = NasDetector(extractor(ReadingDecoder(), maxFullDecodeBytes = 0)) { ROME }
    val d = open("Camera/testo.jpg").use { detector.detect(it, "testo.jpg", "Camera", 1_700_000_000_000) }
    assertThat(d).isInstanceOf(Detection.NotMedia::class.java)
  }
}
