package dev.pampa.mycloudgallery.core.security.device

import android.app.KeyguardManager
import android.content.Context

/** Il telefono ha un blocco schermo vero? */
enum class ScreenLockStatus {
  /** PIN, sequenza o password impostati. */
  SECURE,

  /** Nessun blocco schermo, o solo "scorri": chiunque prenda il telefono entra (E-S3). */
  NOT_SECURE,

  /** Il sistema non risponde (non succede su un telefono vero). Non si avvisa. */
  UNKNOWN,
  ;

  /** Va mostrato l'avviso "il telefono non ha un blocco schermo" (E-S3)? Solo quando e' certo. */
  val needsWarning: Boolean get() = this == NOT_SECURE
}

/**
 * Avviso "telefono senza blocco schermo" (piano E-S3; docs/sicurezza.md C14, scenario S1). Senza blocco
 * schermo la cifratura del telefono protegge poco e le credenziali dell'app sono alla portata di chiunque
 * lo prenda in mano. Si legge ogni volta (puo' cambiare in qualunque momento): all'avvio e a ogni ritorno
 * in primo piano.
 *
 * `KeyguardManager.isDeviceSecure` (API 23) guarda il blocco schermo dell'utente corrente: PIN, sequenza o
 * password. Non conta la sola SIM bloccata, e "scorri" o nessun blocco risultano non sicuri.
 */
object ScreenLockCheck {

  fun status(context: Context): ScreenLockStatus {
    val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return ScreenLockStatus.UNKNOWN
    return if (keyguard.isDeviceSecure) ScreenLockStatus.SECURE else ScreenLockStatus.NOT_SECURE
  }
}
