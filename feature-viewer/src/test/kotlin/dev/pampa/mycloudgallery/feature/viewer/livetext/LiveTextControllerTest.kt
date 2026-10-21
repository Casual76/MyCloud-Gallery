package dev.pampa.mycloudgallery.feature.viewer.livetext

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.viewer.ViewerMessage
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.feature.viewer.gesture.ViewerSubjectPress
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeViewerTextReader
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeViewerTextSource
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Il controller del testo dal vivo (Fase 6, P12; D41, D42, D43, D45, D46; E-V9, E-V13, E-H1): la logica che sta sotto i gesti, provata senza
 * schermo. Qui si fissano le regole della lettura (spenta = mai una chiamata, il fake fallisce), della copia (appunti sensibili) e della
 * selezione (non sopravvive al cambio di media).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LiveTextControllerTest {

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val a = MediaId("00000000-0000-4000-8000-00000000000a")
  private val b = MediaId("00000000-0000-4000-8000-00000000000b")
  private val aspect = LiveTextTestLines.ASPECT
  private val layout = LiveTextTestLines.multiline

  private inner class Rig(
    scope: CoroutineScope,
    val source: FakeViewerTextSource? = null,
    val reader: FakeViewerTextReader? = null,
    val subject: ViewerSubjectPress? = null,
    val kind: MediaKind = MediaKind.PHOTO,
  ) {
    var current: MediaId = a
    val messages = mutableListOf<ViewerMessage>()
    private val services = ViewerServices(textSource = source, textReader = reader, subjectPress = subject)
    val controller = LiveTextController(
      scope = scope,
      context = app,
      host = LiveTextHost(
        services = { services },
        refOf = { MediaRef.Synthetic(seed = 1, width = 1600, height = 900) },
        kindOf = { kind },
        currentId = { current },
        announce = { messages += it },
      ),
    )
  }

  private fun wordPoint(text: ViewerTextLayout, line: Int, word: Int): Offset {
    val quad = LiveTextGeometry(text, aspect).quad(line, word, word)
    return Offset(quad.map { it.x }.average().toFloat(), quad.map { it.y }.average().toFloat())
  }

  private fun clip(): android.content.ClipData? = app.getSystemService(ClipboardManager::class.java).primaryClip

  private fun TestScope.press(rig: Rig, point: Offset) {
    rig.controller.onLongPress(rig.current, aspect, point, tolerance = 0.02f)
    runCurrent()
  }

  // --- Il testo dell'indice e il cambio di media ------------------------------------------------------------------------------

  @Test
  fun bindLeggeIlTestoDellIndiceECambiandoMediaLaSelezioneSiChiude() = runTest {
    val rig = Rig(this, source = FakeViewerTextSource(mutableMapOf(a to layout)))
    rig.controller.bind(a)
    assertThat(rig.controller.hasText(a)).isTrue()
    press(rig, wordPoint(layout, 0, 1))
    assertThat(rig.controller.state).isInstanceOf(LiveTextState.Actions::class.java)
    assertThat(rig.controller.active).isTrue()

    rig.current = b
    rig.controller.bind(b)
    assertThat(rig.controller.state).isEqualTo(LiveTextState.Idle)
    assertThat(rig.controller.owner).isNull()
    assertThat(rig.controller.active).isFalse()
  }

  @Test
  fun e_H1_ilTestoDiUnMediaNonEMaiQuelloDiUnAltro() = runTest {
    val rig = Rig(this, source = FakeViewerTextSource(mutableMapOf(a to layout)))
    rig.controller.bind(a)
    rig.controller.bind(b)
    assertThat(rig.controller.hasText(b)).isFalse()
    assertThat(rig.controller.layoutOf(b)).isNull()
    assertThat(rig.controller.geometry(b, aspect)).isNull()
    assertThat(rig.controller.canOpenSheet(b)).isFalse()
  }

  @Test
  fun unMediaSenzaTestoNonHaIlPulsanteEUnoAnalizzatoSenzaTestoNeppure() = runTest {
    val rig = Rig(this, source = FakeViewerTextSource(mutableMapOf(a to ViewerTextLayout(emptyList()))))
    rig.controller.bind(a)
    assertThat(rig.controller.hasText(a)).isFalse()
    rig.controller.toggleHighlight(a)
    assertThat(rig.controller.state).isEqualTo(LiveTextState.Idle)
  }

  // --- La lettura (D41, D42, E-V9) ----------------------------------------------------------------------------------------------

  @Test
  fun lettura_spenta_nessunaChiamataAllOcr_ilFakeFallirebbeSeChiamato() = runTest {
    val reader = FakeViewerTextReader(available = false, result = layout)
    val pressed = mutableListOf<Offset>()
    val rig = Rig(this, source = FakeViewerTextSource(), reader = reader, subject = { _, x, y -> pressed += Offset(x, y) })
    rig.controller.bind(a)
    press(rig, Offset(0.5f, 0.5f))
    assertThat(reader.reads).isEqualTo(0)
    assertThat(rig.controller.state).isEqualTo(LiveTextState.Idle)
    // E-V9: senza testo letto e con la lettura spenta la pressione va al ritaglio.
    assertThat(pressed).containsExactly(Offset(0.5f, 0.5f))
    // E anche l'azione di TalkBack non legge: dice che non c'e' testo.
    rig.controller.openSheet(a)
    runCurrent()
    assertThat(reader.reads).isEqualTo(0)
    assertThat(rig.messages).containsExactly(ViewerMessage.NoText)
  }

  @Test
  fun lettura_spenta_ilTestoLettoDaUnAltroDispositivoVale() = runTest {
    val reader = FakeViewerTextReader(available = false)
    val rig = Rig(this, source = FakeViewerTextSource(mutableMapOf(a to layout)), reader = reader)
    rig.controller.bind(a)
    press(rig, wordPoint(layout, 1, 2))
    assertThat(rig.controller.selection).isEqualTo(TextSelection(TextPosition(1, 2), TextPosition(1, 2)))
    assertThat(reader.reads).isEqualTo(0)
  }

  @Test
  fun lettura_attiva_alVoloUnaVoltaSolaEPoiDallaMemoria() = runTest {
    val reader = FakeViewerTextReader(available = true, result = layout)
    val rig = Rig(this, source = FakeViewerTextSource(), reader = reader)
    rig.controller.bind(a)
    assertThat(rig.controller.hasText(a)).isFalse()
    assertThat(rig.controller.canOpenSheet(a)).isTrue()
    press(rig, wordPoint(layout, 0, 0))
    assertThat(reader.reads).isEqualTo(1)
    assertThat(rig.controller.selection?.from).isEqualTo(TextPosition(0, 0))
    assertThat(rig.controller.hasText(a)).isTrue()
    rig.controller.dismiss()
    press(rig, wordPoint(layout, 2, 0))
    assertThat(reader.reads).isEqualTo(1)
    assertThat(rig.controller.selection?.from).isEqualTo(TextPosition(2, 0))
  }

  @Test
  fun lettura_attiva_unRisultatoSenzaTestoNonSiRilegge_eLaPressioneVaAlRitaglio() = runTest {
    val reader = FakeViewerTextReader(available = true, result = ViewerTextLayout(emptyList()))
    val pressed = mutableListOf<Offset>()
    val rig = Rig(this, source = FakeViewerTextSource(), reader = reader, subject = { _, x, y -> pressed += Offset(x, y) })
    rig.controller.bind(a)
    press(rig, Offset(0.3f, 0.3f))
    press(rig, Offset(0.6f, 0.6f))
    assertThat(reader.reads).isEqualTo(1)
    assertThat(pressed).containsExactly(Offset(0.3f, 0.3f), Offset(0.6f, 0.6f)).inOrder()
    assertThat(rig.controller.state).isEqualTo(LiveTextState.Idle)
  }

  @Test
  fun lettura_attiva_oltreDueSecondiNientTestoELaPressioneVaAlRitaglio() = runTest {
    val slow = object : ViewerTextReader {
      override val available = true
      override suspend fun readNow(id: MediaId, ref: MediaRef): ViewerTextLayout? {
        delay(10_000)
        return layout
      }
    }
    val pressed = mutableListOf<Offset>()
    val services = ViewerServices(textSource = FakeViewerTextSource(), textReader = slow, subjectPress = { _, x, y -> pressed += Offset(x, y) })
    val controller = LiveTextController(
      this, app,
      LiveTextHost({ services }, { MediaRef.Synthetic(1, 100, 100) }, { MediaKind.PHOTO }, { a }, { }),
    )
    controller.onLongPress(a, aspect, Offset(0.4f, 0.4f), 0.02f)
    runCurrent()
    assertThat(controller.state).isEqualTo(LiveTextState.ReadingNow)
    advanceTimeBy(1_900)
    assertThat(controller.state).isEqualTo(LiveTextState.ReadingNow)
    advanceTimeBy(200)
    runCurrent()
    assertThat(controller.state).isEqualTo(LiveTextState.Idle)
    assertThat(controller.hasText(a)).isFalse()
    assertThat(pressed).containsExactly(Offset(0.4f, 0.4f))
  }

  @Test
  fun unaLetturaCheFallisceNonRompeNienteEVaAlRitaglio() = runTest {
    val broken = object : ViewerTextReader {
      override val available = true
      override suspend fun readNow(id: MediaId, ref: MediaRef): ViewerTextLayout? = throw IOException("lettura fallita")
    }
    val pressed = mutableListOf<Offset>()
    val services = ViewerServices(textSource = FakeViewerTextSource(), textReader = broken, subjectPress = { _, x, y -> pressed += Offset(x, y) })
    val controller = LiveTextController(this, app, LiveTextHost({ services }, { MediaRef.Synthetic(1, 100, 100) }, { MediaKind.PHOTO }, { a }, { }))
    controller.onLongPress(a, aspect, Offset(0.4f, 0.4f), 0.02f)
    runCurrent()
    assertThat(controller.state).isEqualTo(LiveTextState.Idle)
    assertThat(pressed).hasSize(1)
  }

  @Test
  fun seIlMediaCambiaDuranteLaLetturaIlRisultatoNonSiMostra() = runTest {
    val reader = FakeViewerTextReader(available = true, result = layout)
    val rig = Rig(this, source = FakeViewerTextSource(), reader = reader)
    rig.controller.bind(a)
    rig.controller.onLongPress(a, aspect, wordPoint(layout, 0, 0), 0.02f)
    rig.current = b
    runCurrent()
    assertThat(rig.controller.state).isEqualTo(LiveTextState.Idle)
  }

  @Test
  fun suUnVideoLaPressioneNonFaNienteNemmenoSeIlTestoCe() = runTest {
    val reader = FakeViewerTextReader(available = true, result = layout)
    val rig = Rig(this, source = FakeViewerTextSource(mutableMapOf(a to layout)), reader = reader, kind = MediaKind.VIDEO)
    rig.controller.bind(a)
    assertThat(rig.controller.wantsLongPress(a)).isFalse()
    press(rig, wordPoint(layout, 0, 0))
    assertThat(rig.controller.state).isEqualTo(LiveTextState.Idle)
    assertThat(reader.reads).isEqualTo(0)
  }

  @Test
  fun senzaTestoNeLetturaNeRitaglioLaPressioneNonHaNienteDaFare() = runTest {
    val rig = Rig(this)
    rig.controller.bind(a)
    assertThat(rig.controller.wantsLongPress(a)).isFalse()
    assertThat(Rig(this, subject = { _, _, _ -> }).controller.wantsLongPress(a)).isTrue()
    assertThat(Rig(this, source = FakeViewerTextSource()).controller.wantsLongPress(a)).isTrue()
    assertThat(Rig(this, reader = FakeViewerTextReader(available = false)).controller.wantsLongPress(a)).isFalse()
    assertThat(Rig(this, reader = FakeViewerTextReader(available = true)).controller.wantsLongPress(a)).isTrue()
  }
}
