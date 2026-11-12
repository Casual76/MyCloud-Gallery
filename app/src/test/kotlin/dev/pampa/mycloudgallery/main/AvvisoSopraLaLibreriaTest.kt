package dev.pampa.mycloudgallery.main

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.GalleryScaffold
import dev.pampa.mycloudgallery.SHOT_PHONE
import dev.pampa.mycloudgallery.ScaffoldScenes
import dev.pampa.mycloudgallery.core.designsystem.chrome.GalleryTab
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

/**
 * Pacchetto U, giro 2 (UU2-4): mentre l'avviso dell'aggiornamento sta sopra la pillola, il fondo della Libreria cresce della sua altezza. La Libreria
 * si apre in fondo, sulle foto piu' recenti: il conteggio in fondo («… foto, … video») e l'ultima riga devono restare sopra la scheda, non sotto.
 * Una scheda finta alta 160 dp al posto di quella vera (l'altezza vera la mostrano gli screenshot `UAggiornamento*`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-$SHOT_PHONE")
internal class AvvisoSopraLaLibreriaTest {

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

  @Test
  fun ilConteggioInFondoAllaLibreriaStaSopraLAvviso() {
    val scenes = ScaffoldScenes()
    val notice: @Composable (Modifier) -> Unit = { modifier -> Box(modifier.fillMaxWidth().height(NOTICE_HEIGHT.dp).testTag(NOTICE)) }
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) {
        CompositionLocalProvider(LocalTabBarNotice provides notice) { GalleryScaffold(scenes, GalleryTab.Library) }
      }
    }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SETTLE_MS)
    composeRule.waitForIdle()
    val card = composeRule.onNodeWithTag(NOTICE).fetchSemanticsNode().boundsInRoot
    val summary = composeRule.onAllNodes(hasText(" foto", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
    assertWithMessage("il conteggio in fondo alla Libreria").that(summary).isNotEmpty()
    val bottom = summary.maxOf { it.boundsInRoot.bottom }
    assertWithMessage("il fondo del conteggio (%s) sopra la cima dell'avviso (%s)", bottom, card.top).that(bottom).isAtMost(card.top + 1f)
  }

  @Test
  fun lAvvisoCheCompareDopo_laGrigliaInFondoSaleConLaScheda() {
    // Il caso vero: la Libreria e' gia' aperta in fondo, il controllo silenzioso arriva dopo il primo disegno e l'avviso compare. Chi guardava le piu'
    // recenti le vede ancora: la griglia sale con la scheda (non basta allargare il fondo: la lista resterebbe dov'era).
    val scenes = ScaffoldScenes()
    var shown by mutableStateOf(false)
    val notice: @Composable (Modifier) -> Unit = { modifier -> if (shown) Box(modifier.fillMaxWidth().height(NOTICE_HEIGHT.dp).testTag(NOTICE)) }
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = ThemeMode.LIGHT)) {
        CompositionLocalProvider(LocalTabBarNotice provides notice) { GalleryScaffold(scenes, GalleryTab.Library) }
      }
    }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SETTLE_MS)
    composeRule.waitForIdle()
    shown = true
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SETTLE_MS)
    composeRule.waitForIdle()
    val card = composeRule.onNodeWithTag(NOTICE).fetchSemanticsNode().boundsInRoot
    val summary = composeRule.onAllNodes(hasText(" foto", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
    assertWithMessage("il conteggio in fondo alla Libreria").that(summary).isNotEmpty()
    val bottom = summary.maxOf { it.boundsInRoot.bottom }
    assertWithMessage("il fondo del conteggio (%s) sopra la cima dell'avviso (%s)", bottom, card.top).that(bottom).isAtMost(card.top + 1f)
  }

  private companion object {
    const val NOTICE = "avviso-aggiornamento"
    const val NOTICE_HEIGHT = 160
    const val SETTLE_MS = 2_000L
  }
}
