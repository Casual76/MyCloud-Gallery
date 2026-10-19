package dev.pampa.mycloudgallery.core.nas.ops.backup

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nas.Harness
import dev.pampa.mycloudgallery.core.nas.SHARE
import dev.pampa.mycloudgallery.core.nas.contract.NasAttributes
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.contract.okOrNull
import dev.pampa.mycloudgallery.core.nas.ops.TempFinish
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.nas.virtualClock
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 5A, P2 (D5, §1.2 "Ripresa"): la vista del backup sopra le primitive SMB vere (`SmbNasFileOps` via [NasBackupOpsFactory]) sul
 * simulatore Samba. Cio' che il fake non puo' mostrare:
 * - `reopenTemp` apre il **proprio** temporaneo **senza crearlo** (`FILE_OPEN`: un nome assente resta assente), lo accorcia al punto di
 *   ripresa con il `SET_INFO` di fine file **sullo stesso handle** e lo scrittore riparte da li';
 * - il punto di ripresa (`checkpoint`, sopra `finish` della 4B) **non chiude** l'handle: lo scrittore resta usabile dopo ognuno;
 * - una cartella o un collegamento al nome del temporaneo non si accorciano mai; un file aperto dal PC e' un'attesa;
 * - un guasto prima e dopo **ogni** richiesta SMB della ripresa non lascia mai un temporaneo con byte diversi dal prefisso salvato;
 * - R3 della revisione 5A: il punto di ripresa dice l'identita' del file letta **sullo stesso handle** dello scrittore, e la riapertura
 *   accorcia solo quel file con un nome solo, in una `tmp/` vera: un collegamento fisico o simbolico al nome del temporaneo, un temporaneo
 *   con un secondo nome o una `tmp/` che e' un collegamento non si toccano, e `deleteOwnTemp` dopo toglie solo il nome.
 */
class SmbNasBackupOpsTest {

  private val me = Base32.encode(ByteArray(16) { 0x11 })
  private val other = Base32.encode(ByteArray(16) { 0x22 })
  private val purpose = Purpose.BACKGROUND

  private fun TestScope.harness(): Harness = Harness(backgroundScope, virtualClock())

  private fun Harness.ops(): NasBackupOps = NasBackupOpsFactory.create(manager, me)

  private fun bytes(n: Int, seed: Int = 7) = ByteArray(n) { (it * 31 + seed + (it shr 9)).toByte() }

  private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toList()

  /** Le richieste SMB arrivate al simulatore, per tipo. */
  private fun Harness.recordOps(): MutableList<SimOp> = CopyOnWriteArrayList<SimOp>().also { seen -> backend.beforeOp = { op, _ -> seen += op } }

  @Test
  fun lasciaLoScrittoreUsabileDopoOgniPuntoDiRipresa() = runTest {
    val h = harness()
    val ops = h.ops()
    val temp = NasBackupPaths.tempFor(me, 1)
    val data = bytes(300_000)
    val writer = ops.openTemp(SHARE, temp, purpose).okOrNull()!!
    writer.write(0, data, 0, 100_000)
    assertThat(writer.checkpoint().okOrNull()).isEqualTo(TempPoint(100_000L, h.backend.fileId(SHARE, temp.value)))
    writer.write(100_000, data, 100_000, 100_000)
    assertThat(writer.checkpoint().okOrNull()).isEqualTo(TempPoint(200_000L, h.backend.fileId(SHARE, temp.value)))
    writer.write(200_000, data, 200_000, 100_000)
    assertThat(writer.length).isEqualTo(300_000L)
    val finished = writer.finish(300_000).okOrNull()
    assertThat(finished).isInstanceOf(TempFinish.Complete::class.java)
    writer.close()
    assertThat(sha(h.backend.bytes(SHARE, temp.value)!!)).isEqualTo(sha(data))
  }

