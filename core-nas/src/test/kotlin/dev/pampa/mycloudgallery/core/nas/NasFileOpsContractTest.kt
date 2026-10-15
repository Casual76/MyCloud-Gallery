package dev.pampa.mycloudgallery.core.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.contract.okOrNull
import dev.pampa.mycloudgallery.core.nas.noop.NoNasFileOps
import dev.pampa.mycloudgallery.core.nas.ops.CopyOutcome
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.NasOpsPaths
import dev.pampa.mycloudgallery.core.nas.ops.NasSpaceInfo
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.RenameOutcome
import dev.pampa.mycloudgallery.core.nas.ops.RmdirOutcome
import dev.pampa.mycloudgallery.core.nas.ops.TempFinish
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.ServerCopyMode
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Il contratto delle primitive di scrittura (`NasFileOps`, docs/fase4-progetto.md §1.2 4B) sul NAS finto: mai sostituire, mai eliminare
 * un media, rinomina con il timbro controllato, temporanei propri, cestino per rinomina, eliminazione solo dentro `.mycloudgallery/`, e
 * i guasti "prima/dopo" che P15 usa per la prova delle 500 interruzioni.
 */
class NasFileOpsContractTest {

  private val me = Base32.encode(ByteArray(16) { 0x11 })
  private val other = Base32.encode(ByteArray(16) { 0x22 })
  private val nas = InMemoryNasMedia().apply {
    addShare("a")
    addShare("b")
  }
  private val ops = nas.fileOps(me)
  private val purpose = Purpose.INTERACTIVE

  private fun p(path: String) = SharePath.parse(path)

  private fun file(share: String, path: String, bytes: ByteArray = byteArrayOf(1, 2, 3)): NasStamp {
    val id = nas.putFile(share, path, NasContent.of(bytes), modifiedAt = 1_000)
    return NasStamp(bytes.size.toLong(), 1_000, id)
  }

  private fun temp(name: String = "op-1") = NasLayout.ROOT.child("tmp").child("$me-$name")

  private fun id(n: Int) = Base32.encode(ByteArray(16) { n.toByte() })

  // --- mkdirs -----------------------------------------------------------------------------------------------------------------

  @Test
  fun mkdirsCreaLeCartelleEPerCartellaEsistenteEUnSuccesso() = runTest {
    assertThat(ops.mkdirs("a", p("Foto/2024/Mare"), purpose)).isEqualTo(NasResult.Ok(Unit))
    assertThat(nas.exists("a", "Foto/2024/Mare")).isTrue()
    assertThat(ops.mkdirs("a", p("foto/2024"), purpose)).isEqualTo(NasResult.Ok(Unit))
  }

  @Test
  fun mkdirsOveEUnFileDaExists() = runTest {
    file("a", "Foto/x.jpg")
    assertThat(ops.mkdirs("a", p("Foto/x.jpg"), purpose).errorOrNull()).isEqualTo(NasError.Exists)
    assertThat(ops.mkdirs("a", p("Foto/x.jpg/sotto"), purpose).errorOrNull()).isEqualTo(NasError.Exists)
  }

  // --- rename -----------------------------------------------------------------------------------------------------------------

  @Test
  fun renameSpostaSenzaSostituireEConservaIlFileId() = runTest {
    val stamp = file("a", "Foto/x.jpg")
    nas.putDir("a", "Altro")
    val out = ops.rename("a", p("Foto/x.jpg"), p("Altro/y.jpg"), stamp, purpose).okOrNull()
    assertThat(out).isInstanceOf(RenameOutcome.Renamed::class.java)
    assertThat((out as RenameOutcome.Renamed).stamp!!.fileId).isEqualTo(stamp.fileId)
    assertThat(nas.exists("a", "Foto/x.jpg")).isFalse()
    assertThat(nas.exists("a", "Altro/y.jpg")).isTrue()
  }

