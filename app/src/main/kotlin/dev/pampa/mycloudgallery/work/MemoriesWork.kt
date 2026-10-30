package dev.pampa.mycloudgallery.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.memories.notify.MemoryNotifyPolicy
import dev.pampa.mycloudgallery.core.memories.notify.MemoryNotifyRun
import dev.pampa.mycloudgallery.core.memories.notify.NotifyScheduler
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Il lavoro delle notifiche dei ricordi (Fase 9, P10; docs/fase9-progetto.md §2.6, D20; E-NT3, E-NT4, E-NT5, E-P13, E-P15): **un lavoro unico**, `memories-notify`,
 * `OneTimeWork` con il ritardo fino alla prossima ora scelta, **senza vincoli** di rete o di carica, rifatto a ogni esecuzione, a ogni cambio di impostazioni e a ogni
 * avvio dell'app (WorkManager lo conserva anche dopo un riavvio del telefono). **Nessun allarme esatto e nessun permesso nuovo**: l'ora e' "piu' o meno" (da H a H+2 h,
 * oltre H+3 h quel giorno si salta) e se Android non fa partire il lavoro (sospensione profonda) quel giorno non c'e' notifica, e va bene cosi'. Nessun ricevitore per i
 * cambi di fuso: il ritardo si ricalcola alla corsa dopo (E-NT4).
 *
 * Qui c'e' solo l'aggancio a WorkManager e a Hilt; il giorno di una notifica e' [MemoryNotifyRun] di `core-memories` (provato sulla JVM con il tempo virtuale).
 */
object MemoriesWorkScheduling {
  const val NOTIFY_WORK = "memories-notify"
  const val TAG = "memories-notify"

  /** Un secondo in piu' sul ritardo: il lavoro non parte mai prima dell'orario (un ritardo arrotondato per difetto sarebbe una corsa "in anticipo"). */
  private const val MARGIN_MS = 1_000L

  /** Vero mentre gira [MemoryNotifyWorker]: il lavoro dopo si accoda a quello che sta girando invece di sostituirlo (lo cancellerebbe mentre finisce). */
  private val inWorker = AtomicBoolean(false)

  internal fun enterWorker() = inWorker.set(true)

  internal fun leaveWorker() = inWorker.set(false)

  /**
   * Mette (o rifa') il lavoro unico con [delayMs] di ritardo. Dall'esterno (avvio, impostazioni) **sostituisce** quello in attesa; dall'interno del lavoro si accoda a se' stesso
   * (`APPEND_OR_REPLACE`: il ritardo parte quando il lavoro finisce).
   */
  fun schedule(context: Context, delayMs: Long) {
    val request = OneTimeWorkRequestBuilder<MemoryNotifyWorker>()
      .setInitialDelay(delayMs.coerceAtLeast(0L) + MARGIN_MS, TimeUnit.MILLISECONDS)
      .addTag(TAG)
      .build()
    val policy = if (inWorker.get()) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
    try {
      WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(NOTIFY_WORK, policy, request)
    } catch (e: IllegalStateException) {
      // WorkManager non inizializzato (test senza lavori): niente notifica, nessun crollo.
      GalleryLog.w(TAG) { "lavoro delle notifiche non programmato: ${e.javaClass.simpleName}" }
    }
  }

  /** Toglie il lavoro (notifiche spente, azzeramento, uscita dalla famiglia: E-NT15). */
  fun cancel(context: Context) {
    try {
      WorkManager.getInstance(context.applicationContext).cancelUniqueWork(NOTIFY_WORK)
    } catch (e: IllegalStateException) {
      GalleryLog.w(TAG) { "lavoro delle notifiche non tolto: ${e.javaClass.simpleName}" }
    }
  }
}

/**
 * Il lavoro unico su WorkManager (la porta [NotifyScheduler] di `core-memories`): il ritardo vero dall'ora locale di adesso all'ora locale del prossimo orario, nel fuso
 * del telefono ([MemoryNotifyPolicy.delayUntil]: ora legale con le sue lacune e le sue ore doppie). [zone] si legge a ogni richiesta, cosi' un cambio di fuso si vede alla
 * ripianificazione dopo (E-NT4).
 */
class WorkManagerNotifyScheduler(private val context: Context, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) : NotifyScheduler {

  override fun scheduleAt(now: LocalDateTime, next: LocalDateTime) {
    MemoriesWorkScheduling.schedule(context, MemoryNotifyPolicy.delayUntil(now, next, zone()).toMillis())
  }

  override fun cancel() = MemoriesWorkScheduling.cancel(context)
}

/**
 * Il posto dove P13 mette il giorno di una notifica quando i ricordi sono montati (`MemoriesGraph`): finche' e' vuoto il lavoro non fa niente (famiglia non pronta,
 * ricordi non montati) e non si ripianifica da solo. Uno per processo, costruito da Hilt.
 */
@Singleton
class MemoryNotifySlot @Inject constructor() {
  @Volatile
  var runner: MemoryNotifyRun? = null
}

/**
 * Una corsa del lavoro delle notifiche: **una tappa corta**, nessun nuovo tentativo, nessun ciclo (android.md 1.3). L'esito e' sempre "riuscito": che sia partita una
 * notifica, saltato il giorno o che i ricordi non ci siano ancora, la prossima ora la rifa' [MemoryNotifyRun.run] (anche se qui muore il processo, WorkManager rilancia il
 * lavoro e il registro del giorno impedisce una seconda notifica). Solo il tipo dell'esito nel log.
 */
@HiltWorker
class MemoryNotifyWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  private val slot: MemoryNotifySlot,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    // Ricordi non montati (processo nato per questo lavoro prima di P13, famiglia non pronta): niente da fare, e niente da ripianificare.
    val runner = slot.runner ?: return Result.success()
    MemoriesWorkScheduling.enterWorker()
    try {
      val outcome = runner.run()
      GalleryLog.d(TAG) { "notifica dei ricordi: ${outcome.javaClass.simpleName}" }
    } finally {
      MemoriesWorkScheduling.leaveWorker()
    }
    return Result.success()
  }

  companion object {
    const val TAG = "MemoryNotifyWorker"
  }
}
