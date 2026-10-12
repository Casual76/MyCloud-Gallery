package dev.pampa.mycloudgallery.core.network.contract

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Contratti aggiunti dal pacchetto P2 (non erano nel P0): servono a chi configura la VPN (onboarding passo 11,
// Impostazioni -> Rete e VPN) e a :app per collegare l'automazione alla sessione NAS.

/**
 * Dove si conserva la scelta dell'utente sul controllo di WireGuard (passo 11) e l'eventuale sospensione
 * dell'automazione.
 *
 * La **sospensione** e' il modo in cui l'automazione "si disattiva da sola" (§2.4): dopo un sospetto di "VPN sempre
 * attiva con blocco" o dopo comandi che WireGuard ignora. E' persistente (altrimenti, al riavvio del processo,
 * l'automazione spegnerebbe di nuovo la VPN e toglierebbe di nuovo la rete al telefono) e si toglie solo con
 * un'azione dell'utente: salvare di nuovo la configurazione ([save]) o [clearSuspension], per esempio dopo una prova
 * di accensione e spegnimento riuscita.
 */
interface VpnControlConfigStore {
  val config: StateFlow<VpnControlConfig>

  /** Il motivo per cui l'automazione e' ferma, o null. */
  val suspension: StateFlow<VpnSuspendReason?>

  /** L'utente salva la scelta. Toglie anche la sospensione. Un nome di tunnel non valido diventa "nessun nome". */
  suspend fun save(config: VpnControlConfig)

  /** Lo fa l'automazione, mai l'utente. */
  suspend fun suspendBecause(reason: VpnSuspendReason)

  suspend fun clearSuspension()
}

/**
 * La sessione NAS sta lavorando? Serve all'automazione: prima di spegnere la VPN in casa aspetta che la sessione
 * sia libera (al massimo due minuti), per non tagliare un trasferimento. `:app` lo adatta a `NasSessionManager`.
 */
interface NasSessionActivity {
  val isBusy: StateFlow<Boolean>

  /** Nessuna sessione: sempre libera. */
  object Idle : NasSessionActivity {
    override val isBusy: StateFlow<Boolean> = MutableStateFlow(false)
  }
}

/** I nomi di tunnel che WireGuard accetta: 1-15 caratteri fra lettere, cifre e `_=+.-`. */
object WireGuardTunnelNames {
  private val VALID = Regex("[A-Za-z0-9_=+.\\-]{1,15}")

  fun isValid(name: String?): Boolean = name != null && VALID.matches(name)
}
