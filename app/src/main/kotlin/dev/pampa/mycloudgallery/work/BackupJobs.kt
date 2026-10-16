package dev.pampa.mycloudgallery.work

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.common.log.GalleryLog

/**
 * Chi fa girare il backup avviato dall'utente fuori dalla schermata (Fase 5A, P7; docs/fase5-progetto.md D11, §2.8): il
 * [BackupJobService] come **lavoro avviato dall'utente** (UIDT, Android 14+) con la notifica di avanzamento e "Ferma". Lo chiedono solo
 * "Inizia ora" del primo backup e "Fai ora", dal tocco, con l'app in primo piano (lo vuole Android). Sotto Android 14 e' un lavoro normale.
 *
 * Il servizio chiama solo il motore del backup (`BackupStages`): non crea elementi del diario che non nascano dal catalogo locale. I test
 * lo sostituiscono con [NONE] o con un registratore.
 */
interface BackupJobs {

  /**
   * Un giro dell'utente: [trigger] e' `FIRST_BACKUP` ("Inizia ora") o `USER_NOW` ("Fai ora"); [confirmed] = l'utente ha visto la stima e
   * accettato i dati mobili (proposta 10), altrimenti sui dati mobili le regole chiedono conferma e il giro non parte.
   */
  fun userInitiated(trigger: BackupTrigger, confirmed: Boolean)

  /** L'azzeramento: niente piu' lavori del backup in coda. */
  fun cancelAll()

  companion object {
    /** Nessun lavoro (test, varianti senza NAS): il backup gira solo con i lavori a tappe di WorkManager. */
    val NONE: BackupJobs = object : BackupJobs {
      override fun userInitiated(trigger: BackupTrigger, confirmed: Boolean) = Unit

      override fun cancelAll() = Unit
    }
  }
}

/**
 * Il lavoro del [BackupJobService] con `JobScheduler`. Un id fisso, fuori dall'intervallo che `MyCloudGalleryApp` lascia a WorkManager
 * (`setJobSchedulerJobIdRange`) e diverso da quelli delle operazioni sul NAS: programmare di nuovo lo stesso id sostituisce quello in coda.
 */
class JobSchedulerBackupJobs(context: Context) : BackupJobs {

  private val app: Context = context.applicationContext ?: context
  private val scheduler: JobScheduler? get() = app.getSystemService(JobScheduler::class.java)
  private val component = ComponentName(app, BackupJobService::class.java)

  override fun userInitiated(trigger: BackupTrigger, confirmed: Boolean) {
    require(trigger == BackupTrigger.FIRST_BACKUP || trigger == BackupTrigger.USER_NOW) { "Solo il primo backup e \"Fai ora\" sono lavori dell'utente." }
    val extras = PersistableBundle().apply {
      putString(EXTRA_TRIGGER, trigger.code)
      putInt(EXTRA_CONFIRMED, if (confirmed) 1 else 0)
    }
    val builder = JobInfo.Builder(JOB_USER, component).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setExtras(extras)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) builder.setUserInitiated(true)
    try {
      scheduler?.schedule(builder.build())
    } catch (e: IllegalStateException) {
      // UIDT chiesto con l'app non visibile: il backup riprende con i lavori a tappe.
      GalleryLog.w(TAG) { "lavoro del backup non programmato: ${e.javaClass.simpleName}" }
    } catch (e: SecurityException) {
      GalleryLog.w(TAG) { "lavoro del backup non programmato: ${e.javaClass.simpleName}" }
    } catch (e: IllegalArgumentException) {
      GalleryLog.w(TAG) { "lavoro del backup non programmato: ${e.javaClass.simpleName}" }
    }
  }

  override fun cancelAll() {
    scheduler?.cancel(JOB_USER)
  }

  companion object {
    private const val TAG = "BackupJobs"

    /** Il lavoro avviato dall'utente (UIDT). Fuori da [JobSchedulerNasOpsJobs.WORK_MANAGER_JOB_IDS] e diverso da quelli della 4B. */
    const val JOB_USER: Int = 1_000_103

    const val EXTRA_TRIGGER: String = "trigger"
    const val EXTRA_CONFIRMED: String = "confirmed"

    /** Il trigger scritto negli extra, solo uno dei due valori ammessi (qualunque altro vale "Fai ora"). */
    fun triggerOf(code: String?): BackupTrigger =
      if (code == BackupTrigger.FIRST_BACKUP.code) BackupTrigger.FIRST_BACKUP else BackupTrigger.USER_NOW
  }
}
