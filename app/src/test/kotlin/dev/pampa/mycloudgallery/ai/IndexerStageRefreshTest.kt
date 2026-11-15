package dev.pampa.mycloudgallery.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.AiWaitReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IndexerStageRefreshTest {
  @Test
  fun preparingEachImageDoesNotQueueFullLibraryReadsBehindASlowBacklog() = runTest {
    val status = MutableSharedFlow<AiStatus>()
    val unblock = CompletableDeferred<Unit>()
    var reads = 0
    var completed = 0
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      observeIndexerStageEnds(status) {
        backgroundScope.launch {
          reads++
          unblock.await()
          completed++
        }
      }
    }
    repeat(40) { image ->
      status.emit(AiStatus.Running(image, 40, null, AiBackend.GPU))
      status.emit(AiStatus.Waiting(AiWaitReason.PREPARATION))
    }
    runCurrent()
    assertThat(reads).isEqualTo(0)
    status.emit(AiStatus.Idle(null))
    runCurrent()
    assertThat(reads).isEqualTo(1)
    assertThat(completed).isEqualTo(0)
    unblock.complete(Unit)
    runCurrent()
    assertThat(completed).isEqualTo(1)
  }
}
