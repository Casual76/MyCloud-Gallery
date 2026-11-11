package dev.pampa.mycloudgallery.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IndexerModelRequestsTest {
  @Test fun ruoloGiaAccesoAllOnboardingRichiedeSubitoIlModello() = runTest {
    val roles = MutableStateFlow(true)
    var skipped = 0
    val previous = backgroundScope.launch { roles.drop(1).collect { if (it) skipped++ } }
    runCurrent(); assertThat(skipped).isEqualTo(0)
    previous.cancel()
    var requests = 0
    backgroundScope.launch { watchIndexerModels(roles, { true }) { requests++ } }
    runCurrent(); assertThat(requests).isEqualTo(1)
    roles.value = false; runCurrent(); roles.value = true; runCurrent()
    assertThat(requests).isEqualTo(2)
  }
  @Test fun nessunDownloadNellaVarianteDiMisura() = runTest {
    var requests = 0
    backgroundScope.launch { watchIndexerModels(MutableStateFlow(true), { false }) { requests++ } }
    runCurrent(); assertThat(requests).isEqualTo(0)
  }
}
