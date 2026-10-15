package dev.pampa.mycloudgallery.core.nas.ops

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nas.Harness
import dev.pampa.mycloudgallery.core.nas.READ_ONLY_SHARE
import dev.pampa.mycloudgallery.core.nas.SHARE
import dev.pampa.mycloudgallery.core.nas.awaitBlocking
import dev.pampa.mycloudgallery.core.nas.blockingIo
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.contract.okOrNull
import dev.pampa.mycloudgallery.core.nas.mustOpen
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.nas.waitUntil
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Test

/**
 * "Permesso negato" che non lo e' (decisione dell'utente, 15/10). Sul WD una cartella con un file aperto SOTTO dallo stesso client non si
 * rinomina (`ACCESS_DENIED`, Samba `strict rename = no`): se il file lo tiene aperto la Libreria (una miniatura, un'anteprima) lo spostamento
 * o l'eliminazione della cartella si fermavano come errore. Sul simulatore, che riproduce quel comportamento:
 *  - un handle di lettura nostro sotto la cartella si **rilascia** e la rinomina riesce; il file resta leggibile (la lettura dopo riapre);
 *  - un handle che non si puo' rilasciare (un elenco a meta') o in uso in quel momento da una lettura da' `FolderInUse`, senza cambiare niente;
 *  - **un vero `ACCESS_DENIED` resta un errore**: senza handle nostri sotto la cartella, con handle fuori dalla cartella, su una share in sola
 *    lettura, o con handle nostri che, rilasciati, non cambiano la risposta.
 */
class OwnFolderHandlesTest {

  private val me = Base32.encode(ByteArray(16) { 0x11 })
  private val purpose = Purpose.BACKGROUND
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val clock = MonotonicClock { System.nanoTime() / 1_000_000 }

  private val h = Harness(scope, clock, io = Dispatchers.IO)
  private val ops = NasFileOpsFactory.create(h.manager, me)
  private val reader = h.manager.mediaReader()

  @After
  fun tearDown() {
    h.backend.beforeOp = null
    h.backend.faults = null
    scope.cancel()
  }

  private fun p(path: String) = SharePath.parse(path)

  private fun file(path: String, size: Int = 5_000, share: String = SHARE) {
    h.backend.putContent(share, path, NasContent.of(ByteArray(size) { (it * 3).toByte() }), lastWriteMs = 1_000)
  }

  private suspend fun renameFolder(from: String = "Foto", to: String = "Gita", share: String = SHARE) =
    ops.rename(share, p(from), p(to), null, purpose)

  // --- Il caso da sistemare ------------------------------------------------------------------------------------------------

  @Test
  fun unaMiniaturaApertaSottoLaCartellaNonFermaLaRinomina() = blockingIo {
    file("Foto/Mare/a.jpg")
    file("Foto/b.jpg")
    val thumbnail = reader.open(SHARE, p("Foto/Mare/a.jpg"), purpose).okOrNull()!!
    try {
      // Il simulatore fa quello che fa il WD: con l'handle aperto sotto, la cartella non si rinomina.
      assertThat(h.backend.openHandlesOn(SHARE, "Foto/Mare/a.jpg")).isEqualTo(1)
      assertThat(renameFolder().okOrNull()).isInstanceOf(RenameOutcome.Renamed::class.java)
      assertThat(h.backend.exists(SHARE, "Gita/Mare/a.jpg")).isTrue()
      assertThat(h.backend.exists(SHARE, "Foto")).isFalse()
      // L'handle sul NAS e' stato lasciato (la CLOSE e' arrivata prima del secondo tentativo).
      assertThat(h.backend.openHandlesOn(SHARE, "Gita/Mare/a.jpg")).isEqualTo(0)
    } finally {
      thumbnail.close()
    }
  }

  @Test
  fun ilFileRilasciatoRestaLeggibileSeLaCartellaTornaAlSuoPosto() = blockingIo {
    file("Foto/Mare/a.jpg")
    val expected = h.backend.bytes(SHARE, "Foto/Mare/a.jpg")!!
    val thumbnail = reader.open(SHARE, p("Foto/Mare/a.jpg"), purpose).okOrNull()!!
    try {
      assertThat(renameFolder("Foto", "Gita").okOrNull()).isInstanceOf(RenameOutcome.Renamed::class.java)
      // La cartella e' stata spostata: al vecchio posto il file non c'e', e chi legge lo sa (non un errore strano, non un handle morto).
      val moved = try {
        thumbnail.read(0, ByteArray(16), 0, 16)
        null
      } catch (e: NasIoException) {
        e.error
      }
      assertThat(moved).isEqualTo(NasError.NotFound)
      // Rimessa a posto, la stessa lettura riapre da sola e controlla che sia ancora lo stesso file.
      assertThat(renameFolder("Gita", "Foto").okOrNull()).isInstanceOf(RenameOutcome.Renamed::class.java)
      val buffer = ByteArray(16)
      assertThat(thumbnail.read(0, buffer, 0, buffer.size)).isEqualTo(16)
      assertThat(buffer).isEqualTo(expected.copyOf(16))
    } finally {
      thumbnail.close()
    }
  }

