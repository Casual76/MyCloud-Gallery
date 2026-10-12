package dev.pampa.mycloudgallery.work

import dev.pampa.mycloudgallery.core.family.contract.PreflightResult
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.network.contract.NetPlace
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.notify.AppNotifications
import dev.pampa.mycloudgallery.notify.NotifiedRequests
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Come e' andato un giro del controllo del registro (per i test e la Diagnostica, mai dati personali). */
enum class TrustSyncOutcome {
  /** Dispositivo non configurato, in onboarding o azzerato: niente da controllare (l'onboarding usa il NAS solo in primo piano). */
  NOT_CONFIGURED,

  /** Il motore delle regole dice di aspettare (roaming, tetto, rete non di casa, profilo fermo...). */
  RULES_WAIT,

  /** Il NAS non risponde, l'account non e' piu' valido o il dispositivo non lavora (revocato, app da aggiornare). */
  STOPPED,

  /** Sola lettura (in attesa di approvazione, dismesso, registro in conflitto): controllato, niente altro. */
  READ_ONLY,

  /** Il registro firmato e accettato dice "perso": l'azzeramento e' partito (C15). */
  WIPE_STARTED,

  /** Controllato; sull'admin, anche le richieste in attesa. */
  DONE,
}

/**
 * Un giro del controllo del registro (§2.3 "Autodistruzione": `TrustGuard.preflight` + `TrustSyncWorker` una volta al
 * giorno; §2.2: notifica all'admin "Nuovo dispositivo..."):
 *
 * 1. il **battito** (Diagnostica, E-P19);
 * 2. lo stato salvato (`AppGraph.start`): niente da fare se il dispositivo non e' configurato;
 * 3. il **motore delle regole** per l'attivita' piu' vicina (`INDEX_SYNC`, leggera). Il controllo e' di pochi KB e serve
 *    alla sicurezza (un telefono perso deve azzerarsi): non lo fermano la fascia oraria, la carica, il risparmio
 *    energetico ne' la sync dell'indice spenta dall'utente; lo fermano invece roaming, tetti, rete non di casa, NAS
 *    irraggiungibile, temperatura, batteria bassa, profilo inattivo e i limiti di Android;
 * 4. `TrustGuard.preflight(BACKGROUND)`: rilegge il registro (al massimo ogni 15 minuti) e, se il registro **firmato e
 *    accettato** dice "perso", azzera;
 * 5. sull'admin: le richieste in attesa (`RegistryService.refresh`) e la notifica di quelle nuove, una volta ciascuna;
 * 6. con l'app in background, la sessione si chiude subito (§3.1: in background la rete si interrompe comunque).
 */
class TrustSyncRun(
  private val graph: AppGraph,
  private val notifications: AppNotifications?,
  private val notified: NotifiedRequests?,
) {

  suspend fun execute(): TrustSyncOutcome {
    graph.heartbeat.beat(graph.platform.deviceNow())
    graph.start()
    try {
      return run()
    } finally {
      if (!graph.appContext.context.value.inForeground) graph.session.invalidate(InvalidateReason.PROCESS_STOPPED)
    }
  }

  private suspend fun run(): TrustSyncOutcome {
    when (graph.setup.state.first { it !is SetupState.Loading }) {
      SetupState.Loading, SetupState.NotConfigured, is SetupState.Onboarding, is SetupState.Wiped -> return TrustSyncOutcome.NOT_CONFIGURED
      is SetupState.AwaitingApproval, is SetupState.Ready, is SetupState.Blocked -> Unit
    }
    awaitNetwork()
    if (!rulesAllow()) return TrustSyncOutcome.RULES_WAIT
    when (val preflight = graph.trustGuard.preflight(Purpose.BACKGROUND)) {
      PreflightResult.WipeStarted -> return TrustSyncOutcome.WIPE_STARTED
      is PreflightResult.Stop -> {
        val error = preflight.error
        if (error is NasError.Auth || error is NasError.AuthBlocked) notifications?.notifyPasswordNeeded()
        return TrustSyncOutcome.STOPPED
      }
      is PreflightResult.ReadOnly -> return TrustSyncOutcome.READ_ONLY
      is PreflightResult.Proceed -> Unit
    }
    val status = graph.registry.status.value
    if (status is TrustStatus.Approved && status.admin) {
      graph.registry.refresh(Purpose.BACKGROUND)
      val pending = graph.registry.pendingRequests.value
      val store = notified
      if (notifications != null && store != null) {
        val already = store.read()
        notifications.notifyNewDevices(pending, already)
        // Si ricordano solo le richieste ancora in attesa: quelle notificate ora e quelle notificate prima.
        store.write(pending.filter { it.id in already || it.windowOpen }.map { it.id }.toSet())
      }
    }
    return TrustSyncOutcome.DONE
  }

  /**
   * In un processo appena avviato da WorkManager le reti arrivano dai callback un attimo dopo: si aspetta che la rete
   * si veda (al massimo [NETWORK_SETTLE]) e, se il percorso verso il NAS e' consentito, si fa la sonda TCP 445, cosi' il
   * motore delle regole non legge "offline" o "NAS irraggiungibile" solo perche' nessuno ha ancora guardato.
   */
  private suspend fun awaitNetwork() {
    withTimeoutOrNull(NETWORK_SETTLE) { graph.environment.snapshot.first { it.place != NetPlace.OFFLINE } }
    if (graph.routeGate.current() is NasRoute.Allowed) graph.statusMonitor.probeNow()
  }

  private suspend fun rulesAllow(): Boolean =
    when (val decision = graph.rulesEngine.evaluate(WorkRequest.Background(BackgroundActivity.INDEX_SYNC))) {
      is RuleDecision.Run -> true
      is RuleDecision.Wait -> (listOf(decision.reason) + decision.alsoBlockedBy).all(::ignorable)
      is RuleDecision.Stop -> (listOf(decision.reason) + decision.alsoBlockedBy).all(::ignorable)
      is RuleDecision.NeedsConfirmation -> false
    }

  private fun ignorable(reason: WaitReason): Boolean = when (reason) {
    is WaitReason.OutsideWindow, WaitReason.NeedsPower, WaitReason.PowerSaver, WaitReason.ActivityDisabled -> true
    else -> false
  }

  private companion object {
    val NETWORK_SETTLE = 5.seconds
  }
}
