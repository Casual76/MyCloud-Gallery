package dev.pampa.mycloudgallery.core.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.contract.okOrNull
import dev.pampa.mycloudgallery.core.nas.noop.NoNasBackupOps
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.TempFinish
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOpsFactory
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupPaths
import dev.pampa.mycloudgallery.core.nas.ops.backup.ReopenOutcome
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasSessionManager
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 5A (P0, D2, D5, D33): il contratto delle primitive del backup (`NasBackupOps`) sul NAS finto. Solo **aggiungere**: temporanei
 * del backup del dispositivo (`<io>-bk-`), pubblicazione senza sostituire, riapertura con l'accorciamento al punto di ripresa,
 * guasti prima e dopo l'applicazione come per la 4B. La vista vera sopra SMB (con `reopenTemp`, P2) e' in `ops/backup/SmbNasBackupOpsTest`:
 * qui si prova anche che, sopra primitive che non sono SMB, non finge un successo.
 */
class NasBackupOpsContractTest {

  private val me = Base32.encode(ByteArray(16) { 0x11 })
  private val other = Base32.encode(ByteArray(16) { 0x22 })
  private val nas = InMemoryNasMedia().apply { addShare("test") }
  private val ops = nas.backupOps(me)
  private val purpose = Purpose.BACKGROUND

  private fun p(path: String) = SharePath.parse(path)

  private fun bytes(n: Int, seed: Int = 7) = ByteArray(n) { (it * 31 + seed).toByte() }

  private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toList()

