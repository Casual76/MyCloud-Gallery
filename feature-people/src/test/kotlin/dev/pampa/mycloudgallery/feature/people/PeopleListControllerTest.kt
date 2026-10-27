package dev.pampa.mycloudgallery.feature.people

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryPeople
import dev.pampa.mycloudgallery.feature.people.contract.MergeSheetState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleListState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleMessage
import dev.pampa.mycloudgallery.feature.people.list.PeopleListController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * "Tutte le persone" (Fase 8, P9; §1.5 `PeopleListState`, §2.6, Q2 (b), D24, E-F23): lo stato, la selezione, l'unione (con due nomi si sceglie quale
 * tenere, con piu' di due si fa due alla volta), le proposte "E' la stessa persona?" e il loro "No". Sul riferimento `InMemoryPeople` (le persone vere
 * hanno la loro suite di contratto in core-faces).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeopleListControllerTest {

  private val world = PeopleFixture()

  @Test
  fun q2b_inPrimoPianoSoloChiHaUnNome_glialtriInAltriVolti() = runTest(UnconfinedTestDispatcher()) {
    val controller = PeopleListController(world.people, backgroundScope)
    val first = controller.ui.value.state as PeopleListState.Ready
    assertThat(first.named).isEmpty()
    assertThat(first.unnamed).isEmpty()
    // Anna (4 foto mostrabili), Marco e Bruno (5): tre gruppi senza nome; il gruppo con soli nascosti non si conta (E-F38).
    assertThat(first.othersCount).isEqualTo(3)
    world.people.rename(world.anna, "Anna")
    val named = controller.ui.value.state as PeopleListState.Ready
    assertThat(named.named.map { it.name }).containsExactly("Anna")
    assertThat(named.othersCount).isEqualTo(2)
  }

  @Test
  fun q2a_tuttiInPersone() = runTest(UnconfinedTestDispatcher()) {
    val all = PeopleFixture(namedOnly = false)
    all.people.rename(all.marco, "Marco")
    val controller = PeopleListController(all.people, backgroundScope)
    val ready = controller.ui.value.state as PeopleListState.Ready
    assertThat(ready.named.map { it.name }).containsExactly("Marco")
    assertThat(ready.unnamed.map { it.id }).containsExactly(all.bruno, all.anna).inOrder()
    assertThat(ready.othersCount).isEqualTo(0)
  }

  @Test
  fun primoCalcoloEVuoto() = runTest(UnconfinedTestDispatcher()) {
    val empty = InMemoryPeople(world.visibility, world.visibility, { emptySet() }, { emptySet() }, world.author, ids = GroupingStories.ids(1))
    val controller = PeopleListController(empty, backgroundScope)
    assertThat(controller.ui.value.state).isEqualTo(PeopleListState.Empty)
    empty.progressPercent = 35
    empty.refresh()
    assertThat(controller.ui.value.state).isEqualTo(PeopleListState.Working(35))
  }

  @Test
  fun laSelezioneSiFaEDisfa() = runTest(UnconfinedTestDispatcher()) {
    world.people.rename(world.anna, "Anna")
    world.people.rename(world.marco, "Marco")
    val controller = PeopleListController(world.people, backgroundScope)
    controller.toggle(world.anna)
    assertThat(controller.ui.value.selected).isEmpty() // fuori dalla selezione un tocco non sceglie
    controller.startWith(world.anna)
    assertThat(controller.ui.value.selecting).isTrue()
    assertThat(controller.ui.value.selected).containsExactly(world.anna)
    controller.toggle(world.marco)
    assertThat(controller.ui.value.selected).containsExactly(world.anna, world.marco)
    controller.toggle(world.anna)
    assertThat(controller.ui.value.selected).containsExactly(world.marco)
    controller.setSelecting(false)
    assertThat(controller.ui.value.selecting).isFalse()
    assertThat(controller.ui.value.selected).isEmpty()
  }

  /** E-F23: due nomi, si sceglie quale tenere; l'unione e' un alias, e solo dopo "Unisci". */
  @Test
  fun unisciConDueNomiSceglieQualeTenere() = runTest(UnconfinedTestDispatcher()) {
    world.people.rename(world.anna, "Anna")
    world.people.rename(world.marco, "Marco")
    val controller = PeopleListController(world.people, backgroundScope)
    controller.startWith(world.anna)
    controller.toggle(world.marco)
    controller.mergeSelected()
    val pick = controller.ui.value.merge!!.sheet as MergeSheetState.PickName
    assertThat(pick.canConfirm).isFalse()
    controller.continueFromName() // senza una scelta non si va avanti
    assertThat(controller.ui.value.merge!!.sheet).isInstanceOf(MergeSheetState.PickName::class.java)
    controller.pickName(world.marco)
    controller.continueFromName()
    val confirm = controller.ui.value.merge!!.sheet as MergeSheetState.Confirm
    assertThat(confirm.keepNameOf).isEqualTo(world.marco)
    assertThat(world.people.edits.none { it is PeopleEdit.Alias }).isTrue() // ancora niente di scritto
    controller.confirmMerge()
    assertThat(world.people.edits.last()).isEqualTo(PeopleEdit.Alias(world.anna, world.marco))
    assertThat(controller.ui.value.merge).isNull()
    assertThat(controller.ui.value.selecting).isFalse()
    assertThat((controller.ui.value.state as PeopleListState.Ready).named.map { it.name }).containsExactly("Marco")
  }

  @Test
  fun conPiuDiDueNomiSiUniscePiuAllaVolta() = runTest(UnconfinedTestDispatcher()) {
    world.people.rename(world.anna, "Anna")
    world.people.rename(world.marco, "Marco")
    world.people.rename(world.bruno, "Bruno")
    val controller = PeopleListController(world.people, backgroundScope)
    controller.startWith(world.anna)
    controller.toggle(world.marco)
    controller.toggle(world.bruno)
    controller.mergeSelected()
    assertThat(controller.ui.value.merge).isNull()
    assertThat(controller.ui.value.message).isEqualTo(PeopleMessage.TOO_MANY_NAMES)
    controller.dismissMessage()
    assertThat(controller.ui.value.message).isNull()
  }

  @Test
  fun unNomeSoloRestaQuelloEUnaSolaPersonaNonSiUnisce() = runTest(UnconfinedTestDispatcher()) {
    world.people.rename(world.anna, "Anna")
    val controller = PeopleListController(world.people, backgroundScope)
    controller.startWith(world.anna)
    controller.mergeSelected() // una persona sola: niente da unire
    assertThat(controller.ui.value.merge).isNull()
    // Un nome e un gruppo senza nome (Q2 a): si va dritti alla conferma, e resta il nome.
    val others = PeopleFixture(namedOnly = false)
    others.people.rename(others.anna, "Anna")
    val c2 = PeopleListController(others.people, backgroundScope)
    c2.startWith(others.anna)
    c2.toggle(others.marco)
    c2.mergeSelected()
    val confirm = c2.ui.value.merge!!.sheet as MergeSheetState.Confirm
    assertThat(confirm.keepNameOf).isEqualTo(others.anna)
    assertThat(confirm.persons).hasSize(2)
    c2.confirmMerge()
    assertThat(others.people.resolve(others.marco)).isEqualTo(others.anna)
    assertThat(others.people.list.value.named.single().photos).isEqualTo(PeopleFixture.PHOTOS - 1 + PeopleFixture.PHOTOS)
  }

  /** "E' la stessa persona?": le proposte stanno in cima, un tocco apre il foglio, "No" separa per sempre. */
  @Test
  fun leProposteSiVedonoESiRifiutano() = runTest(UnconfinedTestDispatcher()) {
    val controller = PeopleListController(world.people, backgroundScope)
    val proposal = controller.ui.value.proposals.single()
    assertThat(proposal.pair.first).isIn(listOf(world.anna, world.marco))
    controller.openProposal(proposal)
    val confirm = controller.ui.value.merge!!.sheet as MergeSheetState.Confirm
    assertThat(confirm.proposal).isEqualTo(proposal)
    controller.rejectProposal()
    assertThat(controller.ui.value.merge).isNull()
    assertThat(world.people.edits.last()).isInstanceOf(PeopleEdit.Apart::class.java)
    assertThat(controller.ui.value.proposals).isEmpty()
  }

  @Test
  fun rispondereSiUniscesubito_ma_solo_dopoIlFoglio() = runTest(UnconfinedTestDispatcher()) {
    val controller = PeopleListController(world.people, backgroundScope)
    controller.openProposal(controller.ui.value.proposals.single())
    assertThat(world.people.edits).isEmpty()
    controller.confirmMerge()
    assertThat(world.people.edits.single()).isInstanceOf(PeopleEdit.Alias::class.java)
    assertThat(controller.ui.value.proposals).isEmpty()
    controller.dismissMerge()
    assertThat(controller.ui.value.merge).isNull()
  }

  /** Una proposta fra due persone con un nome: prima si sceglie quale tenere, e anche da quel foglio si puo' dire "No, sono diverse". */
  @Test
  fun unaPropostaFraDueNomi_sipuoRifiutareAncheDalFoglioDeiNomi() = runTest(UnconfinedTestDispatcher()) {
    world.people.rename(world.anna, "Anna")
    world.people.rename(world.marco, "Marco")
    val controller = PeopleListController(world.people, backgroundScope)
    val proposal = controller.ui.value.proposals.single()
    controller.openProposal(proposal)
    val pick = controller.ui.value.merge!!.sheet as MergeSheetState.PickName
    assertThat(pick.proposal).isEqualTo(proposal)
    controller.pickName(world.anna)
    assertThat((controller.ui.value.merge!!.sheet as MergeSheetState.PickName).proposal).isEqualTo(proposal) // scegliere il nome non perde la proposta
    controller.rejectProposal()
    assertThat(controller.ui.value.merge).isNull()
    assertThat(world.people.edits.last()).isInstanceOf(PeopleEdit.Apart::class.java)
    assertThat(controller.ui.value.proposals).isEmpty()
  }
}
