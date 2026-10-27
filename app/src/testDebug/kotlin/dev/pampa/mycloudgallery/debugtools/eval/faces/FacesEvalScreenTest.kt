package dev.pampa.mycloudgallery.debugtools.eval.faces

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import kotlinx.coroutines.runBlocking
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
 * Lo strumento composto davvero (Robolectric): la schermata senza porta dice che le persone non sono collegate; con la porta (una famiglia sintetica) mostra le due
 * sessioni, sceglie i gruppi, apre un gruppo, segna un volto con un tocco (e lo salva), dice "Gruppo misto" e porta a "e' la stessa persona". Niente volti veri: i
 * ritagli sono sagome astratte.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class FacesEvalScreenTest {

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
    FacesEvalEnvironmentHolder.current = null
  }

  private fun show() {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) { FacesEvalScreen(onBack = {}) }
    }
    composeRule.waitForIdle()
  }

  private fun storedLabels(rig: FacesEvalTestRig): FacesLabelSet {
    val app = ApplicationProvider.getApplicationContext<Application>()
    return FacesEvalSession(rig, FacesEvalStore.of(app)).also { it.load() }.labels.value
  }

  @Test
  fun senzaLaPortaDiceCheLePersoneNonSonoCollegate() {
    FacesEvalEnvironmentHolder.current = null
    show()
    composeRule.onNodeWithText(FacesEvalStrings.NOT_BOUND, substring = true).assertExists()
  }

  @Test
  fun laSchermataMostraLeDueSessioniELeFaIniziare() {
    val rig = FacesEvalTestRig.family()
    FacesEvalEnvironmentHolder.current = rig
    show()
    composeRule.onNodeWithText(FacesEvalStrings.SECTION_HOW, ignoreCase = true).assertExists()
    composeRule.onNodeWithText(FacesEvalStrings.SESSION_1, ignoreCase = true).assertExists()
    composeRule.onNodeWithText(FacesEvalStrings.SESSION_2, ignoreCase = true).assertExists()
    assertThat(composeRule.onAllNodesWithText(FacesEvalStrings.NOT_STARTED).fetchSemanticsNodes()).hasSize(2)

    // "Inizia" della sessione 1: sceglie i gruppi e mostra l'elenco, con lo stato di ognuno.
    composeRule.onAllNodesWithText(FacesEvalStrings.START)[0].performScrollTo().performClick()
    val biggest = rig.members().values.maxOf { it.size }
    composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(FacesEvalStrings.groupRow(1, biggest)).fetchSemanticsNodes().isNotEmpty() }
    composeRule.onNodeWithText(FacesEvalStrings.sessionProgress(0, rig.members().size, false), substring = true).assertExists()
    assertThat(storedLabels(rig).session(EvalSessionKind.CALIBRATION)!!.groups).hasSize(rig.members().size)
  }

  @Test
  fun unGruppoSiApreEUnToccoSegnaUnVolto() {
    val rig = FacesEvalTestRig.family()
    FacesEvalEnvironmentHolder.current = rig
    show()
    composeRule.onAllNodesWithText(FacesEvalStrings.START)[0].performScrollTo().performClick()
    val biggest = rig.members().values.maxOf { it.size }
    composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(FacesEvalStrings.groupRow(1, biggest)).fetchSemanticsNodes().isNotEmpty() }

    // Si apre il primo gruppo: una cella per volto (o per i primi 60 piu' i 10 meno simili), tutte "giuste".
    composeRule.onNodeWithText(FacesEvalStrings.groupRow(1, biggest)).performClick()
    val shown = minOf(biggest, 70)
    val right = FacesEvalStrings.faceDescription(1, shown, wrong = false)
    composeRule.waitUntil(5_000) { composeRule.onAllNodesWithContentDescription(right).fetchSemanticsNodes().isNotEmpty() }
    // Il titolo c'e' due volte: grande e, quando la barra si chiude, piccolo.
    assertThat(composeRule.onAllNodesWithText(FacesEvalStrings.groupTitle(1, rig.members().size)).fetchSemanticsNodes()).isNotEmpty()

    // Un tocco segna il volto come non suo; si salva subito, e il gruppo comincia a contare.
    composeRule.onNodeWithContentDescription(right).performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription(FacesEvalStrings.faceDescription(1, shown, wrong = true)).assertExists()
    val saved = storedLabels(rig).session(EvalSessionKind.CALIBRATION)!!.groups.first()
    assertThat(saved.wrong).hasSize(1)
    assertThat(saved.reviewed).isTrue()
    assertThat(saved.shown).isEqualTo(shown)

    // Un altro tocco sullo stesso volto lo rimette giusto.
    composeRule.onNodeWithContentDescription(FacesEvalStrings.faceDescription(1, shown, wrong = true)).performClick()
    composeRule.waitForIdle()
    assertThat(storedLabels(rig).session(EvalSessionKind.CALIBRATION)!!.groups.first().wrong).isEmpty()
  }

  @Test
  fun gruppoMistoEStessaPersona() {
    val rig = FacesEvalTestRig.family()
    FacesEvalEnvironmentHolder.current = rig
    // La sessione 2 e' gia' cominciata (come se l'utente avesse chiuso e riaperto l'app): il tasto dice "Continua".
    runBlocking { FacesEvalSession(rig, FacesEvalStore.of(ApplicationProvider.getApplicationContext<Application>())).also { it.load() }.start(EvalSessionKind.MEASURE) }
    show()
    composeRule.onNodeWithText(FacesEvalStrings.CONTINUE).performScrollTo().performClick()
    val biggest = rig.members().values.maxOf { it.size }
    composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(FacesEvalStrings.groupRow(1, biggest)).fetchSemanticsNodes().isNotEmpty() }
    composeRule.onNodeWithText(FacesEvalStrings.groupRow(1, biggest)).performClick()
    composeRule.waitUntil(5_000) { composeRule.onAllNodesWithContentDescription("Volto 1 di", substring = true).fetchSemanticsNodes().isNotEmpty() }

    // I bottoni stanno in fondo alla griglia: la lista li porta in vista.
    composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText(FacesEvalStrings.MIXED))
    composeRule.onNodeWithText(FacesEvalStrings.MIXED).performClick()
    composeRule.waitForIdle()
    val mixed = storedLabels(rig).session(EvalSessionKind.MEASURE)!!.groups.first()
    assertThat(mixed.mixed).isTrue()
    assertThat(mixed.reviewed).isTrue()
    composeRule.onNodeWithText(FacesEvalStrings.MIXED_OFF).assertExists()

    // "E' la stessa persona di un altro gruppo": l'elenco degli altri gruppi; un tocco li collega.
    composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText(FacesEvalStrings.SAME_TITLE))
    composeRule.onNodeWithText(FacesEvalStrings.SAME_TITLE).performClick()
    composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Gruppo di", substring = true).fetchSemanticsNodes().isNotEmpty() }
    composeRule.onAllNodesWithText("Gruppo di", substring = true)[0].performClick()
    composeRule.waitForIdle()
    assertThat(storedLabels(rig).session(EvalSessionKind.MEASURE)!!.groups.first().sameAs).hasSize(1)
  }
}
