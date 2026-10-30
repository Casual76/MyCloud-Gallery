package dev.pampa.mycloudgallery.feature.viewer

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.viewer.info.InfoSectionModel
import dev.pampa.mycloudgallery.feature.viewer.info.InfoSections
import dev.pampa.mycloudgallery.feature.viewer.info.InfoSheet
import dev.pampa.mycloudgallery.feature.viewer.info.InfoSidePanel
import dev.pampa.mycloudgallery.feature.viewer.info.rememberPeoplePanel
import dev.pampa.mycloudgallery.feature.viewer.info.sectionsWithPeople
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceAction
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceChip
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceOutcome
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceRef
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerPeople
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerPersonChoice
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La riga "Persone" delle informazioni del viewer (Fase 8, P9; §1.5 `ViewerFaceChip`, §2.6 "Info del viewer", D24, D25, D28, E-F3, E-F21, F99): montata come la
 * monta il pannello (`rememberPeoplePanel`), con un `ViewerPeople` finto. Prova i chip (nome o "Chi e'?"), i volti non riconoscibili, il tocco, la pressione
 * lunga, i fogli ("Chi e'?", il menu, la conferma di "Ignora questo volto"), gli esiti che non vanno, TalkBack (frase e azioni personalizzate), i bersagli da
 * 48 dp, il testo a 1,3x e gli screenshot in `docs/screenshots/8-P9/` (chiaro e scuro, telefono e tablet). **Nessun volto vero**: i ritagli sono sagome
 * astratte; i nomi sono inventati.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE)
class ViewerPeopleInfoTest {

  val composeRule = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private val photo = MediaId.random()
  private val anna = PersonId.random()
  private val marco = PersonId.random()
  private val faceAnna = ViewerFaceRef("anna-face")
  private val faceMarco = ViewerFaceRef("marco-face")
  private val faceUnknown = ViewerFaceRef("unknown-face")

  private fun people(vararg chips: ViewerFaceChip) = FakeViewerPeople(chips.toList())

  private fun named() = ViewerFaceChip.Named(faceAnna, anna, "Anna")

  private fun unnamed() = ViewerFaceChip.Unnamed(faceUnknown, null)

  private fun both(): FakeViewerPeople = people(named(), ViewerFaceChip.Named(faceMarco, marco, "Marco"), unnamed(), ViewerFaceChip.Unrecognizable(2)).also {
    it.choices = listOf(ViewerPersonChoice(anna, "Anna"), ViewerPersonChoice(marco, "Marco"))
  }

