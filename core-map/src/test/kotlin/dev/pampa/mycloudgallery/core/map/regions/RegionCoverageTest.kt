package dev.pampa.mycloudgallery.core.map.regions

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.RegionId
import dev.pampa.mycloudgallery.core.map.contract.RegionKind
import dev.pampa.mycloudgallery.core.map.contract.RegionSpec
import dev.pampa.mycloudgallery.core.map.contract.RegionState
import dev.pampa.mycloudgallery.core.map.contract.RegionStatus
import dev.pampa.mycloudgallery.core.map.contract.TileKey
import dev.pampa.mycloudgallery.core.map.contract.TileMath
import dev.pampa.mycloudgallery.core.map.testing.FakeMapNetworkState
import dev.pampa.mycloudgallery.core.map.testing.FakeRegionEngine
import dev.pampa.mycloudgallery.core.map.testing.InMemoryMapSettings
import java.util.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Test

/**
 * La copertura delle regioni e' esatta ai bordi (Fase 9, P5; §2.9, D26; "proprieta' sulle tessere"). `covers` di una regione **completa** deve dire "si'" per **tutte e sole** le tessere che lo
 * scaricamento ha scaricato (quelle che toccano il riquadro, da z0 al livello della regione): se una tessera scaricata non fosse coperta si chiederebbe online (la zona si rivelerebbe), se una non
 * scaricata lo fosse la mappa avrebbe un buco. Tre proprieta', su riquadri casuali e su riquadri con i bordi **esattamente** sui bordi delle tessere:
 *  1. ogni punto del riquadro (angoli, bordi, punti a caso) sta in una tessera coperta a ogni livello fino al massimo;
 *  2. le tessere coperte a un livello sono esattamente quelle dell'elenco dello scaricamento (conteggio e appartenenza, forza bruta sui livelli bassi);
 *  3. nessuna tessera coperta e' lontana dal riquadro, e nessuna e' coperta oltre il livello massimo ne' per una regione non completa.
 */
class RegionCoverageTest {

  private fun regions(spec: RegionSpec, maxZoom: Int, status: RegionStatus = RegionStatus.READY): OfflineMapRegions =
    OfflineMapRegions(
      engine = FakeRegionEngine(),
      store = MemoryRegionStateStore(listOf(RegionState(spec, status, 10, 10, 1_000, 20261031, maxZoom = maxZoom))),
      network = FakeMapNetworkState(MapNetwork.WIFI),
      settings = InMemoryMapSettings(),
      places = dev.pampa.mycloudgallery.core.map.noop.NoPlaceCenters,
      freeBytes = { Long.MAX_VALUE },
      nowMs = { 0L },
      today = { 20261031 },
      scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

  private fun named(box: GeoBox) = RegionSpec.Named(RegionId("it-prova"), RegionKind.ITALIAN_REGION, box)

  private fun randomBox(random: Random): GeoBox {
    val west = random.nextDouble() * 300 - 150
    val south = random.nextDouble() * 120 - 60
    return GeoBox(e6(west), e6(south), e6(west + random.nextDouble() * 6 + 0.002), e6(south + random.nextDouble() * 4 + 0.002))
  }

  /** Un riquadro i cui quattro bordi stanno sui bordi delle tessere di livello [z] (a meno dell'arrotondamento al microgrado). */
  private fun alignedBox(random: Random, z: Int): GeoBox {
    val x = random.nextInt(1 shl (z - 1)) + (1 shl (z - 2))
    val y = random.nextInt(1 shl (z - 1)) + (1 shl (z - 2))
    val a = TileMath.boxOf(TileKey(z, x, y))
    val b = TileMath.boxOf(TileKey(z, minOf(x + random.nextInt(3), (1 shl z) - 1), minOf(y + random.nextInt(3), (1 shl z) - 1)))
    return GeoBox(minOf(a.westE6, b.westE6), minOf(a.southE6, b.southE6), maxOf(a.eastE6, b.eastE6), maxOf(a.northE6, b.northE6))
  }

  @Test
  fun ogniPuntoDelRiquadroStaInUnaTesseraCoperta() {
    val random = Random(41)
    repeat(400) { n ->
      val box = if (n % 2 == 0) randomBox(random) else alignedBox(random, 8 + random.nextInt(5))
      val maxZoom = 10 + random.nextInt(5)
      val covers = regions(named(box), maxZoom)
      val points = mutableListOf(
        box.southE6 to box.westE6, box.southE6 to box.eastE6, box.northE6 to box.westE6, box.northE6 to box.eastE6,
        (box.southE6 + box.northE6) / 2 to box.westE6, (box.southE6 + box.northE6) / 2 to box.eastE6,
        box.southE6 to (box.westE6 + box.eastE6) / 2, box.northE6 to (box.westE6 + box.eastE6) / 2,
      )
      repeat(40) { points += (box.southE6 + random.nextInt(box.northE6 - box.southE6 + 1)) to (box.westE6 + random.nextInt(box.eastE6 - box.westE6 + 1)) }
      for ((lat, lon) in points) for (z in 0..maxZoom) {
        val tile = TileMath.tileOf(lat, lon, z)
        assertWithMessage("punto del riquadro a z$z non coperto").that(covers.covers(tile)).isTrue()
      }
    }
  }

  @Test
  fun letessereCopertesonoEsattamenteQuelleDelloScaricamento() {
    val random = Random(43)
    repeat(30) { n ->
      val box = if (n % 2 == 0) randomBox(random) else alignedBox(random, 8 + random.nextInt(2))
      val maxZoom = 9
      val covers = regions(named(box), maxZoom)
      var covered = 0L
      for (z in 0..maxZoom) {
        val side = 1 shl z
        var atLevel = 0L
        for (x in 0 until side) for (y in 0 until side) if (covers.covers(TileKey(z, x, y))) atLevel++
        assertWithMessage("livello $z").that(atLevel).isEqualTo(TileMath.count(box, z, z))
        covered += atLevel
      }
      assertThat(covered).isEqualTo(TileMath.count(box, 0, maxZoom))
    }
  }

  @Test
  fun nessunaTesseraLontanaNeOltreIlLivelloNePerUnaRegioneNonCompleta() {
    val random = Random(47)
    repeat(200) {
      val box = randomBox(random)
      val maxZoom = 10 + random.nextInt(3)
      val covers = regions(named(box), maxZoom)
      val partial = regions(named(box), maxZoom, RegionStatus.PARTIAL)
      repeat(300) {
        val z = random.nextInt(15)
        val side = 1 shl z
        val tile = TileKey(z, random.nextInt(side), random.nextInt(side))
        assertThat(partial.covers(tile)).isFalse()
        if (covers.covers(tile)) {
          assertThat(z).isAtMost(maxZoom)
          val t = TileMath.boxOf(tile)
          // La tessera e' vicina al riquadro: i due rettangoli si toccano (con un margine di 2 microgradi per l'arrotondamento dei bordi).
          assertThat(t.westE6 <= box.eastE6 + 2 && t.eastE6 >= box.westE6 - 2 && t.southE6 <= box.northE6 + 2 && t.northE6 >= box.southE6 - 2).isTrue()
        }
      }
      // Oltre il livello massimo una tessera dentro il riquadro non e' coperta (a z oltre si chiede online, come dice la nota).
      val inside = TileMath.tileOf((box.southE6 + box.northE6) / 2, (box.westE6 + box.eastE6) / 2, minOf(14, maxZoom + 1))
      if (maxZoom < 14) assertThat(covers.covers(inside)).isFalse()
    }
  }

  private fun e6(degrees: Double) = StrictMath.round(degrees * 1_000_000.0).toInt()
}
