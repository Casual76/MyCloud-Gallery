package dev.pampa.mycloudgallery.feature.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.core.imaging.contract.ViewerImageState
import dev.pampa.mycloudgallery.core.imaging.testing.FakeViewerImageSource
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Gli screenshot del viewer per la revisione grafica della beta (G3, B4): il tasto di chiusura e il ⋮ con lo stesso vetro **su una foto chiara e su una scura** (la scura ha una zona accesa
 * proprio dietro il ⋮: era il caso in cui il vetro del ⋮ non ne teneva conto), e il menu che si apre dalla goccia del tasto (a meta' del viaggio e aperto). Chiaro e scuro, telefono e tablet.
 * `gradlew :feature-viewer:recordRoborazziDebug --tests "*ViewerG3Shots*"` scrive i PNG in `docs/screenshots/beta-grafica/g3-viewer/`.
 */
abstract class ViewerG3Shots(private val device: String, private val portrait: Boolean) : ViewerTestBase() {

  private fun path(name: String, dark: Boolean) = "../docs/screenshots/beta-grafica/g3-viewer/viewer-$name-${if (dark) "scuro" else "chiaro"}-$device.png"

  /**
   * La Libreria di prova ha foto tutte larghe: qui il catalogo dice che ogni foto riempie lo schermo (0,46 sul telefono, 1,6 sul tablet), cosi' la foto arriva sotto la barra, dove sta il
   * vetro dei tasti, come una foto verticale vera sul telefono.
   */
  private fun fullScreenView(): TimelineView {
    val base = fixture.library.view()
    val milli = if (portrait) 460 else 1600
    val snapshot = object : TimelineSnapshot by base.snapshot {
      override fun aspectMilliAt(i: Int): Int = milli
    }
    return object : TimelineView {
      override val snapshot: TimelineSnapshot = snapshot
      override val filter: LibraryFilter = base.filter
      override val size: Int = base.size

      override fun snapshotIndex(viewIndex: Int): Int = base.snapshotIndex(viewIndex)

      override fun viewIndexOf(id: MediaId): Int = base.viewIndexOf(id)
    }
  }

  private fun photoServices(light: Boolean): ViewerServices {
    val bitmap = (if (light) lightPhoto(portrait) else darkPhoto(portrait)).asImageBitmap()
    // La foto dichiara le sue proporzioni (e' da li' che il viewer la dispone a schermo intero): quelle della sua bitmap.
    val source = PhoneAwareImages(
      delegate = FakeViewerImageSource(forced = ViewerImageState.Sharp(bitmap, hdr = false, colorSpace = null)),
      mapper = { MediaRef.Synthetic(seed = it.mediaStoreId, width = bitmap.width, height = bitmap.height) },
    )
    return services(imageSource = source)
  }

  private fun buttons(photoLight: Boolean, dark: Boolean) {
    fixture.items.publish(fullScreenView())
    show(services = photoServices(light = photoLight), dark = dark)
    composeRule.mainClock.advanceTimeBy(600)
    composeRule.onRoot().captureRoboImage(path("tasti-foto-${if (photoLight) "chiara" else "scura"}", dark))
  }

  private fun menu(dark: Boolean) {
    fixture.items.publish(fullScreenView())
    show(services = photoServices(light = false), dark = dark)
    composeRule.mainClock.advanceTimeBy(600)
    composeRule.mainClock.autoAdvance = false
    composeRule.onNodeWithContentDescription("Altre azioni").performClick()
    // A meta' del viaggio: la goccia del tasto che diventa il menu.
    composeRule.mainClock.advanceTimeBy(110)
    composeRule.onRoot().captureRoboImage(path("menu-goccia", dark))
    composeRule.mainClock.advanceTimeBy(700)
    composeRule.onRoot().captureRoboImage(path("menu-aperto", dark))
    composeRule.mainClock.autoAdvance = true
    composeRule.waitUntil(10_000L) { composeRule.onAllNodesWithText("Informazioni").fetchSemanticsNodes().isNotEmpty() }
  }

  @Test fun tasti_fotoChiara_chiaro() = buttons(photoLight = true, dark = false)

  @Test fun tasti_fotoChiara_scuro() = buttons(photoLight = true, dark = true)

  @Test fun tasti_fotoScura_chiaro() = buttons(photoLight = false, dark = false)

  @Test fun tasti_fotoScura_scuro() = buttons(photoLight = false, dark = true)

  @Test fun menu_chiaro() = menu(dark = false)

  @Test fun menu_scuro() = menu(dark = true)
}

@Config(qualifiers = "it-w411dp-h891dp-xhdpi")
class ViewerG3ShotsPhone : ViewerG3Shots("telefono", portrait = true)

@Config(qualifiers = "it-w1280dp-h800dp-xhdpi")
class ViewerG3ShotsTablet : ViewerG3Shots("tablet", portrait = false)

/** Una foto chiara: cielo quasi bianco in alto (dietro la barra), un prato caldo e luminoso sotto, qualche nuvola. */
private fun lightPhoto(portrait: Boolean): Bitmap = photo(portrait) { canvas, w, h ->
  val sky = Paint().apply { shader = LinearGradient(0f, 0f, 0f, h * 0.62f, 0xFFFDFEFF.toInt(), 0xFFD6E6F3.toInt(), Shader.TileMode.CLAMP) }
  canvas.drawRect(0f, 0f, w, h * 0.62f, sky)
  val grass = Paint().apply { shader = LinearGradient(0f, h * 0.62f, 0f, h, 0xFFF1E7C4.toInt(), 0xFFC9D8A2.toInt(), Shader.TileMode.CLAMP) }
  canvas.drawRect(0f, h * 0.62f, w, h, grass)
  val cloud = Paint().apply { color = 0xFFFFFFFF.toInt() }
  canvas.drawOval(RectF(w * 0.10f, h * 0.08f, w * 0.52f, h * 0.22f), cloud)
  canvas.drawOval(RectF(w * 0.55f, h * 0.03f, w * 0.96f, h * 0.17f), cloud)
  val shadow = Paint().apply { color = 0xFFB9C7D4.toInt() }
  canvas.drawOval(RectF(w * 0.30f, h * 0.45f, w * 0.80f, h * 0.60f), shadow)
}

/** Una foto scura: la sera, con una finestra accesa in alto a destra (dietro il ⋮) e qualche luce. */
private fun darkPhoto(portrait: Boolean): Bitmap = photo(portrait) { canvas, w, h ->
  val night = Paint().apply { shader = LinearGradient(0f, 0f, 0f, h, 0xFF0A0E13.toInt(), 0xFF1B242E.toInt(), Shader.TileMode.CLAMP) }
  canvas.drawRect(0f, 0f, w, h, night)
  val window = Paint().apply { color = 0xFFFFF4D6.toInt() }
  canvas.drawRect(RectF(w * 0.72f, h * 0.01f, w * 0.99f, h * 0.10f), window)
  val lamp = Paint().apply { color = 0xFFFFD27A.toInt() }
  canvas.drawOval(RectF(w * 0.08f, h * 0.30f, w * 0.20f, h * 0.38f), lamp)
  val house = Paint().apply { color = 0xFF05080B.toInt() }
  canvas.drawRect(RectF(0f, h * 0.70f, w, h), house)
}

/** Una foto che riempie lo schermo: alta e stretta sul telefono, larga sul tablet, cosi' il vetro dei tasti sta sopra la foto e non sul fondo del viewer. */
private fun photo(portrait: Boolean, draw: (Canvas, Float, Float) -> Unit): Bitmap {
  val width = if (portrait) 1080 else 1600
  val height = if (portrait) 2340 else 1000
  val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
  draw(Canvas(bitmap), width.toFloat(), height.toFloat())
  return bitmap
}
