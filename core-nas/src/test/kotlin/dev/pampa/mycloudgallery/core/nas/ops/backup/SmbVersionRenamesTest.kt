package dev.pampa.mycloudgallery.core.nas.ops.backup

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nas.Harness
import dev.pampa.mycloudgallery.core.nas.SHARE
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.contract.okOrNull
import dev.pampa.mycloudgallery.core.nas.ops.RenameOutcome
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.nas.virtualClock
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 5B, P8 (D17, D18, §1.2 "Versioni (5B)"): le due rinomine delle versioni della vista del backup (`archiveVersion`, `restoreVersion`)
 * sopra le primitive SMB vere (`SmbNasFileOps.renameVersion` via [NasBackupOpsFactory]) sul **simulatore Samba**. Cio' che il fake di P0-B
 * non puo' mostrare:
 * - una sola apertura con `DELETE` e un solo `SET_INFO` di rinomina, **sullo stesso handle** su cui si e' letto il timbro: nessuna
 *   creazione, nessuna scrittura, nessuna cancellazione; i byte e il `fileId` arrivano intatti dall'altra parte;
 * - il nome occupato (anche per le sole maiuscole) e' `Collision` e niente si muove: mai una sostituzione;
 * - il sorgente cambiato dal PC (byte, data o un altro file con lo stesso nome) e' `SourceChanged` e **nessuna** rinomina arriva al server;
 * - la cartella di destinazione che manca e' `NotFound` (nessuna cartella si crea di nascosto): dopo `mkdirs` riesce;
 * - una cartella al posto del file non si sposta; un file aperto dal PC e' un'attesa;
 * - un guasto prima e dopo **ogni** richiesta SMB lascia il file a uno solo dei due nomi, intero, con lo stesso `fileId`, e la ripresa
 *   (stesso timbro, come la rifarebbe il diario) lo riconosce: rinominato adesso, oppure sorgente assente e destinazione con quel `fileId`.
 */
class SmbVersionRenamesTest {

  private val me = Base32.encode(ByteArray(16) { 0x11 })
  private val purpose = Purpose.BACKGROUND

  private fun TestScope.harness(): Harness = Harness(backgroundScope, virtualClock())

  private fun Harness.ops(): NasBackupOps = NasBackupOpsFactory.create(manager, me)

  private fun p(path: String) = SharePath.parse(path)

