package dev.pampa.mycloudgallery.core.map.contract

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.map.testing.FakeMapNetworkState
import dev.pampa.mycloudgallery.core.map.testing.FakePlaceCenters
import dev.pampa.mycloudgallery.core.map.testing.InMemoryMapRegions
import dev.pampa.mycloudgallery.core.map.testing.SampleRegions
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La suite di contratto delle regioni offline (Fase 9, P0; `docs/fase9-progetto.md` D26, D29, E-MAP11, E-MAP12, E-MAP20, §2.9, §5.2). Gira sul
 * riferimento (`InMemoryMapRegions`) e, da P5, sulle regioni vere: il [Rig] guida rete, spazio, orologio e avanzamento dello scaricamento.
 */
abstract class MapRegionsContract {

  interface Rig {
    val regions: MapRegions
    val network: FakeMapNetworkState
    var wifiOnly: Boolean
    var freeBytes: Long
    var nowMs: Long
    var today: LocalDate

    /** Lo scaricamento di [id] arriva in fondo. */
    fun complete(id: RegionId)

    /** Il processo muore a meta' scaricamento. */
    fun interrupt(id: RegionId)

    fun checkAges()
  }

  /** Un rig con il catalogo [SampleRegions] e il luogo 3000001 (centro inventato) per "attorno a un luogo". */
  protected abstract fun rig(): Rig

  private fun confirm(spec: RegionSpec, rig: Rig, mobile: Boolean = false, at: Long = rig.nowMs) =
    RegionDownloadConfirmation(spec.id, 1, mobile, at)

  @Test
  fun ilCatalogoEFissoEAttornoAUnLuogoPrendeIlCentroDelLuogo() {
    val rig = rig()
    assertThat(rig.regions.catalog().map { it.id }).containsExactlyElementsIn(SampleRegions.CATALOG.map { it.id })
    val around = rig.regions.around(3_000_001, RegionRadius.KM_30)!!
    assertThat(around).isInstanceOf(RegionSpec.AroundPlace::class.java)
    assertThat(around.box.contains(43_000_000, 11_000_000)).isTrue()
    assertThat(rig.regions.around(42, RegionRadius.KM_15)).isNull()
  }

  @Test
  fun laStimaCresceConLaZona() = runTest {
    val rig = rig()
    val small = rig.regions.estimate(rig.regions.around(3_000_001, RegionRadius.KM_15)!!)
    val large = rig.regions.estimate(rig.regions.around(3_000_001, RegionRadius.KM_60)!!)
    assertThat(small.tiles).isGreaterThan(0L)
    assertThat(large.bytes).isGreaterThan(small.bytes)
  }

  /** D26: senza una conferma valida (di questa regione, entro 10 minuti) non parte niente. */
  @Test
  fun serveLaConfermaDelFoglio() = runTest {
    val rig = rig()
    val spec = SampleRegions.NORTH
    assertThat(rig.regions.download(spec, RegionDownloadConfirmation(SampleRegions.SOUTH.id, 1, false, rig.nowMs))).isEqualTo(DownloadStart.Expired)
    val old = confirm(spec, rig, at = rig.nowMs)
    rig.nowMs += RegionDownloadConfirmation.VALID_MS + 1
    assertThat(rig.regions.download(spec, old)).isEqualTo(DownloadStart.Expired)
    assertThat(rig.regions.regions.value).isEmpty()
    assertThat(rig.regions.download(spec, confirm(spec, rig))).isEqualTo(DownloadStart.Started)
  }

  /** D26: solo sul Wi-Fi; sui dati mobili con "Solo sul Wi-Fi" spento serve la seconda conferma; mai in roaming. */
  @Test
  fun laReteDecideLoScaricamento() = runTest {
    val rig = rig()
    val spec = SampleRegions.NORTH
    rig.network.set(MapNetwork.ROAMING)
    assertThat(rig.regions.download(spec, confirm(spec, rig, mobile = true))).isEqualTo(DownloadStart.NeedsWifi)
    rig.network.set(MapNetwork.MOBILE)
    assertThat(rig.regions.download(spec, confirm(spec, rig))).isEqualTo(DownloadStart.NeedsWifi)
    rig.wifiOnly = false
    assertThat(rig.regions.download(spec, confirm(spec, rig))).isInstanceOf(DownloadStart.NeedsMobileConfirmation::class.java)
    assertThat(rig.regions.download(spec, confirm(spec, rig, mobile = true))).isEqualTo(DownloadStart.Started)
  }

