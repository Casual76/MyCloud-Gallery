package dev.pampa.mycloudgallery.feature.library.hidden

import dev.pampa.mycloudgallery.core.security.lock.AuthAvailability
import dev.pampa.mycloudgallery.core.security.lock.AuthPromptText
import dev.pampa.mycloudgallery.core.security.lock.AuthResult
import dev.pampa.mycloudgallery.core.security.lock.MonotonicClock
import dev.pampa.mycloudgallery.core.security.lock.SystemCredentialAuthenticator
import dev.pampa.mycloudgallery.core.security.lock.SystemMonotonicClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Perche' i Nascosti sono rimasti chiusi: serve a dire all'utente cosa fare, non a decidere nulla. */
enum class HiddenDenial {
  /** L'utente ha chiuso la schermata di sistema. Si resta chiusi; ci si riprova con "Sblocca". */
  CANCELLED,

  /** Il sistema ha chiuso la schermata (l'app e' andata in secondo piano, rotazione...). */
  INTERRUPTED,

  /** Troppi tentativi sbagliati: il SISTEMA ha bloccato lo sblocco (E-S4). L'app non conta e non cancella niente. */
  LOCKED_OUT,

  /** Il telefono non ha un blocco schermo: i Nascosti non sarebbero protetti, quindi restano chiusi (fail closed). */
  NO_SCREEN_LOCK,

  /** Lo sblocco non e' disponibile adesso (hardware che non risponde, combinazione non supportata). */
  UNAVAILABLE,

  /** Altro errore del sistema. */
  ERROR,
}

/** Lo stato dei Nascosti rispetto alla credenziale. Solo [Unlocked] lascia leggere qualcosa. */
sealed interface HiddenAccess {
  data object Locked : HiddenAccess

  /** La schermata di sistema e' aperta. */
  data object Authenticating : HiddenAccess

  data object Unlocked : HiddenAccess

  /** Lo sblocco non e' riuscito: come [Locked], piu' il motivo. */
  data class Denied(val reason: HiddenDenial) : HiddenAccess
}

/**
 * Il cancello dei Nascosti **e degli Eliminati di recente** (docs/fase3-progetto.md §2.4, E-H3, E-S5): **la credenziale
 * di sistema a ogni ingresso, con un minuto di tolleranza**, la stessa per le due schermate: sbloccate una, si entra
 * nell'altra entro il minuto senza rifare lo sblocco (come in Apple Foto).
 *
 * - Si entra bloccati. Per aprire serve [unlock], cioe' l'impronta, il volto o il blocco schermo del telefono: mai un
 *   PIN dell'app, e nessun contatore di tentativi falliti (li limita il sistema, E-S4).
 * - Uscendo dalla schermata ([onHidden]) si ricorda l'istante. Rientrando ([onShown]) entro la tolleranza si resta
 *   sbloccati; oltre, o con un orologio che e' andato indietro, si richiude (fail closed). L'orologio e' quello
 *   **monotono** (`elapsedRealtime`): cambiare ora e data non allunga la tolleranza.
 * - Uno solo per processo: sopravvive alla destinazione di navigazione (cosi' la tolleranza vale anche uscendo dalla
 *   pagina e rientrando) ma non al riavvio del processo, che riparte sempre bloccato.
 * - Un telefono **senza blocco schermo** non puo' autenticare: i Nascosti restano chiusi (come l'app con il blocco attivo).
 * - Questo oggetto non sa nulla dei contenuti: non legge niente. Chi legge i nascosti ([HiddenLibrary]) chiede prima a
 *   lui se e' sbloccato.
 */
