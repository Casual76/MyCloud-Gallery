package dev.pampa.mycloudgallery.feature.library.trash

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.contract.RecentlyDeletedSource
import dev.pampa.mycloudgallery.core.mediastore.contract.TrashedItem
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeRecentlyDeletedSource
import dev.pampa.mycloudgallery.core.security.lock.AuthAvailability
import dev.pampa.mycloudgallery.core.security.lock.AuthResult
import dev.pampa.mycloudgallery.core.security.lock.SystemCredentialAuthenticator
import dev.pampa.mycloudgallery.feature.library.collections.ComposeScreenTest
import dev.pampa.mycloudgallery.feature.library.collections.FakeAuthenticator
import dev.pampa.mycloudgallery.feature.library.collections.RecordingLoader
import dev.pampa.mycloudgallery.feature.library.collections.TestClock
import dev.pampa.mycloudgallery.feature.library.collections.testing.CountingRecentlyDeletedSource
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeThumbnailLoader
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeTrashedItems
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenAccessGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test

/**
 * Gli Eliminati di recente (D14, D17, E-H1, E-S5): dietro la credenziale dei Nascosti, con `FLAG_SECURE`; dopo lo sblocco
 * i giorni rimasti su ogni cella, i nascosti col segno, la frase che lo distingue dal cestino di Samsung Gallery e
 * "Ripristina" che apre il dialogo di sistema.
 */
class RecentlyDeletedScreenTest : ComposeScreenTest() {

  private val now = 1_800_000_000_000L
  private val items = fakeTrashedItems(now, count = 8)
  private val intents = FakePhoneMediaIntents(canTrash = true)
  private val loader = RecordingLoader(fakeThumbnailLoader())
  private val scope = CoroutineScope(Dispatchers.Unconfined)
  private val clock = TestClock()
  private val gate = HiddenAccessGate(clock, graceMillis = 60_000L)
  private val auth = FakeAuthenticator()

  @After
  fun stop() {
    scope.cancel()
  }

  private fun controller(
    source: RecentlyDeletedSource = FakeRecentlyDeletedSource(items),
    mask: TrashHiddenMask = TrashHiddenMask { emptySet() },
    system: FakePhoneMediaIntents = intents,
  ) = RecentlyDeletedController(gate, source, mask, system, scope) { now }

  private fun showTrash(c: RecentlyDeletedController, authenticator: SystemCredentialAuthenticator? = auth) = show {
    RecentlyDeletedHost(c, loader, keepRecentsHidden = { false }, authenticator = authenticator, onBack = {})
  }

  // --- Dietro la credenziale -------------------------------------------------------------------------------------

  @Test
  fun entrandoSiChiedeLaCredenzialeELaFinestraEProtetta() {
    val counting = CountingRecentlyDeletedSource(FakeRecentlyDeletedSource(items))
    auth.hold = CompletableDeferred()

    showTrash(controller(source = counting))

    // La schermata di sistema e' aperta: finestra protetta, niente letto, nessuna miniatura chiesta.
    assertThat(auth.calls).isEqualTo(1)
    assertThat(windowIsSecure()).isTrue()
    assertThat(counting.reads).isEqualTo(0)
    assertThat(loader.seen).isEmpty()
    composeRule.onNodeWithText("Sblocco in corso").assertExists()

    auth.hold!!.complete(Unit)
    composeRule.waitForIdle()

    assertThat(counting.reads).isEqualTo(1)
    composeRule.onNodeWithContentDescription("Foto eliminata, restano 29 giorni").assertExists()
  }

  @Test
  fun unRifiutoMostraIlMotivoENonLeggeNiente() {
    val counting = CountingRecentlyDeletedSource(FakeRecentlyDeletedSource(items))
    auth.result = AuthResult.Cancelled

    showTrash(controller(source = counting))

    composeRule.onNodeWithText("Hai annullato lo sblocco. Gli Eliminati di recente restano chiusi.").assertExists()
    assertThat(counting.reads).isEqualTo(0)
    assertThat(loader.seen).isEmpty()
    composeRule.onAllNodesWithContentDescription("Foto eliminata", substring = true).assertCountEquals(0)

    auth.result = AuthResult.Success
    composeRule.onNodeWithText("Sblocca").performClick()
    composeRule.waitForIdle()

    assertThat(auth.calls).isEqualTo(2)
    composeRule.onNodeWithContentDescription("Foto eliminata, restano 29 giorni").assertExists()
  }

