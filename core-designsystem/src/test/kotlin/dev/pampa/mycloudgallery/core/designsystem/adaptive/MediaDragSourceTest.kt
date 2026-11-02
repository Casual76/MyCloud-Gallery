package dev.pampa.mycloudgallery.core.designsystem.adaptive

import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 10 (P6, D20, E-T17): il gesto del trascinamento verso un'altra app, **senza il sistema che trascina** (il sistema lo fa un'interfaccia, `MediaDragStarter`, che qui registra).
 * Col tocco parte con la pressione lunga e con il dito fermo; un dito che si muove prima e' uno scorrimento; il mouse parte trascinando subito (e non col clic destro); la sorgente decide
 * **al tocco** cosa si trascina; se la preparazione dura il trascinamento parte quando e' pronta, a dito ancora giu'; un dito alzato prima la annulla e non parte niente; se il sistema
 * rifiuta il gesto le copie si tolgono. Senza porta, o disabilitato, il modificatore non fa niente (fallire chiusi).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
@OptIn(ExperimentalTestApi::class)
class MediaDragSourceTest {

  @get:Rule val compose = createComposeRule()

  @After
  fun tearDown() = MediaDragPorts.reset()

  private class RecordingPort : MediaDragPort {
    val requests = mutableListOf<Pair<String, List<String>>>()
    var cancelled = 0
    var wait: CompletableDeferred<Unit>? = null
    var refuse = false
    var discards = 0

    override suspend fun prepare(origin: String, ids: List<String>): MediaDrag? {
      requests += origin to ids
      try {
        wait?.await()
      } catch (e: CancellationException) {
        cancelled++
        throw e
      }
      if (refuse) return null
      val clip = ClipData(ClipDescription("", arrayOf("image/jpeg")), ClipData.Item(Uri.parse("content://x.documents/document/aaaaaaaaaaaaaaaa")))
      return MediaDrag(clip, 257, ids.size) { discards++ }
    }
  }

  private class RecordingStarter(var accept: Boolean = true) : MediaDragStarter {
    val started = mutableListOf<Pair<MediaDrag, MediaDragShadow>>()

    override fun start(drag: MediaDrag, shadow: MediaDragShadow): Boolean {
      started += drag to shadow
      return accept
    }
  }

  private val port = RecordingPort()
  private val starter = RecordingStarter()
  private val touches = mutableListOf<DragTouch>()
  private var subject: (DragTouch) -> DragSubject? = { DragSubject(listOf("m-1", "m-2")) }

  private fun show(enabled: Boolean = true, withPort: Boolean = true) {
    compose.setContent {
      CompositionLocalProvider(
        LocalMediaDragPort provides (if (withPort) port else null),
        LocalMediaDragStarter provides starter,
      ) {
        MyCloudGalleryTheme {
          Box(Modifier.size(120.dp).testTag("cella").mediaDragSource(enabled, "library") { touch ->
            touches += touch
            subject(touch)
          })
        }
      }
    }
  }

  private fun longPressMs(): Long = 500L

  @Test
  fun colTocco_laPressioneLungaPreparaEFaPartireIlTrascinamento() {
    show()

    compose.onNodeWithTag("cella").performTouchInput { down(center) }
    compose.mainClock.advanceTimeBy(longPressMs())
    compose.waitForIdle()

    assertThat(port.requests).containsExactly("library" to listOf("m-1", "m-2"))
    val (drag, shadow) = starter.started.single()
    assertThat(drag.count).isEqualTo(2)
    assertThat(drag.flags).isEqualTo(257)
    assertThat(shadow.count).isEqualTo(2)
    // La sorgente e' stata chiesta al tocco, col dito (non con il mouse).
    assertThat(touches.first().pointer).isEqualTo(DragPointer.TOUCH)
    assertThat(port.discards).isEqualTo(0)
    compose.onNodeWithTag("cella").performTouchInput { up() }
  }

  @Test
  fun unDitoCheSiMuoveOCheSiAlzaPrimaDellaPressioneLungaNonTrascina() {
    show()

    // Alzato prima del tempo: e' un tocco.
    compose.onNodeWithTag("cella").performTouchInput { down(center) }
    compose.mainClock.advanceTimeBy(100)
    compose.onNodeWithTag("cella").performTouchInput { up() }
    compose.mainClock.advanceTimeBy(longPressMs())
    // Mosso oltre la soglia: e' uno scorrimento.
    compose.onNodeWithTag("cella").performTouchInput { down(center) }
    compose.onNodeWithTag("cella").performTouchInput { moveBy(Offset(0f, 200f)) }
    compose.mainClock.advanceTimeBy(longPressMs())
    compose.onNodeWithTag("cella").performTouchInput { up() }
    compose.waitForIdle()

    assertThat(port.requests).isEmpty()
    assertThat(starter.started).isEmpty()
  }

  @Test
  fun seLaSorgenteNonHaNienteDaTrascinareLaPortaNonSiChiama() {
    subject = { null }
    show()

    compose.onNodeWithTag("cella").performTouchInput { down(center) }
    compose.mainClock.advanceTimeBy(longPressMs())
    compose.waitForIdle()
    compose.onNodeWithTag("cella").performTouchInput { up() }

    assertThat(touches).isNotEmpty()
    assertThat(port.requests).isEmpty()
    assertThat(starter.started).isEmpty()

    subject = { DragSubject(emptyList()) }
    compose.onNodeWithTag("cella").performTouchInput { down(center) }
    compose.mainClock.advanceTimeBy(longPressMs())
    compose.onNodeWithTag("cella").performTouchInput { up() }
    assertThat(port.requests).isEmpty()
  }

