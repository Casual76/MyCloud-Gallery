package dev.pampa.mycloudgallery.core.backup.upload

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupItemId
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupStore
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.IncomingDestination
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.UploadStateMachine
import dev.pampa.mycloudgallery.core.backup.testing.BackupDeath
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.backup.testing.DyingBackupCatalog
import dev.pampa.mycloudgallery.core.backup.testing.DyingBackupStore
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupGate
import dev.pampa.mycloudgallery.core.backup.testing.FakePhoneBytes
import dev.pampa.mycloudgallery.core.backup.testing.ProcessDeath
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.nas.ops.NasOpsPaths
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupPaths
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 7 P4, E-SH1 (riga P4 del §4.4: "`AlbumFolder` in `INCOMING`"): i media che stanno **solo sul telefono** si caricano direttamente nella
 * cartella dell'album condiviso con il motore vero del backup ([SafeUploadEngine], cammino `INCOMING` con la riga del telefono): letti per intero dal
 * telefono, caricati su un temporaneo proprio, riletti, pubblicati senza sostituire ("nome (2)"; lo stesso file gia' li' = collegato), e collegati
 * nel catalogo allo **stesso** media (D29). Idempotenti per chiave ([AlbumFolderUploads]). Un guasto **prima e dopo ogni** chiamata al NAS e la
 * **morte del processo** prima e dopo **ogni** scrittura del diario e del catalogo: a meta' e alla fine gli invarianti tengono.
 *
 * Invarianti: nessun file visibile nuovo che non abbia i byte di un media caricato, e solo nella cartella dell'album; nessuna sostituzione di un file
 * che c'era; nessun doppione degli stessi byte nella cartella; temporanei solo propri e citati a meta', nessuno alla fine; alla fine ogni elemento
 * fatto o collegato, con la copia verificata nel catalogo dello stesso media, nell'area comune, con i byte del telefono.
 */
class AlbumFolderUploadsTest {

