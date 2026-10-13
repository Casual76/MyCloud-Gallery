package dev.pampa.mycloudgallery.process

import android.app.Activity
import android.app.Application
import android.os.Bundle
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.SyncReason
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.notify.AppNotifications
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Il ciclo di vita del **processo** visto dall'app (docs/fase2-progetto.md §1.2, "Cosa resta in :app"):
 *
 * - `AppContextSignal` (primo piano, profilo attivo) per il motore delle regole e l'automazione della VPN;
 * - `VpnAutomation` agganciata al processo: parte con il processo e vive quanto lui ([start]);
 * - in primo piano: permessi e WireGuard riletti (P2, §12), controllo del registro "all'apertura";
 * - in background: la sessione NAS si chiude se non sta lavorando (`invalidate(PROCESS_STOPPED)` con
 *   `activeOperations == 0`, P1), il profilo si dice attivo o no (E-P19);
 * - all'arrivo a casa: controllo del registro;
 * - dispositivo configurato: il controllo quotidiano e' pianificato;
 * - interruttore di sicurezza aperto con l'app in background: notifica "Serve la password del NAS".
 *
 * Un'activity sola (la `MainActivity`): "in primo piano" = almeno un'activity avviata. Una rotazione non conta come
 * uscita (`isChangingConfigurations`).
 */
class ProcessWatcher(
  private val graph: AppGraph,
  private val notifications: AppNotifications,
  private val gallery: GalleryLifecycle = GalleryLifecycle.None,
  private val ensureDaily: () -> Unit,
) : Application.ActivityLifecycleCallbacks {

  private var started = 0

  /** Da `Application.onCreate`: tutto cio' che vive quanto il processo. */
  fun start(application: Application) {
    application.registerActivityLifecycleCallbacks(this)
    val scope = graph.scope
    graph.appContext.update(inForeground = false, profileActive = graph.platform.isUserForeground() ?: true)
    scope.launch { graph.start() }
    graph.vpnAutomation.attach(scope)
    // Fase 3: la galleria del telefono vive quanto il processo (repository della timeline, lavori in background).
    gallery.start()
    scope.launch {
      // All'arrivo a casa (non all'avvio: il primo valore e' lo stato di partenza).
      graph.environment.isAtHome.drop(1).distinctUntilChanged().collect { home ->
        if (home) graph.scheduler.requestSoon(SyncReason.ARRIVED_HOME)
      }
    }
    scope.launch {
      graph.setup.state.map { it is SetupState.Ready || it is SetupState.AwaitingApproval }.distinctUntilChanged().collect { configured ->
        if (configured) ensureDaily()
      }
    }
    scope.launch {
      graph.breaker.state.collect { state ->
        when {
          state is BreakerState.Open && !graph.appContext.context.value.inForeground -> notifications.notifyPasswordNeeded()
          state is BreakerState.Closed -> notifications.cancelPasswordNeeded()
        }
      }
    }
  }

  override fun onActivityStarted(activity: Activity) {
    started++
    if (started == 1) onForeground()
  }

  override fun onActivityStopped(activity: Activity) {
    started = (started - 1).coerceAtLeast(0)
    if (started == 0 && !activity.isChangingConfigurations) onBackground()
  }

  private fun onForeground() {
    graph.appContext.update(inForeground = true, profileActive = true)
    graph.environment.onPermissionsChanged()
    graph.wireGuard.refresh()
    gallery.onForeground()
    // Alla prima apertura lo stato salvato si sta ancora leggendo (AppGraph.start): si aspetta di saperlo.
    graph.scope.launch {
      val state = graph.setup.state.first { it !is SetupState.Loading }
      if (state is SetupState.Ready || state is SetupState.AwaitingApproval || state is SetupState.Blocked) {
        graph.scheduler.requestSoon(SyncReason.APP_OPENED)
      }
    }
  }

  private fun onBackground() {
    graph.appContext.update(inForeground = false, profileActive = graph.platform.isUserForeground() ?: true)
    gallery.onBackground()
    graph.scope.launch {
      if (graph.session.activeOperations.value == 0) {
        graph.session.invalidate(InvalidateReason.PROCESS_STOPPED)
        GalleryLog.d(TAG) { "app in background: sessione NAS chiusa" }
      }
    }
  }

  override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

  /** I permessi dei media non si memorizzano mai: si rileggono a ogni `onResume` (E-P1, E-P15). */
  override fun onActivityResumed(activity: Activity) = gallery.onResume()

  override fun onActivityPaused(activity: Activity) = Unit

  override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

  override fun onActivityDestroyed(activity: Activity) = Unit

  private companion object {
    const val TAG = "ProcessWatcher"
  }
}

/**
 * La galleria del telefono vista dal processo (Fase 3, docs/fase3-progetto.md §1.4): parte con il processo (appena la
 * famiglia e' pronta), si aggancia al primo piano (D10: in primo piano la lettura gira sempre), si stacca in background e
 * rilegge i permessi a ogni `onResume`. `None` nei test che non la riguardano.
 */
interface GalleryLifecycle {
  fun start()

  fun onForeground()

  fun onBackground()

  fun onResume()

  object None : GalleryLifecycle {
    override fun start() = Unit

    override fun onForeground() = Unit

    override fun onBackground() = Unit

    override fun onResume() = Unit
  }
}
