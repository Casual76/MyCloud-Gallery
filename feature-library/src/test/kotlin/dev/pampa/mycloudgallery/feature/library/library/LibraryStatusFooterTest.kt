package dev.pampa.mycloudgallery.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LibraryBackupStatus
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
 * Lo stato del NAS e i segni di presenza per TalkBack (G2, `docs/grafica-progetto.md` §12; Fase 4 §1.5). Dalla beta (B2) lo stato del
 * NAS non e' piu' una voce in fondo alla Libreria: lo dice il pop-up della nuvola in alto, con le stesse frasi; "Apri la Diagnostica"
 * porta ai dettagli ("Ho controllato, continua"). Ogni cella dice dove sta il media.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class LibraryStatusFooterTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private val nasState = mutableStateOf<LibraryNasStatus>(LibraryNasStatus.Idle)
  private var nasClicks = 0
  private var started = false

  private fun showPanel(nas: LibraryNasStatus) {
    if (!started) {
      started = true
      nasState.value = nas
      composeRule.showThemed {
        val cloud = libraryCloud(null, nasState.value, LibraryBackupStatus.Idle)
        if (cloud != null) {
          LibraryCloudPanel(
            cloud = cloud,
            lastSyncNasMillis = null,
            commands = NoLibraryCommands,
            actions = LibraryCloudActions(onNetwork = {}, onNas = { nasClicks++ }, onBackup = {}),
            close = {},
          )
        }
      }
    } else {
      composeRule.runOnIdle { nasState.value = nas }
    }
    composeRule.waitForIdle()
  }

  @Test
  fun laPrimaScansioneDiceAChePuntoE() {
    showPanel(LibraryNasStatus.FirstScan(done = 7_412, total = 20_000, etaMillis = 4 * 60_000L))
    // I4: l'anello e il conto a schermo; la frase intera per TalkBack.
    composeRule.onNodeWithText("7.412 / 20.000", useUnmergedTree = true).assertExists()
    composeRule.onNodeWithText("circa 4 min", useUnmergedTree = true).assertExists()
    composeRule.onNodeWithContentDescription("Sto leggendo il NAS…. 7.412 di 20.000 · circa 4 min").assertExists()
    composeRule.onNodeWithText("Sincronizzazione in corso").assertExists()
  }

  @Test
  fun senzaTotaleDiceQuantiNeHaLetti() {
    showPanel(LibraryNasStatus.FirstScan(done = 1, total = null, etaMillis = null))
    composeRule.onNodeWithText("1 file letto finora").assertExists()
  }

  @Test
  fun iLavoriFermiSiDiconoESiAprono() {
    showPanel(LibraryNasStatus.Frozen(FreezeReason.VANISHED, sinceNasMillis = 0L))
    composeRule.onNodeWithText("Lavori fermi: cambiamenti anomali").assertExists().performClick()
    composeRule.onNodeWithText("spariti molti file", substring = true).assertExists()
    composeRule.onNodeWithText("Serve il tuo intervento").assertExists()
    composeRule.onNodeWithText("Apri la Diagnostica").performClick()
    assertThat(nasClicks).isEqualTo(1)
  }

  @Test
  fun leAreeNonRaggiungibiliDiconoCheCosaSiVede() {
    showPanel(LibraryNasStatus.AreasUnavailable(setOf(NasArea.COMMON)))
    composeRule.onNodeWithText("Area comune non raggiungibile").assertExists()
    composeRule.onNodeWithText("C’è qualcosa da guardare").assertExists()
  }

  @Test
  fun lAllineamentoDiceIByte() {
    showPanel(LibraryNasStatus.Aligning(bytesDone = 640_000_000L, bytesTotal = 1_200_000_000L, etaMillis = null))
    composeRule.onNodeWithText("640 MB / 1,2 GB", useUnmergedTree = true).assertExists()
    composeRule.onNodeWithContentDescription("640 MB di 1,2 GB", substring = true).assertExists()
  }

  @Test
  fun ilNasAggiornatoNonEUnaVoceMaIlTitolo() {
    showPanel(LibraryNasStatus.UpToDate(atNasMillis = 0L))
    composeRule.onNodeWithText("NAS aggiornato").assertExists()
    assertThat(composeRule.onAllNodesWithText("Apri la Diagnostica").fetchSemanticsNodes()).isEmpty()
  }

  /** B2: in fondo alla Libreria le tre scritte (la rete, il NAS, il backup) non ci sono piu', qualunque cosa dicano. */
  @Test
  fun inFondoAllaLibreriaNonCiSonoPiuLeTreScritte() {
    val state = syntheticLibraryState(
      count = 60,
      nas = LibraryNasStatus.Frozen(FreezeReason.VANISHED, sinceNasMillis = 0L),
      backup = LibraryBackupStatus.AllSafe(atNasMillis = null),
    )
    val network = LibraryNetworkStatus(severity = LibraryNetworkSeverity.Attention, label = "Rete non riconosciuta")
    composeRule.showThemed {
      LibraryTab(contentPadding = PaddingValues(bottom = 96.dp), backdropOut = {}, onOpenViewer = {}, onOpenSettings = {}, state = state, network = network)
    }
    composeRule.waitForIdle()
    for (phrase in listOf("Lavori fermi: cambiamenti anomali", "Tutto al sicuro", "Rete non riconosciuta")) {
      assertThat(composeRule.onAllNodesWithText(phrase).fetchSemanticsNodes()).isEmpty()
    }
    // La nuvola le dice, con il problema per TalkBack.
    composeRule.onAllNodesWithContentDescription("Stato della sincronizzazione: Serve il tuo intervento", substring = true).fetchSemanticsNodes().let {
      assertThat(it).hasSize(1)
    }
  }

  /** Nella Libreria mista ogni cella dice a TalkBack dove sta il media, dopo il resto della descrizione. */
  @Test
  fun leCelleDiconoDoveStaIlMedia() {
    val state = syntheticLibraryState(count = 400, mix = NasMix.BUDGET)
    composeRule.showThemed {
      LibraryTab(contentPadding = PaddingValues(bottom = 96.dp), backdropOut = {}, onOpenViewer = {}, onOpenSettings = {}, state = state)
    }
    composeRule.waitForIdle()
    val phrases = listOf("solo sul telefono", "solo sul NAS", "sul telefono e sul NAS", "al sicuro sul NAS")
    val described = phrases.count { composeRule.onAllNodesWithContentDescription(", $it", substring = true).fetchSemanticsNodes().isNotEmpty() }
    assertThat(described).isAtLeast(2)
  }
}
