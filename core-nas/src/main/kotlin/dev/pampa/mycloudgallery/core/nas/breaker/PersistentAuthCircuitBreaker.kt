package dev.pampa.mycloudgallery.core.nas.breaker

import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.AuthCircuitBreaker
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Com'e' andato UN tentativo d'autenticazione. */
internal sealed interface AuthOutcome<out T> {
  /** Password accettata: [value] e' la sessione. */
  class Accepted<T>(val value: T) : AuthOutcome<T>

  /** Uno dei cinque errori d'autenticazione del §3.4: l'interruttore si apre. */
  class Rejected(val reason: AuthBlockReason) : AuthOutcome<Nothing>

  /** Un errore che NON e' d'autenticazione (rete, percorso cambiato, credenziali assenti): non apre. */
  class Failed(val error: NasError) : AuthOutcome<Nothing>
}

internal sealed interface AttemptResult<out T> {
  /** L'interruttore era aperto: nessun tentativo. */
  class Blocked(val reason: AuthBlockReason) : AttemptResult<Nothing>

  class Done<T>(val outcome: AuthOutcome<T>) : AttemptResult<T>
}

/** Dentro un tentativo: [beforeAuthenticate] va chiamato subito prima di `authenticate` (traccia persistente). */
internal interface AttemptScope {
  suspend fun beforeAuthenticate()
}

/**
 * L'interruttore di sicurezza sull'autenticazione (§3.3, E-R8, E-S12, C5), persistente.
 *
 * - Si apre al **primo** errore d'autenticazione (il primo `Rejected`), e lo scrive nel [BreakerStore] PRIMA di
 *   lasciar passare chiunque altro.
 * - Finche' e' aperto **nessuno** arriva ad `authenticate`: tutti i tentativi passano da [attempt], che li mette in
 *   fila (un `Mutex` per chiave, condiviso da tutte le sessioni del processo tramite [AuthBreakerRegistry]) e
 *   rilegge lo stato persistente a ogni giro. Dopo un riavvio il file dice gia' "aperto".
 * - Si chiude solo con [onNewPasswordSaved] (generazione + 1). Un fallimento arrivato da un tentativo della
 *   generazione precedente non riapre quella nuova.
 * - Gli errori di rete non lo aprono. Un tentativo interrotto dalla morte del processo resta "non confermato": dopo
 *   [MAX_UNCONFIRMED] di fila si apre comunque.
 */
class PersistentAuthCircuitBreaker internal constructor(
  internal val key: String,
  private val store: BreakerStore,
  private val wallClock: () -> Long,
  scope: CoroutineScope,
) : AuthCircuitBreaker {

  private val attemptLock = Mutex()
  private val mutableState = MutableStateFlow<BreakerState>(BreakerState.Closed(BreakerRecord.FIRST_GENERATION))

  override val state: StateFlow<BreakerState> = mutableState.asStateFlow()

  init {
    scope.launch { store.observe(key).collect { mutableState.value = it.toState() } }
  }

  /** Lo stato letto ora dal file (non dalla copia in memoria). */
  suspend fun current(): BreakerState = store.read(key).toState().also { mutableState.value = it }

  override suspend fun onNewPasswordSaved() {
    val record = store.update(key) { BreakerRecord(generation = it.generation + 1) }
    mutableState.value = record.toState()
  }

  /**
   * Esegue [block] (che contiene l'unico `authenticate`) solo se l'interruttore e' chiuso, uno alla volta per
   * chiave. Un `Rejected` apre l'interruttore prima che il prossimo in fila possa leggerlo.
   */
  internal suspend fun <T> attempt(block: suspend AttemptScope.(generation: Long) -> AuthOutcome<T>): AttemptResult<T> =
    attemptLock.withLock {
      val record = store.read(key)
      mutableState.value = record.toState()
      record.openReason?.let { return@withLock AttemptResult.Blocked(it) }
      if (record.unconfirmed >= MAX_UNCONFIRMED) {
        open(AuthBlockReason.AUTH_FAILED, record.generation)
        return@withLock AttemptResult.Blocked(AuthBlockReason.AUTH_FAILED)
      }
      val generation = record.generation
      var marked = false
      val scope = object : AttemptScope {
        override suspend fun beforeAuthenticate() {
          if (marked) return
          store.update(key) { if (it.generation == generation) it.copy(unconfirmed = it.unconfirmed + 1) else it }
          marked = true
        }
      }
      val outcome = try {
        scope.block(generation)
      } catch (t: Throwable) {
        // Annullato o guasto a meta': nessun verdetto del NAS, non e' un errore d'autenticazione.
        if (marked) withContext(NonCancellable) { settle(generation, reset = false) }
        throw t
      }
      when (outcome) {
        is AuthOutcome.Rejected -> open(outcome.reason, generation)
        is AuthOutcome.Accepted -> if (marked) settle(generation, reset = true)
        is AuthOutcome.Failed -> if (marked) settle(generation, reset = false)
      }
      AttemptResult.Done(outcome)
    }

  private suspend fun open(reason: AuthBlockReason, generation: Long) {
    val record = store.update(key) {
      if (it.generation == generation) it.copy(openReason = reason, openedAt = wallClock(), unconfirmed = 0) else it
    }
    mutableState.value = record.toState()
  }

  private suspend fun settle(generation: Long, reset: Boolean) {
    val record = store.update(key) {
      when {
        it.generation != generation -> it
        reset -> it.copy(unconfirmed = 0)
        else -> it.copy(unconfirmed = (it.unconfirmed - 1).coerceAtLeast(0))
      }
    }
    mutableState.value = record.toState()
  }

  companion object {
    /** Tentativi lasciati a meta' (processo morto) dopo i quali l'interruttore si apre da solo. */
    const val MAX_UNCONFIRMED: Int = 2
  }
}

/**
 * Gli interruttori del processo, uno per `(nasId, utente)`, condivisi da sessione e test di connessione: lo stesso
 * account ha sempre lo stesso oggetto (e quindi lo stesso `Mutex`). [scope] tiene aggiornati gli `state`.
 */
class AuthBreakerRegistry(
  private val store: BreakerStore,
  private val scope: CoroutineScope,
  private val wallClock: () -> Long = System::currentTimeMillis,
) {
  private val breakers = ConcurrentHashMap<String, PersistentAuthCircuitBreaker>()

  fun forKey(nasId: String, user: String): PersistentAuthCircuitBreaker {
    val key = BreakerKeys.of(nasId, user)
    return breakers.computeIfAbsent(key) { PersistentAuthCircuitBreaker(it, store, wallClock, scope) }
  }

  /** L'interruttore di un account: il NAS si riconosce dal GUID memorizzato (dall'IP finche' non c'e'). */
  fun forAccount(account: NasAccount): PersistentAuthCircuitBreaker = forKey(nasIdOf(account), account.user)

  companion object {
    fun nasIdOf(account: NasAccount): String = account.pinnedGuid?.lowercase() ?: "addr:${account.address.ip}"
  }
}
