package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.ai.AiStatusMemory
import dev.pampa.mycloudgallery.ai.AppIndexerControl
import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.AiWaitReason
import dev.pampa.mycloudgallery.core.ai.contract.ProbeResult
import dev.pampa.mycloudgallery.core.rules.ThermalStatus
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.run.NextStage
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import dev.pampa.mycloudgallery.core.rules.run.StageStats
import dev.pampa.mycloudgallery.core.rules.run.YieldReason
import java.io.File
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
 * IX-2 e IX-3 della verifica funzionale della Fase 6, sul montaggio vero (telefono, NAS finto, motori finti). Lo stato del calcolo viveva solo in
 * memoria e nasceva "Off": un'app riaperta dopo la notte diceva a un Indicizzatore "Questo dispositivo non analizza le foto"; un'attesa delle
 * regole prima di partire non passava mai dal motore. Ora la pagina parte dall'ultimo stato visto, anche scritto da un altro processo.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class AiIndexerPageTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private lateinit var rig: AiRig
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

  @Before
  fun setUp() {
    rig = AiRig(app, temp.root)
  }

  @After
  fun tearDown() {
    scope.cancel()
    rig.close()
  }

  private val stats = StageStats(batches = 0, bytes = 0, roundBytes = 0, elapsed = Duration.ZERO, resumed = false)

  @Test
  fun ix2_laPaginaPartedallUltimoStatoVistoAncheDaUnAltroProcesso() = runBlocking<Unit> {
    val test = rig.foundedDevice()
    // Il processo della notte ha lasciato scritto che il telefono era troppo caldo.
    File(test.context.noBackupFilesDir, AiStatusMemory.FILE).writeText("waiting HOT")

    val device = rig.mount(test, TestNas.ADMIN_USER)

    assertThat(device.stack.ai.status.value).isEqualTo(AiStatus.Waiting(AiWaitReason.HOT))
    val control = AppIndexerControl(device.stack, scope) {}
    assertThat(device.stack.ai.indexer.value).isTrue()
    assertThat(control.status.value).isEqualTo(AiStatus.Waiting(AiWaitReason.HOT))
  }

  @Test
  fun ix2_unaAttesaDelleRegoleCheNonPassaDalMotoreArrivaAllaPaginaESiRicorda() = runBlocking<Unit> {
    val device = rig.founder()
    val file = File(device.test.context.noBackupFilesDir, AiStatusMemory.FILE)

    device.stack.ai.noteStage(StageOutcome.Waiting(WaitReason.Thermal(ThermalStatus.SEVERE), emptyList(), stats))
    assertThat(device.stack.ai.status.value).isEqualTo(AiStatus.Waiting(AiWaitReason.HOT))
    assertThat(file.readText()).isEqualTo("waiting HOT")

    device.stack.ai.noteStage(StageOutcome.Waiting(WaitReason.Offline, emptyList(), stats))
    assertThat(device.stack.ai.status.value).isEqualTo(AiStatus.Waiting(AiWaitReason.NO_NETWORK))

    // Una tappa finita per tempo con altro da fare non e' un'attesa delle regole: "riprendo fra poco".
    device.stack.ai.noteStage(StageOutcome.Yielded(YieldReason.TIME_SLICE, NextStage.IMMEDIATELY, stats))
    assertThat(device.stack.ai.status.value).isEqualTo(AiStatus.Waiting(AiWaitReason.NEXT_STAGE))
  }

  @Test
  fun ix3_laMisuraDelDispositivoSiSalvaESiRitrovaNelProcessoDopo() = runBlocking<Unit> {
    val device = rig.founder()
    val probeFile = File(device.test.context.noBackupFilesDir, AppIndexerControl.PROBE_FILE)
    val first = AppIndexerControl(device.stack, scope, probeFile) {}
    assertThat(first.savedProbe()).isNull()

    first.saveProbe(ProbeResult(imagesPerSecond = 2.8, backend = AiBackend.GPU, coldStartMs = 3_600))

    val again = AppIndexerControl(device.stack, scope, probeFile) {}
    assertThat(again.savedProbe()).isEqualTo(ProbeResult(2.8, AiBackend.GPU, 3_600))
    // Un file rovinato non fa male: nessuna misura.
    probeFile.writeText("non sono numeri")
    assertThat(AppIndexerControl(device.stack, scope, probeFile) {}.savedProbe()).isNull()
  }

  @Test
  fun ix3_indicizzaOraChiedeUnLavoroAccelerato() = runBlocking<Unit> {
    val device = rig.founder()
    var requests = 0
    val control = AppIndexerControl(device.stack, scope) { requests++ }
    control.indexNow()
    assertThat(requests).isEqualTo(1)
  }
}
