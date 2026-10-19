package dev.pampa.mycloudgallery.core.network.contract

import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Cosa l'app puo' fare con WireGuard su questo profilo (ogni profilo ha il suo WireGuard, E-P20). */
enum class VpnCapability {
  /** L'app WireGuard non e' installata. */
  WIREGUARD_MISSING,

  /** Manca `CONTROL_TUNNELS` (o "Allow remote control apps" e' spento in WireGuard). */
  PERMISSION_MISSING,

  /** Permesso c'e', ma l'utente non ha indicato il nome del tunnel di casa. */
  NOT_CONFIGURED,

  /** Si puo' comandare il tunnel. */
  READY,
}

/** Perche' un comando non e' stato nemmeno mandato. */
enum class VpnSkipReason {
  WIREGUARD_MISSING,
  PERMISSION_MISSING,
  NOT_CONFIGURED,
  AUTOMATION_DISABLED,

  /** Il tunnel e' gia' nello stato chiesto. */
  ALREADY_IN_STATE,

  /** Gia' mandato un comando per questo cambio di rete: al massimo uno, mai in ciclo (§2.4). */
  RATE_LIMITED,

  /** In background si prova una volta sola; Android rifiuta spesso gli avvii in background (E-R3). */
  BACKGROUND_LIMIT,
}

/**
 * L'esito OSSERVATO di un comando. "VPN accesa" non si dice solo perche' l'intent e' partito (§2.4): un
 * broadcast a WireGuard non ha risposta, quindi conta solo cio' che la rete fa entro il tempo concesso.
 */
sealed interface VpnCommandResult {
  /** La rete e' cambiata come chiesto entro il tempo (tunnel di casa comparso o sparito). */
  data object Confirmed : VpnCommandResult

  /** Nessun cambiamento osservato: "VPN non controllabile" e *Apri WireGuard*. */
  data object NotObserved : VpnCommandResult

  /**
   * Spento il tunnel, la connettivita' e' sparita: probabile "VPN sempre attiva con blocco". Si riaccende
   * subito e il controllo automatico si spegne, con una spiegazione.
   */
  data object LostConnectivity : VpnCommandResult

  data class Skipped(val reason: VpnSkipReason) : VpnCommandResult
}

/** Il controllo di WireGuard scelto dall'utente al passo 11 (facoltativo). */
data class VpnControlConfig(val enabled: Boolean, val tunnelName: String?) {
  companion object {
    val OFF = VpnControlConfig(enabled = false, tunnelName = null)
  }
}

/**
 * Il comando di WireGuard (intent `SET_TUNNEL_UP`/`SET_TUNNEL_DOWN` con permesso `CONTROL_TUNNELS`).
 * Progettato per fallire bene: ogni comando ha una verifica e un esito osservato ([VpnCommandResult]).
 */
interface VpnController {
  val capability: StateFlow<VpnCapability>

  suspend fun setTunnel(up: Boolean, timeout: Duration = 10.seconds): VpnCommandResult

  /** L'intent che apre WireGuard ("Apri WireGuard"), o null se non e' installato. */
  fun openWireGuardIntent(): Intent?

  /**
   * Rilegge installazione e permesso di WireGuard e aggiorna [capability]. Va chiamato quando una di queste cose puo' essere
   * cambiata senza che l'app se ne accorga: dopo il dialogo di sistema del permesso ([WireGuardPermission]), al ritorno da WireGuard
   * (verifica funzionale, RE-1: senza, la capacita' restava "manca il permesso" anche a permesso concesso).
   */
  fun refresh()
}

/**
 * Il permesso `CONTROL_TUNNELS` di WireGuard (docs/research/smb-vpn.md §3.1): "pericoloso", quindi lo concede l'utente a runtime con il
 * dialogo di sistema. Senza, il broadcast `SET_TUNNEL_UP`/`DOWN` non arriva a WireGuard e la VPN gestita dall'app non parte mai
 * (verifica funzionale, RE-1: nessuno lo chiedeva).
 */
object WireGuardPermission {

  /** Il pacchetto dell'app WireGuard ufficiale. */
  const val PACKAGE = "com.wireguard.android"

  /** Il nome completo del permesso, da chiedere con `requestPermissions`. */
  const val NAME = "$PACKAGE.permission.CONTROL_TUNNELS"

  /**
   * Come e' finito il dialogo: concesso, negato ma si puo' chiedere ancora, oppure negato "per sempre" (Android non lo mostra piu': resta
   * la scheda dell'app). [canAskAgain] e' `shouldShowRequestPermissionRationale` **dopo** il rifiuto.
   */
  fun outcomeOf(granted: Boolean, canAskAgain: Boolean): ControlPermissionOutcome = when {
    granted -> ControlPermissionOutcome.GRANTED
    canAskAgain -> ControlPermissionOutcome.DENIED
    else -> ControlPermissionOutcome.DENIED_FOR_GOOD
  }
}

/** L'esito della richiesta del permesso di WireGuard. */
enum class ControlPermissionOutcome {
  GRANTED,

  /** Rifiutato, ma Android lo richiede ancora: "Riprova". */
  DENIED,

  /** Rifiutato e Android non lo chiede piu': si apre la scheda dell'app. */
  DENIED_FOR_GOOD,
}

/** Perche' l'automazione si e' fermata da sola. */
enum class VpnSuspendReason {
  /** Spegnendo il tunnel si e' persa la rete: "VPN sempre attiva con blocco" (§2.4). */
  LOCKDOWN_SUSPECTED,

  /** Il tunnel non ha risposto ai comandi. */
  NOT_CONTROLLABLE,
}

/** Lo stato della macchina che accende e spegne la VPN (§2.4). */
sealed interface VpnAutomationStatus {
  /** Controllo non attivo (scelta dell'utente o capacita' mancante). */
  data object Off : VpnAutomationStatus

  /** Nessuna azione necessaria. */
  data object Idle : VpnAutomationStatus

  /** In casa con la VPN accesa: si aspetta che la sessione NAS sia libera (al massimo 2 minuti). */
  data class WaitingForIdleSession(val since: Long) : VpnAutomationStatus

  /** Comando mandato, si aspetta l'esito osservato. */
  data class Commanding(val up: Boolean, val at: Long) : VpnAutomationStatus

  /** L'ultimo comando e il suo esito. */
  data class Done(val up: Boolean, val result: VpnCommandResult) : VpnAutomationStatus

  /** Fermata da sola, con il motivo da spiegare all'utente. */
  data class Suspended(val reason: VpnSuspendReason) : VpnAutomationStatus
}

/**
 * La regola "spenta a casa, accesa fuori" (P2, §2.4): al massimo un comando per ogni cambio di rete, con
 * attesa crescente, mai in ciclo. [attach] la collega a uno scope (il ciclo di vita del processo, in :app).
 */
interface VpnAutomation {
  val status: StateFlow<VpnAutomationStatus>

  fun attach(scope: CoroutineScope)
}
