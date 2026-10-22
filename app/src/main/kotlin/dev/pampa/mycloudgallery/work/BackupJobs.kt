package dev.pampa.mycloudgallery.work

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
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

  /**
   * D24 (P14): dei file in arrivo sono in coda nel diario ("Salva sul NAS"). [userInitiated] = la condivisione e' grande (oltre
   * `IncomingLimits.USER_INITIATED_JOB_BYTES`): un lavoro avviato dall'utente con la sua notifica, chiesto dal tocco con l'activity visibile;
   * altrimenti il lavoro a tappe di WorkManager. Di norma niente (test, varianti senza NAS): gli arrivi aspettano il giro dopo.
   */
  fun incoming(userInitiated: Boolean) = Unit

  /**
   * VE-10, MC-3 della verifica funzionale 5B: un lavoro delle versioni e' nato adesso da un fatto locale (uno scambio dopo un ripristino fatto
   * sul telefono, la modifica di un solo-NAS, le versioni ferme per il PC dopo una scansione): il lavoro a tappe di [kind] (unico, `KEEP`), con
   * le sue regole. Di norma niente (test, varianti senza NAS).
   */
  fun versionWork(kind: BackupKind) = Unit

  /**
   * Campo, gruppo A (D6): una lettura del telefono con l'app aperta ha trovato foto appena scattate da proteggere subito (D34): il lavoro rapido (unico,
   * `KEEP`), con le sue regole. Per le foto e i video (anche una modifica fatta ad app chiusa) vale [versionWork]. Di norma niente.
   */
  fun quickWork() = Unit

  /**
   * Correzioni 5B (LS-2): la verifica di "Libera spazio" come lavoro avviato dall'utente (UIDT su Android 14+, con la sua notifica), chiesto dal
   * tocco di conferma o dalla pagina che ritrova un giro a meta' (l'app e' davanti all'utente, come vuole Android). Ospita il giro del processo e,
   * dopo la morte del processo, lo riprende dal diario. Vero se e' in coda (o lo era gia'); di norma falso (test, varianti senza NAS): il giro va
   * avanti finche' il processo vive.
   */
  fun freeSpaceWork(): Boolean = false

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
  // P14: anche il lavoro dei file in arrivo (UIDT con un id suo, o WorkManager per le condivisioni piccole).

  private val app: Context = context.applicationContext ?: context
  private val scheduler: JobScheduler? get() = app.getSystemService(JobScheduler::class.java)
  private val component = ComponentName(app, BackupJobService::class.java)

  override fun userInitiated(trigger: BackupTrigger, confirmed: Boolean) {
    require(trigger == BackupTrigger.FIRST_BACKUP || trigger == BackupTrigger.USER_NOW) { "Solo il primo backup e \"Fai ora\" sono lavori dell'utente." }
    val extras = PersistableBundle().apply {
      putString(EXTRA_TRIGGER, trigger.code)
      putInt(EXTRA_CONFIRMED, if (confirmed) 1 else 0)
    }
    // RE-11: una rete qualunque, anche senza internet (il NAS e' in LAN): `NETWORK_TYPE_ANY` chiede una rete validata.
    val builder = JobInfo.Builder(JOB_USER, component).setRequiredNetwork(LanConstraints.request()).setExtras(extras)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) builder.setUserInitiated(true)
    try {
      // D-8 (prove sul campo): Android rifiuta un lavoro dell'utente chiesto con l'app non in primo piano con un esito, non con un'eccezione.
      // Prima l'esito si ignorava e il tocco spariva senza una traccia: adesso almeno il log lo dice.
      if (scheduler?.schedule(builder.build()) == JobScheduler.RESULT_FAILURE) GalleryLog.w(TAG) { "lavoro del backup rifiutato da Android" }
    } catch (e: IllegalStateException) {
      // UIDT chiesto con l'app non visibile: il backup riprende con i lavori a tappe.
      GalleryLog.w(TAG) { "lavoro del backup non programmato: ${e.javaClass.simpleName}" }
    } catch (e: SecurityException) {
      GalleryLog.w(TAG) { "lavoro del backup non programmato: ${e.javaClass.simpleName}" }
    } catch (e: IllegalArgumentException) {
      GalleryLog.w(TAG) { "lavoro del backup non programmato: ${e.javaClass.simpleName}" }
    }
  }

  override fun incoming(userInitiated: Boolean) {
    if (!userInitiated || !scheduleIncoming()) BackupWorkScheduling.incoming(app)
  }

  override fun versionWork(kind: BackupKind) {
    BackupWorkScheduling.versionWork(app, kind)
  }

  override fun quickWork() {
    BackupWorkScheduling.quickWork(app)
  }

  /** LS-2: il lavoro che ospita la verifica di "Libera spazio". Se c'e' gia' (in coda o in corso) non si riprogramma: lo sostituirebbe. */
  override fun freeSpaceWork(): Boolean {
    val jobs = scheduler ?: return false
    if (jobs.getPendingJob(JOB_FREE_SPACE) != null) return true
    val builder = JobInfo.Builder(JOB_FREE_SPACE, component).setRequiredNetwork(LanConstraints.request())
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) builder.setUserInitiated(true)
    return try {
      jobs.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS
    } catch (e: IllegalStateException) {
      GalleryLog.w(TAG) { "lavoro di Libera spazio non programmato: ${e.javaClass.simpleName}" }
      false
    } catch (e: SecurityException) {
      GalleryLog.w(TAG) { "lavoro di Libera spazio non programmato: ${e.javaClass.simpleName}" }
      false
    } catch (e: IllegalArgumentException) {
      GalleryLog.w(TAG) { "lavoro di Libera spazio non programmato: ${e.javaClass.simpleName}" }
      false
    }
  }

  /** Il lavoro dell'utente per una condivisione grande: falso se Android non lo accetta (allora vale WorkManager). */
  private fun scheduleIncoming(): Boolean {
    val extras = PersistableBundle().apply { putString(EXTRA_TRIGGER, BackupTrigger.INCOMING.code) }
    val builder = JobInfo.Builder(JOB_INCOMING, component).setRequiredNetwork(LanConstraints.request()).setExtras(extras)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) builder.setUserInitiated(true)
    return try {
      scheduler?.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS
    } catch (e: IllegalStateException) {
      GalleryLog.w(TAG) { "lavoro degli arrivi non programmato: ${e.javaClass.simpleName}" }
      false
    } catch (e: SecurityException) {
      GalleryLog.w(TAG) { "lavoro degli arrivi non programmato: ${e.javaClass.simpleName}" }
      false
    } catch (e: IllegalArgumentException) {
      GalleryLog.w(TAG) { "lavoro degli arrivi non programmato: ${e.javaClass.simpleName}" }
      false
    }
  }

  override fun cancelAll() {
    scheduler?.cancel(JOB_USER)
    scheduler?.cancel(JOB_INCOMING)
    scheduler?.cancel(JOB_FREE_SPACE)
  }

  companion object {
    private const val TAG = "BackupJobs"

    /** Il lavoro avviato dall'utente (UIDT). Fuori da [JobSchedulerNasOpsJobs.WORK_MANAGER_JOB_IDS] e diverso da quelli della 4B. */
    const val JOB_USER: Int = 1_000_103

    /** D24 (P14): il caricamento di una condivisione grande ("Salva sul NAS"), UIDT. Diverso da [JOB_USER]: non lo sostituisce. */
    const val JOB_INCOMING: Int = 1_000_104

    /** Correzioni 5B (LS-2): la verifica di "Libera spazio", UIDT. Diverso dagli altri: non li sostituisce. */
    const val JOB_FREE_SPACE: Int = 1_000_105

    const val EXTRA_TRIGGER: String = "trigger"
    const val EXTRA_CONFIRMED: String = "confirmed"

    /** Il trigger scritto negli extra, solo uno dei tre valori ammessi (qualunque altro vale "Fai ora"). */
    fun triggerOf(code: String?): BackupTrigger = when (code) {
      BackupTrigger.FIRST_BACKUP.code -> BackupTrigger.FIRST_BACKUP
      BackupTrigger.INCOMING.code -> BackupTrigger.INCOMING
      else -> BackupTrigger.USER_NOW
    }
  }
}
