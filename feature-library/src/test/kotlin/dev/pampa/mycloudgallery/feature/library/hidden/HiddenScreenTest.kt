package dev.pampa.mycloudgallery.feature.library.hidden

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.security.lock.AuthAvailability
import dev.pampa.mycloudgallery.core.security.lock.AuthResult
import dev.pampa.mycloudgallery.core.security.lock.SystemCredentialAuthenticator
import dev.pampa.mycloudgallery.feature.library.collections.ComposeScreenTest
import dev.pampa.mycloudgallery.feature.library.collections.FakeAuthenticator
import dev.pampa.mycloudgallery.feature.library.collections.RecordingLoader
import dev.pampa.mycloudgallery.feature.library.collections.TestClock
import dev.pampa.mycloudgallery.feature.library.collections.idsOf
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeGridEnvironment
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeThumbnailLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test

/**
 * La pagina dei Nascosti (D17, E-H1, E-H3, E-S5): credenziale a ogni ingresso con un minuto di tolleranza, finestra
 * protetta (`FLAG_SECURE`) per tutto il tempo in cui e' in composizione, elementi letti solo dopo lo sblocco, viewer
 * aperto da qui con `secure = true`.
 */
class HiddenScreenTest : ComposeScreenTest() {

  private val world = FakeCollectionsWorld()
  private val clock = TestClock()
  private val gate = HiddenAccessGate(clock, graceMillis = 60_000L)
  private val auth = FakeAuthenticator()
  private val loader = RecordingLoader(fakeThumbnailLoader())
  private val environment = fakeGridEnvironment(loader = loader)
  private val scope = CoroutineScope(Dispatchers.Unconfined)
  private val controller = HiddenController(gate, world.hiddenLibrary, world.actions, scope)
  private val requests = mutableListOf<ViewerRequest>()
  private var recentsAsked = 0

  @After
  fun stop() {
    scope.cancel()
  }

  private fun showHidden(authenticator: SystemCredentialAuthenticator? = auth) = show {
    HiddenHost(
      controller = controller,
      environment = environment,
      keepRecentsHidden = { recentsAsked++; true },
      onOpenViewer = { requests += it },
      authenticator = authenticator,
      onBack = {},
    )
  }

  @Test
  fun entrandoSiChiedeLaCredenzialeESiLeggeSoloDopo() {
    auth.hold = CompletableDeferred()

    showHidden()

    // La schermata di sistema e' aperta: la finestra e' gia' protetta e non e' stato letto niente.
    assertThat(auth.calls).isEqualTo(1)
    assertThat(windowIsSecure()).isTrue()
    assertThat(world.hiddenLibrary.reads).isEqualTo(0)
    assertThat(loader.seen).isEmpty()
    composeRule.onNodeWithText("Sblocco in corso").assertExists()

    auth.hold!!.complete(Unit)
    composeRule.waitForIdle()

    assertThat(world.hiddenLibrary.reads).isEqualTo(1)
    composeRule.onAllNodes(anyCell).fetchSemanticsNodes().also { assertThat(it).isNotEmpty() }
    assertThat(loader.seen).isNotEmpty()
    assertThat(world.hiddenIds).containsAtLeastElementsIn(loader.seen)
  }

  @Test
  fun lAutoSbloccoParteUnaVoltaSola() {
    auth.result = AuthResult.Cancelled

    showHidden()
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(2_000)

    assertThat(auth.calls).isEqualTo(1)
  }

  @Test
  fun unRifiutoDiceIlMotivoENonMostraNienteEIlPulsanteRiprova() {
    auth.result = AuthResult.Cancelled

    showHidden()

    composeRule.onNodeWithText("Hai annullato lo sblocco. I Nascosti restano chiusi.").assertExists()
    assertThat(world.hiddenLibrary.reads).isEqualTo(0)
    composeRule.onAllNodes(anyCell).assertCountEquals(0)

    auth.result = AuthResult.Success
    composeRule.onNodeWithText("Sblocca").performClick()
    composeRule.waitForIdle()

    assertThat(auth.calls).isEqualTo(2)
    composeRule.onAllNodes(anyCell).fetchSemanticsNodes().also { assertThat(it).isNotEmpty() }
  }

  @Test
  fun troppiTentativiLoDiceEResta_chiuso() {
    auth.result = AuthResult.LockedOut(permanent = false)

    showHidden()

    composeRule.onNodeWithText("Troppi tentativi", substring = true).assertExists()
    assertThat(world.hiddenLibrary.reads).isEqualTo(0)
  }

