package dev.pampa.mycloudgallery.feature.map.map

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.RefuseReason
import dev.pampa.mycloudgallery.core.map.contract.RegionDownloadScope
import dev.pampa.mycloudgallery.core.map.contract.TileKey
import dev.pampa.mycloudgallery.core.map.policy.TilePolicy
import dev.pampa.mycloudgallery.core.map.testing.FakeMapNetworkState
import dev.pampa.mycloudgallery.core.map.testing.InMemoryMapRegions
import dev.pampa.mycloudgallery.core.map.testing.InMemoryMapSettings
import dev.pampa.mycloudgallery.core.map.testing.RecordingMapTraffic
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Le parti pure dell'inizializzazione (P1, verifica (7) del §2.7; D23, D24, D26): quando MapLibre si crede in rete, lo stato che il cancello
 * legge dalle porte, i contatori prima dell'inizializzazione. L'inizializzazione vera (MapLibre, il client installato, `setConnected`) la provano
 * i test strumentati (`MapNetDeviceTest`).
 */
class MapLibreInitTest {

  @Test
  fun maplibreSiCredeInReteSoloQuandoIlCancelloPotrebbeLasciarPassare() {
    val confirmedWifi = RegionDownloadScope({ true }, mobileConfirmed = false)
    val confirmedMobile = RegionDownloadScope({ true }, mobileConfirmed = true)
    for (online in listOf(true, false)) for (wifiOnly in listOf(true, false)) for (net in MapNetwork.entries) {
      for (download in listOf(null, confirmedWifi, confirmedMobile)) {
        val connected = MapConnectivity.connected(online, wifiOnly, net, download)
        val expected = when (net) {
          MapNetwork.ROAMING, MapNetwork.NONE -> false
          MapNetwork.WIFI -> online || download != null
          MapNetwork.MOBILE -> (online && !wifiOnly) || download?.mobileConfirmed == true
        }
        assertWithMessage("online=$online wifiOnly=$wifiOnly ${net.code} download=${download != null}").that(connected).isEqualTo(expected)
      }
    }
    // E-MAP1, E-MAP2: spenta, in roaming, sui dati mobili con "Solo sul Wi-Fi": MapLibre non prova nemmeno.
    assertThat(MapConnectivity.connected(online = false, wifiOnly = true, network = MapNetwork.WIFI, download = null)).isFalse()
    assertThat(MapConnectivity.connected(online = true, wifiOnly = false, network = MapNetwork.ROAMING, download = confirmedMobile)).isFalse()
    assertThat(MapConnectivity.connected(online = true, wifiOnly = true, network = MapNetwork.MOBILE, download = null)).isFalse()
  }

  @Test
  fun loStatoDelCancelloVieneDallePorte() = runTest {
    val settings = InMemoryMapSettings()
    val network = FakeMapNetworkState(MapNetwork.WIFI)
    val regions = InMemoryMapRegions(network)
    var scope: RegionDownloadScope? = null
    val wiring = MapNetWiring(TilePolicy, buildFlag = true, settings, network, regions, RecordingMapTraffic()) { scope }
    val on = wiring.state("v1")
    assertThat(on.online).isTrue()
    assertThat(on.mobileAllowed).isFalse()
    assertThat(on.currentVersion).isEqualTo("v1")
    assertThat(on.download).isNull()

    settings.disable()
    settings.setWifiOnly(false)
    network.set(MapNetwork.MOBILE)
    scope = RegionDownloadScope({ it.z == 3 }, mobileConfirmed = true)
    val off = wiring.state(null)
    assertThat(off.online).isFalse()
    assertThat(off.mobileAllowed).isTrue()
    assertThat(off.network).isEqualTo(MapNetwork.MOBILE)
    assertThat(off.download).isSameInstanceAs(scope)
    assertThat(off.covered(TileKey(0, 0, 0))).isEqualTo(regions.covers(TileKey(0, 0, 0)))

    // L'interruttore di build spento vince su tutto (D24): mappa online mai accesa.
    val noBuild = MapNetWiring(TilePolicy, buildFlag = false, InMemoryMapSettings(), network, regions, RecordingMapTraffic())
    assertThat(noBuild.state("v1").online).isFalse()
    assertThat(noBuild.toString()).doesNotContain("v1")
  }

  @Test
  fun primaDellInizializzazioneNienteENumeriAZero() {
    assertThat(MapLibreInit.isReady).isFalse()
    val counts = MapLibreInit.counts()
    assertThat(counts.allowed).isEqualTo(0)
    assertThat(counts.allowedByZoom).hasSize(15)
    assertThat(counts.refused.keys).containsExactlyElementsIn(RefuseReason.entries)
    assertThat(MapLibreInit.MAP_DIR).isEqualTo("map")
    assertThat(File(MapLibreInit.MAP_DIR).isAbsolute).isFalse()
  }
}
