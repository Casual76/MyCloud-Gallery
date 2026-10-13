package dev.pampa.mycloudgallery.feature.diagnostics.contributors

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.rules.platform.health.BackgroundHealth
import dev.pampa.mycloudgallery.core.rules.platform.health.ExitReasonKind
import dev.pampa.mycloudgallery.core.rules.platform.health.ExitRecord
import dev.pampa.mycloudgallery.core.rules.platform.health.HealthPermission
import dev.pampa.mycloudgallery.core.rules.platform.health.PermissionGrant
import dev.pampa.mycloudgallery.core.rules.platform.health.StandbyBucket
import dev.pampa.mycloudgallery.core.rules.platform.health.UnusedAppRestrictions
import dev.pampa.mycloudgallery.core.rules.platform.health.testing.FakeBackgroundHealthReader
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCodes
import dev.pampa.mycloudgallery.feature.diagnostics.healthyHealth
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import dev.pampa.mycloudgallery.feature.diagnostics.assertExportKeysAllowed
import org.junit.Test

/** «Avvisi di Android» e «Profili»: cosa dice la Diagnostica per ogni stato di [BackgroundHealth]. */
class BackgroundHealthContributorTest {

  private val now = 1_800_000_000_000L

  private fun contributor(
    health: BackgroundHealth,
    screenLock: Boolean? = true,
    heartbeatAt: Long? = null,
    uptime: Long = Long.MAX_VALUE,
  ) = BackgroundHealthContributor(
    reader = FakeBackgroundHealthReader(health),
    security = DeviceSecuritySource { screenLock },
    heartbeat = heartbeatAt?.let { at -> HeartbeatSource { at } },
    nowMillis = { now },
    uptimeMillis = { uptime },
  )

  private suspend fun codes(c: BackgroundHealthContributor) = c.collect().map { it.code }

  private fun List<DiagItem>.bySection(section: DiagSection) = filter { it.section == section }

  @Test
  fun unTelefonoInSalute_nonHaAvvisi() = runTest {
    val items = contributor(healthyHealth()).collect()

    assertThat(items.filter { it.severity >= DiagSeverity.WARNING }).isEmpty()
    assertThat(items.map { it.code }).containsAtLeast(
      DiagCodes.AND_BATTERY_UNRESTRICTED,
      DiagCodes.AND_BUCKET_OK,
      DiagCodes.AND_HIBERNATION_OFF,
      DiagCodes.PROFILE_PRIMARY,
    )
  }

  @Test
  fun unTelefonoDoveAndroidFermaIlLavoro_dicePerche() = runTest {
    val health = healthyHealth().copy(
      ignoringBatteryOpt = false,
      standbyBucket = StandbyBucket.RESTRICTED,
      backgroundRestricted = true,
      unusedAppRestrictions = UnusedAppRestrictions.ENABLED,
      userUnlocked = false,
      permissions = mapOf(
        HealthPermission.NOTIFICATIONS to PermissionGrant.DENIED,
        HealthPermission.MEDIA_IMAGES to PermissionGrant.DENIED,
        HealthPermission.MEDIA_VIDEO to PermissionGrant.DENIED,
        HealthPermission.MEDIA_PARTIAL to PermissionGrant.DENIED,
        HealthPermission.MEDIA_LOCATION to PermissionGrant.DENIED,
        HealthPermission.CAMERA to PermissionGrant.DENIED,
      ),
    )

    val items = contributor(health, screenLock = false).collect()
    val severities = items.associate { it.code to it.severity }

    assertThat(severities).containsEntry(DiagCodes.AND_BATTERY_OPTIMIZED, DiagSeverity.WARNING)
    assertThat(severities).containsEntry(DiagCodes.AND_BUCKET_RESTRICTED, DiagSeverity.WARNING)
    assertThat(severities).containsEntry(DiagCodes.AND_BACKGROUND_RESTRICTED, DiagSeverity.ERROR)
    assertThat(severities).containsEntry(DiagCodes.AND_HIBERNATION_ON, DiagSeverity.WARNING)
    assertThat(severities).containsEntry(DiagCodes.AND_USER_LOCKED, DiagSeverity.WARNING)
    assertThat(severities).containsEntry(DiagCodes.AND_NOTIFICATIONS_DENIED, DiagSeverity.WARNING)
    assertThat(severities).containsEntry(DiagCodes.AND_MEDIA_DENIED, DiagSeverity.ERROR)
    assertThat(severities).containsEntry(DiagCodes.AND_MEDIA_LOCATION_DENIED, DiagSeverity.WARNING)
    assertThat(severities).containsEntry(DiagCodes.AND_CAMERA_DENIED, DiagSeverity.INFO)
    assertThat(severities).containsEntry(DiagCodes.AND_NO_SCREEN_LOCK, DiagSeverity.WARNING)
    // Ogni avviso ha il suo «cosa fare» nel catalogo (o un'azione): niente «c'e' un problema» e basta.
    items.filter { it.severity >= DiagSeverity.WARNING }.forEach {
      assertWithMessage(it.code.id).that(DiagCatalog.guideOf(it.code)).isNotEqualTo(0)
    }
  }

