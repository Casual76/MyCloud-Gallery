package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import dev.pampa.mycloudgallery.core.library.testing.NasMix
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.feature.library.grid.RegisterComponentActivity
import dev.pampa.mycloudgallery.feature.library.grid.showThemed
import dev.pampa.mycloudgallery.feature.library.library.testing.syntheticLibraryState
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Il piede della Libreria e i segni di presenza per TalkBack (G2, `docs/grafica-progetto.md` §12; Fase 4 §1.5): lo stato
 * del NAS (`LibraryTabState.nas`) diventa la capsula accanto a quella della rete, con il dettaglio sotto; toccarla apre
 * i dettagli. Ogni cella dice dove sta il media.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class LibraryStatusFooterTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private fun showFooter(nas: LibraryNasStatus, onNasClick: () -> Unit = {}) {
    composeRule.showThemed { LibraryStatusBlock(nas = nas, statusFooter = { Text("Rete non riconosciuta") }, onNasClick = onNasClick) }
    composeRule.waitForIdle()
  }

  @Test
  fun laPrimaScansioneDiceAChePuntoE() {
    showFooter(LibraryNasStatus.FirstScan(done = 7_412, total = 20_000, etaMillis = 4 * 60_000L))
    composeRule.onNodeWithText("Sto leggendo il NAS…").assertExists()
    composeRule.onNodeWithText("7.412 di 20.000 · circa 4 min").assertExists()
    composeRule.onNodeWithText("Rete non riconosciuta").assertExists()
  }

  @Test
  fun senzaTotaleDiceQuantiNeHaLetti() {
    showFooter(LibraryNasStatus.FirstScan(done = 1, total = null, etaMillis = null))
    composeRule.onNodeWithText("1 file letto finora").assertExists()
  }

  @Test
  fun iLavoriFermiSiDiconoESiAprono() {
    var opened = 0
    showFooter(LibraryNasStatus.Frozen(FreezeReason.VANISHED, sinceNasMillis = 0L)) { opened++ }
    composeRule.onNodeWithText("Lavori fermi: cambiamenti anomali").assertExists().performClick()
    composeRule.onNodeWithText("spariti molti file", substring = true).assertExists()
    assertThat(opened).isEqualTo(1)
  }

  @Test
  fun leAreeNonRaggiungibiliDiconoCheCosaSiVede() {
    showFooter(LibraryNasStatus.AreasUnavailable(setOf(NasArea.COMMON)))
    composeRule.onNodeWithText("Area comune non raggiungibile").assertExists()
  }

  @Test
  fun lAllineamentoDiceIByte() {
    showFooter(LibraryNasStatus.Aligning(bytesDone = 640_000_000L, bytesTotal = 1_200_000_000L, etaMillis = null))
    composeRule.onNodeWithText("640 MB di 1,2 GB").assertExists()
  }

  @Test
  fun senzaNasIlPiedeDiceSoloLaRete() {
    showFooter(LibraryNasStatus.Idle)
    composeRule.onNodeWithText("Rete non riconosciuta").assertExists()
    composeRule.onAllNodesWithText("NAS", substring = true).assertCountEqualsZero()
  }

  /** Nella Libreria mista ogni cella dice a TalkBack dove sta il media, dopo il resto della descrizione. */
  @Test
  fun leCelleDiconoDoveStaIlMedia() {
    val state = syntheticLibraryState(count = 400, mix = NasMix.BUDGET)
    composeRule.showThemed {
      LibraryTab(contentPadding = PaddingValues(bottom = 96.dp), backdropOut = {}, onOpenViewer = {}, onOpenSettings = {}, statusFooter = {}, state = state)
    }
    composeRule.waitForIdle()
    val phrases = listOf("solo sul telefono", "solo sul NAS", "sul telefono e sul NAS", "al sicuro sul NAS")
    val described = phrases.count { composeRule.onAllNodesWithContentDescription(", $it", substring = true).fetchSemanticsNodes().isNotEmpty() }
    assertThat(described).isAtLeast(2)
  }

  private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEqualsZero() {
    assertThat(fetchSemanticsNodes()).isEmpty()
  }
}