  @Test
  fun seLaPreparazioneDuraIlTrascinamentoPartiQuandoEProntaADitoGiu() {
    port.wait = CompletableDeferred()
    show()

    compose.onNodeWithTag("cella").performTouchInput { down(center) }
    compose.mainClock.advanceTimeBy(longPressMs())
    compose.waitForIdle()
    // La porta sta preparando, il sistema non ha ancora cominciato niente.
    assertThat(port.requests).hasSize(1)
    assertThat(starter.started).isEmpty()

    port.wait!!.complete(Unit)
    compose.waitForIdle()

    assertThat(starter.started).hasSize(1)
    assertThat(port.cancelled).isEqualTo(0)
    compose.onNodeWithTag("cella").performTouchInput { up() }
  }

  @Test
  fun unDitoAlzatoMentreSiPreparaAnnullaLaPreparazioneENonParteNiente_E_T17() {
    port.wait = CompletableDeferred()
    show()

    compose.onNodeWithTag("cella").performTouchInput { down(center) }
    compose.mainClock.advanceTimeBy(longPressMs())
    compose.waitForIdle()
    assertThat(port.requests).hasSize(1)

    compose.onNodeWithTag("cella").performTouchInput { up() }
    compose.waitForIdle()

    assertThat(port.cancelled).isEqualTo(1)
    // Anche se poi arrivasse, non parte niente.
    port.wait!!.complete(Unit)
    compose.waitForIdle()
    assertThat(starter.started).isEmpty()
  }

  @Test
  fun seLaPortaDiceNoNonParteNienteESeIlSistemaRifiutaIlGestoLeCopieSiTolgono() {
    show()
    port.refuse = true
    compose.onNodeWithTag("cella").performTouchInput { down(center) }
    compose.mainClock.advanceTimeBy(longPressMs())
    compose.waitForIdle()
    compose.onNodeWithTag("cella").performTouchInput { up() }
    assertThat(starter.started).isEmpty()

    // Il sistema non accetta il gesto: il prestito si rilascia subito, una volta.
    port.refuse = false
    starter.accept = false
    compose.onNodeWithTag("cella").performTouchInput { down(center) }
    compose.mainClock.advanceTimeBy(longPressMs())
    compose.waitForIdle()
    compose.onNodeWithTag("cella").performTouchInput { up() }
    assertThat(starter.started).hasSize(1)
    assertThat(port.discards).isEqualTo(1)
  }

  @Test
  fun ilMouseParteTrascinandoSubito_NonColClicDestro() {
    show()

    compose.onNodeWithTag("cella").performMouseInput { moveTo(center); press(MouseButton.Secondary); moveBy(Offset(60f, 0f)); release(MouseButton.Secondary) }
    compose.waitForIdle()
    assertThat(port.requests).isEmpty()

    compose.onNodeWithTag("cella").performMouseInput { moveTo(center); press(MouseButton.Primary); moveBy(Offset(60f, 0f)) }
    compose.waitForIdle()

    assertThat(touches.last().pointer).isEqualTo(DragPointer.MOUSE)
    assertThat(port.requests).hasSize(1)
    assertThat(starter.started).hasSize(1)
    compose.onNodeWithTag("cella").performMouseInput { release(MouseButton.Primary) }
  }

  @Test
  fun senzaPortaODisabilitatoNonFaNiente() {
    show(enabled = false)
    compose.onNodeWithTag("cella").performTouchInput { down(center) }
    compose.mainClock.advanceTimeBy(longPressMs())
    compose.onNodeWithTag("cella").performTouchInput { up() }
    assertThat(touches).isEmpty()
    assertThat(port.requests).isEmpty()
  }

  @Test
  fun senzaPortaInstallataNiente_ConLaPortaDelProcessoSi() {
    show(withPort = false)
    compose.onNodeWithTag("cella").performTouchInput { down(center) }
    compose.mainClock.advanceTimeBy(longPressMs())
    compose.waitForIdle()
    compose.onNodeWithTag("cella").performTouchInput { up() }
    assertThat(touches).isEmpty()
    assertThat(starter.started).isEmpty()
  }

  @Test
  fun laPortaDelProcessoValeQuandoLaComposizioneNonNeHa() {
    MediaDragPorts.install(port)
    compose.setContent {
      CompositionLocalProvider(LocalMediaDragStarter provides starter) {
        MyCloudGalleryTheme {
          Box(Modifier.size(120.dp).testTag("cella").mediaDragSource(true, "viewer") { DragSubject(listOf("m-9")) })
        }
      }
    }
    compose.onNodeWithTag("cella").performTouchInput { down(center) }
    compose.mainClock.advanceTimeBy(longPressMs())
    compose.waitForIdle()
    compose.onNodeWithTag("cella").performTouchInput { up() }

    assertThat(port.requests).containsExactly("viewer" to listOf("m-9"))
    assertThat(starter.started).hasSize(1)
  }
}
