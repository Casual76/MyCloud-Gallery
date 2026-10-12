package dev.pampa.mycloudgallery.core.rules.platform.health

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.rules.platform.health.testing.FakeBackgroundHealthReader
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BackgroundHealthContractTest {

  @Test
  fun ilNullObjectNonIndovinaNiente() = runTest {
    val health = UnknownBackgroundHealthReader.read()
    assertThat(health.ignoringBatteryOpt).isNull()
    assertThat(health.standbyBucket).isEqualTo(StandbyBucket.UNKNOWN)
    assertThat(health.unusedAppRestrictions).isEqualTo(UnusedAppRestrictions.UNKNOWN)
    assertThat(health.bootCount).isNull()
  }

  @Test
  fun ilFakeRestituisceCioCheGliSiDa() = runTest {
    val fake = FakeBackgroundHealthReader(BackgroundHealth.UNKNOWN.copy(ignoringBatteryOpt = true, isSystemUser = false))
    assertThat(fake.read().ignoringBatteryOpt).isTrue()
    assertThat(fake.read().isSystemUser).isFalse()
    assertThat(fake.reads).isEqualTo(2)
  }
}
