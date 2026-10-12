package dev.pampa.mycloudgallery.feature.diagnostics.contributors

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.CapKind
import dev.pampa.mycloudgallery.core.rules.ConfirmationKind
import dev.pampa.mycloudgallery.core.rules.DeviceRole
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.Pace
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.RunScope
import dev.pampa.mycloudgallery.core.rules.ThermalStatus
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficNetwork
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficSource
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.InMemoryLedger
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** «Attivita'» (verdetti del motore delle regole) e «Consumi del mese» (contatori di traffico). */
class ActivitiesAndTrafficContributorsTest {

  // --- Attivita' -------------------------------------------------------------------------------------------------

  private fun activities(decisions: Map<BackgroundActivity, RuleDecision>) = ActivitiesContributor { decisions }

  @Test
  fun ognunaDelleSeiAttivitaHaUnaVoce() = runTest {
    val all = BackgroundActivity.entries.associateWith<BackgroundActivity, RuleDecision> {
      RuleDecision.Run(network = NetworkClass.HOME_WIFI)
    }

    val items = activities(all).collect()

    assertThat(items).hasSize(6)
    assertThat(items.map { it.code }.toSet()).containsExactly(DiagCodes.ACT_RUNNING)
    assertThat(items.map { it.severity }.toSet()).containsExactly(DiagSeverity.OK)
    assertThat(items.map { it.section }.toSet()).containsExactly(DiagSection.ACTIVITIES)
    assertThat(items.map { it.titleRes }.toSet()).hasSize(6) // ciascuna con il proprio nome
  }

  @Test
  fun unVia_libero_conUnaAvvertenza() = runTest {
    val items = activities(
      mapOf(
        BackgroundActivity.AI_COMPUTE to RuleDecision.Run(scope = RunScope.LOCAL_BUFFER_ONLY, network = NetworkClass.OFFLINE),
        BackgroundActivity.PREPARATION to RuleDecision.Run(pace = Pace.REDUCED, network = NetworkClass.HOME_WIFI, remainingBytes = 500L),
      ),
    ).collect().associateBy { it.code }

    // Sul buffer locale (E-A6) e a ritmo ridotto (E-A5).
    assertThat(items.keys).containsExactly(DiagCodes.ACT_LOCAL_BUFFER_ONLY, DiagCodes.ACT_REDUCED_PACE)
    assertThat(items.getValue(DiagCodes.ACT_REDUCED_PACE).export["remainingBytes"]).isEqualTo(ExportValue.Num(500))
  }

  @Test
  fun ogniMotivoDiAttesaDiventaIlSuoCodiceEIlTestoDelMotivoRestaSoloASchermo() = runTest {
    val reason = WaitReason.BatteryLow(19, 20)
    val item = activities(mapOf(BackgroundActivity.PHOTO_BACKUP to RuleDecision.Wait(reason, listOf(WaitReason.PowerSaver)))).collect().single()

    assertThat(item.code).isEqualTo(DiagCodes.ACT_BATTERY_LOW)
    // Il testo italiano di core-rules (con le cifre) sta nel dettaglio dello schermo...
    assertThat(item.detail!!.args).containsExactly(reason.message)
    // ...e nell'esportazione ci sono i numeri e il tipo del motivo, non il testo.
    assertThat(item.export["reason"].toString()).isEqualTo("Enum(ActivityReason.BATTERY_LOW)")
    assertThat(item.export["batteryLevel"]).isEqualTo(ExportValue.Num(19))
    assertThat(item.export["batteryThreshold"]).isEqualTo(ExportValue.Num(20))
    assertThat(item.export["alsoBlockedBy"]).isEqualTo(ExportValue.Num(1))
    assertThat(item.export.values.map { it.toString() }.joinToString()).doesNotContain("pausa")
  }

  @Test
  fun lAttesaELaGravitaDelCatalogo() = runTest {
    val items = activities(
      mapOf(
        BackgroundActivity.PHOTO_BACKUP to RuleDecision.Wait(WaitReason.Roaming),
        BackgroundActivity.VIDEO_BACKUP to RuleDecision.Wait(WaitReason.NeedsPower),
        BackgroundActivity.INDEX_SYNC to RuleDecision.Stop(WaitReason.Thermal(ThermalStatus.SEVERE)),
      ),
    ).collect().associateBy { it.code }

    assertThat(items.getValue(DiagCodes.ACT_ROAMING).severity).isEqualTo(DiagSeverity.WARNING)
    assertThat(items.getValue(DiagCodes.ACT_NEEDS_POWER).severity).isEqualTo(DiagSeverity.INFO)
    // «Fermati» (Stop) e «non partire» (Wait) hanno lo stesso motivo; l'esportazione li distingue.
    assertThat(items.getValue(DiagCodes.ACT_THERMAL).export["decision"].toString()).isEqualTo("Enum(DecisionKind.STOP)")
    assertThat(items.getValue(DiagCodes.ACT_THERMAL).export["thermalStatus"].toString()).isEqualTo("Enum(ThermalStatus.SEVERE)")
  }