  @Test
  fun ilCestinoDiUnaCartellaConUnaMiniaturaApertaRiesce() = blockingIo {
    file("Gita/Mare/a.jpg")
    file("Gita/b.jpg")
    val thumbnail = reader.open(SHARE, p("Gita/Mare/a.jpg"), purpose).okOrNull()!!
    try {
      val trashId = Base32.encode(ByteArray(16) { 0x44 })
      assertThat(ops.trashMove(SHARE, p("Gita"), trashId, null, purpose).okOrNull()).isInstanceOf(RenameOutcome.Renamed::class.java)
      assertThat(h.backend.exists(SHARE, "Gita")).isFalse()
      assertThat(h.backend.exists(SHARE, "${NasLayout.trashItemFile(me, trashId).value}/Mare/a.jpg")).isTrue()
    } finally {
      thumbnail.close()
    }
  }

  @Test
  fun piuLettureAperteSottoLaCartellaSiRilasciano() = blockingIo {
    val names = listOf("Foto/a.jpg", "Foto/Mare/b.jpg", "Foto/Mare/Sera/c.jpg")
    names.forEach { file(it) }
    val open = names.map { reader.open(SHARE, p(it), purpose).okOrNull()!! }
    try {
      assertThat(renameFolder().okOrNull()).isInstanceOf(RenameOutcome.Renamed::class.java)
      assertThat(h.backend.openHandles).isEqualTo(0)
    } finally {
      open.forEach { it.close() }
    }
  }

  // --- Cio' che non si puo' rilasciare, o che e' in uso adesso -------------------------------------------------------------

  @Test
  fun unElencoAMetaSottoLaCartellaDaFolderInUseEPoiPassa() = blockingIo {
    file("Foto/Mare/a.jpg")
    file("Foto/Mare/b.jpg")
    // Un elenco di Foto/Mare a pagine di una voce, fermo dopo la prima: la cartella aperta resta aperta sul NAS finche' non finisce.
    val gate = CompletableDeferred<Unit>()
    val listing = scope.launch(Dispatchers.IO) { reader.list(SHARE, p("Foto/Mare"), purpose, pageSize = 1) { gate.await() } }
    waitUntil("elenco a meta'") { h.backend.openHandlesOn(SHARE, "Foto/Mare") == 1 }
    try {
      assertThat(renameFolder().okOrNull()).isEqualTo(RenameOutcome.FolderInUse)
      assertThat(h.backend.exists(SHARE, "Foto/Mare/a.jpg")).isTrue()
      assertThat(h.backend.exists(SHARE, "Gita")).isFalse()
    } finally {
      gate.complete(Unit)
    }
    listing.join()
    waitUntil("elenco chiuso") { h.backend.openHandles == 0 }
    assertThat(renameFolder().okOrNull()).isInstanceOf(RenameOutcome.Renamed::class.java)
  }

  @Test
  fun unaLetturaInCorsoNonSiChiudeSottoIPiediDiChiLegge() = blockingIo {
    file("Foto/Mare/a.jpg", size = 5_000)
    val expected = h.backend.bytes(SHARE, "Foto/Mare/a.jpg")!!.copyOf(16)
    val thumbnail = reader.open(SHARE, p("Foto/Mare/a.jpg"), purpose).okOrNull()!!
    val entered = CountDownLatch(1)
    val proceed = CountDownLatch(1)
    h.backend.beforeOp = { op, _ ->
      if (op == SimOp.READ) {
        entered.countDown()
        proceed.await(30, TimeUnit.SECONDS)
      }
    }
    val buffer = ByteArray(16)
    val read = scope.async(Dispatchers.IO) { thumbnail.read(0, buffer, 0, buffer.size) }
    try {
      entered.mustOpen("lettura in corso")
      // L'handle e' in uso: si rilascia (nessuna lettura nuova lo usa) ma la CLOSE aspetta la lettura, e la cartella non e' ancora libera.
      assertThat(renameFolder().okOrNull()).isEqualTo(RenameOutcome.FolderInUse)
      assertThat(h.backend.openHandlesOn(SHARE, "Foto/Mare/a.jpg")).isEqualTo(1)
    } finally {
      h.backend.beforeOp = null
      proceed.countDown()
    }
    // La lettura che era in corso finisce bene, sullo stesso handle.
    assertThat(read.awaitBlocking("lettura")).isEqualTo(16)
    assertThat(buffer).isEqualTo(expected)
    // E' stata l'ultima lettura a chiudere l'handle: ora la cartella si rinomina.
    waitUntil("handle chiuso") { h.backend.openHandles == 0 }
    assertThat(renameFolder().okOrNull()).isInstanceOf(RenameOutcome.Renamed::class.java)
    thumbnail.close()
  }

