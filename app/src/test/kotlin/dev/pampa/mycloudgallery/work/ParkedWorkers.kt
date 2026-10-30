package dev.pampa.mycloudgallery.work

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * I lavori "che non finiscono mai" dei test di pianificazione: partono (l'esecutore sincrono li avvia subito), restano in coda come fra un giro e l'altro in
 * produzione e il test li guarda, senza eseguire niente.
 *
 * **Perche' non basta un `CoroutineWorker` che aspetta per sempre** (com'erano): il lavoro avviato e mai finito tiene un `Completer` di `CallbackToFutureAdapter`
 * che nessuno completa. Quando il WorkManager di quel test non e' piu' raggiungibile (il test dopo ne crea uno nuovo) il Completer diventa spazzatura e il suo
 * `finalize` completa il futuro **col fallimento, dal thread del Finalizer**: `WorkerWrapper` riprogramma i lavori sul database di un test gia' chiuso
 * (`connection is closed`) e l'eccezione, non presa, arriva al primo `runTest` che parte dopo, in un'altra classe (`UncaughtExceptionsBeforeTest`: cosi' sono
 * cadute `P9FlowsPhoneScreenshotTest` e `CutoutClipboardTest`, quando il GC passava in quel momento). Qui il Completer lo tiene il test e lo chiude lui, a fine
 * prova, con [release]: nessun lavoro resta appeso al processo.
 */
internal class ParkedWorkers {

  private val parked = CopyOnWriteArrayList<CallbackToFutureAdapter.Completer<ListenableWorker.Result>>()

  /** La fabbrica da dare alla configurazione del WorkManager di prova: ogni lavoro avviato aspetta senza finire. */
  val factory: WorkerFactory = object : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
      object : ListenableWorker(appContext, workerParameters) {
        override fun startWork(): ListenableFuture<Result> = CallbackToFutureAdapter.getFuture { completer ->
          parked += completer
          "lavoro di prova"
        }
      }
  }

  /** Quanti lavori sono partiti e non sono ancora stati chiusi. */
  val count: Int get() = parked.size

  /**
   * A fine prova: annulla ogni lavoro e chiude ogni Completer **qui, nel thread del test** (i `WorkerWrapper` finiscono i loro conti sul database mentre e'
   * ancora aperto), poi lascia il processo senza lavori appesi.
   */
  fun release(workManager: WorkManager) {
    workManager.cancelAllWork().result.get()
    parked.forEach { it.setCancelled() }
    parked.clear()
  }
}
