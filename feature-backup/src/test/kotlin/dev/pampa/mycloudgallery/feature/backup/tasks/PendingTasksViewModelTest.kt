package dev.pampa.mycloudgallery.feature.backup.tasks

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.PendingTask
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskKind
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskState
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryPendingTasks
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.VersionId
import java.util.Optional
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * "Da decidere" (`docs/fase5-progetto.md` §1.5 `PendingTasksState`, §2.6, D20): il ViewModel legge l'elenco dal contratto e inoltra i due tocchi.
 * "Tienilo" chiude l'attivita' senza toccare il NAS; "Elimina l'originale" non cancella niente da qui, passa a `:app` (il foglio di feature-nas).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingTasksViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()
  private val tasks = InMemoryPendingTasks()

  @Before
  fun setUp() = Dispatchers.setMain(dispatcher)

  @After
  fun tearDown() = Dispatchers.resetMain()

  private fun add(n: Long, bytes: Long = 1_200_000_000L, at: Long = 1_792_000_000_000L + n): PendingTask = tasks.add { id ->
    PendingTask(
      id = id,
      kind = PendingTaskKind.EDITED_VIDEO_ORIGINAL,
      state = PendingTaskState.OPEN,
      mediaId = MediaId(UUID(0x7A5C, n).toString()),
      versionId = VersionId(UUID(0x7A5D, n).toString()),
      bytes = bytes,
      createdAtNasMillis = at,
    )
  }

  private class RecordingTaskActions : PendingTaskActions {
    val deleted = ArrayList<PendingTask>()

    override fun deleteOriginal(task: PendingTask) {
      deleted += task
    }
  }

  @Test
  fun senzaIServiziNonCeNienteDaDecidere() = runTest(dispatcher) {
    val vm = PendingTasksViewModel(Optional.empty(), Optional.empty())
    backgroundScope.launch { vm.state.collect {} }
    assertThat(vm.state.value.tasks).isEmpty()
    assertThat(vm.state.value.canDeleteOriginal).isFalse()
    // Un tocco senza servizi non fa niente.
    vm.keep(add(1))
    assertThat(tasks.tasks.value).hasSize(1)
  }

  @Test
  fun laListaSegueLeAttivitaAperteDallaPiuVecchia() = runTest(dispatcher) {
    add(2, at = 2_000)
    add(1, at = 1_000)
    val vm = PendingTasksViewModel(Optional.of(tasks), Optional.of(RecordingTaskActions()))
    backgroundScope.launch { vm.state.collect {} }
    assertThat(vm.state.value.tasks.map { it.createdAtNasMillis }).containsExactly(1_000L, 2_000L).inOrder()
    assertThat(vm.state.value.canDeleteOriginal).isTrue()
    add(3, at = 3_000)
    assertThat(vm.state.value.tasks).hasSize(3)
  }

  @Test
  fun tienilo_ChiudeLAttivitaENonTocaIlNas() = runTest(dispatcher) {
    val first = add(1)
    val second = add(2)
    val actions = RecordingTaskActions()
    val vm = PendingTasksViewModel(Optional.of(tasks), Optional.of(actions))
    backgroundScope.launch { vm.state.collect {} }
    vm.keep(first)
    assertThat(vm.state.value.tasks.map { it.id }).containsExactly(second.id)
    assertThat(tasks.everything.first { it.id == first.id }.state).isEqualTo(PendingTaskState.KEPT)
    // Nessuna cancellazione e' stata chiesta a nessuno.
    assertThat(actions.deleted).isEmpty()
  }

  @Test
  fun eliminaLOriginaleNonCancellaNienteDaQuiMaPassaAApp() = runTest(dispatcher) {
    val task = add(1)
    val actions = RecordingTaskActions()
    val vm = PendingTasksViewModel(Optional.of(tasks), Optional.of(actions))
    backgroundScope.launch { vm.state.collect {} }
    vm.deleteOriginal(task)
    assertThat(actions.deleted).containsExactly(task)
    // L'attivita' resta aperta finche' `:app` non dice che l'operazione e' partita (`complete`).
    assertThat(vm.state.value.tasks).hasSize(1)
  }

  @Test
  fun senzaIlFoglioDiApp_ILTastoElimina_NonCe() = runTest(dispatcher) {
    add(1)
    val vm = PendingTasksViewModel(Optional.of(tasks), Optional.empty())
    backgroundScope.launch { vm.state.collect {} }
    assertThat(vm.state.value.canDeleteOriginal).isFalse()
    vm.deleteOriginal(vm.state.value.tasks.single())
    assertThat(vm.state.value.tasks).hasSize(1)
  }
}
