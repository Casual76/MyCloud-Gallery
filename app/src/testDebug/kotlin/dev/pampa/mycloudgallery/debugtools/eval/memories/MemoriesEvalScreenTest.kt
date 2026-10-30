package dev.pampa.mycloudgallery.debugtools.eval.memories

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Lo strumento composto davvero (Robolectric): la schermata senza porta dice che i ricordi non sono collegati; con la porta (ricordi inventati) la corsa riempie la lista, un
 * ricordo si apre, una foto si segna (e il segno si salva), il tocco sul ricordo si registra e i numeri escono come resoconto. Niente foto vere: i ricordi sono quelli del banco.
 * Lo schermo e' alto, cosi' la lista pigra compone tutto senza scorrere.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h4000dp-xhdpi")
class MemoriesEvalScreenTest {

  private val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterActivity()).around(composeRule)

  private class RegisterActivity : TestWatcher() {
    override fun starting(description: Description) {
      val app = ApplicationProvider.getApplicationContext<Application>()
      val info = ActivityInfo().apply {
        name = ComponentActivity::class.java.name
        packageName = app.packageName
        flags = flags or ActivityInfo.FLAG_HARDWARE_ACCELERATED
      }
      shadowOf(app.packageManager).addOrUpdateActivity(info)
    }
  }

  @After
  fun tearDown() {
    MemoriesEvalEnvironmentHolder.current = null
    MemoriesEvalStore.of(ApplicationProvider.getApplicationContext<Application>()).wipe()
  }

  private fun show() {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) { MemoriesEvalScreen(onBack = {}) }
    }
    composeRule.waitForIdle()
  }

  private fun waitForText(text: String, substring: Boolean = false) {
    composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
  }

  @Test
  fun senzaLaPortaDiceCheIRicordiNonSonoCollegati() {
    MemoriesEvalEnvironmentHolder.current = null
    show()
    composeRule.onNodeWithText(MemoriesEvalStrings.NOT_BOUND, substring = true).assertExists()
  }

  @Test
  fun laCorsaRiempieLaListaEUnRicordoSiGiudica() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val rig = MemoriesEvalTestRig(count = 5)
    MemoriesEvalEnvironmentHolder.current = rig
    show()
    composeRule.onNodeWithText(MemoriesEvalStrings.SECTION_HOW, ignoreCase = true).assertExists()
    composeRule.onNodeWithText(MemoriesEvalStrings.NO_SESSION).assertExists()

    // La corsa: cinque ricordi inventati nella lista, ancora da giudicare.
    composeRule.onNodeWithText(MemoriesEvalStrings.RUN_BUTTON).performClick()
    waitForText(MemoriesEvalStrings.RUN_DONE + 5)
    assertThat(rig.generateCalls).isEqualTo(1)
    composeRule.onNodeWithText("Ricordo 1 (anni fa), 6 foto: ${MemoriesEvalStrings.PENDING}").assertExists()

    // Si apre il primo: una cella per foto, descritte per TalkBack senza id ne' date.
    composeRule.onNodeWithText("Ricordo 1 (anni fa), 6 foto: ${MemoriesEvalStrings.PENDING}").performClick()
    waitForText(MemoriesEvalStrings.GRID_HINT)
    composeRule.onAllNodesWithContentDescription("Foto 1 di 6", substring = true).assertCountEquals(1)
    composeRule.onAllNodesWithContentDescription("Foto 6 di 6", substring = true).assertCountEquals(1)

    // Un tocco sulla prima foto offre i motivi; "Doppione" la segna, e il segno si salva.
    composeRule.onAllNodesWithContentDescription("Foto 1 di 6", substring = true)[0].performClick()
    waitForText(MemoriesEvalStrings.SELECTED_HINT)
    composeRule.onNodeWithText(MemoriesEvalStrings.FAULT_DUPLICATE).performClick()
    composeRule.waitForIdle()
    composeRule.onAllNodesWithContentDescription("Foto 1 di 6, ${MemoriesEvalStrings.FAULT_DUPLICATE}").assertCountEquals(1)
    val firstId = MemoriesEvalTestRig.memoryId(0)
    val saved = MemoriesEvalSession(rig, MemoriesEvalStore.of(app)).also { it.load() }
    assertThat(saved.labels.value.faultsOf(firstId).values).containsExactly(EvalFault.DUPLICATE)

    // Il tocco sul ricordo: "Bello" lo registra; "Da non mostrare" offre il motivo.
    composeRule.onNodeWithText(MemoriesEvalStrings.VERDICT_GOOD).performClick()
    composeRule.waitForIdle()
    assertThat(MemoriesEvalSession(rig, MemoriesEvalStore.of(app)).also { it.load() }.labels.value.judgement(firstId)!!.verdict).isEqualTo(EvalVerdict.GOOD)
    composeRule.onNodeWithText(MemoriesEvalStrings.VERDICT_DONT_SHOW).performClick()
    waitForText(MemoriesEvalStrings.WHY_HINT)
    composeRule.onNodeWithText(MemoriesEvalStrings.WHY_SAD).performClick()
    composeRule.waitForIdle()
    assertThat(MemoriesEvalSession(rig, MemoriesEvalStore.of(app)).also { it.load() }.labels.value.judgement(firstId)).isEqualTo(EvalJudgement(EvalVerdict.DONT_SHOW, EvalWhy.SAD))
  }

  @Test
  fun iNumeriEscono_ComeResocontoDiSoliNumeri() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val rig = MemoriesEvalTestRig(count = 5)
    MemoriesEvalEnvironmentHolder.current = rig
    // Una sessione gia' scritta, come se l'utente avesse chiuso e riaperto l'app: un ricordo giudicato, con una foto da togliere.
    val before = MemoriesEvalSession(rig, MemoriesEvalStore.of(app)).also { it.load() }
    kotlinx.coroutines.runBlocking { before.start() }
    val first = before.labels.value.run[0]
    before.setFault(first.id, first.members[2], EvalFault.UNRELATED)
    before.judge(first.id, EvalVerdict.DONT_SHOW, EvalWhy.BORING)
    show()
    waitForText("Ricordo 1 (anni fa), 6 foto: ${MemoriesEvalStrings.VERDICT_DONT_SHOW}, 1 da togliere")
    composeRule.onNodeWithText("1 giudicati su 5", substring = true).assertExists()
    composeRule.onNodeWithText(MemoriesEvalStrings.NUMBERS_TITLE).performClick()
    waitForText("report-01.txt")
    waitForText("1 giudicati (4 senza giudizio)", substring = true)
    // Nel resoconto non c'e' niente di privato.
    for (m in before.labels.value.run) assertThat(composeRule.onAllNodesWithText(m.title, substring = true).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun unNascostoDopoLaCorsaEsceDallaListaEDaiNumeri() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val rig = MemoriesEvalTestRig(count = 4)
    MemoriesEvalEnvironmentHolder.current = rig
    // Una sessione gia' scritta, come se l'utente avesse chiuso e riaperto l'app; poi nasconde quasi tutto il secondo ricordo.
    val before = MemoriesEvalSession(rig, MemoriesEvalStore.of(app)).also { it.load() }
    kotlinx.coroutines.runBlocking { before.start() }
    val second = before.labels.value.run[1]
    rig.hidden += second.members.drop(1)
    show()
    waitForText(MemoriesEvalStrings.HIDDEN_DROPPED)
    composeRule.onNodeWithText("Ricordo 1 (anni fa), 6 foto: ${MemoriesEvalStrings.PENDING}").assertExists()
    assertThat(composeRule.onAllNodesWithText("Ricordo 4", substring = true).fetchSemanticsNodes()).isEmpty()
    assertThat(MemoriesEvalSession(rig, MemoriesEvalStore.of(app)).also { it.load() }.labels.value.run).hasSize(3)
  }
}
