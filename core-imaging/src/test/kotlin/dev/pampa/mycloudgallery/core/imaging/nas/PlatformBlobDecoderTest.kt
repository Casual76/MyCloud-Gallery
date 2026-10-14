package dev.pampa.mycloudgallery.core.imaging.nas

import android.graphics.Color
import android.os.CancellationSignal
import android.os.OperationCanceledException
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.TestImages
import dev.pampa.mycloudgallery.core.imaging.nas.NasTestSupport.center
import dev.pampa.mycloudgallery.core.imaging.nas.NasTestSupport.near
import java.nio.ByteBuffer
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * La decodifica vera dei blob (Skia nativo): JPEG e WebP alla taglia della cella, mai ingranditi, buffer di ogni tipo
 * (anche quelli in sola lettura che `ImageDecoder` non saprebbe leggere), e i blob ostili che dichiarano dimensioni
 * enormi o non sono immagini.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlatformBlobDecoderTest {

  private fun decode(bytes: ByteBuffer, px: Int) = PlatformBlobDecoder.decode(bytes, px, CancellationSignal())

  @Test
  fun unJpegSiRiduceAllaTagliaDellaCella_senzaChiederePiuDiCosaServe() {
    val big = ByteBuffer.wrap(NasTestSupport.jpeg(Color.RED, 400, 300))
    val s = decode(big, 160)!!
    assertThat(maxOf(s.width, s.height)).isEqualTo(160)
    // Le proporzioni restano (400:300 = 4:3).
    assertThat(s.width.toDouble() / s.height).isWithin(0.05).of(4.0 / 3.0)
    assertThat(near(center(s), Color.RED)).isTrue()
    assertThat(s.config).isNotNull()
  }

  @Test
  fun unaMiniaturaPiuPiccolaDellaCellaNonSiIngrandisce() {
    val small = decode(ByteBuffer.wrap(NasTestSupport.jpeg(Color.GREEN, 50, 40)), 400)!!
    assertThat(small.width).isEqualTo(50)
    assertThat(small.height).isEqualTo(40)
  }

  @Test
  fun unWebpDellaReplicaSiDecodifica() {
    val s = decode(ByteBuffer.wrap(NasTestSupport.webp(Color.BLUE, 200, 267)), 320)!!
    assertThat(s.width).isEqualTo(200)
    assertThat(s.height).isEqualTo(267)
    assertThat(near(center(s), Color.BLUE)).isTrue()
  }

  @Test
  fun l_orientamentoExifSiApplicaAllaMiniatura() {
    // La miniatura provvisoria e' un JPEG: se dichiara un orientamento la cella la vede dritta.
    val rotated = TestImages.jpegWithOrientation(orientation = 6, width = 32, height = 16)
    val s = decode(ByteBuffer.wrap(rotated), 160)!!
    assertThat(s.width).isEqualTo(16)
    assertThat(s.height).isEqualTo(32)
  }

  @Test
  fun ognTipoDiBufferVaBene_ancheInSolaLetturaEConPosizione() {
    val bytes = NasTestSupport.jpeg(Color.RED, 64, 48)
    // Sola lettura sull'heap: quello della replica finta (e' l'unico che ImageDecoder non leggerebbe da se').
    val readOnly = ByteBuffer.wrap(bytes).asReadOnlyBuffer()
    assertThat(decode(readOnly, 160)).isNotNull()
    // Diretto: come i pacchi mappati.
    val direct = ByteBuffer.allocateDirect(bytes.size).put(bytes).flip() as ByteBuffer
    assertThat(decode(direct, 160)).isNotNull()
    // Una fetta con un prefisso davanti (un blob dentro un pacco): conta solo da `position` a `limit`.
    val pack = ByteBuffer.allocate(10 + bytes.size + 5)
    pack.position(10)
    pack.put(bytes)
    pack.position(10).limit(10 + bytes.size)
    val slice = decode(pack.slice().asReadOnlyBuffer(), 160)
    assertThat(slice).isNotNull()
    // Il buffer di chi chiama non si tocca (e' condiviso con la replica).
    assertThat(readOnly.position()).isEqualTo(0)
  }

  @Test
  fun unBlobOstileCheDichiaraDimensioniEnormiNonDiventaUnaBitmap() {
    // 60.000 x 60.000 = 3,6 miliardi di pixel dichiarati, nessun dato: l'intestazione basta a rifiutarlo.
    assertThat(decode(ByteBuffer.wrap(NasTestSupport.hostilePng(60_000, 60_000)), 160)).isNull()
    // Un lato oltre il tetto.
    assertThat(decode(ByteBuffer.wrap(NasTestSupport.hostilePng(PlatformBlobDecoder.MAX_SIDE + 1, 10)), 160)).isNull()
  }

  @Test
  fun nonEUnImmagine_oEVuoto_oETroncato_nullaSenzaEccezioni() {
    assertThat(decode(ByteBuffer.wrap(ByteArray(0)), 160)).isNull()
    assertThat(decode(ByteBuffer.wrap(ByteArray(64) { it.toByte() }), 160)).isNull()
    assertThat(decode(ByteBuffer.wrap("MZ not an image at all".toByteArray()), 160)).isNull()
    val good = NasTestSupport.jpeg(Color.RED, 200, 150)
    // Un JPEG troncato a meta': o si decodifica quanto c'e' o null; mai un'eccezione.
    decode(ByteBuffer.wrap(good.copyOf(good.size / 2)), 160)
  }

  @Test
  fun unSegnaleGiaScattatoFermaLaDecodifica() {
    val signal = CancellationSignal().also { it.cancel() }
    assertThrows(OperationCanceledException::class.java) {
      PlatformBlobDecoder.decode(ByteBuffer.wrap(NasTestSupport.jpeg(Color.RED)), 160, signal)
    }
  }
}