  @Test
  fun senzaBloccoSchermoNonSiApre() {
    auth.availability = AuthAvailability.NO_SCREEN_LOCK
    val counting = CountingRecentlyDeletedSource(FakeRecentlyDeletedSource(items))

    showTrash(controller(source = counting))

    composeRule.onNodeWithText("Serve un blocco schermo").assertExists()
    assertThat(counting.reads).isEqualTo(0)
    assertThat(auth.calls).isEqualTo(0)
  }

  @Test
  fun uscendoLaFinestraNonEPiuProtettaEElencoEDimenticato() {
    var visible by mutableStateOf(true)
    val c = controller()
    show { if (visible) RecentlyDeletedHost(c, loader, { false }, auth, onBack = {}) }
    assertThat(windowIsSecure()).isTrue()
    assertThat(c.state.value).isInstanceOf(TrashUiState.Loaded::class.java)

    composeRule.runOnUiThread { visible = false }
    composeRule.waitForIdle()

    assertThat(windowIsSecure()).isFalse()
    assertThat(c.state.value).isNotInstanceOf(TrashUiState.Loaded::class.java)
  }

  @Test
  fun rientrandoEntroUnMinutoNonSiRichiedeLaCredenzialeDopoUnMinutoSi() {
    var visible by mutableStateOf(true)
    val c = controller()
    show { if (visible) RecentlyDeletedHost(c, loader, { false }, auth, onBack = {}) }
    assertThat(auth.calls).isEqualTo(1)

    composeRule.runOnUiThread { visible = false }
    composeRule.waitForIdle()
    clock.now += 30_000
    composeRule.runOnUiThread { visible = true }
    composeRule.waitForIdle()
    assertThat(auth.calls).isEqualTo(1)
    composeRule.onNodeWithContentDescription("Foto eliminata, restano 29 giorni").assertExists()

    composeRule.runOnUiThread { visible = false }
    composeRule.waitForIdle()
    clock.now += 61_000
    composeRule.runOnUiThread { visible = true }
    composeRule.waitForIdle()

    assertThat(auth.calls).isEqualTo(2)
  }

  // --- Contenuto -------------------------------------------------------------------------------------------------

  @Test
  fun dicheNonEIlCestinoDiSamsungGallery() {
    showTrash(controller())

    composeRule.onNodeWithText("Il cestino di Android").assertExists()
    composeRule.onNodeWithText("Non è il cestino della Galleria di Samsung", substring = true).assertExists()
    composeRule.onNodeWithText("30 giorni", substring = true).assertExists()
  }

  @Test
  fun ogniCellaDiceQuantiGiorniRestano() {
    showTrash(controller())

    // Il piu' recente e' stato eliminato un'ora fa: 29 giorni. Il quarto e' un video: 26.
    composeRule.onNodeWithContentDescription("Foto eliminata, restano 29 giorni").assertExists()
    composeRule.onNodeWithContentDescription("Video eliminato, restano 26 giorni").assertExists()
    composeRule.onNodeWithText("29 giorni").assertExists()
    composeRule.onNodeWithText("26 giorni").assertExists()
  }

  @Test
  fun leMiniatureSiChiedonoPerLeCelleVisibili() {
    showTrash(controller())

    // Come la griglia della Libreria (D5): le celle visibili e la finestra chiedono, mai tutte le celle del cestino.
    val asked = loader.seen
    assertThat(asked).containsAtLeastElementsIn(items.take(3).map { RecentlyDeletedController.trashEntryId(it.ref) })
  }

  @Test
  fun iNascostiEliminatiSiVedonoDopoLoSbloccoColSegno() {
    val hidden = items[2]
    showTrash(controller(mask = TrashHiddenMask { setOf(hidden.ref) }))

    // Il terzo (27 giorni) c'e', nascosto: si legge nella descrizione, ha il segno, e la nota lo spiega.
    composeRule.onNodeWithContentDescription("Foto eliminata, restano 27 giorni, nascosta").assertExists()
    composeRule.onAllNodesWithText("Nascosto").assertCountEquals(1)
    composeRule.onNodeWithText("restano nascosti anche dopo il ripristino", substring = true).assertExists()
    // Gli altri non hanno il segno.
    composeRule.onNodeWithContentDescription("Foto eliminata, restano 29 giorni").assertExists()
    assertThat(loader.seen).contains(RecentlyDeletedController.trashEntryId(hidden.ref))
  }

