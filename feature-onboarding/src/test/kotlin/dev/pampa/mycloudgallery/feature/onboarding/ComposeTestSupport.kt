package dev.pampa.mycloudgallery.feature.onboarding

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.robolectric.Shadows.shadowOf

/** Le due finestre su cui si guarda l'onboarding: un telefono alto (412 dp) e un tablet in orizzontale (1280 dp). */
internal object Devices {
  const val PHONE = "w412dp-h915dp-xhdpi"
  const val PHONE_TALL = "w412dp-h2300dp-xhdpi"

  /** Il telefono degli screenshot per l'utente (K4): il suo Galaxy S25, 360 x 780 dp. Non 412 dp, dove entrano piu' cose che sul suo schermo. */
  const val SHOT_PHONE = "w360dp-h780dp-xhdpi"
  const val SHOT_PHONE_TALL = "w360dp-h2300dp-xhdpi"
  const val TABLET = "w1280dp-h800dp-land-xhdpi"
  const val LARGE_TEXT = 1.3f
}

/**
 * La base dei test di Compose con Robolectric: la regola di Compose con l'activity vuota registrata (non e' nel
 * manifest dell'app) e con la finestra accelerata, come un'activity vera: il vetro della barra usa `RuntimeShader`,
 * che un Canvas software rifiuta.
 */
internal abstract class OnboardingComposeBase {

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

  /** La finestra dell'activity di prova: serve a controllare `FLAG_SECURE` e i tocchi "oscurati". */
  protected var window: Window? = null

  /** `FLAG_SECURE` e' impostato adesso sulla finestra? (niente screenshot, niente anteprima nei recenti) */
  protected fun isSecure(): Boolean =
    ((window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE) != 0

  /** I tocchi arrivati attraverso una sovrapposizione si scartano? (difesa dal tapjacking) */
  protected fun discardsObscuredTouches(): Boolean = window?.decorView?.filterTouchesWhenObscured == true

  /** Disegna [content] nel tema della galleria (chiaro o scuro) e aspetta che sia fermo. */
  protected fun show(dark: Boolean = false, fontScale: Float? = null, content: @Composable () -> Unit) {
    composeRule.setContent {
      val context = LocalContext.current
      SideEffect { window = context.findActivity()?.window }
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
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}
