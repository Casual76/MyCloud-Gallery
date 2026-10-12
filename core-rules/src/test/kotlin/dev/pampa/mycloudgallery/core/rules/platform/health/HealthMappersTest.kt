package dev.pampa.mycloudgallery.core.rules.platform.health

import android.app.ApplicationExitInfo
import androidx.core.content.UnusedAppRestrictionsConstants
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Le tabelle di traduzione da Android a [BackgroundHealth], su JVM puro. */
class HealthMappersTest {

  @Test
  fun ilBucketSiLeggePerFasceNonPerUguaglianza() {
    // Le costanti di UsageStatsManager e i valori intermedi che il sistema puo' restituire.
    assertThat(HealthMappers.standbyBucket(10)).isEqualTo(StandbyBucket.ACTIVE)
    assertThat(HealthMappers.standbyBucket(5)).isEqualTo(StandbyBucket.ACTIVE) // EXEMPTED: libero come l'attivo
    assertThat(HealthMappers.standbyBucket(20)).isEqualTo(StandbyBucket.WORKING_SET)
    assertThat(HealthMappers.standbyBucket(15)).isEqualTo(StandbyBucket.WORKING_SET)
    assertThat(HealthMappers.standbyBucket(30)).isEqualTo(StandbyBucket.FREQUENT)
    assertThat(HealthMappers.standbyBucket(40)).isEqualTo(StandbyBucket.RARE)
    assertThat(HealthMappers.standbyBucket(45)).isEqualTo(StandbyBucket.RESTRICTED)
    assertThat(HealthMappers.standbyBucket(43)).isEqualTo(StandbyBucket.RESTRICTED)
  }

  @Test
  fun unBucketCheNonSiRiconosceNonSiIndovina() {
    assertThat(HealthMappers.standbyBucket(0)).isEqualTo(StandbyBucket.UNKNOWN) // sconosciuto
    assertThat(HealthMappers.standbyBucket(-1)).isEqualTo(StandbyBucket.UNKNOWN)
    assertThat(HealthMappers.standbyBucket(50)).isEqualTo(StandbyBucket.UNKNOWN) // NEVER: mai avviata
    assertThat(HealthMappers.standbyBucket(Int.MAX_VALUE)).isEqualTo(StandbyBucket.UNKNOWN)
  }

  @Test
  fun lePauseSeInutilizzataSiRiduconoATreStati() {
    assertThat(HealthMappers.unusedAppRestrictions(UnusedAppRestrictionsConstants.DISABLED))
      .isEqualTo(UnusedAppRestrictions.DISABLED)
    assertThat(HealthMappers.unusedAppRestrictions(UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE))
      .isEqualTo(UnusedAppRestrictions.NOT_AVAILABLE)
    listOf(
      UnusedAppRestrictionsConstants.API_30_BACKPORT,
      UnusedAppRestrictionsConstants.API_30,
      UnusedAppRestrictionsConstants.API_31,
    ).forEach { assertThat(HealthMappers.unusedAppRestrictions(it)).isEqualTo(UnusedAppRestrictions.ENABLED) }
    assertThat(HealthMappers.unusedAppRestrictions(UnusedAppRestrictionsConstants.ERROR))
      .isEqualTo(UnusedAppRestrictions.UNKNOWN)
    assertThat(HealthMappers.unusedAppRestrictions(99)).isEqualTo(UnusedAppRestrictions.UNKNOWN)
  }

  @Test
  fun ogniMotivoDiUscitaHaIlSuoSignificato() {
    val expected = mapOf(
      ApplicationExitInfo.REASON_ANR to ExitReasonKind.ANR,
      ApplicationExitInfo.REASON_CRASH to ExitReasonKind.CRASH,
      ApplicationExitInfo.REASON_CRASH_NATIVE to ExitReasonKind.CRASH_NATIVE,
      ApplicationExitInfo.REASON_LOW_MEMORY to ExitReasonKind.LOW_MEMORY,
      ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to ExitReasonKind.EXCESSIVE_RESOURCE_USAGE,
      ApplicationExitInfo.REASON_USER_REQUESTED to ExitReasonKind.USER_REQUESTED,
      ApplicationExitInfo.REASON_USER_STOPPED to ExitReasonKind.USER_STOPPED,
      ApplicationExitInfo.REASON_FREEZER to ExitReasonKind.FREEZER,
      ApplicationExitInfo.REASON_PERMISSION_CHANGE to ExitReasonKind.PERMISSION_CHANGE,
      ApplicationExitInfo.REASON_DEPENDENCY_DIED to ExitReasonKind.DEPENDENCY_DIED,
      ApplicationExitInfo.REASON_INITIALIZATION_FAILURE to ExitReasonKind.INITIALIZATION_FAILURE,
      ApplicationExitInfo.REASON_SIGNALED to ExitReasonKind.SIGNALED,
      ApplicationExitInfo.REASON_EXIT_SELF to ExitReasonKind.EXIT_SELF,
      ApplicationExitInfo.REASON_UNKNOWN to ExitReasonKind.UNKNOWN,
      ApplicationExitInfo.REASON_OTHER to ExitReasonKind.OTHER,
    )
    expected.forEach { (raw, kind) -> assertThat(HealthMappers.exitKind(raw)).isEqualTo(kind) }
  }

  @Test
  fun unMotivoDiUscitaFuturoDiventaAltro() {
    assertThat(HealthMappers.exitKind(9_999)).isEqualTo(ExitReasonKind.OTHER)
    assertThat(HealthMappers.exitKind(-3)).isEqualTo(ExitReasonKind.OTHER)
  }
}
