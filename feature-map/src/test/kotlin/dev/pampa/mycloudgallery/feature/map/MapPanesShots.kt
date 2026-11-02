package dev.pampa.mycloudgallery.feature.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryPaneRules
import dev.pampa.mycloudgallery.core.designsystem.adaptive.GalleryPanes
import dev.pampa.mycloudgallery.core.designsystem.adaptive.rememberGalleryWindow
import dev.pampa.mycloudgallery.core.library.index.IndexOrderedTimelineView
import dev.pampa.mycloudgallery.core.map.contract.CameraSpot
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.feature.map.contract.MapBubble
import dev.pampa.mycloudgallery.feature.map.contract.MapScreenState
import dev.pampa.mycloudgallery.feature.map.contract.ZoneGridState
import dev.pampa.mycloudgallery.feature.map.grid.NoZoneGridHandlers
import dev.pampa.mycloudgallery.feature.map.grid.ZoneGridScreen
import dev.pampa.mycloudgallery.feature.map.map.MapInitial
import dev.pampa.mycloudgallery.feature.map.map.MapPanesLayout
import dev.pampa.mycloudgallery.feature.map.map.MapScreen
import dev.pampa.mycloudgallery.feature.map.map.MapStyles
import dev.pampa.mycloudgallery.feature.map.map.MapUi
import dev.pampa.mycloudgallery.feature.map.map.NoMapHandlers
import dev.pampa.mycloudgallery.feature.map.map.PlacedBubble
import dev.pampa.mycloudgallery.feature.map.support.MapRig
import dev.pampa.mycloudgallery.feature.map.testing.FakeMapSurface
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Gli screenshot dei pannelli della mappa (Fase 10, P5, D13 (2), §2.3) alle quattro larghezze del riquadro, chiaro e scuro: la mappa intera (zona chiusa) e, da 840 dp, la mappa al 60% con la griglia della zona al 40%.
 * Sotto 840 dp la zona e' una pagina (la sua pagina c'e' gia' negli screenshot della Fase 9): qui resta la mappa intera. **MapLibre non si disegna in Robolectric**: la carta e' quella finta (vedi `MapShots`).
 * Nessun controllo si sovrappone a un altro e i bersagli sono da 48 dp.
 * `gradlew :feature-map:recordRoborazziDebug --tests "*MapPanesShots*"` scrive i PNG in `docs/screenshots/10-P5/`.
 */
internal abstract class MapPanesShots(private val width: Int) : MapScreenTest() {

  private val rig = MapRig()
  private val thumbs get() = images.thumbnails
  private val center = GeoE6(43_000_000, 12_000_000)
  private val on = MapOnline.On(byDefault = true)

  private fun at(dLat: Double, dLon: Double) = GeoE6(center.latE6 + (dLat * 1_000_000).toInt(), center.lonE6 + (dLon * 1_000_000).toInt())

  private fun bubbles(): List<PlacedBubble> = listOf(
    PlacedBubble(MapBubble.Cluster(rig.cellOf(0, 20), 128, rig.ids[0], "Firenze"), at(0.0, 0.0)),
    PlacedBubble(MapBubble.Cluster(rig.cellOf(1, 20), 12, rig.ids[1], null), at(0.9, 1.0)),
    PlacedBubble(MapBubble.Cluster(rig.cellOf(2, 20), 1_240, rig.ids[2], "Roma"), at(-1.2, 0.9)),
    PlacedBubble(MapBubble.Single(rig.cellOf(3, 20), rig.ids[3]), at(1.2, -0.9)),
  )

  private fun scene(dark: Boolean, zoneOpen: Boolean): @Composable () -> Unit = {
    val window = rememberGalleryWindow()
    val panes = GalleryPaneRules.mapZone(window, zoneOpen)
    val split = (panes as? GalleryPanes.Docked)?.let { GalleryPaneRules.split(window, it) }
    val ui = MapUi(MapScreenState.Ready(on, null, 1_240), MapStyles.choose(on, false, dark), MapInitial.Saved(CameraSpot(center, 6.0)), bubbles())
    MapPanesLayout(
      panes = panes,
      split = split,
      map = { modifier ->
        val mapWidth = (split?.other ?: window).widthDp
        val surface = FakeMapSurface(center, zoom = 6.0, widthDp = mapWidth, heightDp = window.heightDp, density = 2f)
        MapScreen(ui, surface, surface.content(), thumbs, rig.snapshot, NoMapHandlers, modifier)
      },
      zone = { modifier ->
        ZoneGridScreen(ZoneGridState.Ready("Firenze", IndexOrderedTimelineView(rig.snapshot, IntArray(40) { it })), thumbs, NoZoneGridHandlers, modifier)
      },
    )
  }

  private fun shoot(zoneOpen: Boolean, dark: Boolean) {
    val name = if (zoneOpen) "mappa-zona-aperta" else "mappa-zona-chiusa"
    showAll(dark = dark, content = scene(dark, zoneOpen))
    assertNoOverlappingControls("$name $width dp")
    assertTouchTargets("$name $width dp")
    composeRule.onRoot().captureRoboImage("../docs/screenshots/10-P5/$name-${width}dp-${if (dark) "scuro" else "chiaro"}.png")
  }

  @Test fun chiusaChiaro() = shoot(zoneOpen = false, dark = false)

  @Test fun chiusaScuro() = shoot(zoneOpen = false, dark = true)

  @Test fun apertaChiaro() = shoot(zoneOpen = true, dark = false)

  @Test fun apertaScuro() = shoot(zoneOpen = true, dark = true)
}

@Config(qualifiers = "w360dp-h800dp-xhdpi")
internal class MapPanesShots360 : MapPanesShots(360)

@Config(qualifiers = "w600dp-h900dp-xhdpi")
internal class MapPanesShots600 : MapPanesShots(600)

@Config(qualifiers = "w840dp-h900dp-xhdpi")
internal class MapPanesShots840 : MapPanesShots(840)

@Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
internal class MapPanesShots1280 : MapPanesShots(1280)
