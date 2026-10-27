package dev.pampa.mycloudgallery.core.map

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.map.contract.DownloadStart
import dev.pampa.mycloudgallery.core.map.contract.GeoIndexState
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.map.contract.MapOnlineChoice
import dev.pampa.mycloudgallery.core.map.contract.RegionDownloadConfirmation
import dev.pampa.mycloudgallery.core.map.contract.RegionId
import dev.pampa.mycloudgallery.core.map.contract.TileKey
import dev.pampa.mycloudgallery.core.map.noop.NoMapNetworkState
import dev.pampa.mycloudgallery.core.map.noop.NoMapPointSource
import dev.pampa.mycloudgallery.core.map.noop.NoMapPoints
import dev.pampa.mycloudgallery.core.map.noop.NoMapRegions
import dev.pampa.mycloudgallery.core.map.noop.UnavailableMapSettings
import dev.pampa.mycloudgallery.core.map.testing.SampleRegions
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Fase 9 (P0): i null-object della mappa falliscono chiusi: nessuna rete, nessuna regione, nessun punto, mappa online non disponibile. */
class NullObjectsTest {
  @Test
  fun falliscanoChiusi() = runTest {
    assertThat(NoMapNetworkState.current.value).isEqualTo(MapNetwork.NONE)
    assertThat(NoMapPointSource.pages().toList()).isEmpty()
    assertThat(NoMapPoints.index.value).isEqualTo(GeoIndexState.Empty(0))
    assertThat(NoMapRegions.covers(TileKey(0, 0, 0))).isFalse()
    val confirmation = RegionDownloadConfirmation(RegionId("it-prova-nord"), 1, mobile = true, issuedAtMs = 0)
    assertThat(NoMapRegions.download(SampleRegions.NORTH, confirmation)).isEqualTo(DownloadStart.NeedsWifi)
    UnavailableMapSettings.enable(MapOnlineChoice(1, 0))
    assertThat(UnavailableMapSettings.online.value).isEqualTo(MapOnline.Unavailable)
    assertThat(UnavailableMapSettings.wifiOnly.value).isTrue()
  }
}