  @Test
  fun iNumeriDeiMotiviEntranoNellEsportazione() = runTest {
    val items = activities(
      mapOf(
        BackgroundActivity.PHOTO_BACKUP to RuleDecision.Wait(WaitReason.CapReached(CapKind.MOBILE, 1_200_000_000L, 1_000_000_000L)),
        BackgroundActivity.AI_COMPUTE to RuleDecision.Wait(WaitReason.RoleDisabled(DeviceRole.INDEXER)),
        BackgroundActivity.HQ_PREVIEWS to RuleDecision.Wait(WaitReason.NetworkNotAllowed(NetworkClass.MOBILE)),
      ),
    ).collect().associateBy { it.code }

    val cap = items.getValue(DiagCodes.ACT_CAP_REACHED)
    assertThat(cap.export["usedBytes"]).isEqualTo(ExportValue.Num(1_200_000_000L))
    assertThat(cap.export["capBytes"]).isEqualTo(ExportValue.Num(1_000_000_000L))
    assertThat(items.getValue(DiagCodes.ACT_ROLE_DISABLED).export["role"].toString()).isEqualTo("Enum(DeviceRole.INDEXER)")
    assertThat(items.getValue(DiagCodes.ACT_NETWORK_NOT_ALLOWED).export["network"].toString()).isEqualTo("Enum(NetworkClass.MOBILE)")
  }

  @Test
  fun serveLaConfermaPerUnFaiOra() = runTest {
    val decision = RuleDecision.NeedsConfirmation(setOf(ConfirmationKind.ROAMING), estimate = null)

    val item = activities(mapOf(BackgroundActivity.VIDEO_BACKUP to decision)).collect().single()

    assertThat(item.code).isEqualTo(DiagCodes.ACT_CONFIRMATION_REQUIRED)
    assertThat(item.export["confirmRoaming"]).isEqualTo(ExportValue.Flag(true))
    assertThat(item.export["confirmMobileData"]).isEqualTo(ExportValue.Flag(false))
  }

  @Test
  fun ogniVoceDelleAttivitaHaUnCodiceDelCatalogoEUnTitolo() = runTest {
    val reasons = listOf(
      WaitReason.ProfileInactive, WaitReason.ActivityDisabled, WaitReason.AppNotVisible, WaitReason.Offline,
      WaitReason.NetworkNotTrusted, WaitReason.PoorNetwork, WaitReason.HeavyNeedsHomeWifi, WaitReason.NasUnreachable,
    )
    val items = activities(
      reasons.withIndex().associate { (i, r) -> BackgroundActivity.entries[i % 6] to RuleDecision.Wait(r) as RuleDecision },
    ).collect()

    items.forEach {
      assertWithMessage(it.code.id).that(it.code in DiagCatalog).isTrue()
      assertWithMessage(it.code.id).that(it.titleRes).isNotEqualTo(0)
    }
  }

  // --- Consumi del mese --------------------------------------------------------------------------------------------

  @Test
  fun iConsumiDelMeseSiLeggonoDalRegistro() = runTest {
    val ledger = InMemoryLedger()
    ledger.record(TrafficSource.PHOTO_BACKUP, TrafficNetwork.HOME_WIFI, 800_000_000L)
    ledger.record(TrafficSource.PHOTO_BACKUP, TrafficNetwork.MOBILE, 100_000_000L)
    ledger.record(TrafficSource.INDEX_SYNC, TrafficNetwork.HOME_WIFI, 50_000_000L)

    val items = TrafficContributor(ledger).collect()

    val month = items.first { it.code == DiagCodes.TRAFFIC_MONTH }
    assertThat(month.export["totalBytes"]).isEqualTo(ExportValue.Num(950_000_000L))
    assertThat(month.export["wifiBytes"]).isEqualTo(ExportValue.Num(850_000_000L))
    assertThat(month.export["mobileBytes"]).isEqualTo(ExportValue.Num(100_000_000L))
    val perActivity = items.filter { it.code == DiagCodes.TRAFFIC_ACTIVITY }
    assertThat(perActivity).hasSize(2) // solo le attivita' con traffico
    assertThat(perActivity.first { (it.export["source"]).toString() == "Enum(TrafficSource.PHOTO_BACKUP)" }.export["mobileBytes"])
      .isEqualTo(ExportValue.Num(100_000_000L))
    assertThat(items.map { it.code }).doesNotContain(DiagCodes.TRAFFIC_ROAMING)
  }

  @Test
  fun ilTrafficoInRoamingHaUnAvviso() = runTest {
    val ledger = InMemoryLedger()
    ledger.record(TrafficSource.VISIBLE_CONTENT, TrafficNetwork.ROAMING, 4_000_000L)

    val roaming = TrafficContributor(ledger).collect().single { it.code == DiagCodes.TRAFFIC_ROAMING }

    assertThat(roaming.severity).isEqualTo(DiagSeverity.WARNING)
    assertThat(roaming.export["roamingBytes"]).isEqualTo(ExportValue.Num(4_000_000L))
  }

  @Test
  fun senzaRegistroLaSezioneRestaVuota() = runTest {
    assertThat(TrafficContributor(null).collect()).isEmpty()
  }

  @Test
  fun aMeseNuovoISoldiSonoZeroNonMancanti() = runTest {
    val items = TrafficContributor(InMemoryLedger()).collect()

    assertThat(items.map { it.code }).containsExactly(DiagCodes.TRAFFIC_MONTH)
    assertThat(items.single().export["totalBytes"]).isEqualTo(ExportValue.Num(0))
  }
}
