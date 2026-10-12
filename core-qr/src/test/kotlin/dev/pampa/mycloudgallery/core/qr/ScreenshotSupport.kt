package dev.pampa.mycloudgallery.core.qr

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import java.util.Base64
import kotlin.random.Random
import org.junit.rules.TestRule

/**
 * Dove e come si chiamano gli screenshot del pacchetto P5.
 *
 * `gradlew :core-qr:recordRoborazziDebug` scrive i PNG in `docs/screenshots/2-P5/` (il percorso e' relativo
 * alla cartella del modulo, che e' la directory di lavoro dei test). Nei normali `testDebugUnitTest` i test
 * verificano solo che le schermate compongano e non scrivono niente.
 *
 * Il nome e' `<schermata>-<chiaro|scuro>-<telefono|tablet>[-testo-grande]`, come nel pacchetto P0.
 */
internal object P5Shots {

  const val DIR = "../docs/screenshots/2-P5"

  const val PHONE = "telefono"
  const val TABLET = "tablet"

  /** Il testo al massimo di Android (2,0): il caso peggiore per un layout. */
  const val LARGE_TEXT = 2f

  fun path(name: String, dark: Boolean, device: String, largeText: Boolean): String {
    val look = if (dark) "scuro" else "chiaro"
    val suffix = if (largeText) "-testo-grande" else ""
    return "$DIR/$name-$look-$device$suffix.png"
  }

  /** Chiaro e scuro: i due parametri di ogni classe di screenshot. */
  fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))

  /** Un invito verosimile (circa 250 caratteri): prefisso e base64url. */
  val invite: String = "MCG1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(Random(11).nextBytes(180))

  /** Una chiave di dispositivo: corta, quindi un QR rado. */
  const val deviceKey: String = "MCGK1:K7QD2M9XFA4T"

  /** Il tetto: 600 caratteri, un QR fitto. */
  val max600: String = Base64.getUrlEncoder().withoutPadding().encodeToString(Random(12).nextBytes(450)).take(QrLimits.MAX_CHARS)
}

/** Disegna [content] come la disegnerebbe una schermata vera: tema, sfondo della pagina e margini laterali. */
internal fun AndroidComposeTestRule<out TestRule, ComponentActivity>.shoot(
  path: String,
  dark: Boolean,
  fontScale: Float? = null,
  content: @Composable () -> Unit,
) {
  setContent {
    MyCloudGalleryTheme(settings = EngineSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
      val density = LocalDensity.current
      val provided = if (fontScale == null) density else Density(density.density, fontScale)
      CompositionLocalProvider(LocalDensity provides provided) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = GalleryDimens.Gutter),
          contentAlignment = Alignment.TopCenter,
        ) {
          content()
        }
      }
    }
  }
  waitForIdle()
  onRoot().captureRoboImage(path)
}

/**
 * Una fotocamera finta per gli screenshot: una scena grigia con un QR un po' storto, come lo terrebbe in mano
 * qualcuno. Niente colori scritti a mano: la scena e' fatta con i ruoli del tema.
 */
@Composable
internal fun BoxScope.FakeCameraFeed(content: String = P5Shots.invite) {
  val scheme = MaterialTheme.colorScheme
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Brush.verticalGradient(listOf(scheme.surfaceVariant, scheme.outlineVariant))),
    contentAlignment = Alignment.Center,
  ) {
    QrCode(
      content = content,
      contentDescription = "",
      secure = false,
      modifier = Modifier
        .size(170.dp)
        .graphicsLayer { rotationZ = 6f },
    )
  }
}
