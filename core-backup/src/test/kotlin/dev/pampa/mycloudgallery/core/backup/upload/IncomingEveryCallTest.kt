package dev.pampa.mycloudgallery.core.backup.upload

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupCatalog
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupItem
import dev.pampa.mycloudgallery.core.backup.contract.BackupItemId
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupStore
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.UploadStateMachine
import dev.pampa.mycloudgallery.core.backup.testing.BackupDeath
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.backup.testing.DyingBackupCatalog
import dev.pampa.mycloudgallery.core.backup.testing.DyingBackupStore
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupGate
import dev.pampa.mycloudgallery.core.backup.testing.FakePhoneBytes
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryBackupCatalog
import dev.pampa.mycloudgallery.core.backup.testing.ProcessDeath
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.ops.NasOpsPaths
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupPaths
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.IOException
import java.util.Locale
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il **cammino `INCOMING`** del motore vero (5B, P14; D24, E-X8): "Salva sul NAS" di un file ricevuto da un'altra app. Lo stesso caricamento sicuro
 * di ogni elemento (temporaneo proprio, rilettura completa, pubblicazione senza sostituire, "nome (2)"), la cartella scelta nell'area privata o
 * comune, e **la copia locale si toglie solo dopo** che la copia sul NAS e' riletta e collegata nel catalogo. Come gli altri cammini del motore:
 * un guasto **prima e dopo ogni** chiamata al NAS e la **morte del processo** prima e dopo **ogni** scrittura del diario, del catalogo e della
 * copia locale; dopo ogni interruzione (a meta') e dopo la ripresa dal diario (alla fine) gli invarianti qui sotto sono veri.
 *
 * Invarianti dei file in arrivo:
 * - nessun file visibile nuovo che non abbia i byte di un file in arrivo, e solo nella cartella scelta (mai fuori, mai nell'area dell'app);
 * - nessuna sostituzione di un file che c'era, nessun doppione degli stessi byte nella cartella;
 * - temporanei solo propri e citati dal diario a meta', nessuno a lavoro finito;
 * - **la copia locale non manca mai prima del collegamento**: se e' stata tolta, la copia sul NAS c'e', ha i byte giusti ed e' verificata nel
 *   catalogo;
 * - a lavoro finito: l'elemento e' `DONE` (o `LINKED_EXISTING` se lo stesso file c'era gia'), la copia locale e' tolta.
 */
class IncomingEveryCallTest {

  private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 31 + seed * 17 + (it shr 9)).toByte() }

  /** Le copie locali dei file in arrivo (la `incoming/` del telefono): sopravvivono alla morte del processo come un disco. */
  private class LocalFiles : IncomingLocalFiles {
    val files = HashMap<Long, ByteArray>()
    val removed = HashSet<Long>()
    var death: BackupDeath? = null

    override suspend fun open(id: BackupItemId): IncomingLocalFile? {
      val data = files[id.value] ?: return null
      return object : IncomingLocalFile {
        override val bytes: MediaBytes = InMemoryMediaBytes(data)

        override fun close() = Unit
      }
    }

    override suspend fun finished(id: BackupItemId, state: UploadState) {
      if (state != UploadState.DONE && state != UploadState.LINKED_EXISTING) return
      val step: suspend () -> Unit = {
        if (files.remove(id.value) != null) removed += id.value
      }
      death?.around { step() } ?: step()
    }
  }

  /** Un banco: il kit della 5A (NAS finto, diario e catalogo in memoria) con il motore vero e le porte dei file in arrivo. */
  private class Bench(val root: SharePath? = null, val gateState: BackupGateState = BackupGateState.OPEN) {
    val kit = BackupTestKit()
    val local = LocalFiles()
    val incomingGate = FakeBackupGate(gateState)
    var engine: SafeUploadEngine = newEngine()
    val expected = HashMap<Long, Pair<String, ByteArray>>()

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
      incoming = IncomingUploadPorts(local, incomingGate, root),
    )

    /** Un file ricevuto e messo in coda per [share]/[folder]/[name] (come fa la coda di `:app` dopo il tocco su "Salva sul NAS"). */
    suspend fun enqueue(share: String, folder: String, name: String, data: ByteArray, kind: MediaKind = MediaKind.PHOTO): BackupItem {
      val sha = FakePhoneBytes.sha256Of(NasContent.of(data))
      val media = kit.catalog.put(
        InMemoryBackupCatalog.Media(
          mediaId = MediaId.random(), versionId = VersionId.random(), kind = kind, size = data.size.toLong(), quickHash = null, phone = null,
          phoneStamp = null, source = BackupSource.Camera, localDay = null, fileName = name, sortTime = 0, fullHash = null,
        ),
      )
      val item = kit.store.createIncoming(
        NewBackupItem(
          kind = UploadKind.INCOMING, mediaId = media.mediaId, versionId = media.versionId, size = data.size.toLong(), phone = null,
          expected = null, share = share, planned = SharePath.parse("$folder/$name"), sha256 = sha,
        ),
      )
      local.files[item.id.value] = data.copyOf()
      expected[item.id.value] = "$share:$folder" to data
      return item
    }

    fun restart() {
      engine = newEngine()
    }

    suspend fun runAll(e: SafeUploadEngine = engine) = listOf(
      e.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.INCOMING),
      e.resumeAll(1.hours),
    )

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
      val items = kit.store.all()
      for ((k, old) in before) {
        val cur = now[k]
        if (cur == null) v += "3: sparito un file che c'era ($k)" else if (!cur.contentEquals(old)) v += "3: byte cambiati ($k)"
      }
      val news = now.filterKeys { it !in before }
      val incomingShas = expected.values.map { FakePhoneBytes.sha256Of(NasContent.of(it.second)) }.toSet()
      val folders = expected.values.map { it.first.lowercase(Locale.ROOT) }.toSet()
      val cited = kit.store.citedTemps().map { it.value.lowercase(Locale.ROOT) }.toSet()
      for ((k, data) in news) {
        val share = k.substringBefore(':')
        val path = k.substringAfter(':')
        if (NasOpsPaths.isAppArea(SharePath.parse(path))) {
          when {
            !NasBackupPaths.isBackupTempOf(SharePath.parse(path), kit.deviceId) -> v += "6: temporaneo non del backup ($k)"
            final -> v += "6: temporaneo rimasto a lavoro finito ($k)"
            path !in cited -> v += "6: temporaneo non citato ($k)"
          }
          continue
        }
        if (FakePhoneBytes.sha256Of(NasContent.of(data)) !in incomingShas) v += "1: file visibile con byte che non sono di un arrivo ($k)"
        if ("$share:${path.substringBeforeLast('/')}" !in folders) v += "10: file fuori dalla cartella scelta ($k)"
      }
      news.filterKeys { !NasOpsPaths.isAppArea(SharePath.parse(it.substringAfter(':'))) }.entries
        .groupBy { it.key.substringBefore(':') to FakePhoneBytes.sha256Of(NasContent.of(it.value)) }
        .filterValues { it.size > 1 }.forEach { (_, dup) -> v += "2: gli stessi byte pubblicati due volte (${dup.joinToString { it.key }})" }
      // La copia locale non manca mai prima del collegamento: se e' tolta, il NAS ha la copia verificata con i byte giusti.
      for (item in items) {
        val data = expected[item.id.value]?.second ?: continue
        if (item.id.value in local.files) continue
        if (item.id.value !in local.removed) continue
        val sha = FakePhoneBytes.sha256Of(NasContent.of(data))
        val copy = kit.catalog.copiesOf(item.mediaId).firstOrNull { it.verifiedAtMillis != null && it.share.equals(item.share, ignoreCase = true) }
        if (copy == null) {
          v += "L: copia locale tolta senza una copia verificata nel catalogo (#${item.id})"
        } else {
          val nasBytes = kit.nas.bytes(copy.share, copy.path.value)
          if (nasBytes == null || FakePhoneBytes.sha256Of(NasContent.of(nasBytes)) != sha) v += "L: copia locale tolta e copia sul NAS diversa (#${item.id})"
        }
      }
      for (t in kit.store.transitions) {
        val flow = items.firstOrNull { it.id == t.item }?.flow ?: continue
        if (!UploadStateMachine.canTransition(flow, t.from, t.to)) v += "8: transizione non ammessa ${t.from.code} -> ${t.to.code}"
      }
      if (final) {
        for (item in items) {
          if (item.state != UploadState.DONE && item.state != UploadState.LINKED_EXISTING) v += "9: elemento #${item.id} fermo in ${item.state.code}"
          if (item.id.value in local.files) v += "L: copia locale rimasta a lavoro finito (#${item.id})"
        }
      }
      return v
    }
  }

  private class Scenario(val name: String, val setup: suspend (Bench) -> Unit, val check: (Bench, String) -> Unit = { _, _ -> })

  private suspend fun faultsAtEveryCall(s: Scenario): Int {
    val clean = Bench()
    s.setup(clean)
    val before = clean.files()
    val counter = FaultPlan()
    clean.kit.nas.opsFaults = counter
    clean.kit.nas.readFaults = counter
    clean.runAll()
    clean.kit.nas.opsFaults = null
    clean.kit.nas.readFaults = null
    clean.untilQuiet()
    assertWithMessage("${s.name}, pulito").that(clean.violations(before, final = true)).isEmpty()
    s.check(clean, "${s.name}, pulito")
    val total = counter.operations
    assertWithMessage("${s.name}: chiamate contate").that(total).isAtLeast(4)
    for (n in 1..total) {
      for (phase in FaultPhase.entries) {
        val h = Bench()
        s.setup(h)
        val start = h.files()
        val fault = FaultPlan().at(n, phase, SimFault.DropConnection)
        h.kit.nas.opsFaults = fault
        h.kit.nas.readFaults = fault
        h.runAll()
        h.kit.nas.opsFaults = null
        h.kit.nas.readFaults = null
        val label = "${s.name}, guasto alla chiamata $n/$total $phase"
        assertWithMessage("$label: il guasto e' scattato").that(fault.fired).hasSize(1)
        assertWithMessage("$label: a meta'").that(h.violations(start, final = false)).isEmpty()
        h.restart()
        h.untilQuiet()
        assertWithMessage("$label: alla fine").that(h.violations(start, final = true)).isEmpty()
        s.check(h, label)
      }
    }
    return total
  }

  private suspend fun deathAtEveryWrite(s: Scenario): Int {
    val clean = Bench()
    s.setup(clean)
    val counter = BackupDeath()
    clean.local.death = counter
    clean.runAll(clean.newEngine(DyingBackupStore(clean.kit.store, counter), DyingBackupCatalog(clean.kit.catalog, counter)))
    val total = counter.steps
    assertWithMessage("${s.name}: scritture contate").that(total).isAtLeast(5)
    for (n in 1..total) {
      for (after in listOf(false, true)) {
        val h = Bench()
        s.setup(h)
        val start = h.files()
        val death = BackupDeath(n, after)
        h.local.death = death
        try {
          h.runAll(h.newEngine(DyingBackupStore(h.kit.store, death), DyingBackupCatalog(h.kit.catalog, death)))
        } catch (_: ProcessDeath) {
          // Il processo e' morto: si riparte dal diario.
        }
        h.local.death = null
        val label = "${s.name}, morte alla scrittura $n/$total ${if (after) "dopo" else "prima"}"
        assertWithMessage("$label: la morte e' avvenuta").that(death.died).isTrue()
        assertWithMessage("$label: a meta'").that(h.violations(start, final = false)).isEmpty()
        h.restart()
        h.untilQuiet()
        assertWithMessage("$label: alla fine").that(h.violations(start, final = true)).isEmpty()
        s.check(h, label)
      }
    }
    return total
  }

  private suspend fun all(s: Scenario) {
    val calls = faultsAtEveryCall(s)
    val writes = deathAtEveryWrite(s)
    println("P14 arrivi ${s.name}: $calls chiamate al NAS x 2 fasi, $writes scritture x 2 (prima/dopo)")
  }

  // --- Gli scenari ----------------------------------------------------------------------------------------------------------------

  @Test
  fun unaFotoNellAreaPrivata() = runTest {
    all(
      Scenario(
        "foto nell'area privata",
        setup = { b -> b.enqueue(b.kit.share, "Ricevute", "IMG-WA0001.jpg", bytes(40_000, 1)) },
        check = { b, label ->
          assertWithMessage(label).that(b.kit.nas.bytes(b.kit.share, "Ricevute/IMG-WA0001.jpg")?.toList()).isEqualTo(bytes(40_000, 1).toList())
          assertWithMessage(label).that(b.kit.store.all().single().state).isEqualTo(UploadState.DONE)
        },
      ),
    )
  }

  /** Un video con piu' punti di ripresa nell'area comune e, insieme, una foto con il nome gia' occupato da un altro file ("nome (2)"). */
  @Test
  fun unVideoNellAreaComuneEUnaFotoConIlNomeOccupato() = runTest {
    all(
      Scenario(
        "video nell'area comune e nome occupato",
        setup = { b ->
          b.kit.nas.putFile(b.kit.share, "Ricevute/IMG_1.jpg", NasContent.of(bytes(5_000, 9)), modifiedAt = 1_000)
          b.enqueue(b.kit.commonShare, "Famiglia/Gite", "VID_1.mp4", bytes(100_000, 2), kind = MediaKind.VIDEO)
          b.enqueue(b.kit.share, "Ricevute", "IMG_1.jpg", bytes(30_000, 3))
        },
        check = { b, label ->
          assertWithMessage(label).that(b.kit.nas.bytes(b.kit.commonShare, "Famiglia/Gite/VID_1.mp4")?.toList()).isEqualTo(bytes(100_000, 2).toList())
          assertWithMessage(label).that(b.kit.nas.bytes(b.kit.share, "Ricevute/IMG_1.jpg")?.toList()).isEqualTo(bytes(5_000, 9).toList())
          assertWithMessage(label).that(b.kit.nas.bytes(b.kit.share, "Ricevute/IMG_1 (2).jpg")?.toList()).isEqualTo(bytes(30_000, 3).toList())
        },
      ),
    )
  }

  /** Lo stesso file c'era gia' al suo nome (riletto per intero): si collega quello, niente si carica, la copia locale si toglie. */
  @Test
  fun loStessoFileCeraGia() = runTest {
    all(
      Scenario(
        "stesso file gia' presente",
        setup = { b ->
          b.kit.nas.putFile(b.kit.share, "Ricevute/IMG_2.jpg", NasContent.of(bytes(20_000, 4)), modifiedAt = 1_000)
          b.enqueue(b.kit.share, "Ricevute", "IMG_2.jpg", bytes(20_000, 4))
        },
        check = { b, label ->
          // Collegato al file che c'era (subito, o dopo una risposta persa della pubblicazione): mai una seconda copia.
          assertWithMessage(label).that(b.kit.store.all().single().state).isAnyOf(UploadState.LINKED_EXISTING, UploadState.DONE)
          assertWithMessage(label).that(b.kit.nas.filePaths(b.kit.share).filter { it.startsWith("Ricevute/") }).containsExactly("Ricevute/IMG_2.jpg")
        },
      ),
    )
  }

  // --- I casi che non caricano -------------------------------------------------------------------------------------------------------

  @Test
  fun d12ConIlCancelloChiusoNienteSiScriveELaCopiaLocaleResta() = runTest {
    val b = Bench(gateState = BackupGateState.TEST_ONLY)
    val item = b.enqueue(b.kit.share, "Ricevute", "IMG.jpg", bytes(10_000, 5))
    val before = b.files()
    b.runAll()
    assertThat(b.files().keys).isEqualTo(before.keys)
    assertThat(b.kit.store.item(item.id)!!.state).isEqualTo(UploadState.PLANNED)
    assertThat(b.local.files).containsKey(item.id.value)
  }

  @Test
  fun d13FuoriDallaRadiceDiProvaNienteSiPubblica() = runTest {
    val b = Bench(root = SharePath.of("mcg-campo"))
    val outside = b.enqueue(b.kit.share, "Ricevute", "IMG.jpg", bytes(10_000, 6))
    val inside = b.enqueue(b.kit.share, "mcg-campo/Ricevute", "IMG2.jpg", bytes(11_000, 7))
    b.untilQuiet()
    assertThat(b.kit.store.item(outside.id)!!.state).isEqualTo(UploadState.FAILED)
    assertThat(b.kit.nas.filePaths(b.kit.share).filterNot { it.startsWith(".mycloudgallery/") }).containsExactly("mcg-campo/Ricevute/IMG2.jpg")
    assertThat(b.kit.store.item(inside.id)!!.state).isEqualTo(UploadState.DONE)
    // La copia locale di cio' che non si e' caricato resta (l'utente non perde il file).
    assertThat(b.local.files).containsKey(outside.id.value)
    assertThat(b.local.files).doesNotContainKey(inside.id.value)
  }

  @Test
  fun unaCopiaLocaleSparitaOCambiataNonSiCaricaMai() = runTest {
    val b = Bench()
    val gone = b.enqueue(b.kit.share, "Ricevute", "A.jpg", bytes(10_000, 8))
    val changed = b.enqueue(b.kit.share, "Ricevute", "B.jpg", bytes(10_000, 9))
    b.local.files.remove(gone.id.value)
    b.local.files[changed.id.value]!![100] = 0x42
    b.untilQuiet()
    assertThat(b.kit.store.item(gone.id)!!.state).isEqualTo(UploadState.GONE)
    assertThat(b.kit.store.item(changed.id)!!.state).isEqualTo(UploadState.FAILED)
    assertThat(b.kit.store.item(changed.id)!!.problem).isEqualTo(BackupProblemCode.UNREADABLE)
    assertThat(b.kit.nas.filePaths(b.kit.share).filter { it.startsWith("Ricevute/") }).isEmpty()
    // La copia cambiata resta: nessun dato si perde.
    assertThat(b.local.files).containsKey(changed.id.value)
  }

  /** Senza le porte dei file in arrivo gli elementi aspettano: niente si carica, niente si toglie. */
  @Test
  fun senzaLePorteGliArriviAspettano() = runTest {
    val b = Bench()
    val item = b.enqueue(b.kit.share, "Ricevute", "C.jpg", bytes(10_000, 10))
    val bare = SafeUploadEngine(
      deviceId = b.kit.deviceId, share = b.kit.share, ops = b.kit.ops, reader = b.kit.nas.reader, store = b.kit.store, catalog = b.kit.catalog,
      phone = b.kit.phone.port, gate = b.kit.gate, clock = b.kit.clock, planner = b.kit.planner, folder = { b.kit.folder },
      existing = RereadingCopyFinder(b.kit.catalog, b.kit.nas.reader, b.kit.clock),
    )
    bare.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.INCOMING)
    bare.resumeAll(1.hours)
    assertThat(b.kit.store.item(item.id)!!.state).isEqualTo(UploadState.PLANNED)
    assertThat(b.local.files).containsKey(item.id.value)
  }

  /** Un errore di lettura della copia locale non e' un `GONE`: si riprova (la copia c'e'). */
  @Test
  fun unErroreDiLetturaDellaCopiaLocaleSiRiprova() = runTest {
    val b = Bench()
    val item = b.enqueue(b.kit.share, "Ricevute", "D.jpg", bytes(200_000, 11))
    val real = b.local.files[item.id.value]!!
    var failures = 2
    val flaky = object : IncomingLocalFiles {
      override suspend fun open(id: BackupItemId): IncomingLocalFile? {
        val inner = b.local.open(id) ?: return null
        return object : IncomingLocalFile {
          override val bytes: MediaBytes = object : MediaBytes by inner.bytes {
            override suspend fun read(range: LongRange): ByteArray {
              if (failures > 0) {
                failures--
                throw IOException("lettura interrotta")
              }
              return inner.bytes.read(range)
            }
          }

          override fun close() = inner.close()
        }
      }

      override suspend fun finished(id: BackupItemId, state: UploadState) = b.local.finished(id, state)
    }
    val engine = SafeUploadEngine(
      deviceId = b.kit.deviceId, share = b.kit.share, ops = b.kit.ops, reader = b.kit.nas.reader, store = b.kit.store, catalog = b.kit.catalog,
      phone = b.kit.phone.port, gate = b.kit.gate, clock = b.kit.clock, planner = b.kit.planner, folder = { b.kit.folder },
      existing = RereadingCopyFinder(b.kit.catalog, b.kit.nas.reader, b.kit.clock),
      config = UploadConfig(chunkBytes = b.kit.chunkBytes, checkpointBytes = b.kit.checkpointBytes, inMemoryMaxBytes = 0),
      incoming = IncomingUploadPorts(flaky, b.incomingGate),
    )
    repeat(4) { engine.resumeAll(1.hours) }
    assertThat(b.kit.store.item(item.id)!!.state).isEqualTo(UploadState.DONE)
    assertThat(b.kit.nas.bytes(b.kit.share, "Ricevute/D.jpg")?.toList()).isEqualTo(real.toList())
  }

  @Suppress("unused")
  private fun shaOf(data: ByteArray): Sha256 = FakePhoneBytes.sha256Of(NasContent.of(data))
}
