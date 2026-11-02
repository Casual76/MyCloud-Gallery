package dev.pampa.mycloudgallery.core.metadata

import android.media.MediaDataSource
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.metadata.extract.AndroidPreviewDecoder
import dev.pampa.mycloudgallery.core.metadata.extract.MediaDataSourceInputStream
import dev.pampa.mycloudgallery.core.metadata.extract.QuietMediaDataSource
import dev.pampa.mycloudgallery.core.metadata.testing.ByteArrayMediaBytes
import dev.pampa.mycloudgallery.fixtures.ImageFixtures
import java.io.IOException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Prova sul campo di Fase 10 (release candidata sull'S25): 833 stack trace su `System.err` in 85 minuti per `ReadLimitExceededException`, il tetto di lettura per file del
 * NAS. Li scrive il sistema: un'eccezione che esce da `MediaDataSource.readAt` o da un flusso letto da `BitmapFactory` attraversa il codice nativo, che la descrive. In release i
 * log hanno solo numeri e codici (C21, S6): una sorgente passata al sistema non lancia mai, un errore vale `-1` (fine dei dati).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuietMediaDataSourceTest {

  /** Una sorgente che lancia sempre, come `NasMediaBytes` oltre il tetto per file (`ReadLimitExceededException` e' una `IOException` con un messaggio fisso). */
  private class ThrowingSource(private val error: () -> Exception = { IOException("Troppi byte letti per un solo rilevamento.") }) : MediaDataSource() {
    var reads = 0
    var closed = 0

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
      reads++
      throw error()
    }

    override fun getSize(): Long = throw error()

    override fun close() {
      closed++
      throw error()
    }
  }

  @Test
  fun unaSorgenteChePassaAlSistemaNonLanciaMai_UnErroreVale_FineDeiDati() {
    val inner = ThrowingSource()
    val quiet = QuietMediaDataSource(inner)
    val buffer = ByteArray(16)
    assertThat(quiet.readAt(0, buffer, 0, buffer.size)).isEqualTo(-1)
    assertThat(quiet.size).isEqualTo(-1L)
    quiet.close()
    assertThat(inner.reads).isEqualTo(1)
    assertThat(inner.closed).isEqualTo(1)
    // Anche un errore di runtime (una lettura che fallisce dentro il NAS) non esce.
    assertThat(QuietMediaDataSource(ThrowingSource { IllegalStateException("x") }).readAt(0, buffer, 0, buffer.size)).isEqualTo(-1)
  }

  @Test
  fun unaLetturaRiuscitaPassaInvariata() {
    val bytes = ByteArray(100) { it.toByte() }
    val quiet = QuietMediaDataSource(ByteArrayMediaBytes(bytes).dataSource())
    val buffer = ByteArray(40)
    assertThat(quiet.size).isEqualTo(100L)
    assertThat(quiet.readAt(10, buffer, 0, 40)).isEqualTo(40)
    assertThat(buffer.toList()).isEqualTo(bytes.copyOfRange(10, 50).toList())
    assertThat(quiet.readAt(100, buffer, 0, 40)).isEqualTo(-1)
  }

  @Test
  fun ilFlussoDelDecodificatoreNonLanciaMai() {
    val stream = MediaDataSourceInputStream(ThrowingSource())
    val buffer = ByteArray(16)
    assertThat(stream.read(buffer, 0, buffer.size)).isEqualTo(-1)
    assertThat(stream.read()).isEqualTo(-1)
    assertThat(stream.available()).isEqualTo(0)
    stream.close()
  }

  @Test
  fun ilDecodificatoreCheTrovaIlTettoDaNullaESenzaEccezioni() {
    val image = ImageFixtures.jpeg(64, 48, 0x4080C0)
    val over = object : MediaBytes by ByteArrayMediaBytes(image) {
      override fun dataSource(): MediaDataSource = ThrowingSource()
    }
    assertThat(AndroidPreviewDecoder().decode(over, 100)).isNull()
    // Con una sorgente che funziona la stessa immagine si decodifica: la prova non e' vuota.
    assertThat(AndroidPreviewDecoder().decode(ByteArrayMediaBytes(image), 100)).isNotNull()
  }
}
