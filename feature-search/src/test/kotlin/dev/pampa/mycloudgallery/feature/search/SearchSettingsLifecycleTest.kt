package dev.pampa.mycloudgallery.feature.search

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.*
import dev.pampa.mycloudgallery.core.models.contract.*
import dev.pampa.mycloudgallery.core.models.testing.FakeModelManager
import dev.pampa.mycloudgallery.core.search.noop.NoSearchEngine
import dev.pampa.mycloudgallery.core.search.testing.InMemorySearchHistory
import dev.pampa.mycloudgallery.feature.search.contract.IndexerBacklog
import dev.pampa.mycloudgallery.feature.search.models.SearchSettingsController
import dev.pampa.mycloudgallery.feature.search.models.observeProbeWhileStarted
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchSettingsLifecycleTest {
  private class Page : LifecycleOwner {
    override val lifecycle = LifecycleRegistry.createUnsafe(this)
  }

  @Test fun caldoNascostoNonSiSvegliaNeMisura_eRiprendeSoloStarted() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    val page = Page()
    page.lifecycle.currentState = Lifecycle.State.CREATED
    val indexer = FakeIndexerControl(backlog = IndexerBacklog.NONE).apply { this.indexer.value = true }
    val models = FakeModelManager().apply { set(ModelCatalog.INDEX_440M.id, ModelState.Ready(1, ModelSourceKind.NAS, 0)) }
    var hot = true
    var measures = 0
    var checks = 0
    val probe = object : DeviceProbe {
      override suspend fun availability(): ProbeAvailability { checks++; return if (hot) ProbeAvailability.HOT else ProbeAvailability.READY }
      override suspend fun measure(): ProbeResult {
        measures++
        if (hot) throw ProbeUnavailable(ProbeAvailability.HOT)
        return ProbeResult(1.0, AiBackend.CPU, 0)
      }
    }
    val controller = SearchSettingsController(indexer, models, NoSearchEngine, InMemorySearchHistory(), null, null, probe, backgroundScope, dispatcher, dispatcher)
    val binding = backgroundScope.launch { observeProbeWhileStarted(controller, page.lifecycle) }
    try {
      runCurrent()
      assertThat(measures).isEqualTo(0)
      page.lifecycle.currentState = Lifecycle.State.STARTED; runCurrent()
      assertThat(controller.probeHot.value).isTrue()
      assertThat(measures).isEqualTo(1)
      page.lifecycle.currentState = Lifecycle.State.CREATED
      controller.measure() // anche prima del finally del blocco cancellato
      runCurrent()
      hot = false
      advanceTimeBy(10_000); runCurrent()
      controller.measure(); runCurrent()
      assertThat(checks).isEqualTo(0)
      assertThat(measures).isEqualTo(1)
      page.lifecycle.currentState = Lifecycle.State.STARTED; runCurrent()
      advanceTimeBy(2001); runCurrent()
      assertThat(measures).isEqualTo(2)
      assertThat(controller.state.value.indexer.probe).isNotNull()
      val settledChecks = checks
      advanceTimeBy(10_000); runCurrent()
      assertThat(checks).isEqualTo(settledChecks)
      assertThat(measures).isEqualTo(2)
    } finally {
      page.lifecycle.currentState = Lifecycle.State.DESTROYED
      binding.cancel(); runCurrent(); Dispatchers.resetMain()
    }
  }

  @Test fun modelloProntoInBackgroundAspettaLaPaginaVisible() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    val page = Page()
    page.lifecycle.currentState = Lifecycle.State.CREATED
    val indexer = FakeIndexerControl(backlog = IndexerBacklog.NONE).apply { this.indexer.value = true }
    val models = FakeModelManager()
    val probe = dev.pampa.mycloudgallery.core.ai.testing.FakeDeviceProbe(ProbeResult(1.0, AiBackend.CPU, 0))
    val controller = SearchSettingsController(indexer, models, NoSearchEngine, InMemorySearchHistory(), null, null, probe, backgroundScope, dispatcher, dispatcher)
    val binding = backgroundScope.launch { observeProbeWhileStarted(controller, page.lifecycle) }
    try {
      runCurrent()
      models.set(ModelCatalog.INDEX_440M.id, ModelState.Ready(1, ModelSourceKind.NAS, 0)); runCurrent()
      advanceTimeBy(5000); runCurrent()
      assertThat(probe.measures).isEqualTo(0)
      page.lifecycle.currentState = Lifecycle.State.STARTED; runCurrent()
      assertThat(probe.measures).isEqualTo(1)
    } finally {
      page.lifecycle.currentState = Lifecycle.State.DESTROYED
      binding.cancel(); runCurrent(); Dispatchers.resetMain()
    }
  }
}
