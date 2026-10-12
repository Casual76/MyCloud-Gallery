package dev.pampa.mycloudgallery.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.family.contract.SyncReason
import dev.pampa.mycloudgallery.core.family.contract.TrustSyncScheduler
import dev.pampa.mycloudgallery.core.family.lifecycle.WorkCanceller
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Dove si ricorda la finestra d'invito (§2.2): fino a quando (ora del **dispositivo**, ms) i controlli del registro
 * sono frequenti. L'admin la apre creando un invito; vale 24 ore. Un file con un numero in `noBackupFilesDir`.
 */
class InviteWindowStore(private val file: File) {

  fun until(): Long? = try {
    if (file.exists()) file.readText(Charsets.US_ASCII).trim().toLongOrNull() else null
  } catch (e: IOException) {
    null
  }

  fun open(untilDevice: Long) {
    try {
      file.parentFile?.mkdirs()
      file.writeText(untilDevice.toString(), Charsets.US_ASCII)
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "finestra d'invito non salvata" }
    }
  }

  fun clear() {
    file.delete()
  }

  fun isOpen(nowDevice: Long): Boolean = until()?.let { nowDevice < it } == true

  private companion object {
    const val TAG = "InviteWindow"
  }
}

/**
 * La pianificazione dei controlli del registro (`TrustSyncScheduler`, contratto P0) con WorkManager
 * (docs/fase2-progetto.md §2.2, §2.3; docs/android.md 1.3):
 *
 * - **una volta al giorno**: un lavoro periodico di 24 ore ([ensureDaily]), con la sola condizione "c'e' una rete";
 *   casa, VPN e tetti li decide il motore delle regole dentro il lavoro (android.md 1.3, punto 10);
 * - **nella finestra d'invito** (24 ore dalla creazione di un invito): un lavoro periodico di 15 minuti, il minimo di
 *   WorkManager, che si toglie da solo quando la finestra e' chiusa: cosi' l'admin riceve "Nuovo dispositivo..."
 *   entro un quarto d'ora (E-R15: fuori dalla finestra mai cosi' spesso);
 * - **presto** ([requestSoon]): all'apertura dell'app e all'arrivo a casa, un lavoro singolo. Al massimo uno ogni
 *   [MIN_SOON_INTERVAL] per motivo (il controllo vero lo limita comunque `TrustGuard` a uno ogni 15 minuti), mai in ciclo.
 *
 * Niente lavori accelerati ne' servizi in primo piano: e' un controllo di pochi KB.
 */
class WorkManagerTrustSyncScheduler(
  context: Context,
  private val nasNow: () -> Long?,
  private val deviceNow: () -> Long = System::currentTimeMillis,
  private val monotonicNow: () -> Long,
  private val window: InviteWindowStore = InviteWindowStore(File(context.noBackupFilesDir, INVITE_WINDOW_FILE)),
) : TrustSyncScheduler {

  private val app: Context = context.applicationContext ?: context
  private val workManager: WorkManager get() = WorkManager.getInstance(app)
  private val lastSoon = HashMap<SyncReason, Long>()

  override fun requestSoon(reason: SyncReason) {
    val now = monotonicNow()
    synchronized(lastSoon) {
      val last = lastSoon[reason]
      if (last != null && now - last < MIN_SOON_INTERVAL.inWholeMilliseconds) return
      lastSoon[reason] = now
    }
    val request = OneTimeWorkRequestBuilder<TrustSyncWorker>()
      .setConstraints(connected())
      .setInputData(Data.Builder().putString(TrustSyncWorker.KEY_REASON, reason.name).build())
      .addTag(TrustSyncWorker.TAG)
      .build()
    workManager.enqueueUniqueWork(SOON_WORK, ExistingWorkPolicy.KEEP, request)
  }

  override fun setInviteWindow(untilNasMs: Long) {
    val nowDevice = deviceNow()
    // La scadenza e' in ora del NAS: la si porta all'ora del dispositivo con lo scarto della sessione; senza una
    // sessione recente si usano le 24 ore dell'invito dal momento della creazione.
    val remaining = nasNow()?.let { untilNasMs - it } ?: MAX_WINDOW.inWholeMilliseconds
    val untilDevice = nowDevice + remaining.coerceIn(0L, MAX_WINDOW.inWholeMilliseconds)
    window.open(untilDevice)
    val request = PeriodicWorkRequestBuilder<TrustSyncWorker>(INVITE_INTERVAL.inWholeMinutes, TimeUnit.MINUTES)
      .setConstraints(connected())
      .setInputData(Data.Builder().putString(TrustSyncWorker.KEY_REASON, SyncReason.INVITE_WINDOW.name).build())
      .addTag(TrustSyncWorker.TAG)
      .build()
    workManager.enqueueUniquePeriodicWork(INVITE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
  }

  /** Il controllo quotidiano (idempotente: non sposta un lavoro gia' pianificato). */
  fun ensureDaily() {
    val request = PeriodicWorkRequestBuilder<TrustSyncWorker>(DAILY.inWholeHours, TimeUnit.HOURS)
      .setConstraints(connected())
      .setInputData(Data.Builder().putString(TrustSyncWorker.KEY_REASON, SyncReason.PERIODIC.name).build())
      .addTag(TrustSyncWorker.TAG)
      .build()
    workManager.enqueueUniquePeriodicWork(DAILY_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
  }

  /** La finestra d'invito e' aperta adesso? */
  fun inviteWindowOpen(): Boolean = window.isOpen(deviceNow())

  /** Dal lavoro: se la finestra e' chiusa, il lavoro di 15 minuti si toglie. */
  fun closeInviteWindowIfExpired() {
    if (!window.isOpen(deviceNow())) {
      window.clear()
      workManager.cancelUniqueWork(INVITE_WORK)
    }
  }

  /** Dopo un azzeramento: niente piu' finestra (i lavori li cancella [WorkManagerCanceller]). */
  fun forgetInviteWindow() = window.clear()

  private fun connected(): Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

  companion object {
    const val DAILY_WORK = "trust-sync-daily"
    const val INVITE_WORK = "trust-sync-invite"
    const val SOON_WORK = "trust-sync-soon"
    const val INVITE_WINDOW_FILE = "invite-window"
    val DAILY = 24.hours
    val INVITE_INTERVAL = 15.minutes
    val MAX_WINDOW = 24.hours
    val MIN_SOON_INTERVAL = 15.minutes
  }
}

/** La fase (6) dell'azzeramento: tutti i lavori di WorkManager dell'app cancellati. */
class WorkManagerCanceller(context: Context, private val window: InviteWindowStore? = null) : WorkCanceller {
  private val app: Context = context.applicationContext ?: context

  override suspend fun cancelAll() {
    WorkManager.getInstance(app).cancelAllWork()
    window?.clear()
  }
}
