package dev.pampa.mycloudgallery.feature.viewer

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B4 (G3): il vetro del viewer che compare e sparisce (il badge, gli avvisi, le note) entra ed esce con i token di `FluidMotion`, non di colpo: all'uscita il contenuto resta, con
 * l'ultimo valore, per il tempo della dissolvenza, e poi non c'e' piu'.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ViewerGlassPresenceTest {

  @get:Rule
  val composeRule = createComposeRule()

  private var notice by mutableStateOf<String?>(null)

  @Test
  fun entraSfumandoEEsceSfumando_conIlSuoUltimoValore() {
    composeRule.setContent { MyCloudGalleryTheme { ViewerGlassPresence(value = notice) { Text(it) } } }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Pronto").assertDoesNotExist()

    composeRule.runOnIdle { notice = "Pronto" }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Pronto").assertExists()

    // L'avviso se ne va: per un attimo e' ancora li' (esce sfumando, col suo testo), poi non c'e' piu'. Il tempo lo guida il test: la scrittura si applica a mano e si avanza di poco.
    composeRule.mainClock.autoAdvance = false
    composeRule.runOnUiThread {
      notice = null
      Snapshot.sendApplyNotifications()
    }
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.mainClock.advanceTimeBy(30)
    composeRule.onNodeWithText("Pronto").assertExists()
    composeRule.mainClock.advanceTimeBy(800)
    composeRule.onNodeWithText("Pronto").assertDoesNotExist()
  }

  @Test
  fun unAvvisoCheCambiaSostituisceIlTesto() {
    composeRule.setContent { MyCloudGalleryTheme { ViewerGlassPresence(value = notice) { Text(it) } } }
    notice = "Uno"
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Uno").assertExists()
    notice = "Due"
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Due").assertExists()
    composeRule.onNodeWithText("Uno").assertDoesNotExist()
  }
}
