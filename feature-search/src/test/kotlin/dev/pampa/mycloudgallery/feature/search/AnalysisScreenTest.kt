package dev.pampa.mycloudgallery.feature.search

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import dev.pampa.mycloudgallery.feature.search.models.visibleNow
import dev.pampa.mycloudgallery.feature.search.models.AnalysisMediaKey
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisThumb
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.ui.fluid.FluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisFlow
import dev.pampa.mycloudgallery.feature.search.models.AnalysisHandlers
import dev.pampa.mycloudgallery.feature.search.models.AnalysisLaneTag
import dev.pampa.mycloudgallery.feature.search.models.AnalysisQuietTag
import dev.pampa.mycloudgallery.feature.search.models.AnalysisRestTag
import dev.pampa.mycloudgallery.feature.search.models.AnalysisScreen
import dev.pampa.mycloudgallery.feature.search.models.AnalysisShownTag
import dev.pampa.mycloudgallery.feature.search.models.AnalysisStatusTag
import dev.pampa.mycloudgallery.feature.search.models.CodeAction
import dev.pampa.mycloudgallery.feature.search.models.laneStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pacchetto X, grafica XU: la pagina "Analisi delle foto" — la testata sorella del Backup (anello, due parole, una riga, il tasto quando serve) e il
 * nastro che passa nel mirino (adesso, le finite con la spunta, le prossime); un media non mostrabile e' la casella neutra. A riposo il mirino tiene
 * l'icona del perche'. Il passo del nastro si calcola dalle anteprime che restano, e con il movimento ridotto la pagina non scorre.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, qualifiers = "it-w360dp-h800dp-xhdpi")
class AnalysisScreenTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private class Recording : AnalysisHandlers {
    var now = 0
    val codes = mutableListOf<CodeAction>()

    override fun onAnalyzeNow() {
      now++
    }

