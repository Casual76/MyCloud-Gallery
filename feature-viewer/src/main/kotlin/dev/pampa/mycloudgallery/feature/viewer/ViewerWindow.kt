package dev.pampa.mycloudgallery.feature.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** L'activity che contiene [this], risalendo i `ContextWrapper`. Null in una composizione senza activity. */
internal fun Context.findActivity(): Activity? {
  var current: Context? = this
  while (current is ContextWrapper) {
    if (current is Activity) return current
    current = current.baseContext
  }
  return null
}

/**
 * Il viewer immersivo: con [immersive] le barre di sistema si nascondono (e tornano con uno swipe dal bordo, in modo
 * transitorio), senza lasciare niente nascosto quando il viewer esce dalla composizione.
 *
 * Il chrome e le barre di sistema vanno insieme: un tocco sulla foto mostra o nasconde tutte e due, e lo sfondo passa
 * al nero immersivo (lo cura il chiamante).
 */
@Composable
internal fun ImmersiveSystemBars(immersive: Boolean) {
  val view = LocalView.current
  val window = LocalContext.current.findActivity()?.window ?: return
  val controller = remember(window, view) { WindowCompat.getInsetsController(window, view) }
  LaunchedEffect(immersive, controller) {
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    if (immersive) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
  }
  DisposableEffect(controller) {
    onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
  }
}

/**
 * Ultra HDR (E-V4): mentre la foto in primo piano ha una gain map ([hdr]) la finestra passa a `COLOR_MODE_HDR`, e
 * torna com'era quando la foto esce di vista o il viewer si chiude. Dall'API 34 (Android 14), dove l'Ultra HDR esiste;
 * prima la finestra non si tocca. I tasselli dello zoom possono essere SDR (limite dichiarato, T-30).
 */
@Composable
internal fun HdrWindowMode(hdr: Boolean) {
  val window: Window = LocalContext.current.findActivity()?.window ?: return
  DisposableEffect(window, hdr) {
    val previous = window.colorMode
    val applies = hdr && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    if (applies) window.colorMode = ActivityInfo.COLOR_MODE_HDR
    onDispose { if (applies) window.colorMode = previous }
  }
}
