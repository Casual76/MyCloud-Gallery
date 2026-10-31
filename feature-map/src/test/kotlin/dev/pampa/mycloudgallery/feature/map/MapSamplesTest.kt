package dev.pampa.mycloudgallery.feature.map

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.map.contract.MapBubble
import dev.pampa.mycloudgallery.feature.map.contract.MapNotice
import dev.pampa.mycloudgallery.feature.map.contract.MapPrivacyState
import dev.pampa.mycloudgallery.feature.map.contract.MapScreenState
import dev.pampa.mycloudgallery.feature.map.contract.PlaceRow
import dev.pampa.mycloudgallery.feature.map.contract.PlacesSectionState
import dev.pampa.mycloudgallery.feature.map.contract.RegionsState
import dev.pampa.mycloudgallery.feature.map.contract.ZoneGridState
import dev.pampa.mycloudgallery.feature.map.testing.MapSamples
import org.junit.Assert.assertThrows
import org.junit.Test

/** Fase 9 (P0): ogni caso degli stati del §1.5 della mappa ha un esempio (P4 e P5 partono da qui), e le regole degli stati tengono. */
class MapSamplesTest {

  private fun caseOf(state: MapScreenState): String = when (state) {
    MapScreenState.Loading -> "loading"
    is MapScreenState.Ready -> "ready"
    is MapScreenState.Empty -> "empty"
    MapScreenState.Failed -> "failed"
  }

  private fun caseOf(state: MapBubble): String = when (state) {
    is MapBubble.Cluster -> "cluster"
    is MapBubble.Single -> "single"
  }

  private fun caseOf(state: ZoneGridState): String = when (state) {
    is ZoneGridState.Ready -> "ready"
    ZoneGridState.Empty -> "empty"
    ZoneGridState.Failed -> "failed"
  }

  private fun caseOf(state: PlacesSectionState): String = when (state) {
    is PlacesSectionState.Ready -> "ready"
    is PlacesSectionState.NoGps -> "no_gps"
    PlacesSectionState.Failed -> "failed"
  }

  private fun caseOf(state: MapPrivacyState): String = when (state) {
    is MapPrivacyState.Shown -> "shown"
    MapPrivacyState.Unavailable -> "unavailable"
  }

  private fun caseOf(state: RegionsState): String = when (state) {
    is RegionsState.Catalog -> "catalog"
    is RegionsState.Estimate -> "estimate"
    is RegionsState.Downloading -> "downloading"
    is RegionsState.Partial -> "partial"
    is RegionsState.Ready -> "ready"
    is RegionsState.NeedsUpdate -> "needs_update"
  }

  @Test
  fun ogniCasoHaUnEsempio() {
    assertThat(MapSamples.screens.map { caseOf(it) }.toSet()).containsExactly("loading", "ready", "empty")
    assertThat(MapSamples.screens.filterIsInstance<MapScreenState.Ready>().mapNotNull { it.notice }.toSet()).containsExactlyElementsIn(MapNotice.entries)
    assertThat(MapSamples.bubbles.map { caseOf(it) }.toSet()).containsExactly("cluster", "single")
    assertThat(MapSamples.grids.map { caseOf(it) }.toSet()).containsExactly("ready", "empty")
    assertThat(MapSamples.places.map { caseOf(it) }.toSet()).containsExactly("ready", "no_gps")
    assertThat(MapSamples.privacy.map { caseOf(it) }.toSet()).containsExactly("shown", "unavailable")
    assertThat(MapSamples.regions.map { caseOf(it) }.toSet()).containsExactly("catalog", "estimate", "downloading", "partial", "ready", "needs_update")
  }

  /** D30: al piu' 8 luoghi; D29: mai la parola "casa" negli esempi; D33: nessun nome di luogo nei `toString()`. */
  @Test
  fun leRegoleDegliStati() {
    assertThrows(IllegalArgumentException::class.java) { PlacesSectionState.Ready(1, List(9) { PlaceRow(it, "x", 1) }, 0) }
    val texts = (MapSamples.screens + MapSamples.bubbles + MapSamples.grids + MapSamples.places + MapSamples.privacy + MapSamples.regions).map { it.toString() }
    for (text in texts) {
      assertThat(text.lowercase()).doesNotContain("casa")
      assertThat(text).doesNotContain("Luogo di prova")
    }
  }
}
