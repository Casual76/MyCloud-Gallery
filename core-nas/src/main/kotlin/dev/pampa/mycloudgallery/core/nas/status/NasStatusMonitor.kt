package dev.pampa.mycloudgallery.core.nas.status

import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.nas.session.SmbNasSessionManager
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.network.contract.NasRouteGate
import dev.pampa.mycloudgallery.core.network.contract.ProbeResult
import dev.pampa.mycloudgallery.core.network.contract.ReachabilityProbe
import dev.pampa.mycloudgallery.core.rules.NasReachabilitySignal
import dev.pampa.mycloudgallery.core.rules.NetworkQualitySignal
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * I segnali che il motore delle regole chiede alla Fase 2 (core-rules `Signals.kt`):
 * - [isReachable]: il percorso e' consentito e il NAS risponde. Lo dicono la sessione (pronta o in risveglio) e la
 *   sonda **solo TCP 445** (E-R15: mai `list`, `stat` o `getShareInformation` per sapere "se c'e'"), che parte da
 *   sola a ogni cambio di percorso e su richiesta ([probeNow]).
 * - [isPoor]: un trasferimento bloccato (watchdog, E-R14) rende la rete "scadente" per [poorFor] (10 minuti): i
 *   lavori automatici si fermano, l'uso interattivo continua.
 */
class NasStatusMonitor(
  private val session: SmbNasSessionManager,
  private val routeGate: NasRouteGate,
  private val probe: ReachabilityProbe,
  private val account: () -> NasAccount?,
  private val scope: CoroutineScope,
  private val probeTimeout: Duration = 6.seconds,
  private val poorFor: Duration = 10.minutes,
) : NasReachabilitySignal, NetworkQualitySignal {

  /** Cio' che si sa adesso: null = non ancora osservato. */
  private val known = MutableStateFlow<Boolean?>(null)

  override val isReachable: StateFlow<Boolean> =
    combine(routeGate.routes, known) { route, answer -> route is NasRoute.Allowed && answer == true }
      .stateIn(scope, SharingStarted.Eagerly, false)

  private val poor = MutableStateFlow(false)
  override val isPoor: StateFlow<Boolean> = poor

  private var poorJob: Job? = null

  init {
    scope.launch {
      session.state.collect { state ->
        when (state) {
          is NasConnState.Ready, is NasConnState.WakingUp -> known.value = true
          is NasConnState.Unavailable -> known.value = false
          // Interruttore aperto: il NAS risponde ma non ci si puo' lavorare; la sessione non parte.
          is NasConnState.AuthBlocked -> known.value = false
          NasConnState.Idle, NasConnState.Connecting -> Unit
        }
      }
    }
    scope.launch {
      routeGate.routes.collect { route ->
        known.value = null
        if (route is NasRoute.Allowed) probeNow()
      }
    }
    scope.launch {
      session.lastStallAt.filterNotNull().collect {
        poor.value = true
        poorJob?.cancel()
        poorJob = scope.launch {
          delay(poorFor)
          poor.value = false
        }
      }
    }
  }

  /** Una sonda TCP alla 445 sul percorso corrente (non tocca i dischi). Null se il percorso e' negato. */
  suspend fun probeNow(): ProbeResult? {
    val route = routeGate.current() as? NasRoute.Allowed ?: return null
    val address = account()?.address ?: return null
    val result = probe.tcp445(route, address, probeTimeout)
    known.value = result is ProbeResult.Open
    return result
  }
}
