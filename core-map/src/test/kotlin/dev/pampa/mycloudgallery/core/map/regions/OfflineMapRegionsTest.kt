package dev.pampa.mycloudgallery.core.map.regions

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.map.contract.BudgetLimit
import dev.pampa.mycloudgallery.core.map.contract.DownloadStart
import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.OpenFreeMapLimits
import dev.pampa.mycloudgallery.core.map.contract.RegionDownloadConfirmation
import dev.pampa.mycloudgallery.core.map.contract.RegionEngineFailure
import dev.pampa.mycloudgallery.core.map.contract.RegionId
import dev.pampa.mycloudgallery.core.map.contract.RegionKind
import dev.pampa.mycloudgallery.core.map.contract.RegionLimits
import dev.pampa.mycloudgallery.core.map.contract.RegionRadius
import dev.pampa.mycloudgallery.core.map.contract.RegionSpec
import dev.pampa.mycloudgallery.core.map.contract.RegionState
import dev.pampa.mycloudgallery.core.map.contract.RegionStatus
import dev.pampa.mycloudgallery.core.map.contract.RegionStop
import dev.pampa.mycloudgallery.core.map.contract.TileMath
import dev.pampa.mycloudgallery.core.map.testing.FakeMapNetworkState
import dev.pampa.mycloudgallery.core.map.testing.FakePlaceCenters
import dev.pampa.mycloudgallery.core.map.testing.FakeRegionEngine
import dev.pampa.mycloudgallery.core.map.testing.InMemoryMapSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Un gestore delle regioni con tutto finto (motore, stato su disco, rete, impostazioni, spazio, orologi): il [RegionSpec] di prova sta in Toscana. */
internal class ManagerRig(
  val store: MemoryRegionStateStore = MemoryRegionStateStore(),
  val engine: FakeRegionEngine = FakeRegionEngine(),
  val network: FakeMapNetworkState = FakeMapNetworkState(MapNetwork.WIFI),
  val settings: InMemoryMapSettings = InMemoryMapSettings(),
) {
  var freeBytes: Long = 64L shl 30
  var nowMs: Long = 1_000
  var today: Int = 20261031

  val regions = OfflineMapRegions(
    engine = engine,
    store = store,
    network = network,
    settings = settings,
    places = FakePlaceCenters(mapOf(77 to GeoE6(43_000_000, 11_000_000)), urbanIds = setOf(77), names = mapOf(77 to "Luogo di prova")),
    freeBytes = { freeBytes },
    nowMs = { nowMs },
    today = { today },
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    stopGraceMs = 0,
  )

  val umbria: RegionSpec = RegionCatalog.entryOf(RegionId("it-umbria"))!!.spec
  val marche: RegionSpec = RegionCatalog.entryOf(RegionId("it-marche"))!!.spec

  fun confirm(spec: RegionSpec, mobile: Boolean = false) = RegionDownloadConfirmation(spec.id, 1, mobile, nowMs)

  fun state(spec: RegionSpec): RegionState = regions.regions.value.first { it.spec.id == spec.id }
}

/**
 * Le regioni vere (Fase 9, P5): stati, limiti di OpenFreeMap, un solo scaricamento alla volta, rete, ripresa dopo la morte del processo, aggiornamento solo a mano, azzeramento. Sulla JVM, con
 * un motore finto: la suite di contratto delle regioni (`OfflineMapRegionsContractTest`) prova il resto.
 */
class OfflineMapRegionsTest {

  @Test
  fun unSoloScaricamentoAllaVolta() = runTest {
    val rig = ManagerRig()
    assertThat(rig.regions.download(rig.umbria, rig.confirm(rig.umbria))).isEqualTo(DownloadStart.Started)
    assertThat(rig.regions.download(rig.marche, rig.confirm(rig.marche))).isEqualTo(DownloadStart.Busy)
    assertThat(rig.regions.resume(rig.marche.id)).isEqualTo(DownloadStart.Expired)
    assertThat(rig.engine.starts.map { it.key }).containsExactly(rig.umbria.id.code)
    assertThat(rig.regions.regions.value.map { it.spec.id }).containsExactly(rig.umbria.id)
    // Finito il primo, il secondo parte.
    rig.engine.complete(rig.umbria.id)
    assertThat(rig.regions.download(rig.marche, rig.confirm(rig.marche))).isEqualTo(DownloadStart.Started)
  }

