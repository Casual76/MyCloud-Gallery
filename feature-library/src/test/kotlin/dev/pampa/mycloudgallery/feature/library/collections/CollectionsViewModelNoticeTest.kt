package dev.pampa.mycloudgallery.feature.library.collections

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.LocalMediaActions
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
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
    CollectionsViewModel(world.source, FakePhoneMediaIntents(), actions, fakeGridEnvironment())

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

  @Test
  fun quandoVaBeneNonCeNessunAvviso() {
    val vm = viewModel(world.actions)

    vm.hide(some)
    vm.setFavorite(world.visibleIds.drop(5).take(1).toSet(), true)

    assertThat(vm.notice.value).isNull()
    assertThat(world.hiddenIds).containsAtLeastElementsIn(some)
  }
}
