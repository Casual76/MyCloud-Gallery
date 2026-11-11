package dev.pampa.mycloudgallery.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IndexerModelRequestsTest {
  @Test fun scadenzaLocaleNonChiudeLOsservatore_eIlCambioRuoloRiprova() = runTest {
    val roles = MutableStateFlow(true)
    var marked = false
    var firstAttempts = 0
    val requests = mutableListOf<ModelTrigger>()
    val observer = backgroundScope.launch {
      watchIndexerModels(roles, { true }, { true }, firstRequestDone = { marked },
        markFirstRequestDone = { marked = true }, firstRequest = {
          firstAttempts++
          kotlinx.coroutines.withTimeout(QUEUE_LIMIT_MS) { kotlinx.coroutines.delay(20_000) }
        }, request = { requests += it })
    }
    runCurrent(); advanceTimeBy(10_001); runCurrent()
    assertThat(observer.isActive).isTrue()
    assertThat(marked).isFalse()
    assertThat(firstAttempts).isEqualTo(1)
    advanceTimeBy(60_000); runCurrent()
    assertThat(firstAttempts).isEqualTo(1) // nessun ciclo di retry
    roles.value = false; runCurrent(); roles.value = true; runCurrent()
    assertThat(requests).containsExactly(ModelTrigger.ROLE_CHANGED)
    assertThat(observer.isActive).isTrue()
  }

  @Test fun cancellareIlChiamanteFermaDavveroLOsservatore() = runTest {
    val observer = backgroundScope.launch {
      kotlinx.coroutines.withTimeout(CALLER_LIMIT_MS) {
        watchIndexerModels(MutableStateFlow(true), { true }, { true }, firstRequestDone = { false },
          firstRequest = { kotlinx.coroutines.delay(20_000) }, request = {})
      }
    }
    runCurrent(); advanceTimeBy(51); runCurrent()
    assertThat(observer.isCancelled).isTrue()
  }

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

  private companion object {
    // Tempo virtuale di `runTest` (lo fa avanzare `advanceTimeBy`), non attese vere: la barriera `NoFixedWaitsTest` vuole comunque un nome.
    /** Il limite dell'accodamento della prima richiesta, come in produzione. */
    const val QUEUE_LIMIT_MS = 10_000L

    /** Il limite del chiamante che cancella l'osservatore. */
    const val CALLER_LIMIT_MS = 50L
  }
}