  @Test
  fun senzaBloccoSchermoNonSiApreEDiceCosaFare() {
    auth.availability = AuthAvailability.NO_SCREEN_LOCK

    showHidden()

    composeRule.onNodeWithText("Serve un blocco schermo").assertExists()
    composeRule.onNodeWithText("Apri le impostazioni di sicurezza").assertExists()
    assertThat(auth.calls).isEqualTo(0)
    assertThat(world.hiddenLibrary.reads).isEqualTo(0)
  }

  @Test
  fun senzaUnaActivityPerLaSchermataDiSistemaRestaChiuso() {
    showHidden(authenticator = null)

    composeRule.onNodeWithText("Lo sblocco non è disponibile adesso. Riprova fra poco.").assertExists()
    assertThat(world.hiddenLibrary.reads).isEqualTo(0)
    assertThat(windowIsSecure()).isTrue()
  }

  @Test
  fun unTocco_apreIlViewerSicuro() {
    showHidden()

    visibleCell().performClick()

    assertThat(requests).hasSize(1)
    val request = requests.single()
    // E-S5: il viewer aperto dai Nascosti vale con SecureScreen. Gli elementi sono solo i nascosti.
    assertThat(request.secure).isTrue()
    assertThat(idsOf(request.items.view.value)).containsExactlyElementsIn(world.hiddenIds)
    assertThat(request.items.view.value.viewIndexOf(request.start)).isAtLeast(0)
  }

  @Test
  fun uscendoDallaPaginaLaFinestraNonEPiuProtettaEINascostiSonoDimenticati() {
    var visible by mutableStateOf(true)
    show {
      if (visible) {
        HiddenHost(controller, environment, { recentsAsked++; false }, { requests += it }, auth)
      }
    }
    assertThat(windowIsSecure()).isTrue()
    assertThat(controller.state.value).isInstanceOf(HiddenUiState.Content::class.java)

    composeRule.runOnUiThread { visible = false }
    composeRule.waitForIdle()

    assertThat(windowIsSecure()).isFalse()
    assertThat(controller.state.value).isNotInstanceOf(HiddenUiState.Content::class.java)
    // I recenti tornano come erano: all'uscita si chiede se devono restare oscurati (blocco dell'app attivo).
    assertThat(recentsAsked).isEqualTo(1)
  }

  @Test
  fun rientrandoEntroUnMinutoNonSiRichiedeLaCredenziale_dopoUnMinutoSi() {
    var visible by mutableStateOf(true)
    show { if (visible) HiddenHost(controller, environment, { false }, { requests += it }, auth) }
    assertThat(auth.calls).isEqualTo(1)

    composeRule.runOnUiThread { visible = false }
    composeRule.waitForIdle()
    clock.now += 30_000
    composeRule.runOnUiThread { visible = true }
    composeRule.waitForIdle()

    assertThat(auth.calls).isEqualTo(1)
    composeRule.onAllNodes(anyCell).fetchSemanticsNodes().also { assertThat(it).isNotEmpty() }

    composeRule.runOnUiThread { visible = false }
    composeRule.waitForIdle()
    clock.now += 61_000
    val readsBefore = world.hiddenLibrary.reads
    composeRule.runOnUiThread { visible = true }
    composeRule.waitForIdle()

    assertThat(auth.calls).isEqualTo(2)
    assertThat(readsBefore).isEqualTo(2)
  }

  @Test
  fun senzaElementiNascostiDicePerche() {
    val emptyWorld = FakeCollectionsWorld(initiallyHidden = emptyList())
    val emptyController = HiddenController(HiddenAccessGate(clock, 60_000L), emptyWorld.hiddenLibrary, emptyWorld.actions, scope)
    show { HiddenHost(emptyController, environment, { false }, {}, auth) }

    composeRule.onNodeWithText("Nessun elemento nascosto").assertExists()
    composeRule.onAllNodesWithText("Nascondere vale solo dentro MyCloud Gallery", substring = true).fetchSemanticsNodes()
      .also { assertThat(it).isNotEmpty() }
  }

  @Test
  fun dichiaraCheNascondereVaSoloInApp() {
    showHidden()

    composeRule.onNodeWithText("Galleria di Samsung", substring = true).assertExists()
  }
}
