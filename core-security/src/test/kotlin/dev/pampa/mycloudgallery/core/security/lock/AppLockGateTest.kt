package dev.pampa.mycloudgallery.core.security.lock

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.security.AppLockMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Il cancello: il contenuto c'e' solo con l'app sbloccata, lo sblocco parte da solo una volta, niente scorciatoie. */
@RunWith(RobolectricTestRunner::class)
class AppLockGateTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private class FakeClock(var now: Long = 0L) : MonotonicClock {
    override fun elapsedRealtimeMillis(): Long = now
  }

  private class FakeAuthenticator(var availability: AuthAvailability = AuthAvailability.AVAILABLE) : SystemCredentialAuthenticator {
    var calls = 0
    var pending = CompletableDeferred<AuthResult>()

    override fun availability(): AuthAvailability = availability

    override suspend fun authenticate(text: AuthPromptText): AuthResult {
      calls++
      return pending.await()
    }

    fun answer(result: AuthResult) {
      pending.complete(result)
      pending = CompletableDeferred()
    }
  }

  private val clock = FakeClock()
  private val controller = AppLockController(clock)
  private val authenticator = FakeAuthenticator()

  private fun enabled(timeout: AppLockTimeout = AppLockTimeout.IMMEDIATELY) =
    AppLockSettings(AppLockMode.SYSTEM_CREDENTIAL, timeout)

  private fun setGate(onLocked: (LockedScreenState) -> Unit = {}, content: @Composable () -> Unit = { BasicText("CONTENUTO", Modifier.testTag("content")) }) {
    rule.setContent {
      AppLockGate(
        controller = controller,
        authenticator = authenticator,
        promptText = AuthPromptText("Sblocca", "prova"),
        lockedContent = { state ->
          onLocked(state)
          Column {
            BasicText(
              "BLOCCATO availability=${state.availability} last=${state.lastResult} authenticating=${state.isAuthenticating}",
              Modifier.testTag("locked"),
            )
            BasicText("SBLOCCA", Modifier.testTag("unlock").clickable { state.onUnlock() })
            BasicText("IMPOSTAZIONI", Modifier.testTag("settings").clickable { state.onOpenSecuritySettings() })
          }
        },
        content = content,
      )
    }
  }

  private fun idle() = rule.waitForIdle()

  @Test
  fun finoAlleImpostazioni_nonSiDisegnaNiente() {
    setGate()
    idle()
    rule.onAllNodesWithTag("content").assertCountEquals(0)
    rule.onAllNodesWithTag("locked").assertCountEquals(0)
    assertThat(authenticator.calls).isEqualTo(0)
  }

  @Test
  fun bloccoSpento_mostraSubitoIlContenuto_eNonChiedeNulla() {
    controller.updateSettings(AppLockSettings(AppLockMode.OFF))
    setGate()
    idle()
    rule.onNodeWithTag("content").assertIsDisplayed()
    rule.onAllNodesWithTag("locked").assertCountEquals(0)
    assertThat(authenticator.calls).isEqualTo(0)
  }

  @Test
  fun bloccato_ilContenutoNonEInComposizione_eLoSbloccoParteDaSolo() {
    controller.updateSettings(enabled())
    setGate()
    idle()

    rule.onAllNodesWithTag("content").assertCountEquals(0)
    rule.onNodeWithTag("locked").assertIsDisplayed()
    assertThat(authenticator.calls).isEqualTo(1)
    assertThat(controller.isAuthenticating).isTrue()
  }

  @Test
  fun sbloccoRiuscito_mostraIlContenuto_eTogliLaSchermataDiBlocco() {
    controller.updateSettings(enabled())
    setGate()
    idle()

    authenticator.answer(AuthResult.Success)
    idle()

    rule.onNodeWithTag("content").assertIsDisplayed()
    rule.onAllNodesWithTag("locked").assertCountEquals(0)
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)
    assertThat(controller.isAuthenticating).isFalse()
  }

  @Test
  fun sbloccoAnnullato_restaBloccato_nonSiRipropone_eIlPulsanteRiprova() {
    controller.updateSettings(enabled())
    setGate()
    idle()

    authenticator.answer(AuthResult.Cancelled)
    idle()

    rule.onAllNodesWithTag("content").assertCountEquals(0)
    rule.onNodeWithTag("locked").assertTextContains("last=Cancelled", substring = true)
    assertThat(authenticator.calls).isEqualTo(1) // niente ciclo di richieste

    rule.onNodeWithTag("unlock").performClick()
    idle()
    assertThat(authenticator.calls).isEqualTo(2)
    authenticator.answer(AuthResult.Success)
    idle()
    rule.onNodeWithTag("content").assertIsDisplayed()
  }

  @Test
  fun troppiTentativi_ilSistemaBlocca_l_appRestaChiusaESenzaCancellazioni() {
    controller.updateSettings(enabled())
    setGate()
    idle()

    authenticator.answer(AuthResult.LockedOut(permanent = true))
    idle()

    rule.onAllNodesWithTag("content").assertCountEquals(0)
    rule.onNodeWithTag("locked").assertTextContains("LockedOut", substring = true)
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
  }

  @Test
  fun senzaBloccoSchermo_l_appRestaChiusa_enonSiChiedeNulla() {
    authenticator.availability = AuthAvailability.NO_SCREEN_LOCK
    controller.updateSettings(enabled())
    setGate()
    idle()

    rule.onAllNodesWithTag("content").assertCountEquals(0)
    rule.onNodeWithTag("locked").assertTextContains("availability=NO_SCREEN_LOCK", substring = true)
    assertThat(authenticator.calls).isEqualTo(0)
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
  }

  @Test
  fun ilPulsanteImpostazioni_apreLeImpostazioniDiSicurezzaDelTelefono() {
    authenticator.availability = AuthAvailability.NO_SCREEN_LOCK
    controller.updateSettings(enabled())
    setGate()
    idle()

    rule.onNodeWithTag("settings").performClick()
    idle()

    val started = shadowOf(rule.activity).nextStartedActivity
    assertThat(started.action).isEqualTo(android.provider.Settings.ACTION_SECURITY_SETTINGS)
  }

  @Test
  fun uscendoEntroIlTimeout_restaAperta_oltre_siRichiudeEChiedeDiNuovo() {
    controller.updateSettings(enabled(AppLockTimeout.AFTER_1_MINUTE))
    setGate()
    idle()
    authenticator.answer(AuthResult.Success)
    idle()
    rule.onNodeWithTag("content").assertIsDisplayed()

    // esce e torna dopo 30 s: ancora aperta
    rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
    clock.now += 30_000
    rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    idle()
    rule.onNodeWithTag("content").assertIsDisplayed()
    assertThat(authenticator.calls).isEqualTo(1)

    // esce e torna dopo 61 s: richiusa, e lo sblocco riparte da solo
    rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
    clock.now += 61_000
    rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    idle()
    rule.onAllNodesWithTag("content").assertCountEquals(0)
    rule.onNodeWithTag("locked").assertIsDisplayed()
    assertThat(authenticator.calls).isEqualTo(2)
  }

  @Test
  fun conSubito_ogniUscitaRichiude() {
    controller.updateSettings(enabled(AppLockTimeout.IMMEDIATELY))
    setGate()
    idle()
    authenticator.answer(AuthResult.Success)
    idle()

    rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
    rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    idle()

    rule.onAllNodesWithTag("content").assertCountEquals(0)
    rule.onNodeWithTag("locked").assertIsDisplayed()
  }

  @Test
  fun bloccaOra_richiudeIlContenuto() {
    controller.updateSettings(enabled(AppLockTimeout.AFTER_15_MINUTES))
    setGate()
    idle()
    authenticator.answer(AuthResult.Success)
    idle()
    rule.onNodeWithTag("content").assertIsDisplayed()

    rule.runOnUiThread { controller.lockNow() }
    idle()
    rule.onAllNodesWithTag("content").assertCountEquals(0)
    rule.onNodeWithTag("locked").assertIsDisplayed()
  }

  @Test
  fun lo_stato_di_rememberSaveable_sopravvive_a_blocco_e_sblocco() {
    controller.updateSettings(AppLockSettings(AppLockMode.OFF))
    setGate(content = {
      var taps by rememberSaveable { mutableIntStateOf(0) }
      BasicText("TOCCHI=$taps", Modifier.testTag("content").clickable { taps++ })
    })
    idle()
    repeat(3) { rule.onNodeWithTag("content").performClick() }
    rule.onNodeWithTag("content").assertTextContains("TOCCHI=3")

    // il blocco si accende e si blocca: il contenuto sparisce
    rule.runOnUiThread {
      controller.updateSettings(enabled(AppLockTimeout.AFTER_15_MINUTES))
      controller.lockNow()
    }
    idle()
    rule.onAllNodesWithTag("content").assertCountEquals(0)

    authenticator.answer(AuthResult.Success)
    idle()
    rule.onNodeWithTag("content").assertTextContains("TOCCHI=3")
  }

  @Test
  fun lasciandoLaComposizione_unoSbloccoInCorso_siAnnullaEIlControllerTornaLibero() {
    controller.updateSettings(enabled())
    var show by mutableStateOf(true)
    rule.setContent {
      if (show) {
        AppLockGate(
          controller = controller,
          authenticator = authenticator,
          promptText = AuthPromptText("t"),
          lockedContent = { BasicText("BLOCCATO", Modifier.testTag("locked")) },
          content = { BasicText("CONTENUTO", Modifier.testTag("content")) },
        )
      }
    }
    idle()
    assertThat(controller.isAuthenticating).isTrue()

    rule.runOnUiThread { show = false }
    idle()

    assertThat(controller.isAuthenticating).isFalse()
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
  }

  @Test
  fun follow_applicaLeImpostazioniCheArrivanoDalloStore() {
    val flow = MutableStateFlow(AppLockSettings(AppLockMode.OFF))
    val job = CoroutineScope(Dispatchers.Unconfined).launch { controller.follow(flow) }
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)
    flow.value = enabled()
    assertThat(controller.settings.value).isEqualTo(enabled())
    job.cancel()
  }
}
