package dev.pampa.mycloudgallery.core.backup.versions

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.RunStop
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.upload.UploadControl
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.breaker.AuthBreakerRegistry
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.okOrNull
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOpsFactory
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasCredentialSource
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryBreakerStore
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.ManualMonotonicClock
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fase 5B, P8: il motore delle versioni sopra il **simulatore Samba** (`InMemorySmbBackend`) con la sessione di produzione e le **primitive
 * vere** del backup (`SmbNasBackupOps` → `SmbNasFileOps.renameVersion`, `openTemp`, `publishTemp`; la lettura vera `readAll`): E-E1 e E-E2
 * con la semantica di Samba (maiuscole, rinomina senza sostituire sullo stesso handle, `fileId` che resta), `SOURCE_CHANGED` con il file
 * cambiato dal PC fra la verifica e lo spostamento, e un guasto prima e dopo **ogni** richiesta SMB del lavoro, con la ripresa dal diario.
 */
class VersionSimulatorTest {

  @get:Rule val folder = TemporaryFolder()

  private val share = VersionWorld.PRIVATE_SHARE

  private class Sim(scope: CoroutineScope, val w: VersionWorld) {
    val clock = ManualMonotonicClock(10_000_000L)
    val backend = InMemorySmbBackend().apply {
      addUser(USER, PASSWORD)
      addShare(w.share, mapOf(USER to SimAccess.READ_WRITE))
    }
    val manager = SmbNasSessionManager(
      account = { NasAccount(NasAddress("192.168.178.58"), USER, "slot-prova", backend.serverGuid) },
      routeGate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, backend.binding("wifi-casa")) },
      credentials = FakeNasCredentialSource { CredentialLoad.Found(NasCredentials(USER, PASSWORD.toCharArray())) },
      breakers = AuthBreakerRegistry(InMemoryBreakerStore(), scope, wallClock = { 1_791_763_200_000L }),
      scope = scope,
      transport = backend,
      clock = clock,
      io = Dispatchers.Unconfined,
      deviceId = { w.deviceId },
      backoff = { },
      shareGuard = null,
    )
    val ops: NasBackupOps = NasBackupOpsFactory.create(manager, w.deviceId)
    val reader: NasMediaReader = manager.mediaReader()

    fun engine(ops: NasBackupOps = this.ops): NasVersionEngine = NasVersionEngine(
      deviceId = w.deviceId, share = w.share, ops = ops, reader = reader, journal = w.journal, catalog = w.catalog, phone = w.phone.port,
      staged = w.staged, gate = w.gate, clock = w.clock, tasks = w.tasks, config = w.config, zone = w.zone, sleep = { w.clock.advance(it) },
    )

    suspend fun stampOf(path: String): NasStamp = reader.stat(w.share, SharePath.parse(path), Purpose.BACKGROUND).okOrNull()!!.stamp

    fun sha(path: String): Sha256? = backend.bytes(w.share, path)?.let { Sha256.of(it) }

    /** Un media al sicuro sul simulatore: l'originale sul telefono e al solito percorso, verificato. */
    suspend fun seed(): VersionWorld.Seeded {
      val content = w.bytes(70_000)
      val sha = Sha256.of(content)
      w.knownContents += sha
      val (mediaId, original) = w.catalog.addMedia(size = content.size.toLong(), fullHash = sha)
      val ref = w.phone.add(100, NasContent.of(content), 1_000)
      w.catalog.putPhoneCopy(mediaId, original, ref, w.phone.stamp(100)!!)
      val path = SharePath.parse("Backup/S25 di prova/2026/10/IMG_1234.jpg")
      backend.put(w.share, path.value, content)
      w.catalog.putNasCopy(mediaId, original, w.share, path, stampOf(path.value), verifiedAt = w.clock.nasNow)
      return VersionWorld.Seeded(mediaId, original, 100, ref, path, content)
    }

    companion object {
      const val USER = "test"
      const val PASSWORD = "pw-di-prova-lunga"
    }
  }

  private fun TestScope.sim(): Sim = Sim(backgroundScope, VersionWorld(folder.newFolder()))

  private val archived = "Versioni/Backup/S25 di prova/2026/10/IMG_1234 (originale).jpg"

  @Test
  fun e_e1_ed_e_e2_sulSimulatoreSamba() = runTest {
    val s = sim()
    val seed = s.seed()
    val originalFileId = s.backend.fileId(share, seed.usualPath.value)
    s.w.editOnPhone(seed)
    val v1 = s.w.phone.sha256(seed.phoneId!!)!!
    val engine = s.engine()
    engine.planPhoneVersions(listOf(s.w.candidate(seed.mediaId, seed.phoneId)))
    repeat(4) { engine.run(1.hours) }
    assertThat(s.w.journal.all().single().state).isEqualTo(UploadState.DONE)
    assertThat(s.sha(seed.usualPath.value)).isEqualTo(v1)
    assertThat(s.sha(archived)).isEqualTo(Sha256.of(seed.content))
    assertThat(s.backend.fileId(share, archived)).isEqualTo(originalFileId)
    // Nessun temporaneo resta.
    assertThat(s.backend.children(share, ".mycloudgallery/tmp")).isEmpty()

    // E-E2: lo scambio con due rinomine sullo stesso handle, nessun byte caricato.
    s.w.revertOnPhone(seed)
    val swap = engine.planRealign(seed.mediaId)!!
    repeat(3) { engine.run(1.hours) }
    assertThat(s.w.journal.job(swap.id)!!.state).isEqualTo(UploadState.DONE)
    assertThat(s.sha(seed.usualPath.value)).isEqualTo(Sha256.of(seed.content))
    assertThat(s.backend.fileId(share, seed.usualPath.value)).isEqualTo(originalFileId)
    // v1 in Versioni/ con l'ora del NAS del simulatore nel nome.
    val names = s.backend.children(share, "Versioni/Backup/S25 di prova/2026/10")
    val v1Name = names.single { it.startsWith("IMG_1234 (versione del ") && it.endsWith(").jpg") }
    assertThat(s.sha("Versioni/Backup/S25 di prova/2026/10/$v1Name")).isEqualTo(v1)
  }

  @Test
  fun sourceChangedSulSimulatoreNienteSiSposta() = runTest {
    val s = sim()
    val seed = s.seed()
    s.w.editOnPhone(seed)
    val hooked = HookedOps(s.ops)
    val fromPc = s.w.bytes(60_000)
    hooked.beforeArchive = {
      hooked.beforeArchive = {}
      s.backend.modify(share, seed.usualPath.value, NasContent.of(fromPc))
    }
    val engine = s.engine(hooked)
    engine.planPhoneVersions(listOf(s.w.candidate(seed.mediaId, seed.phoneId!!)))
    val result = engine.run(1.hours)
    assertThat(result.held).isEqualTo(1)
    val job = s.w.journal.all().single()
    assertThat(job.hold).isEqualTo(VersionHold.SOURCE_CHANGED)
    assertThat(s.sha(seed.usualPath.value)).isEqualTo(Sha256.of(fromPc))
    assertThat(s.backend.exists(share, "Versioni")).isTrue() // la cartella si crea prima (mkdirs), ma vuota
    assertThat(s.backend.children(share, "Versioni/Backup/S25 di prova/2026/10")).isEmpty()
    assertThat(s.backend.exists(share, job.temp!!.value)).isTrue()
  }

  /** Un giro che si ferma dopo [limit] byte caricati (la fine di una tappa): il lavoro resta a meta' con il suo temporaneo. */
  private class StopAfter(private val limit: Long) : UploadControl {
    private var seen = 0L

    override suspend fun proceed(): Boolean = seen < limit

    override suspend fun traffic(bytes: Long) {
      seen += bytes
    }
  }

  @Test
  fun f1_unLavoroDalTelefonoConLaVersioneSuperataFinisceGoneEToglieIlSuoTemporaneo() = runTest {
    // F1 della prova della 5B (docs/prova-500.md §C6): il lavoro e' a meta' (temporaneo con il punto di ripresa), il MediaStore si ricostruisce e
    // la foto cambia ancora. La riga del lavoro non tornera': il lavoro toglie il suo temporaneo e finisce GONE, mai appeso.
    val s = sim()
    val seed = s.seed()
    val v1 = s.w.editOnPhone(seed)
    val engine = s.engine()
    engine.planPhoneVersions(listOf(s.w.candidate(seed.mediaId, seed.phoneId!!)))
    engine.run(1.hours, StopAfter(40_000))
    val half = s.w.journal.all().single()
    assertThat(half.state).isEqualTo(UploadState.UPLOADING)
    assertThat(half.resumeAt).isGreaterThan(0L)
    assertThat(s.backend.exists(share, half.temp!!.value)).isTrue()
    val fresh = 900L
    s.w.phone.rebuild("v2", mapOf(seed.phoneId to fresh))
    s.w.catalog.phoneRenumbered(seed.mediaId, seed.phoneId, s.w.phone.ref(fresh)!!)
    val bytes = s.w.bytes(seed.content.size + 2_000)
    s.w.knownContents += Sha256.of(bytes)
    s.w.phone.rewrite(fresh, NasContent.of(bytes), 999_999L)
    val v2 = s.w.catalog.phoneChanged(seed.mediaId, s.w.phone.ref(fresh)!!, s.w.phone.stamp(fresh)!!, bytes.size.toLong(), null)
    val again = s.engine()
    again.planPhoneVersions(listOf(s.w.candidate(seed.mediaId, fresh, v2)))
    repeat(4) { again.run(1.hours) }
    assertThat(s.w.journal.all().first { it.versionId == v1 }.state).isEqualTo(UploadState.GONE)
    assertThat(s.w.journal.all().first { it.versionId == v2 }.state).isEqualTo(UploadState.DONE)
    assertThat(s.sha(seed.usualPath.value)).isEqualTo(Sha256.of(bytes))
    assertThat(s.backend.children(share, ".mycloudgallery/tmp")).isEmpty()
  }

  @Test
  fun unGuastoPrimaEDopoOgniRichiestaSmbDelLavoro() = runTest {
    suspend fun prepare(s: Sim): Pair<NasVersionEngine, VersionWorld.Seeded> {
      val seed = s.seed()
      s.w.editOnPhone(seed)
      val engine = s.engine()
      engine.planPhoneVersions(listOf(s.w.candidate(seed.mediaId, seed.phoneId!!)))
      return engine to seed
    }
    val clean = sim()
    val (cleanEngine, _) = prepare(clean)
    val counter = FaultPlan()
    clean.backend.faults = counter
    cleanEngine.run(1.hours)
    clean.backend.faults = null
    val total = counter.operations
    assertWithMessage("richieste contate").that(total).isAtLeast(10)
    for (n in 1..total) {
      for (phase in FaultPhase.entries) {
        val s = sim()
        val (engine, seed) = prepare(s)
        val v1 = s.w.phone.sha256(seed.phoneId!!)!!
        val originalFileId = s.backend.fileId(share, seed.usualPath.value)
        s.backend.faults = FaultPlan().at(n, phase, SimFault.DropConnection)
        engine.run(1.hours)
        s.backend.faults = null
        // La ripresa con un processo nuovo.
        val again = s.engine()
        var last = again.run(1.hours)
        repeat(8) { if (s.w.journal.all().any { !it.state.terminal }) last = again.run(1.hours) }
        val label = "guasto alla richiesta $n/$total $phase"
        assertWithMessage("$label: finito (${last.stoppedBy})").that(s.w.journal.all().single().state).isEqualTo(UploadState.DONE)
        assertWithMessage("$label: la versione al solito percorso").that(s.sha(seed.usualPath.value)).isEqualTo(v1)
        assertWithMessage("$label: l'originale in Versioni/, lo stesso file").that(s.backend.fileId(share, archived)).isEqualTo(originalFileId)
        assertWithMessage("$label: l'originale intero").that(s.sha(archived)).isEqualTo(Sha256.of(seed.content))
        assertWithMessage("$label: nessun temporaneo").that(s.backend.children(share, ".mycloudgallery/tmp")).isEmpty()
        assertWithMessage("$label: nessun altro file").that(s.backend.children(share, "Versioni/Backup/S25 di prova/2026/10")).hasSize(1)
        assertThat(last.stoppedBy).isAnyOf(RunStop.COMPLETE, RunStop.NAS)
        assertThat(s.w.journal.all().single().kind).isEqualTo(UploadKind.VERSION)
      }
    }
  }
}
