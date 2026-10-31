package dev.pampa.mycloudgallery.feature.map.map

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.map.contract.DownloadStart
import dev.pampa.mycloudgallery.core.map.contract.MapHost
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapRegions
import dev.pampa.mycloudgallery.core.map.contract.MapRequestFacts
import dev.pampa.mycloudgallery.core.map.contract.MapVerdict
import dev.pampa.mycloudgallery.core.map.contract.RefuseReason
import dev.pampa.mycloudgallery.core.map.contract.RegionDownloadConfirmation
import dev.pampa.mycloudgallery.core.map.contract.TileMath
import dev.pampa.mycloudgallery.core.map.noop.NoMapRegions
import dev.pampa.mycloudgallery.core.map.policy.TilePolicy
import dev.pampa.mycloudgallery.core.map.testing.FakeMapNetworkState
import dev.pampa.mycloudgallery.core.map.testing.InMemoryMapRegions
import dev.pampa.mycloudgallery.core.map.testing.InMemoryMapSettings
import dev.pampa.mycloudgallery.core.map.testing.RecordingMapTraffic
import dev.pampa.mycloudgallery.core.map.testing.SampleRegions
import dev.pampa.mycloudgallery.feature.map.net.FakeTileServer
import dev.pampa.mycloudgallery.feature.map.net.MapRequestHeld
import dev.pampa.mycloudgallery.feature.map.net.MapTileHttp
import dev.pampa.mycloudgallery.feature.map.net.TestClock
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Correzioni I3 e R5 della Fase 9 (31/10):
 *  - I3: `MapNetWiring` legge le regioni **a ogni richiesta**: una regione legata dopo l'avvio di MapLibre (P5 assegnera' `MapGraph.regions` piu' tardi) rende `COVERED` la sua tessera dalla richiesta dopo;
 *  - R5: `MapLibreInit.erase` svuota il database della libreria solo se la libreria e' partita, aspetta al piu' `ERASE_WAIT_MS`, e un guasto non lancia mai (ci pensano i file).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MapWiringAndEraseTest {

  private val network = FakeMapNetworkState(MapNetwork.WIFI)

  private fun facts(tile: dev.pampa.mycloudgallery.core.map.contract.TileKey) =
    MapRequestFacts("GET", "https", MapHost.TILES, 443, "/planet/v/${tile.z}/${tile.x}/${tile.y}.pbf", null)

  /** Una regione completa, scaricata e finita (il riferimento in memoria di P0), che copre il nord della prova. */
  private suspend fun completeRegions(): InMemoryMapRegions {
    val regions = InMemoryMapRegions(network)
    val spec = SampleRegions.NORTH
    assertThat(regions.download(spec, RegionDownloadConfirmation(spec.id, 1, false, 0))).isEqualTo(DownloadStart.Started)
    regions.complete(spec.id)
    return regions
  }

  @Test
  fun unaRegioneLegataDopoLAvvioRendeCoperta_LaSuaTessera() = runTest {
    var bound: MapRegions = NoMapRegions
    val wiring = MapNetWiring(TilePolicy, true, InMemoryMapSettings(), network, { bound }, RecordingMapTraffic())
    val inside = TileMath.tileOf(44_500_000, 10_500_000, 12)
    // Prima dell'avvio non c'e' nessuna regione: la tessera si chiede online (anche uno stato preso adesso e tenuto da MapLibre).
    val early = wiring.state("v1")
    assertThat(TilePolicy.decide(facts(inside), early)).isInstanceOf(MapVerdict.Allow::class.java)
    // P5 lega le regioni dopo che MapLibre e' partita con il suo `MapNetWiring`.
    bound = completeRegions()
    assertThat(TilePolicy.decide(facts(inside), wiring.state("v1"))).isEqualTo(MapVerdict.Refuse(RefuseReason.COVERED))
    // Nemmeno lo stato preso prima la tiene vecchia: le regioni si leggono quando il cancello chiede se la tessera e' coperta.
    assertThat(TilePolicy.decide(facts(inside), early)).isEqualTo(MapVerdict.Refuse(RefuseReason.COVERED))
    // Una tessera fuori dalla regione esce come prima, e `regions()` e' sempre quella di adesso.
    assertThat(TilePolicy.decide(facts(TileMath.tileOf(10_000_000, 100_000_000, 12)), wiring.state("v1"))).isInstanceOf(MapVerdict.Allow::class.java)
    assertThat(wiring.regions()).isSameInstanceAs(bound)
  }

  @Test
  fun unaRegioneLegataDopoLAvvioFermaLaRichiestaNelClientVero() = runTest {
    var bound: MapRegions = NoMapRegions
    val wiring = MapNetWiring(TilePolicy, true, InMemoryMapSettings(), network, { bound }, RecordingMapTraffic())
    val http = MapTileHttp(TilePolicy, wiring::state, RecordingMapTraffic(), { MapNetwork.WIFI }, null, TestClock())
    val server = FakeTileServer()
    val client = http.client.newBuilder().addInterceptor(server).build()
    val tile = TileMath.tileOf(44_500_000, 10_500_000, 12)
    fun get() = runCatching { client.newCall(okhttp3.Request.Builder().url("https://tiles.openfreemap.org/planet/v/12/${tile.x}/${tile.y}.pbf").build()).execute() }
    get().getOrThrow().close()
    assertThat(server.tiles()).hasSize(1)
    bound = completeRegions()
    val covered = get().exceptionOrNull()
    assertThat(covered).isInstanceOf(MapRequestHeld::class.java)
    assertThat((covered as MapRequestHeld).reason).isEqualTo(RefuseReason.COVERED)
    assertWithMessage("la tessera coperta non esce piu'").that(server.tiles()).hasSize(1)
  }

  @Test
  fun leRegioniFisseDeiVecchiChiamantiFunzionanoCome_Prima() {
    val regions = InMemoryMapRegions(network)
    val wiring = MapNetWiring(TilePolicy, true, InMemoryMapSettings(), network, regions, RecordingMapTraffic())
    assertThat(wiring.regions()).isSameInstanceAs(regions)
    assertThat(wiring.state(null).online).isTrue()
  }

  // --- R5: lo svuotamento del database di MapLibre ---------------------------------------------------------------------------------

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  @Test
  fun seMapLibreNonEPartitaNonSiTocca_NienteDaSvuotare() = runTest {
    var called = false
    assertThat(MapLibreInit.eraseWith(null) { called = true; true }).isTrue()
    assertThat(called).isFalse()
    // Nel processo dei test MapLibre non e' mai partita: l'azzeramento vero non ha niente da fare e non apre la libreria nativa.
    assertThat(MapLibreInit.isReady).isFalse()
    assertThat(MapLibreInit.erase()).isTrue()
  }

  @Test
  fun seMapLibreEPartitaSiSvuotaConLaSuaApi_ELEsitoSiVede() = runTest {
    assertThat(MapLibreInit.eraseWith(context) { true }).isTrue()
    assertThat(MapLibreInit.eraseWith(context) { false }).isFalse()
  }

  @Test
  fun unGuastoOUnaLibreriaCheNonRispondeNonFannoFallireLAzzeramento() = runTest {
    assertThat(MapLibreInit.eraseWith(context) { throw IllegalStateException("guasto") }).isFalse()
    // Una libreria che non risponde: si aspetta il tetto (tempo virtuale) e poi si va avanti coi file.
    assertThat(MapLibreInit.eraseWith(context) { awaitCancellation() }).isFalse()
    assertThat(currentTimeForTest()).isAtLeast(MapLibreInit.ERASE_WAIT_MS)
  }

  private fun kotlinx.coroutines.test.TestScope.currentTimeForTest(): Long = testScheduler.currentTime

  @Test
  fun ilDatabaseDiMapLibreHaIlNomeDiSerieDellaLibreria() {
    assertThat(MapLibreInit.DATABASE_PREFIX).isEqualTo("mbgl-offline.db")
    assertThat(MapLibreInit.ERASE_WAIT_MS).isGreaterThan(0)
  }
}
