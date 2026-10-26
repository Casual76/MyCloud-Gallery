package dev.pampa.mycloudgallery.feature.nas

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderEntry
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderState
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.nas.folders.FolderWhere
import dev.pampa.mycloudgallery.feature.nas.folders.FoldersActions
import dev.pampa.mycloudgallery.feature.nas.folders.FoldersUiState
import dev.pampa.mycloudgallery.feature.nas.folders.HiddenFiles
import dev.pampa.mycloudgallery.feature.nas.folders.NasFoldersScreen
import org.junit.Test

/**
 * Fase 7 (P9, D23, I8 della 4B): la pagina di Cartelle NAS con file nascosti nella Libreria. Una riga "N elementi nascosti" **senza nessun nome**, che
 * TalkBack legge intera; niente nome dei nascosti da nessuna parte nell'albero (nemmeno nelle descrizioni); fallire chiusi se non si sa; telefono e
 * testo a 1,3x. Il vetro non si vede in Robolectric: gli screenshot sono in `NasHiddenScreenshotTest`.
 */
class NasHiddenRowScreenTest : ComposeNasTest() {

  private val areas = FakeNasAreas.defaultAreas()
  private val entries = NasFixtures.tripEntries()
  private val secret1 = entries.first { it.name == "Biglietti aerei.pdf" }
  private val secret2 = entries.first { it.name == "DJI_0142.mp4" }

  private fun page(hidden: HiddenFiles, listing: List<NasFolderEntry> = entries) = FoldersUiState(
    areas = areas,
    where = FolderWhere("test", SharePath.of("Viaggi")),
    listing = NasFolderState.Listing(listing, readOnly = false, cachedAt = null),
    hidden = hidden,
  )

  private fun showPage(state: FoldersUiState, fontScale: Float? = null) =
    show(fontScale = fontScale) { NasFoldersScreen(state = state, actions = FoldersActions(), monotonicMs = { 0L }, onBack = {}) }

  /** Tutto il testo e tutte le descrizioni dell'albero, anche non unite: cio' che legge TalkBack e cio' che vede UI Automator. */
  private fun everythingSaid(): String =
    composeRule.onAllNodes(SemanticsMatcher("tutti") { true }, useUnmergedTree = true).fetchSemanticsNodes().joinToString("\n") { node ->
      val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { t -> t.text }.orEmpty()
      val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()
      "$text|$description"
    }

  @Test
  fun laRigaDiceQuantiSonoENonNominaNessuno() {
    showPage(page(HiddenFiles.Known(setOf(secret1.path, secret2.path))))

    composeRule.onNodeWithText("2 elementi nascosti").assertExists()
    composeRule.onNodeWithText("Sono nascosti nella Libreria: qui non se ne mostra il nome.").assertExists()
    val said = everythingSaid()
    assertThat(said).doesNotContain("Biglietti aerei")
    assertThat(said).doesNotContain("DJI_0142")
    // Gli altri file si vedono come sempre.
    composeRule.onNodeWithText("Mappa del percorso.png").assertExists()
    composeRule.onNodeWithText("Oslo 2026").assertExists()
  }

  @Test
  fun unSoloNascostoStaAlSingolare() {
    showPage(page(HiddenFiles.Known(setOf(secret1.path))))

    composeRule.onNodeWithText("1 elemento nascosto").assertExists()
  }

  @Test
  fun seNonCeNeSonoLaRigaNonCe() {
    showPage(page(HiddenFiles.Known(emptySet())))

    composeRule.onAllNodesWithText("nascost", substring = true).assertCountEquals(0)
  }

  @Test
  fun laRigaELettaDaTalkBackInteraENonERispondeATocchi() {
    showPage(page(HiddenFiles.Known(setOf(secret1.path, secret2.path))))

    val row = composeRule.onNode(hasContentDescription("2 elementi nascosti. Sono nascosti nella Libreria: qui non se ne mostra il nome.")).fetchSemanticsNode()
    assertThat(row.config.getOrNull(SemanticsActions.OnClick)).isNull()
  }

  @Test
  fun fincheNonSiSaNessunFileSiElenca() {
    showPage(page(HiddenFiles.Checking))

    composeRule.onNodeWithText("Oslo 2026").assertExists()
    composeRule.onAllNodesWithText("Mappa del percorso.png").assertCountEquals(0)
    composeRule.onAllNodesWithText("Biglietti aerei.pdf").assertCountEquals(0)
  }

  @Test
  fun seNonSiRiesceASaperloLaPaginaDiceDiNonElencareIFile() {
    showPage(page(HiddenFiles.Unknown))

    composeRule.onNodeWithText("Non riesco a controllare i nascosti").assertExists()
    composeRule.onAllNodesWithText("Biglietti aerei.pdf").assertCountEquals(0)
    composeRule.onAllNodesWithText("Mappa del percorso.png").assertCountEquals(0)
    composeRule.onNodeWithText("Oslo 2026").assertExists()
  }

  @Test
  fun seTuttiIFileSonoNascostiNonSiDiceCartellaVuota() {
    val onlyFiles = entries.filter { !it.isFolder }
    showPage(page(HiddenFiles.Known(onlyFiles.map { it.path }.toSet()), listing = onlyFiles))

    composeRule.onNodeWithText("${onlyFiles.size} elementi nascosti").assertExists()
    composeRule.onAllNodesWithText("Cartella vuota").assertCountEquals(0)
  }

  @Test
  fun conIlTestoAUnVirgolaTreLaRigaRestaLeggibile() {
    showPage(page(HiddenFiles.Known(setOf(secret1.path, secret2.path))), fontScale = LARGE_TEXT)

    composeRule.onNodeWithText("2 elementi nascosti").assertExists()
    composeRule.onNodeWithText("Sono nascosti nella Libreria: qui non se ne mostra il nome.").assertExists()
  }
}
