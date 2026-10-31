package dev.pampa.mycloudgallery.core.map.regions

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.OpenFreeMapLimits
import dev.pampa.mycloudgallery.core.map.contract.RegionId
import dev.pampa.mycloudgallery.core.map.contract.RegionLimits
import dev.pampa.mycloudgallery.core.map.contract.RegionRadius
import dev.pampa.mycloudgallery.core.map.contract.RegionSpec
import dev.pampa.mycloudgallery.core.map.contract.TileMath
import java.io.File
import java.util.Random
import org.junit.Test

/**
 * La stima e il piano delle regioni (Fase 9, P5; decisione dell'utente del 31/10: dentro i limiti di OpenFreeMap). Tre prove:
 *  1. le tessere si contano come le conta una formula indipendente (Mercatore in doppia precisione, come lo strumento di calibrazione): sui tre campioni veri e su riquadri casuali;
 *  2. la stima sta **entro il 30%** dei tre campioni veri scaricati il 31/10 dal PC (Marche, 15 km attorno a Bologna, 30 km attorno a Sulmona: 2.809 tessere, 72,5 MB);
 *  3. il piano sceglie il livello piu' alto che sta nei limiti (3.000 tessere, 50 MB con il fattore 1,3), mai sotto z10, mai oltre il livello di partenza.
 */
class RegionPlannerTest {

  /** Un campione scaricato davvero: la zona, il livello massimo, le tessere (identiche per forza) e i byte sul filo. */
  private class Sample(val name: String, val spec: RegionSpec, val maxZoom: Int, val tiles: Long, val bytes: Long)

  private val marche = RegionCatalog.entryOf(RegionId("it-marche"))!!.spec
  private val bologna = RegionSpec.AroundPlace(1, RegionRadius.KM_15, GeoE6(44_494_900, 11_342_600), urban = true)
  private val sulmona = RegionSpec.AroundPlace(2, RegionRadius.KM_30, GeoE6(42_047_300, 13_926_800), urban = false)

  private val samples = listOf(
    Sample("Marche z0-z12", marche, 12, 731, 24_260_866),
    Sample("15 km attorno a Bologna z0-z14", bologna, 14, 484, 17_092_420),
    Sample("30 km attorno a Sulmona z0-z14", sulmona, 14, 1_594, 31_184_966),
  )

  @Test
  fun leTessereSiContanoComeUnaFormulaIndipendente() {
    for (s in samples) assertWithMessage(s.name).that(TileMath.count(s.spec.box, 0, s.maxZoom)).isEqualTo(s.tiles)
    val random = Random(5)
    repeat(2_000) {
      val west = random.nextDouble() * 340 - 170
      val south = random.nextDouble() * 150 - 75
      val box = GeoBox(e6(west), e6(south), e6(west + random.nextDouble() * 9 + 0.001), e6(south + random.nextDouble() * 9 + 0.001))
      val z = random.nextInt(15)
      assertThat(TileMath.count(box, z, z)).isEqualTo(independentCount(box, z))
    }
  }

  @Test
  fun laStimaStaEntroIlTrentaPerCentoDeiCampioniVeri() {
    for (s in samples) {
      val estimate = RegionPlanner.estimate(s.spec.box, RegionCatalog.mixOf(s.spec), s.maxZoom)
      val error = (estimate.bytes - s.bytes).toDouble() / s.bytes
      println("campione ${s.name}: stima ${estimate.bytes} byte, vero ${s.bytes}, errore ${"%.1f".format(error * 100)}%")
      assertThat(estimate.tiles).isEqualTo(s.tiles)
      assertWithMessage("${s.name}: errore ${error * 100}%").that(StrictMath.abs(error)).isAtMost(0.30)
    }
  }

  @Test
  fun ilPianoStaNeiLimitiEScegliIlLivelloPiuAlto() {
    for (entry in RegionCatalog.ENTRIES) {
      val plan = RegionPlanner.plan(entry.spec)
      assertWithMessage(entry.spec.id.code).that(plan.maxZoom).isIn(RegionLimits.MIN_OFFER_ZOOM..RegionLimits.CATALOG_TOP_ZOOM)
      if (plan.offered) {
        assertThat(RegionPlanner.fits(plan.tiles, plan.bytes)).isTrue()
        assertThat(plan.tiles).isAtMost(OpenFreeMapLimits.PLAN_MAX_TILES.toLong())
        assertThat(plan.bytes * OpenFreeMapLimits.ESTIMATE_SAFETY).isAtMost(OpenFreeMapLimits.MAX_BYTES.toDouble())
        if (plan.maxZoom < RegionLimits.CATALOG_TOP_ZOOM) {
          val above = RegionPlanner.estimate(entry.spec.box, entry.mix, plan.maxZoom + 1)
          assertWithMessage("${entry.spec.id.code}: il livello sopra ci sta, quindi andava scelto").that(RegionPlanner.fits(above.tiles, above.bytes)).isFalse()
        }
      } else {
        assertThat(plan.maxZoom).isEqualTo(RegionLimits.MIN_OFFER_ZOOM)
        assertThat(RegionPlanner.fits(plan.tiles, plan.bytes)).isFalse()
      }
    }
  }