  /** Una regione e' **uno** scaricamento: tutto il riquadro, da z0 al livello del piano, in una sola partenza del motore (mai spezzata in pezzi piccoli per aggirare il limite). */
  @Test
  fun unaRegioneEUnoScaricamentoSolo() = runTest {
    val rig = ManagerRig()
    rig.regions.download(rig.umbria, rig.confirm(rig.umbria))
    val start = rig.engine.starts.single()
    assertThat(start.box).isEqualTo(rig.umbria.box)
    assertThat(start.minZoom).isEqualTo(0)
    assertThat(start.maxZoom).isEqualTo(rig.regions.estimate(rig.umbria).maxZoom)
    // Altri tocchi sulla stessa zona mentre scarica non fanno altre partenze.
    assertThat(rig.regions.download(rig.umbria, rig.confirm(rig.umbria))).isEqualTo(DownloadStart.Busy)
    assertThat(rig.engine.starts).hasSize(1)
    assertThat(rig.regions.downloadScope()!!.budget!!.maxTiles).isEqualTo(OpenFreeMapLimits.MAX_TILES)
  }

  /** Mai due scaricamenti in parallelo, nemmeno se due toccano insieme: da due thread, uno parte e l'altro trova "occupato". */
  @Test
  fun nemmenoDueTocchiInsieme() {
    repeat(200) {
      val rig = ManagerRig()
      val started = AtomicInteger()
      val busy = AtomicInteger()
      val gate = CountDownLatch(1)
      val pool = Executors.newFixedThreadPool(2)
      try {
        val jobs = listOf(rig.umbria, rig.marche).map { spec ->
          pool.submit {
            gate.await()
            when (runBlocking { rig.regions.download(spec, rig.confirm(spec)) }) {
              DownloadStart.Started -> started.incrementAndGet()
              DownloadStart.Busy -> busy.incrementAndGet()
              else -> Unit
            }
          }
        }
        gate.countDown()
        jobs.forEach { it.get() }
      } finally {
        pool.shutdown()
      }
      assertThat(started.get()).isEqualTo(1)
      assertThat(busy.get()).isEqualTo(1)
      assertThat(rig.engine.starts).hasSize(1)
    }
  }

  @Test
  fun unaZonaTroppoGrandeNonParte() = runTest {
    val rig = ManagerRig()
    val world = RegionSpec.Named(RegionId("country-mondo"), RegionKind.COUNTRY, GeoBox(-179_000_000, -80_000_000, 179_000_000, 80_000_000))
    assertThat(rig.regions.estimate(world).offered).isFalse()
    assertThat(rig.regions.download(world, rig.confirm(world))).isEqualTo(DownloadStart.TooLarge)
    assertThat(rig.engine.starts).isEmpty()
    assertThat(rig.regions.regions.value).isEmpty()
    // La Francia e' fuori dai limiti (non si offre) e l'Umbria dentro.
    assertThat(rig.regions.estimate(RegionCatalog.entryOf(RegionId("country-fr"))!!.spec).offered).isFalse()
    assertThat(rig.regions.estimate(rig.umbria).offered).isTrue()
  }

