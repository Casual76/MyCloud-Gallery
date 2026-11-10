package dev.pampa.mycloudgallery.core.designsystem.design

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.FluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
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
 * GF-2 (review della beta): l'etichetta in alto mostra **la prima forma della data che entra** nella larghezza che la riga le lascia,
 * invece di troncare quella lunga con i puntini. Qui l'etichetta da sola, in una scatola larga quanto le resta sul telefono
 * dell'utente (360 dp, quattro tasti nella capsula: 132 dp), piu' larga e piu' stretta, a testo normale e grande, in chiaro e in scuro:
 *  - la forma si sceglie al layout, quindi e' giusta **dal primo fotogramma**;
 *  - cambia quando cambia la larghezza (la capsula accanto che si allarga) e torna lunga quando lo spazio torna;
 *  - TalkBack legge sempre la data per esteso;
 *  - ogni forma si misura una volta sola, e solo se serve ([ChromeTitleFit]).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-xhdpi")
class GalleryChromeLabelFitTest {

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

  private var boxWidth by mutableStateOf(PhoneLabelWidth)
  private var title by mutableStateOf(FullDate)
  private var shorter by mutableStateOf(ShortDates)
  private var subtitle by mutableStateOf<String?>("Sesto San Giovanni e Milano")

  private fun showLabel(dark: Boolean = false, fontScale: Float = 1f, reduced: Boolean = false) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        val policy = if (reduced) FluidMotionPolicy(durationScale = 0f) else FluidMotionPolicy.Default
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalFluidMotionPolicy provides policy) {
          Box(Modifier.width(boxWidth), contentAlignment = Alignment.CenterStart) {
            val palette = GalleryChromeStyles.adaptivePalette()
            GalleryChromeLabel(
              title = title,
              subtitle = subtitle,
              style = GalleryChromeStyles.adaptive(GalleryLegibility.adapt(palette.unknown, palette)),
              backdrop = null,
              modifier = Modifier.testTag(LabelTag),
              shorterTitles = shorter,
            )
          }
        }
      }
    }
  }

  private fun settle() {
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(SettleMillis)
    composeRule.waitForIdle()
  }

  /** Le righe di testo dell'etichetta, dall'alto: la prima e' il titolo. */
  private fun lines(): List<SemanticsNode> = composeRule
    .onAllNodes(hasAnyAncestor(hasTestTag(LabelTag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
    .fetchSemanticsNodes()
    .sortedBy { it.boundsInRoot.top }

  private fun SemanticsNode.shown(): String = config[SemanticsProperties.Text].joinToString("") { it.text }

  private fun SemanticsNode.layoutResult(): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    checkNotNull(config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(results)
    return results.single()
  }

  /** Il titolo mostrato, dopo aver verificato che si legge intero (una riga, senza puntini, senza uscire). */
  private fun wholeTitle(where: String): String {
    val node = lines().first()
    val layout = node.layoutResult()
    assertWithMessage("$where: «${node.shown()}» sta su una riga").that(layout.lineCount).isEqualTo(1)
    assertWithMessage("$where: «${node.shown()}» non finisce con i puntini").that(layout.isLineEllipsized(0)).isFalse()
    // La riga scritta sta dentro il nodo (`didOverflowWidth` non lo dice: con una riga sola vale anche per un testo che entra).
    assertWithMessage("$where: «${node.shown()}» sta nel suo spazio").that(layout.getLineRight(0)).isAtMost(node.boundsInRoot.width + HalfPixel)
    return node.shown()
  }

  /** Cio' che TalkBack legge: la descrizione dell'intestazione se c'e', altrimenti il suo testo. */
  private fun spoken(): String {
    val heading = composeRule
      .onAllNodes(hasAnyAncestor(hasTestTag(LabelTag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
      .fetchSemanticsNodes()
      .single()
    val description = heading.config.getOrNull(SemanticsProperties.ContentDescription)
    return description?.joinToString(", ") ?: heading.config[SemanticsProperties.Text].joinToString(", ") { it.text }
  }

  // --- La scelta ---------------------------------------------------------------------------------------------------------------

  private fun checkPhone(dark: Boolean) {
    showLabel(dark = dark)
    settle()
    val shown = wholeTitle("132 dp, scuro=$dark")
    // La lunga non entra in 96 dp di testo; entra una delle corte, e non la piu' stretta se ne entra una piu' lunga.
    assertThat(shown).isNotEqualTo(FullDate)
    assertThat(shown).isIn(ShortDates)
    assertWithMessage("non serve la forma con le barre a testo normale").that(shown).isNotEqualTo(ShortDates.last())
  }

  @Test
  fun sulTelefonoStrettoLaDataPrendeLaPrimaFormaCheEntra() = checkPhone(dark = false)

  @Test
  fun sulTelefonoStrettoLaDataPrendeLaPrimaFormaCheEntraAncheInScuro() = checkPhone(dark = true)

  @Test
  fun conIlTestoGrandeLaDataSiLeggeAncoraIntera() {
    showLabel(fontScale = LargeText)
    settle()
    val shown = wholeTitle("132 dp, testo 1,3x")
    assertThat(shown).isIn(ShortDates)
  }

  @Test
  fun doveCeSpazioLaDataRestaLunga() {
    boxWidth = WideLabelWidth
    showLabel()
    settle()
    assertThat(wholeTitle("320 dp")).isEqualTo(FullDate)
    // Il titolo intero e' a schermo: TalkBack legge le due righe cosi' come sono.
    assertThat(spoken()).isEqualTo("$FullDate, Sesto San Giovanni e Milano")
  }

  @Test
  fun talkBackLeggeSempreLaDataPerEsteso() {
    showLabel()
    settle()
    assertThat(wholeTitle("132 dp")).isNotEqualTo(FullDate)
    assertThat(spoken()).isEqualTo("$FullDate, Sesto San Giovanni e Milano")
    // Senza il luogo, la data sola.
    composeRule.runOnIdle { subtitle = null }
    settle()
    assertThat(spoken()).isEqualTo(FullDate)
  }

  /** La forma si sceglie al layout: e' giusta nel primo fotogramma, non dopo una ricomposizione. */
  @Test
  fun laFormaEGiustaDalPrimoFotogramma() {
    composeRule.mainClock.autoAdvance = false
    showLabel()
    // Nessun avanzamento dell'orologio: solo la prima composizione e il suo layout.
    val first = wholeTitle("primo fotogramma")
    assertThat(first).isIn(ShortDates)
    composeRule.mainClock.advanceTimeBy(SettleMillis)
    assertThat(wholeTitle("a riposo")).isEqualTo(first)
  }

  /** Il luogo sotto la data puo' restare troncato, ma non toglie posto alla data: la forma scelta non dipende da lui. */
  @Test
  fun ilLuogoLungoNonToglieIlPostoAllaData() {
    subtitle = null
    showLabel()
    settle()
    val alone = wholeTitle("senza luogo")
    composeRule.runOnIdle { subtitle = "Sesto San Giovanni, Cinisello Balsamo e altri 12 luoghi" }
    settle()
    assertThat(wholeTitle("con un luogo lungo")).isEqualTo(alone)
    assertWithMessage("il luogo, lui, si tronca").that(lines().last().layoutResult().isLineEllipsized(0)).isTrue()
  }

  /** La capsula dei comandi che si allarga stringe l'etichetta: la data passa alla forma corta; quando lo spazio torna, torna lunga. */
  @Test
  fun laFormaSegueLaLarghezza() {
    boxWidth = WideLabelWidth
    showLabel()
    settle()
    assertThat(wholeTitle("larga")).isEqualTo(FullDate)
    composeRule.runOnIdle { boxWidth = PhoneLabelWidth }
    settle()
    assertThat(wholeTitle("stretta")).isIn(ShortDates)
    composeRule.runOnIdle { boxWidth = WideLabelWidth }
    settle()
    assertThat(wholeTitle("di nuovo larga")).isEqualTo(FullDate)
  }

  @Test
  fun conLeAnimazioniRidotteLaSceltaELaStessa() {
    showLabel(reduced = true)
    settle()
    assertThat(wholeTitle("animazioni ridotte")).isIn(ShortDates)
    assertThat(spoken()).startsWith(FullDate)
  }

  /** Se nemmeno la forma piu' stretta entra (una scatola di 60 dp), resta lei, con i puntini: mai una piu' lunga. */
  @Test
  fun seNessunaEntraRestaLaPiuStretta() {
    boxWidth = TinyLabelWidth
    showLabel()
    settle()
    assertThat(lines().first().shown()).isEqualTo(ShortDates.last())
  }

  /** Un titolo senza forme corte (il nome di una pagina, "Oggi") resta com'e': si tronca come prima se non entra. */
  @Test
  fun unTitoloSenzaFormeCorteRestaComE() {
    shorter = emptyList()
    showLabel()
    settle()
    assertThat(lines().first().shown()).isEqualTo(FullDate)
    assertThat(lines().first().layoutResult().isLineEllipsized(0)).isTrue()
    assertThat(spoken()).isEqualTo("$FullDate, Sesto San Giovanni e Milano")
  }

  // --- La misura: una volta per forma, e solo se serve -------------------------------------------------------------------------

  private class CountingWidths(private val widths: Map<String, Int>) : (String) -> Int {
    val measured = mutableListOf<String>()

    override fun invoke(text: String): Int {
      measured += text
      return widths.getValue(text)
    }
  }

  @Test
  fun ogniFormaSiMisuraUnaVoltaSolaESoloSeServe() {
    val widths = CountingWidths(mapOf("lunga" to 300, "media" to 200, "corta" to 100))
    val fit = ChromeTitleFit(listOf("lunga", "media", "corta"), backSlackPx = 4, widthOf = widths)
    // Entra la lunga: le altre non si misurano.
    assertThat(fit.pick(400)).isEqualTo(0)
    assertThat(widths.measured).containsExactly("lunga")
    // La riga si stringe a ogni fotogramma (la molla della capsula accanto): la scelta e' un confronto, niente si rimisura.
    for (max in 399 downTo 250) assertThat(fit.pick(max)).isEqualTo(if (max >= 300) 0 else 1)
    assertThat(widths.measured).containsExactly("lunga", "media").inOrder()
    // Nessuna entra: resta l'ultima, che non serve misurare.
    assertThat(fit.pick(50)).isEqualTo(2)
    assertThat(widths.measured).containsExactly("lunga", "media").inOrder()
  }

  @Test
  fun perTornareAUnaFormaPiuLungaServeUnMargine() {
    val fit = ChromeTitleFit(listOf("lunga", "corta"), backSlackPx = 4) { if (it == "lunga") 300 else 100 }
    assertThat(fit.pick(299)).isEqualTo(1)
    // La molla rimbalza di un pixel attorno alla misura: la forma non si alterna.
    assertThat(fit.pick(300)).isEqualTo(1)
    assertThat(fit.pick(303)).isEqualTo(1)
    assertThat(fit.pick(304)).isEqualTo(0)
    // Mostrata la lunga, resta finche' entra.
    assertThat(fit.pick(300)).isEqualTo(0)
    assertThat(fit.pick(299)).isEqualTo(1)
  }

  @Test
  fun unTitoloSoloNonSiMisura() {
    val widths = CountingWidths(emptyMap())
    val fit = ChromeTitleFit(listOf("Libreria"), backSlackPx = 4, widthOf = widths)
    assertThat(fit.pick(10)).isEqualTo(0)
    assertThat(fit.pick(1_000)).isEqualTo(0)
    assertThat(widths.measured).isEmpty()
  }

  private companion object {
    const val LabelTag = "etichetta"
    const val FullDate = "Mercoledì 20 novembre 2024"
    val ShortDates = listOf("Mer 20 nov 2024", "20 nov 2024", "20 nov 24", "20/11/24")

    /** Sul telefono a 360 dp: 360 - 24 di margini - 196 della capsula con quattro tasti - 8 fra i pezzi. */
    val PhoneLabelWidth: Dp = 132.dp
    val WideLabelWidth: Dp = 320.dp
    val TinyLabelWidth: Dp = 60.dp
    const val LargeText = 1.3f
    const val SettleMillis = 1_500L
    const val HalfPixel = 0.5f
  }
}
