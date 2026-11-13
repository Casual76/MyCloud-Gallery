package dev.pampa.mycloudgallery.feature.search

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import dev.pampa.mycloudgallery.feature.search.models.visibleNow
import dev.pampa.mycloudgallery.feature.search.models.AnalysisMediaKey
import dev.pampa.mycloudgallery.feature.search.models.AnalysisBornKey
import dev.pampa.mycloudgallery.feature.search.models.frameBase
import dev.pampa.mycloudgallery.feature.search.models.LocalLaneSteps
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
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisWait
import dev.pampa.mycloudgallery.feature.search.models.analyzeNowShown
import dev.pampa.mycloudgallery.feature.search.models.AnalysisBetweenTag
import dev.pampa.mycloudgallery.feature.search.models.AnalysisPassingTag
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
    // Giro 4 (XU2-1): nessun percento (la libreria puo' avere file che non si analizzano), la riga vera.
    assertThat(rule.onAllNodesWithText("100%", useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
    rule.onNodeWithText("Niente da analizzare qui", useUnmergedTree = true).assertIsDisplayed()
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

  // --- Giro 3: la generazione di visibilita' ----------------------------------------------------------------------------------------------------

  private fun showMutable(initial: AnalysisFlow): androidx.compose.runtime.MutableState<AnalysisFlow> {
    val state = mutableStateOf(initial)
    rule.mainClock.autoAdvance = false
    rule.setSearchContent {
      AnalysisScreen(AnalysisScenes.RUNNING, state.value, AnalysisScenes.thumbnails, Recording(), onBack = {})
    }
    rule.mainClock.advanceTimeByFrame()
    rule.waitForIdle()
    return state
  }

  private fun emit(state: androidx.compose.runtime.MutableState<AnalysisFlow>, value: AnalysisFlow) {
    rule.runOnIdle { state.value = value }
    rule.waitForIdle()
    rule.mainClock.advanceTimeByFrame()
    rule.waitForIdle()
  }

  private fun bornOf(thumb: AnalysisThumb.Shown): List<Long> =
    nodesOf(thumb).map { it.config[AnalysisBornKey] }

  /**
   * XS2-1 parte 2 (giro 3): a una generazione nuova nessuna anteprima nata nella vecchia resta a schermo, nemmeno se lo stesso media e' ancora
   * mostrabile e il valore neutro di passaggio e' stato saltato (uno StateFlow puo' unire le due emissioni): la sua composizione rinasce.
   */
  @Test
  fun unaGenerazioneNuovaNonRiusaNessunaAnteprimaDellaVecchia() {
    val gen1 = AnalysisScenes.flow().copy(visibilityGeneration = 1)
    val target = gen1.recent[0] as AnalysisThumb.Shown
    val state = showMutable(gen1)
    assertThat(bornOf(target)).containsExactly(1L)
    // Dentro il passo (1,2 s): la visibilita' cambia e arriva direttamente il risolto della generazione 2 con lo stesso media.
    rule.mainClock.advanceTimeBy(STEP_INSIDE)
    emit(state, gen1.copy(visibilityGeneration = 2))
    assertThat(bornOf(target)).containsExactly(2L)
  }

  /** Giro 3: il neutro di passaggio toglie subito tutte le anteprime, anche quelle trattenute dal passo. */
  @Test
  fun ilNeutroDiUnaGenerazioneNuovaToglieTutteLeAnteprimeSubito() {
    val gen1 = AnalysisScenes.flow().copy(visibilityGeneration = 1)
    val state = showMutable(gen1)
    assertThat(rule.onAllNodesWithTag(AnalysisShownTag, useUnmergedTree = true).fetchSemanticsNodes()).isNotEmpty()
    rule.mainClock.advanceTimeBy(STEP_INSIDE)
    emit(state, neutralOf(gen1, generation = 2))
    assertThat(rule.onAllNodesWithTag(AnalysisShownTag, useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
  }

  /** Giro 3 (XU-4 con l'ordine nuovo): vuoto, neutro, risolto: le anteprime vere compaiono subito, senza aspettare il passo del neutro. */
  @Test
  fun vuotoNeutroRisoltoLeAnteprimeArrivanoSubito() {
    val resolved = AnalysisScenes.flow().copy(visibilityGeneration = 1)
    val target = resolved.current as AnalysisThumb.Shown
    val state = showMutable(AnalysisFlow.EMPTY)
    emit(state, neutralOf(resolved, generation = 1))
    emit(state, resolved)
    rule.mainClock.advanceTimeBy(FEW_FRAMES)
    rule.waitForIdle()
    assertThat(nodesOf(target)).isNotEmpty()
  }

  @Test
  fun laBaseDelFotogrammaSegueLaGenerazione() {
    val old = AnalysisScenes.flow().copy(visibilityGeneration = 1)
    val now = neutralOf(old, generation = 2)
    assertThat(frameBase(old, now)).isSameInstanceAs(now)
    assertThat(frameBase(old, old.copy(next = emptyList()))).isSameInstanceAs(old)
    // Dal neutro al risolto la corsia entra da destra; dal vuoto al neutro non scorre.
    assertThat(laneStep(now, old.copy(visibilityGeneration = 2))).isEqualTo(2)
    assertThat(laneStep(AnalysisFlow.EMPTY, now)).isEqualTo(0)
    assertThat(laneStep(old, neutralOf(old, generation = 1))).isEqualTo(0)
  }

  private fun neutralOf(flow: AnalysisFlow, generation: Long): AnalysisFlow = AnalysisFlow(
    flow.current?.let { AnalysisThumb.Quiet },
    flow.recent.map { AnalysisThumb.Quiet },
    flow.next.map { AnalysisThumb.Quiet },
    generation,
  )

  // --- Giro 3, XU-9: il contatore dei media finiti -----------------------------------------------------------------------------------------------

  /** Un nastro tutto di caselle neutre con le stesse lunghezze: lo fa avanzare solo il contatore (uno StateFlow non emetterebbe altrimenti). */
  private fun quietRun(finished: Long, generation: Long = 1): AnalysisFlow {
    val q = AnalysisThumb.Quiet
    return AnalysisFlow(q, listOf(q, q, q), listOf(q, q), visibilityGeneration = generation, finished = finished)
  }

  @Test
  fun ilContatoreDeiFinitiFaAvanzareUnNastroDiCaselleNeutre() {
    // Cresciuto di uno o di tanti: un passo solo.
    assertThat(laneStep(quietRun(5), quietRun(6))).isEqualTo(1)
    assertThat(laneStep(quietRun(5), quietRun(9))).isEqualTo(1)
    // Fermo: nessun passo, nemmeno con la generazione cambiata o la forma diversa.
    assertThat(laneStep(quietRun(5), quietRun(5, generation = 2))).isEqualTo(0)
    val shorter = quietRun(5, generation = 2).let { it.copy(recent = it.recent.drop(1)) }
    assertThat(laneStep(quietRun(5), shorter)).isEqualTo(0)
  }

  private class Steps {
    val seen = mutableListOf<Pair<Int, Boolean>>()
    val moving: List<Pair<Int, Boolean>> get() = seen.filter { it.first > 0 }
  }

  private fun showSteps(initial: AnalysisFlow, reduced: Boolean = false): Pair<androidx.compose.runtime.MutableState<AnalysisFlow>, Steps> {
    val state = mutableStateOf(initial)
    val steps = Steps()
    rule.mainClock.autoAdvance = false
    rule.setSearchContent {
      CompositionLocalProvider(
        LocalFluidMotionPolicy provides FluidMotionPolicy(durationScale = if (reduced) 0f else 1f),
        LocalLaneSteps provides { step, moves -> steps.seen += step to moves },
      ) {
        AnalysisScreen(AnalysisScenes.RUNNING, state.value, AnalysisScenes.thumbnails, Recording(), onBack = {})
      }
    }
    rule.mainClock.advanceTimeByFrame()
    rule.waitForIdle()
    return state to steps
  }

  /** XU-9: piu' media finiti fra due stati del passo = un solo passo, al ritmo di sempre (non una raffica). */
  @Test
  fun piuFinitiDentroIlPassoSonoUnPassoSolo() {
    val (state, steps) = showSteps(quietRun(10))
    emit(state, quietRun(11))
    emit(state, quietRun(12))
    emit(state, quietRun(14))
    // Ancora dentro il passo (1,2 s dal primo valore): niente si muove.
    assertThat(steps.moving).isEmpty()
    rule.mainClock.advanceTimeBy(STEP_AFTER)
    rule.waitForIdle()
    assertThat(steps.moving).containsExactly(1 to true)
  }

  /** XU-9: un cambio di generazione senza media finiti non e' un passo. */
  @Test
  fun unaGenerazioneNuovaSenzaFinitiNonEUnPasso() {
    val (state, steps) = showSteps(quietRun(10))
    rule.mainClock.advanceTimeBy(STEP_AFTER)
    emit(state, quietRun(10, generation = 2))
    rule.mainClock.advanceTimeBy(STEP_AFTER)
    rule.waitForIdle()
    assertThat(steps.moving).isEmpty()
  }

  /** XU-9 con il movimento ridotto: il passo si conta ma niente scorre. */
  @Test
  fun conIlMovimentoRidottoIlContatoreNonMuoveNiente() {
    val (state, steps) = showSteps(quietRun(10), reduced = true)
    emit(state, quietRun(11))
    rule.mainClock.advanceTimeBy(STEP_AFTER)
    rule.waitForIdle()
    assertThat(steps.moving).containsExactly(1 to false)
  }

  // --- Giro 4 -----------------------------------------------------------------------------------------------------------------------------------

  /** XU2-4 e XU-12: il tasto «Analizza ora» c'e' solo dove una richiesta fa davvero partire l'analisi. Una tabella per ogni stato e ogni perche'. */
  @Test
  fun ilTastoCESoloDoveUnaRichiestaFaPartire() {
    val starts = setOf(
      LibraryAnalysisWait.NIGHT_WINDOW,
      LibraryAnalysisWait.CHARGING,
      LibraryAnalysisWait.BATTERY,
      LibraryAnalysisWait.POWER_SAVER,
      LibraryAnalysisWait.THERMAL,
      LibraryAnalysisWait.ANDROID_LIMIT,
    )
    for (reason in LibraryAnalysisWait.entries) {
      assertWithMessage("in pausa per $reason").that(analyzeNowShown(LibraryAnalysisStatus.Paused(reason, 10, 5, 15))).isEqualTo(reason in starts)
    }
    // Esplicite, quelle della critica: il NAS, il Wi-Fi, il Wi-Fi di casa, le foto in preparazione.
    for (reason in listOf(LibraryAnalysisWait.NAS, LibraryAnalysisWait.WIFI, LibraryAnalysisWait.HOME_WIFI, LibraryAnalysisWait.PREPARATION)) {
      assertThat(analyzeNowShown(LibraryAnalysisStatus.Paused(reason, null, null, null))).isFalse()
    }
    val table = mapOf(
      LibraryAnalysisStatus.Queued(10, 5, 15) to true,
      LibraryAnalysisStatus.Queued(null, null, null) to true,
      LibraryAnalysisStatus.Running(10, null, 5, 15) to false,
      LibraryAnalysisStatus.AllReady to false,
      LibraryAnalysisStatus.NotHere(5, 15) to false,
      LibraryAnalysisStatus.Disabled to false,
      LibraryAnalysisStatus.ModelMissing to false,
      LibraryAnalysisStatus.Problem("AI_STORAGE_LOW") to false,
      LibraryAnalysisStatus.Idle to false,
    )
    table.forEach { (status, shown) -> assertWithMessage("$status").that(analyzeNowShown(status)).isEqualTo(shown) }
  }

  /** XU2-4 e XU2-5 nella pagina: con il NAS che non risponde niente tasto; con le foto in preparazione «In attesa» e niente tasto. */
  @Test
  fun doveUnToccoNonFaPartireNienteIlTastoNonCE() {
    var status by mutableStateOf<LibraryAnalysisStatus>(LibraryAnalysisStatus.Paused(LibraryAnalysisWait.NAS, 2_700, 12_300, 15_000))
    rule.setSearchContent { AnalysisScreen(status, AnalysisScenes.restFlow(), AnalysisScenes.thumbnails, Recording(), onBack = {}) }
    rule.onNodeWithText("Il NAS non risponde", useUnmergedTree = true).assertIsDisplayed()
    assertThat(rule.onAllNodesWithText("Analizza ora").fetchSemanticsNodes()).isEmpty()
    rule.runOnIdle { status = LibraryAnalysisStatus.Paused(LibraryAnalysisWait.PREPARATION, 2_700, 12_300, 15_000) }
    rule.onNodeWithText("In attesa", useUnmergedTree = true).assertIsDisplayed()
    assertThat(rule.onAllNodesWithText("In pausa", useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
    assertThat(rule.onAllNodesWithText("Analizza ora").fetchSemanticsNodes()).isEmpty()
    // In coda si': una richiesta la fa passare davanti.
    rule.runOnIdle { status = LibraryAnalysisStatus.Queued(2_700, 12_300, 15_000) }
    rule.onNodeWithText("Parte da sola", useUnmergedTree = true).assertIsDisplayed()
    rule.onNodeWithText("Analizza ora").assertIsDisplayed()
  }

  /**
   * XU2-2 (riservatezza): la casella di passaggio (una revoca a meta' passo) e' una tessera vuota che non porta **nessun** dato del media: ne'
   * il media ne' la generazione in cui era nata la sua anteprima, nessun figlio (niente immagine); il media revocato non ha nessun nodo.
   */
  @Test
  fun laCasellaDiPassaggioNonPortaNessunDatoDelMedia() {
    val gen1 = AnalysisScenes.flow().copy(visibilityGeneration = 1)
    val target = gen1.recent[0] as AnalysisThumb.Shown
    val state = showMutable(gen1)
    rule.mainClock.advanceTimeBy(STEP_INSIDE)
    emit(state, gen1.copy(recent = listOf(AnalysisThumb.Quiet) + gen1.recent.drop(1)))
    val passing = rule.onAllNodesWithTag(AnalysisPassingTag, useUnmergedTree = true).fetchSemanticsNodes()
    assertThat(passing).isNotEmpty()
    passing.forEach { node ->
      assertThat(node.config.contains(AnalysisMediaKey)).isFalse()
      assertThat(node.config.contains(AnalysisBornKey)).isFalse()
      assertThat(node.children).isEmpty()
    }
    assertThat(nodesOf(target)).isEmpty()
  }

  /**
   * XU2-2: il neutro con cui si apre una generazione nuova e' di passaggio: tessere vuote (nessun occhio barrato, nessun dato); se non arriva altro
   * (le foto sono davvero tutte non mostrabili), dopo un passo le caselle prendono l'occhio barrato.
   */
  @Test
  fun ilNeutroDiPassaggioEFattoDiTessereVuote() {
    val gen1 = AnalysisScenes.flow().copy(visibilityGeneration = 1)
    val state = showMutable(gen1)
    emit(state, neutralOf(gen1, generation = 2))
    assertThat(rule.onAllNodesWithTag(AnalysisPassingTag, useUnmergedTree = true).fetchSemanticsNodes()).isNotEmpty()
    assertThat(rule.onAllNodesWithTag(AnalysisQuietTag, useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
    assertThat(rule.onAllNodes(SemanticsMatcher.keyIsDefined(AnalysisMediaKey), useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
    rule.mainClock.advanceTimeBy(STEP_AFTER)
    rule.waitForIdle()
    assertThat(rule.onAllNodesWithTag(AnalysisQuietTag, useUnmergedTree = true).fetchSemanticsNodes()).isNotEmpty()
    assertThat(rule.onAllNodesWithTag(AnalysisPassingTag, useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
  }

  /** XU2-2: uno stato fermo (la pausa: niente nel mirino, niente in arrivo) si applica subito, senza aspettare la fine del passo. */
  @Test
  fun unoStatoFermoSiApplicaSubito() {
    val gen1 = AnalysisScenes.flow().copy(visibilityGeneration = 1)
    val next = gen1.next[0] as AnalysisThumb.Shown
    val state = showMutable(gen1)
    rule.mainClock.advanceTimeBy(STEP_INSIDE)
    emit(state, AnalysisFlow(null, gen1.recent, emptyList(), visibilityGeneration = 1))
    rule.mainClock.advanceTimeBy(FEW_FRAMES)
    rule.waitForIdle()
    // Dopo pochi fotogrammi (non 1,2 s): le prossime non ci sono piu', nemmeno come tessere di passaggio.
    assertThat(nodesOf(next)).isEmpty()
    assertThat(rule.onAllNodesWithTag(AnalysisPassingTag, useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
  }

  /** XU2-6: in corso ma con il mirino vuoto (fra un mese e l'altro): una tessera vuota, non l'aspetto di «Tutto pronto». */
  @Test
  fun fraUnaTappaELAltraIlMirinoAspettaLaProssima() {
    val flow = AnalysisScenes.flow()
    rule.setSearchContent { AnalysisScreen(AnalysisScenes.RUNNING, AnalysisFlow(null, flow.recent, flow.next), AnalysisScenes.thumbnails, Recording(), onBack = {}) }
    rule.onNodeWithTag(AnalysisBetweenTag, useUnmergedTree = true).assertExists()
    assertThat(rule.onAllNodesWithTag(AnalysisRestTag, useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
  }

  private companion object {
    const val STEP_INSIDE = 300L
    const val STEP_AFTER = 1_500L
    const val FEW_FRAMES = 100L
  }
}
