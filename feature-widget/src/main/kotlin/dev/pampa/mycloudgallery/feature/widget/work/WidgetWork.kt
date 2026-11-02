package dev.pampa.mycloudgallery.feature.widget.work

import android.content.Context
import android.os.PowerManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.widget.contract.WidgetClock
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPeriod
import dev.pampa.mycloudgallery.core.widget.contract.WidgetRules
import dev.pampa.mycloudgallery.core.widget.contract.WidgetScheduleRules
import dev.pampa.mycloudgallery.core.widget.schedule.WidgetRunDecision
import dev.pampa.mycloudgallery.core.widget.schedule.WidgetRunPolicy
import dev.pampa.mycloudgallery.core.widget.schedule.WidgetWorkPlan
import dev.pampa.mycloudgallery.feature.widget.host.WidgetHost
import dev.pampa.mycloudgallery.feature.widget.refresh.AppWidgetTargets
import dev.pampa.mycloudgallery.feature.widget.refresh.WidgetEngine
import dev.pampa.mycloudgallery.feature.widget.refresh.WidgetEngines
import dev.pampa.mycloudgallery.feature.widget.refresh.WidgetTargets
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

// IL LAVORO PERIODICO DEL WIDGET (Fase 10, P3, docs/fase10-progetto.md D6, Q2, §2.1 "Quando si ridisegna", E-P10). `widget-refresh` e' **unico** (un nome solo, sostituito a
// ogni cambio di frequenza), esiste **solo se c'e' almeno un widget nostro** (il ricevitore lo programma alla nascita del primo e lo toglie alla morte dell'ultimo), gira circa
// ogni 3 ore (Q2 (a); 6 o 24 se l'utente lo sceglie), **mai fra le 23 e le 7** ora locale (il passo dentro le ore di quiete esce subito), con il vincolo "batteria non scarica"
// e con il risparmio energetico **tace**. Nessuna rete e nessun NAS: e' un ridisegno dal piano e dalle miniature locali (D6). Le decisioni sono di `WidgetRunPolicy` (core-widget,
// pura); qui ci sono solo WorkManager e i fatti di Android.

/** La programmazione del lavoro periodico. */
object WidgetWork {

  /** I parametri di WorkManager per [period] adesso ([nowLocal] null = non si sa l'ora: il primo giro dopo un intero periodo). */
  internal fun planFor(period: WidgetPeriod, nowLocal: LocalDateTime?, policy: WidgetRunPolicy = WidgetRunPolicy()): WidgetWorkPlan {
    val base = if (nowLocal != null) policy.workPlan(nowLocal, period) else null
    val interval = period.hours * MINUTES_PER_HOUR
    return base ?: WidgetWorkPlan(interval, WidgetScheduleRules.FLEX_MINUTES.coerceAtMost(interval), interval)
  }

  internal fun requestFor(plan: WidgetWorkPlan): PeriodicWorkRequest =
    PeriodicWorkRequest.Builder(WidgetRefreshWorker::class.java, plan.intervalMinutes, TimeUnit.MINUTES, plan.flexMinutes, TimeUnit.MINUTES)
      .setInitialDelay(plan.initialDelayMinutes, TimeUnit.MINUTES)
      // "Batteria non scarica": un ridisegno non vale una batteria al minimo.
      .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
      .build()

  /**
   * Programma il lavoro unico `widget-refresh` per [period]. Con [replace] (la scelta nuova di una frequenza nelle Impostazioni) `UPDATE`: il lavoro e' **sostituito**
   * senza annullare quello che sta girando; senza (un widget che nasce, un aggiornamento dell'app) `KEEP`: se il lavoro c'e' gia' non si tocca e il suo prossimo giro non si
   * sposta.
   */
  fun schedule(context: Context, period: WidgetPeriod, nowLocal: LocalDateTime?, replace: Boolean) {
    val plan = planFor(period, nowLocal)
    val policy = if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP
    WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(WidgetScheduleRules.WORK_NAME, policy, requestFor(plan))
  }

  /** Nessun widget nostro sulla Home: il lavoro si toglie. */
  fun cancel(context: Context) {
    WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WidgetScheduleRules.WORK_NAME)
  }

  internal const val MINUTES_PER_HOUR: Long = 60
}

/**
 * Il lavoro periodico. Una **tappa corta**: decide (nessun widget: annulla; risparmio energetico o ore di quiete: esce subito), altrimenti monta le porte (a freddo: solo
 * istantanea, maschera e scaffale, al piu' 1,5 s) e ridisegna ogni widget. Se le porte non sono pronte **non ridisegna** e finisce bene: il contenuto di prima era verificato
 * e il giro dopo riprova (E-W4, la lezione di NT-1: il lavoro non si fida di un grafo montato da altri, lo chiede e aspetta). Mai `retry`: non c'e' niente da riprovare prima
 * del giro dopo.
 */
class WidgetRefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    val outcome = WidgetPeriodicRun(
      targets = AppWidgetTargets(applicationContext),
      engine = { WidgetEngines.of(applicationContext) },
      clock = { WidgetHost.clock() },
      powerSaver = { applicationContext.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true },
      cancel = { WidgetWork.cancel(applicationContext) },
    ).run()
    GalleryLog.d(TAG) { "widget-refresh: ${outcome.name}" }
    return Result.success()
  }

  private companion object {
    const val TAG = "WidgetRefreshWorker"
  }
}

/** Com'e' andata una tappa del lavoro periodico (solo il nome nel log). */
internal enum class PeriodicOutcome { NO_WIDGETS, NOT_MOUNTED, POWER_SAVER, QUIET, RAN }

/**
 * La logica del lavoro, senza WorkManager (si prova sulla JVM): [targets] i widget sulla Home, [clock] l'orologio **senza montare niente** (le ore di quiete), [powerSaver] il
 * risparmio energetico, [cancel] toglie il lavoro. Dentro le ore di quiete o con il risparmio energetico **non si monta il grafo** (D6: esce subito).
 */
internal class WidgetPeriodicRun(
  private val targets: WidgetTargets,
  private val engine: () -> WidgetEngine,
  private val clock: () -> WidgetClock?,
  private val powerSaver: () -> Boolean,
  private val cancel: () -> Unit,
  private val policy: WidgetRunPolicy = WidgetRunPolicy(),
) {
  suspend fun run(): PeriodicOutcome {
    val count = targets.slots().size
    if (count == 0) {
      cancel()
      return PeriodicOutcome.NO_WIDGETS
    }
    // Senza orologio (nessun fornitore: il grafo non e' stato legato) non si sa se e' notte: non si ridisegna e basta.
    val now = clock()?.nowLocal() ?: return PeriodicOutcome.NOT_MOUNTED
    return when (policy.decide(now, count, powerSaver())) {
      WidgetRunDecision.NO_WIDGETS -> PeriodicOutcome.NO_WIDGETS
      WidgetRunDecision.POWER_SAVER -> PeriodicOutcome.POWER_SAVER
      WidgetRunDecision.QUIET -> PeriodicOutcome.QUIET
      WidgetRunDecision.RUN -> {
        engine().refreshAll(WidgetRules.BUDGET_MS)
        PeriodicOutcome.RAN
      }
    }
  }
}
