package dev.pampa.mycloudgallery.core.network.vpn

import dev.pampa.mycloudgallery.core.network.contract.HomeMatch
import dev.pampa.mycloudgallery.core.network.contract.NasSessionActivity
import dev.pampa.mycloudgallery.core.network.contract.NetTransport
import dev.pampa.mycloudgallery.core.network.contract.NetworkEnvironment
import dev.pampa.mycloudgallery.core.network.contract.NetworkSnapshot
import dev.pampa.mycloudgallery.core.network.contract.VpnAutomation
import dev.pampa.mycloudgallery.core.network.contract.VpnAutomationStatus
import dev.pampa.mycloudgallery.core.network.contract.VpnCapability
import dev.pampa.mycloudgallery.core.network.contract.VpnCommandResult
import dev.pampa.mycloudgallery.core.network.contract.VpnController
import dev.pampa.mycloudgallery.core.network.contract.VpnControlConfigStore
import dev.pampa.mycloudgallery.core.network.contract.VpnObservation
import dev.pampa.mycloudgallery.core.network.contract.VpnSkipReason
import dev.pampa.mycloudgallery.core.network.contract.VpnSuspendReason
import dev.pampa.mycloudgallery.core.rules.AppContextSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Le soglie dell'automazione. I valori di fabbrica sono quelli del §2.4. */
data class VpnAutomationTuning(
  /** In casa con la VPN accesa: quanto aspettare che la sessione NAS sia libera prima di spegnerla. */
  val maxIdleWait: Duration = 2.minutes,
  /** Il tempo per vedere l'effetto di un comando (§2.4: 10 secondi). */
  val verifyTimeout: Duration = 10.seconds,
  /** L'attesa dopo il primo comando senza effetto; raddoppia a ogni insuccesso consecutivo. */
  val backoffBase: Duration = 30.seconds,
  val backoffMax: Duration = 10.minutes,
  /** Dopo tanti comandi senza effetto di fila WireGuard e' "non controllabile" e l'automazione si sospende. */
  val maxConsecutiveFailures: Int = 3,
  /**
   * Su un Wi-Fi o un'Ethernet che "non e' casa" si aspetta prima di accendere la VPN. Appena il telefono si
   * collega a casa arriva prima l'IPv4 e solo dopo (annuncio del router) l'ULA: per un attimo "casa" non e'
   * ancora riconoscibile, e senza questa attesa la VPN si accenderebbe a casa a ogni arrivo (e poi andrebbe
   * spenta). Sul cellulare non serve.
   */
  val lanSettle: Duration = 8.seconds,
) {
  fun backoff(failures: Int): Duration {
    if (failures <= 0) return Duration.ZERO
    var wait = backoffBase
    repeat(failures - 1) { wait = (wait * 2).coerceAtMost(backoffMax) }
    return wait.coerceAtMost(backoffMax)
  }
}

/**
 * La regola "spenta a casa, accesa fuori" (§2.4).
 *
 * **Il bisogno** si ricava dalla fotografia della rete:
 *  - *giu'*: a casa ([HomeMatch.Match]) con la VPN di casa accesa e la rete validata: spegnerla (il NAS in LAN e' piu'
 *    veloce e WireGuard non si puo' aggirare con un socket legato al Wi-Fi). **Solo se la VPN porta soltanto il
 *    traffico verso casa** (revisione 2, F2): "casa" si riconosce da dati non segreti (ULA), e un Wi-Fi che la imita
 *    farebbe spegnere una VPN che protegge tutto il traffico del telefono. Con `AllowedIPs = 0.0.0.0/0`
 *    ([NetworkSnapshot.vpnCarriesInternet]) la VPN resta accesa e il NAS si raggiunge attraverso il tunnel;
 *  - *su*: fuori casa (cellulare, oppure un Wi-Fi che non e' casa) con nessuna VPN e la rete validata: accenderla;
 *  - nessuno in tutti gli altri casi (una VPN estranea non si tocca; offline non si puo' fare niente).
 *
 * **Le regole** (mai in ciclo):
 *  1. al massimo **un comando per ogni cambio di rete**: l'identita' di un tentativo e' (verso, rete sottostante);
 *     sulla stessa rete non si ritenta, e se l'utente riaccende la VPN a mano in casa l'app non la rispegne;
 *  2. dopo comandi **senza effetto** ([VpnCommandResult.NotObserved]) l'attesa prima del comando successivo cresce
 *     (30 s, 1 min, 2 min... fino a 10 min); dopo il terzo di fila l'automazione si **sospende**
 *     ([VpnSuspendReason.NOT_CONTROLLABLE], persistente) e propone *Apri WireGuard*;
 *  3. dopo un insuccesso, in **background** non si ritenta: Android rifiuta spesso gli avvii dal background (E-R3), si
 *     riprova quando l'app torna in primo piano;
 *  4. su un Wi-Fi che non e' (ancora) casa si aspetta 8 secondi prima di accendere la VPN: all'arrivo a casa l'ULA
 *     compare dopo l'IPv4 e la VPN non deve accendersi a ogni rientro;
 *  5. per spegnere la VPN in casa si aspetta che la sessione NAS sia libera, al massimo due minuti;
 *  6. se spegnendo la VPN la rete sparisce ([VpnCommandResult.LostConnectivity], probabile "VPN sempre attiva con
 *     blocco") il controllore ha gia' rimandato `UP`: l'automazione si sospende **per sempre** finche' l'utente non
 *     la riabilita ([VpnSuspendReason.LOCKDOWN_SUSPECTED], persistente).
 *
 * Gli stati sono quelli di [VpnAutomationStatus]. `Done` resta fino al comando successivo: e' "l'ultimo comando e il
 * suo esito", e la conferma stessa cambia la rete.
 */