  /** Il piano lascia 100 tessere di margine sul tetto di 3.000 (richieste doppie di MapLibre dopo una ripresa): 2.900 ci stanno, 2.901 no, e i MB sono quelli del fattore 1,3. */
  @Test
  fun ilPianoLasciaIlMargineSulleTessere() {
    assertThat(RegionPlanner.fits(OpenFreeMapLimits.PLAN_MAX_TILES.toLong(), 1_000)).isTrue()
    assertThat(RegionPlanner.fits(OpenFreeMapLimits.PLAN_MAX_TILES.toLong() + 1, 1_000)).isFalse()
    assertThat(OpenFreeMapLimits.PLAN_MAX_TILES).isEqualTo(OpenFreeMapLimits.MAX_TILES - 100)
    assertThat(RegionPlanner.fits(100, (OpenFreeMapLimits.MAX_BYTES / OpenFreeMapLimits.ESTIMATE_SAFETY).toLong())).isTrue()
    assertThat(RegionPlanner.fits(100, (OpenFreeMapLimits.MAX_BYTES / OpenFreeMapLimits.ESTIMATE_SAFETY).toLong() + 1_000)).isFalse()
  }

  @Test
  fun attornoAUnLuogoDi15E30KmSiParteDaZ14EDi60KmDaZ13() {
    val center = GeoE6(43_000_000, 11_000_000)
    assertThat(RegionPlanner.topZoom(RegionSpec.AroundPlace(1, RegionRadius.KM_15, center))).isEqualTo(14)
    assertThat(RegionPlanner.topZoom(RegionSpec.AroundPlace(1, RegionRadius.KM_30, center))).isEqualTo(14)
    assertThat(RegionPlanner.topZoom(RegionSpec.AroundPlace(1, RegionRadius.KM_60, center))).isEqualTo(13)
    assertThat(RegionPlanner.topZoom(marche)).isEqualTo(12)
    // I due campioni di campagna e di citta' ci stanno a z14 (e' quello che si e' scaricato davvero).
    assertThat(RegionPlanner.plan(bologna).maxZoom).isEqualTo(14)
    assertThat(RegionPlanner.plan(sulmona).maxZoom).isEqualTo(14)
    // Il conto delle tessere dei riferimenti dell'utente: 30 km a z14 e 60 km a z13 stanno sotto le 3.000 tessere (circa 1.780 e 1.650 attorno a 43 N).
    val wide30 = TileMath.count(RegionSpec.AroundPlace(1, RegionRadius.KM_30, center).box, 0, 14)
    val wide60 = TileMath.count(RegionSpec.AroundPlace(1, RegionRadius.KM_60, center).box, 0, 13)
    assertThat(wide30).isIn(1_700L..1_850L)
    assertThat(wide60).isIn(1_550L..1_800L)
    // "Anche qui vale il controllo di MB": 30 km attorno a una citta' (tessere dense) non stanno a z14 e scendono a z13; attorno a un paese si'.
    val bigCity = RegionSpec.AroundPlace(1, RegionRadius.KM_30, center, urban = true)
    assertThat(RegionPlanner.plan(bigCity).maxZoom).isEqualTo(13)
    assertThat(RegionPlanner.plan(bigCity).offered).isTrue()
    assertThat(RegionPlanner.plan(RegionSpec.AroundPlace(1, RegionRadius.KM_30, center, urban = false)).maxZoom).isEqualTo(14)
  }

