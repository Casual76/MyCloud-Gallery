package dev.pampa.mycloudgallery.core.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaLimits
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.contract.okOrNull
import dev.pampa.mycloudgallery.core.nas.fs.smb
import dev.pampa.mycloudgallery.core.nas.session.NasSessionConfig
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.ManualMonotonicClock
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.ByteArrayOutputStream
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * La lettura dei media sopra SMB (P1, `docs/fase4-progetto.md` §4.4 riga P1), sul simulatore: cio' che il contratto
 * comune ([NasMediaReaderContract]) non puo' vedere perche' dipende da SMB (handle, sessioni, flussi paralleli).
 */
class MediaReaderTest {

  private fun TestScope.harness() = Harness(backgroundScope, virtualClock())

  private val photo = NasContent.of(byteArrayOf(1, 2, 3))

  @Test
  fun cinquantamilaVociAPagineSenzaMaiTenereLElencoIntero() = runTest {
    val h = harness()
    repeat(50_000) { h.backend.putContent(SHARE, "WhatsApp/IMG-$it.jpg", photo) }
    val reader = h.manager.mediaReader()
    val names = HashSet<String>()
    var pages = 0
    var maxAhead = 0L

    val outcome = reader.list(SHARE, SharePath.of("whatsapp"), Purpose.BACKGROUND, pageSize = 1000) { page ->
      pages++
      assertThat(page.size).isAtMost(1000)
      // Voci lette dal server ma non ancora consegnate: al piu' una pagina (piu' la voce guardata in avanti).
      maxAhead = maxOf(maxAhead, h.backend.listEntriesServed.get() - names.size)
      page.mapTo(names) { it.name }
    }.okOrNull()!!

    assertThat(outcome.count).isEqualTo(50_000)
    assertThat(outcome.cappedAt).isNull()
    assertThat(names).hasSize(50_000)
    assertThat(pages).isEqualTo(50)
    assertThat(maxAhead).isAtMost(1001L)
    // Un solo handle per tutte le pagine (una CREATE della cartella), e la sessione libera alla fine.
    assertThat(h.manager.activeOperations.value).isEqualTo(0)
  }

  @Test
  fun oltreIlTettoPerCartellaLElencoSiFermaESiDice() = runTest {
    val h = harness()
    repeat(NasMediaLimits.MAX_DIR_ENTRIES + 1) { h.backend.putContent(SHARE, "enorme/$it", photo) }
    var delivered = 0
    val outcome = h.manager.mediaReader().list(SHARE, SharePath.of("enorme"), Purpose.BACKGROUND, pageSize = 5000) { delivered += it.size }
    assertThat(outcome.okOrNull()!!.cappedAt).isEqualTo(NasMediaLimits.MAX_DIR_ENTRIES)
    assertThat(delivered).isEqualTo(NasMediaLimits.MAX_DIR_ENTRIES)
  }

  @Test
  fun unaCadutaPrimaDellaPrimaPaginaSiRiprovaDopoNoPerNonDareDoppioni() = runTest {
    val h = harness()
    repeat(2500) { h.backend.putContent(SHARE, "foto/$it.jpg", photo) }
    val reader = h.manager.mediaReader()

    // Prima di qualunque pagina: nuovo tentativo trasparente, nessun doppione.
    h.backend.faults = FaultPlan().at(1, FaultPhase.BEFORE, SimFault.DropConnection, SimOp.OPEN_DIRECTORY)
    val first = ArrayList<NasEntry>()
    assertThat(reader.list(SHARE, SharePath.of("foto"), Purpose.BACKGROUND, 1000) { first += it }.okOrNull()!!.count).isEqualTo(2500)
    assertThat(first.map { it.name }.toSet()).hasSize(2500)

    // Dopo la prima pagina: l'elenco si ferma con l'errore (chi chiama rifara' la cartella intera).
    h.backend.faults = FaultPlan().at(1500, FaultPhase.BEFORE, SimFault.DropConnection, SimOp.LIST_ENTRY)
    val pages = ArrayList<Int>()
    val result = reader.list(SHARE, SharePath.of("foto"), Purpose.BACKGROUND, 1000) { pages += it.size }
    assertThat(result.errorOrNull()).isEqualTo(NasError.Timeout)
    assertThat(pages).containsExactly(1000)
  }

