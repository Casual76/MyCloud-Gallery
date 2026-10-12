package dev.pampa.mycloudgallery.core.network.env

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.network.contract.NetPlace
import dev.pampa.mycloudgallery.core.network.policy.CombinedNetworkQuality
import dev.pampa.mycloudgallery.core.network.testing.FakeHomeFingerprintStore
import dev.pampa.mycloudgallery.core.network.testing.FakeLocalNetworkPermission
import dev.pampa.mycloudgallery.core.network.testing.FakeNetworkObservationSource
import dev.pampa.mycloudgallery.core.network.testing.LinkFactsSamples
import dev.pampa.mycloudgallery.core.rules.ComposedSystemStateProvider
import dev.pampa.mycloudgallery.core.rules.FakeAndroidLimitsSignal
import dev.pampa.mycloudgallery.core.rules.FakeAppContextSignal
import dev.pampa.mycloudgallery.core.rules.FakeDeviceRolesSignal
import dev.pampa.mycloudgallery.core.rules.FakeDeviceStateSource
import dev.pampa.mycloudgallery.core.rules.FakeNasReachabilitySignal
import dev.pampa.mycloudgallery.core.rules.FakeNetworkQualitySignal
import dev.pampa.mycloudgallery.core.rules.DeviceReading
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.NetworkClassifier
import dev.pampa.mycloudgallery.core.rules.NetworkState
import dev.pampa.mycloudgallery.core.rules.PowerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** L'ambiente di rete e i segnali per il motore delle regole. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveNetworkEnvironmentTest {

  private val source = FakeNetworkObservationSource()
  private val store = FakeHomeFingerprintStore(LinkFactsSamples.homeFingerprint())
  private val permission = FakeLocalNetworkPermission()

  @Test
  fun isAtHome_eVeroSoloACasa_eSegueLeReti() = runTest {
    val env = LiveNetworkEnvironment(source, store, permission, backgroundScope, clockMillis = { 1L })

    env.isAtHome.test {
      assertThat(awaitItem()).isFalse()
      source.set(LinkFactsSamples.homeWifi())
      runCurrent()
      assertThat(awaitItem()).isTrue()
      source.set(LinkFactsSamples.friendWifi())
      runCurrent()
      assertThat(awaitItem()).isFalse()
    }
  }

  @Test
  fun lImprontaCheArriva_riclassificaLaReteSenzaCheLaReteCambi() = runTest {
    store.fingerprint.value = null
    source.set(LinkFactsSamples.homeWifi())
    val env = LiveNetworkEnvironment(source, store, permission, backgroundScope, clockMillis = { 1L })
    assertThat(env.snapshot.value.place).isEqualTo(NetPlace.UNTRUSTED)

    store.fingerprint.value = LinkFactsSamples.homeFingerprint()
    runCurrent()

    assertThat(env.snapshot.value.place).isEqualTo(NetPlace.HOME)
  }

  @Test
  fun lIstanteCambiaSoloQuandoCambiaQualcosaD_altro() = runTest {
    var now = 100L
    source.set(LinkFactsSamples.homeWifi())
    val env = LiveNetworkEnvironment(source, store, permission, backgroundScope, clockMillis = { now })
    val first = env.snapshot.value

    now = 200L
    source.set(LinkFactsSamples.homeWifi()) // uguale
    runCurrent()
    env.evaluateNow()
    assertThat(env.snapshot.value).isEqualTo(first)

    now = 300L
    source.set(LinkFactsSamples.cafeWifi())
    runCurrent()
    assertThat(env.snapshot.value.at).isEqualTo(300L)
  }

  @Test
  fun inizia_aOsservare_alCostruttore() = runTest {
    LiveNetworkEnvironment(source, store, permission, backgroundScope, clockMillis = { 1L })

    assertThat(source.started).isTrue()
  }

  @Test
  fun toNetworkState_siPassaAlMotoreDelleRegole() = runTest {
    source.set(LinkFactsSamples.homeWifi())
    val env = LiveNetworkEnvironment(source, store, permission, backgroundScope, clockMillis = { 1L })

    assertThat(NetworkClassifier.classify(env.toNetworkState())).isEqualTo(NetworkClass.HOME_WIFI)

    source.set(LinkFactsSamples.cafeWifi())
    assertThat(NetworkClassifier.classify(env.toNetworkState())).isEqualTo(NetworkClass.UNTRUSTED)
  }

  @Test
  fun ilSegnaleDiCasaEQuelloDelProviderDelleRegole() = runTest {
    source.set(LinkFactsSamples.homeWifi())
    val env = LiveNetworkEnvironment(source, store, permission, backgroundScope, clockMillis = { 1L })
    val provider = ComposedSystemStateProvider(
      device = FakeDeviceStateSource(DeviceReading(PowerState(pluggedIn = true, levelPercent = 90), NetworkState(transport = dev.pampa.mycloudgallery.core.rules.NetworkTransport.WIFI))),
      home = env,
      nas = FakeNasReachabilitySignal(true),
      roles = FakeDeviceRolesSignal(),
      limits = FakeAndroidLimitsSignal(),
      app = FakeAppContextSignal(),
      quality = env.quality,
    )

    assertThat(provider.current().network.isHome).isTrue()

    source.set(LinkFactsSamples.friendWifi())
    runCurrent()
    assertThat(provider.current().network.isHome).isFalse()
  }

  @Test
  fun ilDeviceStateSource_prendeLaReteDallAmbienteEL_alimentazioneDaAndroid() = runTest {
    source.set(LinkFactsSamples.homeWifi(validated = false))
    val env = LiveNetworkEnvironment(source, store, permission, backgroundScope, clockMillis = { 1L })
    // Android vede i dati mobili come rete predefinita (il Wi-Fi di casa non ha internet, E-R12).
    val android = FakeDeviceStateSource(
      DeviceReading(
        PowerState(pluggedIn = true, levelPercent = 80),
        NetworkState(transport = dev.pampa.mycloudgallery.core.rules.NetworkTransport.CELLULAR, metered = true),
      ),
    )
    val device = EnvironmentDeviceStateSource(android, env)

    val reading = device.read()

    assertThat(reading.power.levelPercent).isEqualTo(80)
    assertThat(reading.network.transport).isEqualTo(dev.pampa.mycloudgallery.core.rules.NetworkTransport.WIFI)
    assertThat(reading.network.isHome).isTrue()
    assertThat(reading.network.validated).isTrue()

    device.changes().test {
      skipItems(2) // l'alimentazione e la rete, all'inizio
      source.set(LinkFactsSamples.cafeWifi())
      runCurrent()
      awaitItem()
    }
  }

  // --- Qualita' ------------------------------------------------------------------------------------------

  @Test
  fun laReteCongestionata_eScadente_eLaCongestioneSiRisolve() = runTest {
    source.set(LinkFactsSamples.homeWifi())
    val env = LiveNetworkEnvironment(source, store, permission, backgroundScope, clockMillis = { 1L })
    assertThat(env.quality.isPoor.value).isFalse()

    source.set(LinkFactsSamples.homeWifi(congested = true))
    runCurrent()
    assertThat(env.quality.isPoor.value).isTrue()

    source.set(LinkFactsSamples.homeWifi())
    runCurrent()
    assertThat(env.quality.isPoor.value).isFalse()
  }

  @Test
  fun ilSegnaleCombinato_ScadenteSeAlmenoUnoLoE() = runTest {
    val android = FakeNetworkQualitySignal(false)
    val nas = FakeNetworkQualitySignal(false)
    val combined = CombinedNetworkQuality(listOf(android, nas), backgroundScope)
    runCurrent()
    assertThat(combined.isPoor.value).isFalse()

    nas.isPoor.value = true
    runCurrent()
    assertThat(combined.isPoor.value).isTrue()

    android.isPoor.value = true
    nas.isPoor.value = false
    runCurrent()
    assertThat(combined.isPoor.value).isTrue()

    android.isPoor.value = false
    runCurrent()
    assertThat(combined.isPoor.value).isFalse()
  }

  @Test
  fun ilSegnaleCombinato_partedaSubitoEConNessunSegnale_ebuono() = runTest {
    assertThat(CombinedNetworkQuality(listOf(FakeNetworkQualitySignal(true)), backgroundScope).isPoor.value).isTrue()
    assertThat(CombinedNetworkQuality(emptyList(), backgroundScope).isPoor.value).isFalse()
  }
}
