package dev.pampa.mycloudgallery.feature.backup.received

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.backup.testing.FakeReceivedQueue
import dev.pampa.mycloudgallery.feature.backup.testing.ReceivedSamples
import java.util.Optional
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
 * "Ricevuti in coda" (correzioni 5B, IN-7): il ViewModel legge l'elenco dalla porta e inoltra i due tocchi. "Riprova" parte subito e dice com'e' andata;
 * "Scarta" chiede prima una conferma e non toglie niente finche' l'utente non la da'. Senza la porta (varianti senza NAS) l'elenco e' vuoto e un tocco
 * non fa niente.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceivedViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()
  private val queue = FakeReceivedQueue(ReceivedSamples.few)

  @Before
  fun setUp() = Dispatchers.setMain(dispatcher)

  @After
  fun tearDown() = Dispatchers.resetMain()

  private fun viewModel(): ReceivedViewModel = ReceivedViewModel(Optional.of(queue))

  @Test
  fun senzaLaPortaLElencoEVuotoEUnTaccoNonFaNiente() = runTest(dispatcher) {
    val vm = ReceivedViewModel(Optional.empty())
    backgroundScope.launch { vm.state.collect {} }
    assertThat(vm.state.value.items).isEmpty()
    vm.retry(ReceivedSamples.item(1, ReceivedStatus.QUEUED))
    vm.askDiscard(ReceivedSamples.item(1, ReceivedStatus.QUEUED))
    vm.confirmDiscard()
    assertThat(vm.state.value.notice).isNull()
    assertThat(queue.retried).isEmpty()
  }

  @Test
  fun lElencoSegueLaPortaESiAggiornaDaSolo() = runTest(dispatcher) {
    val vm = viewModel()
    backgroundScope.launch { vm.state.collect {} }
    assertThat(vm.state.value.items).hasSize(3)
    queue.items.value = queue.items.value + ReceivedSamples.item(4, ReceivedStatus.PASSWORD)
    assertThat(vm.state.value.items).hasSize(4)
    queue.items.value = emptyList()
    assertThat(vm.state.value.items).isEmpty()
  }

  @Test
  fun riprovaParteSubitoEDiceChiEraInCoda() = runTest(dispatcher) {
    val vm = viewModel()
    backgroundScope.launch { vm.state.collect {} }
    val failed = queue.items.value.first()
    vm.retry(failed)
    assertThat(queue.retried).containsExactly(failed.id)
    assertThat(vm.state.value.notice).isEqualTo(ReceivedNotice.Requeued)
  }

  @Test
  fun riprovaDiceChiLoBloccaESeLaCopiaNonCePiu() = runTest(dispatcher) {
    val vm = viewModel()
    backgroundScope.launch { vm.state.collect {} }
    val item = queue.items.value.first()
    queue.retryResult = ReceivedRetry.Blocked(ReceivedStatus.TRIAL)
    vm.retry(item)
    assertThat(vm.state.value.notice).isEqualTo(ReceivedNotice.Blocked(ReceivedStatus.TRIAL))
    queue.retryResult = ReceivedRetry.NoCopy
    vm.retry(item)
    assertThat(vm.state.value.notice).isEqualTo(ReceivedNotice.NoCopy)
    queue.retryResult = ReceivedRetry.AlreadySaved
    vm.retry(item)
    assertThat(vm.state.value.notice).isEqualTo(ReceivedNotice.AlreadySaved)
    queue.retryResult = ReceivedRetry.Unknown
    vm.retry(item)
    assertThat(vm.state.value.notice).isEqualTo(ReceivedNotice.Unknown)
  }

  @Test
  fun scartaChiedePrimaLaConfermaENonTogliNienteFinoAlSi() = runTest(dispatcher) {
    val vm = viewModel()
    backgroundScope.launch { vm.state.collect {} }
    val item = queue.items.value[2]
    vm.askDiscard(item)
    assertThat(vm.state.value.discarding?.id).isEqualTo(item.id)
    assertThat(queue.discarded).isEmpty()
    assertThat(queue.items.value).hasSize(3)

    vm.cancelDiscard()
    assertThat(vm.state.value.discarding).isNull()
    assertThat(queue.discarded).isEmpty()

    vm.askDiscard(item)
    vm.confirmDiscard()
    assertThat(queue.discarded).containsExactly(item.id)
    assertThat(vm.state.value.discarding).isNull()
    assertThat(vm.state.value.notice).isEqualTo(ReceivedNotice.Discarded)
    assertThat(vm.state.value.items.map { it.id }).doesNotContain(item.id)
  }

  @Test
  fun unFileGiaPubblicatoOCheStaSalendoNonSiScarta() = runTest(dispatcher) {
    val vm = viewModel()
    backgroundScope.launch { vm.state.collect {} }
    val item = queue.items.value.first()
    queue.discardResult = ReceivedDiscard.AlreadySaved
    vm.askDiscard(item)
    vm.confirmDiscard()
    assertThat(vm.state.value.notice).isEqualTo(ReceivedNotice.AlreadySaved)
    queue.discardResult = ReceivedDiscard.Uploading
    vm.askDiscard(item)
    vm.confirmDiscard()
    assertThat(vm.state.value.notice).isEqualTo(ReceivedNotice.Uploading)
    // Il file e' ancora in coda: niente e' sparito.
    assertThat(vm.state.value.items).hasSize(3)
  }

  @Test
  fun laConfermaSiChiudeSeIlFileSeNeVaPrimaDelSi() = runTest(dispatcher) {
    val vm = viewModel()
    backgroundScope.launch { vm.state.collect {} }
    val item = queue.items.value.first()
    vm.askDiscard(item)
    assertThat(vm.state.value.discarding).isNotNull()
    queue.items.value = queue.items.value.filterNot { it.id == item.id }
    assertThat(vm.state.value.discarding).isNull()
  }

  @Test
  fun unNuovoTaccoToglieLaFraseVecchia() = runTest(dispatcher) {
    val vm = viewModel()
    backgroundScope.launch { vm.state.collect {} }
    vm.retry(queue.items.value.first())
    assertThat(vm.state.value.notice).isNotNull()
    vm.askDiscard(queue.items.value.first())
    assertThat(vm.state.value.notice).isNull()
    vm.retry(queue.items.value.first())
    vm.dismissNotice()
    assertThat(vm.state.value.notice).isNull()
  }

  @Test
  fun iContiDellaPaginaSonoPerPunto() {
    val counts = ReceivedCounts.of(ReceivedSamples.everyStatus)
    assertThat(counts.waiting).isEqualTo(ReceivedStatus.entries.count { it.phase == ReceivedPhase.WAITING })
    assertThat(counts.uploading).isEqualTo(ReceivedStatus.entries.count { it.phase == ReceivedPhase.UPLOADING })
    assertThat(counts.problems).isEqualTo(ReceivedStatus.entries.count { it.phase == ReceivedPhase.PROBLEM })
    assertThat(counts.total).isEqualTo(ReceivedStatus.entries.size)
    assertThat(ReceivedCounts.of(emptyList())).isEqualTo(ReceivedCounts.NONE)
  }

  @Test
  fun iTastiDipendonoDalloStato() {
    // Uno che sta salendo non si riprova ne' si scarta; senza copia sul telefono si scarta soltanto.
    val uploading = ReceivedSamples.item(1, ReceivedStatus.UPLOADING)
    assertThat(uploading.canRetry).isFalse()
    assertThat(uploading.canDiscard).isFalse()
    val missing = ReceivedSamples.item(2, ReceivedStatus.COPY_MISSING)
    assertThat(missing.canRetry).isFalse()
    assertThat(missing.canDiscard).isTrue()
    val waiting = ReceivedSamples.item(3, ReceivedStatus.NETWORK)
    assertThat(waiting.canRetry).isTrue()
    assertThat(waiting.canDiscard).isTrue()
  }
}