  /** Il piano non offre mai oltre i limiti, qualunque zona: proprieta' su riquadri e quote casuali. */
  @Test
  fun proprietaNessunaZonaOfferta_SuperaIlimiti() {
    val random = Random(23)
    repeat(3_000) {
      val west = random.nextDouble() * 300 - 150
      val south = random.nextDouble() * 120 - 60
      val width = random.nextDouble() * 20 + 0.05
      val height = random.nextDouble() * 12 + 0.05
      val urban = random.nextDouble()
      val sea = random.nextDouble() * (1 - urban)
      val box = GeoBox(e6(west), e6(south), e6(west + width), e6(south + height))
      val mix = ZoneMix(urban, 1 - urban - sea, sea)
      val plan = RegionPlanner.plan(box, mix, RegionLimits.CATALOG_TOP_ZOOM)
      assertThat(plan.maxZoom).isIn(RegionLimits.MIN_OFFER_ZOOM..RegionLimits.CATALOG_TOP_ZOOM)
      if (plan.offered) {
        assertThat(plan.tiles).isAtMost(OpenFreeMapLimits.MAX_TILES.toLong())
        assertThat(plan.bytes * OpenFreeMapLimits.ESTIMATE_SAFETY).isAtMost(OpenFreeMapLimits.MAX_BYTES.toDouble())
      } else {
        assertThat(plan.maxZoom).isEqualTo(RegionLimits.MIN_OFFER_ZOOM)
        assertThat(RegionPlanner.fits(plan.tiles, plan.bytes)).isFalse()
      }
    }
  }

  /** Scrive la tabella del catalogo per `docs/decisioni.md` (livello massimo, tessere, MB stimati, offerta) in `build/regioni-catalogo.md`. */
  @Test
  fun laTabellaDelCatalogo() {
    val out = StringBuilder("| Voce | Livello massimo | Tessere | MB stimati | Offerta |\n|---|---|---|---|---|\n")
    for (entry in RegionCatalog.ENTRIES) {
      val plan = RegionPlanner.plan(entry.spec)
      val top = RegionPlanner.estimate(entry.spec.box, entry.mix, RegionLimits.CATALOG_TOP_ZOOM)
      out.append("| ${entry.spec.name} | ${if (plan.offered) "z${plan.maxZoom}" else "-"} | ${plan.tiles} | ${"%.1f".format(plan.bytes / 1e6)} (a z12: ${top.tiles} tessere, ${"%.1f".format(top.bytes / 1e6)} MB) | ${if (plan.offered) "si'" else "no"} |\n")
    }
    File("build").mkdirs()
    File("build/regioni-catalogo.md").writeText(out.toString())
    println(out)
    assertThat(RegionCatalog.ENTRIES.any { RegionPlanner.plan(it.spec).offered }).isTrue()
  }

  /** Scrive la tabella di "la zona attorno a un luogo" (a 43 N, un luogo di campagna e uno di citta'; 15, 30 e 60 km) in `build/regioni-attorno.md`. */
  @Test
  fun laTabellaAttornoAUnLuogo() {
    val out = StringBuilder("| Luogo | Raggio | Livello massimo | Tessere | MB stimati | Offerta |\n|---|---|---|---|---|---|\n")
    for (urban in listOf(false, true)) for (radius in RegionRadius.entries) {
      val spec = RegionSpec.AroundPlace(1, radius, GeoE6(43_000_000, 11_000_000), urban)
      val plan = RegionPlanner.plan(spec)
      out.append("| ${if (urban) "citta'" else "campagna"} | ${radius.km} km | ${if (plan.offered) "z${plan.maxZoom}" else "-"} | ${plan.tiles} | ${"%.1f".format(plan.bytes / 1e6)} | ${if (plan.offered) "si'" else "no"} |\n")
    }
    File("build").mkdirs()
    File("build/regioni-attorno.md").writeText(out.toString())
    println(out)
  }

  private fun e6(degrees: Double) = StrictMath.round(degrees * 1_000_000.0).toInt()

  /** Il conto delle tessere con le formule "da manuale" in doppia precisione (come `build/p5/calibra.py`). */
  private fun independentCount(box: GeoBox, z: Int): Long {
    val n = 1L shl z
    fun x(lonE6: Int) = StrictMath.floor((lonE6 / 1e6 + 180.0) / 360.0 * n).toLong().coerceIn(0, n - 1)
    fun y(latE6: Int): Long {
      val r = StrictMath.toRadians((latE6 / 1e6).coerceIn(-85.0511, 85.0511))
      val asinh = StrictMath.log(StrictMath.tan(r) + 1.0 / StrictMath.cos(r))
      return StrictMath.floor((1.0 - asinh / StrictMath.PI) / 2.0 * n).toLong().coerceIn(0, n - 1)
    }
    return (x(box.eastE6) - x(box.westE6) + 1) * (y(box.southE6) - y(box.northE6) + 1)
  }
}