  @Test
  fun leAzioniDegliAvvisiSonoQuelleGiuste() = runTest {
    val items = contributor(
      healthyHealth().copy(
        ignoringBatteryOpt = false,
        standbyBucket = StandbyBucket.RESTRICTED,
        unusedAppRestrictions = UnusedAppRestrictions.ENABLED,
        permissions = mapOf(HealthPermission.NOTIFICATIONS to PermissionGrant.DENIED, HealthPermission.MEDIA_IMAGES to PermissionGrant.DENIED),
      ),
      screenLock = false,
    ).collect().associateBy { it.code }

    fun action(code: dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode) = DiagCatalog.actionFor(items.getValue(code))

    assertThat(action(DiagCodes.AND_BATTERY_OPTIMIZED)).isEqualTo(dev.pampa.mycloudgallery.core.common.diagnostics.DiagAction.OPEN_BATTERY_SETTINGS)
    assertThat(action(DiagCodes.AND_BUCKET_RESTRICTED))
      .isEqualTo(dev.pampa.mycloudgallery.feature.diagnostics.actions.DiagnosticsActions.OPEN_NEVER_SLEEPING_APPS)
    assertThat(action(DiagCodes.AND_HIBERNATION_ON))
      .isEqualTo(dev.pampa.mycloudgallery.feature.diagnostics.actions.DiagnosticsActions.OPEN_UNUSED_APP_SETTINGS)
    assertThat(action(DiagCodes.AND_NOTIFICATIONS_DENIED))
      .isEqualTo(dev.pampa.mycloudgallery.core.common.diagnostics.DiagAction.OPEN_NOTIFICATION_SETTINGS)
    assertThat(action(DiagCodes.AND_MEDIA_DENIED)).isEqualTo(dev.pampa.mycloudgallery.core.common.diagnostics.DiagAction.OPEN_APP_DETAILS)
    assertThat(action(DiagCodes.AND_NO_SCREEN_LOCK))
      .isEqualTo(dev.pampa.mycloudgallery.feature.diagnostics.actions.DiagnosticsActions.OPEN_SECURITY_SETTINGS)
  }

  @Test
  fun ciCheAndroidNonDaNonSiIndovina() = runTest {
    // Il null-object di BackgroundHealth: niente di letto. Non deve nascere ne' un avviso ne' un «tutto bene».
    val items = contributor(BackgroundHealth.UNKNOWN, screenLock = null).collect()

    assertThat(items.map { it.code }).containsExactly(DiagCodes.PROFILE_PRIMARY)
  }

  @Test
  fun lAccessoSoloAlleFotoSelezionateELaModalitaLimitataNonUnRifiuto() = runTest {
    val limited = healthyHealth().copy(
      permissions = healthyHealth().permissions + mapOf(
        HealthPermission.MEDIA_IMAGES to PermissionGrant.DENIED,
        HealthPermission.MEDIA_VIDEO to PermissionGrant.DENIED,
        HealthPermission.MEDIA_PARTIAL to PermissionGrant.GRANTED,
      ),
    )

    val codes = codes(contributor(limited))

    assertThat(codes).contains(DiagCodes.AND_MEDIA_PARTIAL)
    assertThat(codes).doesNotContain(DiagCodes.AND_MEDIA_DENIED)
  }

