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
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

  /** L'orario che la corsa di adesso ha gia' messo in coda (NT-1): lo stesso orario chiesto due volte dalla stessa corsa e' un lavoro solo. */
  private val appendedFor = AtomicReference<LocalDateTime?>(null)

  internal fun enterWorker() {
    appendedFor.set(null)
    inWorker.set(true)
  }

  internal fun leaveWorker() {
    inWorker.set(false)
    appendedFor.set(null)
  }

  /**
   * Mette (o rifa') il lavoro unico con [delayMs] di ritardo. Dall'esterno (avvio, impostazioni) **sostituisce** quello in attesa; dall'interno del lavoro si accoda a se' stesso
   * (`APPEND_OR_REPLACE`: il ritardo parte quando il lavoro finisce). [target] e' l'orario scelto: dall'interno del lavoro lo stesso orario non si mette in coda due
   * volte (NT-1: in un processo nato per il lavoro il montaggio dei ricordi ripianifica mentre il lavoro aspetta, e la corsa ripianifica alla fine: sarebbero due lavori
   * di domani uno dietro l'altro).
   */
  fun schedule(context: Context, delayMs: Long, target: LocalDateTime? = null) {
    if (inWorker.get() && target != null && appendedFor.getAndSet(target) == target) return
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
    MemoriesWorkScheduling.schedule(context, MemoryNotifyPolicy.delayUntil(now, next, zone()).toMillis(), target = next)
  }

  override fun cancel() = MemoriesWorkScheduling.cancel(context)
}

/**
 * Il posto dove P13 mette il giorno di una notifica quando i ricordi sono montati (`MemoriesGraph`). Uno per processo, costruito da Hilt.
 *
 * **Il lavoro non lo trova piu' vuoto** (NT-1, 31/10): WorkManager lancia il lavoro appena nato il processo, quando i ricordi non sono ancora montati; [awaitRunner]
 * chiede al grafo ([awaiter]) di montarli senza aspettare il primo disegno e di aspettare l'istantanea della Libreria, al piu' un tempo, e solo dopo risponde.
 */
@Singleton
class MemoryNotifySlot @Inject constructor() {
  @Volatile
  var runner: MemoryNotifyRun? = null

  /** Legata dal grafo dei ricordi alla nascita: aspetta i ricordi montati e la Libreria letta, al piu' il tempo dato. Vuota finche' il grafo non c'e'. */
  @Volatile
  var awaiter: (suspend (Duration) -> Unit)? = null

  /**
   * Il giorno di una notifica, aspettando (al piu' [timeout]) che i ricordi si montino **e** che la Libreria sia letta; null se non c'e' (famiglia mai pronta, nessun NAS).
   * Anche a ricordi gia' montati si chiede al grafo, che risponde subito se e' tutto pronto: un processo vivo con la Libreria ancora in lettura aspetta lo stesso.
   */
  suspend fun awaitRunner(timeout: Duration): MemoryNotifyRun? {
    awaiter?.invoke(timeout)
    return runner
  }
}

/**
 * Una corsa del lavoro delle notifiche: **una tappa corta** (al piu' [COLD_START_WAIT] di attesa dei ricordi, poi la corsa), nessun nuovo tentativo, nessun ciclo
 * (android.md 1.3). L'esito e' sempre "riuscito": che sia partita una notifica, saltato il giorno o che i ricordi non ci siano mai stati, la prossima ora la rifa'
 * [MemoryNotifyRun.run] o il montaggio (anche se qui muore il processo, WorkManager rilancia il lavoro e il registro del giorno impedisce una seconda notifica). Solo il
 * tipo dell'esito nel log.
 *
 * **In un processo nato per questo lavoro** (NT-1, 31/10: il caso normale, telefono fermo e app non in uso) i ricordi non sono montati: come gli altri lavori della
 * galleria aspetta il montaggio, **senza** il primo disegno che li' non c'e', e l'istantanea della Libreria, al piu' 20 secondi, e poi notifica. [enterWorker] viene
 * **prima** dell'attesa: il montaggio ripianifica (`watch`), e con il flag spento sostituirebbe, cioe' cancellerebbe, proprio il lavoro che sta aspettando.
 */
@HiltWorker
class MemoryNotifyWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  private val slot: MemoryNotifySlot,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    // NT-1: PRIMA dell'attesa, perche' il montaggio che segue ripianifica con questo flag (accoda invece di sostituire il lavoro che sta aspettando).
    MemoriesWorkScheduling.enterWorker()
    try {
      // Processo nato per il lavoro: i ricordi si montano adesso (senza il primo disegno) e si aspetta la Libreria letta, al piu' 20 s. Famiglia mai pronta nel tempo:
      // niente da fare, e il montaggio (se arriva) ripianifica da se'.
      val runner = slot.awaitRunner(COLD_START_WAIT) ?: return Result.success()
      val outcome = runner.run()
      GalleryLog.d(TAG) { "notifica dei ricordi: ${outcome.javaClass.simpleName}" }
    } finally {
      MemoriesWorkScheduling.leaveWorker()
    }
    return Result.success()
  }

  companion object {
    const val TAG = "MemoryNotifyWorker"

    /** NT-1: quanto aspetta i ricordi montati e la Libreria letta in un processo appena nato (come gli altri lavori della galleria). */
    val COLD_START_WAIT: Duration = 20.seconds
  }
}