  @Test
  fun senzaNascostiNonCeLaNotaSulSegno() {
    showTrash(controller())

    composeRule.onAllNodesWithText("restano nascosti anche dopo il ripristino", substring = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("Nascosto").assertCountEquals(0)
  }

  // --- Ripristino ------------------------------------------------------------------------------------------------

  @Test
  fun scegliereEPremereRipristinaApreIlDialogoDiSistemaConISoliScelti() {
    showTrash(controller())

    composeRule.onNodeWithContentDescription("Foto eliminata, restano 29 giorni").performClick()
    composeRule.onNodeWithContentDescription("Foto eliminata, restano 28 giorni").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Ripristina 2 elementi").performClick()

    assertThat(intents.trashRequests).hasSize(1)
    val (refs, trash) = intents.trashRequests.single()
    assertThat(refs).containsExactly(items[0].ref, items[1].ref)
    // `trash = false` e' il ripristino; l'app non offre mai di eliminare per sempre.
    assertThat(trash).isFalse()
    composeRule.onAllNodesWithText("Elimina per sempre", substring = true).assertCountEquals(0)
  }

  @Test
  fun siRipristinaAncheUnNascostoEIlRipristinoNonCambiaNiente() {
    val hidden = items[1]
    showTrash(controller(mask = TrashHiddenMask { setOf(hidden.ref) }))

    composeRule.onNodeWithContentDescription("Foto eliminata, restano 28 giorni, nascosta").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Ripristina 1 elemento").performClick()

    // Solo la richiesta di sistema: l'app non toglie il nascosto, quindi tornato non compare in Libreria.
    assertThat(intents.trashRequests.single().first).containsExactly(hidden.ref)
    assertThat(intents.trashRequests.single().second).isFalse()
  }

  @Test
  fun senzaSelezioneNonCeIlPulsanteRipristina() {
    showTrash(controller())

    composeRule.onAllNodesWithText("Ripristina", substring = true).assertCountEquals(0)
  }

  @Test
  fun selezionaTuttoPoiChiudeLaSelezione() {
    showTrash(controller())

    composeRule.onNodeWithContentDescription("Seleziona tutto").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Ripristina ${items.size} elementi").assertExists()
    composeRule.onNodeWithContentDescription("Chiudi").performClick()
    composeRule.waitForIdle()

    composeRule.onAllNodesWithText("Ripristina", substring = true).assertCountEquals(0)
  }

  @Test
  fun senzaIlRiconoscimentoDeiNascostiNonSiMostraNiente() {
    showTrash(controller(mask = TrashHiddenMask { throw IllegalStateException("catalogo chiuso") }))

    composeRule.onNodeWithText("Non riesco a leggere il cestino").assertExists()
    composeRule.onAllNodesWithContentDescription("Foto eliminata", substring = true).assertCountEquals(0)
    assertThat(loader.seen).isEmpty()
  }

  @Test
  fun unCestinoVuotoDicePerche() {
    showTrash(controller(source = FakeRecentlyDeletedSource(emptyList())))

    composeRule.onNodeWithText("Niente eliminato di recente").assertExists()
    // La frase che lo distingue da Samsung c'e' anche da vuoto: e' proprio allora che serve.
    composeRule.onNodeWithText("Non è il cestino della Galleria di Samsung", substring = true).assertExists()
  }

  @Test
  fun suAndroid10DicheNonEDisponibile() {
    showTrash(controller(system = FakePhoneMediaIntents(canTrash = false)))

    composeRule.onNodeWithText("Non disponibile su questo Android").assertExists()
    // Non c'e' niente da proteggere: nessuna credenziale.
    assertThat(auth.calls).isEqualTo(0)
  }

  @Test
  fun dopoUnErroreRiprovaRilegge() {
    var broken = true
    val source = object : RecentlyDeletedSource {
      override suspend fun list(): List<TrashedItem> = if (broken) throw IllegalStateException("MediaProvider") else items
    }
    showTrash(controller(source = source))
    composeRule.onNodeWithText("Non riesco a leggere il cestino").assertExists()

    broken = false
    composeRule.onNodeWithText("Riprova").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithContentDescription("Foto eliminata, restano 29 giorni").assertExists()
  }
}
