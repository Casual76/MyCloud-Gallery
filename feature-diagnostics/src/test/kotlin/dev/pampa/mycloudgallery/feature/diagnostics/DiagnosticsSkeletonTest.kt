package dev.pampa.mycloudgallery.feature.diagnostics

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagEvent
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.RingDiagnosticsLog
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DiagnosticsSkeletonTest {

  private class Contributor(override val section: DiagSection, private val items: () -> List<DiagItem>) : DiagnosticsContributor {
    override suspend fun collect(): List<DiagItem> = items()
  }

  private fun item(code: String, section: DiagSection) = DiagItem(DiagCode(code), section, DiagSeverity.INFO, titleRes = 0)

  @Test
  fun leVociSiRaggruppanoPerSezioneEUnContributoreRottoNonTogliGliAltri() = runTest {
    val log = RingDiagnosticsLog(capacity = 10)
    log.record(DiagEvent(1L, DiagCode("NAS_TIMEOUT"), DiagSeverity.WARNING))
    val contributors = setOf<DiagnosticsContributor>(
      Contributor(DiagSection.NETWORK_AND_NAS) { listOf(item("NET_HOME", DiagSection.NETWORK_AND_NAS), item("NAS_READY", DiagSection.NETWORK_AND_NAS)) },
      Contributor(DiagSection.TRUST) { error("contributore rotto") },
      Contributor(DiagSection.ANDROID_WARNINGS) { listOf(item("AND_BATTERY", DiagSection.ANDROID_WARNINGS)) },
    )

    val state = DiagnosticsViewModel.collect(contributors, log)

    assertThat(state.loading).isFalse()
    assertThat(state.sections[DiagSection.NETWORK_AND_NAS]).hasSize(2)
    assertThat(state.sections[DiagSection.ANDROID_WARNINGS]).hasSize(1)
    assertThat(state.sections[DiagSection.TRUST]).isNull()
    assertThat(state.recentEvents).isEqualTo(1)
  }
}
