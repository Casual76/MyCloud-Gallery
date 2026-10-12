package dev.pampa.mycloudgallery.core.network.contract

import java.net.InetAddress
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/** Legge lo stato del permesso di rete locale (pronto per il target 37, docs/android.md 7.3). */
interface LocalNetworkPermission {
  fun status(): LocalNetworkAccess
}

/**
 * Un NAS trovato in LAN durante l'onboarding.
 *
 * @property serviceName il nome annunciato (NSD `_smb._tcp`): solo da mostrare.
 * @property address l'IP letterale risolto sul Wi-Fi di casa.
 */
data class DiscoveredNas(val serviceName: String, val address: NasAddress)

/**
 * La scoperta del NAS, **solo durante l'onboarding** (passo 2): NSD `_smb._tcp` e, in alternativa, un nome
 * digitato risolto sul Wi-Fi (mai sulla rete predefinita, che potrebbe essere la VPN o i dati mobili).
 */
interface NasDiscovery {
  /** I NAS annunciati, finche' non scade [timeout] o il chiamante smette di raccogliere. */
  fun discover(timeout: Duration): Flow<DiscoveredNas>

  /** Il nome digitato risolto con il DNS del Wi-Fi; null se non si risolve. */
  suspend fun resolveOnWifi(name: String): InetAddress?
}

/** L'esito di una sonda TCP. */
sealed interface ProbeResult {
  /** La porta risponde: il NAS e' acceso (i dischi possono ancora dormire, E-R1). */
  data class Open(val elapsed: Duration) : ProbeResult

  /** Connessione rifiutata: il NAS e' acceso ma SMB non risponde (`ServiceDown`). */
  data object Refused : ProbeResult

  /** Nessuna risposta entro il tempo. */
  data object TimedOut : ProbeResult

  /** La rete del binding non porta al NAS (rete caduta, nessuna rotta). */
  data object NoRoute : ProbeResult
}

/**
 * La sonda di raggiungibilita': **solo una connessione TCP alla 445**, che non sveglia i dischi (E-R15). Mai
 * `list`, `stat` o `getShareInformation` per sapere "se c'e'". Richiede un percorso gia' consentito.
 */
interface ReachabilityProbe {
  suspend fun tcp445(route: NasRoute.Allowed, nas: NasAddress, timeout: Duration): ProbeResult
}

/**
 * Il bersaglio del Wake-on-LAN: il MAC si inserisce a mano (da Android 10 l'ARP non si legge).
 *
 * @property mac sei byte, in forma `aa:bb:cc:dd:ee:ff` (validata da chi implementa).
 */
data class WolTarget(val mac: String, val nas: NasAddress)

/** Come e' andato un risveglio (§2.5). */
sealed interface WolOutcome {
  /** La 445 rispondeva gia': nessun pacchetto mandato. */
  data object AlreadyOn : WolOutcome

  /** Il NAS ha risposto dopo [after] dal primo pacchetto. */
  data class Woke(val after: Duration) : WolOutcome

  /** Tre invii in 30 s e nessuna risposta entro il tetto: nessun nuovo tentativo (E-R6). */
  data object NoEffect : WolOutcome

  /** Non si puo' da qui (fuori casa, attraverso la VPN, funzione spenta). */
  data object NotAllowed : WolOutcome
}

/**
 * Wake-on-LAN (piano 2.6), dietro `FeatureFlags.wol` (spento, D5) e solo con `place == HOME`, mai attraverso
 * la VPN.
 */
interface WakeOnLan {
  val enabled: Boolean

  suspend fun wake(target: WolTarget): WolOutcome
}
