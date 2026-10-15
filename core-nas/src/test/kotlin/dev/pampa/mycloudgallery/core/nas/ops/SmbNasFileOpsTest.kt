package dev.pampa.mycloudgallery.core.nas.ops

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nas.Harness
import dev.pampa.mycloudgallery.core.nas.SHARE
import dev.pampa.mycloudgallery.core.nas.USER
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.contract.okOrNull
import dev.pampa.mycloudgallery.core.nas.noop.NoNasFileOps
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasSessionManager
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.virtualClock
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Le primitive di scrittura SMB (`SmbNasFileOps`, P12) sul simulatore Samba: gli stessi casi del contratto del fake
 * (`NasFileOpsContractTest`) e cio' che solo SMB puo' mostrare: lo **stesso handle** per controllo e modifica, i guasti a ogni
 * richiesta SMB prima e dopo che il server l'abbia applicata, la copia lato server rifiutata, le quote, la recinzione D33.
 */
class SmbNasFileOpsTest {

  private val me = Base32.encode(ByteArray(16) { 0x11 })
  private val other = Base32.encode(ByteArray(16) { 0x22 })
  private val second = "seconda"
  private val purpose = Purpose.INTERACTIVE

  private fun TestScope.harness(): Harness = Harness(backgroundScope, virtualClock()).also {
    it.backend.addShare(second, mapOf(USER to SimAccess.READ_WRITE))
  }

  private fun Harness.ops(): NasFileOps = NasFileOpsFactory.create(manager, me)

  private fun p(path: String) = SharePath.parse(path)

  private fun temp(name: String = "op-1") = NasLayout.ROOT.child("tmp").child("$me-$name")

  private fun id(n: Int) = Base32.encode(ByteArray(16) { n.toByte() })

  private fun Harness.file(share: String, path: String, bytes: ByteArray = byteArrayOf(1, 2, 3), at: Long = 1_000): NasStamp {
    val fileId = backend.putContent(share, path, NasContent.of(bytes), lastWriteMs = at)
    return NasStamp(bytes.size.toLong(), at, fileId)
  }

  private fun Harness.exists(share: String, path: String) = backend.exists(share, path)

  // --- mkdirs ------------------------------------------------------------------------------------------------------------

  @Test
  fun mkdirsCreaLeCartelleEUnaCartellaGiaPresenteEUnSuccesso() = runTest {
    val h = harness()
    assertThat(h.ops().mkdirs(SHARE, p("Foto/2024/Mare"), purpose)).isEqualTo(NasResult.Ok(Unit))
    assertThat(h.exists(SHARE, "Foto/2024/Mare")).isTrue()
    assertThat(h.ops().mkdirs(SHARE, p("foto/2024"), purpose)).isEqualTo(NasResult.Ok(Unit))
  }

  @Test
  fun mkdirsDoveCeUnFileDaExists() = runTest {
    val h = harness()
    h.file(SHARE, "Foto/x.jpg")
    assertThat(h.ops().mkdirs(SHARE, p("Foto/x.jpg"), purpose).errorOrNull()).isEqualTo(NasError.Exists)
    assertThat(h.ops().mkdirs(SHARE, p("Foto/x.jpg/sotto"), purpose).errorOrNull()).isEqualTo(NasError.Exists)
  }

  // --- rename ------------------------------------------------------------------------------------------------------------

  @Test
  fun renameSpostaSenzaSostituireEConservaIlFileId() = runTest {
    val h = harness()
    val stamp = h.file(SHARE, "Foto/x.jpg")
    h.backend.putDir(SHARE, "Altro")
    val out = h.ops().rename(SHARE, p("Foto/x.jpg"), p("Altro/y.jpg"), stamp, purpose).okOrNull()
    assertThat(out).isInstanceOf(RenameOutcome.Renamed::class.java)
    assertThat((out as RenameOutcome.Renamed).stamp!!.fileId).isEqualTo(stamp.fileId)
    assertThat(h.exists(SHARE, "Foto/x.jpg")).isFalse()
    assertThat(h.backend.fileId(SHARE, "Altro/y.jpg")).isEqualTo(stamp.fileId)
  }

