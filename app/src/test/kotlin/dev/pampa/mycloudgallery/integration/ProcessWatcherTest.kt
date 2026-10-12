package dev.pampa.mycloudgallery.integration

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.SyncReason
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.network.contract.VpnAutomationStatus
import dev.pampa.mycloudgallery.notify.AppNotifications
import dev.pampa.mycloudgallery.process.ProcessWatcher
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Il ciclo di vita del processo sul grafo vero (P9): primo piano e profilo per il motore delle regole e la VPN, controllo
 * del registro all'apertura (solo per un dispositivo configurato), controllo quotidiano pianificato, sessione NAS chiusa
 * quando l'app va in background, automazione della VPN agganciata al processo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ProcessWatcherTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val backend = TestNas.backend(time)
  private val device by lazy { TestDevice(app, File(temp.root, "telefono"), TestPlatform(time, backend)) }

  @After
  fun tearDown() = device.close()

  @Test
  fun unDispositivoNonConfiguratoNonControllaIlRegistroEResta_inBackground() = runBlocking {
    val daily = AtomicInteger(0)
    val watcher = ProcessWatcher(device.graph, AppNotifications(device.context)) { daily.incrementAndGet() }
    watcher.start(app)
    device.graph.awaitSetup { it == SetupState.NotConfigured }
    assertThat(device.graph.appContext.context.value.inForeground).isFalse()
    // L'automazione della VPN gira (spenta: il controllo non e' stato scelto).
    assertThat(device.graph.vpnAutomation.status.value).isEqualTo(VpnAutomationStatus.Off)

    val activity = Activity()
    watcher.onActivityStarted(activity)
    assertThat(device.graph.appContext.context.value.inForeground).isTrue()
    watcher.onActivityStopped(activity)
    assertThat(device.graph.appContext.context.value.inForeground).isFalse()
    assertThat(device.scheduler.soon).isEmpty()
    assertThat(daily.get()).isEqualTo(0)
  }

  @Test
  fun unDispositivoConfiguratoControllaAllAperturaEChiudeLaSessioneInBackground() = runBlocking {
    device.graph.start()
    device.graph.foundFamily()
    assertThat(device.graph.session.state.value).isInstanceOf(NasConnState.Ready::class.java)

    val daily = AtomicInteger(0)
    val watcher = ProcessWatcher(device.graph, AppNotifications(device.context)) { daily.incrementAndGet() }
    watcher.start(app)
    device.graph.appContext.context.await { true }
    val activity = Activity()
    watcher.onActivityStarted(activity)
    device.graph.awaitSetup { it is SetupState.Ready }
    awaitCondition { device.scheduler.soon.contains(SyncReason.APP_OPENED) }
    awaitCondition { daily.get() == 1 }

    watcher.onActivityStopped(activity)
    device.graph.session.state.await { it == NasConnState.Idle }
    assertThat(device.graph.appContext.context.value.inForeground).isFalse()
  }

  private suspend fun awaitCondition(check: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 10_000
    while (!check()) {
      check(System.currentTimeMillis() < deadline) { "condizione non raggiunta" }
      kotlinx.coroutines.delay(20)
    }
  }
}
