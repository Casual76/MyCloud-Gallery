package dev.pampa.mycloudgallery.feature.viewer.cutout

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.viewer.ViewerOverlay
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.feature.viewer.ViewerTags
import dev.pampa.mycloudgallery.feature.viewer.ViewerTestBase
import dev.pampa.mycloudgallery.feature.viewer.ViewerTestHost
import dev.pampa.mycloudgallery.feature.viewer.livetext.ContentMapping
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeCutoutSaver
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeViewerSubjectCutter
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeViewerTextSource
import dev.pampa.mycloudgallery.feature.viewer.livetext.ViewerTextLayout

/**
 * La base delle prove del ritaglio del soggetto sul viewer vero (Fase 6, P13): la foto di prova con il robot disegnato ([CutoutTestImage], 16:9),
 * il ritagliatore finto che da' il robot se il dito e' sul robot e niente altrove, il salvataggio finto e la pressione prolungata.
 */
abstract class CutoutViewerBase : ViewerTestBase() {

  protected val aspect: Float = CutoutTestImage.WIDTH.toFloat() / CutoutTestImage.HEIGHT

  private val picture = CutoutTestImage.source()

  /** L'indice del media su cui si apre il viewer: la prima foto della Libreria di prova. */
  internal open val startIndex: Int get() = fixture.indicesOf(MediaKind.PHOTO).first()

  protected val shown: MediaId get() = fixture.idAt(startIndex)

  /** "N di 50": la descrizione della pagina, per vedere che il pager non si e' mosso. */
  protected val pageLabel: String get() = "${startIndex + 1} di ${fixture.library.count}"

  internal fun cutoutServices(
    cutter: FakeViewerSubjectCutter? = CutoutTestImage.cutter(),
    saver: FakeCutoutSaver? = FakeCutoutSaver(),
    text: ViewerTextLayout? = null,
  ): ViewerServices = services(
    imageSource = picture,
    subjectCutter = cutter,
    cutoutSaver = saver,
    textSource = text?.let { FakeViewerTextSource(mutableMapOf(shown to it)) },
  )

  /** Apre il viewer sul media [startIndex] con i servizi dati. */
  internal fun showCutout(services: ViewerServices = cutoutServices(), dark: Boolean = true, secure: Boolean = false) {
    val request = fixture.request(startIndex = startIndex, secure = secure)
    composeRule.setContent { ViewerTestHost(dark = dark) { ViewerOverlay(request = request, onClosed = { closed++ }, services = services) } }
    composeRule.waitForIdle()
  }

  protected fun pager() = composeRule.onNodeWithTag(ViewerTags.Pager)

  protected fun pageSize(): Size = pager().fetchSemanticsNode().size.let { Size(it.width.toFloat(), it.height.toFloat()) }

  /** La mappa a 1x: l'immagine intera nella pagina. */
  internal fun mapping(): ContentMapping = ContentMapping.identity(pageSize(), aspect)

  /** Il punto della pagina dove cade un punto normalizzato dell'immagine a 1x. */
  protected fun atImage(x: Float, y: Float): Offset = mapping().imageToScreen(Offset(x, y))

  protected fun longPress(local: Offset) {
    pager().performTouchInput { longClick(local) }
    composeRule.waitForIdle()
  }

  /** La pressione prolungata sul corpo del robot. */
  protected fun pressRobot() = longPress(atImage(CutoutTestImage.BODY_X, CutoutTestImage.BODY_Y))

  /** La pressione prolungata sul prato (nessun soggetto). */
  protected fun pressGrass() = longPress(atImage(CutoutTestImage.GRASS_X, CutoutTestImage.GRASS_Y))

  protected fun hasNode(description: String): Boolean =
    composeRule.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()

  protected fun tap(description: String) {
    composeRule.onNodeWithContentDescription(description).performClick()
    composeRule.waitForIdle()
  }

  protected fun hasText(text: String): Boolean =
    composeRule.onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()

  companion object {
    const val COPY = "Copia"
    const val SHARE = "Condividi"
    const val SAVE = "Salva come nuova foto"
    const val OUTLINE = "Contorno"
    const val CUTOUT_ACTION = "Ritaglia il soggetto"
  }
}