  @Composable
  private fun Host(source: ViewerPeople?, active: Boolean = true, side: Boolean = false, fontScale: Float? = null) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
      Box(Modifier.fillMaxSize()) {
        val panel = rememberPeoplePanel(source, photo, active)
        if (side) {
          InfoSidePanel(progress = { 1f }, width = 360.dp, title = "Informazioni", onClose = {}) { InfoSections(panel.sections) }
        } else {
          InfoSheet(progress = { 1f }, height = 560.dp, title = "Informazioni", onClose = {}, onHeaderDrag = {}, onHeaderDragEnd = {}) {
            InfoSections(panel.sections)
          }
        }
        panel.sheets()
      }
    }
  }

  private fun show(source: ViewerPeople?, dark: Boolean = false, side: Boolean = false, active: Boolean = true, fontScale: Float? = null) {
    composeRule.setContent { ViewerTestHost(dark = dark) { Host(source, active, side, fontScale) } }
    composeRule.waitForIdle()
  }

  private fun exists(text: String) = composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

  private fun spoken(): List<String> =
    composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") }

  private fun chipNode(prefix: String): SemanticsNode = composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
    .first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.startsWith(prefix) == true }

  private fun labels(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

  // --- La riga -----------------------------------------------------------------------------------------------------------------------

  @Test
  fun laRigaMostraIChip_ilNomeOChiE_eIVoltiNonRiconoscibili() {
    show(both())
    assertThat(exists("PERSONE")).isTrue()
    assertThat(exists("Anna")).isTrue()
    assertThat(exists("Marco")).isTrue()
    assertThat(exists("Chi è?")).isTrue()
    assertThat(exists("2 volti non riconoscibili")).isTrue()
  }

  @Test
  fun unVoltoNonRiconoscibile_alSingolare() {
    show(people(named(), ViewerFaceChip.Unrecognizable(1)))
    assertThat(exists("1 volto non riconoscibile")).isTrue()
  }

  @Test
  fun senzaLaPortaOSenzaVolti_oPannelloChiuso_laRigaNonCe() {
    show(null)
    assertThat(exists("PERSONE")).isFalse()
  }

  @Test
  fun unMediaSenzaVolti_nonHaLaRiga() {
    show(people())
    assertThat(exists("PERSONE")).isFalse()
    assertThat(exists("Informazioni")).isTrue()
  }

  @Test
  fun pannelloChiuso_nonSiCalcolaNiente() {
    val source = both()
    show(source, active = false)
    assertThat(exists("PERSONE")).isFalse()
    assertThat(source.crops).isEmpty() // nessun ritaglio a pannello chiuso
  }

  @Test
  fun ritagliSoloPerIVoltiConUnChip_ePerNessunNonRiconoscibile() {
    val source = both()
    show(source)
    assertThat(source.crops.toSet()).containsExactly(faceAnna, faceMarco, faceUnknown)
  }

  @Test
  fun lasezioneVaSubitoDopoLaData() {
    val date = InfoSectionModel(header = null, rows = emptyList())
    val other = InfoSectionModel(header = "Altro", rows = emptyList())
    val people = InfoSectionModel(header = "Persone", rows = emptyList())
    assertThat(sectionsWithPeople(listOf(date, other), listOf(people))).containsExactly(date, people, other).inOrder()
    assertThat(sectionsWithPeople(listOf(other), listOf(people))).containsExactly(people, other).inOrder()
    assertThat(sectionsWithPeople(listOf(date, other), emptyList())).containsExactly(date, other).inOrder()
  }

  // --- Tocchi e fogli -------------------------------------------------------------------------------------------------------------

  @Test
  fun toccareUnaPersonaConNome_neApreLaPagina() {
    val source = both()
    show(source)
    composeRule.onNodeWithText("Anna").performClick()
    assertThat(source.opened).containsExactly(anna)
  }

  @Test
  fun toccareUnVoltoSenzaNome_chiedeChiE_eSiScegliUnaPersona() {
    val source = both()
    show(source)
    composeRule.onNodeWithText("Chi è?", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    assertThat(exists("Scegli una persona o aggiungine una nuova")).isTrue()
    assertThat(exists("Oppure una persona nuova")).isTrue()
    // "Marco" e' anche un chip: la riga del foglio e' l'ultima nell'albero, perche' il foglio sta sopra il pannello.
    val marcoNodes = composeRule.onAllNodesWithText("Marco")
    marcoNodes[marcoNodes.fetchSemanticsNodes().size - 1].performClick()
    composeRule.waitForIdle()
    assertThat(source.acts).containsExactly(faceUnknown to ViewerFaceAction.Is(marco))
    assertThat(exists("Scegli una persona o aggiungine una nuova")).isFalse() // il foglio si chiude
  }

  @Test
  fun unaPersonaNuovaConUnNome() {
    val source = both()
    show(source)
    composeRule.onNodeWithText("Chi è?", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    composeRule.onNode(hasSetTextAction()).performTextInput("Zio Carlo")
    composeRule.onNodeWithText("Aggiungi persona", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    assertThat(source.acts).containsExactly(faceUnknown to ViewerFaceAction.IsNew("Zio Carlo"))
  }

  @Test
  fun unNomeNonAmmesso_ilFoglioRestaApertoELoDice() {
    val source = both().also { it.outcome = ViewerFaceOutcome.INVALID_NAME }
    show(source)
    composeRule.onNodeWithText("Chi è?", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    composeRule.onNode(hasSetTextAction()).performTextInput("Zio\u0000Carlo")
    composeRule.onNodeWithText("Aggiungi persona", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    assertThat(exists("Da 1 a 64 caratteri, senza caratteri speciali.")).isTrue()
    assertThat(exists("Scegli una persona o aggiungine una nuova")).isTrue()
  }

  @Test
  fun pressioneLunga_apreIlMenuDelChip() {
    val source = both()
    show(source)
    composeRule.onNodeWithText("Anna").performTouchInput { longClick() }
    composeRule.waitForIdle()
    assertThat(exists("Azioni sul volto")).isTrue()
    assertThat(exists("Chi è?")).isTrue()
    assertThat(exists("Non è Anna")).isTrue()
    assertThat(exists("Apri la pagina")).isTrue()
    assertThat(exists("Ignora questo volto")).isTrue()
    composeRule.onNodeWithText("Non è Anna", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    assertThat(source.acts).containsExactly(faceAnna to ViewerFaceAction.IsNot(anna))
  }

  @Test
  fun ilMenuDiUnVoltoSenzaNome_nonHaNonE_ne_ApriLaPagina() {
    val source = both()
    show(source)
    composeRule.onAllNodesWithText("Chi è?")[0].performTouchInput { longClick() }
    composeRule.waitForIdle()
    assertThat(exists("Azioni sul volto")).isTrue()
    assertThat(exists("Non è")).isFalse()
    assertThat(exists("Apri la pagina")).isFalse()
    assertThat(exists("Ignora questo volto")).isTrue()
  }


  // --- GE-3 (correzioni D): un gruppo senza nome si nomina dalla sua pagina ---------------------------------------------------------------

  private val group = PersonId.random()
  private val faceGroup = ViewerFaceRef("group-face")

  /** Un volto in un gruppo senza nome (ha una persona, non ha un nome): il tocco apre la pagina del gruppo, dove si da' il nome al gruppo intero. */
  private fun groupChip() = ViewerFaceChip.Unnamed(faceGroup, group)

  private fun withGroup(): FakeViewerPeople = people(named(), groupChip(), unnamed()).also {
    it.choices = listOf(ViewerPersonChoice(anna, "Anna"))
  }

  @Test
  fun toccareUnGruppoSenzaNome_neApreLaPagina_enonChiedeChiE() {
    val source = withGroup()
    show(source)
    // Il chip del gruppo si dice "Senza nome" (non "Chi è?"): resta "Chi è?" solo per il volto che non e' in nessun gruppo.
    assertThat(exists("Senza nome")).isTrue()
    composeRule.onNodeWithText("Senza nome", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    // Prima si apriva "Chi e'?" e "E' una persona nuova" faceva una persona di un volto solo; ora si apre la pagina del gruppo ("Aggiungi un nome" li').
    assertThat(source.opened).containsExactly(group)
    assertThat(source.acts).isEmpty()
    assertThat(exists("Scegli una persona o aggiungine una nuova")).isFalse()
  }

  @Test
  fun unVoltoFuoriDaOgniGruppoChiedeAncoraChiE() {
    val source = withGroup()
    show(source)
    composeRule.onAllNodesWithText("Chi è?")[0].performClick()
    composeRule.waitForIdle()
    assertThat(exists("Scegli una persona o aggiungine una nuova")).isTrue()
    assertThat(source.opened).isEmpty()
  }

  @Test
  fun ilMenuDiUnGruppoSenzaNomeOffreLaPaginaELeAltreAzioni() {
    val source = withGroup()
    show(source)
    composeRule.onNodeWithText("Senza nome", useUnmergedTree = true).performTouchInput { longClick() }
    composeRule.waitForIdle()
    assertThat(exists("Azioni sul volto")).isTrue()
    assertThat(exists("Apri la pagina")).isTrue()
    assertThat(exists("lì gli dai un nome")).isTrue()
    assertThat(exists("Chi è?")).isTrue()
    assertThat(exists("Non è questa persona")).isTrue()
    assertThat(exists("Ignora questo volto")).isTrue()
    composeRule.onNodeWithText("Apri la pagina", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    assertThat(source.opened).containsExactly(group)
  }

  @Test
  fun lAnnuncioDiTalkBackDiUnGruppoSenzaNomeDiceCheSiApreLaPagina() {
    show(withGroup())
    val node = chipNode("Gruppo senza nome")
    assertThat(node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")).contains("dargli un nome")
    assertThat(labels(node)).contains("Azioni sul volto")
  }

  private fun shotD(name: String, dark: Boolean, suffix: String = "") {
    composeRule.onRoot().captureRoboImage("../docs/screenshots/8-corr-d/viewer-$name-${if (dark) "scuro" else "chiaro"}-telefono$suffix.png")
  }

  @Test
  fun screenshotGruppoSenzaNome_chiaro() {
    show(withGroup(), dark = false)
    shotD("gruppo-senza-nome", dark = false)
  }

  @Test
  fun screenshotGruppoSenzaNome_scuro() {
    show(withGroup(), dark = true)
    shotD("gruppo-senza-nome", dark = true)
  }

  @Test
  fun screenshotGruppoSenzaNome_testoGrande() {
    show(withGroup(), dark = false, fontScale = 1.3f)
    shotD("gruppo-senza-nome", dark = false, suffix = "-testo-grande")
  }

  @Test
  fun screenshotMenuDelGruppoSenzaNome_chiaro() {
    show(withGroup(), dark = false)
    composeRule.onNodeWithText("Senza nome", useUnmergedTree = true).performTouchInput { longClick() }
    composeRule.waitForIdle()
    shotD("gruppo-senza-nome-menu", dark = false)
  }

  @Test
  fun screenshotMenuDelGruppoSenzaNome_scuro() {
    show(withGroup(), dark = true)
    composeRule.onNodeWithText("Senza nome", useUnmergedTree = true).performTouchInput { longClick() }
    composeRule.waitForIdle()
    shotD("gruppo-senza-nome-menu", dark = true)
  }

  @Test
  fun screenshotMenuDelGruppoSenzaNome_testoGrande() {
    show(withGroup(), dark = false, fontScale = 1.3f)
    composeRule.onNodeWithText("Senza nome", useUnmergedTree = true).performTouchInput { longClick() }
    composeRule.waitForIdle()
    shotD("gruppo-senza-nome-menu", dark = false, suffix = "-testo-grande")
  }
  @Test
  fun ignoraQuestoVolto_chiedeLaConferma() {
    val source = both()
    show(source)
    composeRule.onNodeWithText("Anna").performTouchInput { longClick() }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Ignora questo volto", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    assertThat(exists("Ignorare questo volto?")).isTrue()
    assertThat(exists("Non verrà più raggruppato")).isTrue()
    assertThat(source.acts).isEmpty() // niente senza il tocco di conferma
    composeRule.onNodeWithText("Ignora", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    assertThat(source.acts).containsExactly(faceAnna to ViewerFaceAction.Ignore)
  }

  @Test
  fun unVoltoCheNonCePiu_lasciaUnAvvisoSottoIChip() {
    val source = both().also { it.outcome = ViewerFaceOutcome.NOT_FOUND }
    show(source)
    composeRule.onNodeWithText("Anna").performTouchInput { longClick() }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Non è Anna", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    assertThat(exists("Questo volto non c'è più.")).isTrue()
  }

  @Test
  fun personeNonAttivaQui_loDice() {
    val source = both().also { it.outcome = ViewerFaceOutcome.UNAVAILABLE }
    show(source)
    composeRule.onNodeWithText("Anna").performTouchInput { longClick() }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Non è Anna", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    assertThat(exists("Persone non è attiva su questo dispositivo.")).isTrue()
  }

  @Test
  fun leInfoSiAggiornanoQuandoCambiaUnNome() {
    val source = both()
    show(source)
    source.chips.value = listOf(ViewerFaceChip.Named(faceUnknown, PersonId.random(), "Zio Carlo"), named())
    composeRule.waitForIdle()
    assertThat(exists("Zio Carlo")).isTrue()
    assertThat(exists("Chi è?")).isFalse()
  }

  // --- TalkBack -----------------------------------------------------------------------------------------------------------------------

  @Test
  fun talkback_fraseEAzioniDiUnChipConNome() {
    val source = both()
    show(source)
    assertThat(spoken()).contains("Anna. Tocca per aprire la pagina, tieni premuto per le azioni.")
    val node = chipNode("Anna.")
    assertThat(labels(node)).containsExactly("Azioni sul volto", "Chi è?", "Non è Anna", "Ignora questo volto").inOrder()
    composeRule.runOnUiThread { node.config.getOrNull(SemanticsActions.CustomActions)!!.first { it.label == "Non è Anna" }.action() }
    composeRule.waitForIdle()
    assertThat(source.acts).containsExactly(faceAnna to ViewerFaceAction.IsNot(anna))
  }

  @Test
  fun talkback_fraseEAzioniDiUnVoltoSenzaNome() {
    show(both())
    assertThat(spoken()).contains("Volto senza nome. Tocca per dire chi è, tieni premuto per le azioni.")
    val node = chipNode("Volto senza nome")
    assertThat(labels(node)).containsExactly("Azioni sul volto", "Chi è?", "Ignora questo volto").inOrder()
  }

  // --- Bersagli e testo grande ------------------------------------------------------------------------------------------------------

  private fun controls(): List<SemanticsNode> {
    val screen = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
    return composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { node ->
      val b = node.boundsInRoot
      b.width * b.height < screen.width * screen.height * 0.8f && b.width > 0f && b.height > 0f
    }
  }

  private fun label(node: SemanticsNode): String =
    node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
      ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text } ?: "?"

  private fun overlap(a: Rect, b: Rect): Float {
    val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
    val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
    return if (w > 1f && h > 1f) w * h else 0f
  }

  /** I chip dei volti: i controlli che il lettore di schermo legge con "Tocca per …". */
  private fun chips(): List<SemanticsNode> = controls().filter { label(it).contains("Tocca per") }

  private fun assertTouchTargets(scene: String) {
    val density = composeRule.density.density
    val chips = chips()
    assertWithMessage("$scene: nessun chip trovato").that(chips).isNotEmpty()
    for (node in chips) {
      val b = node.touchBoundsInRoot
      assertWithMessage("$scene: «${label(node)}» e' piu' basso di 48 dp").that(b.height / density).isAtLeast(47.5f)
      assertWithMessage("$scene: «${label(node)}» e' piu' stretto di 48 dp").that(b.width / density).isAtLeast(47.5f)
    }
  }

  private fun assertNoOverlap(scene: String) {
    val nodes = controls()
    for (i in nodes.indices) for (j in i + 1 until nodes.size) {
      assertWithMessage("$scene: «${label(nodes[i])}» e «${label(nodes[j])}» si sovrappongono").that(overlap(nodes[i].boundsInRoot, nodes[j].boundsInRoot)).isEqualTo(0f)
    }
  }

  @Test
  fun bersagliDa48dp() {
    show(both())
    assertTouchTargets("chip")
  }

  @Test
  fun aTestoGrande_iChipNonSiSovrappongono() {
    show(both(), fontScale = 1.3f)
    assertNoOverlap("chip 1,3x")
    assertTouchTargets("chip 1,3x")
    assertThat(exists("Chi è?")).isTrue()
  }

  // --- Screenshot ---------------------------------------------------------------------------------------------------------------------

  private fun shot(name: String, dark: Boolean, device: String = "telefono", suffix: String = "") {
    composeRule.onRoot().captureRoboImage("../docs/screenshots/8-P9/viewer-$name-${if (dark) "scuro" else "chiaro"}-$device$suffix.png")
  }

  @Test
  fun screenshotRigaPersone_chiaroEScuro() {
    show(both(), dark = false)
    shot("persone", dark = false)
  }

  @Test
  fun screenshotRigaPersone_scuro() {
    show(both(), dark = true)
    shot("persone", dark = true)
  }

  @Test
  fun screenshotRigaPersone_testoGrande() {
    show(both(), dark = false, fontScale = 1.3f)
    shot("persone", dark = false, suffix = "-testo-grande")
  }

  @Test
  fun screenshotMenuDelChip() {
    show(both(), dark = false)
    composeRule.onNodeWithText("Anna").performTouchInput { longClick() }
    composeRule.waitForIdle()
    shot("persone-menu", dark = false)
  }

  @Test
  fun screenshotMenuDelChip_scuro() {
    show(both(), dark = true)
    composeRule.onNodeWithText("Anna").performTouchInput { longClick() }
    composeRule.waitForIdle()
    shot("persone-menu", dark = true)
  }

  @Test
  fun screenshotChiE() {
    show(both(), dark = false)
    composeRule.onNodeWithText("Chi è?", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    shot("persone-chi-e", dark = false)
  }

  @Test
  fun screenshotChiE_scuro() {
    show(both(), dark = true)
    composeRule.onNodeWithText("Chi è?", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    shot("persone-chi-e", dark = true)
  }

  @Test
  fun screenshotIgnora() {
    show(both(), dark = false)
    composeRule.onNodeWithText("Anna").performTouchInput { longClick() }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Ignora questo volto", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    shot("persone-ignora", dark = false)
  }

  @Test
  fun screenshotIgnora_scuro() {
    show(both(), dark = true)
    composeRule.onNodeWithText("Anna").performTouchInput { longClick() }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Ignora questo volto", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    shot("persone-ignora", dark = true)
  }

  @Test
  fun screenshotAvviso() {
    val source = both().also { it.outcome = ViewerFaceOutcome.NOT_FOUND }
    show(source, dark = false)
    composeRule.onNodeWithText("Anna").performTouchInput { longClick() }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Non è Anna", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    shot("persone-avviso", dark = false)
  }
}

/** Gli screenshot della riga "Persone" nel pannello laterale del tablet (1280 dp), chiaro e scuro. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET)
class ViewerPeopleInfoTabletTest {

  val composeRule = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private val photo = MediaId.random()

  private fun source() = FakeViewerPeople(
    listOf(
      ViewerFaceChip.Named(ViewerFaceRef("anna-face"), PersonId.random(), "Anna"),
      ViewerFaceChip.Named(ViewerFaceRef("marco-face"), PersonId.random(), "Marco"),
      ViewerFaceChip.Unnamed(ViewerFaceRef("unknown-face"), null),
      ViewerFaceChip.Unrecognizable(1),
    ),
  )

  private fun shoot(dark: Boolean) {
    val source = source()
    composeRule.setContent {
      ViewerTestHost(dark = dark) {
        Box(Modifier.fillMaxSize()) {
          val panel = rememberPeoplePanel(source, photo, active = true)
          InfoSidePanel(progress = { 1f }, width = 360.dp, title = "Informazioni", onClose = {}) { InfoSections(panel.sections) }
          panel.sheets()
        }
      }
    }
    composeRule.waitForIdle()
    assertThat(composeRule.onAllNodesWithText("PERSONE", substring = true).fetchSemanticsNodes()).isNotEmpty()
    composeRule.onRoot().captureRoboImage("../docs/screenshots/8-P9/viewer-persone-${if (dark) "scuro" else "chiaro"}-tablet.png")
  }

  @Test
  fun rigaPersoneSulTablet_chiaro() = shoot(dark = false)

  @Test
  fun rigaPersoneSulTablet_scuro() = shoot(dark = true)
}
