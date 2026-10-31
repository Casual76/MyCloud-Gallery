package dev.pampa.mycloudgallery.core.map.regions

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.map.contract.BudgetLimit
import dev.pampa.mycloudgallery.core.map.contract.DownloadBudget
import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapRegions
import dev.pampa.mycloudgallery.core.map.contract.MapRegionsContract
import dev.pampa.mycloudgallery.core.map.contract.RegionId
import dev.pampa.mycloudgallery.core.map.contract.RegionKind
import dev.pampa.mycloudgallery.core.map.contract.RegionRadius
import dev.pampa.mycloudgallery.core.map.contract.RegionSpec
import dev.pampa.mycloudgallery.core.map.contract.RegionState
import dev.pampa.mycloudgallery.core.map.contract.RegionStatus
import dev.pampa.mycloudgallery.core.map.contract.RegionStop
import dev.pampa.mycloudgallery.core.map.testing.FakeMapNetworkState
import dev.pampa.mycloudgallery.core.map.testing.FakePlaceCenters
import dev.pampa.mycloudgallery.core.map.testing.FakeRegionEngine
import dev.pampa.mycloudgallery.core.map.testing.InMemoryMapSettings
import dev.pampa.mycloudgallery.core.map.testing.SampleRegions
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** La suite di contratto delle regioni (`MapRegionsContract`) sulle regioni vere di P5 (`OfflineMapRegions`), con motore, stato su disco, rete e orologi finti: stessa suite del riferimento. */
class OfflineMapRegionsContractTest : MapRegionsContract() {

  override fun rig(): Rig = object : Rig {
    val engine = FakeRegionEngine()
    val store = MemoryRegionStateStore()
    val settings = InMemoryMapSettings()
    override val network = FakeMapNetworkState(MapNetwork.WIFI)
    override var nowMs: Long = 1_000L
    override var freeBytes: Long = 64L shl 30
    private var todayDate: LocalDate = LocalDate.of(2026, 10, 27)
    override var today: LocalDate
      get() = todayDate
      set(value) {
        todayDate = value
      }
    override var wifiOnly: Boolean = true
      set(value) {
        field = value
        runBlocking { settings.setWifiOnly(value) }
      }

    private fun build() = OfflineMapRegions(
      engine = engine,
      store = store,
      network = network,
      settings = settings,
      places = FakePlaceCenters(mapOf(3_000_001 to GeoE6(43_000_000, 11_000_000))),
      freeBytes = { freeBytes },
      nowMs = { nowMs },
      today = { todayDate.year * 10_000 + todayDate.monthValue * 100 + todayDate.dayOfMonth },
      scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
      catalog = SampleRegions.CATALOG,
      stopGraceMs = 0,
    )

    private var current: OfflineMapRegions = build()
    override val regions: MapRegions get() = current

    override fun complete(id: RegionId) {
      engine.complete(id)
    }

    /** La morte del processo: un gestore nuovo legge lo stato su disco (la regione "in scaricamento" e' parziale e interrotta). */
    override fun interrupt(id: RegionId) {
      current = build()
    }

    override fun checkAges() = current.refreshAges()
  }
}

/** Il conto di uno scaricamento (`DownloadBudget`): posto per una tessera alla volta, byte veri, tetti, una sola notifica. */
class DownloadBudgetTest {

  @Test
  fun lePrimeTessereEntranoFinoAlTettoEPoiNo() {
    val budget = DownloadBudget(maxTiles = 3, maxBytes = 1_000)
    assertThat(budget.tryAdmit()).isTrue()
    assertThat(budget.tryAdmit()).isTrue()
    assertThat(budget.tryAdmit()).isTrue()
    assertThat(budget.tryAdmit()).isFalse() // tre in volo: il tetto conta anche quelle
    assertThat(budget.peakInFlight).isEqualTo(3)
    budget.failed()
    assertThat(budget.tryAdmit()).isTrue() // una non e' arrivata: si puo' riprovare
    budget.arrived(); budget.arrived(); budget.arrived()
    assertThat(budget.tiles).isEqualTo(3)
    assertThat(budget.limit).isEqualTo(BudgetLimit.TILES)
    assertThat(budget.exhausted).isTrue()
  }