  @Test
  fun unEccezioneDiChiConsumaFermaLElencoERisale() = runTest {
    val h = harness()
    repeat(3000) { h.backend.putContent(SHARE, "foto/$it.jpg", photo) }
    val boom = IllegalStateException("database pieno")
    var calls = 0
    val thrown = runCatching {
      h.manager.mediaReader().list(SHARE, SharePath.of("foto"), Purpose.BACKGROUND, 1000) {
        calls++
        throw boom
      }
    }.exceptionOrNull()
    assertThat(thrown).isSameInstanceAs(boom)
    assertThat(calls).isEqualTo(1)
    assertThat(h.manager.activeOperations.value).isEqualTo(0)
  }

  @Test
  fun ilFileIdRestaAllaRinominaFattaDaQuiEDalPcEUnaCopiaNeHaUnoNuovo() = runTest {
    val h = harness()
    val reader = h.manager.mediaReader()
    // Come publishExclusive: temporaneo creato in esclusiva, poi rinominato senza sostituire.
    val tempId = h.manager.runOp(SHARE, Purpose.BACKGROUND, idempotent = false, touchesDisk = true) { ctx ->
      ctx.share.mkdir("doc")
      ctx.share.createExclusive("doc\\nuovo.tmp").use { file ->
        file.write(byteArrayOf(9, 9), 0, 0, 2)
        val id = ctx.share.stat("doc\\nuovo.tmp").fileId
        file.renameNoReplace(SharePath.of("doc", "a.jpg").smb())
        id
      }
    }.value()
    val published = reader.stat(SHARE, SharePath.of("doc", "a.jpg"), Purpose.BACKGROUND).okOrNull()!!
    assertThat(published.fileId).isEqualTo(tempId)

    // Il PC sposta la cartella intera: stesso fileId.
    h.backend.rename(SHARE, "doc", "Documenti")
    assertThat(reader.stat(SHARE, SharePath.of("documenti", "A.JPG"), Purpose.BACKGROUND).okOrNull()!!.fileId).isEqualTo(tempId)

    // Una copia lato server e' un file nuovo.
    val copy = dev.pampa.mycloudgallery.core.nas.fs.SmbServerSideCopy(h.manager)
      .copy(SHARE, SharePath.of("Documenti", "a.jpg"), SHARE, SharePath.of("Documenti", "b.jpg")).value()
    assertThat(copy).isEqualTo(2)
    assertThat(reader.stat(SHARE, SharePath.of("Documenti", "b.jpg"), Purpose.BACKGROUND).okOrNull()!!.fileId).isNotEqualTo(tempId)
  }

  @Test
  fun dopoUnaCadutaIlFileSiRiapreESiControllaCheSiaLoStesso() = runTest {
    val h = harness()
    val content = Random(7).nextBytes(200_000)
    h.backend.putContent(SHARE, "v.mp4", NasContent.of(content), lastWriteMs = 1_800_000_000_000L)
    val file = h.manager.mediaReader().open(SHARE, SharePath.of("v.mp4"), Purpose.BACKGROUND).okOrNull()!!
    assertThat(file.stamp.fileId).isEqualTo(h.backend.fileId(SHARE, "v.mp4"))
    assertThat(file.stamp.modifiedAtMillis).isEqualTo(1_800_000_000_000L)
    assertThat(file.readRange(0L until 100)).isEqualTo(content.copyOf(100))

    // Il Wi-Fi cade: la lettura successiva riapre il file sulla sessione nuova e trova lo stesso timbro.
    h.backend.dropAllConnections()
    assertThat(file.readRange(100L until 200)).isEqualTo(content.copyOfRange(100, 200))
    assertThat(h.backend.tcpAttempts.get()).isEqualTo(2)

    // Il PC lo modifica e la connessione ricade: mai mescolare due versioni.
    h.backend.modify(SHARE, "v.mp4", NasContent.of(ByteArray(200_000)), lastWriteMs = 1_800_000_060_000L)
    h.backend.dropAllConnections()
    val error = assertThrows(NasIoException::class.java) { runBlocking { file.readRange(200L until 300) } }.error
    assertThat(error).isEqualTo(NasError.Unexpected(null))
    file.close()
    assertThrows(IllegalStateException::class.java) { runBlocking { file.read(0, ByteArray(1), 0, 1) } }
  }

