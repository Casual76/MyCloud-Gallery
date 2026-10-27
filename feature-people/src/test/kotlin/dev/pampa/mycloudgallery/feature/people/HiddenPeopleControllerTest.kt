package dev.pampa.mycloudgallery.feature.people

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.contract.HiddenPeopleState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleMessage
import dev.pampa.mycloudgallery.feature.people.hidden.HiddenPeopleController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * "Persone nascoste" (Fase 8, P9; E-F28, D28): chi e' nascosto si ritrova e si mostra di nuovo con un tocco; una persona con soli media nascosti non
 * c'e' fra loro. [HiddenPeople] qui e' un riferimento fatto sulle persone del riferimento `InMemoryPeople`, che le vere (core-faces) provano per conto
 * loro.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HiddenPeopleControllerTest {

  private val world = PeopleFixture()

  private fun controller(scope: kotlinx.coroutines.CoroutineScope) =
    HiddenPeopleController(HiddenOfPeople(world.people, listOf(world.anna, world.marco, world.bruno, world.hiddenOnly)), world.people, scope)

  @Test
  fun senzaNascosti_ilElencoEVuoto() = runTest(UnconfinedTestDispatcher()) {
    assertThat(controller(backgroundScope).ui.value).isEqualTo(HiddenPeopleState.Empty)
  }

  @Test
  fun nascondereEMostrareDiNuovo() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(backgroundScope)
    world.people.rename(world.marco, "Marco")
    world.people.setHidden(world.marco, true)
    val ready = c.ui.value as HiddenPeopleState.Ready
    assertThat(ready.people.map { it.id }).containsExactly(world.marco)
    assertThat(ready.people.single().name).isEqualTo("Marco")
    c.show(world.marco)
    assertThat(c.ui.value).isEqualTo(HiddenPeopleState.Empty)
    assertThat(world.people.edits.last()).isEqualTo(PeopleEdit.Hidden(world.marco, false))
    assertThat(c.notice.value).isNull()
  }

  /** D28: una persona con soli media nascosti non e' fra le nascoste (non si vede in nessun elenco). */
  @Test
  fun chiHaSoloMediaNascostiNonCe() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(backgroundScope)
    world.people.setHidden(world.hiddenOnly, true)
    assertThat(c.ui.value).isEqualTo(HiddenPeopleState.Empty)
  }

  @Test
  fun mostrareUnaPersonaCheNonCePiu_loDice() = runTest(UnconfinedTestDispatcher()) {
    val c = controller(backgroundScope)
    c.show(PersonId.random())
    assertThat(c.notice.value).isEqualTo(PeopleMessage.NOT_FOUND)
    c.dismissNotice()
    assertThat(c.notice.value).isNull()
    world.people.available.value = false
    c.show(world.anna)
    assertThat(c.notice.value).isEqualTo(PeopleMessage.NOT_ACTIVE)
  }
}
