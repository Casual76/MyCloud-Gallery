package dev.pampa.mycloudgallery.feature.map

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Place
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.GeoGrid
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.map.contract.MapBubble
import dev.pampa.mycloudgallery.feature.map.contract.MapScreenState
import dev.pampa.mycloudgallery.feature.map.contract.PlaceRow
import dev.pampa.mycloudgallery.feature.map.contract.PlacesSectionState
import dev.pampa.mycloudgallery.feature.map.map.MapInitial
import dev.pampa.mycloudgallery.feature.map.map.MapStyles
import dev.pampa.mycloudgallery.feature.map.map.MapSurfaceRequest
import dev.pampa.mycloudgallery.feature.map.map.MapUi
import dev.pampa.mycloudgallery.feature.map.map.MapViewport
import dev.pampa.mycloudgallery.feature.map.map.PlacedBubble
import dev.pampa.mycloudgallery.feature.map.nav.MapDestination
import dev.pampa.mycloudgallery.feature.map.nav.PlaceDestination
import dev.pampa.mycloudgallery.feature.map.nav.ZoneDestination
import dev.pampa.mycloudgallery.feature.map.places.MapSectionData
import dev.pampa.mycloudgallery.feature.map.places.MapSectionEntry
import dev.pampa.mycloudgallery.feature.map.support.Cities
import org.junit.Test

/**
 * Mai un luogo, una coordinata, una cella, un id o un nome in un `toString()` (Fase 9, P4; D33, E-MAP18): finirebbe in un rapporto o in un log, e dove si e' guardato dice dove si vive. Tutti i tipi che le schermate
 * della mappa aggiungono ai contratti di P0 (stato della pagina, bolle, camera, viewport, rotte, voci della sezione) dicono solo numeri e codici.
 */
class NoNamesInToStringTest {

  private val media = MediaId.random()
  private val cell = GeoGrid.cellOf(Cities.FIRENZE.latE6, Cities.FIRENZE.lonE6, 24)
  private val secrets = listOf("Firenze", "Casa", "43769", "11255", "Luogo di prova", media.value, "${cell.prefix}")

  private fun check(text: String) {
    for (secret in secrets) assertThat(text).doesNotContain(secret)
  }

  @Test
  fun lePosizioniEIlViewportNonDiconoDove() {
    check(Cities.FIRENZE.toString())
    check(CameraSpot(Cities.FIRENZE, 12.5).toString())
    check(MapViewport(Cities.FIRENZE, 9.0, dev.pampa.mycloudgallery.core.map.contract.GeoBox(0, 0, 1, 1), 412, 892).toString())
    check(MapInitial.FitAll(dev.pampa.mycloudgallery.core.map.contract.GeoBox(0, 0, 1, 1)).toString())
    check(MapInitial.Saved(CameraSpot(Cities.FIRENZE, 5.0)).toString())
  }

  @Test
  fun leBolleEIlLoroNomeNonSiVedono() {
    val cluster = PlacedBubble(MapBubble.Cluster(cell, 128, media, "Firenze"), Cities.FIRENZE)
    check(cluster.toString())
    check(PlacedBubble(MapBubble.Single(cell, media), Cities.FIRENZE).toString())
    check(MapBubble.Cluster(cell, 12, media, "Firenze").toString())
  }

  @Test
  fun lePaginaELeRotteNonDiconoNeLaZonaNeIlMedia() {
    val ui = MapUi(MapScreenState.Ready(MapOnline.On(true), null, 4), MapStyles.choose(MapOnline.Off, false, false), MapInitial.FitAll(dev.pampa.mycloudgallery.core.map.contract.GeoBox(0, 0, 1, 1)), listOf(PlacedBubble(MapBubble.Single(cell, media), Cities.FIRENZE)), Cities.FIRENZE)
    check(ui.toString())
    check(MapSurfaceRequest(ui.style, MapInitial.World, Cities.FIRENZE, 1, 2).toString())
    check(MapDestination(media.value).toString())
    check(ZoneDestination(cell.level, cell.prefix).toString())
    check(PlaceDestination(3_000_001).toString())
    assertThat(PlaceDestination(3_000_001).toString()).doesNotContain("3000001")
  }

  @Test
  fun leVociDelleRaccolteNonNominanoIlLuogo() {
    val entry = MapSectionEntry("place_3000001", "Firenze", "900 foto", Icons.Rounded.Place, FluidTone.Neutral)
    check(entry.toString())
    assertThat(entry.toString()).doesNotContain("3000001")
    check(MapSectionData("places", "Luoghi", listOf(entry)).toString())
    check(PlaceRow(3_000_001, "Firenze", 900).toString())
    check(PlacesSectionState.Ready(9_000, listOf(PlaceRow(3_000_001, "Firenze", 900)), 4).toString())
    assertThat(GeoE6(1, 2).toString()).isEqualTo("GeoE6")
  }
}