  @Test
  fun renameNonSostituisceMai() = runTest {
    file("a", "x.jpg", byteArrayOf(1))
    file("a", "y.jpg", byteArrayOf(2))
    assertThat(ops.rename("a", p("x.jpg"), p("y.jpg"), null, purpose).okOrNull()).isEqualTo(RenameOutcome.Collision)
    assertThat(nas.bytes("a", "y.jpg")).isEqualTo(byteArrayOf(2))
    assertThat(nas.bytes("a", "x.jpg")).isEqualTo(byteArrayOf(1))
  }

  @Test
  fun renameSoloDiMaiuscoleNonSiFaInUnColpo() = runTest {
    file("a", "foto.jpg")
    // E-N7: si passa da un nome temporaneo; il fake e' prudente e lo impone.
    assertThat(ops.rename("a", p("foto.jpg"), p("FOTO.jpg"), null, purpose).okOrNull()).isEqualTo(RenameOutcome.Collision)
  }

  @Test
  fun renameSorgenteAssenteCambiataOBloccata() = runTest {
    assertThat(ops.rename("a", p("niente.jpg"), p("x.jpg"), null, purpose).okOrNull()).isEqualTo(RenameOutcome.SourceMissing)

    val stamp = file("a", "x.jpg", byteArrayOf(1, 2, 3))
    nas.modify("a", "x.jpg", NasContent.of(byteArrayOf(9, 9)), modifiedAt = 2_000)
    val changed = ops.rename("a", p("x.jpg"), p("z.jpg"), stamp, purpose).okOrNull()
    assertThat(changed).isInstanceOf(RenameOutcome.SourceChanged::class.java)
    assertThat(nas.exists("a", "x.jpg")).isTrue()
    assertThat(nas.exists("a", "z.jpg")).isFalse()

    nas.lock("a", "x.jpg")
    assertThat(ops.rename("a", p("x.jpg"), p("z.jpg"), null, purpose).okOrNull()).isEqualTo(RenameOutcome.Locked)
  }

  @Test
  fun renameVersoUnaCartellaCheNonCeESecondoUnaCartellaDentroSeStessaSonoErrori() = runTest {
    file("a", "x.jpg")
    assertThat(ops.rename("a", p("x.jpg"), p("Manca/x.jpg"), null, purpose).errorOrNull()).isEqualTo(NasError.NotFound)
    nas.putDir("a", "Dir")
    assertThat(ops.rename("a", p("Dir"), p("Dir/Sotto"), null, purpose).errorOrNull()).isInstanceOf(NasError.Unexpected::class.java)
  }

  @Test
  fun renameFuoriDalleRegoleELErroreDiProgrammazione() = runTest {
    file("a", "x.jpg")
    // Mai da/verso l'area dell'app, tranne il ripristino dal cestino.
    assertThrows(IllegalArgumentException::class.java) {
      kotlinx.coroutines.runBlocking { ops.rename("a", p("x.jpg"), p(".mycloudgallery/x"), null, purpose) }
    }
    assertThrows(IllegalArgumentException::class.java) {
      kotlinx.coroutines.runBlocking { ops.rename("a", p(".mycloudgallery/devices/x"), p("x.jpg"), null, purpose) }
    }
  }

  // --- copyToTemp, openTemp, publishTemp --------------------------------------------------------------------------------------

  @Test
  fun copyToTempCopiaSenzaToccareLaSorgenteENonSostituisce() = runTest {
    val stamp = file("a", "Foto/x.jpg", byteArrayOf(5, 6, 7))
    val t = temp()
    val out = ops.copyToTemp("a", p("Foto/x.jpg"), "b", t, stamp, purpose).okOrNull()
    assertThat(out).isInstanceOf(CopyOutcome.Copied::class.java)
    assertThat((out as CopyOutcome.Copied).bytes).isEqualTo(3)
    assertThat(nas.bytes("b", t.value)).isEqualTo(byteArrayOf(5, 6, 7))
    assertThat(nas.bytes("a", "Foto/x.jpg")).isEqualTo(byteArrayOf(5, 6, 7))
    assertThat(ops.copyToTemp("a", p("Foto/x.jpg"), "b", t, null, purpose).okOrNull()).isEqualTo(CopyOutcome.TempExists)
  }

