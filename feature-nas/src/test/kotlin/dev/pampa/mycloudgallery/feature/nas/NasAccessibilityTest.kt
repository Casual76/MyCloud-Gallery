package dev.pampa.mycloudgallery.feature.nas

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderState
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpaceState
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashState
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.nas.folders.FolderWhere
import dev.pampa.mycloudgallery.feature.nas.folders.FoldersActions
import dev.pampa.mycloudgallery.feature.nas.folders.FoldersUiState
import dev.pampa.mycloudgallery.feature.nas.folders.HiddenFiles
import dev.pampa.mycloudgallery.feature.nas.folders.NasFoldersScreen
import dev.pampa.mycloudgallery.feature.nas.operations.NasOperationsScreen
import dev.pampa.mycloudgallery.feature.nas.operations.operationsUi
import dev.pampa.mycloudgallery.feature.nas.space.NasSpaceScreen
import dev.pampa.mycloudgallery.feature.nas.space.SpaceUi
import dev.pampa.mycloudgallery.feature.nas.trash.NasTrashScreen
import dev.pampa.mycloudgallery.feature.nas.trash.TrashActions
import dev.pampa.mycloudgallery.feature.nas.trash.TrashUi
import dev.pampa.mycloudgallery.feature.nas.trash.key
import org.junit.Test

/**
 * TalkBack (riga P14 del §4.4): ogni cosa che si tocca ha un nome che dice cosa fa; ogni riga si legge come una frase sola (nome,
 * genere, peso, giorni rimasti) e in selezione dice se e' scelta; il cerchio di selezione non e' un secondo bersaglio; le azioni
 * spente si dicono spente; il titolo della pagina e' un'intestazione. Si prova sull'albero semantico, che e' quello che TalkBack legge.
 */
class NasAccessibilityTest : ComposeNasTest() {

  private val areas = FakeNasAreas.defaultAreas()
  private val noop = FoldersActions()

  /** Un peso come lo scrive l'app: lo spazio fra il numero e l'unita' non va a capo. */
  private fun sz(text: String) = text.replace(' ', '\u00A0')

  private fun SemanticsNode.name(): String =
    config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(", ").orEmpty().ifEmpty {
      config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
    }

  private fun clickable(): List<SemanticsNode> =
    composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()

  /** Ogni nodo toccabile ha un nome non vuoto (testo o descrizione). */
  private fun assertEveryTargetHasAName(where: String) {
    // Un nodo senza dimensioni non si vede e TalkBack non ci arriva: conta quello che sta sullo schermo.
    val nodes = clickable().filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
    assertWithMessage("$where: ci sono cose da toccare").that(nodes).isNotEmpty()
    for (node in nodes) assertWithMessage("$where: un bersaglio senza nome ${node.boundsInRoot} ${node.config}").that(node.name()).isNotEmpty()
  }

  private fun folders(selecting: Boolean = false, selection: Set<SharePath> = emptySet(), readOnly: Boolean = false) = FoldersUiState(
    areas = areas,
    where = FolderWhere("test", SharePath.of("Viaggi")),
    listing = NasFolderState.Listing(NasFixtures.tripEntries(), readOnly, null),
    selecting = selecting,
    selection = selection,
    hidden = HiddenFiles.Known(emptySet()),
  )

  @Test
  fun cartelle_OgniRigaSiLegge_ComeUnaFraseSola() {
    show { NasFoldersScreen(folders(), noop, { 0L }, onBack = {}) }
    assertEveryTargetHasAName("Cartelle")
    composeRule.onNodeWithContentDescription("Oslo 2026, cartella, 412 file").assertExists()
    composeRule.onNodeWithContentDescription("DJI_0142.mp4, video, ${sz("2,3 GB")}, 3 ottobre 2026").assertExists()
    composeRule.onNodeWithContentDescription("Mappa del percorso.png, foto, ${sz("2,4 MB")}, 15 settembre 2026").assertExists()
    composeRule.onNodeWithContentDescription("Biglietti aerei.pdf, file, ${sz("301 kB")}, 1 agosto 2026").assertExists()
    // L'indietro e i comandi hanno il loro nome.
    composeRule.onNodeWithContentDescription("Indietro").assertExists()
    composeRule.onNodeWithContentDescription("Nuova cartella").assertExists()
    composeRule.onNodeWithContentDescription("Seleziona").assertExists()
  }

