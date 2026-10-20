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

  /** La `MainActivity` e' in primo piano (fra `ON_START` e `ON_STOP`). Le activity d'ingresso (selettore, ricezione) non lo cambiano. */
  private var foreground = false

  /** P14: l'istante (monotono) dell'ultimo sblocco fatto da un'activity d'ingresso con la `MainActivity` fuori dal primo piano. */
  private var entryUnlockedAt: Long? = null

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
    foreground = false
    if (authenticating) return
    // Conta la PRIMA uscita: un secondo evento senza ritorno in mezzo non deve allungare il tempo concesso.
    if (_settings.value?.isEnabled == true && _state.value == AppLockState.UNLOCKED && backgroundedAt == null) {
      backgroundedAt = clock.elapsedRealtimeMillis()
    }
  }

  /** L'app torna in primo piano: richiude se e' passato il timeout. */
  @Synchronized
  fun onAppForegrounded() {
    foreground = true
    // La tolleranza di uno sblocco d'ingresso vale solo fuori dall'app: entrando, vale il timeout scelto dall'utente.
    entryUnlockedAt = null
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

  /**
   * P14 (nota di P10): il blocco valutato **adesso**, per le porte d'ingresso (il selettore, il provider dei documenti, la ricezione) che
   * lavorano mentre la `MainActivity` e' in secondo piano. Senza, il blocco si richiudeva solo al ritorno della `MainActivity`
   * ([onAppForegrounded]): con la `MainActivity` in secondo piano l'app restava sbloccata per sempre per le altre app.
   *
   * Sbloccata adesso = blocco spento, oppure sbloccata e (la `MainActivity` e' in primo piano, o dall'uscita non e' passato il timeout).
   * Passato il timeout l'app **si richiude qui** (anche per la `MainActivity`, che al ritorno trova la schermata di blocco). Dopo uno sblocco
   * fatto da un'activity d'ingresso vale una finestra di tolleranza di [ENTRY_GRACE_MS] (anche con "Subito"): un'altra app che sfoglia i
   * documenti subito dopo lo sblocco non lo richiede a ogni cartella. Un orologio che torna indietro richiude (fail closed); impostazioni non
   * ancora lette = non sbloccata.
   */
  @Synchronized
  fun isUnlockedNow(): Boolean {
    val current = _settings.value ?: return false
    if (!current.isEnabled) return true
    if (_state.value != AppLockState.UNLOCKED) return false
    val since = backgroundedAt ?: return true
    val elapsed = clock.elapsedRealtimeMillis() - since
    val limit = if (entryUnlockedAt == since) maxOf(current.timeout.millis, ENTRY_GRACE_MS) else current.timeout.millis
    if (elapsed < 0 || elapsed >= limit) {
      _state.value = AppLockState.LOCKED
      backgroundedAt = null
      entryUnlockedAt = null
      return false
    }
    return true
  }

  /**
   * P14: chiude uno sblocco chiesto da un'activity d'ingresso (selettore, ricezione) con la credenziale di sistema. Se riuscito sblocca; con la
   * `MainActivity` fuori dal primo piano l'app resta "fuori" (il tempo conta da adesso) con la finestra di tolleranza di [ENTRY_GRACE_MS].
   */
  @Synchronized
  fun endEntryAuthentication(success: Boolean) {
    authenticating = false
    if (!success || _settings.value?.isEnabled != true) return
    _state.value = AppLockState.UNLOCKED
    if (foreground) {
      backgroundedAt = null
      entryUnlockedAt = null
    } else {
      val now = clock.elapsedRealtimeMillis()
      backgroundedAt = now
      entryUnlockedAt = now
    }
  }

  companion object {
    /** La tolleranza dopo uno sblocco d'ingresso (proposta di P10): due minuti di orologio monotono. */
    const val ENTRY_GRACE_MS: Long = 120_000L
  }
}