  @Test
  fun copyToTempSorgenteCambiataAssenteERifiutoDelServer() = runTest {
    val stamp = file("a", "x.jpg", byteArrayOf(1, 2, 3))
    nas.modify("a", "x.jpg", NasContent.of(byteArrayOf(4)), modifiedAt = 5_000)
    assertThat(ops.copyToTemp("a", p("x.jpg"), "b", temp("1"), stamp, purpose).okOrNull()).isInstanceOf(CopyOutcome.SourceChanged::class.java)
    assertThat(ops.copyToTemp("a", p("niente.jpg"), "b", temp("2"), null, purpose).okOrNull()).isEqualTo(CopyOutcome.SourceMissing)
    nas.serverCopyMode = ServerCopyMode.ACCESS_DENIED
    assertThat(ops.copyToTemp("a", p("x.jpg"), "b", temp("3"), null, purpose).okOrNull()).isEqualTo(CopyOutcome.NotSupported)
  }

  @Test
  fun unTemporaneoDiUnAltroDispositivoNonSiTocca() = runTest {
    file("a", "x.jpg")
    val foreign = NasLayout.ROOT.child("tmp").child("$other-op-1")
    assertThrows(IllegalArgumentException::class.java) {
      kotlinx.coroutines.runBlocking { ops.copyToTemp("a", p("x.jpg"), "b", foreign, null, purpose) }
    }
    assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { ops.deleteOwnTemp("b", foreign, purpose) } }
    assertThrows(IllegalArgumentException::class.java) {
      kotlinx.coroutines.runBlocking { ops.openTemp("b", NasLayout.ROOT.child("devices"), purpose) }
    }
  }

  @Test
  fun lScrittoreScriveAOffsetEFinishControllaLaLunghezza() = runTest {
    val writer = ops.openTemp("b", temp(), purpose).okOrNull()!!
    assertThat(writer.write(0, byteArrayOf(1, 2, 3, 4), 0, 4)).isEqualTo(NasResult.Ok(Unit))
    assertThat(writer.write(4, byteArrayOf(9, 5, 6), 1, 2)).isEqualTo(NasResult.Ok(Unit))
    assertThat(writer.finish(7).okOrNull()).isInstanceOf(TempFinish.SizeMismatch::class.java)
    val done = writer.finish(6).okOrNull()
    assertThat(done).isInstanceOf(TempFinish.Complete::class.java)
    assertThat(nas.bytes("b", temp().value)).isEqualTo(byteArrayOf(1, 2, 3, 4, 5, 6))
    writer.close()
    assertThat(ops.openTemp("b", temp(), purpose).errorOrNull()).isEqualTo(NasError.Exists)
  }

  @Test
  fun publishTempPubblicaSenzaSostituireECreaLeCartelle() = runTest {
    val stamp = file("a", "x.jpg", byteArrayOf(8, 8))
    ops.copyToTemp("a", p("x.jpg"), "b", temp(), stamp, purpose)
    val published = ops.publishTemp("b", temp(), p("Viaggi/2024/x.jpg"), purpose).okOrNull()
    assertThat(published).isInstanceOf(PublishOutcome.Published::class.java)
    assertThat(nas.exists("b", temp().value)).isFalse()
    assertThat(nas.bytes("b", "Viaggi/2024/x.jpg")).isEqualTo(byteArrayOf(8, 8))

    // Il temporaneo non c'e' piu': e' il segnale della risposta persa.
    assertThat(ops.publishTemp("b", temp(), p("Viaggi/2024/x.jpg"), purpose).okOrNull()).isEqualTo(PublishOutcome.TempMissing)

    ops.copyToTemp("a", p("x.jpg"), "b", temp("2"), stamp, purpose)
    assertThat(ops.publishTemp("b", temp("2"), p("Viaggi/2024/x.jpg"), purpose).okOrNull()).isEqualTo(PublishOutcome.Collision)
    assertThat(nas.exists("b", temp("2").value)).isTrue()
  }

  // --- trashMove, deleteIfUnchanged, deleteOwnTemp, rmdirIfEmpty ------------------------------------------------------------

  @Test
  fun trashMoveSpostaNelCestinoProprioENonEliminaNiente() = runTest {
    val stamp = file("a", "Foto/x.jpg", byteArrayOf(1, 2))
    val trashId = id(7)
    val out = ops.trashMove("a", p("Foto/x.jpg"), trashId, stamp, purpose).okOrNull()
    assertThat(out).isInstanceOf(RenameOutcome.Renamed::class.java)
    assertThat(nas.exists("a", "Foto/x.jpg")).isFalse()
    assertThat(nas.bytes("a", NasLayout.trashItemFile(me, trashId).value)).isEqualTo(byteArrayOf(1, 2))
  }

  @Test
  fun trashMoveDiUnaCartellaIntera() = runTest {
    file("a", "Gita/1.jpg")
    file("a", "Gita/2.jpg")
    val trashId = id(8)
    assertThat(ops.trashMove("a", p("Gita"), trashId, null, purpose).okOrNull()).isInstanceOf(RenameOutcome.Renamed::class.java)
    assertThat(nas.exists("a", "Gita")).isFalse()
    assertThat(nas.exists("a", NasLayout.trashItemFile(me, trashId).child("1.jpg").value)).isTrue()
  }

  @Test
  fun nelCestinoSiMetteUnMediaMaiUnFileDellApp() = runTest {
    assertThrows(IllegalArgumentException::class.java) {
      kotlinx.coroutines.runBlocking { ops.trashMove("a", p(".mycloudgallery/devices"), id(1), null, purpose) }
    }
    assertThrows(IllegalArgumentException::class.java) {
      kotlinx.coroutines.runBlocking { ops.trashMove("a", p("x.jpg"), "non-un-id", null, purpose) }
    }
  }

  @Test
  fun deleteIfUnchangedEliminaSoloDentroLAreaDellAppEConLaDimensioneGiusta() = runTest {
    val inTrash = NasLayout.trashItemFile(me, id(3))
    nas.putFile("a", inTrash.value, NasContent.of(byteArrayOf(1, 2, 3)))
    assertThat(ops.deleteIfUnchanged("a", inTrash, 4, purpose).okOrNull()).isEqualTo(DeleteOutcome.SizeChanged(3))
    assertThat(nas.exists("a", inTrash.value)).isTrue()
    nas.lock("a", inTrash.value)
    assertThat(ops.deleteIfUnchanged("a", inTrash, 3, purpose).okOrNull()).isEqualTo(DeleteOutcome.Locked)
    nas.unlock("a", inTrash.value)
    assertThat(ops.deleteIfUnchanged("a", inTrash, 3, purpose).okOrNull()).isEqualTo(DeleteOutcome.Deleted)
    assertThat(nas.exists("a", inTrash.value)).isFalse()
    assertThat(ops.deleteIfUnchanged("a", inTrash, 3, purpose).okOrNull()).isEqualTo(DeleteOutcome.Missing)
  }

  @Test
  fun unMediaNonSiEliminaMai() = runTest {
    file("a", "Foto/x.jpg")
    assertThrows(IllegalArgumentException::class.java) {
      kotlinx.coroutines.runBlocking { ops.deleteIfUnchanged("a", p("Foto/x.jpg"), 3, purpose) }
    }
    assertThat(nas.exists("a", "Foto/x.jpg")).isTrue()
  }

  @Test
  fun deleteOwnTempTogliSoloIlProprio() = runTest {
    file("a", "x.jpg")
    ops.copyToTemp("a", p("x.jpg"), "b", temp(), null, purpose)
    assertThat(ops.deleteOwnTemp("b", temp(), purpose).okOrNull()).isEqualTo(DeleteOutcome.Deleted)
    assertThat(ops.deleteOwnTemp("b", temp(), purpose).okOrNull()).isEqualTo(DeleteOutcome.Missing)
  }

  @Test
  fun rmdirIfEmptyTogliSoloLeCartelleVuote() = runTest {
    file("a", "Gita/1.jpg")
    assertThat(ops.rmdirIfEmpty("a", p("Gita"), purpose).okOrNull()).isEqualTo(RmdirOutcome.NotEmpty)
    nas.delete("a", "Gita/1.jpg")
    assertThat(ops.rmdirIfEmpty("a", p("Gita"), purpose).okOrNull()).isEqualTo(RmdirOutcome.Removed)
    assertThat(ops.rmdirIfEmpty("a", p("Gita"), purpose).okOrNull()).isEqualTo(RmdirOutcome.Missing)
  }

  @Test
  fun rmdirNelCestinoProprioSiMaNelCestinoDiUnAltroNo() = runTest {
    val mine = NasLayout.trashItemFile(me, id(5))
    val theirs = NasLayout.trashItemFile(other, id(5))
    nas.putDir("a", mine.value)
    nas.putDir("a", theirs.value)
    assertThat(ops.rmdirIfEmpty("a", mine, purpose).okOrNull()).isEqualTo(RmdirOutcome.Removed)
    assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { ops.rmdirIfEmpty("a", theirs, purpose) } }
  }

  // --- spazio, sola lettura, guasti ------------------------------------------------------------------------------------------

  @Test
  fun spaceEQuotaEDischiPieni() = runTest {
    assertThat(ops.space("a", purpose).okOrNull()!!.quotaLimited).isFalse()
    nas.spaceOf = { NasSpaceInfo(totalBytes = 1_000, freeBytes = 800, callerFreeBytes = 10) }
    assertThat(ops.space("a", purpose).okOrNull()!!.quotaLimited).isTrue()
    val stamp = file("a", "grande.bin", ByteArray(100))
    assertThat(ops.copyToTemp("a", p("grande.bin"), "b", temp(), stamp, purpose).errorOrNull()).isEqualTo(NasError.QuotaExceeded)
    nas.spaceOf = { NasSpaceInfo(totalBytes = 1_000, freeBytes = 10, callerFreeBytes = 10) }
    assertThat(ops.copyToTemp("a", p("grande.bin"), "b", temp(), stamp, purpose).errorOrNull()).isEqualTo(NasError.NasFull)
  }

  @Test
  fun inUnaShareInSolaLetturaOgniScritturaESenzaPermesso() = runTest {
    file("a", "x.jpg")
    nas.setReadOnly("a", true)
    assertThat(ops.mkdirs("a", p("Nuova"), purpose).errorOrNull()).isEqualTo(NasError.PermissionDenied)
    assertThat(ops.rename("a", p("x.jpg"), p("y.jpg"), null, purpose).errorOrNull()).isEqualTo(NasError.PermissionDenied)
    assertThat(ops.trashMove("a", p("x.jpg"), id(1), null, purpose).errorOrNull()).isEqualTo(NasError.PermissionDenied)
    assertThat(nas.exists("a", "x.jpg")).isTrue()
    // La lettura e lo spazio restano.
    assertThat(ops.space("a", purpose).okOrNull()).isNotNull()
  }

  @Test
  fun unGuastoPrimaNonApplicaUnoDopoApplicaEPerdeLaRisposta() = runTest {
    file("a", "x.jpg")
    nas.putDir("a", "Dir")
    nas.opsFaults = FaultPlan().at(1, FaultPhase.BEFORE, SimFault.DropConnection, op = SimOp.RENAME)
    assertThat(ops.rename("a", p("x.jpg"), p("Dir/x.jpg"), null, purpose).errorOrNull()).isEqualTo(NasError.Timeout)
    assertThat(nas.exists("a", "x.jpg")).isTrue()
    assertThat(nas.exists("a", "Dir/x.jpg")).isFalse()

    nas.opsFaults = FaultPlan().at(1, FaultPhase.AFTER, SimFault.DropConnection, op = SimOp.RENAME)
    assertThat(ops.rename("a", p("x.jpg"), p("Dir/x.jpg"), null, purpose).errorOrNull()).isEqualTo(NasError.Timeout)
    // La rinomina e' avvenuta, la risposta no: la ripresa lo scopre guardando.
    assertThat(nas.exists("a", "x.jpg")).isFalse()
    assertThat(nas.exists("a", "Dir/x.jpg")).isTrue()
    assertThat(ops.rename("a", p("x.jpg"), p("Dir/x.jpg"), null, purpose).okOrNull()).isEqualTo(RenameOutcome.SourceMissing)
  }

  @Test
  fun iGuastiSiContanoEdFermanoLeOperazioniDiOgniTipo() = runTest {
    nas.opsFaults = FaultPlan().at(1, FaultPhase.BEFORE, SimFault.ExpireSession).at(1, FaultPhase.BEFORE, SimFault.Stall, op = SimOp.SPACE)
    assertThat(ops.mkdirs("a", p("Nuova"), purpose).errorOrNull()).isEqualTo(NasError.SessionExpired)
    assertThat(ops.space("a", purpose).errorOrNull()).isEqualTo(NasError.Stalled)
    assertThat(nas.opCount(FakeNasOp.MKDIRS)).isEqualTo(1)
    assertThat(nas.opCount(FakeNasOp.SPACE)).isEqualTo(1)
  }

  // --- percorsi, null-object --------------------------------------------------------------------------------------------------

  @Test
  fun iPercorsiDellAppSiRiconosconoAncheConMaiuscoleEForme() {
    assertThat(NasOpsPaths.isAppArea(p(".mycloudgallery/trash"))).isTrue()
    assertThat(NasOpsPaths.isAppArea(p(".MyCloudGallery/x"))).isTrue()
    assertThat(NasOpsPaths.isAppArea(p("Foto/.mycloudgallery"))).isFalse()
    assertThat(NasOpsPaths.isTrashItem(NasLayout.trashItemFile(me, id(1)))).isTrue()
    assertThat(NasOpsPaths.isTrashItem(NasLayout.ROOT.child("trash").child(me))).isFalse()
    assertThat(NasOpsPaths.isTempOf(temp(), me)).isTrue()
    assertThat(NasOpsPaths.isTempOf(temp(), other)).isFalse()
    assertThat(NasOpsPaths.isOwnTrashTree(NasLayout.trashItemFile(me, id(1)).child("sub"), me)).isTrue()
    assertThat(NasOpsPaths.isOwnTrashTree(NasLayout.trashItemFile(other, id(1)), me)).isFalse()
  }

  @Test
  fun ilNullObjectNonScriveNienteEFallisceChiuso() = runTest {
    assertThat(NoNasFileOps.mkdirs("a", p("x"), purpose).errorOrNull()).isInstanceOf(NasError.NotAllowedHere::class.java)
    assertThat(NoNasFileOps.rename("a", p("x"), p("y"), null, purpose).errorOrNull()).isInstanceOf(NasError.NotAllowedHere::class.java)
    assertThat(NoNasFileOps.deleteIfUnchanged("a", p(".mycloudgallery/x"), 1, purpose).errorOrNull())
      .isInstanceOf(NasError.NotAllowedHere::class.java)
    assertThat(NoNasFileOps.space("a", purpose).errorOrNull()).isInstanceOf(NasError.NotAllowedHere::class.java)
  }
}
