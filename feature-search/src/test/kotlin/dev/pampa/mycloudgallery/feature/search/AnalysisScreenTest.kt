package dev.pampa.mycloudgallery.feature.search

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText

import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisFlow
import dev.pampa.mycloudgallery.feature.search.models.AnalysisHandlers
import dev.pampa.mycloudgallery.feature.search.models.AnalysisQuietTag
import dev.pampa.mycloudgallery.feature.search.models.AnalysisRecentTag
import dev.pampa.mycloudgallery.feature.search.models.AnalysisScreen
import dev.pampa.mycloudgallery.feature.search.models.AnalysisShownTag
import dev.pampa.mycloudgallery.feature.search.models.AnalysisStatusTag
import dev.pampa.mycloudgallery.feature.search.models.CodeAction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pacchetto X: la pagina "Analisi delle foto" — lo stato si vede e le anteprime scorrono (quella di adesso, le appena fatte con il
 * segno, le prossime); un media non mostrabile e' una tessera neutra. In pausa dice il motivo vero e fa partire con un tasto solo.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, qualifiers = "it-w360dp-h2200dp-xhdpi")
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
    // La riga di stato si dice in una frase sola (TalkBack): la si cerca come descrizione.
    rule.onAllNodesWithContentDescription("Sta analizzando le foto adesso", substring = true).onFirst().assertIsDisplayed()
    rule.onNodeWithText("Adesso", ignoreCase = true, useUnmergedTree = true).assertExists()
    rule.onNodeWithText("Appena fatte", ignoreCase = true, useUnmergedTree = true).assertExists()
    rule.onNodeWithText("Prossime", ignoreCase = true, useUnmergedTree = true).assertExists()
    // Le anteprime composte a vista (quella di adesso e le prime delle file); la neutra non e' un'anteprima.
    assertThat(rule.onAllNodesWithTag(AnalysisShownTag).fetchSemanticsNodes()).isNotEmpty()
    assertThat(rule.onAllNodesWithTag(AnalysisQuietTag).fetchSemanticsNodes()).hasSize(1)
    rule.onNodeWithTag(AnalysisRecentTag).assertExists()
  }

  @Test
  fun inPausaDiceIlMotivoEFaPartire() {
    val handlers = Recording()
    rule.setSearchContent {
      AnalysisScreen(AnalysisScenes.PAUSED, AnalysisFlow.EMPTY, AnalysisScenes.thumbnails, handlers, onBack = {})
    }
    rule.onNodeWithText("In pausa · telefono caldo").assertIsDisplayed()
    rule.onNodeWithText("Indicizza ora").performClick()
    assertThat(handlers.now).isEqualTo(1)
  }

  @Test
  fun chiNonAnalizzaNonHaTasti() {
    rule.setSearchContent {
      AnalysisScreen(LibraryAnalysisStatus.NotHere(ready = 10, total = 12), AnalysisFlow.EMPTY, AnalysisScenes.thumbnails, Recording(), onBack = {})
    }
    rule.onAllNodesWithContentDescription("le analizza un altro dispositivo", substring = true).onFirst().assertIsDisplayed()
    assertThat(rule.onAllNodesWithText("Indicizza ora").fetchSemanticsNodes()).isEmpty()
  }
}
