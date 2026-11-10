package dev.pampa.mycloudgallery.feature.widget

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.feature.widget.frame.WidgetFrame
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot del widget (Fase 10, P3, "Fatto quando"): **chiaro e scuro, le tre misure, testo 1,3x, contrasto alto**. Sono i `RemoteViews` veri composti da Glance con la
 * palette del kit (`engineWidgetPalette`), applicati a una vista e disegnati con la grafica nativa di Robolectric su uno sfondo che fa da sfondo della Home. Le "foto" sono immagini
 * disegnate (sfumature e forme): nessun dato personale. Il titolo e' "Un anno fa · Oslo" (un luogo di prova).
 *
 * `gradlew :feature-widget:recordRoborazziDebug --tests "*WidgetScreenshotTest*"` scrive i PNG in `docs/screenshots/10-P3/`. Nei normali `testDebugUnitTest` non scrive niente e
 * si verifica solo che ogni disegno si componga e abbia le dimensioni giuste.
 *
 * **Contrasto alto**: il kit di `engine-widget` non ha uno schema a contrasto alto (la lettura dell'impostazione di sistema e il suo schema sono di P8, D26); qui "contrasto alto" e'
 * lo schema piu' contrastato che il kit ha, **AMOLED** (nero puro, testo chiarissimo), e il contrasto dei testi del widget e' misurato in `WidgetContrastTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi")
class WidgetScreenshotTest {

  private val context: Application get() = ApplicationProvider.getApplicationContext()

  @After
  fun resetFontScale() {
    RuntimeEnvironment.setFontScale(1.0f)
  }

  private val light = EngineSettings(themeMode = ThemeMode.LIGHT)
  private val dark = EngineSettings(themeMode = ThemeMode.DARK)
  private val highContrast = EngineSettings(themeMode = ThemeMode.AMOLED)

  private fun name(size: WidgetSize) = when (size) {
    WidgetSize.SMALL -> "piccola"
    WidgetSize.WIDE -> "larga"
    WidgetSize.LARGE -> "grande"
  }

  /** Il widget sullo sfondo della Home (uno sfondo di prova a due tinte) e la foto del disegno. */
  private fun onHome(widget: View, dark: Boolean): View {
    val host = FrameLayout(context)
    val backdrop = GradientDrawable(
      GradientDrawable.Orientation.TL_BR,
      if (dark) intArrayOf(Color.rgb(24, 28, 40), Color.rgb(52, 40, 64)) else intArrayOf(Color.rgb(184, 204, 232), Color.rgb(232, 206, 220)),
    )
    host.background = backdrop
    val pad = (16 * context.resources.displayMetrics.density).toInt()
    host.setPadding(pad, pad, pad, pad)
    host.addView(widget, FrameLayout.LayoutParams(widget.measuredWidth, widget.measuredHeight))
    val w = widget.measuredWidth + 2 * pad
    val h = widget.measuredHeight + 2 * pad
    host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
    host.layout(0, 0, w, h)
    return host
  }

  private fun shoot(frame: WidgetFrame, size: WidgetSize, settings: EngineSettings, isDark: Boolean, file: String) = runBlocking {
    val widget = WidgetTestKit.render(context, frame, size, settings)
    val (wDp, hDp) = WidgetTestKit.dp(size)
    val density = context.resources.displayMetrics.density
    assertThat(widget.width).isEqualTo((wDp * density).toInt())
    assertThat(widget.height).isEqualTo((hDp * density).toInt())
    val host = onHome(widget, isDark)
    // La vista non e' in una finestra (e' un RemoteViews applicato a mano): si disegna su una bitmap e si fotografa quella.
    val picture = Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888)
    host.draw(Canvas(picture))
    picture.captureRoboImage("$DIR/$file.png")
  }

  /** Chiaro e scuro, tre misure, i quattro casi dello stato (ricordo, foto a caso, blocco dell'app, nessuna foto). */
  @Test
  fun chiaroEScuroNelleTreMisure() {
    for (size in WidgetSize.entries) {
      for ((theme, settings) in listOf("chiaro" to light, "scuro" to dark)) {
        val isDark = theme == "scuro"
        shoot(WidgetTestKit.memory(size), size, settings, isDark, "${name(size)}-ricordo-$theme")
        shoot(WidgetTestKit.photos(size), size, settings, isDark, "${name(size)}-foto-$theme")
        shoot(WidgetTestKit.locked(size), size, settings, isDark, "${name(size)}-blocco-$theme")
        shoot(WidgetTestKit.message(size, WidgetEmpty.NO_MEDIA), size, settings, isDark, "${name(size)}-nessuna-foto-$theme")
      }
      shoot(WidgetTestKit.message(size, WidgetEmpty.NOT_CONFIGURED, "Configura MyCloud Gallery"), size, light, false, "${name(size)}-configura-chiaro")
    }
  }

  /** Testo grande (1,3x): il titolo e il messaggio nelle tre misure, chiaro e scuro. Il titolo si accorcia con i puntini, la descrizione dice il testo intero. */
  @Test
  fun testoUnVirgolaTre() {
    RuntimeEnvironment.setFontScale(1.3f)
    for (size in WidgetSize.entries) {
      for ((theme, settings) in listOf("chiaro" to light, "scuro" to dark)) {
        val isDark = theme == "scuro"
        shoot(WidgetTestKit.memory(size), size, settings, isDark, "${name(size)}-ricordo-testo-1-3x-$theme")
        shoot(WidgetTestKit.message(size), size, settings, isDark, "${name(size)}-nessuna-foto-testo-1-3x-$theme")
      }
    }
  }

  /** Contrasto alto: lo schema AMOLED del kit (nero puro, testo chiarissimo), le tre misure. */
  @Test
  fun contrastoAlto() {
    for (size in WidgetSize.entries) {
      shoot(WidgetTestKit.memory(size), size, highContrast, true, "${name(size)}-ricordo-contrasto-alto")
      shoot(WidgetTestKit.message(size), size, highContrast, true, "${name(size)}-nessuna-foto-contrasto-alto")
    }
  }

  private companion object {
    const val DIR = "../docs/screenshots/10-P3"
  }
}
