package dev.pampa.mycloudgallery.feature.integrations.picker

import android.annotation.SuppressLint
import android.os.Build
import android.view.Window

/**
 * La difesa delle activity d'ingresso dalle sovrapposizioni di un'altra app (tapjacking, R7 della 4B, R8 della revisione di sicurezza 5B): la
 * finestra non prende i tocchi che passano attraverso una sovrapposizione, e da Android 12 le sovrapposizioni di altre app si nascondono del tutto.
 * Un'app che disegna sopra la griglia non fa scegliere una foto, ne' premere "Scegli" o "Salva sul NAS", al posto dell'utente.
 *
 * `HIDE_OVERLAY_WINDOWS` non e' nel manifest di questo modulo (nessun permesso nuovo qui, §4.3): lo porta `core-security` nel manifest di `:app`.
 * Senza, `setHideOverlayWindows` lancia e resta il filtro dei tocchi oscurati.
 */
internal object OverlayShield {

  @SuppressLint("MissingPermission")
  fun protect(window: Window) {
    window.decorView.filterTouchesWhenObscured = true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      try {
        window.setHideOverlayWindows(true)
      } catch (_: SecurityException) {
        // Resta il filtro dei tocchi oscurati.
      }
    }
  }
}
