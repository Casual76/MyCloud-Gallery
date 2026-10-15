package dev.pampa.mycloudgallery.core.nasops

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nas.contract.ListOutcome
import dev.pampa.mycloudgallery.core.nas.contract.NasAttributes
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nas.ops.RmdirOutcome
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemState
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.OwnTrashRef
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.PlanRejection
import dev.pampa.mycloudgallery.core.nasops.contract.PlanSummary
import dev.pampa.mycloudgallery.core.nasops.contract.PlanWarning
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import dev.pampa.mycloudgallery.core.nasops.contract.TrashManifest
import dev.pampa.mycloudgallery.core.nasops.engine.CatalogPresence
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsEngine
import dev.pampa.mycloudgallery.core.nasops.engine.NasOpsLock
import dev.pampa.mycloudgallery.core.nasops.store.TrashManifestFiles
import dev.pampa.mycloudgallery.core.nasops.testing.KitEngine
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.nasops.trash.NasTrashService
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Le correzioni della revisione di sicurezza della 4B (docs/review/4b-fase4b.md) che i cinque test di `SecurityReview4bTest` non coprono:
 * R1 (salto dell'ora accettato), R2 (la cartella cresciuta dopo la conferma), R3 (collegamenti trasparenti di Samba e inventario), R9
 * ("Ferma" che sopravvive alla morte del processo), I6, I10. Sempre sullo stack di produzione del kit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Review4bFixesTest {

  private val day = 24L * 60 * 60 * 1000

  private fun kit() = NasOpsTestKit(engine = KitEngine.PRODUCTION)

  private fun p(path: String) = SharePath.parse(path)

  // --- R1 -----------------------------------------------------------------------------------------------------------------------

  @Test
  fun r1_dopoUnSaltoDellOraAccettatoIlCestinoRiparteConTrentaGiorniPieni() = runTest {
    val kit = kit()
    kit.putFile("test", "Foto/a.jpg", byteArrayOf(1, 2, 3))
    assertThat(kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Foto/a.jpg"))))).isInstanceOf(StartOutcome.Started::class.java)
    kit.advance(20 * day)
    // L'ora del NAS e' saltata in avanti di 10 giorni ed e' stata accettata adesso (24 ore stabili o il gesto): da qui contano i 30 giorni.
    kit.advance(10 * day)
    kit.clocks.trustedSince = kit.clocks.nasNow
    kit.advance(day)
    assertWithMessage("31 giorni d'orologio, ma solo uno da quando l'ora e' di nuovo fidata").that(kit.trash.purgeExpired(1.minutes).purged).isEqualTo(0)
    kit.advance(30 * day)
    assertThat(kit.trash.purgeExpired(1.minutes).purged).isEqualTo(1)
  }

  // --- R2 -----------------------------------------------------------------------------------------------------------------------

  @Test
  fun r2_ilPianoContaLaCartellaELaConfermaNonValePerUnaCartellaCresciuta() = runTest {
    val kit = kit()
    kit.putFile("test", "Viaggi/Oslo/a.jpg", byteArrayOf(1, 2, 3))
    kit.putFile("test", "Viaggi/b.jpg", byteArrayOf(4, 5))
    val plan = (kit.operations.plan(OpRequest.Trash(listOf(kit.itemOf("test", "Viaggi")))) as PlanOutcome.Ready).plan
    assertThat(plan.summary.fileCount).isEqualTo(2)
    assertThat(plan.summary.totalBytes).isEqualTo(5)
    assertThat(plan.items.single().contentFiles).isEqualTo(2)
    // Fra il foglio e il tocco il PC aggiunge 1.000 byte nella cartella: la conferma era per 5 byte, la cartella non si sposta.
    kit.putFile("test", "Viaggi/Oslo/c.jpg", ByteArray(1_000))
    assertThat(kit.operations.start(plan, kit.confirm(plan))).isInstanceOf(StartOutcome.Started::class.java)
    assertThat(kit.exists("test", "Viaggi/Oslo/c.jpg")).isTrue()
    assertThat(kit.store.allTrash().none { it.state == TrashEntryState.TRASHED }).isTrue()
    val op = kit.store.ops(OpState.entries.toSet()).single()
    assertThat(kit.store.items(op.id).single().errorCode).isEqualTo(OpErrorCode.SOURCE_CHANGED)
  }

  @Test
  fun r2_moltiFileSonoUnAvvisoDelPiano() = runTest {
    val kit = kit()
    repeat(PlanSummary.MANY_FILES + 1) { kit.putFile("test", "Tanti/$it.jpg", byteArrayOf(1)) }
    val plan = (kit.operations.plan(OpRequest.Trash(listOf(kit.itemOf("test", "Tanti")))) as PlanOutcome.Ready).plan
    assertThat(plan.warnings).contains(PlanWarning.MANY_FILES)
    assertThat(plan.summary.fileCount).isEqualTo(PlanSummary.MANY_FILES + 1)
  }

  // --- R3 -----------------------------------------------------------------------------------------------------------------------

  /**
   * Un collegamento simbolico come lo presenta Samba di serie (`follow symlinks = yes`, client SMB 3 senza estensioni POSIX): una cartella
   * normale, **senza** `REPARSE_POINT**, con l'identita' della destinazione; i percorsi sotto di lei si risolvono nella destinazione (anche
   * per le cancellazioni); la voce `..` e' il genitore della destinazione. Il "nodo" del collegamento e' una cartella vuota del NAS finto
   * ([linkId]): si sposta con la cartella che lo contiene, come un collegamento vero.
   */
  private class SambaLink(
    private val kit: NasOpsTestKit,
    private val share: String,
    private val linkId: Long,
    private val target: SharePath,
    private val parentIdentityKnown: Boolean = true,
  ) {
    private val raw = kit.nas.reader
    private val rawOps = kit.nas.fileOps(kit.deviceId)

    private suspend fun entryOf(path: SharePath): NasEntry? = (raw.stat(share, path, Purpose.BACKGROUND) as? NasResult.Ok)?.value

    /** Il percorso vero sul disco: se un livello del percorso e' il collegamento, si continua dalla destinazione. */
    suspend fun resolve(path: SharePath): SharePath {
      val c = path.components
      for (n in 1..c.size) {
        val prefix = SharePath.of(*c.take(n).toTypedArray())
        if (entryOf(prefix)?.fileId == linkId) {
          val rest = c.drop(n)
          return rest.fold(target) { acc, part -> acc.child(part) }
        }
      }
      return path
    }

    /** La voce del collegamento come la mostra Samba: cartella, identita' e date della destinazione, nessun attributo speciale. */
    private suspend fun disguise(entry: NasEntry): NasEntry {
      if (entry.fileId != linkId) return entry
      val t = checkNotNull(entryOf(target))
      return entry.copy(fileId = t.fileId, modifiedAt = t.modifiedAt, attributes = NasAttributes.DIRECTORY)
    }

    val reader: NasMediaReader = object : NasMediaReader by raw {
      override suspend fun list(
        share: String,
        dir: SharePath?,
        purpose: Purpose,
        pageSize: Int,
        onPage: suspend (List<NasEntry>) -> Unit,
      ): NasResult<ListOutcome> = raw.list(share, dir?.let { resolve(it) }, purpose, pageSize) { page -> onPage(page.map { disguise(it) }) }

      override suspend fun stat(share: String, path: SharePath, purpose: Purpose): NasResult<NasEntry?> =
        when (val r = raw.stat(share, resolve(path), purpose)) {
          is NasResult.Ok -> NasResult.Ok(r.value?.let { disguise(it).copy(name = path.name) })
          is NasResult.Err -> r
        }

      override suspend fun open(share: String, path: SharePath, purpose: Purpose): NasResult<NasRandomFile> = raw.open(share, resolve(path), purpose)
    }

    val fileOps: NasFileOps = object : NasFileOps by rawOps {
      // Samba cancella dove il percorso porta: attraverso il collegamento, il file vero della destinazione.
      override suspend fun deleteIfUnchanged(share: String, path: SharePath, size: Long, purpose: Purpose, expect: NasStamp?): NasResult<DeleteOutcome> {
        val real = resolve(path)
        if (real == path) return rawOps.deleteIfUnchanged(share, path, size, purpose, expect)
        val entry = entryOf(real) ?: return NasResult.Ok(DeleteOutcome.Missing)
        if (entry.isDirectory) return NasResult.Ok(DeleteOutcome.NotAFile)
        if (entry.size != size || (expect != null && !expect.sameAs(entry.stamp))) return NasResult.Ok(DeleteOutcome.SizeChanged(entry.size))
        kit.nas.delete(share, real.value)
        return NasResult.Ok(DeleteOutcome.Deleted)
      }

      override suspend fun rmdirIfEmpty(share: String, dir: SharePath, purpose: Purpose): NasResult<RmdirOutcome> {
        // Il collegamento stesso non si toglie con rmdir (ENOTDIR); sotto di lui, si toglie la cartella vera.
        if (entryOf(dir)?.fileId == linkId) return NasResult.Err(NasError.Unexpected(null))
        return rawOps.rmdirIfEmpty(share, resolve(dir), purpose)
      }

      override suspend fun parentIdentity(share: String, dir: SharePath, purpose: Purpose): NasResult<Long?> {
        if (!parentIdentityKnown) return NasResult.Ok(null)
        val real = resolve(dir)
        if (real == dir) return rawOps.parentIdentity(share, dir, purpose)
        return NasResult.Ok(real.parent()?.let { entryOf(it)?.fileId })
      }
    }
  }

  private class LinkStack(val kit: NasOpsTestKit, val link: SambaLink, presence: CatalogPresence) {
    private val lock = NasOpsLock()
    val operations = NasOpsEngine(
      deviceId = kit.deviceId,
      accountId = kit.accountId,
      fileOps = link.fileOps,
      reader = link.reader,
      store = kit.store,
      clocks = kit.clocks,
      gate = kit.gate,
      lock = lock,
      presence = presence,
    )
    val trash = NasTrashService(
      deviceId = kit.deviceId,
      accountId = kit.accountId,
      shares = { listOf("test") },
      store = kit.store,
      fileOps = link.fileOps,
      reader = link.reader,
      clocks = kit.clocks,
      gate = kit.gate,
      lock = lock,
      presence = presence,
    )

    suspend fun trashFolder(path: String): StartOutcome {
      val entry = checkNotNull((link.reader.stat("test", SharePath.parse(path), Purpose.INTERACTIVE) as NasResult.Ok).value)
      val item = dev.pampa.mycloudgallery.core.nasops.contract.NasItem("test", SharePath.parse(path), entry.isDirectory, 0, null)
      val plan = (operations.plan(OpRequest.Trash(listOf(item))) as PlanOutcome.Ready).plan
      return operations.start(plan, kit.confirm(plan))
    }
  }

  private suspend fun fileIdOf(kit: NasOpsTestKit, path: String): Long? =
    (kit.nas.reader.stat("test", SharePath.parse(path), Purpose.BACKGROUND) as? NasResult.Ok)?.value?.fileId

  /** Foto/Vacanze con un file suo e il collegamento Album -> Foto/Album (la cartella vera di famiglia, con due foto). */
  private suspend fun linkedWorld(kit: NasOpsTestKit, parentIdentityKnown: Boolean = true): SambaLink {
    kit.putFile("test", "Foto/Vacanze/mare.jpg", byteArrayOf(1, 2, 3))
    kit.putFile("test", "Foto/Album/nonna.jpg", byteArrayOf(4, 5, 6, 7))
    kit.putFile("test", "Foto/Album/nonno.jpg", byteArrayOf(8, 9))
    kit.putDir("test", "Foto/Vacanze/Album")
    val linkId = checkNotNull(fileIdOf(kit, "Foto/Vacanze/Album"))
    return SambaLink(kit, "test", linkId, SharePath.parse("Foto/Album"), parentIdentityKnown)
  }

  @Test
  fun r3_laPuliziaNonAttraversaUnCollegamentoCheSambaPresentaComeCartella() = runTest {
    val kit = kit()
    val stack = LinkStack(kit, linkedWorld(kit), CatalogPresence.NONE)
    assertThat(stack.trashFolder("Foto/Vacanze")).isInstanceOf(StartOutcome.Started::class.java)
    assertThat(kit.exists("test", "Foto/Vacanze")).isFalse()
    // La scadenza: la cartella cestinata contiene il collegamento (genitore sul disco = Foto, non la cartella cestinata).
    kit.advance(TrustLimits.TRASH_RETENTION_MILLIS + day)
    val report = stack.trash.purgeExpired(1.minutes)
    assertThat(report.purged).isEqualTo(0)
    assertWithMessage("le foto vere di Foto/Album, mai cestinate").that(kit.filesIn("test")).containsAtLeast("Foto/Album/nonna.jpg", "Foto/Album/nonno.jpg")
    // "Elimina subito" passa dallo stesso controllo: si ferma, niente si cancella.
    val entry = kit.store.allTrash().single { it.state == TrashEntryState.TRASHED }
    val purge = (stack.operations.plan(OpRequest.PurgeNow(listOf(OwnTrashRef(entry.trashId)))) as PlanOutcome.Ready).plan
    kit.clocks.monotonic += 1
    stack.operations.start(purge, kit.confirm(purge))
    assertThat(kit.filesIn("test")).containsAtLeast("Foto/Album/nonna.jpg", "Foto/Album/nonno.jpg")
    assertThat(kit.store.trashEntry(entry.trashId)?.state).isEqualTo(TrashEntryState.TRASHED)
  }

  @Test
  fun r3_senzaLaVocePuntoPuntoBastaIlCatalogoAFermarlaSeVedeLeFotoFuoriDalCestino() = runTest {
    val kit = kit()
    val link = linkedWorld(kit, parentIdentityKnown = false)
    // Il catalogo (scansione) vede le foto vere a Foto/Album: sono presenti fuori dalla cartella cestinata.
    val known = listOf("Foto/Album/nonna.jpg", "Foto/Album/nonno.jpg").associateBy { checkNotNull(fileIdOf(kit, it)) }
    val catalog = CatalogPresence { _, ids -> ids.mapNotNull { id -> known[id]?.let { id to listOf(SharePath.parse(it)) } }.toMap() }
    val stack = LinkStack(kit, link, catalog)
    stack.trashFolder("Foto/Vacanze")
    kit.advance(TrustLimits.TRASH_RETENTION_MILLIS + day)
    assertThat(stack.trash.purgeExpired(1.minutes).purged).isEqualTo(0)
    assertThat(kit.filesIn("test")).containsAtLeast("Foto/Album/nonna.jpg", "Foto/Album/nonno.jpg")
  }

  @Test
  fun r3_laPuliziaCancellaSoloCioCheLInventarioDice() = runTest {
    val kit = kit()
    kit.putFile("test", "Gita/1.jpg", byteArrayOf(1))
    kit.putFile("test", "Gita/Mare/2.jpg", byteArrayOf(2, 2))
    kit.putFile("test", "Gita/Monti/3.jpg", byteArrayOf(3, 3, 3))
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Gita"))))
    val entry = kit.store.allTrash().single()
    val manifest = checkNotNull(kit.store.trashManifest(entry.trashId))
    assertThat(manifest.files).hasSize(3)
    assertThat(manifest.folders).hasSize(2)
    val root = NasLayout.trashItemFile(kit.deviceId, entry.trashId).value
    // Dopo il cestino: dentro la cartella cestinata compare un file che l'utente non ha mai eliminato (dal PC o da un dispositivo
    // compromesso), e una sottocartella nuova. La pulizia non tocca niente: la voce resta, e il file resta.
    kit.putFile("test", "$root/Mare/ospite.jpg", byteArrayOf(9))
    kit.advance(TrustLimits.TRASH_RETENTION_MILLIS + day)
    assertThat(kit.trash.purgeExpired(1.minutes).purged).isEqualTo(0)
    assertThat(kit.exists("test", "$root/Mare/ospite.jpg")).isTrue()
    assertThat(kit.exists("test", "$root/1.jpg")).isTrue()
    // Tolto l'ospite (dal PC), la stessa voce si elimina intera.
    kit.deleteFromPc("test", "$root/Mare/ospite.jpg")
    kit.advance(day)
    assertThat(kit.trash.purgeExpired(1.minutes).purged).isEqualTo(1)
    assertThat(kit.filesIn("test")).isEmpty()
  }

  @Test
  fun r3_unFileSostituitoNelCestinoConLoStessoNomeEDimensioneNonSiCancella() = runTest {
    val kit = kit()
    kit.putFile("test", "Gita/1.jpg", byteArrayOf(1, 1))
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Gita"))))
    val entry = kit.store.allTrash().single()
    val root = NasLayout.trashItemFile(kit.deviceId, entry.trashId).value
    // Un altro file, stessa dimensione e stesso nome, al posto di quello cestinato (identita' diversa).
    kit.deleteFromPc("test", "$root/1.jpg")
    kit.putFile("test", "$root/1.jpg", byteArrayOf(7, 7))
    kit.advance(TrustLimits.TRASH_RETENTION_MILLIS + day)
    assertThat(kit.trash.purgeExpired(1.minutes).purged).isEqualTo(0)
    assertThat(kit.bytes("test", "$root/1.jpg")).isEqualTo(byteArrayOf(7, 7))
  }

  @Test
  fun r3_unFileCestinatoSiCancellaSoloConIlTimbroDelDiario() = runTest {
    val kit = kit()
    kit.putFile("test", "Foto/a.jpg", byteArrayOf(1, 2, 3))
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Foto/a.jpg"))))
    val entry = kit.store.allTrash().single()
    val inTrash = NasLayout.trashItemFile(kit.deviceId, entry.trashId).value
    // Stessa dimensione, contenuto e data diversi (modificato sul posto dal PC): non e' piu' quello che l'utente ha eliminato.
    kit.modify("test", inTrash, byteArrayOf(9, 9, 9), modifiedAt = kit.clocks.nasNow!! + 5)
    kit.advance(TrustLimits.TRASH_RETENTION_MILLIS + day)
    assertThat(kit.trash.purgeExpired(1.minutes).purged).isEqualTo(0)
    assertThat(kit.exists("test", inTrash)).isTrue()
  }

  @Test
  fun r3_gliInventariSuFileSopravvivonoERifiutanoUnFileRovinato() {
    val dir = Files.createTempDirectory("mcg-manifest").toFile()
    try {
      val files = TrashManifestFiles(dir)
      val id = NasOpsTestKit.DEVICE_A
      val manifest = TrashManifest(42L, setOf(7L, 8L), mapOf(9L to TrashManifest.ManifestFile(10, 11), 12L to TrashManifest.ManifestFile(0, -5)))
      files.write(id, manifest)
      val back = checkNotNull(files.read(id))
      assertThat(back.rootFileId).isEqualTo(42L)
      assertThat(back.folders).containsExactly(7L, 8L)
      assertThat(back.files).isEqualTo(manifest.files)
      // Un byte cambiato: nessun inventario (la cartella allora non si svuota da sola, mai il contrario).
      val file = dir.listFiles()!!.single()
      val bytes = file.readBytes()
      bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
      file.writeBytes(bytes)
      assertThat(files.read(id)).isNull()
      files.remove(id)
      assertThat(files.read(id)).isNull()
    } finally {
      dir.deleteRecursively()
    }
  }

  // --- R9 -----------------------------------------------------------------------------------------------------------------------

  @Test
  fun r9_fermaSiScriveSubitoEUnaRipresaDopoLaMorteDelProcessoAnnullaNonContinua() = runTest {
    val kit = kit()
    repeat(3) { kit.putFile("test", "Foto/$it.jpg", byteArrayOf(it.toByte(), 1)) }
    val items = (0 until 3).map { kit.itemOf("test", "Foto/$it.jpg") }
    // La rete cade alla prima rinomina nel cestino: l'operazione resta in pausa con il primo elemento a meta'.
    kit.nas.fault = FakeNasFault { op, _, _ -> if (op == FakeNasOp.TRASH_MOVE) NasError.Timeout else null }
    assertThat(kit.run(OpRequest.Trash(items))).isInstanceOf(StartOutcome.Started::class.java)
    kit.nas.fault = null
    val op = kit.store.ops(OpState.entries.toSet()).single()
    assertThat(op.state).isEqualTo(OpState.PAUSED)
    // L'esecuzione tiene il lucchetto (sta lavorando): "Ferma" deve aspettarlo, ma la richiesta e' gia' nel diario.
    kit.lock.mutex.lock()
    val stopping = launch(start = CoroutineStart.UNDISPATCHED) { kit.operations.cancel(op.id) }
    runCurrent()
    assertThat(kit.store.op(op.id)?.errorCode).isEqualTo(OpErrorCode.STOPPED_BY_USER)
    // Il processo muore prima che l'annullamento arrivi in fondo.
    stopping.cancel()
    kit.lock.mutex.unlock()
    kit.restart()
    kit.operations.resumeAll(5.minutes)
    // La ripresa annulla cio' che si puo' annullare: i due file non ancora toccati restano dove sono.
    assertThat(kit.exists("test", "Foto/1.jpg")).isTrue()
    assertThat(kit.exists("test", "Foto/2.jpg")).isTrue()
    val after = kit.store.items(op.id)
    assertThat(after.filter { it.index > 0 }.map { it.state }).containsExactly(OpItemState.CANCELLED, OpItemState.CANCELLED)
    assertThat(kit.store.op(op.id)?.state?.terminal).isTrue()
  }

  // --- I6, I10 --------------------------------------------------------------------------------------------------------------------

  @Test
  fun i6_eliminaSubitoNonPrendeUnaVoceAncoraInManoAUnOperazioneAperta() = runTest {
    val kit = kit()
    kit.putFile("test", "Foto/a.jpg", byteArrayOf(1, 2, 3))
    kit.nas.fault = FakeNasFault { op, _, _ -> if (op == FakeNasOp.TRASH_MOVE) NasError.Timeout else null }
    kit.run(OpRequest.Trash(listOf(kit.itemOf("test", "Foto/a.jpg"))))
    kit.nas.fault = null
    val entry = kit.store.allTrash().single()
    val outcome = kit.operations.plan(OpRequest.PurgeNow(listOf(OwnTrashRef(entry.trashId))))
    assertThat((outcome as PlanOutcome.Rejected).reason).isEqualTo(PlanRejection.WRITES_BLOCKED)
  }

  @Test
  fun i10_laSorgenteNonVaNelCestinoSeLaCopiaPubblicataNonCePiu() = runTest {
    val kit = kit()
    kit.putFile("test", "Foto/a.jpg", byteArrayOf(1, 2, 3))
    // Pubblicata la copia, il cestino della sorgente non riesce (rete): l'operazione resta in pausa.
    kit.nas.fault = FakeNasFault { op, _, _ -> if (op == FakeNasOp.TRASH_MOVE) NasError.Timeout else null }
    kit.run(OpRequest.Move(listOf(kit.itemOf("test", "Foto/a.jpg")), "test-comune", null))
    kit.nas.fault = null
    assertThat(kit.exists("test-comune", "a.jpg")).isTrue()
    // Nel frattempo, dal PC, qualcuno toglie il "doppione" appena comparso.
    kit.deleteFromPc("test-comune", "a.jpg")
    kit.operations.resumeAll(5.minutes)
    assertWithMessage("l'unica copia rimasta").that(kit.exists("test", "Foto/a.jpg")).isTrue()
    assertThat(kit.store.allTrash().none { it.state == TrashEntryState.TRASHED }).isTrue()
  }
}
