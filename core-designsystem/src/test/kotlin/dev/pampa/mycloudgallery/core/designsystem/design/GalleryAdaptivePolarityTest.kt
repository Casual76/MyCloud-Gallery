package dev.pampa.mycloudgallery.core.designsystem.design

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * GS-5 (review della beta): quando il contenuto sotto un pezzo di chrome cambia e il vetro adattivo sceglie un'altra coppia di
 * inchiostro e pellicola, **nessuna coppia mostrata per strada** scende sotto 4,5:1 sul contenuto di arrivo. Prima inchiostro e
 * pellicola si animavano separatamente con la stessa molla: fra l'inchiostro chiaro e quello scuro, a meta' interpolazione, il testo
 * stava a 2,56:1 (estremi a 21:1 e 20,75:1), e una pellicola che doveva farsi piu' densa restava sotto soglia finche' non arrivava.
 *
 * Qui il composable vero ([rememberAdaptiveChromeStyle]), fotogramma per fotogramma con l'orologio fermo: ogni stile restituito dopo
 * il cambio del contenuto si misura sul contenuto nuovo.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-xhdpi")
class GalleryAdaptivePolarityTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

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

  /** Un contenuto di un colore solo sotto la chrome (una foto uniforme), che il test cambia. */
  private class FlatContent(initial: Color) : GalleryChromeContent {
    var color by mutableStateOf(initial)

    override fun chromeRowsUnder(bounds: Rect): Long = color.value.toLong()

    override fun chromeContentUnder(bounds: Rect, page: Color, text: Color): ContentRange = ContentRange(listOf(color))

    override val chromeOriginInWindow: Offset get() = Offset.Zero

    override val chromeContentVersion: Any? get() = null
  }

  /** Inchiostro e pellicola restituiti a una composizione. */
  private data class Shown(val ink: Color, val film: Color)

  /**
   * Compone lo stile adattivo sopra [from] con il tema [dark], poi cambia il contenuto in [to] e restituisce ogni coppia mostrata da
   * quel momento (la prima e' quella della composizione che vede il contenuto nuovo), per un secondo e mezzo di fotogrammi.
   */
  private fun pairsAfterChange(dark: Boolean, from: Color, to: Color): List<Shown> {
    val content = FlatContent(from)
    val shown = mutableListOf<Shown>()
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val style = rememberAdaptiveChromeStyle(content, Bounds, veil = null)
        SideEffect { shown += Shown(style.ink, style.tint.overlay) }
      }
    }
    composeRule.waitForIdle()
    composeRule.mainClock.autoAdvance = false
    composeRule.runOnIdle {
      shown.clear()
      content.color = to
    }
    repeat(Frames) {
      composeRule.mainClock.advanceTimeByFrame()
      composeRule.waitForIdle()
    }
    return shown.toList()
  }

  private fun assertLegibleAllTheWay(pairs: List<Shown>, on: Color, where: String) {
    assertWithMessage("$where: lo stile si e' ricomposto").that(pairs).isNotEmpty()
    pairs.forEachIndexed { i, pair ->
      val contrast = GalleryLegibility.contrastOver(pair.ink, pair.film, on)
      assertWithMessage("$where: coppia $i di ${pairs.size} (inchiostro ${pair.ink}, pellicola ${pair.film})").that(contrast).isAtLeast(GalleryLegibility.TextContrast - Rounding)
    }
  }

  /** Il caso del critico: dall'inchiostro chiaro su una foto nera a quello scuro su una foto bianca. La polarita' cambia. */
  @Test
  fun cambiandoPolaritaNessunaCoppiaScendeSottoLaSoglia() {
    // La premessa, con la funzione pura: nel tema scuro sopra il nero l'inchiostro e' chiaro, e sopra il bianco passa a scuro.
    val palette = GalleryChromeStyles.adaptivePaletteFor(Black)
    val start = GalleryLegibility.adapt(ContentRange(listOf(Black)), palette, previousDark = false)
    val end = GalleryLegibility.adapt(ContentRange(listOf(White)), palette, previousDark = start.darkInk)
    assertThat(start.darkInk).isFalse()
    assertThat(end.darkInk).isTrue()

    val pairs = pairsAfterChange(dark = true, from = Black, to = White)
    assertWithMessage("alla fine l'inchiostro e' quello scuro: $pairs").that(pairs.last().ink.luminance()).isLessThan(DarkInkLuminance)
    assertLegibleAllTheWay(pairs, White, "nero -> bianco, tema scuro")
  }

  /** L'inchiostro resta lo stesso ma la pellicola deve farsi piu' densa: non ci arriva piano, restando sotto soglia per strada. */
  @Test
  fun quandoServePiuPellicolaArrivaSubito() {
    val pairs = pairsAfterChange(dark = false, from = White, to = Black)
    assertLegibleAllTheWay(pairs, Black, "bianco -> nero, tema chiaro")
  }

  /** La pellicola che puo' alleggerirsi lo fa con la molla (resta sopra soglia per costruzione): il movimento non sparisce. */
  @Test
  fun laPellicolaSiAlleggerisceConLaMolla() {
    // Tema scuro, inchiostro chiaro: sopra un grigio molto chiaro serve piu' pellicola nera che sopra il nero (ma non tanta da cambiare
    // polarita'). Passando al nero la pellicola torna al minimo, con la molla.
    val pairs = pairsAfterChange(dark = true, from = LightGray, to = Black)
    assertWithMessage("l'inchiostro resta chiaro: $pairs").that(pairs.map { it.ink }.distinct()).hasSize(1)
    assertLegibleAllTheWay(pairs, Black, "grigio chiaro -> nero, tema scuro")
    val alphas = pairs.map { it.film.alpha }.distinct()
    assertWithMessage("opacita' della pellicola per strada: $alphas").that(alphas.size).isAtLeast(MinStepsOfSpring)
    assertThat(alphas.last()).isLessThan(alphas.first())
    assertWithMessage("la pellicola scende soltanto: $alphas").that(alphas.zipWithNext().all { (a, b) -> b <= a }).isTrue()
  }

  private companion object {
    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)
    val LightGray = Color(0xFFF2F2F2)
    val Bounds = Rect(0f, 0f, 200f, 100f)

    /** Un secondo e mezzo: la molla standard (0,38 s) e' arrivata da tempo. */
    const val Frames = 94

    /** La ricerca dell'opacita' minima si ferma a 4,5 entro l'arrotondamento dei colori a 8 bit. */
    const val Rounding = 0.02f

    const val MinStepsOfSpring = 4
    const val DarkInkLuminance = 0.1f
  }
}
