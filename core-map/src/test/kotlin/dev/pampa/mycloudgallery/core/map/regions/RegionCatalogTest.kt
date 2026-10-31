package dev.pampa.mycloudgallery.core.map.regions

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.map.contract.RegionId
import dev.pampa.mycloudgallery.core.map.contract.RegionKind
import dev.pampa.mycloudgallery.core.map.contract.RegionRadius
import dev.pampa.mycloudgallery.core.map.contract.RegionSpec
import java.io.File
import org.junit.Test

/**
 * Il catalogo fisso (Fase 9, P5; D26, C156): le 20 regioni italiane e i 6 paesi vicini, con i riquadri nel codice, mai una zona proposta dalla casa o dai punti delle foto.
 */
class RegionCatalogTest {

  private val italian = listOf(
    "abruzzo", "basilicata", "calabria", "campania", "emilia-romagna", "friuli-venezia-giulia", "lazio", "liguria", "lombardia", "marche",
    "molise", "piemonte", "puglia", "sardegna", "sicilia", "toscana", "trentino-alto-adige", "umbria", "valle-d-aosta", "veneto",
  )
  private val countries = listOf("fr", "ch", "at", "si", "sm", "va")

  @Test
  fun ilCatalogoEFissoVentiRegioniESeiPaesi() {
    assertThat(RegionCatalog.specs().map { it.id.code }).containsExactlyElementsIn(italian.map { "it-$it" } + countries.map { "country-$it" }).inOrder()
    assertThat(RegionCatalog.ENTRIES.count { it.spec.kind == RegionKind.ITALIAN_REGION }).isEqualTo(20)
    assertThat(RegionCatalog.ENTRIES.count { it.spec.kind == RegionKind.COUNTRY }).isEqualTo(6)
    assertThat(RegionCatalog.specs().map { it.id }.toSet()).hasSize(26)
    for (entry in RegionCatalog.ENTRIES) assertWithMessage(entry.spec.id.code).that(entry.spec.name).isNotEmpty()
  }

  /** I riquadri sono arrotondati a 0,1 gradi **verso l'esterno** e hanno una forma sensata (mai oltre i limiti di Mercatore, mai rovesciati, nessuno attraversa l'antimeridiano). */
  @Test
  fun iRiquadriSonoArrotondatiAUnDecimoVersoLEsterno() {
    for (entry in RegionCatalog.ENTRIES) {
      val box = entry.spec.box
      assertWithMessage(entry.spec.id.code).that(box.crossesAntimeridian).isFalse()
      for (edge in listOf(box.westE6, box.southE6, box.eastE6, box.northE6)) assertThat(edge % 100_000).isEqualTo(0)
      assertThat(box.westE6).isLessThan(box.eastE6)
      assertThat(box.southE6).isLessThan(box.northE6)
    }
    // Qualche riferimento: i punti estremi noti stanno dentro (verso l'esterno).
    val toscana = RegionCatalog.entryOf(RegionId("it-toscana"))!!.spec.box
    assertThat(toscana.contains(43_770_000, 11_250_000)).isTrue() // Firenze
    assertThat(toscana.contains(42_360_000, 10_920_000)).isTrue() // Isola del Giglio
    val sicilia = RegionCatalog.entryOf(RegionId("it-sicilia"))!!.spec.box
    assertThat(sicilia.contains(35_500_000, 12_600_000)).isTrue() // Lampedusa
    assertThat(RegionCatalog.entryOf(RegionId("it-sardegna"))!!.spec.box.contains(39_220_000, 9_120_000)).isTrue() // Cagliari
    assertThat(RegionCatalog.entryOf(RegionId("country-fr"))!!.spec.box.contains(48_850_000, 2_350_000)).isTrue() // Parigi
    assertThat(RegionCatalog.entryOf(RegionId("country-va"))!!.spec.box.contains(41_902_000, 12_453_000)).isTrue()
  }

  @Test
  fun leQuoteDiOgniVoceFannoUno() {
    for (entry in RegionCatalog.ENTRIES) {
      val m = entry.mix
      assertWithMessage(entry.spec.id.code).that(m.urban + m.rural + m.sea).isWithin(1e-9).of(1.0)
    }
    assertThat(RegionCatalog.mixOf(RegionSpec.AroundPlace(1, RegionRadius.KM_15, dev.pampa.mycloudgallery.core.map.contract.GeoE6(0, 0), urban = false)).rural).isEqualTo(1.0)
    assertThat(RegionCatalog.aroundMix(urban = true, radius = RegionRadius.KM_15).urban).isEqualTo(1.0)
    assertThat(RegionCatalog.aroundMix(urban = true, radius = RegionRadius.KM_60).urban).isEqualTo(0.25)
  }

  /** D29, C156: il catalogo non legge ne' nomina la casa ne' i punti delle foto ne' un luogo dell'utente: e' un elenco nel codice. */
  @Test
  fun ilCatalogoNonNominaLaCasaNeIPuntiDelleFoto() {
    val source = File("src/main/kotlin/dev/pampa/mycloudgallery/core/map/regions/RegionCatalog.kt").readText()
    // (Il nome del dato della casa si scrive a pezzi: Phase9ArchitectureTest cerca in ogni file di core-map, prove comprese, e un nome intero in una prova che lo vieta farebbe fallire la prova che lo vieta.)
    val forbidden = Regex("(?i)hom" + "e|casa\\b|MapPoint|GeoMapPoints|MapPointSource|PlaceCenters|PlaceSearch")
    assertThat(forbidden.findAll(source.lineSequence().filter { !it.trimStart().startsWith("//") && !it.trimStart().startsWith("*") && !it.trimStart().startsWith("/**") }.joinToString("\n")).map { it.value }.toList()).isEmpty()
  }
}