  @Test
  fun iTemporaneiSonoSoloQuelliDelBackupDelDispositivo() {
    val mine = NasBackupPaths.tempFor(me, 42)
    assertThat(mine.value).isEqualTo(".mycloudgallery/tmp/$me-bk-42")
    assertThat(NasBackupPaths.isBackupTempOf(mine, me)).isTrue()
    assertThat(NasBackupPaths.isBackupTempOf(NasBackupPaths.tempFor(me, 42, attempt = 2), me)).isTrue()
    assertThat(NasBackupPaths.isBackupTempOf(mine, other)).isFalse()
    // Un temporaneo delle operazioni della 4B (`-op-`) o uno senza segno non e' del backup: il backup non lo puo' toccare.
    assertThat(NasBackupPaths.isBackupTempOf(NasLayout.ROOT.child("tmp").child("$me-op-1-0"), me)).isFalse()
    assertThat(NasBackupPaths.isBackupTempOf(NasLayout.ROOT.child("tmp").child("$me-bk-"), me)).isFalse()
    assertThat(NasBackupPaths.isBackupTempOf(p("Backup/$me-bk-1"), me)).isFalse()
    runTest {
      val opTemp = NasLayout.ROOT.child("tmp").child("$me-op-1-0")
      nas.putFile("test", opTemp.value, NasContent.of(bytes(3)))
      assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { ops.deleteOwnTemp("test", opTemp, purpose) } }
      assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { ops.openTemp("test", NasBackupPaths.tempFor(other, 1), purpose) } }
      assertThat(nas.exists("test", opTemp.value)).isTrue()
    }
  }

  @Test
  fun caricaVerificaEPubblicaSenzaMaiSostituire() = runTest {
    val temp = NasBackupPaths.tempFor(me, 1)
    val data = bytes(200_000)
    val writer = ops.openTemp("test", temp, purpose).okOrNull()!!
    writer.write(0, data, 0, 100_000)
    // Il punto di ripresa dice fin dove arriva il file e quale file e' (R3 della revisione 5A).
    val point = writer.checkpoint().okOrNull()!!
    assertThat(point.length).isEqualTo(100_000L)
    assertThat(point.fileId).isEqualTo(nas.reader.stat("test", temp, purpose).okOrNull()!!.fileId)
    writer.write(100_000, data, 100_000, 100_000)
    assertThat((writer.finish(200_000).okOrNull() as TempFinish.Complete).stamp.size).isEqualTo(200_000L)
    writer.close()
    // Lo stesso nome una seconda volta: Exists, niente cambia.
    assertThat(ops.openTemp("test", temp, purpose).errorOrNull()).isEqualTo(NasError.Exists)
    // Destinazione occupata da un altro file: Collision, il file che c'era resta byte per byte.
    nas.putFile("test", "Backup/S25/2026/10/IMG_1.jpg", NasContent.of(bytes(10, seed = 3)))
    assertThat(ops.publishTemp("test", temp, p("Backup/S25/2026/10/IMG_1.jpg"), purpose).okOrNull()).isEqualTo(PublishOutcome.Collision)
    assertThat(nas.bytes("test", "Backup/S25/2026/10/IMG_1.jpg")!!.toList()).isEqualTo(bytes(10, seed = 3).toList())
    val published = ops.publishTemp("test", temp, p("Backup/S25/2026/10/IMG_1 (2).jpg"), purpose).okOrNull()
    assertThat(published).isInstanceOf(PublishOutcome.Published::class.java)
    assertThat(sha(nas.bytes("test", "Backup/S25/2026/10/IMG_1 (2).jpg")!!)).isEqualTo(sha(data))
    // La risposta persa si riconosce: il temporaneo non c'e' piu'.
    assertThat(ops.publishTemp("test", temp, p("Backup/S25/2026/10/IMG_1 (3).jpg"), purpose).okOrNull()).isEqualTo(PublishOutcome.TempMissing)
    assertThat(ops.deleteOwnTemp("test", temp, purpose).okOrNull()).isEqualTo(DeleteOutcome.Missing)
  }

  @Test
  fun laRipresaAccorciaAlPuntoSalvatoEContinuaDaLi() = runTest {
    val temp = NasBackupPaths.tempFor(me, 2)
    val data = bytes(300_000, seed = 9)
    val writer = ops.openTemp("test", temp, purpose).okOrNull()!!
    writer.write(0, data, 0, 250_000) // il punto salvato nel diario e' a 128 KiB, poi la connessione cade
    val id = writer.checkpoint().okOrNull()!!.fileId!!
    writer.close()
    assertThat(ops.reopenTemp("test", NasBackupPaths.tempFor(me, 3), 0, id, purpose).okOrNull()).isEqualTo(ReopenOutcome.Missing)
    assertThat(ops.reopenTemp("test", temp, 400_000, id, purpose).okOrNull()).isEqualTo(ReopenOutcome.TooShort(250_000))
    // R3 (revisione 5A): un'altra identita' al nome del temporaneo non si accorcia mai.
    assertThat(ops.reopenTemp("test", temp, 131_072, id + 1, purpose).okOrNull()).isEqualTo(ReopenOutcome.Foreign)
    assertThat(nas.bytes("test", temp.value)!!.size).isEqualTo(250_000)
    assertThat(nas.backupTruncations.get()).isEqualTo(0)
    val reopened = ops.reopenTemp("test", temp, 131_072, id, purpose).okOrNull() as ReopenOutcome.Reopened
    assertThat(reopened.writer.length).isEqualTo(131_072L)
    assertThat(nas.bytes("test", temp.value)!!.size).isEqualTo(131_072)
    assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { reopened.writer.write(131_073, data, 0, 1) } }
    reopened.writer.write(131_072, data, 131_072, 300_000 - 131_072)
    assertThat(reopened.writer.finish(300_000).okOrNull()).isInstanceOf(TempFinish.Complete::class.java)
    assertThat(sha(nas.bytes("test", temp.value)!!)).isEqualTo(sha(data))
    assertThat(nas.backupTruncations.get()).isEqualTo(1)
    assertThat(nas.opCount(FakeNasOp.REOPEN_TEMP)).isEqualTo(4)
  }

  /** Un video "da GB" virtuale si carica senza occupare memoria: le pagine uguali al contenuto noto restano riferimenti. */
  @Test
  fun unFileVirtualeGrandeSiCaricaSenzaCopiarlo() = runTest {
    val big = NasContent.virtual(size = 64L * 1024 * 1024 + 12_345, seed = 77)
    nas.registerKnownContent(big)
    val temp = NasBackupPaths.tempFor(me, 4)
    val writer = ops.openTemp("test", temp, purpose).okOrNull()!!
    val chunk = ByteArray(1 shl 20)
    var offset = 0L
    while (offset < big.size) {
      val n = minOf(chunk.size.toLong(), big.size - offset).toInt()
      big.copy(offset, chunk, 0, n)
      writer.write(offset, chunk, 0, n)
      offset += n
    }
    assertThat(writer.finish(big.size).okOrNull()).isInstanceOf(TempFinish.Complete::class.java)
    val file = nas.reader.open("test", temp, purpose).okOrNull()!!
    val read = nas.reader.readAll(file, { _, _ -> }, null).okOrNull()!!
    val expected = MessageDigest.getInstance("SHA-256").let { md ->
      var at = 0L
      while (at < big.size) {
        val n = minOf(chunk.size.toLong(), big.size - at).toInt()
        big.copy(at, chunk, 0, n)
        md.update(chunk, 0, n)
        at += n
      }
      Sha256(md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }
    assertThat(read).isEqualTo(expected)
  }

  @Test
  fun iGuastiPrimaEDopoValgonoComeNellaQuattroB() = runTest {
    val temp = NasBackupPaths.tempFor(me, 5)
    nas.opsFaults = FaultPlan().at(1, FaultPhase.BEFORE, SimFault.DropConnection)
    assertThat(ops.openTemp("test", temp, purpose).errorOrNull()).isEqualTo(NasError.Timeout)
    assertThat(nas.exists("test", temp.value)).isFalse()
    // Dopo: il temporaneo c'e', la risposta no (la ripresa lo riconosce dal diario).
    nas.opsFaults = FaultPlan().at(1, FaultPhase.AFTER, SimFault.DropConnection)
    assertThat(ops.openTemp("test", temp, purpose).errorOrNull()).isEqualTo(NasError.Timeout)
    assertThat(nas.exists("test", temp.value)).isTrue()
    nas.opsFaults = null
    // Sola lettura e disco pieno.
    nas.setReadOnly("test", true)
    assertThat(ops.mkdirs("test", p("Backup/S25"), purpose).errorOrNull()).isEqualTo(NasError.PermissionDenied)
    nas.setReadOnly("test", false)
    nas.spaceOf = { dev.pampa.mycloudgallery.core.nas.ops.NasSpaceInfo(totalBytes = 100, freeBytes = 10, callerFreeBytes = 10) }
    val id = nas.reader.stat("test", temp, purpose).okOrNull()!!.fileId!!
    val writer = ops.reopenTemp("test", temp, 0, id, purpose).okOrNull() as ReopenOutcome.Reopened
    assertThat(writer.writer.write(0, bytes(50), 0, 50).errorOrNull()).isEqualTo(NasError.NasFull)
    assertThat(ops.space("test", purpose).okOrNull()!!.freeBytes).isEqualTo(10L)
  }

  /** Senza NAS (o con una sessione finta) nessuna scrittura; la vista SMB non finge una ripresa che le primitive sotto non sanno fare. */
  @Test
  fun nullObjectEVistaSmbFallisconoChiuso() = runTest {
    assertThat(NoNasBackupOps.openTemp("test", NasBackupPaths.tempFor(me, 1), purpose)).isInstanceOf(NasResult.Err::class.java)
    assertThat(NoNasBackupOps.reopenTemp("test", NasBackupPaths.tempFor(me, 1), 0, 1L, purpose)).isInstanceOf(NasResult.Err::class.java)
    assertThat(NasBackupOpsFactory.create(FakeNasSessionManager(), me)).isSameInstanceAs(NoNasBackupOps)
    // La vista SMB sopra primitive che non sono quelle SMB (qui il fake della 4B al posto di SmbNasFileOps): solo temporanei del
    // backup, e la ripresa e' un errore esplicito (il backup ricomincia il file da zero, mai un successo finto). La ripresa vera,
    // sopra SmbNasFileOps e il simulatore SMB, la prova `ops/backup/SmbNasBackupOpsTest` (P2).
    val view = dev.pampa.mycloudgallery.core.nas.ops.backup.SmbNasBackupOps(nas.fileOps(me))
    val temp = NasBackupPaths.tempFor(me, 9)
    val writer = view.openTemp("test", temp, purpose).okOrNull()!!
    writer.write(0, bytes(10), 0, 10)
    assertThat(writer.checkpoint().okOrNull()!!.length).isEqualTo(10L)
    assertThat(writer.length).isEqualTo(10L)
    assertThat(view.reopenTemp("test", temp, 5, 1L, purpose).errorOrNull()).isEqualTo(NasError.Unexpected(0xC0000002L))
    assertThat(nas.bytes("test", temp.value)!!.size).isEqualTo(10)
    assertThrows(IllegalArgumentException::class.java) {
      kotlinx.coroutines.runBlocking { view.deleteOwnTemp("test", NasLayout.ROOT.child("tmp").child("$me-op-1-0"), purpose) }
    }
  }
}
