package dev.pampa.mycloudgallery.core.security.screen

import android.annotation.SuppressLint
import android.os.Build
import android.view.Window
import android.view.WindowManager
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import java.util.WeakHashMap

/**
 * Un "permesso" di protezione preso su una finestra. Si rilascia con [close] (idempotente), di norma da
 * `DisposableEffect`/`onDispose`: la protezione resta finche' ce n'e' almeno uno in vita.
 */
fun interface ProtectionHandle : AutoCloseable {
  override fun close()
}

/**
 * Le protezioni di una singola finestra (la finestra dell'activity, o quella di un dialog/bottom sheet,
 * che ne ha una propria e NON eredita le flag dell'activity), a contatore: due schermate che chiedono
 * `FLAG_SECURE` si sommano, e il flag si toglie solo quando le ha rilasciate entrambe.
 *
 * - **Contenuto riservato** ([acquireSecureContent]): `FLAG_SECURE`. Niente screenshot, niente registrazione
 *   dello schermo, anteprima vuota nei recenti. Per Nascosti, campi password, QR (piano 1.5, E-S5).
 * - **Schermo anti-sovrapposizioni** ([acquireOverlayShield]): difesa dal tapjacking per conferme e password.
 *   Due livelli: `Window.setHideOverlayWindows(true)` (Android 12+, usa il permesso normale
 *   `HIDE_OVERLAY_WINDOWS`, docs/sicurezza.md 4-bis: il sistema nasconde gli overlay delle altre app
 *   finche' la finestra e' visibile) e `filterTouchesWhenObscured` sulla vista radice della finestra (i
 *   tocchi arrivati attraverso una finestra sovrapposta vengono scartati; vale anche prima di Android 12).
 *
 * Va usata dopo `setContentView`/`setContent` (toccare `decorView` prima fissa la finestra) e dal thread
 * principale. Se prima di noi la finestra aveva gia' `FLAG_SECURE` o `filterTouchesWhenObscured`, al
 * rilascio non li togliamo.
 */
class WindowProtection internal constructor(
  private val window: Window,
  private val sdkInt: Int,
) {

  private var secureCount = 0
  private var secureWasAlreadySet = false
  private var overlayCount = 0
  private var filterWasAlreadySet = false

  fun acquireSecureContent(): ProtectionHandle {
    if (secureCount++ == 0) {
      secureWasAlreadySet = (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
      window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    return once {
      if (--secureCount == 0 && !secureWasAlreadySet) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
      }
    }
  }

  fun acquireOverlayShield(): ProtectionHandle {
    if (overlayCount++ == 0) {
      filterWasAlreadySet = window.decorView.filterTouchesWhenObscured
      window.decorView.filterTouchesWhenObscured = true
      setHideOverlayWindows(true)
    }
    return once {
      if (--overlayCount == 0) {
        setHideOverlayWindows(false)
        window.decorView.filterTouchesWhenObscured = filterWasAlreadySet
      }
    }
  }

  /** Per i test e la Diagnostica: `FLAG_SECURE` e' impostato adesso su questa finestra? */
  val isSecure: Boolean get() = (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

  // `sdkInt` e' un parametro (i test simulano le versioni vecchie senza scaricare altri Android di Robolectric):
  // lint non puo' vedere il controllo, ma c'e' ed e' nella riga qui sotto.
  @SuppressLint("NewApi")
  private fun setHideOverlayWindows(hide: Boolean) {
    if (sdkInt < Build.VERSION_CODES.S) return
    try {
      window.setHideOverlayWindows(hide)
    } catch (e: SecurityException) {
      // Senza il permesso HIDE_OVERLAY_WINDOWS (normale, ma se qualcuno lo togliesse dal manifest) il sistema
      // lancia invece di ignorare. Non deve far cadere la schermata della password: resta il filtro dei tocchi.
      GalleryLog.e(TAG) { "HIDE_OVERLAY_WINDOWS mancante: sovrapposizioni non nascoste" }
    }
  }

  private fun once(release: () -> Unit): ProtectionHandle {
    var released = false
    return ProtectionHandle {
      if (!released) {
        released = true
        release()
      }
    }
  }

  companion object {
    private const val TAG = "WindowProtection"
    private val instances = WeakHashMap<Window, WindowProtection>()

    /** Le protezioni di [window] (una sola istanza per finestra, cosi' i contatori sono condivisi). */
    @JvmStatic
    fun of(window: Window): WindowProtection = synchronized(instances) {
      instances.getOrPut(window) { WindowProtection(window, Build.VERSION.SDK_INT) }
    }
  }
}
