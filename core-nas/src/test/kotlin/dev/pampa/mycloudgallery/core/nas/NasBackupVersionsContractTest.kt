package dev.pampa.mycloudgallery.core.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.contract.okOrNull
import dev.pampa.mycloudgallery.core.nas.noop.NoNasBackupOps
import dev.pampa.mycloudgallery.core.nas.ops.RenameOutcome
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import dev.pampa.mycloudgallery.core.nas.ops.backup.SmbNasBackupOps
import dev.pampa.mycloudgallery.core.nas.ops.backup.VersionPaths
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 5B (P0-B, D17, D18): il contratto delle due rinomine delle versioni di `NasBackupOps` (`archiveVersion` e `restoreVersion`) sul
 * NAS finto. Sono le uniche primitive del backup che **spostano** un file visibile, e per questo valgono quattro garanzie: nessuna
 * sostituzione (`Collision`), timbro obbligatorio controllato prima di muovere (`SourceChanged`, niente si muove), una sola estremita'
 * sotto `Versioni/` (un percorso sbagliato e' un errore di programmazione) e nessun file si toglie: i byte e il `fileId` arrivano
 * intatti dall'altra parte. La vista SMB vera e' di P8: qui si prova che, finche' non c'e', **non finge un successo**.
 */
class NasBackupVersionsContractTest {

  private val me = Base32.encode(ByteArray(16) { 0x11 })
  private val nas = InMemoryNasMedia().apply { addShare("test") }
  private val ops = nas.backupOps(me)
  private val purpose = Purpose.BACKGROUND

  private fun p(path: String) = SharePath.parse(path)

  private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 17 + seed).toByte() }

  /** Mette un file di media al suo posto e restituisce il timbro che il diario ne avrebbe. */
  private suspend fun seed(path: String, content: ByteArray): NasStamp {
    nas.putFile("test", path, NasContent.of(content))
    return nas.reader.stat("test", p(path), purpose).okOrNull()!!.stamp
  }

  private val current = "Backup/S25/2026/10/IMG_1.jpg"
  private val archived = "Versioni/Backup/S25/2026/10/IMG_1 (originale).jpg"

  @Test
  fun archiviaEPoiRipristinaConservandoByteEIdentita() = runTest {
    val content = bytes(4_000, seed = 3)
    val stamp = seed(current, content)
    assertThat(ops.mkdirs("test", p("Versioni/Backup/S25/2026/10"), purpose).okOrNull()).isEqualTo(Unit)

    val archivedAt = ops.archiveVersion("test", p(current), p(archived), stamp, purpose).okOrNull() as RenameOutcome.Renamed
    // Stesso file, altro nome: byte e fileId arrivano intatti, il vecchio percorso e' libero, niente e' stato tolto.
    assertThat(archivedAt.stamp!!.fileId).isEqualTo(stamp.fileId)
    assertThat(nas.exists("test", current)).isFalse()
    assertThat(nas.bytes("test", archived)!!.toList()).isEqualTo(content.toList())
    assertThat(nas.fileCount("test")).isEqualTo(1)

    // "Ripristina originale": il solito percorso e' libero, l'originale torna.
    val restored = ops.restoreVersion("test", p(archived), p(current), archivedAt.stamp!!, purpose).okOrNull() as RenameOutcome.Renamed
    assertThat(restored.stamp!!.fileId).isEqualTo(stamp.fileId)
    assertThat(nas.bytes("test", current)!!.toList()).isEqualTo(content.toList())
    assertThat(nas.exists("test", archived)).isFalse()
    assertThat(nas.fileCount("test")).isEqualTo(1)
    assertThat(nas.opCount(FakeNasOp.ARCHIVE_VERSION)).isEqualTo(1)
    assertThat(nas.opCount(FakeNasOp.RESTORE_VERSION)).isEqualTo(1)
  }

  @Test
  fun unFileCambiatoDalPcNonSiSposta() = runTest {
    val stamp = seed(current, bytes(2_000, seed = 5))
    ops.mkdirs("test", p("Versioni/Backup/S25/2026/10"), purpose)
    // Il PC lo riscrive dopo la verifica: dimensione e data diverse, `fileId` uguale. E' un'altra versione, non quella confermata.
    nas.modify("test", current, NasContent.of(bytes(2_500, seed = 6)))

    val outcome = ops.archiveVersion("test", p(current), p(archived), stamp, purpose).okOrNull()
    assertThat(outcome).isInstanceOf(RenameOutcome.SourceChanged::class.java)
    assertThat(nas.exists("test", current)).isTrue()
    assertThat(nas.exists("test", archived)).isFalse()
  }

  @Test
  fun nonSostituisceMai() = runTest {
    val stamp = seed(current, bytes(1_000, seed = 1))
    ops.mkdirs("test", p("Versioni/Backup/S25/2026/10"), purpose)
    seed(archived, bytes(700, seed = 2))

    assertThat(ops.archiveVersion("test", p(current), p(archived), stamp, purpose).okOrNull()).isEqualTo(RenameOutcome.Collision)
    // Anche per le sole maiuscole: il nome occupato e' occupato.
    assertThat(
      ops.archiveVersion("test", p(current), p("Versioni/BACKUP/S25/2026/10/img_1 (originale).JPG"), stamp, purpose).okOrNull(),
    ).isEqualTo(RenameOutcome.Collision)
    assertThat(nas.bytes("test", current)!!.toList()).isEqualTo(bytes(1_000, seed = 1).toList())
    assertThat(nas.bytes("test", archived)!!.toList()).isEqualTo(bytes(700, seed = 2).toList())

    // E il ripristino non copre un file che c'e' gia' al solito percorso.
    val archivedStamp = nas.reader.stat("test", p(archived), purpose).okOrNull()!!.stamp
    assertThat(ops.restoreVersion("test", p(archived), p(current), archivedStamp, purpose).okOrNull()).isEqualTo(RenameOutcome.Collision)
    assertThat(nas.fileCount("test")).isEqualTo(2)
  }

  @Test
  fun laCartellaDiDestinazioneDeveEsistereEUnaCartellaNonSiArchivia() = runTest {
    val stamp = seed(current, bytes(500, seed = 8))
    // Senza `mkdirs` prima: come ogni rinomina della 4B, il chiamante crea la cartella.
    assertThat(ops.archiveVersion("test", p(current), p(archived), stamp, purpose).errorOrNull()).isEqualTo(NasError.NotFound)
    assertThat(nas.exists("test", current)).isTrue()

    ops.mkdirs("test", p("Versioni/Backup/S25/2026/10"), purpose)
    // Al posto del file c'e' una cartella (o il nome e' stato riusato): non e' il file confermato.
    nas.putDir("test", "Backup/S25/2026/11")
    val outcome = ops.archiveVersion("test", p("Backup/S25/2026/11"), p("Versioni/Backup/S25/2026/10/11"), stamp, purpose).okOrNull()
    assertThat(outcome).isInstanceOf(RenameOutcome.SourceChanged::class.java)
    assertThat(nas.exists("test", "Backup/S25/2026/11")).isTrue()
    assertThat(ops.archiveVersion("test", p("Backup/S25/2026/10/non-c-e.jpg"), p(archived), stamp, purpose).okOrNull())
      .isEqualTo(RenameOutcome.SourceMissing)
  }

  @Test
  fun iPercorsiSbagliatiSonoErroriDiProgrammazione() = runTest {
    val stamp = seed(current, bytes(300, seed = 4))
    // Archiviare fuori da Versioni/, da Versioni/, dall'area dell'app o dalla radice di Versioni.
    for ((from, to) in listOf(
      current to "Backup/S25/2026/10/IMG_1 (2).jpg",
      "Versioni/Backup/x.jpg" to "Versioni/Backup/y.jpg",
      ".mycloudgallery/tmp/x" to archived,
      "Versioni" to archived,
      current to ".mycloudgallery/trash/x",
      current to "Versioni",
    )) {
      assertThrows(IllegalArgumentException::class.java) { runBlocking { ops.archiveVersion("test", p(from), p(to), stamp, purpose) } }
    }
    // Ripristinare da fuori da Versioni/ o verso Versioni/ o l'area dell'app.
    for ((from, to) in listOf(
      current to "Backup/S25/2026/10/IMG_1 (2).jpg",
      archived to "Versioni/Backup/S25/2026/10/IMG_2.jpg",
      archived to ".mycloudgallery/x",
      "Versioni" to current,
    )) {
      assertThrows(IllegalArgumentException::class.java) { runBlocking { ops.restoreVersion("test", p(from), p(to), stamp, purpose) } }
    }
    assertThat(nas.exists("test", current)).isTrue()
  }

  @Test
  fun iGuastiPrimaEDopoValgonoComeNelleAltrePrimitive() = runTest {
    val stamp = seed(current, bytes(1_500, seed = 9))
    ops.mkdirs("test", p("Versioni/Backup/S25/2026/10"), purpose)
    nas.opsFaults = FaultPlan().at(1, FaultPhase.BEFORE, SimFault.DropConnection)
    assertThat(ops.archiveVersion("test", p(current), p(archived), stamp, purpose).errorOrNull()).isEqualTo(NasError.Timeout)
    assertThat(nas.exists("test", current)).isTrue()

    // Dopo: la rinomina c'e' stata, la risposta no. La ripresa lo riconosce: sorgente assente + destinazione con lo stesso `fileId`.
    nas.opsFaults = FaultPlan().at(1, FaultPhase.AFTER, SimFault.DropConnection)
    assertThat(ops.archiveVersion("test", p(current), p(archived), stamp, purpose).errorOrNull()).isEqualTo(NasError.Timeout)
    nas.opsFaults = null
    assertThat(nas.exists("test", current)).isFalse()
    assertThat(ops.archiveVersion("test", p(current), p(archived), stamp, purpose).okOrNull()).isEqualTo(RenameOutcome.SourceMissing)
    assertThat(nas.reader.stat("test", p(archived), purpose).okOrNull()!!.fileId).isEqualTo(stamp.fileId)

    // Share in sola lettura (E-N9): niente si sposta.
    nas.setReadOnly("test", true)
    val archivedStamp = nas.reader.stat("test", p(archived), purpose).okOrNull()!!.stamp
    assertThat(ops.restoreVersion("test", p(archived), p(current), archivedStamp, purpose).errorOrNull()).isEqualTo(NasError.PermissionDenied)
    assertThat(nas.exists("test", archived)).isTrue()
  }

  @Test
  fun unFileBloccatoDalPcRestaDovEraConLoStatoLocked() = runTest {
    val stamp = seed(current, bytes(800, seed = 2))
    ops.mkdirs("test", p("Versioni/Backup/S25/2026/10"), purpose)
    nas.lock("test", current)
    assertThat(ops.archiveVersion("test", p(current), p(archived), stamp, purpose).okOrNull()).isEqualTo(RenameOutcome.Locked)
    assertThat(nas.exists("test", current)).isTrue()
  }

  /** Senza NAS nessuna rinomina; la vista SMB non finge un successo che P8 non ha ancora scritto. */
  @Test
  fun nullObjectEVistaSmbFallisconoChiuso() = runTest {
    val stamp = seed(current, bytes(100, seed = 1))
    assertThat(NoNasBackupOps.archiveVersion("test", p(current), p(archived), stamp, purpose)).isInstanceOf(NasResult.Err::class.java)
    assertThat(NoNasBackupOps.restoreVersion("test", p(archived), p(current), stamp, purpose)).isInstanceOf(NasResult.Err::class.java)
    // Anche qui i percorsi sbagliati sono un errore di programmazione, non un "NAS non configurato".
    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { NoNasBackupOps.archiveVersion("test", p(current), p("Backup/altro.jpg"), stamp, purpose) }
    }

    val view: NasBackupOps = SmbNasBackupOps(nas.fileOps(me))
    ops.mkdirs("test", p("Versioni/Backup/S25/2026/10"), purpose)
    assertThat(view.archiveVersion("test", p(current), p(archived), stamp, purpose).errorOrNull()).isEqualTo(NasError.Unexpected(0xC0000002L))
    assertThat(view.restoreVersion("test", p(archived), p(current), stamp, purpose).errorOrNull()).isEqualTo(NasError.Unexpected(0xC0000002L))
    assertThat(nas.exists("test", current)).isTrue()
    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { view.archiveVersion("test", p(current), p("Backup/altro.jpg"), stamp, purpose) }
    }
  }

  @Test
  fun versionPathsRiconosceVersioniAnchePerMaiuscoleEForme() {
    assertThat(VersionPaths.isInVersions(p("Versioni/Backup/x.jpg"))).isTrue()
    assertThat(VersionPaths.isInVersions(p("versioni/Backup/x.jpg"))).isTrue()
    // Forma di compatibilita' (lettera a larghezza intera): come per l'area dell'app, si riconosce lo stesso.
    assertThat(VersionPaths.isInVersions(p("Ｖersioni/Backup/x.jpg"))).isTrue()
    assertThat(VersionPaths.isInVersions(p("Versioni"))).isFalse()
    assertThat(VersionPaths.isVersionsRoot(p("Versioni"))).isTrue()
    assertThat(VersionPaths.isInVersions(p("Backup/Versioni/x.jpg"))).isFalse()
    assertThat(VersionPaths.mirrorOf(p("Backup/S25/2026/10/IMG_1.jpg")).value).isEqualTo("Versioni/Backup/S25/2026/10/IMG_1.jpg")
    assertThrows(IllegalArgumentException::class.java) { VersionPaths.mirrorOf(p("Versioni/x.jpg")) }
    assertThrows(IllegalArgumentException::class.java) { VersionPaths.mirrorOf(p(".mycloudgallery/x")) }
  }
}