  /** E-MAP12: spazio e tetti prima di partire (1 GiB libero sempre; 1 GB per regione). */
  @Test
  fun spazioETetti() = runTest {
    val rig = rig()
    val spec = SampleRegions.NORTH
    rig.freeBytes = RegionLimits.FREE_RESERVE_BYTES
    assertThat(rig.regions.download(spec, confirm(spec, rig))).isInstanceOf(DownloadStart.NoSpace::class.java)
    rig.freeBytes = 64L shl 30
    val world = RegionSpec.Named(RegionId("country-mondo"), RegionKind.COUNTRY, GeoBox(-179_000_000, -80_000_000, 179_000_000, 80_000_000))
    assertThat(rig.regions.download(world, confirm(world, rig))).isEqualTo(DownloadStart.TooLarge)
  }

  /** D26, E-MAP11: solo una regione **completa** copre le sue tessere; interrotta resta parziale e riprende sul Wi-Fi; eliminata non copre piu'. */
  @Test
  fun coperturaRipresaEdEliminazione() = runTest {
    val rig = rig()
    val spec = SampleRegions.NORTH
    val inside = TileMath.tileOf(44_500_000, 10_500_000, 14)
    val outside = TileMath.tileOf(38_500_000, 15_500_000, 14)
    rig.regions.download(spec, confirm(spec, rig))
    assertThat(rig.regions.covers(inside)).isFalse()
    rig.interrupt(spec.id)
    assertThat(rig.regions.regions.value.single().status).isEqualTo(RegionStatus.PARTIAL)
    rig.network.set(MapNetwork.MOBILE)
    assertThat(rig.regions.resume(spec.id)).isEqualTo(DownloadStart.NeedsWifi)
    rig.network.set(MapNetwork.WIFI)
    assertThat(rig.regions.resume(spec.id)).isEqualTo(DownloadStart.Started)
    rig.complete(spec.id)
    assertThat(rig.regions.regions.value.single().status).isEqualTo(RegionStatus.READY)
    assertThat(rig.regions.covers(inside)).isTrue()
    assertThat(rig.regions.covers(TileKey(0, 0, 0))).isTrue()
    assertThat(rig.regions.covers(outside)).isFalse()
    assertThat(rig.regions.covers(TileKey(15, inside.x * 2, inside.y * 2))).isFalse()
    rig.regions.delete(spec.id)
    assertThat(rig.regions.covers(inside)).isFalse()
  }

  /** E-MAP20: la mappa spenta o il roaming fermano gli scaricamenti (restano parziali); dopo 6 mesi si propone "Aggiorna" (solo a mano). */
  @Test
  fun pausaEAggiornamentoSoloAMano() = runTest {
    val rig = rig()
    val spec = SampleRegions.SOUTH
    rig.regions.download(spec, confirm(spec, rig))
    rig.regions.pauseAll()
    assertThat(rig.regions.regions.value.single().status).isEqualTo(RegionStatus.PARTIAL)
    rig.regions.resume(spec.id)
    rig.complete(spec.id)
    rig.today = rig.today.plusDays(RegionLimits.UPDATE_AFTER_DAYS + 1)
    rig.checkAges()
    assertThat(rig.regions.regions.value.single().status).isEqualTo(RegionStatus.NEEDS_UPDATE)
    // Ancora coperta: aggiornare e' una scelta, non un buco.
    assertThat(rig.regions.covers(TileMath.tileOf(38_500_000, 15_500_000, 12))).isTrue()
  }
}

/** La suite sul riferimento (§5.2). */
class InMemoryMapRegionsContractTest : MapRegionsContract() {
  override fun rig(): Rig = object : Rig {
    override val network = FakeMapNetworkState()
    override var wifiOnly = true
    override var nowMs = 1_000L
    override var today: LocalDate = LocalDate.of(2026, 10, 27)
    private val memory = InMemoryMapRegions(
      network = network,
      wifiOnly = { wifiOnly },
      nowMs = { nowMs },
      today = { today },
      centers = FakePlaceCenters(mapOf(3_000_001 to GeoE6(43_000_000, 11_000_000))),
    )
    override val regions: MapRegions = memory
    override var freeBytes: Long
      get() = memory.freeBytes
      set(value) {
        memory.freeBytes = value
      }

    override fun complete(id: RegionId) = memory.complete(id)

    override fun interrupt(id: RegionId) = memory.interrupt(id)

    override fun checkAges() = memory.checkAges()
  }
}
