package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.albums.AlbumsGraph
import dev.pampa.mycloudgallery.albums.AlbumsLive
import dev.pampa.mycloudgallery.albums.AlbumsWiring
import dev.pampa.mycloudgallery.core.albums.contract.PublishRules
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationSheet
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareStartOutcome
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.core.trust.registry.ApprovalProof
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fase 7, P13 ("Fatto quando": il banco di P5 **sui legami veri di `:app`**, `docs/prova-500.md` Parte E: "il banco va ripetuto sui legami di `:app`").
 * Il banco di P5 (`ShareChaosBench` di core-albums) gira sui legami scritti nei test di core-albums; qui gira l'**app vera**: `AlbumsWiring` con
 * `OpsAlbumMover` sopra le vere `NasOperations` della 4B (diario di core-nasops su Room), `CatalogMoveCompletionSink` sopra `MediaCatalog.attachMoved`,
 * `RoomShareDiary`, i registri durevoli e il pubblicatore su Room, sul **simulatore SMB** con i guasti del suo piano (connessione caduta prima o dopo
 * l'operazione, sessione scaduta) e con la **morte del processo** a un punto a caso del lavoro (il dispositivo si chiude e rinasce sulle sue cartelle).
 *
 * Ogni giro (seme ripetibile): due famiglie di dispositivi (`test` e `test2`), 2-4 foto nell'area privata, un album condiviso confermato con un tocco;
 * il lavoro gira a colpi armati per un numero di passi a caso; poi senza guasti fino in fondo, e gli invarianti:
 *  1. nessun file perso: i byte di ogni foto sono sul NAS (area privata, area comune o cestino del NAS);
 *  2. il diario della condivisione e' chiuso e ogni elemento e' in uno stato finale; nessuna operazione della 4B resta aperta;
 *  3. un elemento fatto ha **un** file nella cartella dell'album, il catalogo lo trova li' con lo **stesso** media (D29), e la sorgente non e' piu' nell'area privata;
 *  4. nessun file delle foto in due copie nell'area comune;
 *  5. il familiare dell'altro account vede l'album con tanti elementi quanti quelli fatti.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AlbumsShareBenchAppTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val today = Today { LocalDate.of(2026, 10, 27) }

  /** Un dispositivo vivo: il grafo dell'app, la galleria e gli album. Muore e rinasce sulle stesse cartelle (i file e le chiavi restano). */
  private class Alive(val device: TestDevice, val rig: BackupRig, val albums: AlbumsGraph) {
    val live: AlbumsLive get() = checkNotNull(albums.live.value) { "album non montati" }
  }

  private class World(val time: TestTime, val backend: InMemorySmbBackend, val root: File, val round: Int) {
    val alive = ArrayList<Alive>()
    val devices = ArrayList<TestDevice>()
    var deaths = 0
    var faultsFired = 0
    var done = 0
    var other = 0
  }

  @Test
  fun ilBancoDellaCondivisioneSuiLegamiDiApp() = runBlocking<Unit> {
    val failures = ArrayList<String>()
    var deaths = 0
    var faults = 0
    for (round in 0 until ROUNDS) {
      val seed = BASE_SEED * 1_000_003L + round * 7_919L + 17
      val time = TestTime()
      val world = World(time, TestNas.backend(time), File(temp.root, "giro-$round"), round)
      val startedAt = System.nanoTime()
      try {
        val problems = try {
          round(world, seed)
        } catch (e: Exception) {
          listOf("eccezione ${e.javaClass.simpleName}: ${e.message}")
        }
        if (problems.isNotEmpty()) failures += "giro $round (seme $seed): ${problems.joinToString("; ")}"
        println("BANCO P13: giro $round in ${(System.nanoTime() - startedAt) / 1_000_000} ms, morti ${world.deaths}, guasti ${world.faultsFired}, elementi fatti ${world.done} e non ${world.other}, problemi ${problems.size}")
      } finally {
        deaths += world.deaths
        faults += world.faultsFired
        world.alive.forEach { a -> runCatching { runBlocking { a.albums.stop(); a.rig.gallery.shutdown() } }; runCatching { a.rig.close() } }
        world.devices.forEach { runCatching { it.close() } }
      }
    }
    println("BANCO P13: $ROUNDS giri, morti del processo $deaths, guasti SMB $faults, giri rossi ${failures.size}")
    assertWithMessage(failures.joinToString("\n")).that(failures).isEmpty()
    assertWithMessage("il banco deve far cadere davvero qualcosa").that(deaths + faults).isAtLeast(ROUNDS)
  }

  private suspend fun round(world: World, seed: Long): List<String> {
    val random = Random(seed)
    val backend = world.backend
    // I database di un giro partono vuoti (anche se un giro di prima, nello stesso processo dei test, avesse usato lo stesso nome).
    listOf(TestNas.ADMIN_USER, TestNas.MEMBER_USER).forEach { app.deleteDatabase(dbName(world, it)) }
    val phoneDir = File(world.root, "telefono")
    val tabletDir = File(world.root, "tablet")
    val phonePlatform = TestPlatform(world.time, backend)
    val tabletPlatform = TestPlatform(world.time, backend)
    val phone = device(world, phoneDir, phonePlatform)
    phone.graph.start()
    phone.graph.foundFamily()
    val tablet = device(world, tabletDir, tabletPlatform)
    val invite = phone.graph.inviteMember()
    tablet.graph.start()
    val awaiting = tablet.graph.joinWith(invite)
    phone.graph.registry.refresh(Purpose.INTERACTIVE)
    phone.graph.registry.approve(phone.graph.registry.pendingRequests.value.single().id, ApprovalProof.TypedCode(awaiting.code))
    tablet.graph.approvalWatcher.watch().first { it !is TrustStatus.Pending && it !is TrustStatus.Stale }
    tablet.graph.awaitSetup { it is SetupState.Ready }
    phone.graph.registry.refresh(Purpose.INTERACTIVE)

    val count = 2 + random.nextInt(3)
    val photos = (1..count).associate { n ->
      "Foto/Giro/IMG_${1000 + n}.jpg" to BackupRig.jpeg(tone = 10 + n * 40) + ByteArray(64 + 37 * n + random.nextInt(50)) { (n * 13 + it + seed.toInt()).toByte() }
    }
    photos.forEach { (path, bytes) -> backend.put(TestNas.ADMIN_USER, path, bytes, lastWriteMs = world.time.now() - 2 * DAY) }
    val digests = photos.values.map { sha(it) }.toSet()

    var phoneAlive = open(world, phone, TestNas.ADMIN_USER)
    val ids = idsOf(phoneAlive.rig, count)

    val outcome = phoneAlive.live.shared.albums.create("Giro", ShareLevel.ADD, ids)
    val plan = (outcome as? SharePlanOutcome.Ready)?.plan ?: return listOf("piano rifiutato: $outcome")
    val confirmation = checkNotNull(ShareConfirmationSheet(plan).tap(phoneAlive.live.shared.ports.clocks.monotonicMs()))
    val started = phoneAlive.live.shared.orchestrator.start(plan, confirmation)
    if (started !is ShareStartOutcome.Started) return listOf("avvio: $started")

    // 1. A colpi armati: guasti SMB a caso sulle operazioni sui file e, a un passo a caso, la morte del processo del telefono.
    val plan2 = FaultPlan()
      .random(seed + 1, FAULT_PROBABILITY, FaultPhase.BEFORE, SimFault.DropConnection, FILE_OPS)
      .random(seed + 2, FAULT_PROBABILITY, FaultPhase.AFTER, SimFault.DropConnection, FILE_OPS)
      .random(seed + 3, FAULT_PROBABILITY / 2, FaultPhase.BEFORE, SimFault.ExpireSession, FILE_OPS)
    backend.faults = plan2
    val armedSteps = 3 + random.nextInt(8)
    val deathAt = random.nextInt(armedSteps + 2)
    for (step in 0 until armedSteps) {
      if (step == deathAt) {
        phoneAlive = die(world, phoneAlive, phoneDir, phonePlatform)
      }
      drive(world, phoneAlive)
    }
    world.faultsFired += plan2.fired.size
    backend.faults = null

    // 2. Senza guasti, fino in fondo (anche le operazioni della 4B rimaste a meta' riprendono: come il loro lavoro).
    val finished = withTimeoutOrNull(Attese.TETTO) {
      while (true) {
        drive(world, phoneAlive)
        val open = phoneAlive.live.shared.diary.openOps()
        val nasOpen = phoneAlive.rig.nas.organizer.opsStore.ops(OpState.entries.toSet()).filter { !it.state.terminal }
        if (open.isEmpty() && nasOpen.isEmpty()) break
      }
    }
    val problems = ArrayList<String>()
    if (finished == null) {
      val op = phoneAlive.live.shared.diary.openOps().firstOrNull()
      problems += "lavoro non finito: " + (op?.let { o -> phoneAlive.live.shared.diary.items(o.id).map { "${it.state}/${it.failure}/${it.wait}" } } ?: "operazioni della 4B aperte")
      return problems
    }

    // 3. Gli invarianti.
    val items = phoneAlive.live.shared.diary.items(started.op)
    if (items.any { !it.state.terminal }) problems += "elementi non finiti: ${items.map { it.state }}"
    val everywhere = allFiles(backend, TestNas.ADMIN_USER).map { sha(checkNotNull(backend.bytes(TestNas.ADMIN_USER, it))) } +
      allFiles(backend, TestNas.COMMON).map { sha(checkNotNull(backend.bytes(TestNas.COMMON, it))) }
    for (digest in digests) if (digest !in everywhere) problems += "una foto non e' piu' sul NAS (invariante 1)"
    val inCommon = allFiles(backend, TestNas.COMMON).filterNot { it.startsWith(".mycloudgallery") }
      .groupBy { sha(checkNotNull(backend.bytes(TestNas.COMMON, it))) }
    for ((digest, paths) in inCommon) if (digest in digests && paths.size > 1) problems += "una foto in ${paths.size} copie nell'area comune (invariante 4)"
    val done = items.filter { it.state == ShareItemState.DONE }
    world.done += done.size
    world.other += items.size - done.size
    for (item in done) {
      val dest = item.dest ?: run { problems += "elemento fatto senza destinazione"; null } ?: continue
      val bytes = backend.bytes(TestNas.COMMON, dest.path.value)
      if (bytes == null || sha(bytes) !in digests) problems += "elemento fatto senza il suo file nella cartella dell'album (invariante 3)"
      val source = item.source
      if (source != null && backend.exists(TestNas.ADMIN_USER, source.path.value)) problems += "elemento fatto con la sorgente ancora nell'area privata (invariante 3)"
    }
    // D29: gli stessi media, ora nell'area comune (la Libreria si aggiorna da sola: si aspetta l'evento, col tetto comune).
    val view = withTimeoutOrNull(Attese.TETTO) {
      phoneAlive.rig.gallery.repository.view(LibraryFilter.ALL).first { v -> v.size == count && List(v.size) { v.snapshot.idAt(v.snapshotIndex(it)) }.toSet() == ids.toSet() }
    }
    if (view == null) {
      val v = phoneAlive.rig.gallery.repository.view(LibraryFilter.ALL).value
      val now = List(v.size) { v.snapshot.idAt(v.snapshotIndex(it)) }.toSet()
      problems += "i media sono cambiati (D29): ${ids.size} -> ${v.size}, ${now.intersect(ids.toSet()).size} uguali; elementi ${items.map { "${it.state}/${it.failure}" }}"
    }

    // 4. Il familiare dell'altro account vede l'album con tanti elementi quanti quelli fatti.
    if (done.isNotEmpty()) {
      world.time.advance(PublishRules.MIN_HEAD_INTERVAL_MS + 1_000)
      repeat(3) { drive(world, phoneAlive) }
      val tabletAlive = open(world, tablet, TestNas.MEMBER_USER)
      tablet.graph.trustGuard.preflight(Purpose.BACKGROUND)
      checkNotNull(tabletAlive.rig.nas.scan(ScanMode.Full, 5.minutes))
      tabletAlive.albums.syncRound(1.minutes)
      val seen = withTimeoutOrNull(Attese.TETTO) {
        tabletAlive.live.shared.albums.visible.first { list -> list.any { it.title == "Giro" && it.count == done.size } }
      }
      if (seen == null) problems += "il familiare non vede l'album con ${done.size} elementi: ${tabletAlive.live.shared.albums.visible.value.map { it.count }} (invariante 5)"
    }
    return problems
  }

  // --- Il dispositivo e il lavoro -------------------------------------------------------------------------------------------------------

  private fun device(world: World, dir: File, platform: TestPlatform) = TestDevice(app, dir, platform).also { world.devices += it }

  private suspend fun open(world: World, device: TestDevice, account: String): Alive {
    // Il database su file (un nome per giro e per account): il processo nuovo ritrova catalogo, diari e righe degli album, come sul telefono.
    val rig = BackupRig(app, device, account, databaseName = dbName(world, account))
    rig.open()
    device.graph.trustGuard.preflight(Purpose.BACKGROUND)
    val albums = AlbumsWiring.build(device.context, device.graph, rig.gallery, search = null, today = today)
    checkNotNull(albums.ensureLive()) { "album non montati" }
    return Alive(device, rig, albums).also { world.alive += it }
  }

  /** La morte del processo del telefono: tutto si ferma dov'e' (anche a meta' di un'operazione), poi un processo nuovo sulle stesse cartelle. */
  private suspend fun die(world: World, alive: Alive, dir: File, platform: TestPlatform): Alive {
    world.deaths++
    world.alive.remove(alive)
    runCatching { alive.albums.stop() }
    runCatching { alive.rig.gallery.shutdown() }
    runCatching { alive.rig.close() }
    alive.device.close()
    world.devices.remove(alive.device)
    // La rinascita (avvio, prima lettura) non e' cio' che il banco prova: i guasti tornano appena il processo nuovo e' su.
    val faults = world.backend.faults
    world.backend.faults = null
    try {
      val reborn = device(world, dir, platform)
      reborn.graph.start()
      reborn.graph.awaitSetup { it is SetupState.Ready }
      return open(world, reborn, TestNas.ADMIN_USER)
    } finally {
      world.backend.faults = faults
    }
  }

  /** Un passo del lavoro, come lo fanno i lavori dell'app: le operazioni della 4B, il giro degli album, l'ora del NAS che va avanti. */
  private suspend fun drive(world: World, alive: Alive) {
    runCatching { alive.rig.nas.resumeOperations(30.seconds) }
    runCatching { alive.albums.syncRound(30.seconds) }
    runCatching { alive.albums.shareRound(30.seconds) }
    val waiting = runCatching { alive.live.shared.diary.openOps().flatMap { alive.live.shared.diary.items(it.id) } }.getOrDefault(emptyList())
    if (waiting.isEmpty() || waiting.all { it.state == ShareItemState.ENTRY_STAGED || it.state.terminal }) {
      world.time.advance(PublishRules.MIN_HEAD_INTERVAL_MS + 1_000)
    }
  }

  private fun dbName(world: World, account: String) = "banco-p13-${world.round}-$account.db"

  private suspend fun idsOf(rig: BackupRig, count: Int): List<MediaId> {
    val view = withTimeout(Attese.TETTO) { rig.gallery.repository.view(LibraryFilter.ALL).first { it.size == count } }
    return (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }
  }

  private fun allFiles(backend: InMemorySmbBackend, share: String, dir: String = ""): List<String> =
    backend.children(share, dir).flatMap { name ->
      val path = if (dir.isEmpty()) name else "$dir/$name"
      if (backend.bytes(share, path) != null) listOf(path) else allFiles(backend, share, path)
    }

  private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private companion object {
    /** I giri: ognuno e' l'app intera due volte (due famiglie di dispositivi), con le morti e le rinascite. */
    const val ROUNDS = 12
    const val BASE_SEED = 20261027L
    const val FAULT_PROBABILITY = 0.03
    const val DAY = 24L * 60 * 60 * 1000

    /** I guasti cadono sulle operazioni sui file, non sul login (un login rifiutato ferma il NAS per sicurezza: e' un'altra prova). */
    val FILE_OPS: Set<SimOp> = setOf(
      SimOp.OPEN_READ, SimOp.READ, SimOp.CREATE, SimOp.WRITE, SimOp.FLUSH, SimOp.LENGTH, SimOp.RENAME, SimOp.DELETE_ON_CLOSE, SimOp.MKDIR,
      SimOp.OPEN_DIRECTORY, SimOp.LIST_ENTRY, SimOp.DELETE, SimOp.STAT, SimOp.OPEN_CHANGE, SimOp.COPY_CHUNK, SimOp.RESUME_KEY, SimOp.OPEN_WRITE,
      SimOp.SET_END_OF_FILE,
    )
  }
}