  @Test
  fun iPermessiCheNonEsistonoSullaVersioneNonSonoUnProblema() = runTest {
    val android12 = healthyHealth().copy(
      permissions = HealthPermission.entries.associateWith { PermissionGrant.NOT_APPLICABLE },
    )

    assertThat(codes(contributor(android12))).doesNotContain(DiagCodes.AND_MEDIA_DENIED)
  }

  @Test
  fun unaPausaSeInutilizzataNonDisponibileNonEUnAvviso() = runTest {
    val codes = codes(contributor(healthyHealth().copy(unusedAppRestrictions = UnusedAppRestrictions.NOT_AVAILABLE)))

    assertThat(codes).containsNoneOf(DiagCodes.AND_HIBERNATION_ON, DiagCodes.AND_HIBERNATION_OFF)
  }

  @Test
  fun unBucketPocoUsatoEUnaNotaNonUnAvviso() = runTest {
    val items = contributor(healthyHealth().copy(standbyBucket = StandbyBucket.RARE)).collect()

    assertThat(items.single { it.code == DiagCodes.AND_BUCKET_RARE }.severity).isEqualTo(DiagSeverity.INFO)
  }

  // --- Uscite del processo e riavvii ----------------------------------------------------------------------------

  @Test
  fun leUsciteSiRaggruppanoPerTipoEQuelleVecchieOInutiliSiLasciano() = runTest {
    val exits = listOf(
      ExitRecord(ExitReasonKind.CRASH, now - 1.hours.inWholeMilliseconds),
      ExitRecord(ExitReasonKind.CRASH_NATIVE, now - 2.hours.inWholeMilliseconds),
      ExitRecord(ExitReasonKind.ANR, now - 3.hours.inWholeMilliseconds),
      ExitRecord(ExitReasonKind.LOW_MEMORY, now - 4.hours.inWholeMilliseconds),
      ExitRecord(ExitReasonKind.USER_REQUESTED, now - 5.hours.inWholeMilliseconds),
      ExitRecord(ExitReasonKind.SIGNALED, now - 6.hours.inWholeMilliseconds), // non dice nulla di utile
      ExitRecord(ExitReasonKind.EXIT_SELF, now - 7.hours.inWholeMilliseconds),
      ExitRecord(ExitReasonKind.CRASH, now - 30.days.inWholeMilliseconds), // troppo vecchia
    )

    val items = contributor(healthyHealth().copy(lastExitReasons = exits)).collect().associateBy { it.code }

    assertThat(items.keys).containsAtLeast(
      DiagCodes.AND_EXIT_CRASH,
      DiagCodes.AND_EXIT_ANR,
      DiagCodes.AND_EXIT_MEMORY,
      DiagCodes.AND_EXIT_FORCE_STOP,
    )
    assertThat(items.keys).containsNoneOf(DiagCodes.AND_EXIT_FREEZER, DiagCodes.AND_EXIT_PERMISSION_CHANGE)
    // Due crash recenti (uno nativo), non tre: il terzo e' fuori finestra. Esportati come numeri.
    assertThat(items.getValue(DiagCodes.AND_EXIT_CRASH).export["exitCount"]).isEqualTo(ExportValue.Num(2))
    assertThat(items.getValue(DiagCodes.AND_EXIT_CRASH).export["lastExitAge"]).isEqualTo(ExportValue.Dur(1.hours.inWholeMilliseconds))
    assertThat(items.getValue(DiagCodes.AND_EXIT_CRASH).severity).isEqualTo(DiagSeverity.ERROR)
  }

  @Test
  fun unRiavvioRecenteSpiegaIRitardi() = runTest {
    val recent = contributor(healthyHealth(), uptime = 2.hours.inWholeMilliseconds).collect()
    val old = contributor(healthyHealth(), uptime = 30.hours.inWholeMilliseconds).collect()

    val reboot = recent.single { it.code == DiagCodes.AND_RECENT_REBOOT }
    assertThat(reboot.severity).isEqualTo(DiagSeverity.INFO)
    assertThat(reboot.export["bootCount"]).isEqualTo(ExportValue.Num(33))
    assertThat(old.map { it.code }).doesNotContain(DiagCodes.AND_RECENT_REBOOT)
  }

  // --- Profili e battito -----------------------------------------------------------------------------------------------

