package dev.pampa.mycloudgallery.work

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * Il `JobService` delle operazioni lunghe sui file del NAS avviate dall'utente (UIDT, `JobInfo.Builder.setUserInitiated(true)`,
 * Android 14+; `docs/fase4-progetto.md` D29, §1.4, §2.7; `docs/android.md` 1.3 punto 4; `docs/sicurezza.md` 4-bis). Una mossa di 2.000
 * file non deve fermarsi se l'utente esce dall'app: lo spostamento e' un lavoro dell'utente, con una notifica di avanzamento e "Ferma".
 *
 * **Scheletro di P0-B.** Esiste perche' il componente e il permesso `RUN_USER_INITIATED_JOBS` entrino in `AndroidManifest.xml`, nella
 * policy del manifest di release e in `docs/sicurezza.md` 4-bis **prima** del codice che li usa, come per ogni componente nuovo.
 * Non fa niente: `onStartJob` risponde "nessun lavoro" e nessuno lo programma. P16 lo lega alle operazioni (`NasOperations.resumeAll`
 * dal diario locale, mai da una conferma nuova: l'operazione era gia' stata confermata dall'utente), alla notifica (`setNotification`)
 * e al "Ferma".
 *
 * **Sicurezza.** Nel manifest e' `exported="false"` e protetto da `BIND_JOB_SERVICE`: lo lega solo il sistema (`JobScheduler`).
 * `verifyReleaseManifest` e `tools/check.ps1` pretendono entrambe le cose per ogni `*JobService`. Le operazioni che esegue partono
 * solo dal diario locale: un dato letto dal NAS non puo' avviarne una.
 */
class NasOpsJobService : JobService() {

  /** Nessun lavoro finche' P16 non lega le operazioni: il job e' gia' finito. */
  override fun onStartJob(params: JobParameters): Boolean = false

  /** Niente da fermare, niente da riprogrammare. */
  override fun onStopJob(params: JobParameters): Boolean = false
}
