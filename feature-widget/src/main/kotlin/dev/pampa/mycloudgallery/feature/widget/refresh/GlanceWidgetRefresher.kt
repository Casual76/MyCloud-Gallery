package dev.pampa.mycloudgallery.feature.widget.refresh

import android.content.Context
import android.os.SystemClock
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPeriod
import dev.pampa.mycloudgallery.core.widget.contract.WidgetRefresher
import dev.pampa.mycloudgallery.core.widget.schedule.BackgroundRefreshGate
import dev.pampa.mycloudgallery.feature.widget.glance.GlanceWidgetComposer
import dev.pampa.mycloudgallery.feature.widget.host.WidgetHost
import dev.pampa.mycloudgallery.feature.widget.work.WidgetWork
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** I motori del processo: uno solo, costruito alla prima richiesta (il ricevitore, il lavoro e il `WidgetRefresher` di `:app` ne condividono lo stato: i cancelli e le epoche). */
internal object WidgetEngines {
  @Volatile
  private var shared: WidgetEngine? = null

  fun of(context: Context): WidgetEngine {
    shared?.let { return it }
    return synchronized(this) {
      shared ?: build(context.applicationContext).also { shared = it }
    }
  }

  /** Per i test: un motore con porte finte, o null per tornare a quello vero. */
  fun override(engine: WidgetEngine?) {
    synchronized(this) { shared = engine }
  }

  private fun build(app: Context): WidgetEngine = WidgetEngine(
    services = { budget -> WidgetHost.services(budget) },
    targets = AppWidgetTargets(app),
    composer = GlanceWidgetComposer(app),
    resources = { app.resources },
    prewarmBlanks = true,
  )
}

/**
 * Il `WidgetRefresher` vero (Fase 10, P3, docs/fase10-progetto.md §1.2 "Ridisegno", §2.1 "Quando si ridisegna", D5, D42): chi cambia qualcosa che il widget mostra lo dice qui e
 * non aspetta il disegno (tranne [blankAll]: l'azzeramento aspetta che i widget siano vuoti **prima** di cancellare). `:app` (P13) ne costruisce **uno**, lo da' a
 * `GalleryGraph.setHidden`, al giro dei dati personali (`onHiddenChanged`), allo scaffale dei ricordi, al tema, al blocco e all'azzeramento.
 *
 * | Chiamata | Cosa succede |
 * |---|---|
 * | [hiddenChanged], [memoryDropped] | i widget che mostrano quegli id si **svuotano subito** (< 100 ms, nessuna decodifica), poi il giro intero |
 * | [shelfChanged], [appearanceChanged], [lockChanged] | ogni giro in corso e' vecchio (non consegna); poi il giro intero |
 * | [appBackgrounded] | dopo 2 s di calma, al piu' una volta ogni 15 minuti ([BackgroundRefreshGate]): la Home torna visibile |
 * | [blankAll] | tutti i widget a `reason` senza immagini, il registro dimentica ogni cella |
 *
 * Il lavoro gira in `scope` (di processo): una chiamata non si perde se chi la fa sparisce. **Un errore non esce mai**: nel log solo il tipo, mai un id.
 */
class GlanceWidgetRefresher internal constructor(
  private val context: Context,
  private val engine: () -> WidgetEngine,
  private val scope: CoroutineScope,
  private val monotonicMs: () -> Long,
) : WidgetRefresher {

  /** Il `WidgetRefresher` del processo, sopra i widget veri e il `WidgetHost`. */
  constructor(context: Context) : this(
    context.applicationContext,
    { WidgetEngines.of(context) },
    CoroutineScope(SupervisorJob() + Dispatchers.Default),
    { SystemClock.elapsedRealtime() },
  )

  private val backgroundGate = BackgroundRefreshGate()

  override fun hiddenChanged(ids: Set<MediaId>) {
    scope.launchSafely("nascosto") { engine().hiddenChanged(ids) }
  }

  override fun memoryDropped(id: MemoryId) {
    scope.launchSafely("ricordo scartato") { engine().memoryDropped(id) }
  }

  override fun shelfChanged() {
    scope.launchSafely("ricordo del giorno") { engine().settingsChanged() }
  }

  override fun appearanceChanged() {
    scope.launchSafely("aspetto") { engine().settingsChanged() }
  }

  override fun lockChanged() {
    scope.launchSafely("blocco") { engine().settingsChanged() }
  }

  override fun appBackgrounded() {
    val now = monotonicMs()
    backgroundGate.backgrounded(now)
    scope.launchSafely("secondo piano") {
      // La calma e il minimo fra due ridisegni sono del gate (puro); qui si aspetta soltanto, e si chiede `fire` quando il tempo e' passato.
      val wait = backgroundGate.waitMs(now) ?: return@launchSafely
      delay(wait)
      if (backgroundGate.fire(monotonicMs())) engine().refreshAll()
    }
  }

  /**
   * L'app e' tornata in primo piano: l'attesa del ridisegno "in secondo piano" cade (la Home non e' piu' visibile). Non e' nel contratto di `WidgetRefresher`: `:app` la
   * puo' chiamare al ritorno; se non lo fa, al peggio si fa un ridisegno inutile (costa un piano).
   */
  fun appForegrounded() {
    backgroundGate.foregrounded()
  }

  override suspend fun blankAll(reason: WidgetEmpty) {
    engine().blankAll(reason)
  }

  /**
   * Programma il lavoro periodico per la frequenza scelta (Q2) **sostituendo** quello che c'e' (`UPDATE`): alla scelta nuova nelle Impostazioni. Quando nasce il primo widget lo
   * programma il ricevitore (`KEEP`, con la frequenza scelta di `WidgetHost.period()`, WG-2) e, dopo un azzeramento, `WidgetWiring.publishAtStart` (WG-1). Senza le porte
   * (processo freddo) il primo giro e' fra un periodo intero.
   */
  fun reschedulePeriodic() {
    scope.launchSafely("lavoro periodico") {
      val services = WidgetHost.services(0)
      val period = services?.prefs?.period?.value ?: WidgetPeriod.DEFAULT
      WidgetWork.schedule(context, period, services?.clock?.nowLocal() ?: WidgetHost.clock()?.nowLocal(), replace = true)
    }
  }

  private fun CoroutineScope.launchSafely(what: String, block: suspend () -> Unit) {
    launch {
      try {
        block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Nel log solo il tipo: mai un id di media, un titolo o un luogo (D39).
        GalleryLog.w(TAG) { "Ridisegno del widget ($what) non riuscito (${e.javaClass.simpleName})." }
      }
    }
  }

  private companion object {
    const val TAG = "WidgetRefresher"
  }
}
