package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La rete di `TestDevice.close()` (secondo giro dei test stabili, 31/10): il lavoro sfuggito a un dispositivo fa cadere **la classe che lo lascia**, non quella che
 * apre il primo `runTest` dopo. Qui si prova che la rete prende davvero i due modi in cui un dispositivo lascia lavoro, e che non protesta quando e' tutto in ordine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TestDeviceSafetyNetTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val devices = mutableListOf<TestDevice>()

  private fun device(name: String): TestDevice =
    TestDevice(app, File(temp.root, name), TestPlatform(time, TestNas.backend(time))).also { devices += it }

  @After
  fun tearDown() = devices.forEach { runCatching { it.close() } }

  @Test
  fun undispositivoInOrdineSiChiudeSenzaProteste() = runBlocking<Unit> {
    val phone = device("telefono")
    phone.graph.start()
    phone.graph.foundFamily()
    phone.close()
  }

  @Test
  fun unaCoroutineNataFuoriDalloScopeDelDispositivoMaSuoiThreadFaCadereLaChiusura() {
    val phone = device("telefono")
    // Uno scope "al volo": il contesto del dispositivo (anche il suo dispatcher) ma un `Job` nuovo, che lo scope del dispositivo non conosce e non aspetta.
    val orphan = CoroutineScope(phone.scope.coroutineContext + SupervisorJob()).launch { awaitCancellation() }
    val failure = runCatching { phone.close() }.exceptionOrNull()
    assertThat(failure).isInstanceOf(AssertionError::class.java)
    assertThat(failure).hasMessageThat().contains("non e' figlio")
    // La chiusura l'ha annullata e aspettata: non resta niente che possa leggere un file che sparisce.
    assertThat(orphan.isActive).isFalse()
  }

  @Test
  fun unaEccezionePersaDaUnaCoroutineFaCadereLaChiusuraDelDispositivoVivo() {
    val phone = device("telefono")
    // Come la perderebbe un `launch` senza gestore: arriva ai gestori registrati come servizio (non al `runTest` che apre dopo, per questo non passa dal `launch`).
    StrayExceptions().handleException(EmptyCoroutineContext, IOException("PROVA DELLA RETE, non e' un guasto: lettura di un file sparito"))
    val failure = runCatching { phone.close() }.exceptionOrNull()
    assertThat(failure).isInstanceOf(AssertionError::class.java)
    assertThat(failure).hasMessageThat().contains("eccezione non gestita")
    assertThat(failure).hasMessageThat().contains("PROVA DELLA RETE")
  }

  @Test
  fun unaEccezionePersaPrimaDellaNascitaDelDispositivoNonLoRiguarda() {
    StrayExceptions().handleException(EmptyCoroutineContext, IOException("PROVA DELLA RETE, non e' un guasto: di una classe di prima"))
    val phone = device("telefono")
    phone.close()
  }
}
