package dev.pampa.mycloudgallery.feature.people

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.contract.PeopleActions
import dev.pampa.mycloudgallery.feature.people.hidden.HiddenPeopleRoute
import dev.pampa.mycloudgallery.feature.people.hidden.HiddenPeopleViewModel
import dev.pampa.mycloudgallery.feature.people.list.OthersRoute
import dev.pampa.mycloudgallery.feature.people.list.OthersViewModel
import dev.pampa.mycloudgallery.feature.people.list.PeopleListRoute
import dev.pampa.mycloudgallery.feature.people.list.PeopleListViewModel
import dev.pampa.mycloudgallery.feature.people.nav.PeopleDependencies
import dev.pampa.mycloudgallery.feature.people.page.PersonPageExits
import dev.pampa.mycloudgallery.feature.people.page.PersonPageRoute
import dev.pampa.mycloudgallery.feature.people.page.PersonPageViewModel
import dev.pampa.mycloudgallery.feature.people.review.FaceReviewRoute
import dev.pampa.mycloudgallery.feature.people.review.FaceReviewViewModel
import java.util.Optional
import org.junit.Test

/**
 * Le rotte delle persone sul mondo di riferimento (Fase 8, P9): dal tocco sulla schermata, passando per il ViewModel e il controllore, fino al contratto
 * (`InMemoryPeople`). Provano il **legame** che gli altri test non vedono: che ogni gesto arrivi al controllore giusto, che le uscite verso `:app` (aprire una persona,
 * il viewer, "Crea album smart", "Controlla le foto") siano chiamate e che un id che non e' di una persona dia "non c'e'".
 */
class PeopleRoutesTest : PeopleScreenTest() {

  private class RecordingActions : PeopleActions {
    val viewed = mutableListOf<MediaId>()
    val persons = mutableListOf<PersonId>()
    val smart = mutableListOf<Pair<PersonId, String?>>()
    var others = 0

    override fun openViewer(view: OrderedTimelineView, start: MediaId) {
      viewed += start
    }

    override fun openPerson(id: PersonId) {
      persons += id
    }

    override fun openAllPeople() = Unit

    override fun openOthers() {
      others++
    }

    override fun openActivation() = Unit

    override fun openPeopleSettings() = Unit

    override fun openDiagnostics() = Unit

    override fun createSmartAlbum(person: PersonId, title: String?) {
      smart += person to title
    }
  }

  private val world get() = scenes.world

  private fun deps() = PeopleDependencies(
    Optional.of(world.people),
    Optional.of(world.personFaces),
    Optional.of(HiddenOfPeople(world.people, listOf(world.anna, world.marco, world.bruno, world.hiddenOnly, scenes.nascosta, scenes.giulia, scenes.luca))),
  )

  private fun idHandle(id: PersonId) = SavedStateHandle(mapOf("id" to id.value))

  private fun reviewHandle(id: String, split: Boolean = false) = SavedStateHandle(mapOf("id" to id, "split" to split))

  private val thumbs get() = scenes.thumbs.thumbnails

  // --- Tutte le persone -------------------------------------------------------------------------------------------------------------

  @Test
  fun elenco_leUscitePortanoAllePagine() {
    val opened = mutableListOf<PersonId>()
    var others = 0
    var hidden = 0
    val model = PeopleListViewModel(deps())
    show { PeopleListRoute(scenes.crops, opened::add, { others++ }, { hidden++ }, {}, viewModel = model) }
    composeRule.onNode(hasContentDescription("Anna, 4 foto")).performClick()
    assertThat(opened).containsExactly(world.anna)
    composeRule.onNodeWithText("Altri volti (3)", useUnmergedTree = true).performClick()
    assertThat(others).isEqualTo(1)
    composeRule.onNodeWithText("Persone nascoste (1)", useUnmergedTree = true).performClick()
    assertThat(hidden).isEqualTo(1)
  }

