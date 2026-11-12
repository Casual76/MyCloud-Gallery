package dev.pampa.mycloudgallery.core.designsystem.design

import android.app.Application
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto
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
 * Fase I, CU-2: il nome sopra una copertina si legge anche su una foto **bianca** (neve, cielo), il caso peggiore. Si misura sui pixel, nella fascia
 * a sinistra delle lettere (stessa altezza, dentro il margine dell'etichetta: lo scrim e' uguale per tutta la larghezza), riga per riga dall'alto al
 * basso del titolo e della riga piccola: il bianco contro quel fondo deve avere almeno 4,5:1. Con la sfumatura di prima (dallo 0 allo scrim pieno
 * dietro le righe stesse) l'alto del titolo stava sotto 2,6:1 e la prova cade.
 *
 * E CS-6: di serie la scheda si legge una volta sola (la sua descrizione), i testi non si rileggono.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class GalleryCoverCardContrastTest {

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

  private val darkState = mutableStateOf(false)
  private val compactState = mutableStateOf(false)
  private var host: View? = null
  private var started = false

  private fun stage(dark: Boolean, compact: Boolean = false) {
    darkState.value = dark
    compactState.value = compact
    if (!started) {
      started = true
      composeRule.setContent {
        MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (darkState.value) ThemeMode.DARK else ThemeMode.LIGHT)) {
          host = LocalView.current
          Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            val compact = compactState.value
            GalleryCoverCard(
              // La scheda piccola di «Tutte le persone» (griglia a tre su 360 dp): titolo di una riga e sfumatura corta.
              modifier = Modifier.size(if (compact) 100.dp else 180.dp, if (compact) 133.dp else 240.dp).semantics(mergeDescendants = true) { contentDescription = SPOKEN },
              title = TITLE,
              caption = if (compact) null else CAPTION,
              titleMaxLines = if (compact) 1 else 2,
              isNew = true,
              fadeHeight = if (compact) GalleryCoverCardDefaults.FadeHeightCompact else GalleryCoverCardDefaults.FadeHeight,
            ) {
              // La copertina peggiore: tutta bianca.
              Box(Modifier.fillMaxSize().background(GalleryOnPhoto.Content))
            }
          }
        }
      }
    }
    composeRule.waitForIdle()
  }

  private fun bitmap(): Bitmap {
    val view = host!!
    return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
  }

  private fun boundsOf(text: String): Rect = composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

  /** Il contrasto peggiore del bianco contro il fondo, riga per riga, nella fascia appena a sinistra delle lettere di [text]. */
  private fun worstContrastBeside(text: String, bitmap: Bitmap): Float {
    val b = boundsOf(text)
    val x = (b.left - GUTTER_PX).toInt()
    var worst = Float.MAX_VALUE
    for (y in b.top.toInt() until b.bottom.toInt()) {
      val ratio = GalleryLegibility.contrast(GalleryOnPhoto.Content, Color(bitmap.getPixel(x, y)))
      if (ratio < worst) worst = ratio
    }
    return worst
  }

  @Test
  fun suUnaCopertinaBiancaIlTitoloELaRigaPiccolaReggonoIl4e5() {
    for (dark in listOf(false, true)) {
      stage(dark)
      val pixels = bitmap()
      val title = worstContrastBeside(TITLE, pixels)
      val caption = worstContrastBeside(CAPTION, pixels)
      assertWithMessage("scuro=$dark: titolo $title:1").that(title).isAtLeast(4.5f)
      assertWithMessage("scuro=$dark: riga piccola $caption:1").that(caption).isAtLeast(4.5f)
    }
  }

  @Test
  fun conLaSfumaturaCortaIlTitoloReggeComePrima() {
    // fadeHeight compatto (I1, i volti piccoli): la sfumatura sale meno, lo scrim dietro la riga e' lo stesso.
    for (dark in listOf(false, true)) {
      stage(dark, compact = true)
      val title = worstContrastBeside(TITLE, bitmap())
      assertWithMessage("compatta, scuro=$dark: titolo $title:1").that(title).isAtLeast(4.5f)
    }
  }

  @Test
  fun diSerieLaSchedaSiLeggeUnaVoltaSola() {
    stage(dark = false)
    // La descrizione e' quella della scheda; il titolo e la riga piccola si vedono ma TalkBack non li rilegge.
    composeRule.onNodeWithContentDescription(SPOKEN).assertExists()
    for (text in listOf(TITLE, CAPTION)) {
      composeRule.onNodeWithText(text, useUnmergedTree = true).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
    }
  }

  private companion object {
    const val TITLE = "Neve sulle Alpi"
    const val CAPTION = "Gennaio 2026"
    const val SPOKEN = "Neve sulle Alpi, gennaio 2026, 48 foto"
    const val GUTTER_PX = 4f
  }
}
