package dev.pampa.mycloudgallery.feature.search

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * TS-2: la richiesta di "Trova simili" si consegna **una volta**. La scheda Cerca si compone solo quando e' selezionata: prima la richiesta restava nel
 * valore di chi la teneva e ogni rientro nella scheda rilanciava "Simili a" (il campo si svuotava, tornavano i simili), mentre una seconda richiesta sullo
 * stesso media non cambiava il valore e non faceva niente.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class FindSimilarEffectTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val media = MediaId("00000000-0000-4000-8000-000000000001")
  private val other = MediaId("00000000-0000-4000-8000-000000000002")

  /** Chi tiene la richiesta (come `AppNavGraph`) e la scheda che entra e esce dalla composizione (come `MainScaffold`). */
  private class Host {
    var request by mutableStateOf<List<MediaId>?>(null)
    var tabShown by mutableStateOf(true)
    val found = ArrayList<List<MediaId>>()
    var handled = 0
  }

  private fun compose(host: Host, clearsRequest: Boolean = true) {
    rule.setContent {
      if (host.tabShown) {
        FindSimilarEffect(
          request = host.request,
          onFind = { host.found += it },
          onHandled = {
            host.handled++
            if (clearsRequest) host.request = null
          },
        )
      }
    }
  }

  @Test
  fun laRichiestaSiConsegnaUnaVoltaEUnRientroNellaSchedaNonLaRipete() {
    val host = Host()
    compose(host)

    host.request = listOf(media)
    rule.waitForIdle()
    assertThat(host.found).containsExactly(listOf(media))
    assertThat(host.request).isNull()

    // L'utente cambia scheda e torna: Cerca non rilancia i simili.
    host.tabShown = false
    rule.waitForIdle()
    host.tabShown = true
    rule.waitForIdle()
    assertThat(host.found).containsExactly(listOf(media))
  }

  @Test
  fun unaSecondaRichiestaSulloStessoMediaRiparte() {
    val host = Host()
    compose(host)

    host.request = listOf(media)
    rule.waitForIdle()
    host.request = listOf(media)
    rule.waitForIdle()

    assertThat(host.found).containsExactly(listOf(media), listOf(media)).inOrder()
    assertThat(host.handled).isEqualTo(2)
  }

  @Test
  fun unaRichiestaChiestaMentreLaSchedaNonCEArrivaAlRientro() {
    val host = Host()
    compose(host)
    host.tabShown = false
    rule.waitForIdle()

    host.request = listOf(other)
    rule.waitForIdle()
    assertThat(host.found).isEmpty()

    host.tabShown = true
    rule.waitForIdle()
    assertThat(host.found).containsExactly(listOf(other))
    assertThat(host.request).isNull()
  }

  /** Senza l'azzeramento (il comportamento di prima) il rientro la ripeteva: il test lo mostra, per far vedere cosa evita la correzione. */
  @Test
  fun senzaAzzerareLaRichiestaUnRientroLaRipeteva() {
    val host = Host()
    compose(host, clearsRequest = false)

    host.request = listOf(media)
    rule.waitForIdle()
    host.tabShown = false
    rule.waitForIdle()
    host.tabShown = true
    rule.waitForIdle()

    assertThat(host.found).containsExactly(listOf(media), listOf(media)).inOrder()
  }
}