  /** Dal gesto alla voce: pressione lunga, un'altra persona, "Unisci", il nome da tenere, la conferma: la voce `people` e' scritta e l'elenco si aggiorna. */
  @Test
  fun elenco_unireDuePersoneConDueNomi_finoAllaVoce() {
    val model = PeopleListViewModel(deps())
    show { PeopleListRoute(scenes.crops, {}, {}, {}, {}, viewModel = model) }
    composeRule.onNode(hasContentDescription("Anna, 4 foto")).performTouchInput { longClick() }
    composeRule.onNode(hasContentDescription("Marco, 5 foto")).performClick()
    composeRule.onNodeWithContentDescription("Unisci").performClick()
    composeRule.waitForIdle()
    assertShows("elenco", "Quale nome tieni?")
    composeRule.onNodeWithText("Tieni «Marco»", useUnmergedTree = true).performClick()
    composeRule.onNodeWithText("Continua", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    assertShows("conferma", "Unire queste persone?", "Resta il nome «Marco»")
    assertThat(world.people.edits).isEmpty() // finche' non si tocca «Unisci» non e' scritto niente
    composeRule.onNodeWithText("Unisci", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    assertThat(world.people.edits.last()).isEqualTo(PeopleEdit.Alias(world.anna, world.marco))
    assertDoesNotShow("fatto", "Unire queste persone?")
    assertThat(spokenControls()).contains("Marco, 9 foto")
  }

  @Test
  fun elenco_unaPropostaSiRifiuta_eNonTornaPiu() {
    val model = PeopleListViewModel(deps())
    show { PeopleListRoute(scenes.crops, {}, {}, {}, {}, viewModel = model) }
    // L'intestazione della sezione e' in maiuscolo: il testo esatto e' solo il titolo della riga.
    composeRule.onNodeWithText("È la stessa persona?", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("No, sono diverse", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    assertThat(world.people.edits.last()).isInstanceOf(PeopleEdit.Apart::class.java)
    assertDoesNotShow("dopo il no", "È la stessa persona?")
  }

  // --- Altri volti ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun altriVolti_unTocco_apreLaPaginaDelGruppo_eDimenticaPassaDallaConferma() {
    val opened = mutableListOf<PersonId>()
    val model = OthersViewModel(deps())
    show { OthersRoute(scenes.crops, opened::add, {}, viewModel = model) }
    composeRule.onAllNodesWithContentDescription("Persona senza nome, 5 foto")[0].performClick()
    assertThat(opened).hasSize(1)
    composeRule.onAllNodesWithContentDescription("Persona senza nome, 5 foto")[0].performTouchInput { longClick() }
    composeRule.onNodeWithContentDescription("Dimentica").performClick()
    composeRule.waitForIdle()
    assertShows("conferma", "Dimenticare questa persona?")
    assertThat(world.people.edits).isEmpty()
    composeRule.onNodeWithText("Dimentica", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    assertThat(world.people.edits.filterIsInstance<PeopleEdit.Forget>()).hasSize(1)
    assertThat(spokenControls().count { it.startsWith("Persona senza nome") }).isEqualTo(2)
  }

  // --- Pagina ---------------------------------------------------------------------------------------------------------------------------

  private fun pageRoute(id: PersonId, actions: PeopleActions = RecordingActions(), exits: PersonPageExits = PersonPageExits({}, { _, _ -> })): @androidx.compose.runtime.Composable () -> Unit {
    val model = PersonPageViewModel(idHandle(id), deps())
    return { PersonPageRoute(actions, thumbs, scenes.crops, exits, viewModel = model) }
  }

  @Test
  fun pagina_unaFotoSiApreNelViewerSullaVistaDellaPersona() {
    val actions = RecordingActions()
    show(content = pageRoute(world.anna, actions))
    assertThat(countDescribed("Foto, ")).isEqualTo(4)
    composeRule.onAllNodes(hasContentDescription("Foto, ", substring = true))[0].performClick()
    composeRule.waitForIdle()
    assertThat(actions.viewed).hasSize(1)
  }

  @Test
  fun pagina_ilNomeSiDaDalFoglio_eLaPaginaEIlTitoloSiAggiornano() {
    show(content = pageRoute(world.bruno))
    composeRule.onNodeWithContentDescription("Aggiungi un nome").performClick()
    composeRule.waitForIdle()
    composeRule.onNode(hasSetTextAction()).performTextInput("  Zio Carlo ")
    composeRule.onNodeWithText("Salva", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    assertThat(world.people.edits.last()).isEqualTo(PeopleEdit.Name(world.bruno, "Zio Carlo"))
    assertShows("dopo il nome", "Zio Carlo", "Rinomina")
    assertDoesNotShow("dopo il nome", "Aggiungi un nome")
  }

  @Test
  fun pagina_ilMenu_creaAlbumSmart_conIlNome() {
    val actions = RecordingActions()
    rename(world.anna, "Anna")
    show(content = pageRoute(world.anna, actions))
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.onNodeWithText("Crea album smart", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    assertThat(actions.smart).containsExactly(world.anna to "Anna")
  }

  @Test
  fun pagina_controllaEDividi_portanoAlleGrigliePerQuellaPersona() {
    val reviews = mutableListOf<Pair<PersonId, Boolean>>()
    show(content = pageRoute(world.anna, exits = PersonPageExits({}, { id, split -> reviews += id to split })))
    composeRule.onNodeWithContentDescription("Controlla le foto").performClick()
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.onNodeWithText("Dividi…", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    assertThat(reviews).containsExactly(world.anna to false, world.anna to true).inOrder()
  }

  @Test
  fun pagina_nascondiEMostraDiNuovo() {
    show(content = pageRoute(world.anna))
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.onNodeWithText("Nascondi persona", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    assertShows("nascosta", "Persona nascosta", "Mostra di nuovo")
    assertThat(world.people.edits.last()).isEqualTo(PeopleEdit.Hidden(world.anna, true))
    composeRule.onNodeWithText("Mostra di nuovo", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    assertThat(world.people.edits.last()).isEqualTo(PeopleEdit.Hidden(world.anna, false))
    assertDoesNotShow("di nuovo visibile", "Persona nascosta")
  }

  @Test
  fun pagina_dimenticaChiedeLaConfermaEPoiDicePersonaDimenticata() {
    show(content = pageRoute(world.anna))
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    composeRule.onNodeWithText("Dimentica persona", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    assertShows("conferma", "Dimenticare questa persona?")
    assertThat(world.people.edits).isEmpty()
    composeRule.onNodeWithText("Dimentica", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    assertShows("dimenticata", "Persona dimenticata")
  }

  @Test
  fun pagina_unIdCheNonEDiUnaPersona_dice_nonCe() {
    var back = 0
    val model = PersonPageViewModel(SavedStateHandle(mapOf("id" to "non-un-id")), deps())
    show { PersonPageRoute(RecordingActions(), thumbs, scenes.crops, PersonPageExits({ back++ }, { _, _ -> }), viewModel = model) }
    assertShows("non c'e'", "Questa persona non c'è")
    composeRule.onNodeWithText("Indietro", useUnmergedTree = true).performClick()
    assertThat(back).isEqualTo(1)
  }

  // --- Controlla e Dividi -------------------------------------------------------------------------------------------------------------

  @Test
  fun controlla_ignoraUnVolto_scriveLaVoce_eTornaIndietro() {
    var back = 0
    val model = FaceReviewViewModel(reviewHandle(world.anna.value), deps())
    show { FaceReviewRoute(scenes.crops, { back++ }, {}, viewModel = model) }
    composeRule.onNode(hasContentDescription("Volto 1 di 4")).performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Ignora questo volto", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    assertThat(world.people.edits.single()).isInstanceOf(PeopleEdit.Ignore::class.java)
    assertThat(back).isEqualTo(1)
  }

  @Test
  fun controlla_nonEAnna_scriveLaVoceDiEsclusione() {
    rename(world.anna, "Anna")
    val model = FaceReviewViewModel(reviewHandle(world.anna.value), deps())
    show { FaceReviewRoute(scenes.crops, {}, {}, viewModel = model) }
    composeRule.onNode(hasContentDescription("Volto 2 di 4")).performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Non è Anna", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    assertThat(world.people.edits.filterIsInstance<PeopleEdit.Not>().single().person).isEqualTo(world.anna)
  }

  @Test
  fun dividi_apreLaPersonaNuova() {
    val opened = mutableListOf<PersonId>()
    val model = FaceReviewViewModel(reviewHandle(world.anna.value, split = true), deps())
    show { FaceReviewRoute(scenes.crops, {}, opened::add, viewModel = model) }
    composeRule.onNode(hasContentDescription("Volto 2 di 4")).performClick()
    composeRule.onNode(hasContentDescription("Volto 3 di 4")).performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Sposta in una persona nuova", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    assertThat(opened).hasSize(1)
    assertThat(world.people.edits.first()).isInstanceOf(PeopleEdit.Born::class.java)
  }

  @Test
  fun controlla_unIdCheNonEDiUnaPersona_tornaIndietro() {
    var back = 0
    val model = FaceReviewViewModel(reviewHandle("non-un-id"), deps())
    show { FaceReviewRoute(scenes.crops, { back++ }, {}, viewModel = model) }
    composeRule.waitForIdle()
    assertThat(back).isAtLeast(1)
  }

  // --- Persone nascoste ----------------------------------------------------------------------------------------------------------------

  @Test
  fun nascoste_unTocco_laMostraDiNuovo() {
    hide(scenes.giulia)
    val model = HiddenPeopleViewModel(deps())
    show { HiddenPeopleRoute(scenes.crops, {}, viewModel = model) }
    assertShows("nascoste", "Giulia")
    composeRule.onNodeWithText("Giulia", useUnmergedTree = true).performClick()
    composeRule.waitForIdle()
    assertThat(world.people.edits.last()).isEqualTo(PeopleEdit.Hidden(scenes.giulia, false))
  }

  private fun rename(id: PersonId, name: String) {
    kotlinx.coroutines.runBlocking { world.people.rename(id, name) }
  }

  private fun hide(id: PersonId) {
    kotlinx.coroutines.runBlocking { world.people.setHidden(id, true) }
  }
}
