package dev.pampa.mycloudgallery.core.qr

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `QrCode` sulla pagina, come la userebbero le schermate dell'invito: un invito normale, una chiave corta, il
 * tetto di 600 caratteri e il caso "non si puo' codificare". Chiaro e scuro (il QR resta nero su bianco in
 * entrambi), telefono e tablet (dove non supera i 400 dp), piu' il testo grande per la didascalia.
 *
 * `gradlew :core-qr:recordRoborazziDebug` scrive i PNG in `docs/screenshots/2-P5/`.
 */
internal abstract class QrScreenshotBase(private val dark: Boolean, private val device: String, private val largeText: Boolean) {

  @get:Rule
  val rule = createAndroidComposeRule<ComponentActivity>()

  private fun shoot(name: String, content: String, caption: String) {
    rule.shoot(
      path = P5Shots.path(name, dark, device, largeText),
      dark = dark,
      fontScale = if (largeText) P5Shots.LARGE_TEXT else null,
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        QrCode(content = content, contentDescription = caption, secure = true)
        Text(
          text = caption,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
    }
  }

  @Test
  fun invito() = shoot("qr-invito", P5Shots.invite, "QR d'invito per Marta")

  @Test
  fun chiaveDelDispositivo() = shoot("qr-chiave-dispositivo", P5Shots.deviceKey, "QR della chiave del dispositivo")

  @Test
  fun seicentoCaratteri() = shoot("qr-600-caratteri", P5Shots.max600, "QR da 600 caratteri, il massimo")

  @Test
  fun nonCodificabile() = shoot("qr-non-codificabile", "x".repeat(QrLimits.MAX_CHARS + 1), "QR non disponibile")

}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-xhdpi")
internal class QrPhoneScreenshotTest(dark: Boolean) : QrScreenshotBase(dark, P5Shots.PHONE, largeText = false) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = P5Shots.looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
internal class QrTabletScreenshotTest(dark: Boolean) : QrScreenshotBase(dark, P5Shots.TABLET, largeText = false) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = P5Shots.looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-xhdpi")
internal class QrPhoneLargeTextScreenshotTest(dark: Boolean) : QrScreenshotBase(dark, P5Shots.PHONE, largeText = true) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = P5Shots.looks()
  }
}
