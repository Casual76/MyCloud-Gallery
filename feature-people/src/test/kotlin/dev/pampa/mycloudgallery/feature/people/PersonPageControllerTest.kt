package dev.pampa.mycloudgallery.feature.people

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.People
import dev.pampa.mycloudgallery.core.faces.contract.PersonView
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.contract.MergeSheetState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleMessage
import dev.pampa.mycloudgallery.feature.people.contract.PersonAction
import dev.pampa.mycloudgallery.feature.people.contract.PersonPageState
import dev.pampa.mycloudgallery.feature.people.page.PersonPageController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La pagina di una persona (Fase 8, P9; §1.5 `PersonPageState`, §2.6 "Pagina persona", D22, D24, D28, E-F23, E-F27, E-F28): gli stati (pronta,
 * nascosta, dimenticata, non trovata), le azioni con le voci giuste e l'effetto subito, il foglio del nome, l'unione, la copertina e la conferma di
 * "Dimentica". Sul riferimento `InMemoryPeople`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonPageControllerTest {

  private val world = PeopleFixture()

  private fun controller(scope: kotlinx.coroutines.CoroutineScope, id: PersonId = world.anna) = PersonPageController(id, world.people, world.personFaces, scope)

  private fun PersonPageController.ready(): PersonPageState.Ready = ui.value.page as PersonPageState.Ready

  @Test
  fun laPaginaPronta_haLeFotoMostrabiliELeAzioni() = runTest(UnconfinedTestDispatcher()) {
    val page = controller(backgroundScope).ready()
    assertThat(page.header.count).isEqualTo(PeopleFixture.PHOTOS - 1) // una foto di Anna e' nascosta
    assertThat(page.header.name).isNull()
    assertThat(page.view.size).isEqualTo(PeopleFixture.PHOTOS - 1)
    assertThat(page.actions).containsAtLeast(
      PersonAction.RENAME, PersonAction.MERGE, PersonAction.REVIEW, PersonAction.SPLIT, PersonAction.SET_COVER,
      PersonAction.HIDE, PersonAction.FORGET,
    )
    assertThat(page.actions).doesNotContain(PersonAction.UNHIDE)
    // SM-1: senza un nome non c'e' "Crea album smart" (l'editor non avrebbe ne' il titolo ne' la regola).
    assertThat(page.actions).doesNotContain(PersonAction.SMART_ALBUM)
    // "Forse e' anche…": la proposta con Marco, con le carte gia' visibili.
    assertThat(page.proposals.single().second.id).isEqualTo(world.marco)
  }

  @Test
  fun unaPersonaCheNonCeLaPaginaNonCe() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(backgroundScope, PersonId.random())
    assertThat(c.ui.value.page).isEqualTo(PersonPageState.NotFound)
    // Una persona con soli media nascosti non ha pagina nelle persone vere (D28): lo prova PeopleVisibilityTest in core-faces, perche' il riferimento
    // `InMemoryPeople` ne da' una pagina vuota.
  }

  @Test
  fun ilNomeSiDaNelFoglio_unNomeNonAmmessoLoLascia() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(backgroundScope)
    c.startNaming()
    assertThat(c.ui.value.naming!!.current).isNull()
    c.saveName("   ")
    assertThat(c.ui.value.naming!!.invalid).isTrue()
    c.saveName("x".repeat(65))
    assertThat(c.ui.value.naming!!.invalid).isTrue()
    c.saveName("  Anna ")
    assertThat(c.ui.value.naming).isNull()
    assertThat(c.ready().header.name).isEqualTo("Anna")
    assertThat(world.people.edits.last()).isEqualTo(PeopleEdit.Name(world.anna, "Anna"))
    // Con un nome, il foglio e' "Rinomina" e c'e' "Togli il nome".
    c.startNaming()
    assertThat(c.ui.value.naming!!.current).isEqualTo("Anna")
    c.removeName()
    assertThat(c.ready().header.name).isNull()
    assertThat(c.ui.value.naming).isNull()
  }

  /** E-F28: nascondi/mostra di nuovo; la pagina nascosta non ha le foto e dice come tornare. */
  @Test
  fun nascondiEMostraDiNuovo() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(backgroundScope)
    c.hide()
    val hidden = c.ui.value.page as PersonPageState.Hidden
    assertThat(hidden.header.id).isEqualTo(world.anna)
    assertThat(world.people.edits.last()).isEqualTo(PeopleEdit.Hidden(world.anna, true))
    c.unhide()
    assertThat(c.ui.value.page).isInstanceOf(PersonPageState.Ready::class.java)
    assertThat(world.people.edits.last()).isEqualTo(PeopleEdit.Hidden(world.anna, false))
  }

  /** E-F27: "Dimentica" chiede la conferma; fatto, la pagina e' "dimenticata" (anche se l'ha fatto un altro dispositivo). */
  @Test
  fun dimenticaChiedeLaConferma() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(backgroundScope)
    c.askForget()
    assertThat(c.ui.value.confirmForget).isTrue()
    assertThat(world.people.edits).isEmpty()
    c.dismissForget()
    assertThat(c.ui.value.confirmForget).isFalse()
    c.askForget()
    c.confirmForget()
    assertThat(c.ui.value.confirmForget).isFalse()
    assertThat(c.ui.value.page).isEqualTo(PersonPageState.Forgotten)
    assertThat(world.people.edits.takeLast(2)).containsExactly(PeopleEdit.Forget(world.anna), PeopleEdit.Name(world.anna, null)).inOrder()
  }

  @Test
  fun laPersonaDimenticataAltrove_laPaginaSiChiude() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(backgroundScope)
    assertThat(c.ui.value.page).isInstanceOf(PersonPageState.Ready::class.java)
    world.people.forget(world.anna) // come se fosse arrivata da un altro dispositivo
    assertThat(c.ui.value.page).isEqualTo(PersonPageState.Forgotten)
  }


  // --- Correzioni dopo le revisioni, pacchetto D ------------------------------------------------------------------------------------

  /** SM-1: "Crea album smart" si offre a una persona con un nome (titolo = nome, regola = lei) e a nessun'altra; con il nome compare. */
  @Test
  fun creaAlbumSmartSiOffreSoloConUnNome() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(backgroundScope)
    assertThat(c.ready().actions).doesNotContain(PersonAction.SMART_ALBUM)
    c.startNaming()
    c.saveName("Anna")
    assertThat(c.ready().actions).contains(PersonAction.SMART_ALBUM)
    c.startNaming()
    c.removeName()
    assertThat(c.ready().actions).doesNotContain(PersonAction.SMART_ALBUM)
  }

  /** Una persona le cui foto spariscono dalla vista: dal punto di vista della pagina e' o dimenticata o con le foto tutte nascoste, secondo [knowsIt]. */
  private class GonePeople(private val inner: People, private val knowsIt: Boolean) : People by inner {
    val gone = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun person(id: PersonId): Flow<PersonView?> = gone.flatMapLatest { if (it) flowOf(null) else inner.person(id) }

    override suspend fun knows(id: PersonId): Boolean = knowsIt
  }

  /** NV-1: nascondendo l'ultima foto con la pagina aperta, il nome c'e' ancora: lo dice, non "Persona dimenticata". */
  @Test
  fun leFotoTutteNascosteNonSonoUnaPersonaDimenticata() = runTest(UnconfinedTestDispatcher()) {
    val people = GonePeople(world.people, knowsIt = true)
    val c = PersonPageController(world.anna, people, world.personFaces, backgroundScope)
    c.startNaming()
    c.saveName("Anna")
    assertThat(c.ready().header.name).isEqualTo("Anna")
    people.gone.value = true
    val page = c.ui.value.page as PersonPageState.AllHidden
    // La carta di prima, senza foto, senza conto e senza copertina (non c'e' niente da mostrare).
    assertThat(page.header.name).isEqualTo("Anna")
    assertThat(page.header.count).isEqualTo(0)
    assertThat(page.header.cover).isNull()
    // Tornano foto mostrabili: la pagina e' di nuovo pronta.
    people.gone.value = false
    assertThat(c.ui.value.page).isInstanceOf(PersonPageState.Ready::class.java)
  }

  @Test
  fun unaPersonaCheLaPersonaNonConoscePiuEDimenticata() = runTest(UnconfinedTestDispatcher()) {
    val people = GonePeople(world.people, knowsIt = false)
    val c = PersonPageController(world.anna, people, world.personFaces, backgroundScope)
    assertThat(c.ui.value.page).isInstanceOf(PersonPageState.Ready::class.java)
    people.gone.value = true
    assertThat(c.ui.value.page).isEqualTo(PersonPageState.Forgotten)
    // Mai vista: non trovata, qualunque cosa dica `knows`.
    val never = GonePeople(world.people, knowsIt = true).also { it.gone.value = true }
    assertThat(PersonPageController(world.anna, never, world.personFaces, backgroundScope).ui.value.page).isEqualTo(PersonPageState.NotFound)
  }
  @Test
  fun laCopertinaSiSceglieFraIVoltiMostrabili() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(backgroundScope)
    c.openCovers()
    val choices = c.ui.value.covers!!.faces
    assertThat(choices).hasSize(PeopleFixture.PHOTOS - 1)
    c.pickCover(choices.last().face)
    assertThat(c.ui.value.covers).isNull()
    assertThat(world.people.edits.last()).isEqualTo(PeopleEdit.Cover(world.anna, choices.last().face))
    c.openCovers()
    c.pickCover(null)
    assertThat(world.people.edits.last()).isEqualTo(PeopleEdit.Cover(world.anna, null))
    c.openCovers()
    c.dismissCovers()
    assertThat(c.ui.value.covers).isNull()
  }

  /** "Unisci con…": si sceglie, con due nomi si sceglie quale tenere, si conferma. */
  @Test
  fun unisciConUnaAltraPersona() = runTest(UnconfinedTestDispatcher()) {
    world.people.rename(world.anna, "Anna")
    val c = controller(backgroundScope)
    c.startMerge()
    val choose = c.ui.value.merge!!.sheet as MergeSheetState.Choose
    assertThat(choose.candidates.map { it.id }).containsExactly(world.marco, world.bruno)
    c.continueFromChoose() // senza nessuno scelto non si va avanti... ma un solo insieme vuoto non unisce
    assertThat(c.ui.value.merge).isNull()
    c.startMerge()
    c.toggleCandidate(world.marco)
    assertThat((c.ui.value.merge!!.sheet as MergeSheetState.Choose).selected).containsExactly(world.marco)
    c.continueFromChoose()
    val confirm = c.ui.value.merge!!.sheet as MergeSheetState.Confirm
    assertThat(confirm.keepNameOf).isEqualTo(world.anna)
    c.confirmMerge()
    assertThat(world.people.resolve(world.marco)).isEqualTo(world.anna)
    assertThat(c.ready().header.count).isEqualTo(2 * PeopleFixture.PHOTOS - 1)
  }

  @Test
  fun terzoNomeNonEntra_eUnaPropostaSiRifiuta() = runTest(UnconfinedTestDispatcher()) {
    world.people.rename(world.anna, "Anna")
    world.people.rename(world.marco, "Marco")
    world.people.rename(world.bruno, "Bruno")
    val c = controller(backgroundScope)
    c.startMerge()
    c.toggleCandidate(world.marco)
    c.toggleCandidate(world.bruno) // con Anna sarebbero tre nomi
    assertThat((c.ui.value.merge!!.sheet as MergeSheetState.Choose).selected).containsExactly(world.marco)
    assertThat(c.ui.value.message).isEqualTo(PeopleMessage.TOO_MANY_NAMES)
    c.dismissMerge()
    // "Forse e' anche…": la proposta si apre con il suo foglio e il "No" la separa per sempre.
    c.openProposal(c.ready().proposals.single())
    c.rejectProposal()
    assertThat(world.people.edits.last()).isInstanceOf(PeopleEdit.Apart::class.java)
    assertThat(c.ready().proposals).isEmpty()
  }
}
