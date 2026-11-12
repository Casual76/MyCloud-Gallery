package dev.pampa.mycloudgallery.feature.library.trash

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeRecentlyDeletedSource
import dev.pampa.mycloudgallery.feature.library.collections.ComposeScreenTest
import dev.pampa.mycloudgallery.feature.library.collections.FakeAuthenticator
import dev.pampa.mycloudgallery.feature.library.collections.TestClock
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeThumbnailLoader
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeTrashedItems
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenAccessGate
import dev.pampa.mycloudgallery.feature.library.trash.testing.FakeNasTrash
import dev.pampa.mycloudgallery.feature.library.trash.testing.sampleNasRows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test

/**
 * La pagina degli Eliminati di recente con i due cestini (Fase 7, P8, D26): le due parti con i giorni rimasti, la selezione, "Ripristina" e
 * "Elimina subito" a doppio tocco (solo per le voci proprie). Compose su JVM (Robolectric), con un host dei fogli come alla radice dell'app.
 */
class RecentlyDeletedNasScreenTest : ComposeScreenTest() {

  private val now = 1_800_000_000_000L
  private val items = fakeTrashedItems(now, count = 4)
  private val loader = fakeThumbnailLoader()
  private val scope = CoroutineScope(Dispatchers.Unconfined)
  private val gate = HiddenAccessGate(TestClock(), graceMillis = 60_000L)
  private val auth = FakeAuthenticator()
  private val nas = FakeNasTrash(sampleNasRows())

  /** L'orologio monotono dei tocchi del foglio: lo muove il test. */
  private var tapClock = 100_000L

  @After
  fun stop() {
    scope.cancel()
  }

  private fun controller(port: NasTrashPort = nas) = RecentlyDeletedController(
    gate, FakeRecentlyDeletedSource(items), TrashHiddenMask { emptySet() }, FakePhoneMediaIntents(canTrash = true), scope, port,
  ) { now }

