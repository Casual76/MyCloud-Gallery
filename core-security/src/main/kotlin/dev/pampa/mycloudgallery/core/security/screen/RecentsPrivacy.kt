package dev.pampa.mycloudgallery.core.security.screen

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import java.util.WeakHashMap

/**
 * L'anteprima dell'app nella schermata dei recenti, oscurata quando il blocco app e' attivo (piano 1.5:
 * senza questo, chi guarda i recenti vede le foto anche con l'app bloccata).
 *
 * - **Android 13+** (`setRecentsScreenshotEnabled(false)`): solo l'anteprima dei recenti e' vuota; gli
 *   screenshot normali funzionano. Tutti i dispositivi della famiglia sono qui.
 * - **Android 10-12**: l'API non esiste. Ripiego: `FLAG_SECURE` finche' la protezione e' attiva, che
 *   oscura i recenti ma blocca anche gli screenshot. Scelta dichiarata in docs/sicurezza.md §8: meglio un
 *   comportamento piu' rigido su un telefono vecchio che una finestra dei recenti che rivela le foto.
 */
class RecentsPrivacy internal constructor(private val sdkInt: Int) {

  private val fallbackHandles = WeakHashMap<Activity, ProtectionHandle>()

  /** Nasconde (`true`) o mostra (`false`) l'anteprima di [activity] nei recenti. Idempotente. */
  fun setHidden(activity: Activity, hidden: Boolean) {
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
      setRecentsScreenshotEnabled(activity, !hidden)
      return
    }
    synchronized(fallbackHandles) {
      val current = fallbackHandles[activity]
      if (hidden && current == null) {
        fallbackHandles[activity] = WindowProtection.of(activity.window).acquireSecureContent()
      } else if (!hidden && current != null) {
        current.close()
        fallbackHandles.remove(activity)
      }
    }
  }

  // Il controllo di versione e' nel chiamante (`sdkInt` e' un parametro per poterlo provare).
  @SuppressLint("NewApi")
  private fun setRecentsScreenshotEnabled(activity: Activity, enabled: Boolean) {
    activity.setRecentsScreenshotEnabled(enabled)
  }

  companion object {
    /** Quello da usare nell'app. */
    val instance: RecentsPrivacy by lazy { RecentsPrivacy(Build.VERSION.SDK_INT) }
  }
}
