package dev.pampa.mycloudgallery.feature.people

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.feature.people.contract.FaceReviewState
import dev.pampa.mycloudgallery.feature.people.contract.MergeSheetState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleMessage
import dev.pampa.mycloudgallery.feature.people.contract.ReviewPurpose
import dev.pampa.mycloudgallery.feature.people.list.OthersController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * "Altri volti" (Fase 8, P9; Q2 (b), §1.5 `FaceReviewState` con `ReviewPurpose.OTHERS`, E-F23, E-F27, E-F38): i gruppi senza nome con almeno 3 foto
 * mostrabili, la selezione, l'unione fra gruppi e "Dimentica" con la conferma. Sul riferimento `InMemoryPeople`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OthersControllerTest {

  private val world = PeopleFixture()

  private fun OthersController.grid(): FaceReviewState.Grid = ui.value.state as FaceReviewState.Grid

  @Test
  fun iGruppiSenzaNome_ilGruppoSoloNascostoNonCe() = runTest(UnconfinedTestDispatcher()) {
    val controller = OthersController(world.people, world.personFaces, backgroundScope)
    val grid = controller.grid()
    assertThat(grid.purpose).isEqualTo(ReviewPurpose.OTHERS)
    assertThat(grid.person).isNull()
    assertThat(controller.ui.value.groups.keys).containsExactly(world.anna, world.marco, world.bruno)
    assertThat(grid.faces.map { it.person }).containsExactly(world.anna, world.marco, world.bruno)
    assertThat(grid.selection).isEmpty()
    // Nessun volto viene da un media nascosto (D28): la griglia porta solo celle mostrabili.
    assertThat(grid.faces.map { it.media }.toSet().intersect(world.hidden)).isEmpty()
    // Dal piu' grande al piu' piccolo: Anna ha 4 foto mostrabili, gli altri 5.
    assertThat(controller.ui.value.groups.getValue(world.anna).photos).isEqualTo(PeopleFixture.PHOTOS - 1)
    assertThat(grid.faces.last().person).isEqualTo(world.anna)
  }

  @Test
  fun dareUnNome_ilGruppoEsceDaAltriVolti() = runTest(UnconfinedTestDispatcher()) {
    val controller = OthersController(world.people, world.personFaces, backgroundScope)
    world.people.rename(world.marco, "Marco")
    assertThat(controller.ui.value.groups.keys).containsExactly(world.anna, world.bruno)
  }

  @Test
  fun conQ2a_laListaEVuota() = runTest(UnconfinedTestDispatcher()) {
    val all = PeopleFixture(namedOnly = false)
    val controller = OthersController(all.people, all.personFaces, backgroundScope)
    assertThat(controller.grid().faces).isEmpty()
  }

  @Test
  fun laSelezioneSiFaEDisfa_ilVoltoRimandaAlGruppo() = runTest(UnconfinedTestDispatcher()) {
    val controller = OthersController(world.people, world.personFaces, backgroundScope)
    controller.toggle(world.marco) // fuori dalla selezione un tocco non sceglie
    assertThat(controller.grid().selection).isEmpty()
    controller.startWith(world.marco)
    assertThat(controller.ui.value.selecting).isTrue()
    val marcoFace = controller.grid().faces.single { it.person == world.marco }.face
    assertThat(controller.grid().selection).containsExactly(marcoFace)
    assertThat(controller.groupOf(marcoFace)).isEqualTo(world.marco)
    controller.toggle(world.bruno)
    assertThat(controller.grid().selection).hasSize(2)
    controller.toggle(world.marco)
    assertThat(controller.grid().selection).hasSize(1)
    controller.setSelecting(false)
    assertThat(controller.ui.value.selecting).isFalse()
    assertThat(controller.grid().selection).isEmpty()
  }

  /** Due gruppi senza nome sono la stessa persona: si uniscono dopo la conferma, resta il piu' fotografato. */
  @Test
  fun unireDueGruppiSenzaNome() = runTest(UnconfinedTestDispatcher()) {
    val controller = OthersController(world.people, world.personFaces, backgroundScope)
    controller.startWith(world.anna)
    controller.mergeSelected() // un gruppo solo: niente da unire
    assertThat(controller.ui.value.merge).isNull()
    controller.toggle(world.marco)
    controller.mergeSelected()
    val confirm = controller.ui.value.merge!!.sheet as MergeSheetState.Confirm
    assertThat(confirm.persons.map { it.id }).containsExactly(world.anna, world.marco)
    assertThat(confirm.keepNameOf).isNull()
    assertThat(world.people.edits).isEmpty()
    controller.confirmMerge()
    assertThat(world.people.edits.single()).isEqualTo(PeopleEdit.Alias(world.anna, world.marco)) // Marco ha piu' foto mostrabili
    assertThat(controller.ui.value.merge).isNull()
    assertThat(controller.ui.value.selecting).isFalse()
    assertThat(controller.ui.value.groups.keys).containsExactly(world.marco, world.bruno)
    assertThat(controller.ui.value.groups.getValue(world.marco).photos).isEqualTo(2 * PeopleFixture.PHOTOS - 1)
  }

  /** E-F27: "Dimentica" chiede la conferma e non si fa senza una selezione. */
  @Test
  fun dimenticareUnGruppoChiedeLaConferma() = runTest(UnconfinedTestDispatcher()) {
    val controller = OthersController(world.people, world.personFaces, backgroundScope)
    controller.askForget()
    assertThat(controller.ui.value.forgetting).isFalse() // niente di scelto
    controller.startWith(world.bruno)
    controller.askForget()
    assertThat(controller.ui.value.forgetting).isTrue()
    assertThat(world.people.edits).isEmpty()
    controller.dismissForget()
    assertThat(controller.ui.value.forgetting).isFalse()
    controller.askForget()
    controller.confirmForget()
    assertThat(world.people.edits).containsExactly(PeopleEdit.Forget(world.bruno), PeopleEdit.Name(world.bruno, null)).inOrder()
    assertThat(controller.ui.value.groups.keys).containsExactly(world.anna, world.marco)
    assertThat(controller.ui.value.selecting).isFalse()
    assertThat(controller.ui.value.forgetting).isFalse()
  }

  @Test
  fun senzaPersoneAttive_ilMessaggioLoDice() = runTest(UnconfinedTestDispatcher()) {
    val controller = OthersController(world.people, world.personFaces, backgroundScope)
    controller.startWith(world.anna)
    controller.toggle(world.marco)
    controller.mergeSelected()
    world.people.available.value = false
    controller.confirmMerge()
    assertThat(controller.ui.value.message).isEqualTo(PeopleMessage.NOT_ACTIVE)
    controller.dismissMessage()
    assertThat(controller.ui.value.message).isNull()
  }
}
