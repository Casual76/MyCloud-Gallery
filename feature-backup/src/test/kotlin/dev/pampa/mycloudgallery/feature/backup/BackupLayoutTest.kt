package dev.pampa.mycloudgallery.feature.backup

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.feature.backup.folder.FolderActions
import dev.pampa.mycloudgallery.feature.backup.folder.DeviceFolderScreen
import dev.pampa.mycloudgallery.feature.backup.folder.FolderUi
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageActions
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageScreen
import dev.pampa.mycloudgallery.feature.backup.page.BackupPageUi
import dev.pampa.mycloudgallery.feature.backup.settings.BackupSettingsScreen
import dev.pampa.mycloudgallery.feature.backup.settings.FoldersState
import dev.pampa.mycloudgallery.feature.backup.settings.SettingsActions
import dev.pampa.mycloudgallery.feature.backup.settings.SettingsUi
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.feature.backup.testing.BackupSamples
import org.junit.Test

/**
 * Il **test dei bordi** delle pagine del backup (`docs/grafica-progetto.md` §3, P2 e P3; riga P5 del §4.4): sul telefono (412 dp), con il
 * testo a 1,3x e sul tablet,
 *  - ogni controllo toccabile e' almeno 48 x 48 dp e sta dentro lo schermo;
 *  - nessun controllo ne tocca un altro (salvo quello che ne contiene un altro: un tasto nella sua scheda);
 *  - sul tablet la pagina non si allarga oltre la colonna leggibile.
 * Il vetro qui non si vede (Robolectric non sfoca): la leggibilita' della chrome e' in `GalleryLegibilityTest` e nelle misure G2 §12.6.
 */
class BackupLayoutTest : ComposeBackupTallTest() {

  private val statuses = listOf<BackupStatus>(
    BackupStatus.Running(BackupKind.PHOTOS, 1_240, 6_658, 2_100_000_000, 48_000_000_000, 4_800_000, BackupTrigger.FIRST_BACKUP),
    BackupStatus.Problem(BackupProblemCode.VERIFY_FAILED, 12),
    BackupStatus.Problem(BackupProblemCode.NAS_UNREACHABLE, 0),
    BackupStatus.UpToDate(6_658, 1_792_119_120_000),
  )

  private fun Rect.overlaps(other: Rect): Boolean = left < other.right - 1f && other.left < right - 1f && top < other.bottom - 1f && other.top < bottom - 1f

  private fun Rect.contains(other: Rect): Boolean = left <= other.left + 1f && top <= other.top + 1f && right >= other.right - 1f && bottom >= other.bottom - 1f

  /** Ogni nodo toccabile e' almeno 48 dp, dentro lo schermo, e nessuna coppia si sovrappone se non per contenimento. */
  private fun assertBounds(where: String) {
    val min = with(composeRule.density) { GalleryDimens.MinTouchTarget.toPx() }
    val root = composeRule.onAllNodes(SemanticsMatcher("radice") { it.isRoot }).fetchSemanticsNodes().first().boundsInRoot
    val nodes = clickable()
    assertWithMessage("$where: ci sono cose da toccare").that(nodes).isNotEmpty()
    for (node in nodes) {
      val b = node.boundsInRoot
      assertWithMessage("$where: ${node.name()} $b alto almeno 48 dp").that(b.height).isAtLeast(min - 1f)
      assertWithMessage("$where: ${node.name()} $b largo almeno 48 dp").that(b.width).isAtLeast(min - 1f)
      assertWithMessage("$where: ${node.name()} $b dentro lo schermo $root").that(b.left >= root.left - 1f && b.right <= root.right + 1f).isTrue()
    }
    for (i in nodes.indices) {
      for (j in i + 1 until nodes.size) {
        val a = nodes[i].boundsInRoot
        val b = nodes[j].boundsInRoot
        if (a.contains(b) || b.contains(a)) continue
        assertWithMessage("$where: ${nodes[i].name()} $a e ${nodes[j].name()} $b si sovrappongono").that(a.overlaps(b)).isFalse()
      }
    }
  }

  private val pageActions = BackupPageActions()

  @Test
  fun laPaginaBackupSuTelefonoAUnoEATreDecimi() {
    val ui = mutableStateOf(BackupPageUi(statuses.first(), BackupSamples.sources, BackupSamples.deviceFolder, sheetOpen = false))
    show { BackupPageScreen(state = ui.value, actions = pageActions, onBack = {}) }
    for (fontScale in listOf(1f, LARGE_TEXT)) {
      setFontScale(fontScale)
      for (status in statuses) {
        composeRule.runOnIdle { ui.value = ui.value.copy(status = status) }
        composeRule.waitForIdle()
        assertBounds("pagina ${fontScale}x, ${status::class.simpleName}")
      }
    }
  }

  @Test
  fun leImpostazioniSuTelefonoAUnoEATreDecimi() {
    val ui = SettingsUi(
      folders = FoldersState.Ready(BackupSamples.phoneFolders),
      chosen = BackupSources.DEFAULT,
      deviceFolder = null,
      protectNewPhotos = true,
      rulesAvailable = true,
      editable = true,
    )
    show { BackupSettingsScreen(state = ui, actions = SettingsActions(), onBack = {}) }
    assertBounds("impostazioni 1x")
    setFontScale(LARGE_TEXT)
    assertBounds("impostazioni 1,3x")
  }