class DefaultVpnAutomation(
  private val environment: NetworkEnvironment,
  private val controller: VpnController,
  private val configStore: VpnControlConfigStore,
  private val appContext: AppContextSignal,
  private val session: NasSessionActivity = NasSessionActivity.Idle,
  private val clockMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
  private val tuning: VpnAutomationTuning = VpnAutomationTuning(),
) : VpnAutomation {

  private val statusState = MutableStateFlow<VpnAutomationStatus>(VpnAutomationStatus.Off)
  override val status: StateFlow<VpnAutomationStatus> = statusState

  private var started = false

  // Stato della macchina: toccato solo dalla coroutine di `run`, una `step` alla volta.
  private var lastAttempt: Attempt? = null
  private var consecutiveFailures = 0
  private var nextAllowedAtMillis = 0L

  /** Da quando si aspetta una sessione libera per questo tentativo: sopravvive ai riavvii del passo (primo piano/background). */
  private var idleWaitStart: Pair<Attempt, Long>? = null

  private data class Attempt(val up: Boolean, val networkId: String)

  private enum class Need { UP, DOWN }

  override fun attach(scope: CoroutineScope) {
    synchronized(this) {
      if (started) return
      started = true
    }
    scope.launch {
      combine(
        environment.snapshot,
        configStore.config,
        configStore.suspension,
        controller.capability,
        appContext.context,
      ) { _, _, _, _, _ -> }
        // `collectLatest` annulla l'attesa in corso a ogni cambiamento e la rifa con i dati nuovi, ma aspetta che un
        // comando gia' partito finisca (e' protetto da NonCancellable): mai due comandi insieme.
        .collectLatest { step() }
    }
  }

  private suspend fun step() {
    // I dati si rileggono qui, non dal valore che ha fatto partire il passo: durante un comando la rete cambia.
    val snapshot = environment.snapshot.value
    val config = configStore.config.value
    val suspension = configStore.suspension.value
    val capability = controller.capability.value
    val inForeground = appContext.context.value.inForeground

    if (suspension != null) {
      statusState.value = VpnAutomationStatus.Suspended(suspension)
      return
    }
    if (!config.enabled || config.tunnelName.isNullOrBlank() || capability != VpnCapability.READY) {
      statusState.value = VpnAutomationStatus.Off
      return
    }

    val (need, networkId) = needOf(snapshot)
    if (need == null || networkId == null) {
      // Nessuna azione necessaria. `Done` (con un esito vero) e `Commanding` restano: sono l'ultimo esito e un comando
      // in corso. Un "aspetto" (attesa crescente, background) non ha piu' senso: il bisogno e' sparito.
      val current = statusState.value
      val staleWait = current is VpnAutomationStatus.Done && current.result.let {
        it is VpnCommandResult.Skipped && (it.reason == VpnSkipReason.RATE_LIMITED || it.reason == VpnSkipReason.BACKGROUND_LIMIT)
      }
      if (current is VpnAutomationStatus.Off || current is VpnAutomationStatus.WaitingForIdleSession || current is VpnAutomationStatus.Suspended || staleWait) {
        statusState.value = VpnAutomationStatus.Idle
      }
      return
    }
    val up = need == Need.UP
    val attempt = Attempt(up, networkId)
    // Regola 1: un comando per cambio di rete.
    if (attempt == lastAttempt) return

    // Regola 3: dopo un insuccesso, in background non si ritenta.
    if (consecutiveFailures > 0 && !inForeground) {
      statusState.value = VpnAutomationStatus.Done(up, VpnCommandResult.Skipped(VpnSkipReason.BACKGROUND_LIMIT))
      return
    }
    // Su un Wi-Fi che per ora "non e' casa" si aspetta che la rete si assesti (se cambia, il passo riparte).
    if (up && isLan(snapshot)) delay(tuning.lanSettle)
    // Regola 2: attesa crescente.
    val waitMillis = nextAllowedAtMillis - clockMillis()
    if (waitMillis > 0) {
      statusState.value = VpnAutomationStatus.Done(up, VpnCommandResult.Skipped(VpnSkipReason.RATE_LIMITED))
      delay(waitMillis)
    }
    // Regola 4: prima di spegnere la VPN in casa, la sessione deve essere libera.
    if (!up) {
      val started = idleWaitStart?.takeIf { it.first == attempt }?.second ?: clockMillis().also { idleWaitStart = attempt to it }
      statusState.value = VpnAutomationStatus.WaitingForIdleSession(since = started)
      val remaining = tuning.maxIdleWait - (clockMillis() - started).milliseconds
      if (remaining.isPositive()) withTimeoutOrNull(remaining) { session.isBusy.first { busy -> !busy } }
    }
    // Dopo le attese il bisogno puo' essere sparito (la rete e' cambiata): se e' cosi', `collectLatest` ci ha gia'
    // annullati prima di arrivare qui.
    withContext(NonCancellable) { issue(attempt) }
  }

  private suspend fun issue(attempt: Attempt) {
    lastAttempt = attempt
    idleWaitStart = null
    statusState.value = VpnAutomationStatus.Commanding(attempt.up, clockMillis())
    val result =
      try {
        controller.setTunnel(attempt.up, tuning.verifyTimeout)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // Un controllore che lancia (nome del tunnel non valido, errore del sistema) non deve far cadere lo scope
        // dell'app: conta come un comando senza effetto.
        VpnCommandResult.NotObserved
      }
    when (result) {
      VpnCommandResult.Confirmed -> {
        consecutiveFailures = 0
        nextAllowedAtMillis = 0L
        statusState.value = VpnAutomationStatus.Done(attempt.up, result)
      }
      VpnCommandResult.NotObserved -> {
        consecutiveFailures++
        nextAllowedAtMillis = clockMillis() + tuning.backoff(consecutiveFailures).inWholeMilliseconds
        statusState.value = VpnAutomationStatus.Done(attempt.up, result)
        if (consecutiveFailures >= tuning.maxConsecutiveFailures) suspendAutomation(VpnSuspendReason.NOT_CONTROLLABLE)
      }
      VpnCommandResult.LostConnectivity -> {
        // Il controllore ha gia' rimandato `UP`. Qui si ferma l'automazione, per sempre, con la spiegazione.
        statusState.value = VpnAutomationStatus.Done(attempt.up, result)
        suspendAutomation(VpnSuspendReason.LOCKDOWN_SUSPECTED)
      }
      is VpnCommandResult.Skipped -> {
        // Non e' un insuccesso: il comando non e' partito (WireGuard sparito, gia' nello stato giusto...).
        statusState.value = VpnAutomationStatus.Done(attempt.up, result)
      }
    }
  }

  private suspend fun suspendAutomation(reason: VpnSuspendReason) {
    configStore.suspendBecause(reason)
    statusState.value = VpnAutomationStatus.Suspended(reason)
  }

  /** Cosa serve in questa rete, e su quale rete sottostante. */
  private fun needOf(snapshot: NetworkSnapshot): Pair<Need?, String?> {
    val underlying = snapshot.underlying ?: return null to null
    // Senza una rete validata il comando non si puo' verificare (e una VPN non si connette).
    if (!underlying.validated || underlying.captivePortal) return null to null
    val atHome = snapshot.home == HomeMatch.Match
    return when {
      // Revisione 2, F2: mai spegnere da soli una VPN che porta anche il traffico verso internet.
      atHome && snapshot.vpn == VpnObservation.HOME_TUNNEL && !snapshot.vpnCarriesInternet -> Need.DOWN to underlying.networkId
      !atHome && snapshot.vpn == VpnObservation.NONE && isAway(snapshot) -> Need.UP to underlying.networkId
      else -> null to null
    }
  }

  /**
   * "Fuori casa" con certezza: sul cellulare (casa e' sempre Wi-Fi o Ethernet) oppure su un Wi-Fi/Ethernet con
   * un'impronta che non coincide. Con la casa "sconosciuta" su un Wi-Fi (nessuna impronta) non si fa niente.
   */
  private fun isLan(snapshot: NetworkSnapshot): Boolean =
    snapshot.underlying?.transport.let { it == NetTransport.WIFI || it == NetTransport.ETHERNET }

  private fun isAway(snapshot: NetworkSnapshot): Boolean {
    val transport = snapshot.underlying?.transport ?: return false
    return when (transport) {
      NetTransport.CELLULAR -> true
      NetTransport.WIFI, NetTransport.ETHERNET -> snapshot.home is HomeMatch.NoMatch || snapshot.home is HomeMatch.Weak
      NetTransport.OTHER -> false
    }
  }

  private companion object {
    const val NANOS_PER_MILLI = 1_000_000L
  }
}
