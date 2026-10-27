package dev.pampa.mycloudgallery.work

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagJob
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagJobResult
import dev.pampa.mycloudgallery.feature.diagnostics.recording.DiagnosticsRecorder
import kotlinx.coroutines.CancellationException

/**
 * Gli esiti dei lavori degli album e della manutenzione notturna in "Ultimi esiti ed errori" (Fase 7, revisione di fine fase, DG-2). `ShareWorker` e
 * `LibraryMaintenanceWorker` giravano senza scrivere niente: dopo un blocco la Diagnostica non diceva nemmeno se erano partiti, e un errore del giro o un budget
 * finito non lasciavano traccia. Qui sta la parte che si prova senza WorkManager: **solo codici e nomi di lavoro** (mai un messaggio d'errore, che puo' avere
 * dati), un lavoro fermato da Android lo registra il lavoro stesso con `StopReports`, come gli altri.
 */
internal object AlbumJobReports {

  /**
   * Il giro della condivisione. [run] restituisce se resta qualcosa di aperto, o null se la galleria non e' attiva (allora niente da dire). Un errore si
   * registra come lavoro fallito e il giro finisce senza rilanciarlo (la condivisione si riprende dal diario al giro dopo): null.
   */
  suspend fun share(events: DiagnosticsRecorder, run: suspend () -> Boolean?): Boolean? {
    val open = try {
      run()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      events.jobFailed(DiagJob.ALBUM_SHARE)
      GalleryLog.w(TAG) { "giro della condivisione non riuscito: ${e.javaClass.simpleName}" }
      return null
    } ?: return null
    events.result(DiagJob.ALBUM_SHARE, if (open) DiagJobResult.YIELDED else DiagJobResult.FINISHED)
    return open
  }

  /**
   * La manutenzione notturna della Libreria. [run] restituisce se ha fatto tutto (falso = il budget o la galleria non hanno lasciato finire: riprende alla
   * notte dopo). Un errore si registra e si rilancia: WorkManager lo vede come prima.
   */
  suspend fun maintenance(events: DiagnosticsRecorder, run: suspend () -> Boolean) {
    val finished = try {
      run()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      events.jobFailed(DiagJob.LIBRARY_MAINTENANCE)
      throw e
    }
    events.result(DiagJob.LIBRARY_MAINTENANCE, if (finished) DiagJobResult.FINISHED else DiagJobResult.YIELDED)
  }

  private const val TAG = "AlbumJobs"
}
