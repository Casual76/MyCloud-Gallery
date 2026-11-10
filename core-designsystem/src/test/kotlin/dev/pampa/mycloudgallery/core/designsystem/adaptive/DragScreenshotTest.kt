package dev.pampa.mycloudgallery.core.designsystem.adaptive

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fase 10 (P6, D20, D21): come si vede il trascinamento. L'**ombra** (la miniatura locale in un quadrato con gli angoli arrotondati, il numero se sono piu' d'uno, un riquadro neutro se la
 * miniatura non c'e') e le **destinazioni evidenziate** (un filo quando un trascinamento che si puo' ricevere e' in corso, piu' marcato quando ci passa sopra). Chiaro e scuro.
 * `gradlew :core-designsystem:recordRoborazziDebug --tests "*DragScreenshot*"` scrive in `docs/screenshots/10-P6/`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h520dp-xhdpi")
internal class DragScreenshotTest(private val dark: Boolean) {

  @get:Rule
  val composeRule = createComposeRule()

  /** Una miniatura di prova: un gradiente (niente dati dell'utente). */
  private fun thumbnail(): Bitmap {
    val bitmap = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888)
    val paint = Paint().apply { shader = LinearGradient(0f, 0f, 240f, 180f, intArrayOf(0xFF3A6EA5.toInt(), 0xFFE0A458.toInt()), null, Shader.TileMode.CLAMP) }
    Canvas(bitmap).drawRect(0f, 0f, 240f, 180f, paint)
    return bitmap
  }

  @Composable
  private fun Shadow(label: String, shadow: MediaDragShadow) {
    val colors = MaterialTheme.colorScheme
    val bitmap = MediaDragShadows.bitmap(shadow, 288, ShadowColors(colors.surfaceVariant.toArgb(), colors.primary.toArgb(), colors.onPrimary.toArgb()))
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(96.dp))
      Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onBackground)
    }
  }

  @Composable
  private fun Destination(label: String, phase: DropPhase) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(72.dp)
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .dropOutline({ phase }, MaterialTheme.colorScheme.primary),
      contentAlignment = Alignment.Center,
    ) {
      Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }

  @Test
  fun capture() {
    val photo = thumbnail().asImageBitmap()
    composeRule.setContent {
      MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
        Column(
          modifier = Modifier.testTag("shot").background(MaterialTheme.colorScheme.background).padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          Text("L'ombra del trascinamento", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
          Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
            Shadow("Una foto", MediaDragShadow(photo, 1))
            Shadow("Dodici foto", MediaDragShadow(photo, 12))
            Shadow("Senza miniatura", MediaDragShadow(null, 3))
          }
          Text("Le destinazioni", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
          Destination("Libreria · a riposo", DropPhase.Idle)
          Destination("Album · si puo' ricevere", DropPhase.Offered)
          Destination("Cartella · il dito e' sopra", DropPhase.Over)
        }
      }
    }
    composeRule.waitForIdle()
    val look = if (dark) "scuro" else "chiaro"
    composeRule.onNodeWithTag("shot").captureRoboImage("../docs/screenshots/10-P6/ombra-e-destinazioni-$look.png")
  }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}
