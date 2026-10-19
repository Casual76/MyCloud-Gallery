package dev.pampa.mycloudgallery.graph

import dev.pampa.mycloudgallery.core.nas.status.NasStatusMonitor
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.network.contract.NasRouteGate
import dev.pampa.mycloudgallery.core.network.contract.NetPlace
import dev.pampa.mycloudgallery.core.network.contract.NetworkEnvironment
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.run.StageRules
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Aspetta che la rete si veda" (verifica funzionale, RE-3). In un processo appena avviato da WorkManager (la notte, per un lavoro
 * periodico) le reti arrivano dai callback di Android un attimo **dopo** l'avvio: la prima valutazione delle regole vedrebbe "offline"
 * o "NAS irraggiungibile" solo perche' nessuno ha ancora guardato, il giro finirebbe subito senza fare niente e il prossimo sarebbe
 * dopo sei ore (un'attesa per la rete non programma niente, `BackupWorkScheduling.delayFor`).
 *
 * Si aspetta al massimo [timeout] che l'ambiente di rete veda qualcosa che non sia "nessuna rete" e, se il percorso verso il NAS e'
 * consentito e non si sa ancora se risponde (o [alwaysProbe]), si fa la sonda TCP alla 445: solo una connessione, non sveglia i dischi
 * (E-R15). Con un processo caldo (l'app aperta) la rete si vede gia' e il NAS e' gia' noto: non costa niente.
 */
class NetworkSettler(
  private val environment: NetworkEnvironment,
  private val routeGate: NasRouteGate,
  private val monitor: NasStatusMonitor,
  private val timeout: Duration = SETTLE_TIMEOUT,
) {

  suspend fun settle(alwaysProbe: Boolean = false) {
    withTimeoutOrNull(timeout) { environment.snapshot.first { it.place != NetPlace.OFFLINE } }
    if (routeGate.current() is NasRoute.Allowed && (alwaysProbe || !monitor.isReachable.value)) monitor.probeNow()
  }

  companion object {
    val SETTLE_TIMEOUT: Duration = 5.seconds
  }
}

/**
 * Le regole di una tappa con l'attesa della rete prima della **prima** valutazione ([NetworkSettler.settle], una volta per tappa): il
 * punto comune a tutti i lavori a tappe, cosi' nessuno parte alla cieca in un processo freddo (RE-3). Le valutazioni a lavoro iniziato
 * (`running = true`) passano dritte.
 */
class SettledStageRules(private val inner: StageRules, private val settle: suspend () -> Unit) : StageRules {

  private var settled = false

  override suspend fun decide(request: WorkRequest, running: Boolean): RuleDecision {
    if (!running && !settled) {
      settled = true
      settle()
    }
    return inner.decide(request, running)
  }

  override suspend fun isPluggedIn(): Boolean = inner.isPluggedIn()
}