  @Test
  fun iByteSuperatiChiudonoIlConto() {
    val budget = DownloadBudget(maxTiles = 100, maxBytes = 1_000, startTiles = 2, startBytes = 900)
    assertThat(budget.tiles).isEqualTo(2)
    assertThat(budget.exhausted).isFalse()
    budget.received(99)
    assertThat(budget.exhausted).isFalse()
    budget.received(1)
    assertThat(budget.limit).isEqualTo(BudgetLimit.BYTES)
    assertThat(budget.tryAdmit()).isFalse()
  }

  @Test
  fun avvisaUnaVoltaSola() {
    val budget = DownloadBudget(maxTiles = 100, maxBytes = 1_000)
    val seen = mutableListOf<BudgetLimit>()
    budget.onExhausted = { seen += it }
    budget.received(1_001)
    budget.received(10)
    budget.tryAdmit(); budget.arrived()
    assertThat(seen).containsExactly(BudgetLimit.BYTES)
  }

  /** Da molti thread il conto non perde ne' regala tessere: mai oltre il tetto, mai piu' di quante ne sono entrate. */
  @Test
  fun daMoltiThreadNonSuperaIlTetto() {
    repeat(50) {
      val budget = DownloadBudget(maxTiles = 100, maxBytes = Long.MAX_VALUE)
      val admitted = AtomicInteger()
      val gate = CountDownLatch(1)
      val pool = Executors.newFixedThreadPool(8)
      try {
        val jobs = (0 until 8).map {
          pool.submit {
            gate.await()
            repeat(40) { if (budget.tryAdmit()) { admitted.incrementAndGet(); budget.arrived() } }
          }
        }
        gate.countDown()
        jobs.forEach { it.get() }
      } finally {
        pool.shutdown()
      }
      assertThat(admitted.get()).isEqualTo(100)
      assertThat(budget.tiles).isEqualTo(100)
      assertThat(budget.inFlight).isEqualTo(0)
    }
  }
}

/** Lo stato su disco: una riga per regione, scritta in modo atomico, riletta uguale; le righe rovinate si saltano; un file che non e' il nostro vale "nessuna regione". */
class FileRegionStateStoreTest {

  private fun file(): File = Files.createTempDirectory("regioni").toFile().resolve("map/${FileRegionStateStore.FILE_NAME}")

  private val named = RegionState(
    RegionSpec.Named(RegionId("it-umbria"), RegionKind.ITALIAN_REGION, GeoBox(11_900_000, 42_300_000, 13_300_000, 43_700_000), "Umbria"),
    RegionStatus.READY, 100, 100, 21_000_000, 20261031, maxZoom = 12,
  )
  private val around = RegionState(
    RegionSpec.AroundPlace(3_172_000, RegionRadius.KM_30, GeoE6(43_300_000, 11_300_000), urban = true, name = "Luogo\tcon\ntabulazione"),
    RegionStatus.PARTIAL, 40, 1_700, 9_000_000, null, maxZoom = 14, stop = RegionStop.LIMIT_BYTES,
  )

  @Test
  fun siRilegge_Uguale() {
    val f = file()
    val store = FileRegionStateStore(f)
    assertThat(store.load()).isEmpty()
    store.save(listOf(named, around))
    val back = FileRegionStateStore(f).load()
    assertThat(back).hasSize(2)
    assertThat(back[0]).isEqualTo(named)
    assertThat(back[1].spec).isEqualTo((around.spec as RegionSpec.AroundPlace).copy(name = "Luogocontabulazione"))
    assertThat(back[1].stop).isEqualTo(RegionStop.LIMIT_BYTES)
    assertThat(back[1].status).isEqualTo(RegionStatus.PARTIAL)
    assertThat(back[1].bytes).isEqualTo(9_000_000L)
  }

  @Test
  fun leRigheRovinateSiSaltanoEUnFileAltrui_Vale_Nulla() {
    val f = file()
    val store = FileRegionStateStore(f)
    store.save(listOf(named))
    f.appendText("riga rovinata\nit-x\t1\t2\n")
    assertThat(store.load()).containsExactly(named)
    f.writeText("un altro file\n")
    assertThat(store.load()).isEmpty()
    store.delete()
    assertThat(f.exists()).isFalse()
  }

  @Test
  fun nelLogENelToStringNienteZone() {
    assertThat(named.toString()).doesNotContain("Umbria")
    assertThat(named.spec.toString()).doesNotContain("Umbria")
    assertThat(around.spec.toString()).doesNotContain("Luogo")
    assertThat(around.toString()).doesNotContain("Luogo")
    assertThat(RegionId("it-umbria").toString()).doesNotContain("umbria")
  }
}
