package dev.pampa.mycloudgallery.nas

import dev.pampa.mycloudgallery.core.nasops.contract.RunResult
import dev.pampa.mycloudgallery.core.nasops.contract.RunStop

/**
 * "Riprendi le operazioni dal diario" per un processo che puo' essere appena nato (NA-12): il corpo di [NasGraph.resumeOperations], senza
 * Android, cosi' le sue strade si provano su JVM.
 *
 * Il lavoro in background gira in un processo che l'app non ha ancora finito di avviare: la galleria costruisce il catalogo e aggancia il
 * NAS ([NasGraph.start]) un attimo dopo il servizio. Con `NasOrganizer.resumeOperations` direttamente il servizio vedeva "niente NAS",
 * rispondeva null e il lavoro finiva "fatto" senza aver ripreso niente (e le tappe successive di una mossa lunga si allontanavano).
 * Qui, nell'ordine:
 *  1. si prova ad avviare il NAS ([start], idempotente);
 *  2. se non c'e' ancora, si aspetta un poco che la galleria lo agganci da sola ([awaitAttached]);
 *  3. se c'e', si riprende ([resume]);
 *  4. se non c'e' ma il diario ha operazioni aperte, **non e' "niente da fare"**: "il NAS non risponde", e il lavoro si riprogramma;
 *  5. null solo davvero senza NAS, o a galleria ferma (l'azzeramento: [stopped]).
 */
internal suspend fun resumeCold(
  start: suspend () -> Boolean,
  awaitAttached: suspend () -> Boolean,
  resume: suspend () -> RunResult?,
  stopped: () -> Boolean,
  hasOpenOperations: suspend () -> Boolean,
): RunResult? {
  val started = start()
  val attached = started || awaitAttached()
  if (attached) resume()?.let { return it }
  if (stopped()) return null
  return if (hasOpenOperations()) RunResult(0, 0, 0, 0, RunStop.NAS_UNREACHABLE) else null
}
