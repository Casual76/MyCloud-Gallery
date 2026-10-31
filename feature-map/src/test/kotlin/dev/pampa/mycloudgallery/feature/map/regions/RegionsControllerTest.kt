package dev.pampa.mycloudgallery.feature.map.regions

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.PlaceHit
import dev.pampa.mycloudgallery.core.map.contract.PlaceSearch
import dev.pampa.mycloudgallery.core.map.contract.RegionId
import dev.pampa.mycloudgallery.core.map.contract.RegionLimits
import dev.pampa.mycloudgallery.core.map.contract.RegionRadius
import dev.pampa.mycloudgallery.core.map.contract.RegionSpec
import dev.pampa.mycloudgallery.core.map.contract.RegionState
import dev.pampa.mycloudgallery.core.map.contract.RegionStatus
import dev.pampa.mycloudgallery.core.map.contract.RegionStop
import dev.pampa.mycloudgallery.core.map.regions.MemoryRegionStateStore
import dev.pampa.mycloudgallery.core.map.regions.OfflineMapRegions
import dev.pampa.mycloudgallery.core.map.regions.RegionCatalog
import dev.pampa.mycloudgallery.core.map.testing.FakeMapNetworkState
import dev.pampa.mycloudgallery.core.map.testing.FakePlaceCenters
import dev.pampa.mycloudgallery.core.map.testing.FakeRegionEngine
import dev.pampa.mycloudgallery.core.map.testing.InMemoryMapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La pagina "Zone senza Internet" a livello di controllore (Fase 9, P5; §2.9, D26, E-MAP12): dal tocco su una voce al foglio di conferma, dalla conferma allo scaricamento (con le regole di rete, spazio e
 * limiti delle regioni vere, il motore finto), la seconda conferma sui dati mobili, "troppo grande", gestione di una zona (riprendi, aggiorna, elimina) e la ricerca di un luogo per nome. Nessuna rete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RegionsControllerTest {

  private class Rig(
    val store: MemoryRegionStateStore = MemoryRegionStateStore(),
    val network: FakeMapNetworkState = FakeMapNetworkState(MapNetwork.WIFI),
    val settings: InMemoryMapSettings = InMemoryMapSettings(),
  ) {
    val engine = FakeRegionEngine()
    var freeBytes = 64L shl 30
    var nowMs = 1_000L
    val regions = OfflineMapRegions(
      engine = engine, store = store, network = network, settings = settings, places = FakePlaceCenters(emptyMap()),
      freeBytes = { freeBytes }, nowMs = { nowMs }, today = { 20261031 }, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )
    val places = listOf(
      PlaceHit(11, "Cittàdiprova", "Regione di prova, Italia", GeoE6(43_300_000, 11_300_000), urban = true),
      PlaceHit(12, "Paesediprova", "Regione di prova, Italia", GeoE6(42_900_000, 12_100_000), urban = false),
    )
    val search = PlaceSearch { query, limit -> places.filter { it.name.startsWith(query, ignoreCase = true) }.take(limit) }
  }

  /** Il controllore sul tempo virtuale del test, con un dispatcher "subito" (i flussi arrivano senza `runCurrent()`); `backgroundScope` lo ferma alla fine. */
  private fun TestScope.controller(rig: Rig): RegionsController {
    val dispatcher = UnconfinedTestDispatcher(testScheduler)
    val scope = CoroutineScope(backgroundScope.coroutineContext + dispatcher)
    return RegionsController(rig.regions, rig.search, scope, nowMs = { rig.nowMs }, compute = dispatcher, searchCalmMs = 250)
  }

  private fun spec(code: String): RegionSpec = RegionCatalog.entryOf(RegionId(code))!!.spec

  @Test
  fun ilCatalogoHaLeStimeEIlLivelloDiOgniVoce() = runTest {
    val c = controller(Rig())
    val ui = c.ui.value
    assertThat(ui.italian).hasSize(20)
    assertThat(ui.countries).hasSize(6)
    assertThat(ui.italian.all { it.estimate != null }).isTrue()
    val toscana = ui.italian.first { it.spec.id == RegionId("it-toscana") }.estimate!!
    assertThat(toscana.offered).isTrue()
    assertThat(toscana.maxZoom).isEqualTo(11)
    // Francia e Austria non ci stanno nei limiti di OpenFreeMap: non si offrono.
    val france = ui.countries.first { it.spec.id == RegionId("country-fr") }.estimate!!
    assertThat(france.offered).isFalse()
    assertThat(ui.countries.first { it.spec.id == RegionId("country-at") }.estimate!!.offered).isFalse()
    assertThat(ui.downloaded).isEmpty()
  }

  @Test
  fun dalToccoAlFoglioEDalFoglioAlloScaricamento() = runTest {
    val rig = Rig()
    val c = controller(rig)
    val umbria = spec("it-umbria")
    c.onCatalog(umbria)
    runCurrent()
    val sheet = c.ui.value.sheet as RegionSheet.Confirm
    assertThat(sheet.spec).isEqualTo(umbria)
    assertThat(sheet.purpose).isEqualTo(SheetPurpose.DOWNLOAD)
    assertThat(rig.engine.starts).isEmpty() // il foglio da solo non scarica niente
    c.onConfirm()
    runCurrent()
    assertThat(c.ui.value.sheet).isNull()
    assertThat(rig.engine.starts.single().key).isEqualTo(umbria.id.code)
    assertThat(c.ui.value.downloaded.single().status).isEqualTo(RegionStatus.DOWNLOADING)
    rig.engine.progress(umbria.id, 100, 528)
    assertThat(c.ui.value.downloaded.single().percent).isEqualTo(18)
    rig.engine.complete(umbria.id)
    assertThat(c.ui.value.downloaded.single().status).isEqualTo(RegionStatus.READY)
    // Una zona gia' scaricata si gestisce, non si riscarica da un tocco.
    c.onCatalog(umbria)
    assertThat(c.ui.value.sheet).isInstanceOf(RegionSheet.Manage::class.java)
  }

  @Test
  fun annullareNonScaricaNiente() = runTest {
    val rig = Rig()
    val c = controller(rig)
    c.onCatalog(spec("it-molise"))
    runCurrent()
    c.onDismiss()
    assertThat(c.ui.value.sheet).isNull()
    c.onConfirm() // senza foglio non fa niente
    runCurrent()
    assertThat(rig.engine.starts).isEmpty()
    assertThat(rig.regions.regions.value).isEmpty()
  }

  @Test
  fun unaZonaTroppoGrandeDiceTroppoGrande_ESenzaFoglio() = runTest {
    val rig = Rig()
    val c = controller(rig)
    c.onCatalog(spec("country-fr"))
    runCurrent()
    assertThat(c.ui.value.sheet).isNull()
    assertThat(c.ui.value.message).isEqualTo(RegionMessage.TooLarge)
    c.onDismissMessage()
    assertThat(c.ui.value.message).isNull()
    assertThat(rig.engine.starts).isEmpty()
  }

  @Test
  fun suiDatiMobiliCeLaSecondaConfermaConLaDimensione() = runTest {
    val rig = Rig()
    rig.network.set(MapNetwork.MOBILE)
    val c = controller(rig)
    c.onCatalog(spec("it-umbria"))
    runCurrent()
    // Con "Solo sul Wi-Fi" acceso non si scarica: un avviso, nessuno scaricamento.
    c.onConfirm()
    runCurrent()
    assertThat(c.ui.value.message).isEqualTo(RegionMessage.NeedsWifi)
    assertThat(rig.engine.starts).isEmpty()
    rig.settings.setWifiOnly(false)
    c.onCatalog(spec("it-umbria"))
    runCurrent()
    c.onConfirm()
    runCurrent()
    val second = c.ui.value.sheet as RegionSheet.Confirm
    assertThat(second.mobileBytes).isEqualTo(second.estimate.bytes)
    assertThat(rig.engine.starts).isEmpty() // la seconda conferma e' un altro tocco
    c.onConfirm()
    runCurrent()
    assertThat(rig.engine.starts).hasSize(1)
    assertThat(rig.regions.downloadScope()!!.mobileConfirmed).isTrue()
  }

  @Test
  fun spazioOccupatoEFineDelloScaricamento() = runTest {
    val rig = Rig()
    rig.freeBytes = RegionLimits.FREE_RESERVE_BYTES
    val c = controller(rig)
    c.onCatalog(spec("it-umbria"))
    runCurrent()
    c.onConfirm()
    runCurrent()
    val message = c.ui.value.message as RegionMessage.NoSpace
    assertThat(message.neededBytes).isGreaterThan(RegionLimits.FREE_RESERVE_BYTES)
    rig.freeBytes = 64L shl 30
    c.onCatalog(spec("it-umbria"))
    runCurrent()
    c.onConfirm()
    runCurrent()
    c.onCatalog(spec("it-marche"))
    runCurrent()
    c.onConfirm()
    runCurrent()
    assertThat(c.ui.value.message).isEqualTo(RegionMessage.Busy)
    assertThat(rig.engine.starts).hasSize(1)
  }

  @Test
  fun gestireUnaZonaRiprendereAggiornareEliminare() = runTest {
    val umbria = spec("it-umbria")
    val old = RegionState(umbria, RegionStatus.READY, 100, 100, 21_000_000, 20260101, maxZoom = 12)
    val partial = RegionState(spec("it-marche"), RegionStatus.PARTIAL, 40, 100, 5_000_000, null, maxZoom = 12, stop = RegionStop.NETWORK)
    val rig = Rig(store = MemoryRegionStateStore(listOf(old, partial)))
    val c = controller(rig)
    assertThat(c.ui.value.downloaded.map { it.spec.id }).containsExactly(spec("it-marche").id, umbria.id)
    assertThat(c.ui.value.downloaded.first { it.spec.id == umbria.id }.status).isEqualTo(RegionStatus.NEEDS_UPDATE)
    // Riprendere una zona interrotta (sul Wi-Fi, un tocco).
    c.onDownloaded(spec("it-marche").id)
    c.onResume()
    runCurrent()
    assertThat(rig.engine.starts.single().key).isEqualTo(spec("it-marche").id.code)
    rig.engine.complete(spec("it-marche").id)
    // Aggiornare: il foglio di conferma, solo a mano.
    c.onDownloaded(umbria.id)
    c.onUpdate()
    runCurrent()
    val update = c.ui.value.sheet as RegionSheet.Confirm
    assertThat(update.purpose).isEqualTo(SheetPurpose.UPDATE)
    c.onConfirm()
    runCurrent()
    assertThat(rig.engine.deletes).contains(umbria.id.code)
    assertThat(rig.engine.starts.map { it.key }).contains(umbria.id.code)
    rig.engine.complete(umbria.id)
    // Eliminare: prima una conferma.
    c.onDownloaded(umbria.id)
    c.onAskDelete()
    assertThat((c.ui.value.sheet as RegionSheet.Manage).confirmDelete).isTrue()
    c.onDelete()
    runCurrent()
    assertThat(c.ui.value.sheet).isNull()
    assertThat(c.ui.value.downloaded.map { it.spec.id }).containsExactly(spec("it-marche").id)
  }

  @Test
  fun unaZonaFermataDaiLimitiSiPuSoloEliminare() = runTest {
    val stuck = RegionState(spec("it-toscana"), RegionStatus.PARTIAL, 400, 460, 50_100_000, null, maxZoom = 11, stop = RegionStop.LIMIT_BYTES)
    val rig = Rig(store = MemoryRegionStateStore(listOf(stuck)))
    val c = controller(rig)
    c.onDownloaded(stuck.spec.id)
    c.onResume()
    runCurrent()
    assertThat(c.ui.value.message).isEqualTo(RegionMessage.TooLarge)
    assertThat(rig.engine.starts).isEmpty()
  }

  @Test
  fun laZonaAttornoAUnLuogoSiCercaPerNomeEHaTreRaggi() = runTest {
    val rig = Rig()
    val c = controller(rig)
    c.onOpenAround(available = true)
    assertThat(c.ui.value.around).isNotNull()
    c.onQuery("C")
    advanceTimeBy(1_000)
    runCurrent()
    assertThat(c.ui.value.around!!.hits).isEmpty() // meno di due lettere: niente ricerca
    c.onQuery("Citt")
    advanceTimeBy(100)
    runCurrent()
    assertThat(c.ui.value.around!!.hits).isEmpty() // la calma non e' ancora passata
    advanceTimeBy(300)
    runCurrent()
    assertThat(c.ui.value.around!!.hits.map { it.placeId }).containsExactly(11)
    c.onPick(rig.places[0])
    runCurrent()
    val around = c.ui.value.around!!
    assertThat(around.radii.map { it.radius }).containsExactly(RegionRadius.KM_15, RegionRadius.KM_30, RegionRadius.KM_60).inOrder()
    assertThat(around.radii.all { it.estimate != null && it.estimate!!.offered }).isTrue()
    // Il raggio scelto apre il foglio di conferma; confermare scarica la zona attorno al luogo (il suo centro, mai la casa).
    c.onRadius(around.radii[0])
    runCurrent()
    val sheet = c.ui.value.sheet as RegionSheet.Confirm
    assertThat((sheet.spec as RegionSpec.AroundPlace).placeId).isEqualTo(11)
    c.onConfirm()
    runCurrent()
    val started = rig.engine.starts.single()
    assertThat(started.key).isEqualTo("around-11-15")
    assertThat(started.box.contains(43_300_000, 11_300_000)).isTrue()
    assertThat(c.ui.value.around!!.radii[0].present).isNotNull()
    c.onCloseAround()
    assertThat(c.ui.value.around).isNull()
  }

  @Test
  fun senzaIlDatasetDeiLuoghiLaPaginaLoDice() = runTest {
    val c = controller(Rig())
    c.onOpenAround(available = false)
    assertThat(c.ui.value.around!!.available).isFalse()
  }
}
