package dev.pampa.mycloudgallery.feature.albums

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.PeopleRule
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.albums.contract.PeopleRuleInfo
import dev.pampa.mycloudgallery.feature.albums.contract.PersonChoice
import dev.pampa.mycloudgallery.feature.albums.contract.SmartEditorState
import dev.pampa.mycloudgallery.feature.albums.editor.NoSmartEditorHandlers
import dev.pampa.mycloudgallery.feature.albums.editor.PeoplePickerSheet
import dev.pampa.mycloudgallery.feature.albums.editor.PeopleSection
import dev.pampa.mycloudgallery.feature.albums.editor.SmartEditorHandlers
import dev.pampa.mycloudgallery.feature.albums.editor.SmartEditorUi
import dev.pampa.mycloudgallery.feature.albums.editor.showsPeopleSection
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumHost
import org.junit.Test

/**
 * La sezione "Persone" dell'editor e il suo foglio di scelta (Fase 8, P10; E-F23, E-F27, E-F28, E-F37): le parole di ogni stato, i tocchi che arrivano al controllore, il
 * lettore di schermo (la riga che toglie dice «Togli Marco dalla regola», le righe del foglio sono interruttori), bersagli da 48 dp e nessuna sovrapposizione anche a 1,3x.
 * Solo nomi inventati.
 */
class PeopleEditorScreenTest : AlbumsComposeTest() {
  private fun person(n: Int) = PersonId.fromBytes(ByteArray(PersonId.BYTES) { (n * 17 + it).toByte() })

  private val marco = person(1)
  private val anna = person(2)
  private val giulia = person(3)
  private val lost = person(9)
  private val hidden = person(8)

  private val choices = listOf(PersonChoice(marco, "Marco", 120), PersonChoice(anna, "Anna", 80), PersonChoice(giulia, "Giulia", 12))

  private class Recording : SmartEditorHandlers by NoSmartEditorHandlers {
    val removed = mutableListOf<PersonId>()
    val modes = mutableListOf<PeopleMode>()
    val toggled = mutableListOf<PersonId>()
    var removedAll = 0

    override fun onRemovePerson(id: PersonId) {
      removed += id
    }

    override fun onPeopleMode(mode: PeopleMode) {
      modes += mode
    }

    override fun onTogglePerson(id: PersonId) {
      toggled += id
    }

    override fun onRemovePeople() {
      removedAll++
    }
  }

  private fun ui(
    rule: PeopleRule?,
    names: Map<PersonId, String> = emptyMap(),
    lostIds: Set<PersonId> = emptySet(),
    on: Boolean = true,
    available: List<PersonChoice> = choices,
  ) = SmartEditorUi(
    title = "Marco e Anna", state = SmartEditorState.Interpreting("Marco e Anna"), rules = SmartRules(null, null, null, people = rule),
    peopleOn = on, peopleChoices = available, peopleInfo = PeopleRuleInfo(names, lostIds),
  )

  private val both = PeopleRule(listOf(marco, anna), PeopleMode.ALL)
  private val bothNamed = mapOf(marco to "Marco", anna to "Anna")

  private fun section(ui: SmartEditorUi, handlers: SmartEditorHandlers = NoSmartEditorHandlers, onPick: () -> Unit = {}): @Composable () -> Unit = {
    PeopleSection(ui, ui.rules?.people, handlers, onPick)
  }

  private fun showSection(ui: SmartEditorUi, handlers: SmartEditorHandlers = NoSmartEditorHandlers, fontScale: Float? = null, onPick: () -> Unit = {}) =
    show(fontScale = fontScale) { AlbumHost { Column { section(ui, handlers, onPick)() } } }

  private fun assertText(text: String) {
    assertWithMessage("manca «$text»").that(composeRule.onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes()).isNotEmpty()
  }

  private fun assertNoText(text: String) {
    assertWithMessage("non ci deve essere «$text»").that(composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes()).isEmpty()
  }

  // --- Le parole di ogni stato ---------------------------------------------------------------------------------------------------

