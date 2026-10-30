package dev.pampa.mycloudgallery.core.places

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.places.contract.PlacesAttribution
import org.junit.Test

/**
 * L'attribuzione si estende alla mappa (Fase 9, P2; docs/fase9-progetto.md §2.7): le righe brevi e i testi completi di OpenStreetMap, OpenMapTiles,
 * OpenFreeMap, Natural Earth, dello stile e dei caratteri. Quella di GeoNames della Fase 3 resta com'era (la controlla `PlacesContractTest`).
 */
class PlacesAttributionMapTest {
  @Test
  fun leDueRigheBreviSonoQuelleDelPiano() {
    assertThat(PlacesAttribution.MAP_ONLINE_SHORT).isEqualTo("© OpenStreetMap contributors · OpenMapTiles · OpenFreeMap")
    assertThat(PlacesAttribution.MAP_BASE_SHORT).isEqualTo("Natural Earth · GeoNames")
  }

  @Test
  fun iTestiCompletiNominanoLeLicenzeDiOgniPezzo() {
    assertThat(PlacesAttribution.MAP_ONLINE_TEXT).contains("ODbL 1.0")
    assertThat(PlacesAttribution.MAP_ONLINE_TEXT).contains("© OpenStreetMap contributors")
    assertThat(PlacesAttribution.MAP_BASE_TEXT).contains("pubblico dominio")
    assertThat(PlacesAttribution.MAP_BASE_TEXT).contains("Creative Commons Attribuzione 4.0")
    assertThat(PlacesAttribution.MAP_BASE_TEXT).contains("ridotti")
    assertThat(PlacesAttribution.MAP_STYLE_TEXT).contains("BSD 3-Clause")
    assertThat(PlacesAttribution.MAP_STYLE_TEXT).contains("SIL Open Font License 1.1")
    assertThat(PlacesAttribution.MAP_ODBL_URL).startsWith("https://opendatacommons.org/licenses/odbl/")
  }

  @Test
  fun lAttribuzioneDeiLuoghiDellaFase3NonCambia() {
    assertThat(PlacesAttribution.TEXT).isEqualTo(
      "Nomi e fusi orari dei luoghi: dati di GeoNames (geonames.org), licenza Creative Commons Attribuzione 4.0. " +
        "I dati sono stati modificati: ridotti, in parte tradotti in italiano e convertiti in un formato compatto.",
    )
  }
}