  @Test
  fun renameNonSostituisceMaiESoloMaiuscoleENonFaRichieste() = runTest {
    val h = harness()
    h.file(SHARE, "x.jpg", byteArrayOf(1))
    h.file(SHARE, "y.jpg", byteArrayOf(2))
    assertThat(h.ops().rename(SHARE, p("x.jpg"), p("y.jpg"), null, purpose).okOrNull()).isEqualTo(RenameOutcome.Collision)
    assertThat(h.backend.bytes(SHARE, "y.jpg")).isEqualTo(byteArrayOf(2))
    val before = h.backend.shareOps.get()
    assertThat(h.ops().rename(SHARE, p("x.jpg"), p("X.JPG"), null, purpose).okOrNull()).isEqualTo(RenameOutcome.Collision)
    assertThat(h.backend.shareOps.get()).isEqualTo(before)
  }

  @Test
  fun renameSorgenteAssenteCambiataOBloccata() = runTest {
    val h = harness()
    val ops = h.ops()
    assertThat(ops.rename(SHARE, p("niente.jpg"), p("x.jpg"), null, purpose).okOrNull()).isEqualTo(RenameOutcome.SourceMissing)

    val stamp = h.file(SHARE, "x.jpg", byteArrayOf(1, 2, 3))
    h.backend.modify(SHARE, "x.jpg", NasContent.of(byteArrayOf(9, 9)), lastWriteMs = 2_000)
    assertThat(ops.rename(SHARE, p("x.jpg"), p("z.jpg"), stamp, purpose).okOrNull()).isInstanceOf(RenameOutcome.SourceChanged::class.java)
    assertThat(h.exists(SHARE, "x.jpg")).isTrue()
    assertThat(h.exists(SHARE, "z.jpg")).isFalse()

    h.backend.lock(SHARE, "x.jpg")
    assertThat(ops.rename(SHARE, p("x.jpg"), p("z.jpg"), null, purpose).okOrNull()).isEqualTo(RenameOutcome.Locked)
  }

  @Test
  fun renameVersoUnaCartellaCheNonCeEUnaCartellaDentroSeStessaSonoErrori() = runTest {
    val h = harness()
    h.file(SHARE, "x.jpg")
    assertThat(h.ops().rename(SHARE, p("x.jpg"), p("Manca/x.jpg"), null, purpose).errorOrNull()).isEqualTo(NasError.NotFound)
    h.backend.putDir(SHARE, "Dir")
    assertThat(h.ops().rename(SHARE, p("Dir"), p("Dir/Sotto"), null, purpose).errorOrNull()).isInstanceOf(NasError.Unexpected::class.java)
  }

