package dev.pampa.mycloudgallery.feature.diagnostics.aggregate

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagEvent
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.RingDiagnosticsLog
import dev.pampa.mycloudgallery.feature.diagnostics.FakeContributor
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.item
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsAggregatorTest {

  private val log = RingDiagnosticsLog(capacity = 10)

  @Test
  fun leVociSiRaggruppanoPerSezioneNellOrdineDelloSchermo() = runTest {
    val aggregator = DiagnosticsAggregator(
      listOf(
        FakeContributor(DiagSection.TRUST) { listOf(item(DiagCodes.TRUST_APPROVED, DiagSection.TRUST, DiagSeverity.OK)) },
        FakeContributor(DiagSection.NETWORK_AND_NAS) { listOf(item(DiagCodes.NET_PLACE, DiagSection.NETWORK_AND_NAS, DiagSeverity.OK)) },
        FakeContributor(DiagSection.STORAGE) { listOf(item(DiagCodes.STORAGE_USAGE, DiagSection.STORAGE, DiagSeverity.INFO)) },
      ),
      log,
    )

    val snapshot = aggregator.collect()

    assertThat(snapshot.sections.keys.toList())
      .containsExactly(DiagSection.NETWORK_AND_NAS, DiagSection.STORAGE, DiagSection.TRUST)
      .inOrder()
  }

  @Test
  fun unContributorePuoPortareVociDiPiuSezioni() = runTest {
    val aggregator = DiagnosticsAggregator(
      listOf(
        FakeContributor(DiagSection.ANDROID_WARNINGS) {
          listOf(
            item(DiagCodes.AND_BUCKET_OK, DiagSection.ANDROID_WARNINGS, DiagSeverity.OK),
            item(DiagCodes.PROFILE_PRIMARY, DiagSection.PROFILES, DiagSeverity.OK),
          )
        },
      ),
      log,
    )

    val snapshot = aggregator.collect()

    assertThat(snapshot.sections[DiagSection.ANDROID_WARNINGS]).hasSize(1)
    assertThat(snapshot.sections[DiagSection.PROFILES]).hasSize(1)
  }

  @Test
  fun leVociDiUnaSezioneVannoDallaPiuGraveEPariGravitaPerCodice() = runTest {
    val aggregator = DiagnosticsAggregator(
      listOf(
        FakeContributor(DiagSection.NETWORK_AND_NAS) {
          listOf(
            item(DiagCodes.NET_POOR, severity = DiagSeverity.WARNING),
            item(DiagCodes.NET_PLACE, severity = DiagSeverity.OK),
            item(DiagCodes.NET_FOREIGN_VPN, severity = DiagSeverity.ERROR),
            item(DiagCodes.NET_METERED, severity = DiagSeverity.INFO),
            item(DiagCodes.NET_OFFLINE, severity = DiagSeverity.WARNING),
          )
        },
      ),
      log,
    )

    val codes = aggregator.collect().sections.getValue(DiagSection.NETWORK_AND_NAS).map { it.code }

    assertThat(codes).containsExactly(
      DiagCodes.NET_FOREIGN_VPN, // ERROR
      DiagCodes.NET_OFFLINE, // WARNING, prima per codice
      DiagCodes.NET_POOR,
      DiagCodes.NET_METERED, // INFO
      DiagCodes.NET_PLACE, // OK
    ).inOrder()
  }

  @Test
  fun unContributoreRottoNonTogliGliAltriEnonSparisceInSilenzio() = runTest {
    val aggregator = DiagnosticsAggregator(
      listOf(
        FakeContributor(DiagSection.TRUST) { error("contributore rotto") },
        FakeContributor(DiagSection.NETWORK_AND_NAS) { listOf(item(DiagCodes.NET_PLACE, severity = DiagSeverity.OK)) },
      ),
      log,
    )

    val snapshot = aggregator.collect()

    assertThat(snapshot.sections[DiagSection.NETWORK_AND_NAS]).hasSize(1)
    // Al posto delle voci mancanti c'e' una voce che lo dice: una diagnostica che tace mente per omissione.
    val trust = snapshot.sections.getValue(DiagSection.TRUST)
    assertThat(trust.map { it.code }).containsExactly(DiagCodes.DIAG_SECTION_UNAVAILABLE)
    assertThat(trust.single().severity).isEqualTo(DiagSeverity.WARNING)
    assertThat(snapshot.unavailable).containsExactly(DiagSection.TRUST)
  }

  @Test
  fun unContributoreLentoVieneLasciatoIndietroAlTetto() = runTest {
    val slow = FakeContributor(DiagSection.ACTIVITIES) {
      delay(1.hours) // un contributore che tocca la rete e si pianta
      listOf(item(DiagCodes.ACT_RUNNING, DiagSection.ACTIVITIES, DiagSeverity.OK))
    }
    val fast = FakeContributor(DiagSection.STORAGE) { listOf(item(DiagCodes.STORAGE_USAGE, DiagSection.STORAGE)) }

    val snapshot = DiagnosticsAggregator(listOf(slow, fast), log, timeout = 5.seconds).collect()

    assertThat(snapshot.unavailable).containsExactly(DiagSection.ACTIVITIES)
    assertThat(snapshot.sections[DiagSection.STORAGE]).hasSize(1)
    // Il tempo e' virtuale: il test non ha aspettato davvero.
    assertThat(testScheduler.currentTime).isAtMost(5.seconds.inWholeMilliseconds + 1)
  }

  @Test
  fun iContributoriGirano_unaVoltaCiascuno() = runTest {
    val a = FakeContributor(DiagSection.STORAGE) { listOf(item(DiagCodes.STORAGE_USAGE, DiagSection.STORAGE)) }
    val b = FakeContributor(DiagSection.TRUST) { emptyList() }

    DiagnosticsAggregator(listOf(a, b), log).collect()

    assertThat(a.calls).isEqualTo(1)
    assertThat(b.calls).isEqualTo(1)
  }

  @Test
  fun unaSezioneSenzaVociNonCompare() = runTest {
    val snapshot = DiagnosticsAggregator(listOf(FakeContributor(DiagSection.TRUST) { emptyList() }), log).collect()

    assertThat(snapshot.sections).isEmpty()
    assertThat(snapshot.attentionCount).isEqualTo(0)
  }

  @Test
  fun gliEventiRicordatiSiContano() = runTest {
    log.record(DiagEvent(1L, DiagCode("NAS_TIMEOUT"), DiagSeverity.WARNING))
    log.record(DiagEvent(2L, DiagCode("NAS_TIMEOUT"), DiagSeverity.WARNING))

    val snapshot = DiagnosticsAggregator(emptyList(), log).collect()

    assertThat(snapshot.recentEvents).isEqualTo(2)
  }

  @Test
  fun unCodiceNuovoPassaEVieneSegnalatoAChiCollega() = runTest {
    val unknown = DiagCode("QUALCOSA_DI_NUOVO")
    val snapshot = DiagnosticsAggregator(
      listOf(FakeContributor(DiagSection.NETWORK_AND_NAS) { listOf(item(unknown, severity = DiagSeverity.WARNING)) }),
      log,
    ).collect()

    val items = snapshot.sections.getValue(DiagSection.NETWORK_AND_NAS)
    assertThat(items.map { it.code }).containsExactly(unknown)
    assertThat(DiagCatalog.unknownCodes(items)).containsExactly(unknown)
  }

  @Test
  fun leVociDaGuardareSiContanoSoloDalleAvvisoInSu() = runTest {
    val snapshot = DiagnosticsAggregator(
      listOf(
        FakeContributor(DiagSection.NETWORK_AND_NAS) {
          listOf(
            item(DiagCodes.NET_PLACE, severity = DiagSeverity.OK),
            item(DiagCodes.NET_METERED, severity = DiagSeverity.INFO),
            item(DiagCodes.NET_POOR, severity = DiagSeverity.WARNING),
            item(DiagCodes.NET_FOREIGN_VPN, severity = DiagSeverity.ERROR),
          )
        },
      ),
      log,
    ).collect()

    assertThat(snapshot.attentionCount).isEqualTo(2)
    assertThat(snapshot.errorCount).isEqualTo(1)
    assertThat(snapshot.worst(DiagSection.NETWORK_AND_NAS)).isEqualTo(DiagSeverity.ERROR)
    assertThat(snapshot.worst(DiagSection.TRUST)).isNull()
  }
}
