package dev.pampa.mycloudgallery.feature.viewer

import android.app.Application
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.viewer.testing.ScriptedNasReader
import dev.pampa.mycloudgallery.core.imaging.viewer.NasMediaLocation
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.viewer.video.NasVideoDataSource
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Il `DataSource` di Media3 sopra `NasMediaReader` (P8, D19; riga P8 del §4.4: sorgenti **lente, ferme e irraggiungibili**,
 * E-V1, E-V5, E-L4, E-M7). Thread veri, perche' e' proprio cosi' che ExoPlayer lo usa: un caricatore che chiama `open` e
 * `read` bloccanti e, per annullare, interrompe il thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(UnstableApi::class)
class NasVideoDataSourceTest {

  private val nas = InMemoryNasMedia()
  private lateinit var reader: ScriptedNasReader
  private val locations = HashMap<Long, NasMediaLocation?>()
  private val ref = MediaRef.Nas(locationId = 7, contentKey = 77, mime = "video/mp4")
  private val uri: Uri = Uri.parse("nasmedia://v/7.mp4")

  private val size = 3_000_000L

  @Before
  fun setUp() {
    nas.addShare("test")
    reader = ScriptedNasReader(nas.reader)
    put(size)
  }

  private fun put(bytes: Long) {
    val path = SharePath.of("Video", "clip.mp4")
    nas.putFile("test", path.value, NasContent.virtual(bytes, seed = 5, head = byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())))
    locations[7] = NasMediaLocation("test", path, bytes)
  }

  private fun source(first: Int = NasVideoDataSource.FIRST_CHUNK_BYTES, max: Int = NasVideoDataSource.MAX_CHUNK_BYTES) =
    NasVideoDataSource(ref, uri, reader, { locations[it.locationId] }, firstChunk = first, maxChunk = max)

  private fun spec(position: Long = 0, length: Long = C.LENGTH_UNSET.toLong(), at: Uri = uri) =
    DataSpec.Builder().setUri(at).setPosition(position).setLength(length).build()

  private fun NasVideoDataSource.readAll(buffer: Int = 64 * 1024, limit: Long = Long.MAX_VALUE): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(buffer)
    while (out.size() < limit) {
      val n = read(chunk, 0, chunk.size)
      if (n == C.RESULT_END_OF_INPUT) break
      out.write(chunk, 0, n)
    }
    return out.toByteArray()
  }

  private fun reasonOf(block: () -> Unit): Int = (assertThrows(DataSourceException::class.java) { block() }).reason

  // --- La strada normale ----------------------------------------------------------------------------------------------

  @Test
  fun leggeTuttoIlFileInOrdineEDichiaraLaLunghezza() {
    val ds = source()

    val length = ds.open(spec())
    val bytes = ds.readAll()

    assertThat(length).isEqualTo(size)
    assertThat(bytes).isEqualTo(nas.bytes("test", "Video/clip.mp4"))
    assertThat(ds.read(ByteArray(10), 0, 10)).isEqualTo(C.RESULT_END_OF_INPUT)
    ds.close()
    assertThat(reader.openFiles.get()).isEqualTo(0)
    assertThat(ds.uri).isEqualTo(uri)
  }

  @Test
  fun unaLetturaDaZeroByteNonLeggeEUnaPiccolaBastaDiUnBlocco() {
    val ds = source()
    ds.open(spec())

    assertThat(ds.read(ByteArray(10), 0, 0)).isEqualTo(0)
    assertThat(ds.chunksRead).isEqualTo(0)
    assertThat(ds.read(ByteArray(100), 0, 100)).isEqualTo(100)
    // Il lettore ha chiesto cento byte: dal NAS e' arrivato il primo blocco, non il file.
    assertThat(ds.chunksRead).isEqualTo(1)
    assertThat(ds.bytesRequested).isEqualTo(NasVideoDataSource.FIRST_CHUNK_BYTES.toLong())
    ds.close()
  }

  @Test
  fun laLetturaAnticipataParteDa256KiBERaddoppiaFinoA4MiB() {
    put(40L * 1024 * 1024)
    val ds = source()
    ds.open(spec())
    val chunk = ByteArray(1 shl 20)
    val requestedAfterEachChunk = mutableListOf<Long>()
    var seenChunks = 0
    var consumed = 0L
    while (seenChunks < 8) {
      val n = ds.read(chunk, 0, chunk.size)
      consumed += n
      if (ds.chunksRead != seenChunks) {
        seenChunks = ds.chunksRead
        requestedAfterEachChunk += ds.bytesRequested
      }
    }

    // 256 KiB, 512 KiB, 1 MiB, 2 MiB, 4 MiB, 4 MiB, 4 MiB, 4 MiB: cumulati.
    val kib = 1024L
    assertThat(requestedAfterEachChunk).containsExactly(
      256 * kib,
      256 * kib + 512 * kib,
      256 * kib + 512 * kib + 1024 * kib,
      256 * kib + 512 * kib + 1024 * kib + 2048 * kib,
      3840 * kib + 4096 * kib,
      3840 * kib + 2 * 4096 * kib,
      3840 * kib + 3 * 4096 * kib,
      3840 * kib + 4 * 4096 * kib,
    ).inOrder()
    assertThat(consumed).isLessThan(ds.bytesRequested)
    ds.close()
  }

  @Test
  fun unVideoDa9GBNonSiScaricaIntero_EM7() {
    put(9_400_000_000L)
    val ds = source()

    ds.open(spec())
    ds.readAll(limit = 5L * 1024 * 1024)
    ds.close()

    // Indice e primi MiB: il resto del video e' rimasto sul NAS.
    assertThat(reader.bytesDelivered.get()).isLessThan(16L * 1024 * 1024)
    assertThat(reader.opens.get()).isEqualTo(1)
  }

  @Test
  fun unSaltoApreInUnaPosizioneEPartePiccoloDiNuovo() {
    val whole = nas.bytes("test", "Video/clip.mp4")!!
    val ds = source()

    val length = ds.open(spec(position = 1_000_003, length = 100_000))
    val got = ds.readAll()
    ds.close()
    val again = source()
    val tail = again.open(spec(position = size - 500))
    val rest = again.readAll()
    again.close()

    assertThat(length).isEqualTo(100_000)
    assertThat(got).isEqualTo(whole.copyOfRange(1_000_003, 1_100_003))
    // Non si legge mai piu' di quanto serve: il blocco e' ridotto alla lunghezza chiesta.
    assertThat(ds.bytesRequested).isEqualTo(100_000)
    assertThat(tail).isEqualTo(500)
    assertThat(rest).isEqualTo(whole.copyOfRange((size - 500).toInt(), size.toInt()))
    assertThat(reader.openFiles.get()).isEqualTo(0)
  }

  @Test
  fun iByteLettiSiContanoPerIlListenerDiMedia3() {
    val ds = source()
    val started = AtomicInteger(0)
    val transferred = AtomicLong(0L)
    val ended = AtomicInteger(0)
    ds.addTransferListener(object : TransferListener {
      override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

      override fun onTransferStart(source: androidx.media3.datasource.DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        started.incrementAndGet()
      }

      override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        transferred.addAndGet(bytesTransferred.toLong())
      }

      override fun onTransferEnd(source: androidx.media3.datasource.DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        ended.incrementAndGet()
      }
    })

    ds.open(spec())
    ds.readAll()
    ds.close()
    ds.close()

    assertThat(started.get()).isEqualTo(1)
    assertThat(transferred.get()).isEqualTo(size)
    assertThat(ended.get()).isEqualTo(1)
  }

  // --- Sorgenti lente -------------------------------------------------------------------------------------------------

  @Test
  fun unaSorgenteLentaDaComunqueIByteGiusti_EV1() {
    reader.openDelayMs = 40
    reader.chunkDelayMs = 25
    val ds = source(first = 100_000, max = 400_000)
    val started = System.nanoTime()

    ds.open(spec(length = 1_000_000))
    val bytes = ds.readAll()
    ds.close()

    val elapsedMs = (System.nanoTime() - started) / 1_000_000
    assertThat(bytes).isEqualTo(nas.bytes("test", "Video/clip.mp4")!!.copyOf(1_000_000))
    // Apertura + tre blocchi (100 KB, 200 KB, 400 KB e il resto): la lentezza si sente, il risultato non cambia.
    assertThat(elapsedMs).isAtLeast(40L + 25L * 3)
  }

  // --- Sorgenti ferme: il lettore annulla interrompendo il thread -----------------------------------------------------

  @Test
  fun unaSorgenteFermaSiInterrompeEIlNasSiChiude_EV5() {
    reader.stallAfterBytes = NasVideoDataSource.FIRST_CHUNK_BYTES.toLong()
    val ds = source()
    val failure = AtomicReference<Throwable?>()
    val done = CountDownLatch(1)
    val loader = Thread {
      try {
        ds.open(spec())
        ds.readAll()
      } catch (e: Throwable) {
        failure.set(e)
      } finally {
        done.countDown()
      }
    }
    loader.start()
    // Il primo blocco arriva, il secondo non risponde mai: il thread aspetta.
    waitUntil { reader.reads.get() >= 2 && reader.bytesDelivered.get() >= NasVideoDataSource.FIRST_CHUNK_BYTES }
    assertThat(done.await(300, TimeUnit.MILLISECONDS)).isFalse()
    assertThat(reader.openFiles.get()).isEqualTo(1)

    loader.interrupt()

    assertThat(done.await(5, TimeUnit.SECONDS)).isTrue()
    assertThat(failure.get()).isInstanceOf(InterruptedIOException::class.java)
    ds.close()
    assertThat(reader.openFiles.get()).isEqualTo(0)
  }

  @Test
  fun ilSorveglianteCheDiceStalledDiventaUnTimeoutDiRete() {
    reader.stallAfterBytes = NasVideoDataSource.FIRST_CHUNK_BYTES.toLong()
    reader.stallError = NasError.Stalled
    reader.stallErrorAfterMs = 60
    val ds = source()
    ds.open(spec())

    val reason = reasonOf { ds.readAll() }

    assertThat(reason).isEqualTo(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
    ds.close()
    assertThat(reader.openFiles.get()).isEqualTo(0)
  }

  // --- Irraggiungibile e altri errori ---------------------------------------------------------------------------------

  @Test
  fun ogniErroreDelNasInAperturaHaIlSuoCodiceDiMedia3_EL4() {
    val cases = mapOf(
      NasError.Timeout to PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
      NasError.Stalled to PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
      NasError.ServiceDown to PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
      NasError.SessionExpired to PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
      NasError.LocalNetworkBlocked to PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
      NasError.NotAllowedHere(DenyReason.VPN_OFF_AWAY) to PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
      NasError.NotFound to PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
      NasError.PermissionDenied to PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
    )
    for ((error, expected) in cases) {
      reader.openError = error
      val ds = source()
      assertThat(reasonOf { ds.open(spec()) }).isEqualTo(expected)
      ds.close()
    }
    assertThat(reader.openFiles.get()).isEqualTo(0)
  }

  @Test
  fun unMediaSparitoDalCatalogoENonTrovato_EV6() {
    locations[7] = null
    val ds = source()

    assertThat(reasonOf { ds.open(spec()) }).isEqualTo(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
    assertThat(reader.opens.get()).isEqualTo(0)
  }

  @Test
  fun unCatalogoCheLanciaDiventaUnaLetturaFallitaNonUnCrash() {
    val ds = NasVideoDataSource(ref, uri, reader, { error("catalogo non risponde") })

    val e = assertThrows(DataSourceException::class.java) { ds.open(spec()) }

    assertThat(e.reason).isEqualTo(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
  }

  @Test
  fun unaLetturaCheCadeAMetaDiceCheLaReteNonVa() {
    reader.failReadsAfterBytes = NasVideoDataSource.FIRST_CHUNK_BYTES.toLong()
    reader.failReadsWith = NasError.ServiceDown
    val ds = source()
    ds.open(spec())

    val reason = reasonOf { ds.readAll() }

    assertThat(reason).isEqualTo(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
    ds.close()
    assertThat(reader.openFiles.get()).isEqualTo(0)
  }

  @Test
  fun unFileAccorciatoMentreSiGuardaNonEPiuQuelloDelLettore() {
    reader.shortBy = 500_000
    val ds = source()
    ds.open(spec())

    val reason = reasonOf { ds.readAll() }

    assertThat(reason).isEqualTo(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
    ds.close()
  }

  @Test
  fun unaPosizioneOltreLaFineEFuoriIntervallo() {
    val ds = source()

    assertThat(reasonOf { ds.open(spec(position = size + 1)) }).isEqualTo(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
    assertThat(reader.openFiles.get()).isEqualTo(0)
    // Esattamente alla fine e' valido: lunghezza zero.
    val atEnd = source()
    assertThat(atEnd.open(spec(position = size))).isEqualTo(0)
    assertThat(atEnd.read(ByteArray(8), 0, 8)).isEqualTo(C.RESULT_END_OF_INPUT)
    atEnd.close()
  }

  // --- Mai altro indirizzo ---------------------------------------------------------------------------------------------

  @Test
  fun unIndirizzoDiversoDaQuelloDellaSorgenteNonSiApreMai() {
    for (other in listOf("https://example.com/v.mp4", "http://192.168.1.10/v.mp4", "file:///sdcard/v.mp4", "content://media/external/video/1", "nasmedia://v/8.mp4")) {
      val ds = source()
      assertThat(reasonOf { ds.open(spec(at = Uri.parse(other))) }).isEqualTo(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
    }
    // Nessuna richiesta al NAS e, a maggior ragione, nessuna rete: il lettore non e' stato nemmeno interpellato.
    assertThat(reader.opens.get()).isEqualTo(0)
  }

  private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val end = System.nanoTime() + timeoutMs * 1_000_000
    while (!condition()) {
      check(System.nanoTime() < end) { "Condizione non raggiunta in tempo." }
      Thread.sleep(5)
    }
  }
}
