package dev.pampa.mycloudgallery.core.nas.ops

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nas.Harness
import dev.pampa.mycloudgallery.core.nas.SHARE
import dev.pampa.mycloudgallery.core.nas.blockingIo
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.okOrNull
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.smb.SmbOpenMode
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.waitUntil
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test

/**
 * La prova delle interruzioni sul NAS vero (F16, 15/10): 13 giri su 493 con operazioni ferme "in attesa di un file aperto dal PC". Il file
 * lo teneva aperto **il telefono stesso**: un handle di lettura che il NAS (Samba) teneva ancora aperto senza condividere la cancellazione,
 * cosi' la rinomina del file (il cestino della sorgente, la pubblicazione del temporaneo) rispondeva `SHARING_VIOLATION`. Il simulatore non
 * teneva gli handle dei client e non lo faceva vedere. Qui, sul simulatore che ora li tiene come Samba ([SmbOpenMode]):
 *  - un nostro handle di lettura ancora aperto non impedisce rinomina e cestino del file (la lettura condivide la cancellazione);
 *  - una chiusura "in volo" (rimandata su un altro thread) arriva al NAS prima della richiesta successiva (chiusure ordinate);
 *  - un handle rimasto sul NAS dopo una rete persa non blocca la rinomina; blocca solo la cancellazione esclusiva, finche' il NAS non se ne
 *    accorge, e la primitiva lo dice (`Locked`): chi chiama aspetta con un motivo, non per sempre.
 */
class OwnHandlesTest {

  private val me = Base32.encode(ByteArray(16) { 0x11 })
  private val purpose = Purpose.BACKGROUND
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val clock = MonotonicClock { System.nanoTime() / 1_000_000 }

  /** Il gestore con le chiusure rimandate su un thread vero (come in produzione), non sul thread del test. */
  private val h = Harness(scope, clock, io = Dispatchers.IO)
  private val ops = NasFileOpsFactory.create(h.manager, me)
  private val reader = h.manager.mediaReader()

  @After
  fun tearDown() {
    h.backend.beforeClose = null
    scope.cancel()
  }

  private fun p(path: String) = SharePath.parse(path)

  private fun file(path: String, bytes: ByteArray = ByteArray(5_000) { it.toByte() }) {
    h.backend.putContent(SHARE, path, NasContent.of(bytes), lastWriteMs = 1_000)
  }

  @Test
  fun leCondivisioniDelSimulatoreSonoQuelleDelTrasportoVero() {
    // La lettura condivide tutto (anche la cancellazione); il temporaneo nulla; l'handle per cambiare solo la lettura (o nulla).
    assertThat(SmbOpenMode.READ.conflictsWith(SmbOpenMode.change(exclusive = false))).isFalse()
    assertThat(SmbOpenMode.READ.conflictsWith(SmbOpenMode.change(exclusive = true))).isTrue()
    assertThat(SmbOpenMode.READ.conflictsWith(SmbOpenMode.READ)).isFalse()
    assertThat(SmbOpenMode.CREATE_EXCLUSIVE.conflictsWith(SmbOpenMode.READ)).isTrue()
    assertThat(SmbOpenMode.CREATE_EXCLUSIVE.conflictsWith(SmbOpenMode.change(exclusive = false))).isTrue()
    // Chi ha l'handle per cambiare non lascia scrivere nessuno (il timbro controllato resta quello vero fino alla rinomina).
    val writer = SmbOpenMode(read = false, write = true, delete = false, shareRead = true, shareWrite = true, shareDelete = true)
    assertThat(SmbOpenMode.change(exclusive = false).conflictsWith(writer)).isTrue()
  }

  @Test
  fun unNostroHandleDiLetturaApertoNonBloccaRinominaECestinoDelFile() = blockingIo {
    file("Foto/a.jpg")
    file("Foto/b.jpg")
    val a = reader.open(SHARE, p("Foto/a.jpg"), purpose).okOrNull()!!
    val b = reader.open(SHARE, p("Foto/b.jpg"), purpose).okOrNull()!!
    try {
      assertThat(h.backend.openHandlesOn(SHARE, "Foto/a.jpg")).isEqualTo(1)
      assertThat(ops.rename(SHARE, p("Foto/a.jpg"), p("Foto/c.jpg"), null, purpose).okOrNull()).isInstanceOf(RenameOutcome.Renamed::class.java)
      val trashId = Base32.encode(ByteArray(16) { 0x44 })
      assertThat(ops.trashMove(SHARE, p("Foto/b.jpg"), trashId, null, purpose).okOrNull()).isInstanceOf(RenameOutcome.Renamed::class.java)
      // L'handle segue il file: si legge ancora, dal posto nuovo.
      val buffer = ByteArray(16)
      assertThat(a.read(0, buffer, 0, buffer.size)).isEqualTo(16)
    } finally {
      a.close()
      b.close()
    }
  }