  @Test
  fun ilTettoDeiByteVeriFermaLoScaricamentoENonRiparte() = runTest {
    val rig = ManagerRig()
    rig.regions.download(rig.umbria, rig.confirm(rig.umbria))
    val scope = rig.regions.downloadScope()!!
    val budget = scope.budget!!
    assertThat(budget.maxBytes).isEqualTo(50_000_000L)
    rig.engine.progress(rig.umbria.id, 30, 400)
    // Quarantanove milioni: ancora dentro. Poi lo superano.
    assertThat(budget.tryAdmit()).isTrue()
    budget.received(49_000_000)
    budget.arrived()
    assertThat(rig.state(rig.umbria).status).isEqualTo(RegionStatus.DOWNLOADING)
    assertThat(budget.tryAdmit()).isTrue()
    budget.received(1_500_000)
    budget.arrived()
    val partial = rig.state(rig.umbria)
    assertThat(partial.status).isEqualTo(RegionStatus.PARTIAL)
    assertThat(partial.stop).isEqualTo(RegionStop.LIMIT_BYTES)
    assertThat(partial.bytes).isEqualTo(50_500_000L)
    assertThat(rig.engine.stops).containsExactly(rig.umbria.id.code)
    assertThat(rig.regions.downloadScope()).isNull()
    // Non riparte da sola, ne' con un tocco, ne' con una nuova conferma; e non copre le sue tessere (e' parziale).
    rig.regions.resumeInterrupted()
    assertThat(rig.regions.resume(rig.umbria.id)).isEqualTo(DownloadStart.TooLarge)
    assertThat(rig.regions.download(rig.umbria, rig.confirm(rig.umbria))).isEqualTo(DownloadStart.TooLarge)
    assertThat(rig.engine.starts).hasSize(1)
    assertThat(rig.regions.covers(TileMath.tileOf(42_900_000, 12_600_000, 8))).isFalse()
    // Si puo' togliere e scegliere una zona piu' piccola.
    rig.regions.delete(rig.umbria.id)
    assertThat(rig.regions.regions.value).isEmpty()
  }

  @Test
  fun ilTettoDelleTessereFermaLoScaricamento() = runTest {
    val rig = ManagerRig()
    rig.regions.download(rig.umbria, rig.confirm(rig.umbria))
    val budget = rig.regions.downloadScope()!!.budget!!
    repeat(OpenFreeMapLimits.MAX_TILES) {
      assertThat(budget.tryAdmit()).isTrue()
      budget.received(100)
      budget.arrived()
    }
    assertThat(budget.tryAdmit()).isFalse()
    assertThat(budget.limit).isEqualTo(BudgetLimit.TILES)
    assertThat(rig.state(rig.umbria).stop).isEqualTo(RegionStop.LIMIT_TILES)
  }

  /** Se il motore dice "completa" sull'ultima tessera la regione e' completa, anche se il conto aveva gia' chiamato il tetto. */
  @Test
  fun laRegioneCompletaVinceSulTetto() = runTest {
    val rig = ManagerRig()
    rig.regions.download(rig.umbria, rig.confirm(rig.umbria))
    val budget = rig.regions.downloadScope()!!.budget!!
    budget.tryAdmit()
    budget.received(50_100_000)
    budget.arrived()
    assertThat(rig.state(rig.umbria).stop).isEqualTo(RegionStop.LIMIT_BYTES)
    rig.engine.complete(rig.umbria.id)
    assertThat(rig.state(rig.umbria).status).isEqualTo(RegionStatus.READY)
    assertThat(rig.state(rig.umbria).stop).isNull()
  }