  @Test
  fun laRipresaRiapreSenzaCreareAccorciaSulloStessoHandleERiparteDaLi() = runTest {
    val h = harness()
    val ops = h.ops()
    val temp = NasBackupPaths.tempFor(me, 2)
    val data = bytes(300_000, seed = 9)
    val writer = ops.openTemp(SHARE, temp, purpose).okOrNull()!!
    writer.write(0, data, 0, 250_000) // il punto salvato nel diario e' a 128 KiB, poi la connessione cade
    writer.close()
    val fileId = h.backend.fileId(SHARE, temp.value)!!

    // Assente: nessun file creato (FILE_OPEN, mai FILE_OPEN_IF).
    val missing = NasBackupPaths.tempFor(me, 3)
    assertThat(ops.reopenTemp(SHARE, missing, 0, fileId, purpose).okOrNull()).isEqualTo(ReopenOutcome.Missing)
    assertThat(h.backend.exists(SHARE, missing.value)).isFalse()
    // Piu' corto del punto salvato: niente cambia.
    assertThat(ops.reopenTemp(SHARE, temp, 400_000, fileId, purpose).okOrNull()).isEqualTo(ReopenOutcome.TooShort(250_000))
    assertThat(h.backend.bytes(SHARE, temp.value)!!.size).isEqualTo(250_000)

    val seen = h.recordOps()
    val reopened = ops.reopenTemp(SHARE, temp, 131_072, fileId, purpose).okOrNull() as ReopenOutcome.Reopened
    // Una sola apertura senza creazione e un solo SET_INFO di fine file: nessuna CREATE, nessuna cancellazione.
    assertThat(seen.count { it == SimOp.OPEN_WRITE }).isEqualTo(1)
    assertThat(seen.count { it == SimOp.SET_END_OF_FILE }).isEqualTo(1)
    assertThat(seen).containsNoneOf(SimOp.CREATE, SimOp.DELETE, SimOp.DELETE_ON_CLOSE, SimOp.RENAME)
    assertThat(reopened.writer.length).isEqualTo(131_072L)
    assertThat(h.backend.bytes(SHARE, temp.value)!!.toList()).isEqualTo(data.copyOf(131_072).toList())
    assertThat(h.backend.fileId(SHARE, temp.value)).isEqualTo(fileId)
    // Lo scrittore riparte dal punto salvato: mai un buco.
    assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { reopened.writer.write(131_073, data, 0, 1) } }
    reopened.writer.write(131_072, data, 131_072, 100_000)
    assertThat(reopened.writer.checkpoint().okOrNull()).isEqualTo(TempPoint(231_072L, fileId))
    reopened.writer.write(231_072, data, 231_072, 300_000 - 231_072)
    assertThat(reopened.writer.finish(300_000).okOrNull()).isInstanceOf(TempFinish.Complete::class.java)
    reopened.writer.close()
    assertThat(sha(h.backend.bytes(SHARE, temp.value)!!)).isEqualTo(sha(data))
    // Riaprire allo stesso punto della lunghezza attuale non cambia niente (nessun SET_INFO inutile).
    seen.clear()
    val again = ops.reopenTemp(SHARE, temp, 300_000, fileId, purpose).okOrNull() as ReopenOutcome.Reopened
    again.writer.close()
    assertThat(seen).doesNotContain(SimOp.SET_END_OF_FILE)
  }

  @Test
  fun unTemporaneoApertoDalPcEUnAttesaENonSiTocca() = runTest {
    val h = harness()
    val ops = h.ops()
    val temp = NasBackupPaths.tempFor(me, 4)
    val id = h.backend.putContent(SHARE, temp.value, NasContent.of(bytes(10_000)))
    h.backend.lock(SHARE, temp.value)
    assertThat(ops.reopenTemp(SHARE, temp, 5_000, id, purpose).okOrNull()).isEqualTo(ReopenOutcome.Locked)
    assertThat(h.backend.bytes(SHARE, temp.value)!!.size).isEqualTo(10_000)
    h.backend.unlock(SHARE, temp.value)
    val reopened = ops.reopenTemp(SHARE, temp, 5_000, id, purpose).okOrNull() as ReopenOutcome.Reopened
    reopened.writer.close()
    assertThat(h.backend.bytes(SHARE, temp.value)!!.size).isEqualTo(5_000)
  }

  /** R3 della 4B applicata alla ripresa: un collegamento o una cartella al nome del temporaneo non si accorciano mai. */
  @Test
  fun unCollegamentoOUnaCartellaAlNomeDelTemporaneoNonSiAccorciano() = runTest {
    val h = harness()
    val ops = h.ops()
    val link = NasBackupPaths.tempFor(me, 5)
    val id = h.backend.putContent(SHARE, link.value, NasContent.of(bytes(10_000)), attributes = NasAttributes.ARCHIVE or NasAttributes.REPARSE_POINT)
    // Anche con l'identita' giusta: un punto di rianalisi non e' il temporaneo (R3 della revisione 5A), niente si tocca.
    assertThat(ops.reopenTemp(SHARE, link, 1_000, id, purpose).okOrNull()).isEqualTo(ReopenOutcome.Foreign)
    assertThat(h.backend.bytes(SHARE, link.value)!!.size).isEqualTo(10_000)
    val dir = NasBackupPaths.tempFor(me, 6)
    h.backend.putDir(SHARE, dir.value)
    // Una cartella non si apre come file (`FILE_NON_DIRECTORY_FILE`): per la ripresa e' come se il temporaneo non ci fosse.
    assertThat(ops.reopenTemp(SHARE, dir, 0, h.backend.fileId(SHARE, dir.value)!!, purpose).okOrNull()).isNotInstanceOf(ReopenOutcome.Reopened::class.java)
    assertThat(h.backend.exists(SHARE, dir.value)).isTrue()
    // Solo i temporanei del backup di questo dispositivo: altri sono un errore di programmazione, prima di qualunque richiesta.
    assertThrows(IllegalArgumentException::class.java) {
      kotlinx.coroutines.runBlocking { ops.reopenTemp(SHARE, NasBackupPaths.tempFor(other, 1), 0, 1L, purpose) }
    }
  }

  /** Piano B del §7: un server che rifiuta il `SET_INFO` di fine file da' un errore esplicito (il backup riparte da zero), mai un successo. */
  @Test
  fun unServerCheNonAccorciaDaUnErroreENonUnoScrittore() = runTest {
    val h = harness()
    val ops = h.ops()
    val temp = NasBackupPaths.tempFor(me, 7)
    val id = h.backend.putContent(SHARE, temp.value, NasContent.of(bytes(10_000)))
    h.backend.faults = FaultPlan().at(1, FaultPhase.BEFORE, SimFault.Status(NtStatus.NOT_SUPPORTED), op = SimOp.SET_END_OF_FILE)
    assertThat(ops.reopenTemp(SHARE, temp, 4_000, id, purpose).errorOrNull()).isInstanceOf(NasError.Unexpected::class.java)
    h.backend.faults = null
    assertThat(h.backend.bytes(SHARE, temp.value)!!.size).isEqualTo(10_000)
    // L'handle esclusivo non resta aperto: la ripresa dopo funziona.
    val reopened = ops.reopenTemp(SHARE, temp, 4_000, id, purpose).okOrNull() as ReopenOutcome.Reopened
    reopened.writer.close()
    assertThat(h.backend.bytes(SHARE, temp.value)!!.toList()).isEqualTo(bytes(10_000).copyOf(4_000).toList())
  }

  /**
   * Un guasto (caduta della connessione) prima e dopo **ogni** richiesta SMB della ripresa, poi una ripresa pulita: il temporaneo ha
   * sempre come prefisso i byte salvati, non si allunga mai, e alla fine si completa con i byte giusti.
   */
  @Test
  fun unGuastoAOgniRichiestaDellaRipresaNonRovinaMaiIlPrefisso() = runTest {
    val data = bytes(64_000, seed = 3)
    val keep = 20_000L
    suspend fun Harness.seed(): Triple<NasBackupOps, dev.pampa.mycloudgallery.core.trust.nas.SharePath, Long> {
      val temp = NasBackupPaths.tempFor(me, 8)
      val id = backend.putContent(SHARE, temp.value, NasContent.of(data.copyOf(50_000)))
      return Triple(ops(), temp, id)
    }
    val clean = harness()
    val (cleanOps, cleanTemp, cleanId) = clean.seed()
    val counter = FaultPlan()
    clean.backend.faults = counter
    (cleanOps.reopenTemp(SHARE, cleanTemp, keep, cleanId, purpose).okOrNull() as ReopenOutcome.Reopened).writer.close()
    val total = counter.operations
    assertWithMessage("richieste contate").that(total).isAtLeast(3)
    for (n in 1..total) {
      for (phase in FaultPhase.entries) {
        val h = harness()
        val (ops, temp, id) = h.seed()
        val fault = FaultPlan().at(n, phase, SimFault.DropConnection)
        h.backend.faults = fault
        val first = ops.reopenTemp(SHARE, temp, keep, id, purpose)
        (first.okOrNull() as? ReopenOutcome.Reopened)?.writer?.close()
        h.backend.faults = null
        val label = "guasto alla richiesta $n/$total $phase"
        assertWithMessage("$label: scattato").that(fault.fired).hasSize(1)
        val after = h.backend.bytes(SHARE, temp.value)!!
        assertWithMessage("$label: mai piu' lungo").that(after.size).isAtMost(50_000)
        assertWithMessage("$label: il prefisso salvato resta").that(after.copyOf(keep.toInt()).toList()).isEqualTo(data.copyOf(keep.toInt()).toList())
        // La ripresa dopo il guasto (il diario ha ancora lo stesso punto) arriva in fondo con i byte giusti.
        val reopened = ops.reopenTemp(SHARE, temp, keep, id, purpose).okOrNull() as? ReopenOutcome.Reopened
        assertWithMessage("$label: si riprende").that(reopened).isNotNull()
        val w = reopened!!.writer
        assertThat(w.write(keep, data, keep.toInt(), data.size - keep.toInt())).isEqualTo(NasResult.Ok(Unit))
        assertThat(w.finish(data.size.toLong()).okOrNull()).isInstanceOf(TempFinish.Complete::class.java)
        w.close()
        assertWithMessage("$label: byte finali").that(sha(h.backend.bytes(SHARE, temp.value)!!)).isEqualTo(sha(data))
      }
    }
  }

  // --- R3 della revisione 5A --------------------------------------------------------------------------------------------------

  private val media = bytes(200_000, seed = 41)
  private val mediaPath = "Foto/vacanze.mp4"

  /** Un temporaneo scritto fino a [written] byte con il suo punto di ripresa (l'identita' letta sull'handle), poi "la connessione cade". */
  private suspend fun Harness.interruptedTemp(n: Int, written: Int = 150_000): Pair<dev.pampa.mycloudgallery.core.trust.nas.SharePath, Long> {
    val temp = NasBackupPaths.tempFor(me, n.toLong())
    val writer = ops().openTemp(SHARE, temp, purpose).okOrNull()!!
    writer.write(0, bytes(written, seed = n), 0, written)
    val point = writer.checkpoint().okOrNull()!!
    writer.close()
    return temp to point.fileId!!
  }

  @Test
  fun r3_ilPuntoDiRipresaLeggeLIdentitaSulloStessoHandleNonPerNome() = runTest {
    val h = harness()
    h.backend.put(SHARE, mediaPath, media)
    val temp = NasBackupPaths.tempFor(me, 20)
    val writer = h.ops().openTemp(SHARE, temp, purpose).okOrNull()!!
    val own = h.backend.fileId(SHARE, temp.value)
    writer.write(0, bytes(70_000), 0, 70_000)
    // Mentre lo scrittore ha l'handle (uno script via SSH non chiede permesso), al nome del temporaneo finisce un collegamento al media.
    h.backend.delete(SHARE, temp.value)
    h.backend.putHardLink(SHARE, existing = mediaPath, link = temp.value)
    // Il punto di ripresa dice il file che lo scrittore sta scrivendo, non quello che adesso sta al suo nome.
    assertThat(writer.checkpoint().okOrNull()).isEqualTo(TempPoint(70_000, own))
    assertThat(own).isNotEqualTo(h.backend.fileId(SHARE, mediaPath))
    writer.close()
    // E con quell'identita' la riapertura rifiuta il collegamento.
    assertThat(h.ops().reopenTemp(SHARE, temp, 64_000, own!!, purpose).okOrNull()).isEqualTo(ReopenOutcome.Foreign)
    assertThat(sha(h.backend.bytes(SHARE, mediaPath)!!)).isEqualTo(sha(media))
  }

  @Test
  fun r3_unCollegamentoFisicoAlNomeDelTemporaneoNonSiAccorciaEDeleteOwnTempToglieSoloIlNome() = runTest {
    val h = harness()
    val ops = h.ops()
    h.backend.put(SHARE, mediaPath, media)
    val mediaId = h.backend.fileId(SHARE, mediaPath)
    val (temp, id) = h.interruptedTemp(21)
    h.backend.delete(SHARE, temp.value)
    h.backend.putHardLink(SHARE, existing = mediaPath, link = temp.value)
    val seen = h.recordOps()
    assertThat(ops.reopenTemp(SHARE, temp, 100_000, id, purpose).okOrNull()).isEqualTo(ReopenOutcome.Foreign)
    // Niente accorciato, niente scritto, l'handle esclusivo chiuso.
    assertThat(seen).containsNoneOf(SimOp.SET_END_OF_FILE, SimOp.WRITE, SimOp.CREATE, SimOp.DELETE_ON_CLOSE, SimOp.RENAME)
    assertThat(h.backend.openHandlesOn(SHARE, temp.value)).isEqualTo(0)
    assertThat(sha(h.backend.bytes(SHARE, mediaPath)!!)).isEqualTo(sha(media))
    // Anche con l'identita' del media (un diario che la avesse): due nomi, non si accorcia.
    assertThat(ops.reopenTemp(SHARE, temp, 100_000, mediaId!!, purpose).okOrNull()).isEqualTo(ReopenOutcome.Foreign)
    // Il motore poi ricomincia da capo: deleteOwnTemp toglie il NOME del temporaneo, il media resta lo stesso file con un nome solo.
    assertThat(ops.deleteOwnTemp(SHARE, temp, purpose).okOrNull()).isEqualTo(dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome.Deleted)
    assertThat(h.backend.exists(SHARE, temp.value)).isFalse()
    assertThat(sha(h.backend.bytes(SHARE, mediaPath)!!)).isEqualTo(sha(media))
    assertThat(h.backend.fileId(SHARE, mediaPath)).isEqualTo(mediaId)
    assertThat(h.backend.links(SHARE, mediaPath)).isEqualTo(1)
  }

  @Test
  fun r3_unFileConUnAltraIdentitaAlNomeDelTemporaneoNonSiAccorcia() = runTest {
    val h = harness()
    val ops = h.ops()
    h.backend.put(SHARE, mediaPath, media)
    val (temp, id) = h.interruptedTemp(22)
    // Un collegamento simbolico come lo mostra Samba di serie: un file normale con un nome solo, l'identita' e i byte del media.
    h.backend.delete(SHARE, temp.value)
    h.backend.putFileLink(SHARE, link = temp.value, target = mediaPath)
    assertThat(ops.reopenTemp(SHARE, temp, 100_000, id, purpose).okOrNull()).isEqualTo(ReopenOutcome.Foreign)
    assertThat(sha(h.backend.bytes(SHARE, mediaPath)!!)).isEqualTo(sha(media))
    // Un altro file qualunque al suo nome (rimesso da un altro programma): stessa cosa.
    h.backend.delete(SHARE, temp.value)
    h.backend.put(SHARE, temp.value, bytes(150_000, seed = 22))
    assertThat(ops.reopenTemp(SHARE, temp, 100_000, id, purpose).okOrNull()).isEqualTo(ReopenOutcome.Foreign)
    assertThat(h.backend.bytes(SHARE, temp.value)!!.size).isEqualTo(150_000)
    // deleteOwnTemp sul collegamento simbolico toglie il collegamento, mai la destinazione.
    h.backend.delete(SHARE, temp.value)
    h.backend.putFileLink(SHARE, link = temp.value, target = mediaPath)
    assertThat(ops.deleteOwnTemp(SHARE, temp, purpose).okOrNull()).isEqualTo(dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome.Deleted)
    assertThat(sha(h.backend.bytes(SHARE, mediaPath)!!)).isEqualTo(sha(media))
  }

  @Test
  fun r3_unTemporaneoConDueNomiNonSiAccorcia() = runTest {
    val h = harness()
    val ops = h.ops()
    val (temp, id) = h.interruptedTemp(23)
    // E' proprio il nostro temporaneo (stessa identita'), ma ha anche un altro nome: accorciarlo cambierebbe l'altro.
    h.backend.putHardLink(SHARE, existing = temp.value, link = "Foto/copia.mp4")
    val before = sha(h.backend.bytes(SHARE, "Foto/copia.mp4")!!)
    assertThat(ops.reopenTemp(SHARE, temp, 100_000, id, purpose).okOrNull()).isEqualTo(ReopenOutcome.Foreign)
    assertThat(sha(h.backend.bytes(SHARE, "Foto/copia.mp4")!!)).isEqualTo(before)
    assertThat(h.backend.bytes(SHARE, temp.value)!!.size).isEqualTo(150_000)
    // Tolto l'altro nome, e' di nuovo il temporaneo del punto di ripresa: si riprende.
    h.backend.delete(SHARE, "Foto/copia.mp4")
    val reopened = ops.reopenTemp(SHARE, temp, 100_000, id, purpose).okOrNull() as ReopenOutcome.Reopened
    reopened.writer.close()
    assertThat(h.backend.bytes(SHARE, temp.value)!!.size).isEqualTo(100_000)
  }

  @Test
  fun r3_unaTmpCheNonELaCartellaVeraNonSiRiprende() = runTest {
    val h = harness()
    val ops = h.ops()
    val (temp, _) = h.interruptedTemp(24)
    // `.mycloudgallery/tmp` sostituita da un collegamento a una cartella di foto, come Samba di serie lo presenta (cartella normale):
    // il file al nome del temporaneo e' quello che il server troverebbe passando dal collegamento (il simulatore non risolve i percorsi
    // attraverso, il file si mette al suo nome).
    h.backend.put(SHARE, "Foto/Altro/x.jpg", byteArrayOf(1))
    val tmp = temp.parent()!!.value
    h.backend.delete(SHARE, tmp)
    h.backend.putLink(SHARE, tmp, "Foto/Altro", transparent = true)
    val id = h.backend.putContent(SHARE, temp.value, NasContent.of(bytes(150_000, seed = 24)))
    // Tutto il resto torna (stessa identita', un nome solo): e' la voce `..` di tmp/ a dire che non e' la cartella vera.
    assertThat(ops.reopenTemp(SHARE, temp, 100_000, id, purpose).okOrNull()).isEqualTo(ReopenOutcome.Foreign)
    assertThat(h.backend.bytes(SHARE, temp.value)!!.size).isEqualTo(150_000)
  }
}
