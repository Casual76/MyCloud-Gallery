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
 * GF-22 (review della beta): in cima alla Libreria, a 360 dp con quattro tasti nella capsula, il riepilogo («1.817 foto, 183 video») si tagliava dentro un numero:
 * «1.817 foto, 18…». Un conteggio non si taglia a meta': con `shorterSubtitles` il sottotitolo e' un riepilogo che **si mostra intero o non si mostra**: la prima forma che
 * entra (la forma intera, il totale «2.000 elementi», il solo numero) e, se nemmeno la piu' stretta entra, niente seconda riga. Il luogo (`shorterSubtitles` null) si
 * tronca con i puntini come sempre. L'etichetta e' una scatola larga quanto resta sul telefono dell'utente (360 dp, quattro tasti: 132 dp).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-xhdpi")
class GalleryChromeLabelSummaryFitTest {

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
  private var forms by mutableStateOf<List<String>?>(ShorterForms)

  private fun showLabel(dark: Boolean = false, fontScale: Float = 1f) {
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
          Box(Modifier.width(boxWidth), contentAlignment = Alignment.CenterStart) {
            val palette = GalleryChromeStyles.adaptivePalette()
            GalleryChromeLabel(
              title = "Libreria",
              subtitle = FullSummary,
              style = GalleryChromeStyles.adaptive(GalleryLegibility.adapt(palette.unknown, palette)),
              backdrop = null,
              modifier = Modifier.testTag(LabelTag),
              shorterSubtitles = forms,
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

  /** Il riepilogo mostrato, dopo aver verificato che si legge intero (una riga, senza puntini). Null se la seconda riga manca. */
  private fun wholeSummary(where: String): String? {
    val all = lines()
    assertWithMessage("$where: il titolo c'e'").that(all.first().shown()).isEqualTo("Libreria")
    if (all.size == 1) return null
    val node = all.last()
    val layout = node.layoutResult()
    assertWithMessage("$where: «${node.shown()}» sta su una riga").that(layout.lineCount).isEqualTo(1)
    assertWithMessage("$where: «${node.shown()}» non finisce con i puntini").that(layout.isLineEllipsized(0)).isFalse()
    return node.shown()
  }

  private fun spoken(): String {
    val heading = composeRule
      .onAllNodes(hasAnyAncestor(hasTestTag(LabelTag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
      .fetchSemanticsNodes()
      .single()
    val description = heading.config.getOrNull(SemanticsProperties.ContentDescription)
    return description?.joinToString(", ") ?: heading.config[SemanticsProperties.Text].joinToString(", ") { it.text }
  }

  @Test
  fun doveCeSpazioIlRiepilogoEIntero() {
    boxWidth = WideLabelWidth
    showLabel()
    settle()
    assertThat(wholeSummary("320 dp")).isEqualTo(FullSummary)
    assertThat(spoken()).isEqualTo("Libreria, $FullSummary")
  }

  /** Il caso della segnalazione: a 360 dp con quattro tasti la forma intera non entra, e non resta «1.817 foto, 18…»: o una forma piu' corta intera, o niente. */
  private fun checkPhone(dark: Boolean, fontScale: Float) {
    showLabel(dark = dark, fontScale = fontScale)
    settle()
    val shown = wholeSummary("132 dp, scuro=$dark, testo $fontScale")
    assertWithMessage("la forma intera non entra in 96 dp di testo (la premessa della prova)").that(shown).isNotEqualTo(FullSummary)
    if (shown != null) assertThat(shown).isIn(SummaryForms)
    // TalkBack legge sempre il riepilogo per esteso, anche quando a schermo c'e' una forma corta o niente.
    assertThat(spoken()).isEqualTo("Libreria, $FullSummary")
  }

  @Test
  fun sulTelefonoStrettoIlRiepilogoEUnaFormaInteraONiente() = checkPhone(dark = false, fontScale = 1f)

  @Test
  fun sulTelefonoStrettoIlRiepilogoEUnaFormaInteraONienteInScuro() = checkPhone(dark = true, fontScale = 1f)

  @Test
  fun conIlTestoGrandeIlRiepilogoEUnaFormaInteraONiente() = checkPhone(dark = false, fontScale = LargeText)

  /** Se nemmeno la forma piu' stretta entra, la seconda riga non c'e': il titolo resta solo. */
  @Test
  fun seNessunaFormaEntraNonCeLaSecondaRiga() {
    boxWidth = TinyLabelWidth
    showLabel()
    settle()
    assertThat(wholeSummary("64 dp")).isNull()
    assertThat(spoken()).isEqualTo("Libreria, $FullSummary")
  }

  /** La forma segue la larghezza: stretta, torna la forma intera quando lo spazio torna. */
  @Test
  fun laFormaSegueLaLarghezza() {
    boxWidth = WideLabelWidth
    showLabel()
    settle()
    assertThat(wholeSummary("larga")).isEqualTo(FullSummary)
    composeRule.runOnIdle { boxWidth = TinyLabelWidth }
    settle()
    assertThat(wholeSummary("stretta")).isNull()
    composeRule.runOnIdle { boxWidth = WideLabelWidth }
    settle()
    assertThat(wholeSummary("di nuovo larga")).isEqualTo(FullSummary)
  }

  /** Senza forme (il luogo di una sezione) il sottotitolo si tronca con i puntini come prima: non sparisce. */
  @Test
  fun ilLuogoSiTroncaConIPuntiniComePrima() {
    forms = null
    boxWidth = TinyLabelWidth
    showLabel()
    settle()
    val all = lines()
    assertThat(all).hasSize(2)
    assertThat(all.last().layoutResult().isLineEllipsized(0)).isTrue()
  }

  /** Un riepilogo senza forme corte ma «intero o niente» (lista vuota): intero se entra, se no manca. */
  @Test
  fun unRiepilogoSenzaFormeCorteEInteroONiente() {
    forms = emptyList()
    boxWidth = PhoneLabelWidth
    showLabel()
    settle()
    assertThat(wholeSummary("senza forme corte")).isNull()
    composeRule.runOnIdle { boxWidth = WideLabelWidth }
    settle()
    assertThat(wholeSummary("larga")).isEqualTo(FullSummary)
  }

  private companion object {
    const val LabelTag = "etichetta"
    const val FullSummary = "1.817 foto, 183 video"

    /** Le forme piu' corte, dalla piu' lunga alla piu' stretta: il totale con il suo plurale e il solo numero. */
    val SummaryForms = listOf(FullSummary, "2.000 elementi", "2.000")
    val ShorterForms = SummaryForms.drop(1)

    /** Sul telefono a 360 dp: 360 - 24 di margini - 196 della capsula con quattro tasti - 8 fra i pezzi. */
    val PhoneLabelWidth: Dp = 132.dp
    val WideLabelWidth: Dp = 320.dp
    val TinyLabelWidth: Dp = 64.dp
    const val LargeText = 1.3f
    const val SettleMillis = 1_500L
  }
}
