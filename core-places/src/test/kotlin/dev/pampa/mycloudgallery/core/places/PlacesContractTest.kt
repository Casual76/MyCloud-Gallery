package dev.pampa.mycloudgallery.core.places

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.PlaceRef
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.places.contract.PlacePrecision
import dev.pampa.mycloudgallery.core.places.contract.PlacesAttribution
import dev.pampa.mycloudgallery.core.places.noop.NoPlaceNames
import dev.pampa.mycloudgallery.core.places.noop.NoReverseGeocoder
import dev.pampa.mycloudgallery.core.places.noop.NoTimeZoneLookup
import dev.pampa.mycloudgallery.core.places.testing.FakePlaceData
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import java.time.ZoneId
import org.junit.Test

class PlacesContractTest {

  private val places = FakePlaces()

  @Test
  fun ilLuogoPiuVicinoEIlSuoFuso() {
    // Piazza della Signoria: a Firenze, entro 15 km.
    val florence = places.nearest(GeoPoint(43.7696, 11.2558))
    assertThat(florence?.place?.name).isEqualTo("Firenze")
    assertThat(florence?.precision).isEqualTo(PlacePrecision.CITY)
    assertThat(places.zoneAt(GeoPoint(59.91, 10.75))).isEqualTo(ZoneId.of("Europe/Oslo"))
    // In mezzo all'Atlantico non c'e' un luogo: nessun nome e nessun fuso inventati.
    assertThat(places.nearest(GeoPoint(30.0, -40.0))).isNull()
    assertThat(places.zoneAt(GeoPoint(30.0, -40.0))).isNull()
  }

  @Test
  fun ilRiassuntoDelleIntestazioni() {
    val firenze = FakePlaceData.FIRENZE.place.id.value
    val fiesole = FakePlaceData.FIESOLE.place.id.value
    val milano = FakePlaceData.MILANO.place.id.value
    assertThat(places.summary(intArrayOf(firenze), 1)).isEqualTo("Firenze")
    assertThat(places.summary(intArrayOf(firenze, fiesole), 2)).isEqualTo("Firenze e Fiesole")
    assertThat(places.summary(intArrayOf(firenze, fiesole, milano), 3)).isEqualTo("Firenze e altri 2 luoghi")
    assertThat(places.summary(intArrayOf(), 0)).isEmpty()
    assertThat(places.label(PlaceRef(firenze, places.dataset))).isEqualTo("Firenze")
    assertThat(places.labelOfId(999_999)).isNull()
  }

  @Test
  fun ilMiniDatasetHaIdUniciEFusiVeri() {
    val ids = FakePlaceData.ALL.map { it.place.id.value }
    assertThat(ids.toSet()).hasSize(ids.size)
    assertThat(FakePlaceData.IDS.toList()).isEqualTo(ids)
    // Ogni fuso del mini-dataset e' un fuso che Java conosce (la Libreria sintetica li usa per i viaggi, E-M14).
    FakePlaceData.ALL.forEach { assertThat(ZoneId.of(it.place.zone!!)).isNotNull() }
  }

  @Test
  fun iNullObjectFallisconoChiusi() {
    assertThat(NoReverseGeocoder.nearest(GeoPoint(43.77, 11.25))).isNull()
    assertThat(NoReverseGeocoder.dataset).isEqualTo(0)
    assertThat(NoTimeZoneLookup.zoneAt(GeoPoint(43.77, 11.25))).isNull()
    assertThat(NoPlaceNames.summary(intArrayOf(1, 2), 2)).isEmpty()
    assertThat(NoPlaceNames.label(PlaceRef(3176959, 1))).isNull()
  }

  @Test
  fun lAttribuzioneDiGeoNamesDiceLicenzaEModifica() {
    assertThat(PlacesAttribution.TEXT).contains("GeoNames")
    assertThat(PlacesAttribution.TEXT).contains("4.0")
    assertThat(PlacesAttribution.TEXT).contains("modificati")
    assertThat(PlacesAttribution.LICENSE_URL).startsWith("https://creativecommons.org/licenses/by/4.0")
  }
}