  @Composable
  private fun Stage(content: @Composable () -> Unit) {
    val host = rememberFluidGlassModalHostState()
    val backdrop = rememberGlassBackdrop()
    CompositionLocalProvider(LocalFluidGlassModalHostState provides host) {
      Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().glassBackdropSource(backdrop)) { content() }
        FluidGlassModalHost(state = host, backdrop = backdrop)
      }
    }
  }

  private fun showPage(c: RecentlyDeletedController, dark: Boolean = false, fontScale: Float? = null) = show(dark = dark, fontScale = fontScale) {
    Stage {
      RecentlyDeletedHost(c, loader, keepRecentsHidden = { false }, authenticator = auth, onBack = {}, monotonicMs = { tapClock })
    }
  }

  /** Porta in vista la riga (la griglia compone solo cio' che si vede) e la tocca. */
  private fun tapRow(name: String) {
    scrollTo(name)
    composeRule.onNodeWithContentDescription(name, substring = true).performClick()
  }

  private fun scrollTo(name: String) {
    composeRule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(name, substring = true))
    composeRule.waitForIdle()
  }

  @Test
  fun leDuePartiHannoLIntestazioneEIGiorniRimasti() {
    showPage(controller())
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Dal telefono", substring = true).assertExists()
    composeRule.onNodeWithText("Dal NAS", substring = true).assertExists()
    // Le voci del NAS dicono i giorni rimasti, la cartella e chi l'ha eliminata.
    composeRule.onNodeWithContentDescription("Cena da Marco.jpg", substring = true).assertExists()
    composeRule.onNodeWithContentDescription("Restano 2 giorni", substring = true).assertExists()
    composeRule.onNodeWithContentDescription("Eliminato da Tablet di casa", substring = true).assertExists()
    // Quella senza record valido lo dice e non finge un nome.
    composeRule.onNodeWithContentDescription("Senza nome", substring = true).assertExists()
    // CU-3 (fase I): niente «Senza record» su ogni riga; il segno del problema e una riga della sezione con il perche' dietro la «i».
    composeRule.onNodeWithContentDescription("Da controllare dal PC", substring = true).assertExists()
    // La voce piu' lontana dalla scadenza e' in fondo: la lista scorre fin li'.
    scrollTo("IMG_20261012_184501.jpg")
    composeRule.onNodeWithContentDescription("IMG_20261012_184501.jpg", substring = true).assertExists()
    composeRule.onNodeWithContentDescription("Restano 29 giorni", substring = true).assertExists()
  }

  @Test
  fun senzaNasLaPaginaNonHaIntestazioni() {
    showPage(controller(NoNasTrash))
    composeRule.waitForIdle()

    composeRule.onAllNodesWithText("Dal NAS", substring = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("Dal telefono", substring = true).assertCountEquals(0)
  }

  @Test
  fun eliminaSubitoComparisoloPerLeVociProprie() {
    showPage(controller())
    composeRule.waitForIdle()

    composeRule.onAllNodesWithContentDescription("Elimina subito").assertCountEquals(0)
    tapRow("IMG_20261012_184501.jpg")
    composeRule.waitForIdle()
    composeRule.onAllNodesWithContentDescription("Elimina subito").assertCountEquals(1)

    // Una voce di un altro dispositivo spegne "Elimina subito" e la pagina dice perche'.
    tapRow("Cena da Marco.jpg")
    composeRule.waitForIdle()
    composeRule.onAllNodesWithContentDescription("Elimina subito").assertCountEquals(0)
    composeRule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Elimina subito: solo le tue", substring = true))
    composeRule.onNodeWithContentDescription("Elimina subito: solo le tue", substring = true).assertExists()
  }

  @Test
  fun eliminaSubitoVuoleDueTocchiDistinti() {
    val c = controller()
    showPage(c)
    composeRule.waitForIdle()
    tapRow("IMG_20261012_184501.jpg")
    composeRule.waitForIdle()

    composeRule.onNodeWithContentDescription("Elimina subito").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Eliminare subito 1 elemento?", substring = true).assertExists()

    // Primo tocco: arriva il pannello con l'avviso sul backup esterno.
    tapClock += 5_000
    composeRule.onNodeWithText("Elimina subito").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Ultima conferma").assertExists()
    assertThat(nas.purges).isEmpty()

    // Un secondo tocco a pochi millisecondi (un doppio tocco): non conta.
    tapClock += 100
    composeRule.onNodeWithText("Sì, elimina per sempre").performClick()
    composeRule.waitForIdle()
    assertThat(nas.purges).isEmpty()
    composeRule.onNodeWithText("Ultima conferma").assertExists()

    // Un secondo gesto vero: parte con la prova dei due tocchi.
    tapClock += 1_500
    composeRule.onNodeWithText("Sì, elimina per sempre").performClick()
    composeRule.waitForIdle()
    assertThat(nas.purges).containsExactly(setOf("mio/a1") to 2)
    composeRule.onNodeWithText("Eliminazione avviata", substring = true).assertExists()
    composeRule.onAllNodesWithContentDescription("IMG_20261012_184501.jpg", substring = true).assertCountEquals(0)
  }

  @Test
  fun annullareNelPannelloFinaleNonEliminaENessunToccoSopravvive() {
    showPage(controller())
    composeRule.waitForIdle()
    tapRow("IMG_20261012_184501.jpg")
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Elimina subito").performClick()
    composeRule.waitForIdle()
    tapClock += 5_000
    composeRule.onNodeWithText("Elimina subito").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Ultima conferma").assertExists()

    composeRule.onNodeWithText("Annulla").performClick()
    composeRule.waitForIdle()
    assertThat(nas.purges).isEmpty()

    // Riaperto, il foglio ricomincia dal primo pannello: il tocco di prima non conta piu'.
    composeRule.onNodeWithContentDescription("Elimina subito").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Eliminare subito 1 elemento?", substring = true).assertExists()
    composeRule.onAllNodesWithText("Ultima conferma").assertCountEquals(0)
  }

  @Test
  fun ripristinaUnaVoceDelNas() {
    showPage(controller())
    composeRule.waitForIdle()
    tapRow("Cena da Marco.jpg")
    composeRule.waitForIdle()

    composeRule.onNode(hasText("Ripristina", substring = true)).performClick()
    composeRule.waitForIdle()

    assertThat(nas.restores).containsExactly(setOf("tab/b1") to 1)
    composeRule.onNodeWithText("Ripristino avviato", substring = true).assertExists()
  }

  @Test
  fun unRifiutoDelNasSiDice() {
    nas.restoreOutcome = NasTrashOutcome.BLOCKED
    showPage(controller())
    composeRule.waitForIdle()
    tapRow("Cena da Marco.jpg")
    composeRule.waitForIdle()

    composeRule.onNode(hasText("Ripristina", substring = true)).performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Le scritture sul NAS sono ferme", substring = true).assertExists()
  }

  @Test
  fun ilNasCheNonRispondeELaSolaLetturaSiDicono() {
    val c = controller()
    showPage(c)
    composeRule.waitForIdle()

    nas.show(NasTrashFeed.Offline(sampleNasRows().filter { it.deletableNow }))
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Il NAS non risponde", substring = true).assertExists()

    nas.show(NasTrashFeed.Ready(sampleNasRows(), writable = false))
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Sola lettura", substring = true).assertExists()
  }

  @Test
  fun conIDueCestiniVuotiLaPaginaDiceCheNonCEDiNiente() {
    val empty = RecentlyDeletedController(
      gate, FakeRecentlyDeletedSource(emptyList()), TrashHiddenMask { emptySet() }, FakePhoneMediaIntents(canTrash = true), scope,
      FakeNasTrash(emptyList()),
    ) { now }
    showPage(empty)
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Niente eliminato di recente").assertExists()
  }
}
