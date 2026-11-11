package dev.pampa.mycloudgallery.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IndexerModelRequestsTest {
  @Test fun onboardingRichiedeUnaVoltaPoiIlSegnoDuraFraProcessi() = runTest {
    val file = java.io.File(java.nio.file.Files.createTempDirectory("hr-first").toFile(), "done")
    val triggers = mutableListOf<ModelTrigger>()
    var ready = false
    repeat(2) {
      val marker = FirstIndexerRequest(file, StandardTestDispatcher(testScheduler))
      val job = backgroundScope.launch {
        watchIndexerModels(MutableStateFlow(true), { true }, { !ready },
          firstRequestDone = marker::done, markFirstRequestDone = marker::mark,
          request = { triggers += it; ready = true })
      }
      // IO reale del segno: aspetta il suo completamento senza tempi fissi.
      runCurrent(); assertThat(file.exists()).isTrue()
      runCurrent(); job.cancel()
    }
    assertThat(triggers).containsExactly(ModelTrigger.ROLE_CHANGED)
  }

  @Test fun dueAvviiNonSonoDueCambiDiRuolo() = runTest {
    val requests = mutableListOf<ModelTrigger>()
    repeat(2) {
      val job = backgroundScope.launch { watchIndexerModels(MutableStateFlow(true), { true }, { true }) { requests += it } }
      runCurrent(); job.cancel()
    }
    assertThat(requests).containsExactly(ModelTrigger.RULES, ModelTrigger.RULES)
  }
  @Test fun modelloProntoORimossoNonAccodaNiente() = runTest {
    var requests = 0
    repeat(2) {
      backgroundScope.launch { watchIndexerModels(MutableStateFlow(true), { true }, { false }) { requests++ } }
      runCurrent()
    }
    assertThat(requests).isEqualTo(0)
  }
  @Test fun soloIlVeroPassaggioAccendeIlRuolo() = runTest {
    val roles = MutableStateFlow(false)
    val requests = mutableListOf<ModelTrigger>()
    backgroundScope.launch { watchIndexerModels(roles, { true }, { true }) { requests += it } }
    runCurrent(); roles.value = true; runCurrent()
    assertThat(requests).containsExactly(ModelTrigger.ROLE_CHANGED)
  }
  @Test fun nessunDownloadNellaVarianteDiMisura() = runTest {
    var requests = 0
    backgroundScope.launch { watchIndexerModels(MutableStateFlow(true), { false }, { true }) { requests++ } }
    runCurrent(); assertThat(requests).isEqualTo(0)
  }
}
