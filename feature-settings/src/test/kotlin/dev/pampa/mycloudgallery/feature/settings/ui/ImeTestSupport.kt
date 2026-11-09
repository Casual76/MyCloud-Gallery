package dev.pampa.mycloudgallery.feature.settings.ui

import android.graphics.Insets
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.activity.ComponentActivity

/**
 * La tastiera aperta, per le prove: il sistema dice alla vista di Compose che l'IME occupa [heightPx] dal fondo e che si vede. Robolectric non disegna una tastiera ne' manda gli inset da
 * solo, e la finestra della decorazione non li passa ai figli: si consegnano direttamente alla vista di Compose (`AndroidComposeView`), che li legge per `WindowInsets.ime` e `imePadding`.
 */
internal fun ComponentActivity.openKeyboard(heightPx: Int) {
  val insets = WindowInsets.Builder()
    .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, heightPx))
    .setVisible(WindowInsets.Type.ime(), true)
    .build()
  fun walk(view: View) {
    if (view.javaClass.simpleName == "AndroidComposeView") view.dispatchApplyWindowInsets(insets)
    if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
  }
  walk(window.decorView)
}