  private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 37 + seed * 11 + (it shr 7)).toByte() }

  private val album = SharePath.of("Album condivisi", "Oslo")

  private class Bench(gateState: BackupGateState = BackupGateState.OPEN) {
    val kit = BackupTestKit()
    val gate = FakeBackupGate(gateState)
    val journal = MemoryAlbumUploadJournal()
    val ports = IncomingUploadPorts(NoLocal, gate, root = null, albumRoot = SharePath.of("Album condivisi"))
    var engine: SafeUploadEngine = newEngine()
    val phoneBytes = LinkedHashMap<MediaId, ByteArray>()

    fun uploads(store: BackupStore = kit.store) = AlbumFolderUploads(store, journal, gate, ports) { kit.commonShare }

    fun newEngine(store: BackupStore = kit.store, catalog: BackupCatalog = kit.catalog): SafeUploadEngine = SafeUploadEngine(
      deviceId = kit.deviceId,
      share = kit.share,
      ops = kit.ops,
      reader = kit.nas.reader,
      store = store,
      catalog = catalog,
      phone = kit.phone.port,
      gate = kit.gate,
      clock = kit.clock,
      planner = kit.planner,
      folder = { kit.folder },
      existing = RereadingCopyFinder(catalog, kit.nas.reader, kit.clock),
      facts = KitFacts(kit.catalog),
      config = UploadConfig(chunkBytes = kit.chunkBytes, checkpointBytes = kit.checkpointBytes, lockedFirstWaitMs = 10, lockedRetries = 3),
      sleep = { ms -> kit.clock.advance(ms) },
      incoming = ports,
    )

    /** Un media del telefono (mai caricato): la riga e il timbro dal catalogo. */
    fun phoneMedia(name: String, data: ByteArray): AlbumUploadItem {
      val id = kit.addPhoneMedia(name, NasContent.of(data))
      phoneBytes[id] = data
      val m = kit.catalog.media(id)!!
      return AlbumUploadItem(m.mediaId, m.versionId, m.size, m.phone!!, m.phoneStamp!!, m.quickHash, name)
    }

    fun restart() {
      engine = newEngine()
    }

    suspend fun runAll(e: SafeUploadEngine = engine) = listOf(e.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.INCOMING), e.resumeAll(1.hours))

    suspend fun untilQuiet() {
      repeat(12) {
        val results = runAll()
        if (kit.store.all().none { !it.state.terminal } && results.all { it.stoppedBy == RunStop.COMPLETE }) return
      }
    }

    private fun key(share: String, path: String) = share.lowercase(Locale.ROOT) + ":" + path.lowercase(Locale.ROOT)

    fun files(): Map<String, ByteArray> = buildMap {
      for (share in listOf(kit.share, kit.commonShare)) for (path in kit.nas.filePaths(share)) kit.nas.bytes(share, path)?.let { put(key(share, path), it) }
    }

    suspend fun violations(before: Map<String, ByteArray>, final: Boolean): List<String> {
      val v = ArrayList<String>()
      val now = files()
      for ((k, old) in before) {
        val cur = now[k]
        if (cur == null) v += "sparito un file che c'era ($k)" else if (!cur.contentEquals(old)) v += "byte cambiati ($k)"
      }
      val phoneShas = phoneBytes.values.map { FakePhoneBytes.sha256Of(NasContent.of(it)) }.toSet()
      val cited = kit.store.citedTemps().map { it.value.lowercase(Locale.ROOT) }.toSet()
      val news = now.filterKeys { it !in before }
      for ((k, data) in news) {
        val share = k.substringBefore(':')
        val path = k.substringAfter(':')
        if (NasOpsPaths.isAppArea(SharePath.parse(path))) {
          when {
            !NasBackupPaths.isBackupTempOf(SharePath.parse(path), kit.deviceId) -> v += "temporaneo non del backup ($k)"
            final -> v += "temporaneo rimasto ($k)"
            path !in cited -> v += "temporaneo non citato ($k)"
          }
          continue
        }
        if (FakePhoneBytes.sha256Of(NasContent.of(data)) !in phoneShas) v += "file visibile che non e' un media del telefono ($k)"
        if (share != kit.commonShare.lowercase(Locale.ROOT) || !path.startsWith("album condivisi/oslo/")) v += "file fuori dalla cartella dell'album ($k)"
      }
      news.filterKeys { !NasOpsPaths.isAppArea(SharePath.parse(it.substringAfter(':'))) }.entries
        .groupBy { FakePhoneBytes.sha256Of(NasContent.of(it.value)) }.filterValues { it.size > 1 }
        .forEach { (_, dup) -> v += "gli stessi byte due volte (${dup.joinToString { it.key }})" }
      for (t in kit.store.transitions) {
        val flow = kit.store.all().firstOrNull { it.id == t.item }?.flow ?: continue
        if (!UploadStateMachine.canTransition(flow, t.from, t.to)) v += "transizione non ammessa ${t.from.code} -> ${t.to.code}"
      }
      if (final) {
        for (item in kit.store.all()) {
          if (item.state != UploadState.DONE && item.state != UploadState.LINKED_EXISTING) v += "elemento #${item.id} fermo in ${item.state.code}"
          val copy = kit.catalog.copiesOf(item.mediaId).firstOrNull { it.share.equals(kit.commonShare, ignoreCase = true) && it.verifiedAtMillis != null }
          if (copy == null) {
            v += "nessuna copia verificata nell'area comune per lo stesso media (#${item.id})"
          } else if (!kit.nas.bytes(copy.share, copy.path.value)!!.contentEquals(phoneBytes.getValue(item.mediaId))) {
            v += "la copia collegata non ha i byte del telefono (#${item.id})"
          }
        }
      }
      return v
    }
  }

  private object NoLocal : IncomingLocalFiles {
    override suspend fun open(id: BackupItemId): IncomingLocalFile? = null

    override suspend fun finished(id: BackupItemId, state: UploadState) = Unit
  }

  private val dest = IncomingDestination.AlbumFolder(BackupTestKit.COMMON_SHARE, album)

  @Test
  fun eSh1IMediaDelTelefonoArrivanoNellaCartellaDellAlbumConLoStessoMedia() = runTest {
    val b = Bench()
    // Un file del PC con lo stesso nome (byte diversi) e lo stesso file gia' presente al suo nome.
    b.kit.nas.putFile(b.kit.commonShare, "Album condivisi/Oslo/IMG_1.jpg", NasContent.of(bytes(3_000, 99)), modifiedAt = 1_000)
    val same = bytes(20_000, 3)
    b.kit.nas.putFile(b.kit.commonShare, "Album condivisi/Oslo/IMG_3.jpg", NasContent.of(same), modifiedAt = 1_000)
    val items = listOf(b.phoneMedia("IMG_1.jpg", bytes(40_000, 1)), b.phoneMedia("VID_2.mp4", bytes(90_000, 2)), b.phoneMedia("IMG_3.jpg", same))
    val before = b.files()
    val uploads = b.uploads()
    assertThat(uploads.start("op-1:up", items, dest)).isEqualTo(AlbumUploadStart.Started("op-1:up"))
    // La stessa chiave non crea un secondo caricamento.
    assertThat(uploads.start("op-1:up", items, dest)).isEqualTo(AlbumUploadStart.Started("op-1:up"))
    assertThat(b.kit.store.all()).hasSize(3)
    assertThat(uploads.status("op-1:up")).isEqualTo(AlbumUploadStatus.InProgress(0, 3))
    b.untilQuiet()
    assertThat(uploads.status("op-1:up")).isEqualTo(AlbumUploadStatus.Ended(listOf(AlbumUploadOutcome.ARRIVED, AlbumUploadOutcome.ARRIVED, AlbumUploadOutcome.ALREADY_THERE)))
    assertThat(b.kit.nas.bytes(b.kit.commonShare, "Album condivisi/Oslo/IMG_1 (2).jpg")!!.toList()).isEqualTo(bytes(40_000, 1).toList())
    assertThat(b.kit.nas.bytes(b.kit.commonShare, "Album condivisi/Oslo/VID_2.mp4")!!.toList()).isEqualTo(bytes(90_000, 2).toList())
    assertWithMessage("invarianti").that(b.violations(before, final = true)).isEmpty()
    // I media restano sul telefono (non e' "Libera spazio") e non diventano "al sicuro": la copia e' nell'area comune.
    for (item in items) assertThat(b.kit.catalog.isSafe(item.mediaId)).isFalse()
    assertThat(uploads.find("op-1:up")).isEqualTo("op-1:up")
    assertThat(uploads.find("altro")).isNull()
  }

  /** E-B15 sul cammino nuovo: il file del telefono cambiato o cancellato prima del caricamento: niente si carica, l'esito lo dice. */
  @Test
  fun ilFileDelTelefonoCambiatoOSparitoNonSiCarica() = runTest {
    val b = Bench()
    val changed = b.phoneMedia("IMG_5.jpg", bytes(10_000, 5))
    val gone = b.phoneMedia("IMG_6.jpg", bytes(10_000, 6))
    val uploads = b.uploads()
    uploads.start("op-2:up", listOf(changed, gone), dest)
    b.kit.phone.rewrite(b.kit.phoneIdOf(changed.mediaId), NasContent.of(bytes(12_000, 50)), modifiedMs = 9_000)
    b.kit.phone.delete(b.kit.phoneIdOf(gone.mediaId))
    b.untilQuiet()
    assertThat(uploads.status("op-2:up")).isEqualTo(AlbumUploadStatus.Ended(listOf(AlbumUploadOutcome.SOURCE_CHANGED, AlbumUploadOutcome.SOURCE_GONE)))
    assertThat(b.kit.nas.filePaths(b.kit.commonShare).filterNot { it.startsWith(".mycloudgallery") }).isEmpty()
  }

  /** Solo l'area comune, solo sotto "Album condivisi", e mai con il cancello che chiude per sempre; con un cancello che aspetta si mette in coda. */
  @Test
  fun leDestinazioniNonAmmesseNonPartono() = runTest {
    val b = Bench()
    val item = b.phoneMedia("IMG_7.jpg", bytes(5_000, 7))
    val uploads = b.uploads()
    assertThat(uploads.start("k1", listOf(item), IncomingDestination.AlbumFolder(BackupTestKit.PRIVATE_SHARE, album)))
      .isEqualTo(AlbumUploadStart.Refused(AlbumUploadRefusal.NOT_COMMON))
    assertThat(uploads.start("k2", listOf(item), IncomingDestination.AlbumFolder(BackupTestKit.COMMON_SHARE, SharePath.of("Famiglia"))))
      .isEqualTo(AlbumUploadStart.Refused(AlbumUploadRefusal.FOLDER_NOT_ALLOWED))
    assertThat(uploads.start("k3", listOf(item), IncomingDestination.AlbumFolder(BackupTestKit.COMMON_SHARE, SharePath.of("Album condivisi"))))
      .isEqualTo(AlbumUploadStart.Refused(AlbumUploadRefusal.FOLDER_NOT_ALLOWED))
    b.gate.byShare[BackupTestKit.COMMON_SHARE] = BackupGateState.READ_ONLY_AREA
    assertThat(uploads.start("k4", listOf(item), dest)).isEqualTo(AlbumUploadStart.Refused(AlbumUploadRefusal.WRITES_BLOCKED))
    assertThat(b.kit.store.all()).isEmpty()
    b.gate.byShare[BackupTestKit.COMMON_SHARE] = BackupGateState.FROZEN
    assertThat(uploads.start("k5", listOf(item), dest)).isEqualTo(AlbumUploadStart.Started("k5"))
    b.runAll()
    assertThat(uploads.status("k5")).isEqualTo(AlbumUploadStatus.Waiting(0, 1))
    b.gate.byShare.clear()
    b.untilQuiet()
    assertThat(uploads.status("k5")).isEqualTo(AlbumUploadStatus.Ended(listOf(AlbumUploadOutcome.ARRIVED)))
  }

  /** L'intenzione scritta e gli elementi no (il processo e' morto in mezzo): la ripresa li crea, una volta sola. */
  @Test
  fun lIntenzioneSenzaElementiSiCompletaAllaRipresa() = runTest {
    val b = Bench()
    val item = b.phoneMedia("IMG_8.jpg", bytes(8_000, 8))
    b.journal.recordIntent("k", AlbumUploadIntent(BackupTestKit.COMMON_SHARE, album, listOf(item)))
    val uploads = b.uploads()
    assertThat(uploads.find("k")).isEqualTo("k")
    assertThat(uploads.status("k")).isEqualTo(AlbumUploadStatus.InProgress(0, 1))
    assertThat(uploads.status("k")).isEqualTo(AlbumUploadStatus.InProgress(0, 1))
    assertThat(b.kit.store.all()).hasSize(1)
    b.untilQuiet()
    assertThat(uploads.status("k")).isEqualTo(AlbumUploadStatus.Ended(listOf(AlbumUploadOutcome.ARRIVED)))
  }

  // --- Guasti a ogni chiamata e morte a ogni scrittura -------------------------------------------------------------------------------

  private suspend fun scenario(b: Bench) {
    b.kit.nas.putFile(b.kit.commonShare, "Album condivisi/Oslo/IMG_1.jpg", NasContent.of(bytes(2_000, 77)), modifiedAt = 1_000)
    val items = listOf(b.phoneMedia("IMG_1.jpg", bytes(70_000, 1)), b.phoneMedia("IMG_2.jpg", bytes(20_000, 2)))
    b.uploads().start("op:up", items, dest)
  }

  @Test
  fun unGuastoAOgniChiamataAlNas() = runTest {
    val clean = Bench()
    scenario(clean)
    val counter = FaultPlan()
    clean.kit.nas.opsFaults = counter
    clean.kit.nas.readFaults = counter
    clean.runAll()
    clean.kit.nas.opsFaults = null
    clean.kit.nas.readFaults = null
    clean.untilQuiet()
    val total = counter.operations
    assertThat(total).isAtLeast(8)
    for (n in 1..total) for (phase in FaultPhase.entries) {
      val h = Bench()
      scenario(h)
      val fault = FaultPlan().at(n, phase, SimFault.DropConnection)
      h.kit.nas.opsFaults = fault
      h.kit.nas.readFaults = fault
      h.runAll()
      h.kit.nas.opsFaults = null
      h.kit.nas.readFaults = null
      val label = "guasto alla chiamata $n/$total $phase"
      assertWithMessage("$label: scattato").that(fault.fired).hasSize(1)
      assertWithMessage("$label: a meta'").that(h.violations(h.filesBeforeUploads(), final = false)).isEmpty()
      h.restart()
      h.untilQuiet()
      assertWithMessage("$label: alla fine").that(h.violations(h.filesBeforeUploads(), final = true)).isEmpty()
    }
    println("P4 E-SH1: $total chiamate al NAS x 2 fasi, 0 violazioni")
  }

  @Test
  fun laMorteDelProcessoAOgniScrittura() = runTest {
    val clean = Bench()
    scenario(clean)
    val counter = BackupDeath()
    clean.runAll(clean.newEngine(DyingBackupStore(clean.kit.store, counter), DyingBackupCatalog(clean.kit.catalog, counter)))
    val total = counter.steps
    assertThat(total).isAtLeast(8)
    for (n in 1..total) for (after in listOf(false, true)) {
      val h = Bench()
      scenario(h)
      val death = BackupDeath(n, after)
      try {
        h.runAll(h.newEngine(DyingBackupStore(h.kit.store, death), DyingBackupCatalog(h.kit.catalog, death)))
      } catch (_: ProcessDeath) {
        // si riparte dal diario
      }
      val label = "morte alla scrittura $n/$total ${if (after) "dopo" else "prima"}"
      assertWithMessage("$label: avvenuta").that(death.died).isTrue()
      assertWithMessage("$label: a meta'").that(h.violations(h.filesBeforeUploads(), final = false)).isEmpty()
      h.restart()
      h.untilQuiet()
      assertWithMessage("$label: alla fine").that(h.violations(h.filesBeforeUploads(), final = true)).isEmpty()
    }
    println("P4 E-SH1: $total scritture x 2 (prima/dopo), 0 violazioni")
  }

  /** I file che c'erano prima del caricamento (quello del PC nella cartella). */
  private fun Bench.filesBeforeUploads(): Map<String, ByteArray> =
    mapOf("${kit.commonShare.lowercase(Locale.ROOT)}:album condivisi/oslo/img_1.jpg" to bytes(2_000, 77))
}
