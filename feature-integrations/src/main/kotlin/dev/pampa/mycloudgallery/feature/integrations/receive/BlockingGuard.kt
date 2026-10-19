package dev.pampa.mycloudgallery.feature.integrations.receive

import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Fase 5B (P11, docs/fase5-progetto.md D24, C18, E-X2, §4.3 "Lettura difensiva"): un'altra app e' un'origine non fidata anche di TEMPO. Un provider
// ostile puo' non rispondere mai a `query`, `getType` o `openInputStream`, o aprire un flusso che non consegna piu' byte. Una lettura bloccante non
// si interrompe con la cancellazione di una coroutine: si **abbandona**. Il lavoro bloccante gira in una coroutine che non e' figlia di chi aspetta;
// chi aspetta guarda l'avanzamento, e se per [stallMs] non c'e' stato nemmeno un byte (o la risposta) rinuncia, chiude il flusso e va avanti. Il
// thread abbandonato finisce quando il flusso, chiuso, smette di bloccare; non tiene niente di nostro (il file a meta' lo toglie lui).

internal class BlockingGuard(io: CoroutineContext = Dispatchers.IO) {

  private val scope = CoroutineScope(SupervisorJob() + io)

  /** Lo stato di un lavoro bloccante, perche' chi aspetta possa dire "e' fermo" e chiuderlo. */
  class Live {
    /** Quanti byte (o passi) il lavoro ha compiuto: se non cresce per [stallMs], e' fermo. */
    val progress = AtomicLong(0)

    @Volatile
    var abandoned: Boolean = false

    @Volatile
    var closeable: AutoCloseable? = null

    fun abandon() {
      abandoned = true
      try {
        closeable?.close()
      } catch (_: Exception) {
        // Un flusso che non si chiude e' gia' perso: l'importante e' non aspettarlo.
      }
    }
  }

  /** Un lavoro di fondo che non aspetta nessuno (una pulizia da ritentare). */
  fun launch(block: suspend () -> Unit) {
    scope.launch { block() }
  }

  /**
   * Esegue [block] e ne aspetta il risultato. Restituisce null se il lavoro e' fermo: nessun avanzamento per [stallMs]. Se chi aspetta viene
   * cancellato, il lavoro viene abbandonato e la cancellazione si propaga.
   */
  suspend fun <T : Any> run(stallMs: Long, live: Live = Live(), block: (Live) -> T): T? {
    val job: Deferred<T> = scope.async { block(live) }
    try {
      while (true) {
        val before = live.progress.get()
        val result = withTimeoutOrNull(stallMs) { job.await() }
        if (result != null) return result
        if (job.isCompleted) return null
        if (live.progress.get() == before) {
          live.abandon()
          return null
        }
      }
    } catch (e: CancellationException) {
      live.abandon()
      throw e
    }
  }
}
