package dev.pampa.mycloudgallery.core.ai.ocr

import android.app.job.JobScheduler
import android.content.Context
import dev.pampa.mycloudgallery.core.ai.contract.AiDiag
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode

// LA PULIZIA ALLO SPEGNIMENTO della lettura del testo (docs/fase6-progetto.md D41, E-A18, F56, F69): ML Kit, quando gira, mette in coda
// le sue statistiche tecniche in DataTransport (la libreria di Google che le spedisce) e pianifica un lavoro che le invia. Spenta la
// lettura, niente di cio' che e' rimasto deve partire: i lavori pianificati si annullano subito e le statistiche ancora in coda si
// tolgono al primo avvio dopo, prima di qualunque altra cosa.
//
// I NOMI, letti il 21/10/2026 dal manifest fuso dell'APK (gradle/verify-release-manifest.gradle, `allowedThirdPartyComponents`) e dal
// codice della libreria (transport-runtime 2.2.6, text-recognition 16.0.1, common 18.11.0, con javap sugli AAR):
//
//  - COMPONENTI (manifest, tutti non esportati): il servizio `JobInfoSchedulerService` (il JobService che spedisce la coda), il
//    receiver `AlarmManagerSchedulerBroadcastReceiver` (la stessa cosa con AlarmManager: la libreria lo usa solo sotto API 21, mentre
//    l'app ha minSdk 29, quindi non parte mai) e il servizio `TransportBackendDiscovery` (un registro di backend: non pianifica);
//  - LAVORI: `SchedulingModule.workScheduler` sceglie `JobInfoScheduler` da API 21: un lavoro di `JobScheduler` per (backend, priorita')
//    con il componente `JobInfoSchedulerService` dell'app, id = Adler32 di pacchetto, nome del backend, priorita' e extra; per
//    annullarli basta passare in rassegna `JobScheduler.getAllPendingJobs()` e cancellare quelli di quel servizio;
//  - CODA (file): `SchemaManager.DB_NAME`, il database SQLite `com.google.android.datatransport.events` (tabelle events, event_metadata,
//    event_payloads, transport_contexts) in `databases/`, con i suoi `-journal`, `-wal`, `-shm`: `Context.deleteDatabase` li toglie tutti;
//  - IDENTIFICATIVO PER INSTALLAZIONE di ML Kit (`SharedPrefManager.PREF_FILE`): il file di preferenze `com.google.mlkit.internal`
//    (`shared_prefs/com.google.mlkit.internal.xml`) con l'id casuale dell'SDK e i dati dei modelli remoti (che l'app non usa); si toglie
//    anch'esso, cosi' una lettura riaccesa dopo uno spegnimento riparte da un id nuovo.

/** I nomi che la pulizia conosce. Elenco chiuso: un componente nuovo di una versione nuova della libreria si controlla a mano (L49). */
object MlKitTraces {
  /** Il JobService di DataTransport: i lavori da annullare sono quelli di questo componente. */
  const val DATATRANSPORT_JOB_SERVICE: String = "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService"

  /** Il receiver di AlarmManager di DataTransport: solo sotto API 21 (l'app ha minSdk 29), nominato qui perche' e' nel manifest fuso. */
  const val DATATRANSPORT_ALARM_RECEIVER: String =
    "com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver"

  /** Il registro dei backend di DataTransport (voce `cct`): non pianifica niente. */
  const val DATATRANSPORT_BACKEND_DISCOVERY: String = "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery"

  /** La coda degli eventi di DataTransport: un database SQLite. */
  const val DATATRANSPORT_QUEUE_DATABASE: String = "com.google.android.datatransport.events"

  /** Le preferenze con l'identificativo per installazione di ML Kit. */
  const val MLKIT_PREFERENCES: String = "com.google.mlkit.internal"
}

/** Cosa tocca la pulizia, dietro una porta (la vera usa le API di Android, i test una finta). */
internal interface TraceSystem {
  /** I lavori pianificati dell'app: id e nome del componente che li esegue. */
  fun pendingJobs(): List<Pair<Int, String>>

  fun cancelJob(id: Int)

  fun databaseExists(name: String): Boolean

  /** Toglie il database con i suoi file ausiliari. Vero se c'era. */
  fun deleteDatabase(name: String): Boolean

  fun preferencesExist(name: String): Boolean

  fun deletePreferences(name: String): Boolean
}

internal class AndroidTraceSystem(context: Context) : TraceSystem {
  private val appContext = context.applicationContext

  override fun pendingJobs(): List<Pair<Int, String>> {
    val scheduler = appContext.getSystemService(JobScheduler::class.java) ?: return emptyList()
    return scheduler.allPendingJobs.map { it.id to it.service.className }
  }

  override fun cancelJob(id: Int) {
    appContext.getSystemService(JobScheduler::class.java)?.cancel(id)
  }

  override fun databaseExists(name: String): Boolean = appContext.getDatabasePath(name).exists()

  override fun deleteDatabase(name: String): Boolean {
    val existed = databaseExists(name)
    appContext.deleteDatabase(name)
    return existed
  }

  override fun preferencesExist(name: String): Boolean =
    java.io.File(appContext.dataDir, "shared_prefs/$name.xml").exists()

  override fun deletePreferences(name: String): Boolean {
    val existed = preferencesExist(name)
    appContext.deleteSharedPreferences(name)
    return existed
  }
}