  private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 13 + seed + (it shr 7)).toByte() }

  private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toList()

  /** Le richieste SMB arrivate al simulatore, per tipo. */
  private fun Harness.recordOps(): MutableList<SimOp> = CopyOnWriteArrayList<SimOp>().also { seen -> backend.beforeOp = { op, _ -> seen += op } }

  /** Il timbro di un file come lo ricorderebbe il diario (dimensione, data, `fileId`). */
  private fun Harness.stampOf(path: String): NasStamp =
    NasStamp(backend.bytes(SHARE, path)!!.size.toLong(), backend.lastWriteMs(SHARE, path)!!, backend.fileId(SHARE, path))

  private val current = "Backup/S25/2026/10/IMG_1.jpg"
  private val archived = "Versioni/Backup/S25/2026/10/IMG_1 (originale).jpg"
  private val versionsDir = "Versioni/Backup/S25/2026/10"

  @Test
  fun archiviaERipristinaConUnaSolaRinominaSulloStessoHandleConservandoByteEIdentita() = runTest {
    val h = harness()
    val ops = h.ops()
    val content = bytes(80_000, seed = 3)
    h.backend.put(SHARE, current, content)
    val id = h.backend.fileId(SHARE, current)
    val stamp = h.stampOf(current)
    assertThat(ops.mkdirs(SHARE, p(versionsDir), purpose).okOrNull()).isEqualTo(Unit)

    val seen = h.recordOps()
    val moved = ops.archiveVersion(SHARE, p(current), p(archived), stamp, purpose).okOrNull() as RenameOutcome.Renamed
    // Una sola apertura per cambiare e una sola rinomina: niente si crea, si scrive o si cancella.
    assertThat(seen.count { it == SimOp.OPEN_CHANGE }).isEqualTo(1)
    assertThat(seen.count { it == SimOp.RENAME }).isEqualTo(1)
    assertThat(seen).containsNoneOf(SimOp.CREATE, SimOp.WRITE, SimOp.DELETE, SimOp.DELETE_ON_CLOSE, SimOp.MKDIR, SimOp.SET_END_OF_FILE)
    assertThat(moved.stamp).isEqualTo(stamp)
    assertThat(h.backend.exists(SHARE, current)).isFalse()
    assertThat(sha(h.backend.bytes(SHARE, archived)!!)).isEqualTo(sha(content))
    assertThat(h.backend.fileId(SHARE, archived)).isEqualTo(id)

    // "Ripristina originale": torna al solito percorso, stesso file.
    val archivedStamp = h.stampOf(archived)
    val back = ops.restoreVersion(SHARE, p(archived), p(current), archivedStamp, purpose).okOrNull() as RenameOutcome.Renamed
    assertThat(back.stamp!!.fileId).isEqualTo(id)
    assertThat(h.backend.exists(SHARE, archived)).isFalse()
    assertThat(sha(h.backend.bytes(SHARE, current)!!)).isEqualTo(sha(content))
    assertThat(h.backend.fileId(SHARE, current)).isEqualTo(id)
  }

  @Test
  fun unNomeOccupatoEUnaCollisioneENessunFileSiMuove() = runTest {
    val h = harness()
    val ops = h.ops()
    val mine = bytes(5_000, seed = 1)
    val other = bytes(6_000, seed = 2)
    h.backend.put(SHARE, current, mine)
    h.backend.put(SHARE, archived, other)
    val stamp = h.stampOf(current)
    assertThat(ops.archiveVersion(SHARE, p(current), p(archived), stamp, purpose).okOrNull()).isEqualTo(RenameOutcome.Collision)
    // Anche se il nome occupato differisce solo per le maiuscole (Samba confronta senza).
    assertThat(ops.archiveVersion(SHARE, p(current), p("versioni/BACKUP/s25/2026/10/img_1 (ORIGINALE).JPG"), stamp, purpose).okOrNull())
      .isEqualTo(RenameOutcome.Collision)
    assertThat(sha(h.backend.bytes(SHARE, current)!!)).isEqualTo(sha(mine))
    assertThat(sha(h.backend.bytes(SHARE, archived)!!)).isEqualTo(sha(other))

    // Il ripristino verso un solito percorso occupato (l'attuale non e' ancora in Versioni/): collisione, mai una sostituzione.
    val archivedStamp = h.stampOf(archived)
    assertThat(ops.restoreVersion(SHARE, p(archived), p(current), archivedStamp, purpose).okOrNull()).isEqualTo(RenameOutcome.Collision)
    assertThat(sha(h.backend.bytes(SHARE, current)!!)).isEqualTo(sha(mine))
    assertThat(sha(h.backend.bytes(SHARE, archived)!!)).isEqualTo(sha(other))
  }

  @Test
  fun unSorgenteCambiatoDalPcNonSiArchiviaENessunaRinominaArrivaAlServer() = runTest {
    val h = harness()
    val ops = h.ops()
    h.backend.put(SHARE, current, bytes(5_000, seed = 4))
    h.backend.putDir(SHARE, versionsDir)
    val stamp = h.stampOf(current)
    // Il PC modifica il file sul posto (stesso fileId, byte e data nuovi: E-E6) mentre la versione nuova e' nel temporaneo.
    val fromPc = bytes(5_100, seed = 5)
    h.backend.modify(SHARE, current, NasContent.of(fromPc), lastWriteMs = stamp.modifiedAtMillis + 60_000)
    val seen = h.recordOps()
    val outcome = ops.archiveVersion(SHARE, p(current), p(archived), stamp, purpose).okOrNull() as RenameOutcome.SourceChanged
    assertThat(outcome.actual!!.size).isEqualTo(5_100L)
    assertThat(seen).doesNotContain(SimOp.RENAME)
    assertThat(sha(h.backend.bytes(SHARE, current)!!)).isEqualTo(sha(fromPc))
    assertThat(h.backend.exists(SHARE, archived)).isFalse()

    // Un altro file messo al suo nome (stessa dimensione e data, un altro fileId): anche questo non e' il file del diario.
    h.backend.delete(SHARE, current)
    h.backend.put(SHARE, current, bytes(5_000, seed = 4), lastWriteMs = stamp.modifiedAtMillis)
    assertThat(h.backend.fileId(SHARE, current)).isNotEqualTo(stamp.fileId)
    assertThat(ops.archiveVersion(SHARE, p(current), p(archived), stamp, purpose).okOrNull()).isInstanceOf(RenameOutcome.SourceChanged::class.java)
    assertThat(h.backend.exists(SHARE, archived)).isFalse()
  }

  @Test
  fun laCartellaDiDestinazioneMancanteENotFoundENessunaCartellaSiCreaDiNascosto() = runTest {
    val h = harness()
    val ops = h.ops()
    val content = bytes(3_000, seed = 6)
    h.backend.put(SHARE, current, content)
    val stamp = h.stampOf(current)
    val seen = h.recordOps()
    assertThat(ops.archiveVersion(SHARE, p(current), p(archived), stamp, purpose).errorOrNull()).isEqualTo(NasError.NotFound)
    assertThat(seen).doesNotContain(SimOp.MKDIR)
    assertThat(h.backend.exists(SHARE, "Versioni")).isFalse()
    assertThat(sha(h.backend.bytes(SHARE, current)!!)).isEqualTo(sha(content))
    // Il chiamante crea la cartella (D17: prima `mkdirs`) e riprova: riesce.
    assertThat(ops.mkdirs(SHARE, p(versionsDir), purpose).okOrNull()).isEqualTo(Unit)
    assertThat(ops.archiveVersion(SHARE, p(current), p(archived), stamp, purpose).okOrNull()).isInstanceOf(RenameOutcome.Renamed::class.java)
    assertThat(sha(h.backend.bytes(SHARE, archived)!!)).isEqualTo(sha(content))

    // Lo stesso per il ripristino verso una cartella del solito percorso che non c'e' piu' (tolta dal PC).
    h.backend.delete(SHARE, "Backup/S25/2026/10")
    val archivedStamp = h.stampOf(archived)
    assertThat(ops.restoreVersion(SHARE, p(archived), p(current), archivedStamp, purpose).errorOrNull()).isEqualTo(NasError.NotFound)
    assertThat(h.backend.exists(SHARE, archived)).isTrue()
  }

  @Test
  fun unaCartellaUnFileAssenteOApertoDalPcNonSiSpostano() = runTest {
    val h = harness()
    val ops = h.ops()
    h.backend.putDir(SHARE, versionsDir)
    // Una cartella al posto del file: "non e' il file che mi aspettavo", niente si muove.
    h.backend.put(SHARE, "Backup/S25/2026/11/x.jpg", bytes(10, seed = 7))
    val folderStamp = NasStamp(0, h.backend.lastWriteMs(SHARE, "Backup/S25/2026/11")!!, h.backend.fileId(SHARE, "Backup/S25/2026/11"))
    assertThat(ops.archiveVersion(SHARE, p("Backup/S25/2026/11"), p("Versioni/Backup/S25/2026/10/11"), folderStamp, purpose).okOrNull())
      .isEqualTo(RenameOutcome.SourceChanged(null))
    assertThat(h.backend.exists(SHARE, "Backup/S25/2026/11/x.jpg")).isTrue()
    // Un file che non c'e'.
    assertThat(ops.archiveVersion(SHARE, p(current), p(archived), NasStamp(1, 1, 1), purpose).okOrNull()).isEqualTo(RenameOutcome.SourceMissing)
    // Un file aperto dal PC: un'attesa, niente si muove.
    h.backend.put(SHARE, current, bytes(2_000, seed = 8))
    val stamp = h.stampOf(current)
    h.backend.lock(SHARE, current)
    assertThat(ops.archiveVersion(SHARE, p(current), p(archived), stamp, purpose).okOrNull()).isEqualTo(RenameOutcome.Locked)
    assertThat(h.backend.exists(SHARE, archived)).isFalse()
    h.backend.unlock(SHARE, current)
    assertThat(ops.archiveVersion(SHARE, p(current), p(archived), stamp, purpose).okOrNull()).isInstanceOf(RenameOutcome.Renamed::class.java)
  }

  @Test
  fun iPercorsiSbagliatiSonoUnErroreDiProgrammazionePrimaDiToccareIlNas() = runTest {
    val h = harness()
    val ops = h.ops()
    h.backend.put(SHARE, current, bytes(10, seed = 9))
    val stamp = h.stampOf(current)
    val seen = h.recordOps()
    val wrong = listOf(
      current to "Backup/altro.jpg", // nessuna estremita' sotto Versioni/: una rinomina libera
      current to ".mycloudgallery/tmp/x", // l'area dell'app
      "Versioni/a.jpg" to "Versioni/b.jpg", // tutte e due sotto Versioni/
    )
    for ((from, to) in wrong) {
      assertThrows(IllegalArgumentException::class.java) { runBlocking { ops.archiveVersion(SHARE, p(from), p(to), stamp, purpose) } }
    }
    assertThrows(IllegalArgumentException::class.java) { runBlocking { ops.restoreVersion(SHARE, p(current), p(archived), stamp, purpose) } }
    assertThat(seen).isEmpty()
  }

  /**
   * Un guasto (caduta della connessione o sessione scaduta) prima e dopo **ogni** richiesta SMB dell'archiviazione e del ripristino. Dopo il
   * guasto il file sta a **uno** dei due nomi, intero e con lo stesso `fileId`; la ripresa con lo stesso timbro lo riconosce come farebbe il
   * diario delle versioni: rinominato adesso, oppure sorgente assente e destinazione con quel `fileId` (la risposta persa).
   */
  @Test
  fun unGuastoAOgniRichiestaLasciaIlFileAUnSoloNomeERiconoscibile() = runTest {
    val content = bytes(40_000, seed = 11)
    for (restore in listOf(false, true)) {
      suspend fun Harness.seed(): Pair<NasBackupOps, NasStamp> {
        val from = if (restore) archived else current
        backend.put(SHARE, from, content)
        backend.putDir(SHARE, versionsDir)
        backend.putDir(SHARE, "Backup/S25/2026/10")
        return ops() to stampOf(from)
      }
      val from = if (restore) archived else current
      val to = if (restore) current else archived
      suspend fun NasBackupOps.move(stamp: NasStamp): NasResult<RenameOutcome> =
        if (restore) restoreVersion(SHARE, p(from), p(to), stamp, purpose) else archiveVersion(SHARE, p(from), p(to), stamp, purpose)

      val clean = harness()
      val (cleanOps, cleanStamp) = clean.seed()
      val counter = FaultPlan()
      clean.backend.faults = counter
      assertThat(cleanOps.move(cleanStamp).okOrNull()).isInstanceOf(RenameOutcome.Renamed::class.java)
      val total = counter.operations
      assertWithMessage("richieste contate").that(total).isAtLeast(2)
      for (n in 1..total) {
        for (phase in FaultPhase.entries) {
          for (fault in listOf(SimFault.DropConnection, SimFault.ExpireSession)) {
            val h = harness()
            val (ops, stamp) = h.seed()
            val id = h.backend.fileId(SHARE, from)
            val plan = FaultPlan().at(n, phase, fault)
            h.backend.faults = plan
            val first = ops.move(stamp)
            h.backend.faults = null
            val label = "${if (restore) "ripristino" else "archiviazione"}: guasto ${fault::class.simpleName} alla richiesta $n/$total $phase"
            val atFrom = h.backend.exists(SHARE, from)
            val atTo = h.backend.exists(SHARE, to)
            assertWithMessage("$label: a uno solo dei due nomi").that(atFrom xor atTo).isTrue()
            val where = if (atTo) to else from
            assertWithMessage("$label: intero").that(sha(h.backend.bytes(SHARE, where)!!)).isEqualTo(sha(content))
            assertWithMessage("$label: stessa identita'").that(h.backend.fileId(SHARE, where)).isEqualTo(id)
            if (first is NasResult.Ok) continue
            // La ripresa: lo stesso passo del diario, con lo stesso timbro.
            when (val again = ops.move(stamp).okOrNull()) {
              is RenameOutcome.Renamed -> assertWithMessage("$label: rinominato alla ripresa").that(h.backend.exists(SHARE, to)).isTrue()
              RenameOutcome.SourceMissing -> assertWithMessage("$label: la risposta persa si riconosce dal fileId")
                .that(h.backend.fileId(SHARE, to)).isEqualTo(stamp.fileId)
              else -> throw AssertionError("$label: ripresa inattesa $again")
            }
            assertWithMessage("$label: alla fine al nome nuovo").that(sha(h.backend.bytes(SHARE, to)!!)).isEqualTo(sha(content))
          }
        }
      }
    }
  }
}
