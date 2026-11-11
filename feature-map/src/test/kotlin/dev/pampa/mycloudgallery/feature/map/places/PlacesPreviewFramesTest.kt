package dev.pampa.mycloudgallery.feature.map.places

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.GeoIndexState
import dev.pampa.mycloudgallery.core.map.contract.MapPoint
import dev.pampa.mycloudgallery.core.map.contract.MapPoints
import dev.pampa.mycloudgallery.core.map.contract.RebuildReason
import dev.pampa.mycloudgallery.core.map.testing.ReferenceClusterIndex
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.map.support.Cities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * I1 (fase I): l'anteprima della mappa dei Luoghi. Quale pezzo di mondo, dove cadono i luoghi, che cosa esce dal servizio (solo id e frazioni, mai una
 * coordinata) e che senza indice o senza carta non esce niente (le Raccolte tengono il mosaico).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlacesPreviewFramesTest {

  private val firenze = 3_176_959
  private val roma = 3_169_070
  private val oslo = 3_143_244

  private fun point(at: GeoE6, place: Int) = MapPoint(MediaId.random(), at.latE6, at.lonE6, day = 20_000, score = 0, placeId = place)

  @Test
  fun unLuogoSoloStaAlCentroAlloZoomMassimo() {
    val frame = PlacesPreviewFrames.frame(mapOf(firenze to Cities.FIRENZE), listOf(firenze), 328f, 164f)!!
    assertThat(frame.camera.zoom).isEqualTo(PlacesPreviewFrames.MAX_ZOOM)
    assertThat(frame.pins.single().x).isWithin(0.01f).of(0.5f)
    assertThat(frame.pins.single().y).isWithin(0.01f).of(0.5f)
  }

  @Test
  fun piuLuoghiStannoTuttiDentroConIlMargine() {
    val centers = mapOf(firenze to Cities.FIRENZE, roma to Cities.ROMA, oslo to Cities.OSLO)
    val frame = PlacesPreviewFrames.frame(centers, listOf(firenze, roma, oslo), 328f, 164f)!!
    assertThat(frame.pins.map { it.placeId }).containsExactly(firenze, roma, oslo).inOrder()
    val inset = PlacesPreviewFrames.INSET * 164f
    for (pin in frame.pins) {
      assertWithMessage("x di $pin").that(pin.x * 328f).isAtLeast(inset - 1f)
      assertWithMessage("x di $pin").that(pin.x * 328f).isAtMost(328f - inset + 1f)
      assertWithMessage("y di $pin").that(pin.y * 164f).isAtLeast(inset - 1f)
      assertWithMessage("y di $pin").that(pin.y * 164f).isAtMost(164f - inset + 1f)
    }
    // Oslo sta a nord di Firenze, Roma a sud: sulla carta, piu' in alto e piu' in basso.
    val y = frame.pins.associate { it.placeId to it.y }
    assertThat(y.getValue(oslo)).isLessThan(y.getValue(firenze))
    assertThat(y.getValue(roma)).isGreaterThan(y.getValue(firenze))
    assertThat(frame.camera.zoom).isAtLeast(PlacesPreviewFrames.MIN_ZOOM)
    assertThat(frame.camera.zoom).isLessThan(PlacesPreviewFrames.MAX_ZOOM)
  }

  @Test
  fun unLuogoSenzaCentroNonSiAppuntaEDaSoloNonDaUnaCarta() {
    assertThat(PlacesPreviewFrames.frame(emptyMap(), listOf(firenze), 328f, 164f)).isNull()
    val frame = PlacesPreviewFrames.frame(mapOf(roma to Cities.ROMA), listOf(firenze, roma), 328f, 164f)!!
    assertThat(frame.pins.map { it.placeId }).containsExactly(roma)
  }

  @Test
  fun ilCentroEUnaMediaPesataSulleFotoESoloDeiLuoghiChiesti() {
    val index = ReferenceClusterIndex(List(9) { point(Cities.FIRENZE, firenze) } + List(1) { point(Cities.ROMA, firenze) } + listOf(point(Cities.OSLO, oslo)))
    val centers = PlacesPreviewFrames.centers(index, setOf(firenze))
    assertThat(centers.keys).containsExactly(firenze)
    // Nove foto a Firenze e una a Roma con lo stesso luogo: il centro e' vicino a Firenze, non a meta' strada.
    val lat = centers.getValue(firenze).latE6
    assertThat(lat).isLessThan(Cities.FIRENZE.latE6)
    assertThat(Cities.FIRENZE.latE6 - lat).isLessThan((Cities.FIRENZE.latE6 - Cities.ROMA.latE6) / 4)
  }

  @Test
  fun nienteCoordinateNeiTesti() {
    val frame = PlacesPreviewFrames.frame(mapOf(firenze to Cities.FIRENZE), listOf(firenze), 328f, 164f)!!
    val text = frame.toString()
    assertThat(text).doesNotContain("${Cities.FIRENZE.latE6}")
    assertThat(text).doesNotContain("$firenze")
  }

  private class FakePoints(initial: GeoIndexState) : MapPoints {
    val state = MutableStateFlow(initial)
    override val index: StateFlow<GeoIndexState> get() = state
    override suspend fun rebuild(reason: RebuildReason) = Unit
  }

  @Test
  fun ilServizioAspettaLIndiceEDisegnaUnaVoltaSola() = runBlocking<Unit> {
    val points = FakePoints(GeoIndexState.Building)
    var drawn = 0
    val renderer = PlacesPreviewRenderer { _, w, h, _, _ ->
      drawn++
      Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }
    val service = PlacesPreviewService(points, renderer, Dispatchers.Unconfined)
    points.state.value = GeoIndexState.Ready(ReferenceClusterIndex(listOf(point(Cities.FIRENZE, firenze), point(Cities.ROMA, roma))), 0, 0)
    val first = service.preview(listOf(firenze, roma), 328, 164, 2f, dark = false).first()!!
    assertThat(first.pins.map { it.placeId }).containsExactly(firenze, roma).inOrder()
    // La stessa inquadratura un'altra volta: dalla memoria, senza ridisegnare.
    service.preview(listOf(firenze, roma), 328, 164, 2f, dark = false).first()
    assertThat(drawn).isEqualTo(1)
    service.clear()
    service.preview(listOf(firenze, roma), 328, 164, 2f, dark = false).first()
    assertThat(drawn).isEqualTo(2)
  }

  @Test
  fun senzaCartaOSenzaPuntiEsceNull() = runBlocking<Unit> {
    val empty = FakePoints(GeoIndexState.Failed)
    assertThat(PlacesPreviewService(empty, NoPlacesPreviewRenderer, Dispatchers.Unconfined).preview(listOf(firenze), 328, 164, 2f, false).first()).isNull()
    val ready = FakePoints(GeoIndexState.Ready(ReferenceClusterIndex(listOf(point(Cities.FIRENZE, firenze))), 0, 0))
    assertThat(PlacesPreviewService(ready, NoPlacesPreviewRenderer, Dispatchers.Unconfined).preview(listOf(firenze), 328, 164, 2f, false).take(1).toList())
      .containsExactly(null)
  }
}