  @Test
  fun unaChiusuraInVoloArrivaAlNasPrimaDellaRichiestaDopo() = blockingIo {
    // Le chiusure arrivano tardi (un altro thread, la rete): ognuna aspetta 300 ms prima di arrivare al server.
    val closes = AtomicInteger()
    h.backend.beforeClose = {
      closes.incrementAndGet()
      Thread.sleep(300)
    }
    // Il giro del "PC che crea un file" del banco (rapporto F16, giri 394 e 480): temporaneo scritto, chiuso, pubblicato subito.
    val temp = NasLayout.ROOT.child("tmp").child("$me-op-1")
    val writer = ops.openTemp(SHARE, temp, purpose).okOrNull()!!
    val bytes = ByteArray(10_000) { (it * 7).toByte() }
    assertThat(writer.write(0, bytes, 0, bytes.size)).isEqualTo(NasResult.Ok(Unit))
    assertThat(writer.finish(bytes.size.toLong()).okOrNull()).isInstanceOf(TempFinish.Complete::class.java)
    writer.close()
    val published = ops.publishTemp(SHARE, temp, p("Foto/nuovo.jpg"), purpose).okOrNull()
    assertWithMessage("la pubblicazione dopo la chiusura in volo").that(published).isInstanceOf(PublishOutcome.Published::class.java)
    // La verifica di uno spostamento: letto per intero, chiuso (in volo), subito nel cestino.
    val source = reader.open(SHARE, p("Foto/nuovo.jpg"), purpose).okOrNull()!!
    assertThat(reader.readAll(source, { _, _ -> }, null)).isInstanceOf(NasResult.Ok::class.java)
    source.close()
    val trashed = ops.trashMove(SHARE, p("Foto/nuovo.jpg"), Base32.encode(ByteArray(16) { 0x55 }), null, purpose).okOrNull()
    assertThat(trashed).isInstanceOf(RenameOutcome.Renamed::class.java)
    assertThat(closes.get()).isAtLeast(2)
    waitUntil("tutti gli handle chiusi") { h.backend.openHandles == 0 }
  }

  @Test
  fun unaChiusuraCheIlNasNonConfermaNonFermaLeRichiesteDopo() = blockingIo {
    // Sul WD una CLOSE in volo mentre la connessione si chiude non torna mai: smbj la aspetta 60 s. Le richieste dopo la aspettano al
    // massimo 2 s (dalla sua partenza), e dopo la chiusura della connessione per niente.
    file("Foto/a.jpg")
    val release = CountDownLatch(1)
    h.backend.beforeClose = { release.await(30, TimeUnit.SECONDS) }
    try {
      reader.open(SHARE, p("Foto/a.jpg"), purpose).okOrNull()!!.close()
      var started = System.nanoTime()
      assertThat(reader.stat(SHARE, p("Foto/a.jpg"), purpose).okOrNull()).isNotNull()
      val waited = (System.nanoTime() - started) / 1_000_000
      assertWithMessage("attesa della chiusura in volo: $waited ms").that(waited).isIn(com.google.common.collect.Range.closed(1_000L, 10_000L))
      // Un'altra chiusura appesa, poi la connessione si chiude: le richieste sulla connessione nuova non la aspettano.
      reader.open(SHARE, p("Foto/a.jpg"), purpose).okOrNull()!!.close()
      h.manager.invalidate(dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason.SUSPECT_CONNECTION)
      started = System.nanoTime()
      assertThat(reader.stat(SHARE, p("Foto/a.jpg"), purpose).okOrNull()).isNotNull()
      assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1_000L)
    } finally {
      release.countDown()
    }
  }

  @Test
  fun unHandleRimastoSulNasDopoUnaRetePersaNonBloccaLaRinominaEBloccaSoloLaCancellazioneEsclusiva() = blockingIo {
    file("Foto/a.jpg", ByteArray(4_000) { 3 })
    // Il telefono legge il file e perde la rete senza chiuderlo: il NAS tiene la sessione e l'handle finche' non se ne accorge.
    reader.open(SHARE, p("Foto/a.jpg"), purpose).okOrNull()!!
    h.backend.abandonAllConnections()
    assertThat(h.backend.openHandlesOn(SHARE, "Foto/a.jpg")).isEqualTo(1)
    // Sulla connessione nuova la rinomina nel cestino passa (la prima chiamata puo' accorgersi della connessione persa).
    val trashId = Base32.encode(ByteArray(16) { 0x66 })
    var trashed = ops.trashMove(SHARE, p("Foto/a.jpg"), trashId, null, purpose)
    if (trashed is NasResult.Err) trashed = ops.trashMove(SHARE, p("Foto/a.jpg"), trashId, null, purpose)
    assertThat(trashed.okOrNull()).isInstanceOf(RenameOutcome.Renamed::class.java)
    // La cancellazione esclusiva (controllo della dimensione sullo stesso handle) no: Locked, finche' il NAS non chiude l'handle vecchio.
    val inTrash = NasLayout.trashItemFile(me, trashId)
    assertThat(ops.deleteIfUnchanged(SHARE, inTrash, 4_000, purpose).okOrNull()).isEqualTo(DeleteOutcome.Locked)
    h.backend.reapAbandoned()
    assertThat(ops.deleteIfUnchanged(SHARE, inTrash, 4_000, purpose).okOrNull()).isEqualTo(DeleteOutcome.Deleted)
    assertThat(h.backend.openHandles).isEqualTo(0)
  }

  @Test
  fun laMorteDelProcessoChiudeIlSocketEIlNasChiudeGliHandle() = blockingIo {
    file("Foto/a.jpg")
    // Una lettura a meta' di un processo che muore: il sistema chiude il socket (la connessione), il NAS chiude l'handle.
    reader.open(SHARE, p("Foto/a.jpg"), purpose).okOrNull()!!
    assertThat(h.backend.openHandles).isEqualTo(1)
    h.manager.invalidate(dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason.SUSPECT_CONNECTION)
    assertThat(h.backend.openHandles).isEqualTo(0)
    val trashId = Base32.encode(ByteArray(16) { 0x77 })
    assertThat(ops.trashMove(SHARE, p("Foto/a.jpg"), trashId, null, purpose).okOrNull()).isInstanceOf(RenameOutcome.Renamed::class.java)
  }
}
