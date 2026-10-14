package dev.pampa.mycloudgallery.core.imaging.viewer

import android.graphics.ImageDecoder
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.TestImages
import dev.pampa.mycloudgallery.core.imaging.decode.ImageProbe
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** I due modi in cui il viewer legge un originale del NAS: un file temporaneo e un blob della replica. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalImageBytesTest {

  @get:Rule
  val folder = TemporaryFolder()

  private val jpeg = TestImages.jpeg(TestImages.quadrants(64, 32))

  private fun file(bytes: ByteArray = jpeg): File = folder.newFile().apply { writeBytes(bytes) }

  /**
   * Il flusso e il peso. La sorgente di `ImageDecoder` (`createSource(File)`) non si prova qui: Robolectric non sa
   * decodificare da un `File`; sul telefono e' la strada normale ed e' un controllo del campo (F10).
   */
  @Test
  fun unFileSiLeggeComeFlussoEHaIlSuoPeso() {
    val bytes = FileImageBytes(file())

    assertThat(bytes.sizeBytes).isEqualTo(jpeg.size.toLong())
    assertThat(bytes.openStream().use { it.readBytes() }).isEqualTo(jpeg)
    assertThat(ImageProbe.probe(BufferImageBytes(ByteBuffer.wrap(bytes.openStream().use { it.readBytes() }))).uprightWidth).isEqualTo(64)
  }

  @Test
  fun unFileSparitoDiceCheNonCePiu() {
    val gone = file()
    val bytes = FileImageBytes(gone)
    gone.delete()

    assertThrows(FileNotFoundException::class.java) { bytes.openStream() }
  }

  @Test
  fun unBlobDellaReplicaSiDecodificaAncheSeIlBufferEDiSolaLettura() {
    val buffer = ByteBuffer.wrap(jpeg).asReadOnlyBuffer()
    val bytes = BufferImageBytes(buffer)

    assertThat(bytes.sizeBytes).isEqualTo(jpeg.size.toLong())
    assertThat(bytes.openStream().use { it.readBytes() }).isEqualTo(jpeg)
    val bitmap = ImageDecoder.decodeBitmap(bytes.decoderSource())
    assertThat(bitmap.width).isEqualTo(64)
    // Il buffer del chiamante non si muove: la mappatura del pacco resta intatta.
    assertThat(buffer.position()).isEqualTo(0)
  }
}
