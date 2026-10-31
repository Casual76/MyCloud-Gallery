package dev.pampa.mycloudgallery.feature.map

import android.app.Application
import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.map.contract.MapMediaPositions
import dev.pampa.mycloudgallery.feature.map.mini.MiniMapLook
import dev.pampa.mycloudgallery.feature.map.mini.MiniMapRenderer
import dev.pampa.mycloudgallery.feature.map.mini.MiniMapService
import dev.pampa.mycloudgallery.feature.map.mini.MiniMapState
import dev.pampa.mycloudgallery.feature.map.mini.NoMiniMapRenderer
import dev.pampa.mycloudgallery.feature.map.support.Cities
import dev.pampa.mycloudgallery.feature.map.ui.FinalFilter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La mini-mappa delle info del viewer (Fase 9, P4; docs/fase9-progetto.md §1.5 `MiniMapState`, §2.10 "Mini-mappa del viewer", D29, D30, E-MAP7, E-MAP8, E-MAP16, E-H9): **mai per un media dei Nascosti**, nessuna
 * posizione = nessuna mini-mappa, le immagini restano solo in memoria (le ultime sei) e il disegno passa dal suo disegnatore con la larghezza chiesta e l'altezza che e' la meta' (320 x 160 dp). Lo stile **solo
 * offline** e' provato in `MapCameraAndStylesTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MiniMapServiceTest {

  private val look = MiniMapLook(dark = false, marker = 1, ring = 2, density = 2f)

  private class Recording : MiniMapRenderer {
    val calls = ArrayList<Triple<Int, Int, Boolean>>()

    override suspend fun render(point: GeoE6, widthPx: Int, heightPx: Int, look: MiniMapLook): Bitmap? {
      calls += Triple(widthPx, heightPx, look.dark)
      return Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    }
  }

  private val media = MediaId.random()
  private val positions = MapMediaPositions { if (it == media) Cities.FIRENZE else null }

  @Test
  fun unMediaConPosizioneHaLaMiniMappaDi320Per160_ELeImmaginiSiRicordano() = runTest {
    val renderer = Recording()
    val service = MiniMapService(positions, FinalFilter(FakeVisibility(present = setOf(media))), renderer) { look }
    val states = service.place(media, widthPx = 640).toList()
    assertThat(states.first()).isEqualTo(MiniMapState.Loading)
    assertThat((states.last() as MiniMapState.Ready).bitmap).isNotNull()
    assertThat(renderer.calls).containsExactly(Triple(640, 320, false))
    // La seconda volta (la stessa larghezza e lo stesso tema) viene dalla memoria: nessun nuovo disegno. Cambiando tema si ridisegna.
    service.place(media, 640).toList()
    assertThat(renderer.calls).hasSize(1)
    val darkService = MiniMapService(positions, FinalFilter(NothingHidden), renderer) { MiniMapLook(true, 1, 2, 2f) }
    darkService.place(media, 640).toList()
    assertThat(renderer.calls).hasSize(2)
    service.clear()
    service.place(media, 640).toList()
    assertThat(renderer.calls).hasSize(3)
  }

  @Test
  fun senzaPosizioneONascostoNonCeNessunaMiniMappa_ELaCartaNonSiDisegnaNemmeno() = runTest {
    val renderer = Recording()
    // Senza posizione (o coordinate scartate, E-MAP7).
    val other = MediaId.random()
    val plain = MiniMapService(positions, FinalFilter(FakeVisibility(present = setOf(media, other))), renderer) { look }
    assertThat(plain.place(other, 640).toList().last()).isEqualTo(MiniMapState.NoPosition)
    // Un media dei Nascosti: anche se qualcuno chiamasse la porta, il filtro finale dice "niente" (E-H9) e non si disegna niente.
    val hidden = MiniMapService(positions, FinalFilter(FakeVisibility(present = setOf(media), hidden = setOf(media))), renderer) { look }
    assertThat(hidden.place(media, 640).toList().last()).isEqualTo(MiniMapState.NoPosition)
    assertThat(renderer.calls).isEmpty()
  }

  @Test
  fun senzaDisegnatoreLaMiniMappaManca_ELaRigaResta() = runTest {
    val service = MiniMapService(positions, FinalFilter(NothingHidden), NoMiniMapRenderer) { look }
    val last = service.place(media, 320).toList().last() as MiniMapState.Ready
    assertThat(last.bitmap).isNull()
    assertThat(last.toString()).isEqualTo("MiniMapState.Ready(false)")
  }

  @Test
  fun laLarghezzaDeveEssereValida() = runTest {
    val service = MiniMapService(positions, FinalFilter(NothingHidden), NoMiniMapRenderer) { look }
    var failed = false
    try {
      service.place(media, 0).toList()
    } catch (_: IllegalArgumentException) {
      failed = true
    }
    assertThat(failed).isTrue()
    assertThat(look.toString()).isEqualTo("MiniMapLook(dark=false)")
  }
}