  @Test
  fun dueMarcoEAnnaPerNomeConIlModoEIlPulsanteAggiungi() {
    showSection(ui(both, bothNamed))
    for (text in listOf("Persone", "Marco", "Anna", "Le foto devono avere", "Tutte queste persone", "Almeno una di loro", "Aggiungi persone", "Togli le persone")) assertText(text)
    // Due bottoni radio, nell'ordine "Tutte" e "Almeno una": la regola e' "tutte".
    val radios = composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).fetchSemanticsNodes()
    assertThat(radios.map { it.config.getOrNull(SemanticsProperties.Selected) }).containsExactly(true, false).inOrder()
    assertText("restano fra le suggerite")
  }

  @Test
  fun conUnaPersonaSolaNonCeLaSceltaDelModo() {
    showSection(ui(PeopleRule(listOf(marco), PeopleMode.ANY), mapOf(marco to "Marco")))
    assertText("Marco")
    assertNoText("Tutte queste persone")
    assertNoText("Almeno una di loro")
  }

  @Test
  fun senzaLaRegolaLaSezioneDiceQualunquePersona() {
    showSection(ui(null))
    assertText("Qualunque persona")
    assertText("Aggiungi persone")
    assertNoText("Togli le persone")
  }

  @Test
  fun unaPersonaPerdutaSiDiceEUnaNascostaNonMostraIlNome_E_F27_E_F28() {
    val rule = PeopleRule(listOf(marco, lost, hidden), PeopleMode.ALL)
    showSection(ui(rule, mapOf(marco to "Marco"), lostIds = setOf(lost)))
    assertText("Persona non più riconosciuta")
    assertText("Una persona della regola non è più riconosciuta")
    assertText("Una persona nascosta")
    assertText("Marco")
  }

  @Test
  fun senzaIVoltiLaSezioneLoDiceENonOffreLaScelta_E_F37() {
    showSection(ui(both, bothNamed, on = false, available = emptyList()))
    assertText("Le persone non sono attive su questo dispositivo")
    assertNoText("Aggiungi persone")
    // Le persone della regola si vedono comunque, e si possono togliere.
    assertText("Marco")
    assertText("Togli le persone")
  }

  @Test
  fun laSezioneSiNascondeSenzaVoltiESenzaRegola() {
    assertThat(showsPeopleSection(ui(null, on = false, available = emptyList()), null)).isFalse()
    assertThat(showsPeopleSection(ui(both, on = false, available = emptyList()), both)).isTrue()
    assertThat(showsPeopleSection(ui(null, on = true), null)).isTrue()
  }

  @Test
  fun senzaPersoneDaSceglierelaSezioneLoDice() {
    showSection(ui(null, available = emptyList()))
    assertText("Non ci sono persone con un nome da scegliere")
    assertNoText("Aggiungi persone")
  }

  // --- I tocchi -------------------------------------------------------------------------------------------------------------------

  @Test
  fun iTocchiArrivanoAlControllore() {
    val rec = Recording()
    var picked = 0
    showSection(ui(both, bothNamed), rec) { picked++ }
    clickText("Marco")
    assertThat(rec.removed).containsExactly(marco)
    clickText("Almeno una di loro")
    assertThat(rec.modes).containsExactly(PeopleMode.ANY)
    clickText("Aggiungi persone")
    assertThat(picked).isEqualTo(1)
    clickText("Togli le persone")
    assertThat(rec.removedAll).isEqualTo(1)
  }

  // --- Il lettore di schermo ------------------------------------------------------------------------------------------------------

  private fun clickLabelOf(text: String): String? {
    val nodes = composeRule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes()
    return nodes.firstNotNullOfOrNull { n -> generateSequence(n) { it.parent }.firstNotNullOfOrNull { it.config.getOrNull(SemanticsActions.OnClick)?.label } }
  }

  @Test
  fun laRigaCheTogliesDiceChiTogliePerIlLettoreDiSchermo() {
    showSection(ui(both, bothNamed))
    assertThat(clickLabelOf("Marco")).isEqualTo("Togli Marco dalla regola")
    assertThat(clickLabelOf("Anna")).isEqualTo("Togli Anna dalla regola")
  }

  // --- Bersagli da 48 dp e sovrapposizioni ----------------------------------------------------------------------------------------

  private fun controls(): List<SemanticsNode> {
    val nodes = composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
    val screen = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
    return nodes.filter { n ->
      val b = n.boundsInRoot
      b.width * b.height < screen.width * screen.height * 0.8f && b.width > 0f && b.height > 0f
    }
  }

  private fun label(node: SemanticsNode): String =
    node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text } ?: "?"

  private fun assertTargets(scene: String) {
    val density = composeRule.density.density
    for (node in controls()) {
      val b = node.touchBoundsInRoot
      val w = b.width / density
      val h = b.height / density
      assertWithMessage("$scene: «${label(node)}» e' ${"%.1f".format(w)}x${"%.1f".format(h)} dp, meno di 48").that(minOf(w, h)).isAtLeast(47.5f)
    }
  }

  private fun overlap(a: Rect, b: Rect): Float {
    val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
    val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
    return if (w > 1f && h > 1f) w * h else 0f
  }

  private fun assertNoOverlap(scene: String) {
    val nodes = controls()
    for (i in nodes.indices) for (j in i + 1 until nodes.size) {
      assertWithMessage("$scene: «${label(nodes[i])}» e «${label(nodes[j])}» si sovrappongono").that(overlap(nodes[i].boundsInRoot, nodes[j].boundsInRoot)).isEqualTo(0f)
    }
  }

  @Test
  fun laSezioneHaBersagliDa48dpENessunaSovrapposizione() {
    val rule = PeopleRule(listOf(marco, anna, lost, hidden), PeopleMode.ANY)
    showSection(ui(rule, bothNamed, lostIds = setOf(lost)))
    assertTargets("persone")
    assertNoOverlap("persone")
  }

  @Test
  fun laSezioneATestoGrandeNonSiSovrappone() {
    val rule = PeopleRule(listOf(marco, anna, lost, hidden), PeopleMode.ANY)
    showSection(ui(rule, bothNamed, lostIds = setOf(lost)), fontScale = LARGE_TEXT)
    assertTargets("persone 1,3x")
    assertNoOverlap("persone 1,3x")
  }

  // --- Il foglio di scelta --------------------------------------------------------------------------------------------------------

  private fun showPicker(
    list: List<PersonChoice> = choices,
    chosen: Set<PersonId> = setOf(marco),
    fontScale: Float? = null,
    onToggle: (PersonId) -> Unit = {},
    onDismiss: () -> Unit = {},
  ) = show(fontScale = fontScale) { AlbumHost { PeoplePickerSheet(visible = true, choices = list, chosen = chosen, onToggle = onToggle, onDismiss = onDismiss) } }

  @Test
  fun ilFoglioElencaLePersoneConIConteggiEDiceQualiSonoScelte() {
    showPicker()
    for (text in listOf("Scegli le persone", "Marco", "120 foto", "Anna", "80 foto", "Giulia", "12 foto", "Fatto")) assertText(text)
    // Un interruttore per riga, con lo stato letto dal lettore di schermo.
    val rows = composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).fetchSemanticsNodes()
    assertThat(rows).hasSize(3)
    val states = rows.map { it.config.getOrNull(SemanticsProperties.ToggleableState) }
    assertThat(states.count { it == ToggleableState.On }).isEqualTo(1)
  }

  @Test
  fun unToccoArrivaAlControlloreEFattoChiude() {
    val toggled = mutableListOf<PersonId>()
    var dismissed = 0
    showPicker(onToggle = { toggled += it }, onDismiss = { dismissed++ })
    clickText("Anna")
    assertThat(toggled).containsExactly(anna)
    clickText("Fatto")
    assertThat(dismissed).isEqualTo(1)
  }

  @Test
  fun conMoltePersoneCeIlCampoPerCercareSenzaAccentiNeMaiuscole() {
    val many = (1..12).map { PersonChoice(person(100 + it), "Persona $it", it) } + PersonChoice(person(200), "Élodie", 5)
    showPicker(many)
    composeRule.onNode(hasSetTextAction()).performTextInput("elodie")
    composeRule.waitForIdle()
    assertText("Élodie")
    assertNoText("Persona 3")
    composeRule.onNode(hasSetTextAction()).performTextInput("zzz")
    composeRule.waitForIdle()
    assertText("Nessuna persona con questo nome")
  }

  @Test
  fun conPocheNonCeIlCampoPerCercare() {
    showPicker()
    assertThat(composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun ilFoglioHaBersagliDa48dpAncheATestoGrande() {
    showPicker(fontScale = LARGE_TEXT)
    assertTargets("foglio persone 1,3x")
    assertNoOverlap("foglio persone 1,3x")
    composeRule.onNodeWithText("Fatto").assertExists()
  }
}
