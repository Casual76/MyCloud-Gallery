package dev.pampa.mycloudgallery.core.common.perf

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Il cancello **"dopo il primo disegno"** (Avvio istantaneo, 28/10): il lavoro dell'avvio che non serve a disegnare la Libreria
 * (la ricerca che apre l'indice, i modelli che si verificano, la pianificazione dei lavori, il NAS che si collega, la lettura del
 * telefono, i montaggi di album e persone) aspetta qui, cosi' non ruba la CPU al primo fotogramma.
 *
 * Si apre una volta per processo, alla prima di queste cose:
 * - la Libreria ha disegnato le miniature delle celle in vista (`AvvioTrace.LIBRARY_USEFUL`);
 * - la prima schermata non e' la Libreria (blocco app, onboarding, dispositivo fermo) ed e' stata disegnata;
 * - e' passato [MAX_WAIT_MS] dall'avvio del processo ([arm]): un processo nato per un lavoro in background, senza schermate,
 *   o una Libreria che non arriva, non tengono fermo niente piu' di cosi'.
 *
 * **Nessuna garanzia passa di qui**: i ganci, i recinti e i cancelli nascono con il processo come prima; qui aspetta solo cio'
 * che si puo' fare un secondo dopo senza che cambi niente (vedi la sezione "Avvio e pagine istantanei" di docs/decisioni.md).
 */
object AfterFirstDraw {

  /** Il tetto dell'attesa, dall'avvio del processo. */
  const val MAX_WAIT_MS: Long = 1_500L

  private val gate = CompletableDeferred<Unit>()
  @Volatile private var timer: Job? = null

  /** Vero quando il cancello e' aperto. */
  val isOpen: Boolean get() = gate.isCompleted

  /** Apre il cancello (idempotente). */
  fun open() {
    if (gate.complete(Unit)) AvvioTrace.mark(AvvioTrace.DEFERRED_START)
  }

  /** Aspetta che il cancello sia aperto. */
  suspend fun await() {
    gate.await()
  }

  /** Esegue [block] in [scope] appena il cancello e' aperto. */
  fun launchAfter(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit): Job = scope.launch {
    gate.await()
    block()
  }

  /**
   * Arma il tetto dell'attesa (da `Application.onCreate`): il cancello si apre comunque [MAX_WAIT_MS] dopo l'avvio del processo
   * ([processStartMs], `Process.getStartElapsedRealtime`). Idempotente.
   */
  fun arm(scope: CoroutineScope, processStartMs: Long) {
    if (timer != null || isOpen) return
    val left = (processStartMs + MAX_WAIT_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    timer = scope.launch {
      delay(left)
      open()
    }
  }
}