  @Test
  fun leRegoleDeiPercorsiValgonoPrimaDiQualunqueRichiesta() = runTest {
    val h = harness()
    val ops = h.ops()
    val before = h.backend.shareOps.get()
    assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { ops.rename(SHARE, p("x.jpg"), p(".mycloudgallery/x"), null, purpose) } }
    assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { ops.rename(SHARE, p(".mycloudgallery/devices/x"), p("x.jpg"), null, purpose) } }
    assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { ops.deleteIfUnchanged(SHARE, p("Foto/x.jpg"), 3, purpose) } }
    assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { ops.trashMove(SHARE, p(".mycloudgallery/devices"), id(1), null, purpose) } }
    assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { ops.deleteOwnTemp(SHARE, NasLayout.ROOT.child("tmp").child("$other-op-1"), purpose) } }
    assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { ops.rmdirIfEmpty(SHARE, NasLayout.trashItemFile(other, id(5)), purpose) } }
    assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { ops.mkdirs(SHARE, p(".mycloudgallery/x"), purpose) } }
    assertThat(h.backend.shareOps.get()).isEqualTo(before)
  }

  // --- copia lato server, temporanei, pubblicazione -------------------------------------------------------------------------

  @Test
  fun copyToTempTraShareSenzaToccareLaSorgenteENonSostituisce() = runTest {
    val h = harness()
    val bytes = ByteArray(3 * 1024 * 1024 + 7) { (it * 7).toByte() }
    val stamp = h.file(SHARE, "Foto/x.jpg", bytes)
    val out = h.ops().copyToTemp(SHARE, p("Foto/x.jpg"), second, temp(), stamp, purpose).okOrNull()
    assertThat(out).isInstanceOf(CopyOutcome.Copied::class.java)
    assertThat((out as CopyOutcome.Copied).bytes).isEqualTo(bytes.size.toLong())
    assertThat(out.stamp.fileId).isNotEqualTo(stamp.fileId)
    assertThat(h.backend.bytes(second, temp().value)).isEqualTo(bytes)
    assertThat(h.backend.bytes(SHARE, "Foto/x.jpg")).isEqualTo(bytes)
    assertThat(h.ops().copyToTemp(SHARE, p("Foto/x.jpg"), second, temp(), null, purpose).okOrNull()).isEqualTo(CopyOutcome.TempExists)
  }

  @Test
  fun copyToTempSorgenteCambiataAssenteBloccataERifiutoDelServerSenzaTemporaneiRimasti() = runTest {
    val h = harness()
    val ops = h.ops()
    val stamp = h.file(SHARE, "x.jpg", byteArrayOf(1, 2, 3))
    h.backend.modify(SHARE, "x.jpg", NasContent.of(byteArrayOf(4)), lastWriteMs = 5_000)
    assertThat(ops.copyToTemp(SHARE, p("x.jpg"), second, temp("1"), stamp, purpose).okOrNull()).isInstanceOf(CopyOutcome.SourceChanged::class.java)
    assertThat(ops.copyToTemp(SHARE, p("niente.jpg"), second, temp("2"), null, purpose).okOrNull()).isEqualTo(CopyOutcome.SourceMissing)
    h.backend.lock(SHARE, "x.jpg", denyRead = true)
    assertThat(ops.copyToTemp(SHARE, p("x.jpg"), second, temp("3"), null, purpose).okOrNull()).isEqualTo(CopyOutcome.Locked)
    h.backend.unlock(SHARE, "x.jpg")
    for (status in listOf(NtStatus.NOT_SUPPORTED, NtStatus.ACCESS_DENIED)) {
      h.backend.copyChunkStatus = status
      assertThat(ops.copyToTemp(SHARE, p("x.jpg"), second, temp("4"), null, purpose).okOrNull()).isEqualTo(CopyOutcome.NotSupported)
      assertThat(h.exists(second, temp("4").value)).isFalse()
    }
    h.backend.copyChunkStatus = null
    for (n in 1..3) assertThat(h.exists(second, temp("$n").value)).isFalse()
  }

  @Test
  fun copyToTempConLaQuotaPienaDaQuotaENonLasciaTemporanei() = runTest {
    val h = Harness(backgroundScope, virtualClock())
    h.backend.addShare("piccola", mapOf(USER to SimAccess.READ_WRITE), quotaBytes = 10)
    val stamp = h.file(SHARE, "grande.bin", ByteArray(100))
    assertThat(h.ops().copyToTemp(SHARE, p("grande.bin"), "piccola", temp(), stamp, purpose).errorOrNull()).isEqualTo(NasError.QuotaExceeded)
    assertThat(h.exists("piccola", temp().value)).isFalse()
  }

  @Test
  fun loScrittoreScriveAOffsetEFinishControllaLaLunghezzaSulloStessoHandle() = runTest {
    val h = harness()
    val writer = h.ops().openTemp(second, temp(), purpose).okOrNull()!!
    assertThat(writer.write(0, byteArrayOf(1, 2, 3, 4), 0, 4)).isEqualTo(NasResult.Ok(Unit))
    assertThat(writer.write(4, byteArrayOf(9, 5, 6), 1, 2)).isEqualTo(NasResult.Ok(Unit))
    assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { writer.write(10, byteArrayOf(1), 0, 1) } }
    assertThat(writer.finish(7).okOrNull()).isInstanceOf(TempFinish.SizeMismatch::class.java)
    assertThat(writer.finish(6).okOrNull()).isInstanceOf(TempFinish.Complete::class.java)
    assertThat(h.backend.bytes(second, temp().value)).isEqualTo(byteArrayOf(1, 2, 3, 4, 5, 6))
    writer.close()
    assertThat(h.ops().openTemp(second, temp(), purpose).errorOrNull()).isEqualTo(NasError.Exists)
  }

  @Test
  fun publishTempPubblicaSenzaSostituireECreaLeCartelle() = runTest {
    val h = harness()
    val ops = h.ops()
    val stamp = h.file(SHARE, "x.jpg", byteArrayOf(8, 8))
    ops.copyToTemp(SHARE, p("x.jpg"), second, temp(), stamp, purpose)
    assertThat(ops.publishTemp(second, temp(), p("Viaggi/2024/x.jpg"), purpose).okOrNull()).isInstanceOf(PublishOutcome.Published::class.java)
    assertThat(h.exists(second, temp().value)).isFalse()
    assertThat(h.backend.bytes(second, "Viaggi/2024/x.jpg")).isEqualTo(byteArrayOf(8, 8))
    assertThat(ops.publishTemp(second, temp(), p("Viaggi/2024/x.jpg"), purpose).okOrNull()).isEqualTo(PublishOutcome.TempMissing)
    ops.copyToTemp(SHARE, p("x.jpg"), second, temp("2"), stamp, purpose)
    assertThat(ops.publishTemp(second, temp("2"), p("viaggi/2024/X.JPG"), purpose).okOrNull()).isEqualTo(PublishOutcome.Collision)
    assertThat(h.exists(second, temp("2").value)).isTrue()
  }

  // --- cestino, cancellazioni ----------------------------------------------------------------------------------------------

  @Test
  fun trashMoveDiUnFileEDiUnaCartellaInteraEUnFileApertoDentroLaFerma() = runTest {
    val h = harness()
    val ops = h.ops()
    val stamp = h.file(SHARE, "Foto/x.jpg", byteArrayOf(1, 2))
    assertThat(ops.trashMove(SHARE, p("Foto/x.jpg"), id(7), stamp, purpose).okOrNull()).isInstanceOf(RenameOutcome.Renamed::class.java)
    assertThat(h.backend.bytes(SHARE, NasLayout.trashItemFile(me, id(7)).value)).isEqualTo(byteArrayOf(1, 2))
    assertThat(h.backend.fileId(SHARE, NasLayout.trashItemFile(me, id(7)).value)).isEqualTo(stamp.fileId)

    h.file(SHARE, "Gita/1.jpg")
    h.file(SHARE, "Gita/Mare/2.jpg")
    h.backend.lock(SHARE, "Gita/Mare/2.jpg")
    assertThat(ops.trashMove(SHARE, p("Gita"), id(8), null, purpose).okOrNull()).isEqualTo(RenameOutcome.Locked)
    assertThat(h.exists(SHARE, "Gita/Mare/2.jpg")).isTrue()
    h.backend.unlock(SHARE, "Gita/Mare/2.jpg")
    assertThat(ops.trashMove(SHARE, p("Gita"), id(8), null, purpose).okOrNull()).isInstanceOf(RenameOutcome.Renamed::class.java)
    assertThat(h.exists(SHARE, NasLayout.trashItemFile(me, id(8)).child("Mare").child("2.jpg").value)).isTrue()
  }

  @Test
  fun deleteIfUnchangedControllaLaDimensioneSulloStessoHandle() = runTest {
    val h = harness()
    val ops = h.ops()
    val inTrash = NasLayout.trashItemFile(me, id(3))
    h.backend.putContent(SHARE, inTrash.value, NasContent.of(byteArrayOf(1, 2, 3)))
    assertThat(ops.deleteIfUnchanged(SHARE, inTrash, 4, purpose).okOrNull()).isEqualTo(DeleteOutcome.SizeChanged(3))
    assertThat(h.exists(SHARE, inTrash.value)).isTrue()
    h.backend.lock(SHARE, inTrash.value)
    assertThat(ops.deleteIfUnchanged(SHARE, inTrash, 3, purpose).okOrNull()).isEqualTo(DeleteOutcome.Locked)
    h.backend.unlock(SHARE, inTrash.value)
    // La dimensione e la cancellazione passano da UNA apertura: nessuna seconda apertura fra controllo e cancellazione.
    val opens = ArrayList<String>()
    h.backend.beforeOp = { op, _ -> opens += op.name }
    assertThat(ops.deleteIfUnchanged(SHARE, inTrash, 3, purpose).okOrNull()).isEqualTo(DeleteOutcome.Deleted)
    h.backend.beforeOp = null
    assertThat(opens.filter { it.startsWith("OPEN") || it == "STAT" }).containsExactly("OPEN_CHANGE")
    assertThat(h.exists(SHARE, inTrash.value)).isFalse()
    assertThat(ops.deleteIfUnchanged(SHARE, inTrash, 3, purpose).okOrNull()).isEqualTo(DeleteOutcome.Missing)
    h.backend.putDir(SHARE, NasLayout.trashItemFile(me, id(4)).value)
    assertThat(ops.deleteIfUnchanged(SHARE, NasLayout.trashItemFile(me, id(4)), 0, purpose).okOrNull()).isEqualTo(DeleteOutcome.NotAFile)
  }

  @Test
  fun rmdirIfEmptyTogliSoloLeCartelleVuoteENelCestinoProprio() = runTest {
    val h = harness()
    val ops = h.ops()
    h.file(SHARE, "Gita/1.jpg")
    assertThat(ops.rmdirIfEmpty(SHARE, p("Gita"), purpose).okOrNull()).isEqualTo(RmdirOutcome.NotEmpty)
    h.backend.delete(SHARE, "Gita/1.jpg")
    assertThat(ops.rmdirIfEmpty(SHARE, p("Gita"), purpose).okOrNull()).isEqualTo(RmdirOutcome.Removed)
    assertThat(ops.rmdirIfEmpty(SHARE, p("Gita"), purpose).okOrNull()).isEqualTo(RmdirOutcome.Missing)
    val mine = NasLayout.trashItemFile(me, id(5))
    h.backend.putDir(SHARE, mine.value)
    assertThat(ops.rmdirIfEmpty(SHARE, mine, purpose).okOrNull()).isEqualTo(RmdirOutcome.Removed)
  }

  @Test
  fun spazioConTotaleEQuota() = runTest {
    val h = Harness(backgroundScope, virtualClock())
    h.backend.addShare("quota", mapOf(USER to SimAccess.READ_WRITE), quotaBytes = 1_000)
    val space = h.ops().space(SHARE, purpose).okOrNull()!!
    assertThat(space.totalBytes).isEqualTo(h.backend.volumeTotalBytes)
    assertThat(space.quotaLimited).isFalse()
    assertThat(h.ops().space("quota", purpose).okOrNull()!!.quotaLimited).isTrue()
  }

  @Test
  fun inUnaShareInSolaLetturaNessunaScrittura() = runTest {
    val h = Harness(backgroundScope, virtualClock())
    h.backend.addShare("sola", mapOf(USER to SimAccess.READ))
    h.file("sola", "x.jpg")
    assertThat(h.ops().mkdirs("sola", p("Nuova"), purpose).errorOrNull()).isEqualTo(NasError.PermissionDenied)
    assertThat(h.ops().rename("sola", p("x.jpg"), p("y.jpg"), null, purpose).errorOrNull()).isEqualTo(NasError.PermissionDenied)
    assertThat(h.ops().trashMove("sola", p("x.jpg"), id(1), null, purpose).errorOrNull()).isEqualTo(NasError.PermissionDenied)
    assertThat(h.exists("sola", "x.jpg")).isTrue()
  }

  // --- guasti a ogni richiesta SMB, prima e dopo ---------------------------------------------------------------------------

  /**
   * Una primitiva con un guasto a ciascuna delle sue richieste SMB (contate con un giro pulito), prima e dopo che il server
   * l'abbia applicata: l'esito e' un errore (mai un successo inventato), il NAS e' nello stato di prima o in quello di dopo,
   * mai a meta', e ripetere la chiamata arriva a uno stato riconoscibile.
   */
  private suspend fun TestScope.everyRequest(
    name: String,
    setup: (Harness) -> Unit,
    call: suspend (NasFileOps) -> NasResult<*>,
    check: (Harness, String) -> Unit,
  ) {
    val clean = harness()
    setup(clean)
    val counter = FaultPlan()
    clean.backend.faults = counter
    call(clean.ops())
    val total = counter.operations
    assertWithMessage("$name: richieste contate").that(total).isAtLeast(1)
    var fired = 0
    for (n in 1..total) {
      for (phase in FaultPhase.entries) {
        val h = harness()
        setup(h)
        val plan = FaultPlan().at(n, phase, SimFault.DropConnection)
        h.backend.faults = plan
        val result = call(h.ops())
        h.backend.faults = null
        val label = "$name, guasto $n/$total $phase"
        if (plan.fired.isEmpty()) continue
        fired++
        assertWithMessage(label).that(result).isInstanceOf(NasResult.Err::class.java)
        check(h, label)
      }
    }
    assertWithMessage("$name: guasti scattati").that(fired).isAtLeast(total)
  }

  @Test
  fun renameAOgniRichiestaPrimaEDopo() = runTest {
    everyRequest(
      "rinomina",
      setup = { h ->
        h.file(SHARE, "A/x.jpg", byteArrayOf(5, 5))
        h.backend.putDir(SHARE, "B")
      },
      call = { ops -> ops.rename(SHARE, p("A/x.jpg"), p("B/x.jpg"), null, purpose) },
      check = { h, label ->
        val atSource = h.exists(SHARE, "A/x.jpg")
        val atTarget = h.exists(SHARE, "B/x.jpg")
        assertWithMessage("$label: uno e un solo posto").that(atSource xor atTarget).isTrue()
      },
    )
  }

  @Test
  fun copiaETrashMoveEDeleteAOgniRichiestaPrimaEDopo() = runTest {
    everyRequest(
      "copia lato server",
      setup = { h -> h.file(SHARE, "x.jpg", ByteArray(2 * 1024 * 1024 + 3) { it.toByte() }) },
      call = { ops -> ops.copyToTemp(SHARE, p("x.jpg"), second, temp(), null, purpose) },
      check = { h, label ->
        assertWithMessage("$label: sorgente intatta").that(h.backend.bytes(SHARE, "x.jpg")!!.size).isEqualTo(2 * 1024 * 1024 + 3)
        // Il temporaneo puo' restare (anche a meta'): e' proprio, e la ripresa dal diario lo riconosce dalla dimensione.
        assertWithMessage("$label: niente fuori dai temporanei").that(h.backend.children(second, "").filterNot { it == ".mycloudgallery" }).isEmpty()
      },
    )
    everyRequest(
      "cestino",
      setup = { h -> h.file(SHARE, "Foto/x.jpg") },
      call = { ops -> ops.trashMove(SHARE, p("Foto/x.jpg"), id(9), null, purpose) },
      check = { h, label ->
        val visible = h.exists(SHARE, "Foto/x.jpg")
        val trashed = h.exists(SHARE, NasLayout.trashItemFile(me, id(9)).value)
        assertWithMessage("$label: o visibile o nel cestino").that(visible xor trashed).isTrue()
      },
    )
    everyRequest(
      "eliminazione con un handle",
      setup = { h -> h.backend.putContent(SHARE, NasLayout.trashItemFile(me, id(3)).value, NasContent.of(byteArrayOf(1, 2, 3))) },
      call = { ops -> ops.deleteIfUnchanged(SHARE, NasLayout.trashItemFile(me, id(3)), 3, purpose) },
      check = { h, label ->
        // O c'e' ancora intero o non c'e' piu': la ripresa trova Missing e lo tratta come fatto.
        val bytes = h.backend.bytes(SHARE, NasLayout.trashItemFile(me, id(3)).value)
        assertWithMessage(label).that(bytes == null || bytes.contentEquals(byteArrayOf(1, 2, 3))).isTrue()
      },
    )
  }

  @Test
  fun dopoUnaRispostaPersaLaRipetizioneTrovaLoStatoGiusto() = runTest {
    val h = harness()
    val ops = h.ops()
    h.file(SHARE, "x.jpg")
    h.backend.putDir(SHARE, "Dir")
    h.backend.faults = FaultPlan().at(1, FaultPhase.AFTER, SimFault.DropConnection, op = dev.pampa.mycloudgallery.core.nas.testing.SimOp.RENAME)
    assertThat(ops.rename(SHARE, p("x.jpg"), p("Dir/x.jpg"), null, purpose).errorOrNull()).isEqualTo(NasError.Timeout)
    h.backend.faults = null
    assertThat(h.exists(SHARE, "Dir/x.jpg")).isTrue()
    // Nessun nuovo tentativo automatico della sessione: la ripetizione la decide chi ha il diario.
    assertThat(ops.rename(SHARE, p("x.jpg"), p("Dir/x.jpg"), null, purpose).okOrNull()).isEqualTo(RenameOutcome.SourceMissing)
  }

  // --- recinzione, radice di prova, consegna ----------------------------------------------------------------------------

  @Test
  fun laRecinzioneD33FermaAncheLeScrittureSenzaRichieste() = runTest {
    val h = Harness(backgroundScope, virtualClock(), shareGuard = { _, share -> share.equals(SHARE, ignoreCase = true) })
    h.backend.addShare(second, mapOf(USER to SimAccess.READ_WRITE))
    h.file(SHARE, "x.jpg")
    val before = h.backend.shareOps.get()
    val ops = h.ops()
    assertThat(ops.mkdirs(second, p("x"), purpose).errorOrNull()).isEqualTo(NasError.PermissionDenied)
    assertThat(ops.copyToTemp(second, p("x.jpg"), SHARE, temp(), null, purpose).errorOrNull()).isEqualTo(NasError.PermissionDenied)
    assertThat(ops.copyToTemp(SHARE, p("x.jpg"), second, temp(), null, purpose).errorOrNull()).isEqualTo(NasError.PermissionDenied)
    assertThat(h.backend.shareOps.get()).isEqualTo(before)
  }

  @Test
  fun conLaRadiceDiProvaTuttoFinisceSottoLaRadice() = runTest {
    val h = harness()
    val root = SharePath.of("mcg-it", "20261015-000000-abcdef01")
    val ops = SmbNasFileOps(h.manager, me, root)
    h.file(SHARE, "${root.value}/Foto/x.jpg")
    assertThat(ops.trashMove(SHARE, p("Foto/x.jpg"), id(1), null, purpose).okOrNull()).isInstanceOf(RenameOutcome.Renamed::class.java)
    assertThat(h.exists(SHARE, "${root.value}/${NasLayout.trashItemFile(me, id(1)).value}")).isTrue()
    assertThat(h.backend.children(SHARE, "").toSet()).containsExactly("mcg-it")
  }

  @Test
  fun nasSessionManagerNonEsponeLePrimitive() {
    // Anche i metodi `internal` sono pubblici per la JVM: nessuno, di nessun tipo, restituisce le primitive.
    for (type in listOf(NasSessionManager::class.java, SmbNasSessionManager::class.java)) {
      val exposed = type.methods.filter { m ->
        NasFileOps::class.java.isAssignableFrom(m.returnType) || NasTempWriter::class.java.isAssignableFrom(m.returnType) ||
          m.name.contains("fileOps", ignoreCase = true)
      }.map { it.name }
      assertWithMessage("${type.simpleName} espone le primitive di scrittura").that(exposed).isEmpty()
    }
  }

  @Test
  fun laFabbricaSuUnaSessioneFintaNonScriveNiente() = runTest {
    val ops = NasFileOpsFactory.create(FakeNasSessionManager(), me)
    assertThat(ops).isSameInstanceAs(NoNasFileOps)
    val reader: NasMediaReader = FakeNasSessionManager().mediaReader()
    assertThat(reader).isNotNull()
  }
}
