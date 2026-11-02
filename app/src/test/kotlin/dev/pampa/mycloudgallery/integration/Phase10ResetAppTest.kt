package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.noop.NoThumbnailSource
import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.release.contract.AppVersion
import dev.pampa.mycloudgallery.core.release.contract.ManifestOrigin
import dev.pampa.mycloudgallery.core.release.contract.OfferResult
import dev.pampa.mycloudgallery.core.release.contract.ReleaseChannel
import dev.pampa.mycloudgallery.core.release.contract.ReleaseDiag
import dev.pampa.mycloudgallery.core.release.contract.ReleaseMessage
import dev.pampa.mycloudgallery.core.release.contract.ReleaseState
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStatus
import dev.pampa.mycloudgallery.core.widget.contract.WidgetCell
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetRefresher
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetLock
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetMemories
import dev.pampa.mycloudgallery.core.widget.testing.FakeWidgetPrefs
import dev.pampa.mycloudgallery.core.widget.testing.InMemoryWidgetLedger
import dev.pampa.mycloudgallery.core.widget.testing.RecordingWidgetRefresher
import dev.pampa.mycloudgallery.feature.widget.host.WidgetPlaceNames
import dev.pampa.mycloudgallery.graph.GalleryStopper
import dev.pampa.mycloudgallery.release.ReleaseGraph
import dev.pampa.mycloudgallery.widget.LocalWidgetThumbs
import dev.pampa.mycloudgallery.widget.WidgetCounters
import dev.pampa.mycloudgallery.widget.WidgetGraph
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fase 10 (P13, D42; "Fatto quando" del riquadro P13: "azzeramento completo"): l'azzeramento del dispositivo comincia dai **widget** (vuoti sulla Home, "Configura MyCloud
 * Gallery", **prima** di cancellare qualunque cosa), chiude le **altre finestre**, toglie il **registro** del widget e lo **stato del manifest** (file e memoria: la prima
 * lettura dopo rimette un blocco), e la galleria si ferma solo dopo. I soli dati di un account (un altro account alla fine dell'onboarding) svuotano i widget ma non
 * toccano il manifest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class Phase10ResetAppTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val devices = mutableListOf<TestDevice>()

  @Before
  fun setUp() {
    WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
  }

  @After
  fun tearDown() = devices.forEach { it.close() }

  /** Uno stato del manifest che ricorda se e' stato tolto. */
  private class ClearableState : ReleaseState {
    val mutable = MutableStateFlow<ReleaseStatus>(ReleaseStatus.ReadOnly(ReleaseMessage.UPDATE))
    var cleared = 0

    override val status: StateFlow<ReleaseStatus> = mutable
    override val diag: StateFlow<ReleaseDiag> = MutableStateFlow(ReleaseDiag(3, null, mutable.value, 0, emptyMap()))

    override suspend fun load() = Unit

    override suspend fun offer(bytes: ByteArray, origin: ManifestOrigin): OfferResult = OfferResult.REJECTED

    override fun extraUrls(id: String, sha256: String): List<String> = emptyList()

    override suspend fun clear() {
      cleared++
      mutable.value = ReleaseStatus.Normal
    }
  }

  private class Rig(val device: TestDevice, val ledger: InMemoryWidgetLedger, val state: ClearableState, val order: MutableList<String>, val stateDir: File)

  private fun rig(): Rig {
    val root = File(temp.root, "telefono")
    val stateDir = File(File(root, "no_backup"), ReleaseGraph.DIR)
    val state = ClearableState()
    val release = ReleaseGraph(stateDir, AppVersion(3, "1.0.0", ReleaseChannel.STABLE), state)
    val device = TestDevice(app, root, TestPlatform(time, TestNas.backend(time)), release = release).also { devices += it }
    val order = mutableListOf<String>()
    val ledger = InMemoryWidgetLedger()
    val visibility = FakeVisibility()
    val widget = WidgetGraph.create(
      context = device.context,
      feed = dev.pampa.mycloudgallery.core.library.testing.FakeWidgetFeed(visibility, visibility),
      visible = visibility,
      mask = visibility,
      memories = FakeWidgetMemories(),
      thumbs = LocalWidgetThumbs({ null }, NoThumbnailSource),
      lock = FakeWidgetLock(),
      prefs = FakeWidgetPrefs(),
      places = WidgetPlaceNames { null },
      awaitShelf = {},
      stoppedByRelease = { false },
      counters = WidgetCounters(),
      ledger = ledger,
    )
    val recording = RecordingWidgetRefresher()
    widget.refresher = object : WidgetRefresher by recording {
      override suspend fun blankAll(reason: WidgetEmpty) {
        order += "widget vuoti (${reason.code}), registro con ${ledger.slots().size} widget"
      }
    }
    device.graph.widgetStopper = GalleryStopper {
      widget.stop()
      order += "registro con ${ledger.slots().size} widget"
    }
    device.graph.windowsCloser = { order += "altre finestre chiuse" }
    device.graph.galleryStopper = GalleryStopper { order += "galleria ferma" }
    return Rig(device, ledger, state, order, stateDir)
  }

  @Test
  fun lAzzeramentoCominciaDaiWidgetEToglieRegistroEManifest() = runBlocking<Unit> {
    val r = rig()
    r.ledger.record(WidgetPlan(WidgetSlot(4), WidgetSize.SMALL, WidgetMode.Photos, listOf(WidgetCell(0, MediaId(UUID(5, 5).toString()), MediaKind.PHOTO, 20260101)), 1))
    File(r.stateDir, ReleaseGraph.READ_MARK).apply { parentFile!!.mkdirs() }.writeText("1")

    assertThat(r.device.graph.eraser.eraseAll()).isTrue()

    assertThat(r.order.first()).isEqualTo("widget vuoti (${WidgetEmpty.NOT_CONFIGURED.code}), registro con 1 widget")
    assertThat(r.order).containsAtLeast("registro con 0 widget", "altre finestre chiuse", "galleria ferma").inOrder()
    assertThat(r.order.count { it.startsWith("widget vuoti") }).isEqualTo(1)
    assertThat(r.state.cleared).isEqualTo(1)
    assertThat(r.device.graph.release?.status?.value).isEqualTo(ReleaseStatus.Normal)
    assertThat(r.stateDir.exists()).isFalse()
  }

  @Test
  fun iSoliDatiDiUnAccountSvuotanoIWidgetMaNonToccanoIlManifest() = runBlocking<Unit> {
    val r = rig()
    assertThat((r.device.graph.eraser as dev.pampa.mycloudgallery.graph.AppLocalDataEraser).eraseAccountData(stopGallery = true)).isTrue()
    assertThat(r.order.first()).startsWith("widget vuoti")
    assertThat(r.order).doesNotContain("altre finestre chiuse")
    assertThat(r.state.cleared).isEqualTo(0)
  }
}