@Singleton
class HiddenAccessGate internal constructor(
  private val clock: MonotonicClock,
  /** Per quanto, dopo essere usciti dalla schermata, si puo' rientrare senza rifare lo sblocco. */
  val graceMillis: Long,
) {

  @Inject
  constructor() : this(SystemMonotonicClock, DEFAULT_GRACE_MS)

  private val _state = MutableStateFlow<HiddenAccess>(HiddenAccess.Locked)

  /** Lo stato corrente. */
  val state: StateFlow<HiddenAccess> = _state.asStateFlow()

  private var leftAt: Long? = null

  /** Quante schermate protette sono visibili adesso (Nascosti, Eliminati di recente): la tolleranza parte solo a zero. */
  private var visible = 0

  /** La richiesta di sblocco in corso, se c'e': chi ne chiede un'altra nello stesso momento aspetta questa. */
  private var inFlight: CompletableDeferred<HiddenAccess>? = null

  /**
   * Se i Nascosti si possono leggere **adesso**. Tiene conto anche della tolleranza gia' scaduta: se si e' usciti
   * da piu' di un minuto, anche prima che qualcuno richiami [onShown], non si legge niente.
   */
  val isUnlocked: Boolean @Synchronized get() = _state.value == HiddenAccess.Unlocked && !graceExpired()

  /**
   * Chiede la credenziale. Una sola richiesta alla volta: se ce n'e' una in corso (l'ha aperta un'altra schermata
   * protetta) si aspetta il suo esito, se e' gia' sbloccato non se ne apre un'altra. Restituisce lo stato dopo la
   * richiesta. **Fail closed**: qualunque cosa che non sia un successo esplicito lascia chiuso.
   */
  suspend fun unlock(authenticator: SystemCredentialAuthenticator, text: AuthPromptText): HiddenAccess {
    val waiting: CompletableDeferred<HiddenAccess>?
    synchronized(this) {
      when (_state.value) {
        HiddenAccess.Unlocked -> return _state.value
        HiddenAccess.Authenticating -> waiting = inFlight
        HiddenAccess.Locked, is HiddenAccess.Denied -> {
          _state.value = HiddenAccess.Authenticating
          inFlight = CompletableDeferred()
          waiting = null
        }
      }
    }
    if (waiting != null) return waiting.await()
    val availability = authenticator.availability()
    val result: AuthResult = if (availability != AuthAvailability.AVAILABLE) {
      AuthResult.NotAvailable(availability)
    } else {
      try {
        authenticator.authenticate(text)
      } catch (e: CancellationException) {
        // Il chiamante e' stato annullato (si e' usciti dalla pagina durante lo sblocco): non si e' sbloccato niente.
        finish(HiddenAccess.Denied(HiddenDenial.INTERRUPTED))
        throw e
      } catch (_: Exception) {
        AuthResult.Error(code = ERROR_CODE_UNKNOWN)
      }
    }
    return finish(
      when (result) {
        AuthResult.Success -> HiddenAccess.Unlocked
        else -> HiddenAccess.Denied(denialOf(result))
      },
    )
  }

  /** Lo sblocco non puo' nemmeno partire (nessuna activity per la schermata di sistema): resta chiuso, con il motivo. */
  fun deny(reason: HiddenDenial) {
    synchronized(this) {
      if (_state.value != HiddenAccess.Unlocked) _state.value = HiddenAccess.Denied(reason)
    }
  }

  /**
   * Una schermata protetta torna visibile (ingresso, o ritorno in primo piano). Le schermate che condividono il
   * cancello (Nascosti ed Eliminati di recente) si **contano**: la tolleranza e' una sola e parte solo quando non ne
   * resta visibile nessuna. Ogni schermata chiama [onShown] e [onHidden] a coppie.
   */
  @Synchronized
  fun onShown() {
    visible++
    val since = leftAt
    leftAt = null
    when (_state.value) {
      // Un nuovo ingresso dopo un rifiuto e' un ingresso nuovo: si ripropone la credenziale.
      is HiddenAccess.Denied -> _state.value = HiddenAccess.Locked
      HiddenAccess.Unlocked -> if (since != null && graceExpired(since)) _state.value = HiddenAccess.Locked
      HiddenAccess.Locked, HiddenAccess.Authenticating -> Unit
    }
  }

  /**
   * Una schermata protetta non e' piu' visibile (indietro, un'altra raccolta, l'app in secondo piano, schermo spento).
   * Se non ne resta visibile nessuna comincia la tolleranza. Durante lo sblocco la schermata di sistema porta l'app in
   * secondo piano: li' non si ricorda nessuna uscita.
   */
  @Synchronized
  fun onHidden() {
    if (visible > 0) visible--
    if (visible == 0 && _state.value == HiddenAccess.Unlocked && leftAt == null) leftAt = clock.elapsedRealtimeMillis()
  }

  /** Richiude subito (per esempio quando scatta il blocco dell'app). */
  @Synchronized
  fun lock() {
    leftAt = null
    _state.value = HiddenAccess.Locked
    inFlight?.complete(HiddenAccess.Locked)
    inFlight = null
  }

  @Synchronized
  private fun finish(next: HiddenAccess): HiddenAccess {
    // Se nel frattempo qualcuno ha richiuso (lock) non si riapre: l'esito di uno sblocco vecchio non vale.
    if (_state.value == HiddenAccess.Authenticating) {
      leftAt = null
      _state.value = next
    }
    val result = _state.value
    inFlight?.complete(result)
    inFlight = null
    return result
  }

  private fun graceExpired(since: Long? = leftAt): Boolean {
    if (since == null) return false
    val elapsed = clock.elapsedRealtimeMillis() - since
    // Un orologio che va indietro (elapsed < 0) e' un'anomalia: si richiude.
    return elapsed < 0 || elapsed >= graceMillis
  }

  private fun denialOf(result: AuthResult): HiddenDenial = when (result) {
    AuthResult.Success -> HiddenDenial.ERROR
    AuthResult.Cancelled -> HiddenDenial.CANCELLED
    AuthResult.Interrupted -> HiddenDenial.INTERRUPTED
    is AuthResult.LockedOut -> HiddenDenial.LOCKED_OUT
    is AuthResult.NotAvailable -> when (result.reason) {
      AuthAvailability.NO_SCREEN_LOCK -> HiddenDenial.NO_SCREEN_LOCK
      AuthAvailability.AVAILABLE, AuthAvailability.TEMPORARILY_UNAVAILABLE, AuthAvailability.UNSUPPORTED -> HiddenDenial.UNAVAILABLE
    }
    is AuthResult.Error -> HiddenDenial.ERROR
  }

  companion object {
    /** Un minuto (E-H3): quanto si puo' stare fuori dai Nascosti senza rifare lo sblocco. */
    const val DEFAULT_GRACE_MS: Long = 60_000L

    private const val ERROR_CODE_UNKNOWN = -1
  }
}
