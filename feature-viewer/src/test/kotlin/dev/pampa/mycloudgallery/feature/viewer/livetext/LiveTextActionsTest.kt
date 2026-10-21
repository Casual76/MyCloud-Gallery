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
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeViewerTextSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Le azioni del testo dal vivo (Fase 6, P12; D43, D46): copia con `EXTRA_IS_SENSITIVE` (verificata sul `ClipData`), condividi testo,
 * seleziona tutto, maniglie e pulsante "Testo". Il testo copiato non finisce mai nei log: lo fissa anche `NoLiveTextLogTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LiveTextActionsTest {

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val a = MediaId("00000000-0000-4000-8000-00000000000a")
  private val aspect = LiveTextTestLines.ASPECT
  private val layout = LiveTextTestLines.multiline
  private val messages = mutableListOf<ViewerMessage>()

  private fun TestScope.controller(text: ViewerTextLayout = layout): LiveTextController {
    val services = ViewerServices(textSource = FakeViewerTextSource(mutableMapOf(a to text)))
    return LiveTextController(this, app, LiveTextHost({ services }, { MediaRef.Synthetic(1, 100, 100) }, { MediaKind.PHOTO }, { a }, { messages += it }))
  }

  private fun word(line: Int, word: Int): Offset {
    val quad = LiveTextGeometry(layout, aspect).quad(line, word, word)
    return Offset(quad.map { it.x }.average().toFloat(), quad.map { it.y }.average().toFloat())
  }

  private suspend fun TestScope.select(controller: LiveTextController, line: Int, word: Int) {
    controller.bind(a)
    controller.onLongPress(a, aspect, word(line, word), 0.02f)
    runCurrent()
  }

  private fun clip() = app.getSystemService(ClipboardManager::class.java).primaryClip!!

  // --- Copia: appunti sensibili (D43) ---------------------------------------------------------------------------------------------

  @Test
  fun copiaMetteIlTestoSelezionatoNegliAppuntiSegnatoComeSensibile() = runTest {
    val controller = controller()
    select(controller, 1, 1)
    controller.copySelection("Testo dalla foto")
    val clip = clip()
    assertThat(clip.getItemAt(0).text.toString()).isEqualTo("piu'")
    assertThat(clip.description.label.toString()).isEqualTo("Testo dalla foto")
    // `ClipDescription.EXTRA_IS_SENSITIVE`: da Android 13 niente anteprima negli appunti.
    assertThat(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE")).isTrue()
    assertThat(messages).containsExactly(ViewerMessage.TextCopied)
    assertThat(controller.state).isEqualTo(LiveTextState.Idle)
  }

  @Test
  fun copiaUnaSelezioneSuPiuRighe() = runTest {
    val controller = controller()
    select(controller, 0, 1)
    controller.beginDrag(HandleSide.End)
    controller.dragTo(word(2, 0), aspect)
    controller.endDrag()
    controller.copySelection("Testo")
    assertThat(clip().getItemAt(0).text.toString()).isEqualTo("di lettura\nsu piu' righe di testo\nfine")
    assertThat(clip().description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE")).isTrue()
  }

  @Test
  fun selezionaTuttoECopiaTuttoIlTesto() = runTest {
    val controller = controller()
    select(controller, 1, 0)
    controller.selectAll(aspect)
    assertThat(controller.selection).isEqualTo(TextSelection(TextPosition(0, 0), TextPosition(2, 0)))
    controller.copySelection("Testo")
    assertThat(clip().getItemAt(0).text.toString()).isEqualTo("Prova di lettura\nsu piu' righe di testo\nfine")
  }

  @Test
  fun copiaTuttoDelFoglioEAnchEssaSensibile() = runTest {
    val controller = controller()
    controller.bind(a)
    controller.openSheet(a)
    runCurrent()
    assertThat(controller.state).isInstanceOf(LiveTextState.Sheet::class.java)
    controller.copyAll("Testo")
    assertThat(clip().getItemAt(0).text.toString()).isEqualTo("Prova di lettura\nsu piu' righe di testo\nfine")
    assertThat(clip().description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE")).isTrue()
    assertThat(controller.state).isEqualTo(LiveTextState.Idle)
  }

  @Test
  fun senzaSelezioneLaCopiaNonFaNiente() = runTest {
    val controller = controller()
    controller.bind(a)
    controller.copySelection("Testo")
    controller.shareSelection()
    assertThat(messages).isEmpty()
  }

  // --- Condividi testo --------------------------------------------------------------------------------------------------------------

  @Test
  fun condividiTestoEUnACTION_SENDDiTestoSemplice() = runTest {
    val controller = controller()
    select(controller, 0, 2)
    controller.shareSelection()
    val chooser = shadowOf(app).nextStartedActivity
    assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
    val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
    assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
    assertThat(send.type).isEqualTo("text/plain")
    assertThat(send.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("lettura")
    assertThat(send.data).isNull()
    assertThat(controller.state).isEqualTo(LiveTextState.Idle)
  }

  // --- Maniglie: si agganciano alle parole, anche su un'altra riga ---------------------------------------------------------------

  @Test
  fun laManigliaDiFineCresceSuLleRigheEQuellaDiInizioLaRiduce() = runTest {
    val controller = controller()
    select(controller, 0, 0)
    controller.beginDrag(HandleSide.End)
    assertThat(controller.state).isInstanceOf(LiveTextState.Selecting::class.java)
    assertThat(controller.dragging).isEqualTo(HandleSide.End)
    controller.dragTo(word(1, 3), aspect)
    assertThat(controller.selection).isEqualTo(TextSelection(TextPosition(0, 0), TextPosition(1, 3)))
    controller.endDrag()
    assertThat(controller.state).isInstanceOf(LiveTextState.Actions::class.java)
    assertThat(controller.dragging).isNull()

    controller.beginDrag(HandleSide.Start)
    controller.dragTo(word(1, 1), aspect)
    assertThat(controller.selection).isEqualTo(TextSelection(TextPosition(1, 1), TextPosition(1, 3)))
  }

  @Test
  fun tirataOltreLAltraManigliaLaSelezioneRestaOrdinata() = runTest {
    val controller = controller()
    select(controller, 1, 2)
    controller.beginDrag(HandleSide.End)
    controller.dragTo(word(0, 0), aspect)
    assertThat(controller.selection).isEqualTo(TextSelection(TextPosition(0, 0), TextPosition(1, 2)))
  }

  @Test
  fun unaManigliaLontanaDalTestoSiAgganciaComunqueAllaParolaPiuVicina() = runTest {
    val controller = controller()
    select(controller, 0, 0)
    controller.beginDrag(HandleSide.End)
    controller.dragTo(Offset(0.99f, 0.99f), aspect)
    val end = controller.selection!!.to
    assertThat(end.line).isAtLeast(1)
  }

  // --- Pulsante "Testo", foglio, chiusura -----------------------------------------------------------------------------------------

  @Test
  fun ilPulsanteTestoEvidenziaPoiApreIlFoglio() = runTest {
    val controller = controller()
    controller.bind(a)
    controller.toggleHighlight(a)
    assertThat(controller.state).isInstanceOf(LiveTextState.Highlighted::class.java)
    assertThat(controller.canDismiss).isTrue()
    assertThat(controller.active).isFalse()
    controller.toggleHighlight(a)
    assertThat(controller.state).isInstanceOf(LiveTextState.Sheet::class.java)
    assertThat(controller.sheetOpen).isTrue()
    controller.closeSheet()
    assertThat(controller.state).isEqualTo(LiveTextState.Idle)
  }

  @Test
  fun tuttoCioCheESapertoSiChiudeConDismiss() = runTest {
    val controller = controller()
    select(controller, 0, 0)
    assertThat(controller.canDismiss).isTrue()
    controller.dismiss()
    assertThat(controller.state).isEqualTo(LiveTextState.Idle)
    assertThat(controller.canDismiss).isFalse()
    controller.toggleHighlight(a)
    controller.dismiss()
    assertThat(controller.state).isEqualTo(LiveTextState.Idle)
  }

  @Test
  fun ilFoglioNonEUnaSelezioneAperta() = runTest {
    val controller = controller()
    select(controller, 0, 0)
    assertThat(controller.active).isTrue()
    controller.dismiss()
    controller.openSheet(a)
    runCurrent()
    // Il foglio e' modale e si chiude da se': non e' una selezione aperta.
    assertThat(controller.active).isFalse()
  }
}
