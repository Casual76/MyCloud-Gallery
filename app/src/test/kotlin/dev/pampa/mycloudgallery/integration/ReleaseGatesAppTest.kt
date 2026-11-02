package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.ai.RulesModelShelfGate
import dev.pampa.mycloudgallery.albums.AppAlbumWriteGate
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteBlock
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.index.publish.IndexWriteRules
import dev.pampa.mycloudgallery.core.nasops.contract.GateStatus
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.release.contract.AppVersion
import dev.pampa.mycloudgallery.core.release.contract.BlockLevel
import dev.pampa.mycloudgallery.core.release.contract.ManifestLimits
import dev.pampa.mycloudgallery.core.release.contract.OfferResult
import dev.pampa.mycloudgallery.core.release.contract.ReleaseChannel
import dev.pampa.mycloudgallery.core.release.contract.ReleaseManifest
import dev.pampa.mycloudgallery.core.release.contract.ReleaseMessage
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStatus
import dev.pampa.mycloudgallery.core.release.contract.VersionBlock
import dev.pampa.mycloudgallery.core.release.state.NasFirstReleaseClock
import dev.pampa.mycloudgallery.core.release.state.ReleaseStates
import dev.pampa.mycloudgallery.core.release.testing.ReleaseTestSigner
import dev.pampa.mycloudgallery.core.release.contract.ReleaseKeyIds
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.faces.AppFacesWriteGate
import dev.pampa.mycloudgallery.release.NasManifestSource
import dev.pampa.mycloudgallery.release.ReleaseGraph
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 10 (P13, "Fatto quando" del riquadro P13; docs/fase10-progetto.md §1.4 "ReleaseGraph", §2.8, E-REL5, E-REL6): il manifest firmato **dal NAS ai cancelli**, sul grafo
 * vero. Un manifest firmato (chiavi generate nel test, mai K1, K2 o KT vere) messo nell'area comune del NAS finto, letto con la sessione del dispositivo e offerto allo stato
 * vero di core-release: con un blocco `WRITES` per questa versione si fermano **backup, indice, album, modelli, dati personali e operazioni sui file** (e i file in arrivo
 * del backup); con un blocco `ALL` in piu' **non si apre la sessione** (nessuna operazione arriva al NAS) e nessun lavoro a tappe parte. Prima del manifest, gli stessi
 * cancelli sono aperti (il controllo che la prova non passi a vuoto).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class ReleaseGatesAppTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val devices = mutableListOf<TestDevice>()
  private val rigs = mutableListOf<BackupRig>()

  @Before
  fun setUp() {
    WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
  }

  @After
  fun tearDown() {
    rigs.forEach { runBlocking { it.gallery.shutdown() } }
    devices.forEach { it.close() }
  }

  @Test
  fun unBloccoWritesFermaOgniScritturaEAllNonApreLaSessione() = runBlocking<Unit> {
    val signer = ReleaseTestSigner()
    val backend = TestNas.backend(time)
    val root = File(temp.root, "telefono")
    val version = AppVersion(VERSION_CODE, "1.0.0", ReleaseChannel.STABLE)
    val nasNow = { Instant.ofEpochMilli(time.now()) }
    val stateDir = File(root, "release")
    val release = ReleaseGraph(
      dir = stateDir,
      version = version,
      state = ReleaseStates.create(ReleaseStates.fileStore(stateDir), version, NasFirstReleaseClock(nasNow) { nasNow() }, signer.trust()),
    )
    val phone = TestDevice(app, root, TestPlatform(time, backend), release = release).also { devices += it }
    release.load()
    phone.graph.start()
    phone.graph.foundFamily()
    val rig = BackupRig(app, phone, TestNas.ADMIN_USER).also { rigs += it }
    rig.open()
    val share = TestNas.ADMIN_USER
    val flags = phone.graph.nasInputs.flags
    val album = AppAlbumWriteGate(flags, null, { TestNas.ADMIN_USER }, { rig.nas.writeGate })
    val faces = AppFacesWriteGate(flags, null, { TestNas.ADMIN_USER }, { rig.nas.writeGate })
    val personal = IndexWriteRules(flags, rig.nas.writeGate)
    val models = RulesModelShelfGate { IndexWriteRules(flags, rig.nas.writeGate) }

    // Prima del manifest: tutto aperto (la prova non passa a vuoto).
    assertThat(release.status.value).isEqualTo(ReleaseStatus.Normal)
    assertThat(rig.nas.writeGate.blockFor(share)).isNull()
    assertThat(rig.nas.opsGate.statusOf(share)).isNotEqualTo(GateStatus.WRITES_STOPPED)
    assertThat(rig.backup.gate.stateOf(share)).isNotEqualTo(BackupGateState.WRITES_STOPPED)
    assertThat(album.blockFor(share)).isNull()
    assertThat(faces.blockFor(share)).isNull()
    assertThat(personal.blockFor(share)).isNull()
    assertThat(models.blockFor(share)).isNull()

    // 1. Un blocco WRITES per questa versione, firmato e messo nell'area comune: la lettura del giro della sincronizzazione lo applica.
    backend.put(TestNas.COMMON, ManifestLimits.NAS_PATH, signer.seal(manifest(seq = 1, BlockLevel.WRITES), ReleaseKeyIds.K1))
    val source = NasManifestSource(phone.graph.session) { TestNas.COMMON }
    assertThat(release.readIfDue(source)).isEqualTo(OfferResult.APPLIED)
    assertThat(release.status.value).isEqualTo(ReleaseStatus.ReadOnly(ReleaseMessage.SECURITY))
    assertWithMessage("indice").that(rig.nas.writeGate.blockFor(share)).isEqualTo(WriteBlock.EMERGENCY_STOP)
    assertWithMessage("operazioni sui file").that(rig.nas.opsGate.statusOf(share)).isEqualTo(GateStatus.WRITES_STOPPED)
    assertWithMessage("backup").that(rig.backup.gate.stateOf(share)).isEqualTo(BackupGateState.WRITES_STOPPED)
    assertWithMessage("album").that(album.blockFor(share)).isEqualTo(AlbumWriteBlock.EMERGENCY_STOP)
    assertWithMessage("volti").that(faces.blockFor(share)).isEqualTo(FacesWriteBlock.EMERGENCY_STOP)
    assertWithMessage("dati personali").that(personal.blockFor(share)).isEqualTo(WriteBlock.EMERGENCY_STOP)
    assertWithMessage("modelli sullo scaffale").that(models.blockFor(share)).isEqualTo(WriteBlock.EMERGENCY_STOP.name)
    // La lettura si fa al piu' ogni 24 ore: subito dopo non si rilegge.
    assertThat(release.readIfDue(source)).isNull()
    // In sola lettura la sessione resta (si legge, non si scrive).
    assertThat(listCommon(phone).isSuccess).isTrue()

    // 2. Un blocco ALL (seq piu' alto): nessuna sessione, nessuna operazione verso il NAS, nessun lavoro a tappe.
    backend.put(TestNas.COMMON, ManifestLimits.NAS_PATH, signer.seal(manifest(seq = 2, BlockLevel.ALL), ReleaseKeyIds.K1))
    assertThat(release.readIfDue(source, force = true)).isEqualTo(OfferResult.APPLIED)
    assertThat(release.status.value).isEqualTo(ReleaseStatus.Stopped(ReleaseMessage.SECURITY))
    val operations = AtomicInteger()
    backend.beforeOp = { _, _ -> operations.incrementAndGet() }
    val refused = listCommon(phone)
    assertWithMessage("una lettura con la versione fermata").that(refused.isFailure).isTrue()
    assertThat(release.readIfDue(source, force = true)).isNull()
    assertWithMessage("operazioni arrivate al NAS con la versione fermata").that(operations.get()).isEqualTo(0)
    val decision = phone.graph.stageRules().decide(WorkRequest.Background(BackgroundActivity.INDEX_SYNC), running = false)
    assertThat(decision).isInstanceOf(RuleDecision.Stop::class.java)
    assertWithMessage("indice con la versione fermata").that(rig.nas.writeGate.blockFor(share)).isEqualTo(WriteBlock.EMERGENCY_STOP)
    assertWithMessage("backup con la versione fermata").that(rig.backup.gate.stateOf(share)).isEqualTo(BackupGateState.WRITES_STOPPED)
  }

  /** Una lettura qualunque dell'area comune con la sessione del dispositivo (passa dall'apertura della sessione). */
  private suspend fun listCommon(device: TestDevice): Result<Any> = withContext(Dispatchers.IO) {
    runCatching { device.graph.session.fileSystem(TestNas.COMMON).list(SharePath.parse(".mycloudgallery"), 10) }
  }

  /** Un manifest con un blocco [level] che comprende [VERSION_CODE], valido 30 giorni dall'ora del NAS. */
  private fun manifest(seq: Long, level: BlockLevel): ReleaseManifest {
    val issued = Instant.ofEpochMilli(time.now()).minusSeconds(60)
    return ReleaseManifest(
      schema = ManifestLimits.SCHEMA,
      app = ManifestLimits.APP_ID,
      seq = seq,
      issued = issued,
      expires = issued.plus(Duration.ofDays(30)),
      minVersionCode = null,
      blocks = listOf(VersionBlock(1, VERSION_CODE + 10, level, ReleaseMessage.SECURITY)),
      models = emptyList(),
    )
  }

  private companion object {
    const val VERSION_CODE = 7
  }
}
