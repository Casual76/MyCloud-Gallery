package dev.pampa.mycloudgallery.feature.viewer.livetext

import android.content.ClipboardManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.viewer.ScriptedImages
import dev.pampa.mycloudgallery.feature.viewer.ViewerOverlay
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.feature.viewer.ViewerTags
import dev.pampa.mycloudgallery.feature.viewer.ViewerTestBase
import dev.pampa.mycloudgallery.feature.viewer.ViewerTestHost
import dev.pampa.mycloudgallery.feature.viewer.gesture.ViewerSubjectPress
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeViewerTextSource

/**
 * La base delle prove del testo dal vivo sul viewer vero: foto 16:9 (proporzione fissa, cosi' le posizioni del testo a 1x si calcolano),
 * il testo finto dell'indice, la pressione prolungata e la lettura degli appunti. Nessuna posizione e' scritta a mano: vengono dalla
 * stessa geometria che le prove di logica verificano da sole.
 */
abstract class LiveTextViewerBase : ViewerTestBase() {

  protected val aspect: Float = LiveTextTestLines.ASPECT

  private val images16x9 = ScriptedImages { ref -> listOf(ScriptedImages.preview(ref), ScriptedImages.sharp(ref)) }

  /** L'indice del media su cui si apre il viewer: la prima foto della Libreria di prova. */
  internal open val startIndex: Int get() = fixture.indicesOf(MediaKind.PHOTO).first()

  protected val shown: MediaId get() = fixture.idAt(startIndex)

  /** "N di 50": la descrizione della pagina, per vedere che il pager non si e' mosso. */
  protected val pageLabel: String get() = "${startIndex + 1} di ${fixture.library.count}"

  internal fun textServices(
    layout: ViewerTextLayout? = LiveTextTestLines.multiline,
    reader: ViewerTextReader? = null,
    subject: ViewerSubjectPress? = null,
    withSource: Boolean = true,
  ): ViewerServices = services(
    imageSource = images16x9,
    textSource = if (withSource) FakeViewerTextSource(if (layout != null) mutableMapOf(shown to layout) else HashMap()) else null,
    textReader = reader,
    subjectPress = subject,
  )

  /** Apre il viewer sul media [startIndex] con i servizi dati. */
  internal fun showText(services: ViewerServices = textServices(), dark: Boolean = true, secure: Boolean = false) {
    val request = fixture.request(startIndex = startIndex, secure = secure)
    composeRule.setContent { ViewerTestHost(dark = dark) { ViewerOverlay(request = request, onClosed = { closed++ }, services = services) } }
    composeRule.waitForIdle()
  }

  protected fun pager() = composeRule.onNodeWithTag(ViewerTags.Pager)

  protected fun pageSize(): Size = pager().fetchSemanticsNode().size.let { Size(it.width.toFloat(), it.height.toFloat()) }

  /** La mappa a 1x: l'immagine intera nella pagina. */
  internal fun mapping(): ContentMapping = ContentMapping.identity(pageSize(), aspect)

  /** Il centro della pagina, nelle coordinate della pagina. */
  protected fun pageCenter(): Offset = pageSize().let { Offset(it.width / 2f, it.height / 2f) }

  /** Dove sta la parola [word] della riga [line] di [layout] a 1x, nelle coordinate della pagina. */
  protected fun at(layout: ViewerTextLayout, line: Int, word: Int): Offset {
    val quad = LiveTextGeometry(layout, aspect).quad(line, word, word)
    val center = Offset(quad.map { it.x }.average().toFloat(), quad.map { it.y }.average().toFloat())
    return mapping().imageToScreen(center)
  }

  /** Il punto della pagina dove cade un punto normalizzato dell'immagine a 1x. */
  protected fun atImage(x: Float, y: Float): Offset = mapping().imageToScreen(Offset(x, y))

  protected fun longPress(local: Offset) {
    pager().performTouchInput { longClick(local) }
    composeRule.waitForIdle()
  }

  /** Il centro di una maniglia, nelle coordinate della pagina. */
  internal fun handleCenter(side: HandleSide): Offset {
    val label = if (side == HandleSide.Start) START else END
    val bounds = composeRule.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
    val page = pager().fetchSemanticsNode().boundsInRoot
    return bounds.center - page.topLeft
  }

  protected fun handleSeparation(): Float = handleCenter(HandleSide.End).x - handleCenter(HandleSide.Start).x

  protected fun clip(): android.content.ClipData? =
    composeRule.activity.getSystemService(ClipboardManager::class.java).primaryClip

  protected fun clipText(): String? = clip()?.getItemAt(0)?.text?.toString()

  protected fun clipSensitive(): Boolean = clip()?.description?.extras?.getBoolean(SENSITIVE) == true

  protected fun hasNode(description: String): Boolean =
    composeRule.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()

  companion object {
    const val START = "Inizio della selezione"
    const val END = "Fine della selezione"
    const val SENSITIVE = "android.content.extra.IS_SENSITIVE"
  }
}
