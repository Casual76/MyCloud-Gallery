package dev.pampa.mycloudgallery.feature.diagnostics.release

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
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

/** "Simula il passo" su Robolectric: cosa si legge, cosa si tocca. Telefono finto alto 2400 dp: la lista compone tutte le righe. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h2400dp-xhdpi")
class StepSimulationScreenTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private class RegisterComponentActivity : TestWatcher() {
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

  private fun show(content: @Composable () -> Unit) {
    composeRule.setContent { MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) { content() } }
    composeRule.waitForIdle()
  }

  private val report = StepReport(
    forecasts = listOf(
      StepForecast.Writes(StepArea.INDEX, StepCertainty.ESTIMATE, listOf(StepWrite(StepPlace.PRIVATE_AREA, StepFolder.APP_FOLDER, 12, 400L shl 20))),
      StepForecast.Writes(StepArea.BACKUP, StepCertainty.EXACT, listOf(StepWrite(StepPlace.PRIVATE_AREA, StepFolder.BACKUP, 1234, 12L shl 30))),
      StepForecast.Idle(StepArea.FACES, StepIdle.FEATURE_OFF),
      StepForecast.Idle(StepArea.ALBUMS, StepIdle.ON_USER_ACTION),
    ),
    space = StepSpace(300L shl 30),
    freeAfterBytes = 287L shl 30,
    attention = setOf(StepAttention.ESTIMATES_PRESENT),
  )

  @Test
  fun primaDelGiroDiceCheEDiSolaLetturaEOffreIlTasto() {
    var runs = 0
    show { StepSimulationScreen(state = StepSimulationState.Idle, copied = false, onRun = { runs++ }, onCopy = {}, onBack = {}) }
    composeRule.onNodeWithText(StepSimulationText.READ_ONLY_TITLE).assertIsDisplayed()
    composeRule.onNodeWithText(StepSimulationText.READ_ONLY_MESSAGE).assertIsDisplayed()
    composeRule.onNodeWithText(StepSimulationText.RUN).performClick()
    assertThat(runs).isEqualTo(1)
  }

  @Test
  fun conIlResocontoMostraLeQuattroAreeIlTotaleELoSpazio() {
    show { StepSimulationScreen(state = StepSimulationState.Done(report), copied = false, onRun = {}, onCopy = {}, onBack = {}) }
    StepArea.entries.forEach { composeRule.onNodeWithText(StepSimulationText.areaName(it)).assertIsDisplayed() }
    composeRule.onNodeWithText(StepSimulationText.detail(report.forecasts[1])).assertIsDisplayed()
    composeRule.onNodeWithText(StepSimulationText.idleText(StepIdle.FEATURE_OFF)).assertIsDisplayed()
    composeRule.onNodeWithText("1.246 file, 12,4 GB").assertIsDisplayed()
    composeRule.onNodeWithText(StepSimulationText.attentionText(StepAttention.ESTIMATES_PRESENT)).assertIsDisplayed()
  }

  @Test
  fun ilTastoCopiaPassaDalCallback() {
    var copies = 0
    show { StepSimulationScreen(state = StepSimulationState.Done(report), copied = false, onRun = {}, onCopy = { copies++ }, onBack = {}) }
    composeRule.onNodeWithText(StepSimulationText.COPY).performClick()
    assertThat(copies).isEqualTo(1)
  }

  @Test
  fun dopoLaCopiaIlTastoDiceCopiato() {
    show { StepSimulationScreen(state = StepSimulationState.Done(report), copied = true, onRun = {}, onCopy = {}, onBack = {}) }
    composeRule.onNodeWithText(StepSimulationText.COPIED).assertIsDisplayed()
  }

  @Test
  fun unaCosaCheFermaIlPassoSiVedeComeDaControllare() {
    val blocked = report.copy(attention = setOf(StepAttention.AREA_UNAVAILABLE, StepAttention.SPACE_BELOW_MARGIN))
    show { StepSimulationScreen(state = StepSimulationState.Done(blocked), copied = false, onRun = {}, onCopy = {}, onBack = {}) }
    composeRule.onAllNodesWithText(StepSimulationText.CHECK_TITLE).fetchSemanticsNodes().let { assertThat(it).hasSize(2) }
    composeRule.onNodeWithText(StepSimulationText.attentionText(StepAttention.AREA_UNAVAILABLE)).assertIsDisplayed()
  }
}