  @Test
  fun readAllUsaQuattroFlussiEConsegnaInOrdine() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val h = Harness(scope, ManualMonotonicClock(), NasSessionConfig(), io = Dispatchers.IO)
      val content = Random(3).nextBytes(10 * NasMediaLimits.READ_ALL_CHUNK_BYTES + 12_345)
      h.backend.put(SHARE, "grande.mov", content)
      // I primi quattro blocchi partono insieme (la barriera si apre solo con quattro letture in volo); poi tempi
      // casuali, perche' arrivino in disordine.
      val barrier = CyclicBarrier(NasMediaLimits.READ_ALL_STREAMS)
      val random = Random(11)
      var reads = 0
      h.backend.beforeOp = { op, _ ->
        if (op == SimOp.READ) {
          val n = synchronized(this) { reads++ }
          if (n < NasMediaLimits.READ_ALL_STREAMS) barrier.await(10, TimeUnit.SECONDS) else Thread.sleep(random.nextLong(0, 4))
        }
      }
      val out = ByteArrayOutputStream()
      val progress = ArrayList<Long>()
      val hash = runBlocking {
        val reader = h.manager.mediaReader()
        val file = reader.open(SHARE, SharePath.of("grande.mov"), Purpose.BACKGROUND).value()
        reader.readAll(file, { buf, n -> out.write(buf, 0, n) }) { synchronized(progress) { progress += it } }.value()
      }
      assertThat(hash).isEqualTo(Sha256.of(content))
      assertThat(out.toByteArray()).isEqualTo(content)
      assertThat(h.backend.maxReadsInFlight.get()).isEqualTo(NasMediaLimits.READ_ALL_STREAMS)
      assertThat(progress).isInOrder()
      assertThat(progress.last()).isEqualTo(content.size.toLong())
    } finally {
      scope.cancel()
    }
  }

  @Test
  fun readAllDiUnFileVuotoEDiUnFileCambiatoAMeta() = runTest {
    val h = harness()
    h.backend.put(SHARE, "vuoto.jpg", ByteArray(0))
    val reader = h.manager.mediaReader()
    val empty = reader.open(SHARE, SharePath.of("vuoto.jpg"), Purpose.BACKGROUND).value()
    assertThat(reader.readAll(empty, { _, _ -> error("niente da consegnare") }).value()).isEqualTo(Sha256.of(ByteArray(0)))

    val content = Random(5).nextBytes(3 * NasMediaLimits.READ_ALL_CHUNK_BYTES)
    h.backend.putContent(SHARE, "v.mp4", NasContent.of(content), lastWriteMs = 1_800_000_000_000L)
    val file = reader.open(SHARE, SharePath.of("v.mp4"), Purpose.BACKGROUND).value()
    var chunks = 0
    h.backend.beforeOp = { op, _ ->
      if (op == SimOp.READ && ++chunks == 2) {
        h.backend.modify(SHARE, "v.mp4", NasContent.of(ByteArray(content.size)), lastWriteMs = 1_800_000_060_000L)
        h.backend.dropAllConnections()
      }
    }
    assertThat(reader.readAll(file, { _, _ -> }).errorOrNull()).isEqualTo(NasError.Unexpected(null))
  }

  @Test
  fun statDiCartellaFileEAssentiConAttributiEDate() = runTest {
    val h = harness()
    h.backend.putContent(SHARE, "Album/cover.jpg", photo, lastWriteMs = 1_700_000_000_000L, attributes = 0x2)
    val reader = h.manager.mediaReader()
    val cover = reader.stat(SHARE, SharePath.of("album", "COVER.JPG"), Purpose.BACKGROUND).okOrNull()!!
    assertThat(cover.name).isEqualTo("cover.jpg")
    assertThat(cover.isHidden).isTrue()
    assertThat(cover.modifiedAt).isEqualTo(1_700_000_000_000L)
    assertThat(cover.size).isEqualTo(3)
    val album = reader.stat(SHARE, SharePath.of("Album"), Purpose.BACKGROUND).okOrNull()!!
    assertThat(album.isDirectory).isTrue()
    assertThat(album.size).isEqualTo(0)
    assertThat(reader.stat(SHARE, SharePath.of("Album", "manca.jpg"), Purpose.BACKGROUND).okOrNull()).isNull()
    assertThat(reader.stat(SHARE, SharePath.of("Album", "cover.jpg", "sotto"), Purpose.BACKGROUND).okOrNull()).isNull()
  }
}
