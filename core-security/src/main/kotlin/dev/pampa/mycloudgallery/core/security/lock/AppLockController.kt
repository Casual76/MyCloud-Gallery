package dev.pampa.mycloudgallery.core.security.lock

import android.os.SystemClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** L'orologio del blocco: monotono, indipendente da data e ora impostate dall'utente. */
fun interface MonotonicClock {
  fun elapsedRealtimeMillis(): Long
}

/** `SystemClock.elapsedRealtime()`: conta anche con il telefono in sospensione e non si puo' spostare a mano. */
object SystemMonotonicClock : MonotonicClock {
  override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
}

/** Lo stato del blocco, tutto in memoria: dopo un riavvio del processo si riparte sempre da [LOCKED] (se attivo). */
enum class AppLockState {
  /** Le impostazioni non sono ancora state lette: non si mostra niente (ne' contenuto ne' schermata di blocco). */
  LOADING,
  LOCKED,
  UNLOCKED,
}

/**
 * La macchina a stati del blocco app (piano 1.5; docs/sicurezza.md C14, E-S4, E-H3). Logica pura, senza
 * Android a parte l'orologio: la provano i test unitari.
 *
 * Regole:
 * - il blocco usa SEMPRE la credenziale di sistema; qui non esiste nessun PIN dell'app e **nessun
 *   contatore di tentativi falliti** (E-S4): i tentativi li limita il sistema e non si cancella nulla;
 * - a freddo, con il blocco attivo, si parte da [AppLockState.LOCKED];
 * - uscendo dall'app si ricorda l'istante; tornando, se e' passato almeno il timeout (`>=`, quindi con
 *   [AppLockTimeout.IMMEDIATELY] sempre) si richiude. Un orologio che va indietro richiude (fail closed);
 * - durante lo sblocco la schermata di sistema porta l'app in secondo piano: in quel periodo non si
 *   ricorda nessuna uscita (altrimenti con "subito" lo sblocco riuscito si richiuderebbe da solo).
 *
 * I metodi vanno chiamati dal thread principale; sono comunque sincronizzati.
 */
class AppLockController(private val clock: MonotonicClock = SystemMonotonicClock) {

  private val _state = MutableStateFlow(AppLockState.LOADING)
  private val _settings = MutableStateFlow<AppLockSettings?>(null)

  /** Stato corrente. */
  val state: StateFlow<AppLockState> = _state.asStateFlow()

  /** Le impostazioni correnti, o `null` finche' non sono state lette. */
  val settings: StateFlow<AppLockSettings?> = _settings.asStateFlow()

  private var backgroundedAt: Long? = null
  private var authenticating = false

  /** Vero mentre e' in corso uno sblocco (la schermata di sistema e' aperta). */
  val isAuthenticating: Boolean @Synchronized get() = authenticating

  /**
   * Applica le impostazioni. La prima volta decide da dove si parte (bloccato se attivo). Dopo, accendere
   * il blocco mentre si e' dentro l'app non la richiude (l'utente e' appena stato autenticato dalla
   * schermata di impostazioni); spegnerlo la sblocca.
   */
  @Synchronized
  fun updateSettings(settings: AppLockSettings) {
    val first = _settings.value == null
    _settings.value = settings
    when {
      !settings.isEnabled -> {
        _state.value = AppLockState.UNLOCKED
        backgroundedAt = null
      }
      first -> _state.value = AppLockState.LOCKED
      else -> Unit
    }
  }

  /** Segue un flusso di impostazioni (di norma [AppLockSettingsStore.settings]). Non termina. */
  suspend fun follow(settings: Flow<AppLockSettings>) {
    settings.collect { updateSettings(it) }
  }

  /** Richiude subito (es. voce "Blocca ora"). Senza effetto se il blocco e' spento. */
  @Synchronized
  fun lockNow() {
    if (_settings.value?.isEnabled == true) {
      _state.value = AppLockState.LOCKED
      backgroundedAt = null
    }
  }

  /** L'app esce dal primo piano (non per un cambio di configurazione come la rotazione). */
  @Synchronized
  fun onAppBackgrounded() {
    if (authenticating) return
    // Conta la PRIMA uscita: un secondo evento senza ritorno in mezzo non deve allungare il tempo concesso.
    if (_settings.value?.isEnabled == true && _state.value == AppLockState.UNLOCKED && backgroundedAt == null) {
      backgroundedAt = clock.elapsedRealtimeMillis()
    }
  }

  /** L'app torna in primo piano: richiude se e' passato il timeout. */
  @Synchronized
  fun onAppForegrounded() {
    val since = backgroundedAt ?: return
    backgroundedAt = null
    val current = _settings.value ?: return
    if (!current.isEnabled || _state.value != AppLockState.UNLOCKED) return
    val elapsed = clock.elapsedRealtimeMillis() - since
    if (elapsed < 0 || elapsed >= current.timeout.millis) _state.value = AppLockState.LOCKED
  }

  /** Inizia uno sblocco. Falso se non si e' bloccati o ce n'e' gia' uno in corso. */
  @Synchronized
  fun beginAuthentication(): Boolean {
    if (_state.value != AppLockState.LOCKED || authenticating) return false
    authenticating = true
    return true
  }

  /** Chiude lo sblocco; se riuscito, sblocca. */
  @Synchronized
  fun endAuthentication(success: Boolean) {
    authenticating = false
    if (success && _settings.value?.isEnabled == true) {
      _state.value = AppLockState.UNLOCKED
      backgroundedAt = null
    }
  }
}