  @Test
  fun unProfiloSecondarioAvvisaCheIlLavoroPuoRestareFermo() = runTest {
    val items = contributor(healthyHealth().copy(isSystemUser = false)).collect().bySection(DiagSection.PROFILES)

    assertThat(items.map { it.code }).containsExactly(DiagCodes.PROFILE_SECONDARY, DiagCodes.PROFILE_OWN_VPN)
    assertThat(items.first { it.code == DiagCodes.PROFILE_SECONDARY }.severity).isEqualTo(DiagSeverity.WARNING)
  }

  @Test
  fun ilBattitoDiUnProfiloSecondarioSiGiudicaDopo26Ore() = runTest {
    val fresh = contributor(healthyHealth().copy(isSystemUser = false), heartbeatAt = now - 10.hours.inWholeMilliseconds)
    val stale = contributor(healthyHealth().copy(isSystemUser = false), heartbeatAt = now - 27.hours.inWholeMilliseconds)

    assertThat(codes(fresh)).contains(DiagCodes.PROFILE_HEARTBEAT_OK)
    val staleItems = stale.collect()
    assertThat(staleItems.map { it.code }).contains(DiagCodes.PROFILE_HEARTBEAT_STALE)
    assertThat(staleItems.first { it.code == DiagCodes.PROFILE_HEARTBEAT_STALE }.export["heartbeatAge"])
      .isEqualTo(ExportValue.Dur(27.hours.inWholeMilliseconds))
    // 27 ore non sono un problema per il profilo principale: solo i profili secondari vengono messi in pausa.
    assertThat(codes(contributor(healthyHealth(), heartbeatAt = now - 27.hours.inWholeMilliseconds)))
      .doesNotContain(DiagCodes.AND_BACKGROUND_STALLED)
  }

  @Test
  fun unProfiloPrincipaleFermoDaGiorniDaUnAvvisoDiBackground() = runTest {
    val codes = codes(contributor(healthyHealth(), heartbeatAt = now - 4.days.inWholeMilliseconds))

    assertThat(codes).containsAtLeast(DiagCodes.AND_BACKGROUND_STALLED, DiagCodes.PROFILE_HEARTBEAT_STALE)
  }

  @Test
  fun senzaUnBattitoNonSiInventaNiente() = runTest {
    val codes = codes(contributor(healthyHealth(), heartbeatAt = null))

    assertThat(codes).containsNoneOf(
      DiagCodes.PROFILE_HEARTBEAT_OK,
      DiagCodes.PROFILE_HEARTBEAT_STALE,
      DiagCodes.AND_BACKGROUND_STALLED,
    )
  }

  // --- Sezioni, titoli, esportazione ----------------------------------------------------------------------------------

  @Test
  fun leVociPortanoLaSezioneGiustaEUnTitoloDelCatalogo() = runTest {
    val items = contributor(healthyHealth().copy(isSystemUser = false, ignoringBatteryOpt = false)).collect()

    items.forEach { assertWithMessage(it.code.id).that(it.code in DiagCatalog).isTrue() }
    items.forEach { assertWithMessage(it.code.id).that(it.titleRes).isEqualTo(DiagCatalog.titleOf(it.code)) }
    assertThat(items.filter { it.code.id.startsWith("PROFILE_") }.map { it.section }.toSet()).containsExactly(DiagSection.PROFILES)
    assertThat(items.filter { it.code.id.startsWith("AND_") }.map { it.section }.toSet()).containsExactly(DiagSection.ANDROID_WARNINGS)
  }

  @Test
  fun lEsportazioneDelleVociHaSoloValoriTipizzati() = runTest {
    val items = contributor(
      healthyHealth().copy(standbyBucket = StandbyBucket.RESTRICTED, isSystemUser = false),
      heartbeatAt = now - 30.hours.inWholeMilliseconds,
    ).collect()

    val values = items.flatMap { it.export.values }
    assertThat(values).isNotEmpty()
    assertExportKeysAllowed(items)
    // Numeri, si'/no, durate e costanti di enum: niente impronte (non ci sono dati personali da nascondere qui).
    assertThat(values.all { it is ExportValue.Num || it is ExportValue.Flag || it is ExportValue.Dur || it is ExportValue.Enum }).isTrue()
    assertThat(items.first { it.code == DiagCodes.AND_BUCKET_RESTRICTED }.export["standbyBucket"].toString())
      .isEqualTo("Enum(StandbyBucket.RESTRICTED)")
  }
}
