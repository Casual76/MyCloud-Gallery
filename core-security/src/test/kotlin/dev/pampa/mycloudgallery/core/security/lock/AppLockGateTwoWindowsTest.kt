package dev.pampa.mycloudgallery.core.security.lock

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.security.AppLockMode
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * **Due finestre finte, un solo blocco** (Fase 10, P4, D17, C434, E-T7). Due `AppLockGate` nella stessa composizione, ognuno con il **suo** ciclo di vita (la
 * finestra A e la finestra B), sullo stesso controller: e' quello che fa `MainActivity` quando le finestre sono due (un gate alla radice di ciascuna).
 *
 * Provano due cose, ognuna con la sua prova:
 *  1. **Nessun contenuto con il blocco attivo**: finche' l'app e' bloccata il contenuto non e' in composizione in **nessuna** delle due finestre (assente, non
 *     solo coperto), qualunque cosa facciano i cicli di vita, il tempo, "Blocca ora" e gli sblocchi (sequenze a caso, invariante dopo ogni passo);
 *  2. **Per processo**: si blocca solo quando si ferma l'ultima finestra, e uno sblocco riuscito apre tutte e due.
 */
@RunWith(RobolectricTestRunner::class)
class AppLockGateTwoWindowsTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private class FakeClock(var now: Long = 0L) : MonotonicClock {
    override fun elapsedRealtimeMillis(): Long = now
  }

  /** Una finestra finta: il suo ciclo di vita lo decide il test, come quello di un'activity. */
  private class FakeWindow : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry

    fun move(state: Lifecycle.State) {
      registry.currentState = state
    }
  }

  private class FakeAuthenticator : SystemCredentialAuthenticator {
    var calls = 0
    var pending = CompletableDeferred<AuthResult>()

    override fun availability(): AuthAvailability = AuthAvailability.AVAILABLE

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
  private val windowA = FakeWindow()
  private val windowB = FakeWindow()
  private val authA = FakeAuthenticator()
  private val authB = FakeAuthenticator()

  private fun enabled(timeout: AppLockTimeout) = AppLockSettings(AppLockMode.SYSTEM_CREDENTIAL, timeout)

  @androidx.compose.runtime.Composable
  private fun Window(name: String, owner: FakeWindow, authenticator: FakeAuthenticator) {
    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
      AppLockGate(
        controller = controller,
        authenticator = authenticator,
        promptText = AuthPromptText("Sblocca", "prova"),
        lockedContent = { state ->
          Column {
            BasicText("BLOCCATA-$name authenticating=${state.isAuthenticating}", Modifier.testTag("locked-$name"))
          }
        },
        content = { BasicText("CONTENUTO-$name", Modifier.testTag("content-$name")) },
      )
    }
  }

  /** Le due finestre nella stessa composizione; ognuna parte nello stato di ciclo di vita indicato (di solito [Lifecycle.State.RESUMED]). */
  private fun show(a: Lifecycle.State = Lifecycle.State.RESUMED, b: Lifecycle.State = Lifecycle.State.RESUMED) {
    rule.runOnUiThread {
      windowA.move(a)
      windowB.move(b)
    }
    rule.setContent {
      Column {
        Window("A", windowA, authA)
        Window("B", windowB, authB)
      }
    }
    idle()
  }

  private fun idle() = rule.waitForIdle()

  private fun move(window: FakeWindow, state: Lifecycle.State) {
    rule.runOnUiThread { window.move(state) }
    idle()
  }

  private fun assertContent(a: Int, b: Int) {
    rule.onAllNodesWithTag("content-A").assertCountEquals(a)
    rule.onAllNodesWithTag("content-B").assertCountEquals(b)
  }

  private fun assertLocked(a: Int, b: Int) {
    rule.onAllNodesWithTag("locked-A").assertCountEquals(a)
    rule.onAllNodesWithTag("locked-B").assertCountEquals(b)
  }

  /** Chi sta chiedendo la credenziale la completa con [result]. */
  private fun answerAuthentication(result: AuthResult) {
    authA.answer(result)
    authB.answer(result)
    idle()
  }

  private fun unlockBoth() {
    answerAuthentication(AuthResult.Success)
    assertContent(1, 1)
  }

  // --- 1. nessun contenuto con il blocco attivo ----------------------------------------------------------------------

  @Test
  fun bloccato_ilContenutoNonEInComposizioneInNessunaDelleDueFinestre() {
    controller.updateSettings(enabled(AppLockTimeout.AFTER_1_MINUTE))
    show()

    assertContent(0, 0)
    assertLocked(1, 1)
    // Una sola richiesta di credenziale per tutte e due, e la schermata di blocco dell'altra finestra lo sa.
    assertThat(authA.calls + authB.calls).isEqualTo(1)
    assertThat(controller.isAuthenticating).isTrue()
    rule.onNodeWithTag("locked-A").assertTextContains("authenticating=true", substring = true)
    rule.onNodeWithTag("locked-B").assertTextContains("authenticating=true", substring = true)
  }

  @Test
  fun finoAlleImpostazioni_nessunaFinestraDisegnaNiente() {
    show()
    assertContent(0, 0)
    assertLocked(0, 0)
    assertThat(authA.calls + authB.calls).isEqualTo(0)
  }

  @Test
  fun unaFinestraNuovaConIlBloccoAttivo_nascePrivaDiContenuto() {
    controller.updateSettings(enabled(AppLockTimeout.AFTER_1_MINUTE))
    show(b = Lifecycle.State.INITIALIZED)
    unlockBoth()
    // A e' sbloccata e visibile: B nasce adesso (la seconda finestra) e vede lo stato del processo.
    move(windowB, Lifecycle.State.RESUMED)
    assertContent(1, 1)

    // Con il blocco riacceso a mano, ogni finestra si richiude.
    rule.runOnUiThread { controller.lockNow() }
    idle()
    assertContent(0, 0)
    assertLocked(1, 1)
  }

  @Test
  fun bloccaOra_richiudeTutteELeDueFinestre() {
    controller.updateSettings(enabled(AppLockTimeout.AFTER_15_MINUTES))
    show()
    unlockBoth()

    rule.runOnUiThread { controller.lockNow() }
    idle()
    assertContent(0, 0)
    assertLocked(1, 1)
  }

  @Test
  fun senzaBloccoSchermo_ilTelefonoNonPuoAutenticare_nessunaDelleDueApreIlContenuto() {
    val noLock = object : SystemCredentialAuthenticator {
      override fun availability(): AuthAvailability = AuthAvailability.NO_SCREEN_LOCK

      override suspend fun authenticate(text: AuthPromptText): AuthResult = AuthResult.Interrupted
    }
    controller.updateSettings(enabled(AppLockTimeout.AFTER_1_MINUTE))
    rule.runOnUiThread {
      windowA.move(Lifecycle.State.RESUMED)
      windowB.move(Lifecycle.State.RESUMED)
    }
    rule.setContent {
      Column {
        for (name in listOf("A", "B")) {
          CompositionLocalProvider(LocalLifecycleOwner provides if (name == "A") windowA else windowB) {
            AppLockGate(
              controller = controller,
              authenticator = noLock,
              lockedContent = { BasicText("BLOCCATA-$name", Modifier.testTag("locked-$name")) },
              content = { BasicText("CONTENUTO-$name", Modifier.testTag("content-$name")) },
            )
          }
        }
      }
    }
    idle()
    assertContent(0, 0)
    assertLocked(1, 1)
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
  }

  // --- 2. per processo -------------------------------------------------------------------------------------------------

  @Test
  fun uno_sbloccoRiuscitoInUnaFinestraApreTutteEDue_eNonSiChiedeAncora() {
    controller.updateSettings(enabled(AppLockTimeout.AFTER_1_MINUTE))
    show()
    unlockBoth()

    assertLocked(0, 0)
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)
    assertThat(authA.calls + authB.calls).isEqualTo(1)
  }

  @Test
  fun unaCredenzialeAnnullata_lasciaTutteEDueChiuse_epoiLaSiRiprova() {
    controller.updateSettings(enabled(AppLockTimeout.AFTER_1_MINUTE))
    show()

    answerAuthentication(AuthResult.Cancelled)
    assertContent(0, 0)
    assertLocked(1, 1)
    assertThat(controller.isAuthenticating).isFalse()
    rule.onNodeWithTag("locked-A").assertTextContains("authenticating=false", substring = true)
    rule.onNodeWithTag("locked-B").assertTextContains("authenticating=false", substring = true)
    // Il sistema non chiede da solo una seconda volta: nessun ciclo di richieste.
    assertThat(authA.calls + authB.calls).isEqualTo(1)
  }

  @Test
  fun unaFinestraCheSiFermaMentreL_altraResta_nonRichiude_neppureConSubito() {
    controller.updateSettings(enabled(AppLockTimeout.IMMEDIATELY))
    show()
    unlockBoth()

    move(windowA, Lifecycle.State.CREATED) // A va dietro
    move(windowA, Lifecycle.State.RESUMED) // e torna: B e' rimasta visibile per tutto il tempo
    assertContent(1, 1)
    assertThat(controller.startedWindows).isEqualTo(2)

    // E viceversa.
    move(windowB, Lifecycle.State.CREATED)
    clock.now += 3_600_000L
    move(windowB, Lifecycle.State.RESUMED)
    assertContent(1, 1)
  }

  @Test
  fun siBloccaSoloQuandoSiFermaL_Ultima_ETutteEDueTrovanoLaSchermataDiBlocco() {
    controller.updateSettings(enabled(AppLockTimeout.AFTER_1_MINUTE))
    show()
    unlockBoth()

    move(windowA, Lifecycle.State.CREATED)
    clock.now += 61_000 // piu' del tempo concesso, ma B e' ancora visibile
    assertContent(1, 1)

    move(windowB, Lifecycle.State.CREATED) // l'ultima: l'app esce adesso
    clock.now += 61_000
    move(windowA, Lifecycle.State.RESUMED) // torna una sola: il blocco e' del processo
    assertContent(0, 0)
    assertLocked(1, 1)
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
  }

  @Test
  fun ilTempoConcessoNonRichiudeSeSiRientraInTempo_conUnaFinestraSola() {
    controller.updateSettings(enabled(AppLockTimeout.AFTER_1_MINUTE))
    show()
    unlockBoth()

    move(windowA, Lifecycle.State.CREATED)
    move(windowB, Lifecycle.State.CREATED)
    clock.now += 30_000
    move(windowB, Lifecycle.State.RESUMED)
    assertContent(1, 1)
  }

  @Test
  fun unaFinestraChiusa_nonTieneAperta_L_app() {
    controller.updateSettings(enabled(AppLockTimeout.IMMEDIATELY))
    var showB by mutableStateOf(true)
    rule.runOnUiThread {
      windowA.move(Lifecycle.State.RESUMED)
      windowB.move(Lifecycle.State.RESUMED)
    }
    rule.setContent {
      Column {
        Window("A", windowA, authA)
        if (showB) Window("B", windowB, authB)
      }
    }
    idle()
    unlockBoth()

    // B si chiude (la sua composizione esce) mentre A resta visibile: niente uscita.
    rule.runOnUiThread { showB = false }
    idle()
    assertThat(controller.startedWindows).isEqualTo(1)
    assertThat(controller.state.value).isEqualTo(AppLockState.UNLOCKED)

    // Poi A si ferma: era l'ultima.
    move(windowA, Lifecycle.State.CREATED)
    move(windowA, Lifecycle.State.RESUMED)
    assertThat(controller.state.value).isEqualTo(AppLockState.LOCKED)
    rule.onAllNodesWithTag("content-A").assertCountEquals(0)
  }

  @Test
  fun lasciandoLaComposizioneConUnaFinestraAvviata_lAppEsceSoloQuandoNonNeRestanoAltre() {
    controller.updateSettings(enabled(AppLockTimeout.AFTER_1_MINUTE))
    var showA by mutableStateOf(true)
    var showB by mutableStateOf(true)
    rule.runOnUiThread {
      windowA.move(Lifecycle.State.RESUMED)
      windowB.move(Lifecycle.State.RESUMED)
    }
    rule.setContent {
      Column {
        if (showA) Window("A", windowA, authA)
        if (showB) Window("B", windowB, authB)
      }
    }
    idle()
    unlockBoth()

    rule.runOnUiThread { showA = false }
    idle()
    assertThat(controller.startedWindows).isEqualTo(1)
    clock.now += 61_000
    rule.runOnUiThread { showB = false }
    idle()
    // Nessuna finestra: l'app e' uscita adesso, il tempo concesso comincia da qui.
    assertThat(controller.startedWindows).isEqualTo(0)
    clock.now += 30_000
    assertThat(controller.isUnlockedNow()).isTrue()
    clock.now += 30_000
    assertThat(controller.isUnlockedNow()).isFalse()
  }

  // --- proprieta' ------------------------------------------------------------------------------------------------------------

  /**
   * Sequenze a caso (200 passi, tre semi): avvio e fermata di ognuna delle due finestre, il tempo, "Blocca ora", uno sblocco riuscito o annullato. Dopo
   * **ogni** passo: il contenuto di una finestra c'e' **se e solo se** l'app e' sbloccata; con il blocco attivo (o le impostazioni ancora da leggere) non c'e' in
   * nessuna delle due, e la schermata di blocco c'e' in tutte e due.
   */
  @Test
  fun proprieta_nessunContenutoConIlBloccoAttivo_inNessunaDelleDueFinestre_seme1() = assertInvariantOnRandomRun(7)

  @Test
  fun proprieta_nessunContenutoConIlBloccoAttivo_inNessunaDelleDueFinestre_seme2() = assertInvariantOnRandomRun(2026)

  @Test
  fun proprieta_nessunContenutoConIlBloccoAttivo_inNessunaDelleDueFinestre_seme3() = assertInvariantOnRandomRun(31102026)

  private fun assertInvariantOnRandomRun(seed: Int) {
    val random = Random(seed)
    val timeout = AppLockTimeout.entries[random.nextInt(AppLockTimeout.entries.size)]
    show()
    // Prima che le impostazioni siano lette: niente, in nessuna delle due.
    check(controller.state.value == AppLockState.LOADING)
    assertContent(0, 0)
    assertLocked(0, 0)

    rule.runOnUiThread { controller.updateSettings(enabled(timeout)) }
    idle()
    repeat(200) { step ->
      when (random.nextInt(7)) {
        0 -> toggle(windowA)
        1 -> toggle(windowB)
        2 -> clock.now += random.nextLong(0, 90_000)
        3 -> if (random.nextInt(6) == 0) {
          rule.runOnUiThread { controller.lockNow() }
          idle()
        }
        4 -> answerAuthentication(AuthResult.Success)
        5 -> answerAuthentication(AuthResult.Cancelled)
        else -> idle()
      }
      val state = controller.state.value
      check(state != AppLockState.LOADING) { "passo $step: stato in caricamento dopo le impostazioni" }
      val unlocked = if (state == AppLockState.UNLOCKED) 1 else 0
      val locked = if (state == AppLockState.LOCKED) 1 else 0
      assertContent(unlocked, unlocked)
      assertLocked(locked, locked)
    }
  }

  /** La finestra va dietro se era visibile, torna se era dietro. */
  private fun toggle(window: FakeWindow) {
    move(window, if (window.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) Lifecycle.State.CREATED else Lifecycle.State.RESUMED)
  }
}
