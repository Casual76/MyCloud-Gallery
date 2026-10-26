package dev.pampa.mycloudgallery.feature.library.collections

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.ActionNotice
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeGridEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Revisione di sicurezza della Fase 3, R8, nelle Raccolte: un preferito o un "Nascondi" che non riescono non cadono nel
 * `viewModelScope` (che chiuderebbe l'app) e non passano sotto silenzio: la pagina riceve un avviso da mostrare.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CollectionsViewModelNoticeTest {

  private val world = FakeCollectionsWorld()
  private val some = world.visibleIds.take(2).toSet()

  @Before
  fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

  @After
  fun resetDispatcher() = Dispatchers.resetMain()

  private class Failing : LocalMediaActions {
    override suspend fun setFavorite(ids: Set<MediaId>, on: Boolean) {
      throw IllegalStateException("disco pieno")
    }

    override suspend fun setHidden(ids: Set<MediaId>, on: Boolean) {
      throw IllegalStateException("disco pieno")
    }
  }

  private fun viewModel(actions: LocalMediaActions) =
    CollectionsViewModel(world.source, actions, fakeGridEnvironment())

  @Test
  fun nascondiCheNonRiesceDaUnAvviso() {
    val vm = viewModel(Failing())

    vm.hide(some)

    assertThat(vm.notice.value).isEqualTo(ActionNotice.HideFailed)
    vm.dismissNotice()
    assertThat(vm.notice.value).isNull()
  }

  @Test
  fun unPreferitoCheNonRiesceDaUnAvviso() {
    val vm = viewModel(Failing())

    vm.setFavorite(some, true)

    assertThat(vm.notice.value).isEqualTo(ActionNotice.FavoriteFailed)
  }

  /** Fase 7 (P9, D23, E-H2): un catalogo che scrive e sa quanti di loro i familiari vedono ancora. */
  private class FamilyAware(private val family: suspend (Set<MediaId>) -> Int) : LocalMediaActions {
    val hidden = mutableSetOf<MediaId>()

    override suspend fun setFavorite(ids: Set<MediaId>, on: Boolean) = Unit

    override suspend fun setHidden(ids: Set<MediaId>, on: Boolean) {
      if (on) hidden += ids
    }

    override suspend fun familyStillSees(ids: Set<MediaId>): Int = family(ids)
  }

  @Test
  fun nascondereCioCheIFamiliariVedonoAncoraDiceChePartecipaSoloDentroLApp() {
    val actions = FamilyAware { it.size }
    val vm = viewModel(actions)

    vm.hide(some)

    assertThat(actions.hidden).containsExactlyElementsIn(some)
    assertThat(vm.notice.value).isEqualTo(ActionNotice.FamilyStillSees(some.size))
    vm.dismissNotice()
    assertThat(vm.notice.value).isNull()
  }

  @Test
  fun nascondereCioCheNessunoVedeNonAvvisaDeiFamiliari() {
    val vm = viewModel(FamilyAware { 0 })

    vm.hide(some)

    assertThat(vm.notice.value).isNull()
  }

  /** Fallire chiusi: se non si riesce a saperlo si avvisa lo stesso (un avviso in meno lascerebbe credere che nessuno li veda). */
  @Test
  fun seNonSiSaSeIFamiliariLiVedonoSiAvvisaLoStesso() {
    val vm = viewModel(FamilyAware { error("catalogo non raggiungibile") })

    vm.hide(some)

    assertThat(vm.notice.value).isEqualTo(ActionNotice.FamilyStillSees(some.size))
  }

  /** L'avviso dei familiari non nasconde un "Nascondi" fallito: se non e' riuscito si dice solo quello. */
  @Test
  fun unNascondiFallitoNonDiceDeiFamiliari() {
    val failing = object : LocalMediaActions {
      override suspend fun setFavorite(ids: Set<MediaId>, on: Boolean) = Unit

      override suspend fun setHidden(ids: Set<MediaId>, on: Boolean) = error("disco pieno")

      override suspend fun familyStillSees(ids: Set<MediaId>): Int = ids.size
    }
    val vm = viewModel(failing)

    vm.hide(some)

    assertThat(vm.notice.value).isEqualTo(ActionNotice.HideFailed)
  }

  @Test
  fun quandoVaBeneNonCeNessunAvviso() {
    val vm = viewModel(world.actions)

    vm.hide(some)
    vm.setFavorite(world.visibleIds.drop(5).take(1).toSet(), true)

    assertThat(vm.notice.value).isNull()
    assertThat(world.hiddenIds).containsAtLeastElementsIn(some)
  }
}
