package dev.pampa.mycloudgallery.debugtools.eval.albums

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
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
import dev.pampa.mycloudgallery.debugtools.eval.albums.AlbumsTestRig.Companion.day
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
 * Lo strumento composto davvero (Robolectric): la schermata senza porta dice che gli album non sono collegati; con la porta (una Libreria sintetica) mostra i
 * viaggi dell'utente, apre un viaggio, rifiuta la corsa senza foto segnate alla cieca e, nella pagina della cieca, mostra una cella per foto e la segna al tocco.
 * Niente foto vere: la Libreria e' quella del banco di prova.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class AlbumsEvalScreenTest {

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
    AlbumsEvalEnvironmentHolder.current = null
  }

  private fun show() {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) { AlbumsEvalScreen(onBack = {}) }
    }
    composeRule.waitForIdle()
  }

  @Test
  fun senzaLaPortaDiceCheGliAlbumNonSonoCollegati() {
    AlbumsEvalEnvironmentHolder.current = null
    show()
    composeRule.onNodeWithText(AlbumsEvalStrings.NOT_BOUND, substring = true).assertExists()
  }

  @Test
  fun laSchermataMostraIViaggiELaPaginaDellaCieca() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val rig = AlbumsTestRig.osloTrip()
    AlbumsEvalEnvironmentHolder.current = rig
    // Un viaggio gia' scritto, come se l'utente avesse chiuso e riaperto l'app.
    val saved = AlbumsEvalSession(rig, AlbumsEvalStore.of(app)).also { it.load() }
    val trip = saved.addTrip("Oslo marzo 2025", day(3, 14), day(3, 16))
    show()

    composeRule.onNodeWithText(AlbumsEvalStrings.SECTION_HOW, ignoreCase = true).assertExists()
    composeRule.onNodeWithText("Oslo marzo 2025").assertExists()
    composeRule.onNodeWithText("2025-03-14 / 2025-03-16: 3 giorni, 0 foto segnate").assertExists()

    // Il viaggio: i passi del metodo a pozzo.
    composeRule.onNodeWithText("Oslo marzo 2025").performClick()
    composeRule.onNodeWithText(AlbumsEvalStrings.TRIP_BLIND_TITLE).assertExists()
    composeRule.onNodeWithText(AlbumsEvalStrings.TRIP_MISSING_TITLE).assertExists()

    // Senza foto segnate alla cieca la corsa non parte, e lo dice.
    composeRule.onNodeWithText(AlbumsEvalStrings.TRIP_RUN_TITLE).performClick()
    composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(AlbumsEvalStrings.BLIND_FIRST).fetchSemanticsNodes().isNotEmpty() }

    // La pagina della cieca: una cella per foto dei giorni del viaggio (12 di Oslo, lo screenshot e il familiare), da giudicare; un tocco la segna.
    composeRule.onNodeWithText(AlbumsEvalStrings.TRIP_BLIND_TITLE).performClick()
    composeRule.waitUntil(5_000) { composeRule.onAllNodesWithContentDescription("2025-03-14, ${AlbumsEvalStrings.STATE_NONE}").fetchSemanticsNodes().isNotEmpty() }
    composeRule.onAllNodesWithContentDescription("2025-03-14, ${AlbumsEvalStrings.STATE_NONE}").assertCountEquals(4)
    composeRule.onAllNodesWithContentDescription("2025-03-14, ${AlbumsEvalStrings.STATE_NONE}")[0].performClick()
    composeRule.waitForIdle()
    composeRule.onAllNodesWithContentDescription("2025-03-14, ${AlbumsEvalStrings.STATE_RIGHT}").assertCountEquals(1)
    assertThat(AlbumsEvalSession(rig, AlbumsEvalStore.of(app)).also { it.load() }.labels.value.inTripOf(trip.id)).hasSize(1)

    // "Tutto il giorno" li segna tutti e quattro.
    composeRule.onAllNodesWithText(AlbumsEvalStrings.ALL_DAY)[0].performClick()
    composeRule.waitForIdle()
    composeRule.onAllNodesWithContentDescription("2025-03-14, ${AlbumsEvalStrings.STATE_RIGHT}").assertCountEquals(4)
    assertThat(composeRule.onAllNodes(hasContentDescription("2025-03-15, ${AlbumsEvalStrings.STATE_NONE}")).fetchSemanticsNodes()).isNotEmpty()
  }
}
