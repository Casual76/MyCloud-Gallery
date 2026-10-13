package dev.pampa.mycloudgallery.core.places

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.places.tool.DistrictAbsorption
import org.junit.Test

/** I distretti (docs/luoghi.md §4.1): quando un luogo e' "un pezzo" di una citta' molto piu' grande e non deve competere. */
class DistrictAbsorptionTest {

  private val newYork = TestData.place(1, 40.7143, -74.0060, "New York", "US", pop = 8_804_190, cls = 1)
  private val manhattan = TestData.place(2, 40.7834, -73.9662, "Manhattan", "US", pop = 1_487_536, cls = 2)
  private val brooklyn = TestData.place(3, 40.6501, -73.9496, "Brooklyn", "US", pop = 2_736_074, cls = 2)

  private fun districts(vararg places: dev.pampa.mycloudgallery.core.places.dataset.PlaceRow): Set<Int> =
    DistrictAbsorption.districts(places.toList())

  @Test
  fun iBoroughDiNewYorkSonoDistretti() {
    assertThat(districts(newYork, manhattan, brooklyn)).containsExactly(2, 3)
  }

  @Test
  fun unaCittaDelloStessoPesoNonLoE() {
    // Meno di 3 volte la popolazione: due citta' vicine ma alla pari (Oakland e San Francisco) restano entrambe.
    val twin = TestData.place(4, 40.7834, -73.9662, "Gemella", "US", pop = 4_000_000, cls = 2)
    assertThat(districts(newYork, twin)).isEmpty()
  }

  @Test
  fun unComuneSatellitePiccoloNonLoE() {
    // Sotto i 150.000 abitanti non puo' competere con la grande citta': ci pensa la classifica pesata. Segrate resta Segrate.
    val milan = TestData.place(10, 45.4643, 9.1895, "Milano", "IT", pop = 1_371_498, cls = 2)
    val segrate = TestData.place(11, 45.4906, 9.2958, "Segrate", "IT", pop = 36_000, cls = 2)
    val sesto = TestData.place(12, 45.5342, 9.2339, "Sesto San Giovanni", "IT", pop = 80_000, cls = 2)
    val monza = TestData.place(13, 45.5845, 9.2744, "Monza", "IT", pop = 123_000, cls = 2)
    assertThat(districts(milan, segrate, sesto, monza)).isEmpty()
  }

  @Test
  fun serveLoStessoPaeseELaVicinanza() {
    val otherCountry = TestData.place(5, 40.7834, -73.9662, "Altrove", "CA", pop = 1_487_536, cls = 2)
    assertThat(districts(newYork, otherCountry)).isEmpty()
    // 40 km a nord: le aree d'influenza (8 km + 6 km) non si toccano.
    val far = TestData.place(6, 41.07, -74.0060, "Lontana", "US", pop = 1_487_536, cls = 2)
    assertThat(districts(newYork, far)).isEmpty()
  }

  @Test
  fun unLuogoMinoreOUnDistrettoNonSiConta() {
    val minor = TestData.place(7, 40.7834, -73.9662, "Minore", "US", pop = 1_487_536, cls = 0)
    assertThat(districts(newYork, minor)).isEmpty()
  }

  @Test
  fun ilRisultatoNonDipendeDallOrdine() {
    val a = DistrictAbsorption.districts(listOf(newYork, manhattan, brooklyn))
    val b = DistrictAbsorption.districts(listOf(brooklyn, manhattan, newYork))
    assertThat(a).isEqualTo(b)
  }
}
