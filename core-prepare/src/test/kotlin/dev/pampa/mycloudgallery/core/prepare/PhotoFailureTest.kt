package dev.pampa.mycloudgallery.core.prepare

import android.media.MediaDataSource
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.prepare.codec.CodecAvailability
import dev.pampa.mycloudgallery.core.prepare.contract.PrepBudget
import dev.pampa.mycloudgallery.core.prepare.contract.PrepFailCode
import dev.pampa.mycloudgallery.core.prepare.contract.PrepResult
import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import dev.pampa.mycloudgallery.core.prepare.contract.PrepareSource
import dev.pampa.mycloudgallery.core.prepare.engine.DefaultPreparer
import dev.pampa.mycloudgallery.core.prepare.testing.FakeCodecs
import dev.pampa.mycloudgallery.fixtures.Fixture
import dev.pampa.mycloudgallery.fixtures.FixtureCatalog
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * I file che non si preparano (E-M10, E-M12, E-M13, D14): corrotti, troncati, falsi, senza decoder. Mai un'eccezione, mai un
 * temporaneo lasciato sul disco, e il codice giusto: `DECODE_FAILED` per un file rovinato, `DECODER_MISSING` solo quando il
 * formato e' noto e il decoder manca su questo dispositivo.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoFailureTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private val tempDir: File get() = File(tmp.root, "prep")

  private fun preparer(codecs: CodecAvailability = FakeCodecs.everything(), budget: PrepBudget = PrepBudget(256, 8)) =
    testPreparer(budget, tempDir, codecs)

  private fun prepare(bytes: ByteArray, codecs: CodecAvailability = FakeCodecs.everything()): PrepResult =
    kotlinx.coroutines.runBlocking { preparer(codecs).prepare(PrepareSource.Bytes(CountingBytes(bytes), MediaKind.PHOTO, null), PrepSpec()) }

  private fun fixture(name: String): Fixture = FixtureCatalog.all().first { it.name == name }

  private val decodableOnJvm = Regex(""".*\.(jpg|jpeg|png|webp|gif|bmp)$""")

  // --- File rovinati ---------------------------------------------------------------------------------------------

  @Test
  fun iFileRovinatiDelCatalogoDannoDecodeFailedMaiUnEccezione() {
    val broken = listOf("vuoto.jpg", "testo.jpg", "casuali.png", "troncato.jpg", "troncato-nell-exif.jpg", "solo-intestazione.jpg", "senza-sof.jpg",
      "troncato.png", "png-intestazione-rotta.png", "troncato.heic", "troncato.dng")
    for (name in broken) {
      val result = prepare(fixture(name).bytes())
      assertWithMessage(name).that(result).isEqualTo(PrepResult.Failed(PrepFailCode.DECODE_FAILED, retryable = false))
    }
    assertThat(tempDir.listFiles().orEmpty().toList()).isEmpty()
  }

  @Test
  fun unWebpConSoloLExifTroncatoSiPreparaPerche_IPixelCiSonoTutti() {
    // Il catalogo di core-metadata lo chiama "troncato" (l'EXIF e' tagliato) ma l'immagine e' intera: le anteprime sono vere.
    val result = prepare(fixture("troncato.webp").bytes())
    assertThat(result).isInstanceOf(PrepResult.Done::class.java)
  }

  @Test
  fun unDngSenzaDatiGrezziDecodificabiliEDecodeFailed() {
    // Il DNG di core-metadata ha la struttura giusta e dati di zeri: non e' un'immagine.
    val result = prepare(fixture("formato-dng.dng").bytes())
    assertThat(result).isEqualTo(PrepResult.Failed(PrepFailCode.DECODE_FAILED, retryable = false))
  }

  @Test
  fun tuttoIlCatalogoDellaFase3PassaDalPreparatoreSenzaEccezioni() {
    var prepared = 0
    var failed = 0
    for (fixture in FixtureCatalog.all()) {
      if (fixture.mime.startsWith("video/")) continue
      val result = prepare(fixture.bytes())
      when (result) {
        is PrepResult.Done -> {
          prepared++
          // Solo quello che la JVM sa decodificare puo' riuscire, e solo se il catalogo lo dice leggibile.
          assertWithMessage(fixture.name).that(fixture.name).matches(decodableOnJvm.pattern)
          // Eccezione: `troncato.webp` perde solo il chunk EXIF in coda, i pixel ci sono tutti e le anteprime sono giuste.
          if (fixture.name != "troncato.webp") assertWithMessage(fixture.name).that(fixture.expect.result).isEqualTo("read")
        }
        is PrepResult.Failed -> {
          failed++
          assertWithMessage(fixture.name).that(result.code).isAnyOf(PrepFailCode.DECODE_FAILED, PrepFailCode.DECODER_MISSING)
          assertWithMessage(fixture.name).that(result.retryable).isFalse()
        }
      }
    }
    // Il catalogo ha decine di JPEG, PNG e WebP leggibili: se nessuno riuscisse, il test non proverebbe niente.
    assertThat(prepared).isGreaterThan(40)
    assertThat(failed).isGreaterThan(5)
    assertThat(tempDir.listFiles().orEmpty().toList()).isEmpty()
  }

  // --- Decoder che manca (D14) -------------------------------------------------------------------------------------------

  @Test
  fun unHeicSenzaDecoderHevcPassaDiMano() {
    val heic = fixture("formato-heic.heic").bytes()
    val missing = prepare(heic, FakeCodecs.none())
    assertThat(missing).isEqualTo(PrepResult.Failed(PrepFailCode.DECODER_MISSING, retryable = false, decoderFamily = "c2_test"))
    assertThat((missing as PrepResult.Failed).decoderMissing).isTrue()
    // Con il decoder HEVC il fallimento e' un file rovinato (qui l'immagine e' finta), non un decoder mancante.
    val corrupted = prepare(heic, FakeCodecs("video/hevc"))
    assertThat(corrupted).isEqualTo(PrepResult.Failed(PrepFailCode.DECODE_FAILED, retryable = false))
  }

  @Test
  fun unJpegXlEUnFormatoNotoSenzaDecoderQui() {
    val jxl = fixture("formato-sconosciuto.jxl").bytes()
    assertThat(prepare(jxl)).isEqualTo(PrepResult.Failed(PrepFailCode.DECODER_MISSING, retryable = false, decoderFamily = "c2_test"))
    // Testo con estensione di immagine: non e' un formato, e' un file falso.
    assertThat(prepare("<html>404</html>".toByteArray())).isEqualTo(PrepResult.Failed(PrepFailCode.DECODE_FAILED, retryable = false))
  }

  // --- L'origine ----------------------------------------------------------------------------------------------------

  private class FailingBytes(override val size: Long, private val failure: () -> ByteArray) : MediaBytes {
    override suspend fun read(range: LongRange): ByteArray = failure()

    override fun dataSource(): MediaDataSource = throw UnsupportedOperationException()
  }

  @Test
  fun ilNasCheNonRispondeNonEUnFileRovinato() = runTest {
    val bytes = FailingBytes(100_000) { throw IOException("connessione persa") }
    val result = preparer().prepare(PrepareSource.Bytes(bytes, MediaKind.PHOTO, null), PrepSpec())
    assertThat(result).isEqualTo(PrepResult.Failed(PrepFailCode.NAS_UNREACHABLE, retryable = true))
  }

  @Test
  fun unFileCheSiAccorciaDuranteLaLetturaEIlFileECambiato() = runTest {
    val bytes = FailingBytes(100_000) { ByteArray(10) }
    val result = preparer().prepare(PrepareSource.Bytes(bytes, MediaKind.PHOTO, null), PrepSpec())
    assertThat(result).isEqualTo(PrepResult.Failed(PrepFailCode.SOURCE_CHANGED, retryable = true))
  }

  @Test
  fun unOrigineSenzaByteEUnFileRovinatoENonUnErrore() = runTest {
    val result = preparer().prepare(PrepareSource.Bytes(CountingBytes(ByteArray(0)), MediaKind.PHOTO, null), PrepSpec())
    assertThat(result).isEqualTo(PrepResult.Failed(PrepFailCode.DECODE_FAILED, retryable = false))
  }

  @Test
  fun unFileLocaleCheNonCePiuEUnProblemaDelDisco_NonDelMedia() = runTest {
    val result = preparer().prepare(PrepareSource.Local(File(tmp.root, "non-esiste.jpg"), MediaKind.PHOTO, null), PrepSpec())
    assertThat(result).isEqualTo(PrepResult.Failed(PrepFailCode.INTERRUPTED, retryable = true))
  }

  @Test
  fun annullareIlLavoroNonLasciaTemporaneiENonDiventaUnFallimento() = runTest(timeout = 60.seconds) {
    val budget = PrepBudget(256, 8, inMemoryMaxBytes = 1_000, maxTempBytes = 50_000_000)
    val hanging = object : MediaBytes {
      override val size: Long = 100_000

      override suspend fun read(range: LongRange): ByteArray {
        if (range.first == 0L) return ByteArray((range.last - range.first + 1).toInt()) else awaitCancellation()
      }

      override fun dataSource(): MediaDataSource = throw UnsupportedOperationException()
    }
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      preparer(budget = budget).prepare(PrepareSource.Bytes(hanging, MediaKind.PHOTO, null), PrepSpec())
    }
    job.cancel()
    job.join()
    assertThat(job.isCancelled).isTrue()
    assertThat(tempDir.listFiles().orEmpty().toList()).isEmpty()
  }
}
