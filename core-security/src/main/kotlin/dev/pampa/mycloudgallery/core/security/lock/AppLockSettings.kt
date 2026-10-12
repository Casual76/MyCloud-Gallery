package dev.pampa.mycloudgallery.core.security.lock

import dev.pampa.mycloudgallery.core.security.AppLockMode

/**
 * Dopo quanto tempo in background l'app si richiude (piano 1.5, "timeout configurabile").
 *
 * Il tempo si misura con l'orologio *monotono* del sistema (`elapsedRealtime`), non con l'ora: cambiare
 * data e ora del telefono non allunga il timeout. Il default e' un minuto: abbastanza per passare a
 * un'altra app e tornare senza rifare lo sblocco, abbastanza poco per un telefono lasciato su un tavolo.
 */
enum class AppLockTimeout(val millis: Long) {
  /** Ogni volta che si esce dall'app (il piu' sicuro; anche il ripiego se il valore salvato non si legge). */
  IMMEDIATELY(0L),
  AFTER_30_SECONDS(30_000L),
  AFTER_1_MINUTE(60_000L),
  AFTER_5_MINUTES(300_000L),
  AFTER_15_MINUTES(900_000L),
  ;

  companion object {
    val DEFAULT: AppLockTimeout = AFTER_1_MINUTE
  }
}

/** Le impostazioni del blocco app. [mode] ha solo due valori: la credenziale di sistema o niente. */
data class AppLockSettings(
  val mode: AppLockMode = AppLockMode.OFF,
  val timeout: AppLockTimeout = AppLockTimeout.DEFAULT,
) {
  val isEnabled: Boolean get() = mode == AppLockMode.SYSTEM_CREDENTIAL

  companion object {
    /**
     * Cosa si applica quando le impostazioni non si leggono (file danneggiato, errore di I/O): blocco ATTIVO
     * e senza tolleranza. Meglio chiedere lo sblocco una volta di troppo che lasciare l'app aperta in
     * silenzio. L'utente lo spegne dalle impostazioni, dopo essersi sbloccato.
     */
    val FAIL_CLOSED = AppLockSettings(AppLockMode.SYSTEM_CREDENTIAL, AppLockTimeout.IMMEDIATELY)
  }
}
