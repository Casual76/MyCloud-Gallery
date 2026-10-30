package dev.pampa.mycloudgallery.faces

import dev.pampa.mycloudgallery.core.faces.contract.FacesPublishOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublishRules
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublisher
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * GE-1 (correzioni D, revisione funzionale della Fase 8): le modifiche delle persone in coda partono **dopo 30 secondi di calma** ([FacesPublishRules.DEBOUNCE_MS], §2.4),
 * non al giro dell'indice di 15 minuti o di 6 ore. `PeopleEditor` accoda soltanto (`publisher.stage`) e `FlushReason.DEBOUNCE` non aveva chiamanti per i volti (gli album hanno
 * `watchPending`): un nome dato sul telefono arrivava sul tablet dopo uno o due giri dell'indice. Qui si guarda [FacesPublisher.pending] (come per gli album, `AlbumsGraph`):
 * appena c'e' qualcosa in coda si aspetta la calma e si pubblica, e si riprova finche' esce o si ferma per un motivo che il giro dell'indice, la fine di un'unita' o la notte
 * risolveranno da soli.
 *
 * Il pubblicatore rispetta da solo il resto: la calma vera ([FlushReason.DEBOUNCE] risponde `TooSoon` se un'altra modifica e' arrivata da meno di 30 s), i cinque minuti fra due
 * teste, lo stato fresco (D30) e i cancelli (D34, D35). Mai nomi, id o conteggi per persona (D37): niente log qui, solo il conteggio degli errori che passa a [onError].
 */
internal class FacesPendingFlusher(
  private val scope: CoroutineScope,
  private val publisher: FacesPublisher,
  private val onError: (Exception) -> Unit = {},
  private val quietMs: Long = FacesPublishRules.DEBOUNCE_MS,
) {
  /** Segue la coda del pubblicatore finche' la coroutine che lo chiama vive. */
  suspend fun watch() {
    var flusher: Job? = null
    publisher.pending.collect { pending ->
      if (pending > 0 && flusher?.isActive != true) flusher = scope.launch { flushUntilQuiet() }
    }
  }

  /** Aspetta la calma, poi pubblica finche' la coda si svuota, con un numero chiuso di tentativi. Esce al primo esito che da qui non si risolve. */
  internal suspend fun flushUntilQuiet() {
    // Un secondo oltre i 30 s: la prova di calma del pubblicatore guarda l'ultimo accodamento con un orologio suo.
    delay(quietMs.milliseconds + SLACK)
    var attempts = 0
    while (publisher.pending.value > 0 && attempts < MAX_ATTEMPTS) {
      attempts++
      val outcome = try {
        publisher.flush(FlushReason.DEBOUNCE)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        onError(e)
        return
      }
      val wait = when (outcome) {
        is FacesPublishOutcome.TooSoon -> outcome.retryAfterMs.milliseconds
        FacesPublishOutcome.Failed -> RETRY_AFTER_FAILURE
        // Uscita (anche con altro ancora in coda): si guarda di nuovo la coda senza aspettare.
        is FacesPublishOutcome.Published, FacesPublishOutcome.NothingToPublish -> continue
        // Fermo per un motivo che da qui non cambia (recinzione D34, cancello D35, stato non fresco): ci pensano il giro dell'indice, la fine di un'unita' e la notte.
        is FacesPublishOutcome.Blocked, FacesPublishOutcome.NotFresh -> return
      }
      delay(wait.coerceAtLeast(MIN_RETRY))
    }
  }

  internal companion object {
    val SLACK = 1.seconds
    val MIN_RETRY = 5.seconds
    val RETRY_AFTER_FAILURE = 30.seconds
    const val MAX_ATTEMPTS: Int = 20
  }
}
