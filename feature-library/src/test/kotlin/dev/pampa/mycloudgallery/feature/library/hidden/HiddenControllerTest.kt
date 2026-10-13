package dev.pampa.mycloudgallery.feature.library.hidden

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.testing.FakeLocalMediaActions
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.security.lock.AuthAvailability
import dev.pampa.mycloudgallery.core.security.lock.AuthPromptText
import dev.pampa.mycloudgallery.core.security.lock.AuthResult
import dev.pampa.mycloudgallery.feature.library.collections.FakeAuthenticator
import dev.pampa.mycloudgallery.feature.library.collections.TestClock
import dev.pampa.mycloudgallery.feature.library.collections.idsOf
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Quando si leggono i nascosti (E-H1, E-H3): **mai prima dello sblocco**, mai dopo l'uscita, mai con un esito vecchio.
 * Sul mondo finto con nascosti veri, che conta le letture.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HiddenControllerTest {

  private val world = FakeCollectionsWorld()
  private val clock = TestClock()
  private val gate = HiddenAccessGate(clock, graceMillis = 60_000L)
  private val auth = FakeAuthenticator()
  private val prompt = AuthPromptText("Sblocca", "prova")

  private fun TestScope.controller(library: HiddenLibrary = world.hiddenLibrary) =
    HiddenController(gate, library, world.actions, backgroundScope)

  private fun contentIds(state: HiddenUiState): List<MediaId> =
    idsOf((state as HiddenUiState.Content).items.view.value)

  @Test
  fun primaDelloSbloccoNonSiLeggeNiente() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()

    c.shown()

    assertThat(c.state.value).isEqualTo(HiddenUiState.Locked)
    assertThat(world.hiddenLibrary.reads).isEqualTo(0)
  }

  @Test
  fun conLaCredenzialeSiLeggonoSoloINascosti() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    c.shown()

    c.unlock(auth, prompt)

    assertThat(world.hiddenLibrary.reads).isEqualTo(1)
    assertThat(contentIds(c.state.value)).containsExactlyElementsIn(world.hiddenIds)
    // E nessuno dei visibili.
    assertThat(contentIds(c.state.value).toSet().intersect(world.visibleIds)).isEmpty()
  }

  @Test
  fun unRifiutoNonLeggeNiente() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    auth.result = AuthResult.Cancelled

    c.unlock(auth, prompt)

    assertThat(c.state.value).isEqualTo(HiddenUiState.Denied(HiddenDenial.CANCELLED))
    assertThat(world.hiddenLibrary.reads).isEqualTo(0)
  }

  @Test
  fun senzaBloccoSchermoNonSiLeggeNiente() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    auth.availability = AuthAvailability.NO_SCREEN_LOCK

    c.unlock(auth, prompt)

    assertThat(c.state.value).isEqualTo(HiddenUiState.Denied(HiddenDenial.NO_SCREEN_LOCK))
    assertThat(world.hiddenLibrary.reads).isEqualTo(0)
  }

  @Test
  fun senzaUnaActivityPerLaSchermataDiSistemaRestaChiusoENonLegge() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()

    c.unlockUnavailable()

    assertThat(c.state.value).isEqualTo(HiddenUiState.Denied(HiddenDenial.UNAVAILABLE))
    assertThat(world.hiddenLibrary.reads).isEqualTo(0)
  }

  @Test
  fun uscendoSiDimenticaTuttoERientrandoEntroUnMinutoSiRilegge() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    c.shown()
    c.unlock(auth, prompt)
    assertThat(c.state.value).isInstanceOf(HiddenUiState.Content::class.java)

    c.hidden()

    // Fuori dalla pagina il processo non tiene i nascosti: lo stato e' "da rileggere", non un contenuto.
    assertThat(c.state.value).isEqualTo(HiddenUiState.Loading)
    clock.now += 30_000
    c.shown()
    assertThat(auth.calls).isEqualTo(1)
    assertThat(world.hiddenLibrary.reads).isEqualTo(2)
    assertThat(contentIds(c.state.value)).containsExactlyElementsIn(world.hiddenIds)
  }

  @Test
  fun rientrandoDopoUnMinutoSiRichiedeLaCredenzialeENonSiLegge() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    c.shown()
    c.unlock(auth, prompt)
    c.hidden()

    clock.now += 60_000
    c.shown()

    assertThat(c.state.value).isEqualTo(HiddenUiState.Locked)
    assertThat(world.hiddenLibrary.reads).isEqualTo(1)
  }

  @Test
  fun unaRotazioneRientraSenzaCredenziale() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    c.shown()
    c.unlock(auth, prompt)

    // hidden() + shown() subito dopo (ricreazione dell'activity): resta aperto senza credenziale.
    c.hidden()
    c.shown()

    assertThat(auth.calls).isEqualTo(1)
    assertThat(c.state.value).isInstanceOf(HiddenUiState.Content::class.java)
  }

  @Test
  fun mostraTogliIlNascostoERilegge() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    c.shown()
    c.unlock(auth, prompt)
    val toShow = contentIds(c.state.value).take(2).toSet()
    val hiddenBefore = world.hiddenIds

    c.show(toShow)

    assertThat(world.hiddenIds).containsExactlyElementsIn(hiddenBefore - toShow)
    assertThat(contentIds(c.state.value).toSet()).containsExactlyElementsIn(hiddenBefore - toShow)
    // Tornano nella Libreria.
    val all = world.repository.view(LibraryFilter.ALL).value
    toShow.forEach { assertThat(all.viewIndexOf(it)).isAtLeast(0) }
  }

  @Test
  fun mostraDaChiusiNonFaNiente() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    val someHidden = world.hiddenIds.take(1).toSet()

    c.show(someHidden)

    assertThat(world.hiddenIds).containsAtLeastElementsIn(someHidden)
  }

  @Test
  fun unaLetturaFallitaDiceFalliteENonMostraNiente() = runTest(UnconfinedTestDispatcher()) {
    val failing = HiddenLibrary { throw IllegalStateException("database chiuso") }
    val c = controller(failing)
    c.shown()

    c.unlock(auth, prompt)

    assertThat(c.state.value).isEqualTo(HiddenUiState.Failed)
  }

  @Test
  fun unaLetturaChiudeInRitardoDopoLUscitaNonMostraNulla() = runTest(UnconfinedTestDispatcher()) {
    val slow = CompletableDeferred<ViewerItems>()
    val c = controller { slow.await() }
    c.shown()
    c.unlock(auth, prompt)
    assertThat(c.state.value).isEqualTo(HiddenUiState.Loading)

    c.hidden()
    slow.complete(world.hiddenLibrary.read())

    // La lettura e' arrivata dopo l'uscita: si butta.
    assertThat(c.state.value).isEqualTo(HiddenUiState.Loading)
  }

  @Test
  fun unaLetturaChiudeInRitardoDopoIlBloccoNonMostraNulla() = runTest(UnconfinedTestDispatcher()) {
    val slow = CompletableDeferred<ViewerItems>()
    val c = controller { slow.await() }
    c.shown()
    c.unlock(auth, prompt)

    gate.lock()
    slow.complete(world.hiddenLibrary.read())

    assertThat(c.state.value).isEqualTo(HiddenUiState.Locked)
  }

  @Test
  fun chiudereIlControllerNonLasciaNienteInMemoria() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    c.shown()
    c.unlock(auth, prompt)

    c.close()

    assertThat(c.state.value).isEqualTo(HiddenUiState.Loading)
  }

  @Test
  fun mostraConUnInsiemeVuotoEUnIngressoNonLeggeNienteDaChiusi() = runTest(UnconfinedTestDispatcher()) {
    val actions = FakeLocalMediaActions()
    val c = HiddenController(gate, world.hiddenLibrary, actions, backgroundScope)

    c.show(setOf())
    c.shown()

    assertThat(actions.hidden).isEmpty()
    assertThat(world.hiddenLibrary.reads).isEqualTo(0)
  }
}
