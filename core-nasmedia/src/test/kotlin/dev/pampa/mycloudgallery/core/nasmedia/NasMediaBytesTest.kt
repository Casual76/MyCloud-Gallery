package dev.pampa.mycloudgallery.core.nasmedia

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nasmedia.detect.NasMediaBytes
import dev.pampa.mycloudgallery.core.nasmedia.detect.ReadBudget
import dev.pampa.mycloudgallery.core.nasmedia.detect.ReadCapReachedException
import dev.pampa.mycloudgallery.core.nasmedia.detect.ReadLimitExceededException
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** `NasMediaBytes`: i byte di un file del NAS per core-metadata, con regioni tenute in memoria e tetti. */
@RunWith(AndroidJUnit4::class)
class NasMediaBytesTest {

  private val nas = InMemoryNasMedia()
  private val size = 500_000
  private val content = ByteArray(size) { i -> ((i * 7 + i / 251) % 253).toByte() }

  init {
    nas.addShare("s")
    nas.putFile("s", "a.bin", NasContent.of(content))
  }

  private suspend fun open(): NasRandomFile =
    (nas.reader.open("s", SharePath.parse("a.bin"), Purpose.BACKGROUND) as NasResult.Ok).value

  @Test
  fun leRegioniTenuteInMemoriaNonTornanoSulNas() = runTest {
    val bytes = NasMediaBytes(open())
    bytes.pin(0L until 65_536)
    bytes.pin(434_464L until size.toLong())
    val after = nas.bytesRead.get()
    assertThat(after).isEqualTo(65_536L + 65_536L)

    assertThat(bytes.read(100L until 5_000L)).isEqualTo(content.copyOfRange(100, 5_000))
    assertThat(bytes.read(450_000L until size.toLong())).isEqualTo(content.copyOfRange(450_000, size))
    assertThat(nas.bytesRead.get()).isEqualTo(after)
  }

  @Test
  fun unaLetturaAcavalloDiUnaRegioneLeggeSoloIlBuco() = runTest {
    val bytes = NasMediaBytes(open())
    bytes.pin(0L until 65_536)
    val before = nas.bytesRead.get()
    assertThat(bytes.read(60_000L until 70_000L)).isEqualTo(content.copyOfRange(60_000, 70_000))
    // Solo i 4.464 byte oltre la regione sono arrivati dal NAS.
    assertThat(nas.bytesRead.get() - before).isEqualTo(4_464)
    assertThat(bytes.bytesRead).isEqualTo(65_536L + 4_464L)
  }

  @Test
  fun gliIntervalliFuoriDalFileSiAccorciano() = runTest {
    val bytes = NasMediaBytes(open())
    assertThat(bytes.read(LongRange.EMPTY)).isEmpty()
    assertThat(bytes.read(size.toLong()..size + 100L)).isEmpty()
    assertThat(bytes.read(-50L..9L)).isEqualTo(content.copyOfRange(0, 10))
    assertThat(bytes.read((size - 5L)..(size + 100L))).isEqualTo(content.copyOfRange(size - 5, size))
  }

  @Test
  fun laMediaDataSourceLeggeAPagineEFinisceConMenoUno() = runTest {
    val bytes = NasMediaBytes(open())
    val source = bytes.dataSource()
    assertThat(source.size).isEqualTo(size.toLong())
    val buffer = ByteArray(1000)
    val n = source.readAt(70_000, buffer, 0, 1000)
    assertThat(n).isEqualTo(1000)
    assertThat(buffer).isEqualTo(content.copyOfRange(70_000, 71_000))
    // Una lettura che passa il confine fra due pagine da 64 KiB.
    val across = ByteArray(2000)
    assertThat(source.readAt(65_000, across, 0, 2000)).isEqualTo(2000)
    assertThat(across).isEqualTo(content.copyOfRange(65_000, 67_000))
    // In fondo al file: meno byte del chiesto, poi -1.
    val tail = ByteArray(1000)
    assertThat(source.readAt(size - 300L, tail, 0, 1000)).isEqualTo(300)
    assertThat(source.readAt(size.toLong(), tail, 0, 1000)).isEqualTo(-1)
    assertThat(source.readAt(-1, tail, 0, 10)).isEqualTo(-1)
    // La stessa pagina riletta non torna sul NAS.
    val before = nas.bytesRead.get()
    source.readAt(70_100, buffer, 0, 100)
    assertThat(nas.bytesRead.get()).isEqualTo(before)
  }

  @Test
  fun ilTettoPerFileFermaIlDecodificatoreCheLeggeTutto() = runTest {
    val bytes = NasMediaBytes(open(), perFileLimit = 100_000)
    bytes.read(0L until 60_000L)
    assertThrows(ReadLimitExceededException::class.java) { kotlinx.coroutines.runBlocking { bytes.read(60_000L until 120_000L) } }
    assertThat(bytes.limitHit).isTrue()
    assertThat(bytes.capHit).isFalse()
    assertThat(bytes.bytesRead).isEqualTo(60_000)
  }

  @Test
  fun ilTettoDelGiroPrenotaPrimaEDiceDiNo() = runTest {
    val budget = ReadBudget(70_000)
    val bytes = NasMediaBytes(open(), budget)
    bytes.read(0L until 60_000L)
    assertThrows(ReadCapReachedException::class.java) { kotlinx.coroutines.runBlocking { bytes.read(60_000L until 80_000L) } }
    assertThat(bytes.capHit).isTrue()
    assertThat(budget.taken).isEqualTo(60_000)
    assertThat(budget.exhausted).isFalse() // restano 10.000 byte: non e' esaurito, e' che quella lettura non ci stava
    assertThat(nas.bytesRead.get()).isEqualTo(60_000)
  }

  @Test
  fun unaLetturaPiuCortaDelPrevistoRestituisceLaDifferenzaAlBudget() = runTest {
    val budget = ReadBudget(10_000_000)
    val bytes = NasMediaBytes(open(), budget)
    // Il file finisce a 500.000: il resto della richiesta non e' mai arrivato.
    val data = bytes.pin((size - 1_000L)..(size + 5_000L))
    assertThat(data.size).isEqualTo(1_000)
    assertThat(budget.taken).isEqualTo(1_000)
  }

  @Test
  fun unErroreDelNasSiRicordaESiRilancia() = runTest {
    nas.fault = FakeNasFault { op, _, _ -> if (op == FakeNasOp.READ) NasError.FileBusy else null }
    val bytes = NasMediaBytes(open())
    assertThrows(NasIoException::class.java) { kotlinx.coroutines.runBlocking { bytes.read(0L until 100L) } }
    assertThat(bytes.failure).isEqualTo(NasError.FileBusy)
    assertThat(bytes.bytesRead).isEqualTo(0)
    nas.fault = null
  }
}
