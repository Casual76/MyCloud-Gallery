package dev.pampa.mycloudgallery.feature.map

import android.app.Application
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.imaging.testing.FakePlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.testing.FakeThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.map.contract.MapRefs
import dev.pampa.mycloudgallery.feature.map.contract.MapThumbnails
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// La base dei test Compose di feature-map (Fase 9, P4): Robolectric con grafica nativa, telefono da 412 dp, tablet da 1280 dp, miniature e ritagli
// SINTETICI. Come `AlbumsComposeSupport` di feature-albums, che i feature non si possono prestare (non si conoscono). **Nessun volto vero, nessuna
// foto di persone**: i ritagli dei volti sono sagome astratte (un cerchio e un'ellisse su un fondo a colori), le foto sono gradienti.

/** Il telefono (412 dp) e il tablet (1280 dp, orizzontale) degli screenshot e dei test di layout. */
const val PHONE_QUALIFIERS = "w412dp-h915dp-xhdpi"

/** Il telefono degli screenshot per l'utente (K4): il suo Galaxy S25, 360 x 780 dp. Non 412 dp, dove entrano piu' cose che sul suo schermo. */
const val SHOT_PHONE_QUALIFIERS = "w360dp-h780dp-xhdpi"
const val TABLET_QUALIFIERS = "w1280dp-h800dp-land-xhdpi"

/** Il testo grande della regola di accessibilita' (1,3x). */
const val LARGE_TEXT = 1.3f

/**
 * Quali immagini si registrano (le immagini sono grandi: un tablet a 2x e' 2560x1600). Il telefono ha tutte le scene, chiaro e scuro; il tablet le principali (le altre si compongono e si controllano lo
 * stesso); il testo grande solo sul telefono.
 */
object ScreenshotPolicy {
  private val tablet = setOf("mappa-pronta", "mappa-solo-wifi", "mappa-carta-semplice", "mappa-vuota", "zona-pronta", "luoghi-pronta", "impostazioni-accesa", "impostazioni-spenta")

  fun capture(name: String, device: String, fontScale: Float?): Boolean = when {
    device == "telefono" -> true
    fontScale != null -> false
    else -> name in tablet
  }
}

/** Le sorgenti delle miniature dei test: ogni media ha un'immagine sintetica fatta dal suo id. */
class SyntheticMapRefs : MapRefs {
  override fun refAt(snapshot: TimelineSnapshot, index: Int): MediaRef? =
    MediaRef.Synthetic(snapshot.idAt(index).value.hashCode().toLong(), WIDTH, HEIGHT)

  private companion object {
    const val WIDTH = 1_200
    const val HEIGHT = 900
  }
}

/** Una tessera di prova: un fondo liscio dal colore deciso dal seme (nessuna foto vera). */
fun tileBitmap(ref: MediaRef, px: Int): Bitmap? {
  val seed = (ref as? MediaRef.Synthetic)?.seed ?: return null
  val hue = (((seed % 360) + 360) % 360).toFloat()
  val top = Color.HSVToColor(floatArrayOf(hue, 0.30f, 0.93f))
  val bottom = Color.HSVToColor(floatArrayOf((hue + 24f) % 360f, 0.50f, 0.74f))
  val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
  val paint = Paint().apply { shader = LinearGradient(0f, 0f, 0f, px.toFloat(), top, bottom, Shader.TileMode.CLAMP) }
  Canvas(bitmap).drawRect(0f, 0f, px.toFloat(), px.toFloat(), paint)
  return bitmap
}

/** Le miniature dei test: ogni media ha un'immagine sintetica fatta dal suo id. */
class TestMapThumbnails {
  val loader = FakeThumbnailLoader(::tileBitmap)
  val thumbnails = MapThumbnails(loader, FakePlaceholderCache(), SyntheticMapRefs())
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_QUALIFIERS)
abstract class MapComposeTest {

  val composeRule: AndroidComposeTestRule<*, ComponentActivity> = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  /** L'activity vuota della regola di Compose non e' nel manifest di un modulo libreria: la si registra a mano. */
  private class RegisterComponentActivity : TestWatcher() {
    override fun starting(description: Description) {
      val app = ApplicationProvider.getApplicationContext<Application>()
      val info = ActivityInfo().apply {
        name = ComponentActivity::class.java.name
        packageName = app.packageName
        flags = flags or ActivityInfo.FLAG_HARDWARE_ACCELERATED
      }
      shadowOf(app.packageManager).addOrUpdateActivity(info)
    }
  }

  /** Mostra [content] nel tema della galleria e aspetta che tutto sia assestato. */
  protected fun show(dark: Boolean = false, fontScale: Float? = null, content: @Composable () -> Unit) {
    composeRule.setContent {
      val settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
      MyCloudGalleryTheme(settings = settings) {
        if (fontScale == null) {
          content()
        } else {
          val density = LocalDensity.current
          CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { content() }
        }
      }
    }
    composeRule.waitForIdle()
  }

  /**
   * L'azione di click di chi si tocca scrivendo [text]: quella del nodo piu' vicino (lui o un genitore) che ne ha una, fra quelli che hanno il testo, l'ultimo
   * dell'albero (cio' che sta sopra: un foglio sta dopo la pagina). Null se il testo non c'e' o non e' un controllo.
   */
  private fun clickActionOf(text: String, substring: Boolean): (() -> Boolean)? {
    val nodes = composeRule.onAllNodes(hasText(text, substring = substring), useUnmergedTree = true).fetchSemanticsNodes()
    for (node in nodes.asReversed()) {
      val action = generateSequence(node) { it.parent }.firstNotNullOfOrNull { it.config.getOrNull(SemanticsActions.OnClick) }?.action
      if (action != null) return action
    }
    return null
  }

  /** Il testo [text] e' (dentro) un controllo che si puo' toccare? */
  protected fun isClickable(text: String, substring: Boolean = true): Boolean = clickActionOf(text, substring) != null

  /** Tocca il controllo con [text] chiamando la sua azione di click (come fa TalkBack). */
  protected fun clickText(text: String, substring: Boolean = true) {
    val action = checkNotNull(clickActionOf(text, substring)) { "nessun controllo con il testo «$text»" }
    composeRule.runOnUiThread { action() }
    composeRule.waitForIdle()
  }
}