  @Test
  fun laScelta_DellaCartella_SuTelefonoAUnoEATreDecimi() {
    val ui = mutableStateOf<FolderUi>(FolderUi.Choose(BackupSamples.deviceFolder, BackupSamples.existingFolderProposal.existing, BackupSamples.existingFolderProposal.alternative))
    show { DeviceFolderScreen(state = ui.value, actions = FolderActions(), onBack = {}) }
    assertBounds("cartella 1x")
    setFontScale(LARGE_TEXT)
    assertBounds("cartella 1,3x")
    composeRule.onNodeWithText("Continua lì").assertExists()
  }

  @Test
  fun ogniControlloHaUnNomeCheDiceCosaFa() {
    show { BackupPageScreen(state = BackupPageUi(statuses[1], BackupSamples.sources, null, false), actions = pageActions, onBack = {}) }
    val nodes = clickable()
    for (node in nodes) assertWithMessage("un controllo senza nome ${node.boundsInRoot}").that(node.name()).isNotEmpty()
    // Il tasto indietro della chrome e il comando delle impostazioni hanno una descrizione, non solo un'icona.
    val names = nodes.map { it.name() }
    assertThat(names).contains("Indietro")
    assertThat(names).contains("Impostazioni del backup")
  }

  @Test
  fun leIntestazioniDiGruppoSonoIntestazioniPerTalkBack() {
    show { BackupPageScreen(state = BackupPageUi(statuses[3], BackupSamples.sources, null, false), actions = pageActions, onBack = {}) }
    val headings = composeRule.onAllNodes(SemanticsMatcher("intestazione") { it.config.contains(SemanticsProperties.Heading) }).fetchSemanticsNodes()
    val titles = headings.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }
    assertThat(titles).containsAtLeast("Cosa si salva", "Sul NAS")
  }

  @Test
  fun laSchedaDiStatoSiLeggeComeUnaFraseSola() {
    show { BackupPageScreen(state = BackupPageUi(statuses[1], BackupSamples.sources, null, false), actions = pageActions, onBack = {}) }
    // I3: la testata dice il problema con un'intestazione sola (TalkBack ci salta con il gesto delle intestazioni); il perche' e' un tasto a parte,
    // «Perche'», con il suo stato aperto o chiuso. Giro 1 (CS-5): la «i» della galleria dice a TalkBack anche il perche' («Perché. ...»).
    val merged = composeRule.onAllNodes(hasText("12 copie non integre")).fetchSemanticsNodes()
    assertThat(merged).hasSize(1)
    assertThat(merged.single().config.contains(SemanticsProperties.Heading)).isTrue()
    val why = composeRule.onAllNodes(SemanticsMatcher("perche'") { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith("Perché") } == true })
      .fetchSemanticsNodes()
    assertThat(why).hasSize(1)
    assertThat(why.single().config.getOrNull(SemanticsProperties.StateDescription)).isEqualTo("Chiuso")
  }

  @Test
  fun leRigheDelleSorgentiSiLeggonoConNomeStatoENumeri() {
    show { BackupPageScreen(state = BackupPageUi(statuses[3], BackupSamples.sources, null, false), actions = pageActions, onBack = {}) }
    val spoken = composeRule.onAllNodes(SemanticsMatcher("descrizione") { it.config.getOrNull(SemanticsProperties.ContentDescription) != null })
      .fetchSemanticsNodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
    // I3: ogni copertina si legge con nome, stato e il numero che conta, in una frase.
    assertThat(spoken).contains("Fotocamera, Al sicuro. 1.250 al sicuro")
    assertThat(spoken).contains("Screenshot, In corso. 12 da salvare")
    assertThat(spoken).contains("Telegram, Non salvata. 224 foto e video, non si salva")
  }
}

/** Lo stesso sul tablet (1280 dp, orizzontale): la pagina resta nella colonna leggibile e i bordi non cambiano. */
class BackupTabletLayoutTest : ComposeBackupTallTabletTest() {

  @Test
  fun laPaginaBackupSulTabletRestaNellaColonnaLeggibile() {
    val ui = BackupPageUi(
      BackupStatus.Running(BackupKind.VIDEOS, 3, 40, 1_000_000, 9_000_000_000, null, BackupTrigger.USER_NOW),
      BackupSamples.sources, BackupSamples.deviceFolder, sheetOpen = false,
    )
    show { BackupPageScreen(state = ui, actions = BackupPageActions(), onBack = {}) }
    val min = with(composeRule.density) { GalleryDimens.MinTouchTarget.toPx() }
    val maxWidth = with(composeRule.density) { (640 + 2 * 16).dp.toPx() }
    val nodes = clickable()
    assertThat(nodes).isNotEmpty()
    val stop = nodes.first { it.name() == "Ferma" }
    assertThat(stop.boundsInRoot.height).isAtLeast(min - 1f)
    assertThat(stop.boundsInRoot.width).isAtMost(maxWidth)
    // Il tasto "Ferma" non e' a tutta larghezza di un tablet.
    assertThat(stop.boundsInRoot.width).isLessThan(composeRule.onAllNodes(SemanticsMatcher("radice") { it.isRoot }).fetchSemanticsNodes().first().boundsInRoot.width * 0.6f)
  }
}
