package dev.pampa.mycloudgallery.work

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.nasops.contract.ItemFlow
import dev.pampa.mycloudgallery.core.nasops.contract.OpKind
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan

/**
 * Chi fa girare le operazioni sui file del NAS fuori dalla schermata (Fase 4B, P16; docs/fase4-progetto.md D29, §2.7 "Operazioni
 * lunghe"): il [NasOpsJobService]. Due modi, un solo servizio:
 *
 * - [userInitiated]: un'operazione lunga appena confermata dall'utente (una mossa di 2.000 file, uno spostamento tra share) diventa un
 *   **lavoro avviato dall'utente** (UIDT, Android 14+) con la notifica di avanzamento e "Ferma": non si ferma se l'utente esce
 *   dall'app. Si chiede solo dal tocco di conferma, con l'app in primo piano (lo vuole Android). Sotto Android 14 e' un lavoro normale.
 * - [resumeLater]: riprendere dal **diario locale** cio' che e' rimasto a meta' (processo ucciso, NAS sparito, file aperto dal PC da
 *   riprovare piu' tardi): un lavoro normale con la rete, che si puo' chiedere anche dal secondo piano.
 *
 * In entrambi i casi il servizio chiama solo `NasOperations.resumeAll`: nessun lavoro **crea** un'operazione, le riprende (la conferma
 * dell'utente c'era gia'). I test lo sostituiscono con [NONE] (o con un registratore).
 */
interface NasOpsJobs {

  /** L'utente ha appena confermato un'operazione lunga: UIDT con la notifica e "Ferma" (lavoro normale prima di Android 14). */
  fun userInitiated()

  /** Riprendere dal diario fra [delayMs] millisecondi (0 = appena c'e' la rete). */
  fun resumeLater(delayMs: Long)

  /** L'azzeramento: niente piu' lavori delle operazioni in coda. */
  fun cancelAll()

  companion object {
    /** Nessun lavoro (test, varianti senza NAS): le operazioni girano solo con l'app aperta e riprendono al prossimo avvio. */
    val NONE: NasOpsJobs = object : NasOpsJobs {
      override fun userInitiated() = Unit

      override fun resumeLater(delayMs: Long) = Unit

      override fun cancelAll() = Unit
    }

    /** Elementi oltre i quali un'operazione e' "lunga" anche se resta nella stessa share. */
    const val LONG_OPERATION_ITEMS: Int = 10

    /**
     * Un'operazione e' lunga (UIDT) se sposta qualcosa **tra share** (copia lato server, verifica completa: minuti per un video) o se ha
     * almeno [LONG_OPERATION_ITEMS] elementi. Una rinomina, una cartella nuova o tre file nel cestino finiscono prima che l'utente esca.
     */
    fun isLong(plan: OpPlan): Boolean = when (plan.kind) {
      OpKind.CREATE_FOLDER, OpKind.RENAME -> false
      else -> plan.items.size >= LONG_OPERATION_ITEMS || plan.items.any { it.flow == ItemFlow.MOVE_ACROSS_SHARES }
    }
  }
}

/**
 * I lavori del [NasOpsJobService] con `JobScheduler`. Due id fissi, fuori dall'intervallo che `MyCloudGalleryApp` lascia a WorkManager
 * (`setJobSchedulerJobIdRange`): uno per il lavoro dell'utente, uno per la ripresa. Programmare di nuovo lo stesso id sostituisce quello
 * in coda; i due lavori possono girare insieme, e allora si mettono in fila sul lucchetto delle operazioni (uno alla volta).
 */
class JobSchedulerNasOpsJobs(context: Context) : NasOpsJobs {

  private val app: Context = context.applicationContext ?: context
  private val scheduler: JobScheduler? get() = app.getSystemService(JobScheduler::class.java)
  private val component = ComponentName(app, NasOpsJobService::class.java)

  override fun userInitiated() {
    val builder = JobInfo.Builder(JOB_USER, component).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) builder.setUserInitiated(true)
    schedule(builder.build())
  }

  override fun resumeLater(delayMs: Long) {
    val builder = JobInfo.Builder(JOB_RESUME, component)
      .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
      .setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
    if (delayMs > 0) builder.setMinimumLatency(delayMs.coerceAtMost(MAX_DELAY_MS))
    schedule(builder.build())
  }

  override fun cancelAll() {
    scheduler?.cancel(JOB_USER)
    scheduler?.cancel(JOB_RESUME)
  }

  private fun schedule(job: JobInfo) {
    try {
      scheduler?.schedule(job)
    } catch (e: IllegalStateException) {
      // UIDT chiesto con l'app non visibile, troppi lavori in coda: l'operazione gira comunque nel processo e riprende al prossimo avvio.
      GalleryLog.w(TAG) { "lavoro delle operazioni non programmato: ${e.javaClass.simpleName}" }
    } catch (e: SecurityException) {
      GalleryLog.w(TAG) { "lavoro delle operazioni non programmato: ${e.javaClass.simpleName}" }
    } catch (e: IllegalArgumentException) {
      GalleryLog.w(TAG) { "lavoro delle operazioni non programmato: ${e.javaClass.simpleName}" }
    }
  }

  companion object {
    private const val TAG = "NasOpsJobs"

    /** Il lavoro avviato dall'utente (UIDT). Fuori da [WORK_MANAGER_JOB_IDS]. */
    const val JOB_USER: Int = 1_000_101

    /** La ripresa dal diario (lavoro normale). */
    const val JOB_RESUME: Int = 1_000_102

    /** Gli id che WorkManager puo' usare per i suoi lavori (`Configuration.setJobSchedulerJobIdRange`): i nostri stanno fuori. */
    val WORK_MANAGER_JOB_IDS: IntRange = 0..999_999

    private const val BACKOFF_MS = 60_000L

    /** Una ripresa non si rimanda mai oltre 6 ore (un file aperto dal PC si riprova al piu' tardi a quel punto). */
    private const val MAX_DELAY_MS = 6L * 60 * 60 * 1000
  }
}
