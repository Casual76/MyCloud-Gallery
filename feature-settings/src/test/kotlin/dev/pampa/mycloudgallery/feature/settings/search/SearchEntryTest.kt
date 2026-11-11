package dev.pampa.mycloudgallery.feature.settings.search

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** La voce "Ricerca e modelli" delle Impostazioni (Fase 6, P8, §2.6): frasi, tono e tocco. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "it-w412dp-h915dp-xhdpi")
class SearchEntryTest {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private val context: Application get() = ApplicationProvider.getApplicationContext()

  @Test
  fun senzaLegaturaLaFraseDiceATantoServe() {
    val text = SearchEntryTexts.subtitleOf(context, null)
    assertThat(text).contains("modelli")
    assertThat(text).contains("analizza le foto")
  }

  @Test
  fun conLeScelteLaFraseDiceRuoloELetturaDelTesto() {
    assertThat(SearchEntryTexts.subtitleOf(context, SearchEntrySummary(indexer = true, textReadingHere = false, modelMissing = false)))
      .isEqualTo("Analizza le foto · lettura del testo spenta")
    assertThat(SearchEntryTexts.subtitleOf(context, SearchEntrySummary(indexer = false, textReadingHere = true, modelMissing = false)))
      .isEqualTo("Non analizza le foto · lettura del testo attiva")
  }

  @Test
  fun ilTonoDicaQuantoServeAttenzione() {
    assertThat(searchEntryTone(null)).isEqualTo(FluidTone.Primary)
    assertThat(searchEntryTone(SearchEntrySummary(indexer = false, textReadingHere = false, modelMissing = true))).isEqualTo(FluidTone.Warning)
    assertThat(searchEntryTone(SearchEntrySummary(indexer = true, textReadingHere = false, modelMissing = false))).isEqualTo(FluidTone.Info)
    assertThat(searchEntryTone(SearchEntrySummary(indexer = false, textReadingHere = false, modelMissing = false))).isEqualTo(FluidTone.Neutral)
  }

  @Test
  fun laRigaSiTocca_ECon_UnModelloMancanteHaLaPillola() {
    var clicks = 0
    rule.setContent {
      MyCloudGalleryTheme {
        FluidListGroup {
          SearchEntryRow(SearchEntrySummary(indexer = true, textReadingHere = false, modelMissing = true)) { clicks++ }
        }
      }
    }
    rule.onNodeWithText("Ricerca e modelli").assertIsDisplayed()
    rule.onNodeWithText("Manca un modello").assertIsDisplayed()
    rule.onNodeWithText("Ricerca e modelli").performClick()
    assertThat(clicks).isEqualTo(1)
  }

  /** LT-2 / R8: con l'account acceso da un altro dispositivo la voce lo segnala ("Da confermare") invece di dire "lettura del testo spenta". */
  @Test
  fun conLAvvisoDaConfermareLaVoceLoDiceCoseLaPillola() {
    val summary = SearchEntrySummary(indexer = false, textReadingHere = false, modelMissing = false, readingNeedsNotice = true)
    assertThat(SearchEntryTexts.subtitleOf(context, summary)).isEqualTo("Non analizza le foto · lettura del testo da confermare")
    assertThat(searchEntryTone(summary)).isEqualTo(FluidTone.Warning)
    rule.setContent {
      MyCloudGalleryTheme { FluidListGroup { SearchEntryRow(summary) {} } }
    }
    rule.onNodeWithText("Da confermare").assertIsDisplayed()
  }

  @Test
  fun senzaModelloMancanteNonCeLaPillola() {
    rule.setContent {
      MyCloudGalleryTheme { FluidListGroup { SearchEntryRow(null) {} } }
    }
    rule.onNodeWithText("Ricerca e modelli").assertIsDisplayed()
    assertThat(rule.onAllNodesWithTextCount("Manca un modello")).isEqualTo(0)
  }

  private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
    onAllNodesWithText(text).fetchSemanticsNodes().size
}
