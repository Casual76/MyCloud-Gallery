package dev.pampa.mycloudgallery.feature.people

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.feature.people.contract.FaceReviewState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleMessage
import dev.pampa.mycloudgallery.feature.people.contract.ReviewPurpose
import dev.pampa.mycloudgallery.feature.people.review.FaceReviewController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * "Controlla le foto" e "Dividi" (Fase 8, P9; §1.5 `FaceReviewState`, §2.6, D23, E-F3, E-F20, E-F21): la griglia dei volti di una persona (solo celle
 * mostrabili), la selezione, "Non e' …", "Ignora questo volto" e "Sposta in una persona nuova" (non tutti i volti). Sul riferimento `InMemoryPeople`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FaceReviewControllerTest {

  private val world = PeopleFixture()

  private fun controller(scope: kotlinx.coroutines.CoroutineScope, purpose: ReviewPurpose) =
    FaceReviewController(world.anna, purpose, world.people, world.personFaces, scope)

  private fun FaceReviewController.grid(): FaceReviewState.Grid = ui.value.state as FaceReviewState.Grid

  @Test
  fun laGrigliaPortaSoloVoltiMostrabili() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(backgroundScope, ReviewPurpose.CHECK)
    val grid = c.grid()
    assertThat(grid.purpose).isEqualTo(ReviewPurpose.CHECK)
    assertThat(grid.person).isEqualTo(world.anna)
    // Anna ha 5 volti, l'ultimo in una foto nascosta: non c'e' (D28).
    assertThat(grid.faces).hasSize(PeopleFixture.PHOTOS - 1)
    assertThat(grid.faces.map { it.media }.toSet().intersect(world.hidden)).isEmpty()
    assertThat(grid.selection).isEmpty()
    assertThat(grid.canApply).isFalse()
  }

  @Test
  fun unaGrigliaDiAltriVoltiNonSiApreQui() {
    assertThrows(IllegalArgumentException::class.java) {
      FaceReviewController(world.anna, ReviewPurpose.OTHERS, world.people, world.personFaces, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
    }
  }

  @Test
  fun laSelezioneSiFaEDisfa_unVoltoSconosciutoNonEntra() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(backgroundScope, ReviewPurpose.CHECK)
    val faces = c.grid().faces.map { it.face }
    c.toggle(faces[0])
    c.toggle(faces[1])
    assertThat(c.grid().selection).containsExactly(faces[0], faces[1])
    c.toggle(faces[0])
    assertThat(c.grid().selection).containsExactly(faces[1])
    c.toggle(world.facesOf(world.hiddenOnly).first().locator) // di un'altra persona: non e' in griglia
    assertThat(c.grid().selection).containsExactly(faces[1])
    c.clear()
    assertThat(c.grid().selection).isEmpty()
  }

  /** E-F21: "Non e' …" toglie i volti scelti da questa persona, uno per uno, e non ci tornano. */
  @Test
  fun nonEQuestaPersona_leVociSonoUnaPerVolto() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(backgroundScope, ReviewPurpose.CHECK)
    val faces = c.grid().faces.map { it.face }
    c.toggle(faces[0])
    c.toggle(faces[2])
    c.rejectSelected()
    assertThat(c.ui.value.state).isEqualTo(FaceReviewState.Done)
    assertThat(c.ui.value.changed).isEqualTo(2)
    assertThat(c.ui.value.message).isNull()
    assertThat(world.people.edits).containsExactly(
      PeopleEdit.Not(faces[0], world.anna, true), PeopleEdit.Not(faces[2], world.anna, true),
    )
  }

  @Test
  fun nonEQuestaPersona_suUnVoltoSolo_comeFaTalkBack() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(backgroundScope, ReviewPurpose.CHECK)
    val face = c.grid().faces[1].face
    c.rejectOne(world.facesOf(world.hiddenOnly).first().locator) // non e' in griglia: niente
    assertThat(world.people.edits).isEmpty()
    c.rejectOne(face)
    assertThat(world.people.edits.single()).isEqualTo(PeopleEdit.Not(face, world.anna, true))
    assertThat(c.ui.value.changed).isEqualTo(1)
  }

  /** E-F3: un poster, uno schermo: "Ignora questo volto". */
  @Test
  fun ignoraIVoltiScelti() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(backgroundScope, ReviewPurpose.CHECK)
    c.ignoreSelected() // niente di scelto: niente
    assertThat(world.people.edits).isEmpty()
    val face = c.grid().faces.first().face
    c.toggle(face)
    c.ignoreSelected()
    assertThat(world.people.edits.single()).isEqualTo(PeopleEdit.Ignore(face, true))
    assertThat(c.ui.value.state).isEqualTo(FaceReviewState.Done)
  }

  /** E-F20: "Dividi" sposta alcuni volti in una persona nuova; non tutti, e la prima resta con gli altri. */
  @Test
  fun dividi_nonTuttiEMaiNessuno() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(backgroundScope, ReviewPurpose.SPLIT)
    val faces = c.grid().faces.map { it.face }
    c.split() // nessuno scelto: non si fa
    assertThat(world.people.edits).isEmpty()
    faces.forEach { c.toggle(it) }
    assertThat(c.grid().canApply).isFalse() // tutti: la persona di partenza resterebbe vuota
    c.split()
    assertThat(world.people.edits).isEmpty()
    c.toggle(faces[3])
    c.toggle(faces[2])
    assertThat(c.grid().canApply).isTrue()
    c.split()
    val done = c.ui.value
    assertThat(done.state).isEqualTo(FaceReviewState.Done)
    assertThat(done.changed).isEqualTo(2)
    val fresh = done.openNext!!
    assertThat(world.people.edits.first()).isEqualTo(PeopleEdit.Born(fresh, world.anna))
    assertThat(world.people.edits.filterIsInstance<PeopleEdit.Is>().map { it.person }.toSet()).containsExactly(fresh)
    assertThat(world.people.edits.last()).isEqualTo(PeopleEdit.Apart(fresh, world.anna, true))
  }

  @Test
  fun leAzioniSonoDelloScopoGiusto() = runTest(UnconfinedTestDispatcher()) {
    val check = controller(backgroundScope, ReviewPurpose.CHECK)
    val split = controller(backgroundScope, ReviewPurpose.SPLIT)
    val a = check.grid().faces[0].face
    check.toggle(a)
    check.split() // "Dividi" e' un'altra griglia
    split.toggle(split.grid().faces[0].face)
    split.rejectSelected()
    split.ignoreSelected()
    assertThat(world.people.edits).isEmpty()
  }

  @Test
  fun unaDivisioneCheNonRiesce_laGrigliaTornaConIlMessaggio() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(backgroundScope, ReviewPurpose.SPLIT)
    c.toggle(c.grid().faces[0].face)
    world.people.available.value = false
    c.split()
    assertThat(c.ui.value.state).isInstanceOf(FaceReviewState.Grid::class.java)
    assertThat(c.grid().selection).hasSize(1)
    assertThat(c.ui.value.message).isEqualTo(PeopleMessage.NOT_ACTIVE)
    c.dismissMessage()
    assertThat(c.ui.value.message).isNull()
  }

  @Test
  fun unaPersonaSenzaVolti_haUnaGrigliaVuota() = runTest(UnconfinedTestDispatcher()) {
    val c = FaceReviewController(world.hiddenOnly, ReviewPurpose.CHECK, world.people, world.personFaces, backgroundScope)
    assertThat(c.grid().faces).isEmpty()
  }
}