  // --- Un vero permesso negato resta un errore -------------------------------------------------------------------------------

  @Test
  fun unAccessDeniedSenzaHandleNostriSottoLaCartellaResteraUnErrore() = blockingIo {
    file("Foto/a.jpg")
    h.backend.faults = FaultPlan().at(1, FaultPhase.BEFORE, SimFault.Status(NtStatus.ACCESS_DENIED), op = SimOp.RENAME)
    assertThat(renameFolder().errorOrNull()).isEqualTo(NasError.PermissionDenied)
    assertThat(h.backend.exists(SHARE, "Foto/a.jpg")).isTrue()
  }

  @Test
  fun gliHandleFuoriDallaCartellaNonCambianoIlVerdetto() = blockingIo {
    file("Foto/a.jpg")
    file("Altra/x.jpg")
    val elsewhere = reader.open(SHARE, p("Altra/x.jpg"), purpose).okOrNull()!!
    try {
      h.backend.faults = FaultPlan().at(1, FaultPhase.BEFORE, SimFault.Status(NtStatus.ACCESS_DENIED), op = SimOp.RENAME)
      assertThat(renameFolder().errorOrNull()).isEqualTo(NasError.PermissionDenied)
      // Quello fuori dalla cartella non si tocca: nessun rilascio senza una prova.
      assertThat(h.backend.openHandlesOn(SHARE, "Altra/x.jpg")).isEqualTo(1)
    } finally {
      elsewhere.close()
    }
  }

  @Test
  fun unaShareInSolaLetturaResteUnErroreAnchePerChiLeggeDentro() = blockingIo {
    file("Foto/Mare/a.jpg", share = READ_ONLY_SHARE)
    val reading = reader.open(READ_ONLY_SHARE, p("Foto/Mare/a.jpg"), purpose).okOrNull()!!
    try {
      assertThat(renameFolder(share = READ_ONLY_SHARE).errorOrNull()).isEqualTo(NasError.PermissionDenied)
      assertThat(ops.trashMove(READ_ONLY_SHARE, p("Foto"), Base32.encode(ByteArray(16) { 0x45 }), null, purpose).errorOrNull())
        .isEqualTo(NasError.PermissionDenied)
      assertThat(h.backend.exists(READ_ONLY_SHARE, "Foto/Mare/a.jpg")).isTrue()
    } finally {
      reading.close()
    }
  }

  @Test
  fun unRifiutoChePersisteDopoIlRilascioDeiNostriHandleEUnErrore() = blockingIo {
    file("Foto/Mare/a.jpg")
    val thumbnail = reader.open(SHARE, p("Foto/Mare/a.jpg"), purpose).okOrNull()!!
    try {
      // Ogni rinomina e' rifiutata: i nostri handle ci sono (e si rilasciano), ma non erano loro la causa. Alla fine nessuno e' piu' sotto la
      // cartella e il rifiuto e' quello di sempre.
      h.backend.faults = FaultPlan().random(1L, 1.0, FaultPhase.BEFORE, SimFault.Status(NtStatus.ACCESS_DENIED), ops = setOf(SimOp.RENAME))
      assertThat(renameFolder().errorOrNull()).isEqualTo(NasError.PermissionDenied)
      assertThat(h.backend.exists(SHARE, "Foto/Mare/a.jpg")).isTrue()
    } finally {
      thumbnail.close()
    }
  }

  @Test
  fun unFileRifiutatoResteraUnErroreAnchePrimaDellaProva() = blockingIo {
    file("Foto/a.jpg")
    val reading = reader.open(SHARE, p("Foto/a.jpg"), purpose).okOrNull()!!
    try {
      h.backend.faults = FaultPlan().at(1, FaultPhase.BEFORE, SimFault.Status(NtStatus.ACCESS_DENIED), op = SimOp.RENAME)
      // Un file (non una cartella) non entra in questo ragionamento: `ACCESS_DENIED` e' `PermissionDenied`, come sempre.
      assertThat(ops.rename(SHARE, p("Foto/a.jpg"), p("Foto/b.jpg"), null, purpose).errorOrNull()).isEqualTo(NasError.PermissionDenied)
      assertWithMessage("l'handle del file non si rilascia").that(h.backend.openHandlesOn(SHARE, "Foto/a.jpg")).isEqualTo(1)
    } finally {
      reading.close()
    }
  }
}