/** Cosa ha tolto una pulizia. Solo numeri (D40). */
data class CleanupReport(val jobsCancelled: Int, val queueRemoved: Boolean, val installIdRemoved: Boolean) {
  val anythingRemoved: Boolean get() = jobsCancelled > 0 || queueRemoved || installIdRemoved

  companion object {
    val NOTHING = CleanupReport(0, queueRemoved = false, installIdRemoved = false)
  }
}

/**
 * Le tre pulizie, ognuna idempotente e da sola: [cancelScheduledJobs] (subito, allo spegnimento), [removeQueue] (al primo avvio dopo, o
 * subito se ML Kit non e' mai partito in questo processo) e [removeInstallId]. Nessuna di queste nomina una classe della libreria:
 * lavorano su componenti, file e lavori di sistema per nome, quindi non caricano ML Kit ne' DataTransport.
 */
class DataTransportCleanup internal constructor(private val system: TraceSystem) {

  constructor(context: Context) : this(AndroidTraceSystem(context))

  /** Annulla i lavori pianificati di DataTransport; quanti. Un lavoro che non si annulla non ferma gli altri. */
  fun cancelScheduledJobs(): Int {
    var cancelled = 0
    val jobs = try {
      system.pendingJobs()
    } catch (_: RuntimeException) {
      return 0
    }
    for ((id, service) in jobs) {
      if (service != MlKitTraces.DATATRANSPORT_JOB_SERVICE) continue
      try {
        system.cancelJob(id)
        cancelled++
      } catch (_: RuntimeException) {
        // il lavoro e' gia' finito o il sistema non risponde: si riprova al prossimo avvio
      }
    }
    return cancelled
  }

  /** Quanti lavori di DataTransport sono pianificati adesso (per le prove e per la Diagnostica). */
  fun scheduledJobs(): Int =
    try {
      system.pendingJobs().count { it.second == MlKitTraces.DATATRANSPORT_JOB_SERVICE }
    } catch (_: RuntimeException) {
      0
    }

  /** C'e' una coda di eventi da spedire. */
  fun queueExists(): Boolean = system.databaseExists(MlKitTraces.DATATRANSPORT_QUEUE_DATABASE)

  /** Toglie la coda degli eventi (il database e i suoi file ausiliari). Vero se c'era. */
  fun removeQueue(): Boolean =
    try {
      system.deleteDatabase(MlKitTraces.DATATRANSPORT_QUEUE_DATABASE)
    } catch (_: RuntimeException) {
      false
    }

  /** Toglie l'identificativo per installazione di ML Kit. Vero se c'era. */
  fun removeInstallId(): Boolean =
    try {
      system.deletePreferences(MlKitTraces.MLKIT_PREFERENCES)
    } catch (_: RuntimeException) {
      false
    }
}

/**
 * Lo spegnimento della lettura del testo (E-A18, D41). Lo chiama chi implementa `TextReadingSwitch` (P11, con le impostazioni
 * personali di P8):
 *
 *  - [onTurnedOff] **allo spegnimento** (o quando questo dispositivo si esclude): rilascia il motore e annulla i lavori pianificati di
 *    DataTransport, cosi' niente parte piu'. La coda si toglie subito solo se ML Kit non e' mai partito in questo processo (altrimenti
 *    DataTransport la tiene aperta: si toglie al prossimo avvio);
 *  - [atStartup] **a ogni avvio del processo**, con la lettura non attiva qui, **prima di qualunque altra cosa**: lavori annullati
 *    (di nuovo), coda e identificativo tolti. Costa un'interrogazione di JobScheduler e due controlli di file: puo' girare sempre.
 *
 * Quando toglie qualcosa segnala `TEXT_READING_OFF_CLEANUP` alla Diagnostica (solo il codice, D40).
 *
 * @param release chiude il motore OCR se c'e' (`GatedTextReader.release`).
 * @param mlKitStarted vero se ML Kit e' partito in questo processo (`MlKitBootstrap.isInitialized`).
 */
class TextReadingShutdown(
  private val cleanup: DataTransportCleanup,
  private val release: () -> Unit,
  private val mlKitStarted: () -> Boolean,
  private val diag: (DiagCode) -> Unit = {},
) {

  fun onTurnedOff(): CleanupReport {
    release()
    val jobs = cleanup.cancelScheduledJobs()
    val ranHere = mlKitStarted()
    val queue = if (!ranHere) cleanup.removeQueue() else false
    val install = if (!ranHere) cleanup.removeInstallId() else false
    return report(CleanupReport(jobs, queue, install))
  }

  fun atStartup(state: TextReadingState): CleanupReport {
    if (state.activeHere) return CleanupReport.NOTHING
    val jobs = cleanup.cancelScheduledJobs()
    val queue = cleanup.removeQueue()
    val install = cleanup.removeInstallId()
    return report(CleanupReport(jobs, queue, install))
  }

  private fun report(r: CleanupReport): CleanupReport {
    if (r.anythingRemoved) diag(AiDiag.TEXT_READING_OFF_CLEANUP)
    return r
  }

  companion object {
    /** Lo spegnimento dell'app: pulizia vera, rilascio del lettore dietro il cancello e stato dell'avvio di ML Kit del processo. */
    fun create(context: Context, reader: GatedTextReader, diag: (DiagCode) -> Unit = {}): TextReadingShutdown =
      TextReadingShutdown(DataTransportCleanup(context), reader::release, { MlKitProcess.started }, diag)
  }
}