  @Test
  fun cartelle_InSelezioneOgniRigaDiceSeESceltaEIlCerchioNonEUnSecondoBersaglio() {
    val entries = NasFixtures.tripEntries()
    show { NasFoldersScreen(folders(selecting = true, selection = setOf(entries[0].path)), noop, { 0L }, onBack = {}) }
    assertEveryTargetHasAName("Cartelle in selezione")
    val rows = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).fetchSemanticsNodes()
    assertThat(rows).hasSize(entries.size)
    assertThat(rows.count { it.config[SemanticsProperties.Selected] }).isEqualTo(1)
    for (row in rows) {
      assertThat(row.config.getOrNull(SemanticsProperties.StateDescription)).isAnyOf("Selezionato", "Non selezionato")
    }
    // Rinominare vuole un elemento solo: con uno e' acceso, e le azioni sono tre bersagli con il loro nome.
    composeRule.onNodeWithContentDescription("Rinomina").assertExists()
    composeRule.onNodeWithContentDescription("Sposta").assertExists()
    composeRule.onNodeWithContentDescription("Elimina").assertExists()
  }

  @Test
  fun cartelle_UnaAzioneSpentaSiDiceSpenta() {
    val entries = NasFixtures.tripEntries()
    show { NasFoldersScreen(folders(selecting = true, selection = setOf(entries[0].path, entries[1].path)), noop, { 0L }, onBack = {}) }
    composeRule.onNodeWithContentDescription("Rinomina").assertIsNotEnabled()
  }

  @Test
  fun cartelle_InSolaLetturaLeAzioniSonoSpenteElaSpiegazioneSiLegge() {
    show { NasFoldersScreen(folders(readOnly = true), noop, { 0L }, onBack = {}) }
    composeRule.onNodeWithContentDescription("Nuova cartella").assertIsNotEnabled()
    composeRule.onNodeWithContentDescription("Seleziona").assertIsNotEnabled()
    // La spiegazione e' un blocco di testo (titolo e frase insieme), non solo un colore.
    composeRule.onNode(hasTextContaining("non modificare")).assertExists()
  }

  @Test
  fun lIntestazioneDellaPaginaEUnTitolo() {
    show { NasFoldersScreen(folders(), noop, { 0L }, onBack = {}) }
    val headings = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes()
    assertThat(headings).isNotEmpty()
    assertThat(headings.map { it.name() }.first { it.startsWith("Viaggi") }).contains("La mia area")
  }

  private fun hasTextContaining(part: String) = SemanticsMatcher("testo con «$part»") { node ->
    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(part) } == true
  }

  @Test
  fun eliminati_OgniVoceDiceNomeGenerePesoEGiorni() {
    val items = NasFixtures.trashItems()
    show {
      NasTrashScreen(TrashUi(areas = areas, list = NasTrashState.Ready(items, NasFixtures.NOW, true), selecting = true, selection = setOf(items[0].key())), TrashActions(), { 0L }, onBack = {})
    }
    assertEveryTargetHasAName("Eliminati")
    composeRule.onNodeWithContentDescription("IMG_20260921_184233.jpg, ${sz("4,8 MB")}, Restano 29 giorni, Eliminato da questo dispositivo").assertExists()
    composeRule.onNodeWithContentDescription("Vecchie bozze, cartella, 74 file · ${sz("182 MB")}, Restano 11 giorni, Eliminato da questo dispositivo").assertExists()
    // Una voce senza record valido lo dice nella sua frase.
    composeRule.onNode(hasContentDescription("Senza nome", substring = true) and hasContentDescription("Senza record valido", substring = true)).assertExists()
    val rows = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).fetchSemanticsNodes()
    assertThat(rows.count { it.config[SemanticsProperties.Selected] }).isEqualTo(1)
  }

  @Test
  fun spazio_ELOperazioniHannoBersagliConNome() {
    show { NasSpaceScreen(SpaceUi(areas, "test", NasSpaceState.Ready(NasFixtures.space())), {}, {}, onBack = {}, onOpenTrash = {}) }
    assertEveryTargetHasAName("Spazio")
    // Il numero grande si legge con la frase intera, non come "1,8 TB" e poi altro.
    composeRule.onNode(hasContentDescription("liberi su", substring = true)).assertExists()
    composeRule.onNodeWithContentDescription("Ricalcola").assertExists()
  }

  @Test
  fun operazioni_OgniFermaDiceQualeOperazioneFerma() {
    show { NasOperationsScreen(operationsUi(NasFixtures.operations()), onStop = {}, onBack = {}) }
    assertEveryTargetHasAName("Operazioni")
    composeRule.onNodeWithContentDescription("Annulla il resto: Sposto 312 elementi").assertExists()
    composeRule.onNodeWithContentDescription("Annulla il resto: Elimino 40 elementi").assertExists()
  }

  /**
   * Punto 3 delle correzioni dal campo (gruppo B, "Ferma" contro "Riprendi"): il progetto (R9 della 4B) vuole che fermare un'operazione sul NAS la
   * annulli (non c'e' una pausa dell'utente ne' un "Riprendi"), quindi il pulsante lo dice: "Annulla il resto". Niente "Ferma" che promette
   * una ripresa.
   */
  @Test
  fun operazioni_ilPulsanteDiceCheAnnullaIlRestoENonPromettelaRipresa() {
    show { NasOperationsScreen(operationsUi(NasFixtures.operations()), onStop = {}, onBack = {}) }
    composeRule.onAllNodesWithText("Annulla il resto").onFirst().assertExists()
    composeRule.onAllNodesWithText("Quello che è già stato fatto resta fatto", substring = true).onFirst().assertExists()
    composeRule.onAllNodesWithText("Ferma").assertCountEquals(0)
    composeRule.onAllNodesWithText("Riprendi").assertCountEquals(0)
  }

}
