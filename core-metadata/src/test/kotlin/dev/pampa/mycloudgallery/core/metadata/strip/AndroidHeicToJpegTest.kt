package dev.pampa.mycloudgallery.core.metadata.strip

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.metadata.contract.StripOutcome
import dev.pampa.mycloudgallery.core.metadata.contract.WriteFailure
import dev.pampa.mycloudgallery.core.metadata.extract.AndroidPreviewDecoder
import java.io.File
import java.io.IOException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 5B, P11 (D26, D28, L41): la conversione HEIC → JPEG ([AndroidHeicToJpeg]). Su JVM non gira `ImageDecoder` ne' c'e' il decodificatore
 * HEVC, quindi qui si prova tutto **tranne** la sorgente dei pixel: con una sorgente di bitmap di prova (`BitmapFactory`, che su JVM legge
 * JPEG, PNG e WebP) si dimostra che il JPEG che esce non ha nessun metadato e si decodifica, che un file non decodificabile non e' un successo e
 * che un guasto non lascia niente. Che i veri HEIC dell'S25 si decodifichino lo prova F42 sul campo.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidHeicToJpegTest {

  @get:Rule val folder = TemporaryFolder()

  /** Pixel dal decodificatore di immagini della JVM: stessa forma della sorgente vera, senza HEVC. */
  private val factory = BitmapSource { src, _ -> BitmapFactory.decodeFile(src.path) }

  @Test
  fun unaBitmapEscePerJpegSenzaNessunMetadato() {
    val source = StripFixtures.write(folder.root, "a.img", StripFixtures.jpeg()) // ha EXIF e XMP con GPS: la bitmap non li porta
    val out = File(folder.root, "out.jpg").apply { createNewFile() }

    val result = AndroidHeicToJpeg(source = factory).convert(source, out)

    assertThat(result).isEqualTo(HeicConversion.Done)
    val bytes = out.readBytes()
    assertThat(AndroidPreviewDecoder().decode(bytes, 64)).isNotNull()
    assertThat(ExifInterface(out).latLong).isNull()
    assertThat(ExifInterface(out).getAttribute(ExifInterface.TAG_MODEL)).isNull()
    assertThat(StripFixtures.contains(bytes, "GPSLatitude")).isFalse()
    assertThat(LocationTraceRecheck().check(out, "image/jpeg")).isEqualTo(LocationVerdict.CLEAR)
  }

  @Test
  fun unFileCheIlSistemaNonDecodificaNonEUnSuccesso() {
    val source = StripFixtures.write(folder.root, "a.heic", StripFixtures.heic()) // pixel finti
    val out = File(folder.root, "out.jpg").apply { createNewFile() }

    assertThat(AndroidHeicToJpeg(source = factory).convert(source, out)).isEqualTo(HeicConversion.NotAvailable)
    // Su JVM la sorgente vera non gira: ImageDecoder dice "solo su Android", e cio' non e' un successo.
    assertThat(AndroidHeicToJpeg().convert(source, out)).isNotEqualTo(HeicConversion.Done)
  }

  @Test
  fun unaSorgenteCheNonLeggeFalliscePulita() {
    val out = File(folder.root, "out.jpg").apply { createNewFile() }

    val unreadable = BitmapSource { _, _ -> throw IOException("illeggibile") }

    assertThat(AndroidHeicToJpeg(source = unreadable).convert(File(folder.root, "manca"), out))
      .isEqualTo(HeicConversion.Failed(WriteFailure.SOURCE_UNREADABLE))
  }

  @Test
  fun ilTettoDeiPixelRiduceMantenendoLeProporzioni() {
    assertThat(ImageDecoderBitmaps.scaledSize(4000, 3000, 32_000_000)).isNull()
    val (w, h) = ImageDecoderBitmaps.scaledSize(8160, 6120, 32_000_000)!!
    assertThat(w.toLong() * h).isAtMost(32_000_000L)
    assertThat(w.toLong() * h).isAtLeast(31_000_000L)
    assertThat(w.toDouble() / h).isWithin(0.01).of(8160.0 / 6120.0)
    // Un lato non scende sotto 1 pixel: una striscia estrema mantiene le proporzioni invece di sparire.
    assertThat(ImageDecoderBitmaps.scaledSize(100_000, 1, 10)).isEqualTo(1_000 to 1)
    assertThat(ImageDecoderBitmaps.scaledSize(0, 0, -1)).isNull()
  }

  @Test
  fun inUnoStripperUnHeicDiPixelFintiSiFermaENonLasciaNulla() {
    val stripper = DefaultLocationStripper(StripFixtures.rechecks(), heic = AndroidHeicToJpeg())
    val source = StripFixtures.write(folder.root, "a.heic", StripFixtures.heic())
    val out = File(folder.root, "out.jpg")

    val result = stripper.strip(source, out, "image/heic")

    assertThat(result).isNotInstanceOf(StripOutcome.Stripped::class.java)
    assertThat(out.exists()).isFalse()
  }
}