    override fun onCodeAction(action: CodeAction) {
      codes += action
    }
  }

  @Test
  fun inCorsoSiVedeIlNastro() {
    rule.setSearchContent {
      AnalysisScreen(AnalysisScenes.RUNNING, AnalysisScenes.flow(), AnalysisScenes.thumbnails, Recording(), onBack = {})
    }
    rule.onNodeWithTag(AnalysisStatusTag).assertIsDisplayed()
    // La testata: due parole e una riga a vista, la frase intera per TalkBack.
    rule.onNodeWithText("In corso", useUnmergedTree = true).assertIsDisplayed()
    // Giro 2 (XU-1): sotto un anello che dice gia' pronte su totale niente «restano» e niente minuti.
    assertThat(rule.onAllNodesWithText("Restano", substring = true, useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
    assertThat(rule.onAllNodesWithText("min", substring = true, useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
    rule.onAllNodesWithContentDescription("Sta analizzando le foto adesso", substring = true).onFirst().assertIsDisplayed()
    rule.onAllNodesWithText("82%", useUnmergedTree = true).onFirst().assertIsDisplayed()
    // Il nastro: dice che cosa guarda e quante ne passano; niente etichette da modulo.
    rule.onNodeWithTag(AnalysisLaneTag).assertIsDisplayed()
    rule.onAllNodesWithContentDescription("Adesso guarda una foto. 5 appena fatte. 4 in arrivo").onFirst().assertExists()
    assertThat(rule.onAllNodesWithText("Prossime", ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
    assertThat(rule.onAllNodesWithText("Appena fatte", ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
    // Le anteprime (quella di adesso, le finite, le prossime); la neutra non e' un'anteprima e ce n'e' una sola.
    assertThat(rule.onAllNodesWithTag(AnalysisShownTag, useUnmergedTree = true).fetchSemanticsNodes().size).isAtLeast(5)
    assertThat(rule.onAllNodesWithTag(AnalysisQuietTag, useUnmergedTree = true).fetchSemanticsNodes()).hasSize(1)
    // In corso non c'e' niente da far partire.
    assertThat(rule.onAllNodesWithText("Analizza ora").fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun inPausaDiceIlMotivoEFaPartire() {
    val handlers = Recording()
    rule.setSearchContent {
      AnalysisScreen(AnalysisScenes.PAUSED, AnalysisScenes.restFlow(), AnalysisScenes.thumbnails, handlers, onBack = {})
    }
    rule.onNodeWithText("In pausa", useUnmergedTree = true).assertIsDisplayed()
    rule.onNodeWithText("Telefono caldo", useUnmergedTree = true).assertIsDisplayed()
    rule.onAllNodesWithContentDescription("L’analisi è in pausa: telefono caldo", substring = true).onFirst().assertExists()
    // Nel mirino l'icona del perche', con le ultime analizzate ferme accanto.
    rule.onNodeWithTag(AnalysisRestTag, useUnmergedTree = true).assertIsDisplayed()
    rule.onAllNodesWithContentDescription("Le ultime 5 analizzate").onFirst().assertExists()
    rule.onNodeWithText("Analizza ora").performClick()
    assertThat(handlers.now).isEqualTo(1)
  }

  @Test
  fun chiNonAnalizzaNonHaTasti() {
    rule.setSearchContent {
      AnalysisScreen(LibraryAnalysisStatus.NotHere(ready = 10, total = 12), AnalysisFlow.EMPTY, AnalysisScenes.thumbnails, Recording(), onBack = {})
    }
    rule.onAllNodesWithContentDescription("le analizza un altro dispositivo", substring = true).onFirst().assertIsDisplayed()
    rule.onNodeWithText("Su un altro dispositivo", useUnmergedTree = true).assertIsDisplayed()
    assertThat(rule.onAllNodesWithText("Analizza ora").fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun ilModelloCheMancaHaIlSuoRimedio() {
    val handlers = Recording()
    rule.setSearchContent {
      AnalysisScreen(LibraryAnalysisStatus.ModelMissing, AnalysisFlow.EMPTY, AnalysisScenes.thumbnails, handlers, onBack = {})
    }
    rule.onNodeWithText("Serve un download", useUnmergedTree = true).assertIsDisplayed()
    rule.onNodeWithText("Scarica ora").performClick()
    assertThat(handlers.codes).containsExactly(CodeAction.DOWNLOAD_NOW)
  }

  @Test
  fun tuttoProntoNonHaTastiENonSiAnnuncia() {
    rule.setSearchContent {
      AnalysisScreen(LibraryAnalysisStatus.AllReady, AnalysisScenes.restFlow(), AnalysisScenes.thumbnails, Recording(), onBack = {})
    }
    rule.onNodeWithText("Tutto pronto", useUnmergedTree = true).assertIsDisplayed()
    rule.onAllNodesWithText("100%", useUnmergedTree = true).onFirst().assertIsDisplayed()
    rule.onNodeWithTag(AnalysisRestTag, useUnmergedTree = true).assertIsDisplayed()
    assertThat(rule.onAllNodesWithText("Analizza ora").fetchSemanticsNodes()).isEmpty()
  }

  /** Con il movimento ridotto la pagina si compone e resta ferma (nessuna molla del nastro da aspettare). */
  @Test
  fun conIlMovimentoRidottoIlNastroNonScorre() {
    rule.setSearchContent {
      CompositionLocalProvider(LocalFluidMotionPolicy provides FluidMotionPolicy(durationScale = 0f)) {
        AnalysisScreen(AnalysisScenes.RUNNING, AnalysisScenes.flow(), AnalysisScenes.thumbnails, Recording(), onBack = {})
      }
    }
    rule.waitForIdle()
    rule.onNodeWithTag(AnalysisLaneTag).assertIsDisplayed()
  }

  /** Il passo: di quanti posti scorre la corsia fra due fotografie del nastro (la prima anteprima che resta dice quanto). */
  @Test
  fun ilPassoDelNastroSiContaDalleAnteprimeCheRestano() {
    val before = AnalysisScenes.flow()
    // Un passo: la prossima entra nel mirino, quella di adesso diventa la prima finita.
    val one = AnalysisFlow(before.next[0], listOf(before.current!!) + before.recent, before.next.drop(1))
    assertThat(laneStep(before, one)).isEqualTo(1)
    // Tre passi in uno (il motore e' piu' veloce del passo della pagina).
    val three = AnalysisFlow(before.next[2], listOf(before.next[1], before.next[0], before.current!!) + before.recent, before.next.drop(3))
    assertThat(laneStep(before, three)).isEqualTo(3)
    // Dalla pausa: niente scorre, la corsia si ferma dov'e'.
    assertThat(laneStep(one, AnalysisFlow(null, one.recent, emptyList()))).isEqualTo(0)
    // Niente in comune: nessuno scorrimento (le anteprime cambiano sul posto).
    assertThat(laneStep(before, AnalysisScenes.restFlow(seed = 900))).isEqualTo(0)
  }

  // --- Giro 2 ------------------------------------------------------------------------------------------------------------------------------------

  /** XS2-1: una revoca a meta' del passo toglie il media nello stesso fotogramma (il passo trattiene il movimento, non l'autorizzazione). */
  @Test
  fun unaRevocaAMetaPassoTogliePrimaDelPasso() = revocaAMetaPasso(reduced = false)

  @Test
  fun unaRevocaAMetaPassoTogliePrimaDelPassoAncheConIlMovimentoRidotto() = revocaAMetaPasso(reduced = true)

  private fun revocaAMetaPasso(reduced: Boolean) {
    val before = AnalysisScenes.flow()
    val target = before.recent[0] as AnalysisThumb.Shown
    var flow by mutableStateOf(before)
    rule.mainClock.autoAdvance = false
    rule.setSearchContent {
      CompositionLocalProvider(LocalFluidMotionPolicy provides FluidMotionPolicy(durationScale = if (reduced) 0f else 1f)) {
        AnalysisScreen(AnalysisScenes.RUNNING, flow, AnalysisScenes.thumbnails, Recording(), onBack = {})
      }
    }
    rule.mainClock.advanceTimeByFrame()
    rule.waitForIdle()
    assertThat(nodesOf(target)).isNotEmpty()
    // Siamo dentro il passo (il nastro aspetta 1,2 s prima di prendere un valore nuovo): il flusso revoca il media.
    rule.mainClock.advanceTimeBy(STEP_INSIDE)
    // La scrittura sul filo dell'interfaccia, come il flusso raccolto dalla pagina; il fotogramma dopo deve essere gia' senza il media.
    rule.runOnIdle { flow = AnalysisFlow(before.current, listOf(AnalysisThumb.Quiet) + before.recent.drop(1), before.next) }
    rule.waitForIdle()
    rule.mainClock.advanceTimeByFrame()
    rule.waitForIdle()
    assertThat(nodesOf(target)).isEmpty()
    // ...e non torna nemmeno quando il passo scade.
    rule.mainClock.advanceTimeBy(STEP_AFTER)
    rule.waitForIdle()
    assertThat(nodesOf(target)).isEmpty()
  }

  private fun nodesOf(thumb: AnalysisThumb.Shown) =
    rule.onAllNodes(SemanticsMatcher.expectValue(AnalysisMediaKey, thumb.id.value), useUnmergedTree = true).fetchSemanticsNodes()

  @Test
  fun ilFotogrammaTrattenutoPrendeSoloCioCheIlNastroDiAdessoMostra() {
    val held = AnalysisScenes.flow()
    val target = held.recent[0] as AnalysisThumb.Shown
    val now = AnalysisFlow(held.current, listOf(AnalysisThumb.Quiet) + held.recent.drop(1), held.next)
    val shown = visibleNow(held, now)
    assertThat(shown.recent[0]).isEqualTo(AnalysisThumb.Quiet)
    assertThat((listOfNotNull(shown.current) + shown.recent + shown.next).filterIsInstance<AnalysisThumb.Shown>().map { it.id }).doesNotContain(target.id)
    // Riferimento e ThumbHash vengono dal nastro di adesso, non da quello trattenuto.
    val fresh = AnalysisThumb.Shown(target.id, request = null, thumbHash = null, video = false)
    val moved = visibleNow(held, AnalysisFlow(held.current, listOf(fresh) + held.recent.drop(1), held.next))
    assertThat(moved.recent[0]).isSameInstanceAs(fresh)
  }

  /** XU-4 e XU-9: la prima entrata scorre da destra; una fila di caselle neutre fa avanzare il nastro; una revoca senza anteprime in comune no. */
  @Test
  fun ilPassoConLaPrimaEntrataEConLeCaselleNeutre() {
    assertThat(laneStep(AnalysisFlow.EMPTY, AnalysisScenes.flow())).isEqualTo(2)
    val q = AnalysisThumb.Quiet
    val one = AnalysisScenes.shownFor(41)
    assertThat(laneStep(AnalysisFlow(q, listOf(q), listOf(q, q)), AnalysisFlow(q, listOf(q, q), listOf(q)))).isEqualTo(1)
    assertThat(laneStep(AnalysisFlow(q, listOf(one), listOf(q)), AnalysisFlow(q, listOf(q), listOf(q)))).isEqualTo(0)
  }

  @Test
  fun leCaselleNeutreSiVedonoNelMirinoEInFila() {
    rule.setSearchContent {
      AnalysisScreen(AnalysisScenes.RUNNING, AnalysisScenes.quietFlow(), AnalysisScenes.thumbnails, Recording(), onBack = {})
    }
    rule.onAllNodesWithContentDescription("Adesso guarda una foto che qui non si mostra", substring = true).onFirst().assertExists()
    assertThat(rule.onAllNodesWithTag(AnalysisQuietTag, useUnmergedTree = true).fetchSemanticsNodes().size).isAtLeast(4)
  }

  private companion object {
    const val STEP_INSIDE = 300L
    const val STEP_AFTER = 1_500L
  }
}