  /**
   * Dopo uno stop (qui il tetto dei 50 MB) il conto resta **chiuso** al cancello per un po', finche' MapLibre si e' davvero fermata: le richieste gia' in coda non escono come richieste normali (provato sul dispositivo);
   * poi lo scaricamento esce dal cancello. Tempo virtuale.
   */
  @Test
  fun dopoLoStopIlCancelloTieneChiusoLoScaricamentoPerUnPo() = runTest {
    val rig = ManagerRig()
    val manager = OfflineMapRegions(
      engine = rig.engine, store = rig.store, network = rig.network, settings = rig.settings, places = FakePlaceCenters(emptyMap()),
      freeBytes = { rig.freeBytes }, nowMs = { rig.nowMs }, today = { rig.today },
      scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)), stopGraceMs = 3_000,
    )
    manager.download(rig.umbria, rig.confirm(rig.umbria))
    val budget = manager.downloadScope()!!.budget!!
    budget.tryAdmit()
    budget.received(50_000_001)
    budget.arrived()
    assertThat(manager.regions.value.single().stop).isEqualTo(RegionStop.LIMIT_BYTES)
    assertThat(rig.engine.stops).containsExactly(rig.umbria.id.code)
    // Il motore e' fermo, ma il cancello tiene ancora lo scaricamento: chiuso (nessuna tessera della regione passa).
    assertThat(manager.downloadScope()).isNotNull()
    assertThat(budget.exhausted).isTrue()
    assertThat(budget.tryAdmit()).isFalse()
    advanceTimeBy(2_999)
    assertThat(manager.downloadScope()).isNotNull()
    advanceTimeBy(2)
    assertThat(manager.downloadScope()).isNull()
  }

  /** Mai spezzare una regione in scaricamenti piccoli per aggirare il limite: la ripresa riparte dal conto di prima (i byte si sommano). */
  @Test
  fun laRipresaContinuaIlContoDiPrima() = runTest {
    val rig = ManagerRig()
    rig.regions.download(rig.umbria, rig.confirm(rig.umbria))
    val first = rig.regions.downloadScope()!!.budget!!
    first.tryAdmit(); first.received(40_000_000); first.arrived()
    rig.engine.progress(rig.umbria.id, 200, 400)
    rig.network.set(MapNetwork.NONE)
    assertThat(rig.state(rig.umbria).stop).isEqualTo(RegionStop.NETWORK)
    assertThat(rig.regions.downloadScope()).isNull()
    rig.network.set(MapNetwork.WIFI)
    assertThat(rig.regions.resume(rig.umbria.id)).isEqualTo(DownloadStart.Started)
    val second = rig.regions.downloadScope()!!.budget!!
    assertThat(second.bytes).isEqualTo(40_000_000L)
    assertThat(second.tiles).isAtLeast(1)
    second.tryAdmit(); second.received(11_000_000); second.arrived()
    assertThat(rig.state(rig.umbria).stop).isEqualTo(RegionStop.LIMIT_BYTES)
  }

  @Test
  fun laReteFermaELaRipresaAspettaIlWifi() = runTest {
    val rig = ManagerRig()
    rig.regions.download(rig.umbria, rig.confirm(rig.umbria))
    assertThat(rig.regions.downloadScope()).isNotNull()
    rig.network.set(MapNetwork.ROAMING)
    assertThat(rig.state(rig.umbria).status).isEqualTo(RegionStatus.PARTIAL)
    assertThat(rig.state(rig.umbria).stop).isEqualTo(RegionStop.NETWORK)
    assertThat(rig.regions.downloadScope()).isNull()
    // All'apertura: niente Wi-Fi, niente ripresa; con il Wi-Fi riprende da sola.
    rig.regions.resumeInterrupted()
    assertThat(rig.engine.starts).hasSize(1)
    rig.network.set(MapNetwork.WIFI)
    rig.regions.resumeInterrupted()
    assertThat(rig.engine.starts).hasSize(2)
    assertThat(rig.state(rig.umbria).status).isEqualTo(RegionStatus.DOWNLOADING)
  }

  /** E-MAP20: la mappa spenta durante lo scaricamento lo ferma; riprende solo con un tocco (non da sola all'apertura). */
  @Test
  fun laMappaSpentaFermaELaRipresaE_UnTocco() = runTest {
    val rig = ManagerRig()
    rig.regions.download(rig.umbria, rig.confirm(rig.umbria))
    rig.settings.disable()
    assertThat(rig.state(rig.umbria).stop).isEqualTo(RegionStop.MAP_OFF)
    assertThat(rig.regions.downloadScope()).isNull()
    rig.regions.resumeInterrupted()
    assertThat(rig.engine.starts).hasSize(1)
    assertThat(rig.regions.resume(rig.umbria.id)).isEqualTo(DownloadStart.Started)
    assertThat(rig.regions.downloadScope()).isNotNull()
    rig.regions.pauseAll()
    assertThat(rig.state(rig.umbria).stop).isEqualTo(RegionStop.MAP_OFF)
  }

  @Test
  fun iDatiMobiliSoloConLaSecondaConfermaEConWifiOnlySpento() = runTest {
    val rig = ManagerRig()
    rig.network.set(MapNetwork.MOBILE)
    assertThat(rig.regions.download(rig.umbria, rig.confirm(rig.umbria, mobile = true))).isEqualTo(DownloadStart.NeedsWifi)
    rig.settings.setWifiOnly(false)
    val need = rig.regions.download(rig.umbria, rig.confirm(rig.umbria))
    assertThat(need).isInstanceOf(DownloadStart.NeedsMobileConfirmation::class.java)
    assertThat((need as DownloadStart.NeedsMobileConfirmation).bytes).isEqualTo(rig.regions.estimate(rig.umbria).bytes)
    assertThat(rig.regions.download(rig.umbria, rig.confirm(rig.umbria, mobile = true))).isEqualTo(DownloadStart.Started)
    assertThat(rig.regions.downloadScope()!!.mobileConfirmed).isTrue()
    // "Solo sul Wi-Fi" acceso a meta' scaricamento: si ferma.
    rig.settings.setWifiOnly(true)
    assertThat(rig.state(rig.umbria).stop).isEqualTo(RegionStop.NETWORK)
  }

  @Test
  fun spazioETettoTotale() = runTest {
    val rig = ManagerRig()
    val estimate = rig.regions.estimate(rig.umbria)
    rig.freeBytes = estimate.bytes + RegionLimits.FREE_RESERVE_BYTES - 1
    assertThat(rig.regions.download(rig.umbria, rig.confirm(rig.umbria))).isEqualTo(DownloadStart.NoSpace(estimate.bytes + RegionLimits.FREE_RESERVE_BYTES))
    rig.freeBytes = estimate.bytes + RegionLimits.FREE_RESERVE_BYTES
    assertThat(rig.regions.download(rig.umbria, rig.confirm(rig.umbria))).isEqualTo(DownloadStart.Started)
    rig.engine.complete(rig.umbria.id)
    // Il tetto totale e' 1 GB: con 990 MB gia' presi un'altra regione non ci sta.
    val full = ManagerRig(store = MemoryRegionStateStore(listOf(RegionState(rig.umbria, RegionStatus.READY, 100, 100, 990_000_000L, 20261031, maxZoom = 11))))
    assertThat(full.regions.download(full.marche, full.confirm(full.marche))).isEqualTo(DownloadStart.TooLarge)
  }

  @Test
  fun laConfermaDelFoglioVale10MinutiESoloPerLaSuaRegione() = runTest {
    val rig = ManagerRig()
    val old = rig.confirm(rig.umbria)
    rig.nowMs += RegionDownloadConfirmation.VALID_MS + 1
    assertThat(rig.regions.download(rig.umbria, old)).isEqualTo(DownloadStart.Expired)
    assertThat(rig.regions.download(rig.umbria, rig.confirm(rig.marche))).isEqualTo(DownloadStart.Expired)
    assertThat(rig.engine.starts).isEmpty()
  }

  /** E-MAP11: il processo muore a meta' (lo stato su disco dice "in scaricamento"): alla riapertura la regione e' parziale e riprende dal conto di prima, senza rete vera. */
  @Test
  fun dopoLaMorteDelProcessoLaRegioneRiprendeDalContoDiPrima() = runTest {
    val first = ManagerRig()
    first.regions.download(first.umbria, first.confirm(first.umbria))
    val budget = first.regions.downloadScope()!!.budget!!
    budget.tryAdmit(); budget.received(12_000_000); budget.arrived()
    first.nowMs += 2_000 // i progressi si salvano al piu' una volta al secondo
    first.engine.progress(first.umbria.id, 120, 400)
    assertThat(first.store.saved.single().status).isEqualTo(RegionStatus.DOWNLOADING)
    // Il processo muore: niente `pause`, niente `complete`. Un gestore nuovo legge lo stesso stato su disco.
    val second = ManagerRig(store = first.store)
    val loaded = second.state(second.umbria)
    assertThat(loaded.status).isEqualTo(RegionStatus.PARTIAL)
    assertThat(loaded.stop).isEqualTo(RegionStop.INTERRUPTED)
    assertThat(loaded.completedTiles).isEqualTo(120L)
    assertThat(second.regions.covers(TileMath.tileOf(43_000_000, 12_500_000, 8))).isFalse()
    assertThat(second.engine.starts).isEmpty() // niente da solo prima dell'apertura
    second.regions.resumeInterrupted()
    assertThat(second.engine.starts.single().key).isEqualTo(second.umbria.id.code)
    assertThat(second.engine.starts.single().minZoom).isEqualTo(0)
    assertThat(second.engine.starts.single().maxZoom).isEqualTo(loaded.maxZoom)
    val resumed = second.regions.downloadScope()!!.budget!!
    assertThat(resumed.bytes).isEqualTo(12_000_000L)
    assertThat(resumed.tiles).isAtLeast(1)
    second.engine.complete(second.umbria.id)
    assertThat(second.state(second.umbria).status).isEqualTo(RegionStatus.READY)
    assertThat(second.regions.covers(TileMath.tileOf(43_000_000, 12_500_000, 8))).isTrue()
    assertThat(second.store.saved.single().status).isEqualTo(RegionStatus.READY)
  }

  @Test
  fun leRegioniInterrotteRiprendonoUnaAllaVolta() = runTest {
    val first = ManagerRig()
    first.regions.download(first.umbria, first.confirm(first.umbria))
    first.engine.complete(first.umbria.id)
    first.regions.download(first.marche, first.confirm(first.marche))
    val partialMarche = first.store.saved.first { it.spec.id == first.marche.id }
    val other = RegionState(first.umbria, RegionStatus.PARTIAL, 5, 100, 1_000, null, maxZoom = 11, stop = RegionStop.NETWORK)
    val second = ManagerRig(store = MemoryRegionStateStore(listOf(other, partialMarche)))
    second.regions.resumeInterrupted()
    assertThat(second.engine.starts).hasSize(1)
    second.engine.complete(second.umbria.id)
    assertThat(second.engine.starts).hasSize(2)
    assertThat(second.engine.starts.map { it.key }).containsExactly(second.umbria.id.code, second.marche.id.code).inOrder()
  }

  /** L'aggiornamento e' solo a mano: dopo 6 mesi si propone (`NEEDS_UPDATE`) ma non parte; "Aggiorna" e' una nuova conferma e rifa la regione da capo. */
  @Test
  fun l_AggiornamentoESoloAMano() = runTest {
    val done = RegionState(RegionCatalog.entryOf(RegionId("it-umbria"))!!.spec, RegionStatus.READY, 100, 100, 20_000_000, 20260429, maxZoom = 11)
    val rig = ManagerRig(store = MemoryRegionStateStore(listOf(done)))
    assertThat(rig.state(rig.umbria).status).isEqualTo(RegionStatus.NEEDS_UPDATE) // 29/4 -> 31/10: 185 giorni
    assertThat(rig.regions.covers(TileMath.tileOf(42_900_000, 12_600_000, 8))).isTrue() // ancora coperta
    rig.regions.resumeInterrupted()
    assertThat(rig.engine.starts).isEmpty()
    assertThat(rig.regions.download(rig.umbria, rig.confirm(rig.umbria))).isEqualTo(DownloadStart.Started)
    assertThat(rig.engine.deletes).containsExactly(rig.umbria.id.code)
    assertThat(rig.state(rig.umbria).completedTiles).isEqualTo(0L)
    rig.engine.complete(rig.umbria.id)
    rig.today = 20261101
    assertThat(rig.state(rig.umbria).status).isEqualTo(RegionStatus.READY)
    assertThat(rig.state(rig.umbria).completedDay).isEqualTo(20261031)
  }

  @Test
  fun elimina() = runTest {
    val rig = ManagerRig()
    rig.regions.download(rig.umbria, rig.confirm(rig.umbria))
    rig.engine.complete(rig.umbria.id)
    assertThat(rig.regions.covers(TileMath.tileOf(42_900_000, 12_600_000, 8))).isTrue()
    rig.regions.delete(rig.umbria.id)
    assertThat(rig.regions.regions.value).isEmpty()
    assertThat(rig.store.saved).isEmpty()
    assertThat(rig.engine.deletes).containsExactly(rig.umbria.id.code)
    assertThat(rig.regions.covers(TileMath.tileOf(42_900_000, 12_600_000, 8))).isFalse()
  }

  /** D37: l'azzeramento ferma, toglie tutto e da li' non scrive piu' niente (nemmeno per gli eventi in ritardo del motore). */
  @Test
  fun l_AzzeramentoTogliTuttoENonScrivePiu() = runTest {
    val rig = ManagerRig()
    rig.regions.download(rig.umbria, rig.confirm(rig.umbria))
    rig.engine.complete(rig.umbria.id)
    rig.regions.download(rig.marche, rig.confirm(rig.marche))
    val saves = rig.store.saves
    rig.regions.clear()
    assertThat(rig.engine.deletedAll).isEqualTo(1)
    assertThat(rig.regions.regions.value).isEmpty()
    assertThat(rig.store.saved).isEmpty()
    assertThat(rig.regions.downloadScope()).isNull()
    rig.engine.progress(rig.marche.id, 5, 10)
    rig.engine.complete(rig.marche.id)
    assertThat(rig.regions.regions.value).isEmpty()
    assertThat(rig.store.saves).isEqualTo(saves)
    assertThat(rig.regions.download(rig.umbria, rig.confirm(rig.umbria))).isEqualTo(DownloadStart.Expired)
  }

  @Test
  fun ilMotoreCheNonPartePortaLaRegioneAParzialeEDiscorsoDaRiprendere() = runTest {
    val rig = ManagerRig()
    rig.engine.startFailure = IllegalStateException("prova")
    rig.regions.download(rig.umbria, rig.confirm(rig.umbria))
    assertThat(rig.state(rig.umbria).status).isEqualTo(RegionStatus.PARTIAL)
    assertThat(rig.state(rig.umbria).stop).isEqualTo(RegionStop.INTERRUPTED)
    assertThat(rig.regions.downloadScope()).isNull()
    rig.engine.startFailure = null
    rig.regions.resumeInterrupted()
    assertThat(rig.state(rig.umbria).status).isEqualTo(RegionStatus.DOWNLOADING)
    rig.engine.fail(rig.umbria.id, RegionEngineFailure.SERVER)
    assertThat(rig.state(rig.umbria).stop).isEqualTo(RegionStop.SERVER)
  }

  /** "La zona attorno a un luogo" prende il centro dal luogo cercato (mai dalla casa) e la citta' pesa di piu' di un paese. */
  @Test
  fun attornoAUnLuogoUsaIlCentroDelLuogo() = runTest {
    val rig = ManagerRig()
    val around = rig.regions.around(77, RegionRadius.KM_30) as RegionSpec.AroundPlace
    assertThat(around.urban).isTrue()
    assertThat(around.name).isEqualTo("Luogo di prova")
    assertThat(around.box.contains(43_000_000, 11_000_000)).isTrue()
    assertThat(rig.regions.around(5, RegionRadius.KM_15)).isNull()
    val rural = RegionSpec.AroundPlace(78, RegionRadius.KM_30, around.center, urban = false)
    // A parita' di livello la citta' pesa di piu' (poi il controllo dei MB la fa scendere di livello: vedi `RegionPlannerTest`).
    assertThat(RegionPlanner.estimate(around.box, RegionCatalog.mixOf(around), 13).bytes).isGreaterThan(RegionPlanner.estimate(rural.box, RegionCatalog.mixOf(rural), 13).bytes)
  }
}
