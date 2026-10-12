package dev.pampa.mycloudgallery.core.network.contract

import dev.pampa.mycloudgallery.core.rules.HomeNetworkSignal
import dev.pampa.mycloudgallery.core.rules.NetworkState
import kotlinx.coroutines.flow.StateFlow

/**
 * Dove si trova il telefono rispetto al NAS (piano 2.1). Sono gli stati che l'indicatore globale mostra
 * (docs/fase2-progetto.md §3.5). "NAS spento o irraggiungibile" non e' qui: e' un fatto del NAS, non della
 * rete, e lo aggiunge `core-nas` (`NasConnState`).
 */
enum class NetPlace {
  /** Sul Wi-Fi (o Ethernet) di casa, riconosciuto dall'impronta ([HomeMatch.Match]). */
  HOME,

  /** Fuori casa, su un Wi-Fi, con la VPN di casa accesa. */
  AWAY_VPN_WIFI,

  /** Fuori casa, sui dati mobili (o su un Wi-Fi a consumo, E-R13), con la VPN di casa accesa. */
  AWAY_VPN_MOBILE,

  /** Dati mobili in roaming: le attivita' automatiche si fermano. */
  ROAMING,

  /** Fuori casa e la VPN di casa e' spenta: niente NAS finche' non si accende (E-R3). */
  VPN_OFF,

  /**
   * Una rete da cui il NAS non si contatta mai: non e' casa (anche se l'IP coincide, E-S10), oppure c'e'
   * una VPN che non e' quella di casa (E-S16).
   */
  UNTRUSTED,

  /** Nessuna rete validata (anche: pagina di accesso di un captive portal). */
  OFFLINE,
}

/** Il trasporto della rete sottostante (quella vera, sotto un'eventuale VPN). */
enum class NetTransport { WIFI, CELLULAR, ETHERNET, OTHER }

/**
 * La rete sottostante, cosi' come Android la descrive, senza permesso di posizione (niente SSID).
 *
 * @property networkId un identificativo opaco della `Network` di Android (cambia a ogni riconnessione): serve
 *   al binding dei socket e a capire se la rete e' cambiata, mai a riconoscere casa.
 */
data class UnderlyingNetwork(
  val networkId: String,
  val transport: NetTransport,
  val metered: Boolean,
  val roaming: Boolean,
  val validated: Boolean,
  val captivePortal: Boolean,
)

/** Cosa si sa della VPN in questo momento. */
enum class VpnObservation {
  /** Nessuna VPN attiva. */
  NONE,

  /** C'e' una VPN e porta verso casa: rotte o DNS coprono la sottorete o l'ULA di casa (D2). */
  HOME_TUNNEL,

  /** C'e' una VPN e NON e' quella di casa (E-S16): nessun SMB. */
  FOREIGN_VPN,

  /** C'e' una VPN ma non si riesce a dire di chi sia: si tratta come estranea. */
  UNKNOWN,
}

/**
 * Il permesso di usare la rete locale (Android 17 con target 37: `ACCESS_LOCAL_NETWORK`). Con il target 36
 * di oggi e' concesso implicitamente; il ramo per il 37 e' gia' pronto (docs/android.md 7.3).
 */
enum class LocalNetworkAccess {
  /** Concesso esplicitamente. */
  GRANTED,

  /** Concesso perche' il target dell'app e' ancora 36 (concessione "temporanea" per Android). */
  GRANTED_IMPLICIT,

  /** Negato: il NAS non si raggiunge e la Diagnostica lo dice prima di "NAS spento". */
  DENIED,

  UNKNOWN,
}

/**
 * Una fotografia della rete. Tutto cio' che serve per decidere il percorso verso il NAS e per l'indicatore.
 *
 * @property at l'istante della fotografia, orologio monotono del telefono (ms): serve a ordinare, non a datare.
 */
data class NetworkSnapshot(
  val place: NetPlace,
  val underlying: UnderlyingNetwork?,
  val home: HomeMatch,
  val vpn: VpnObservation,
  val localNetwork: LocalNetworkAccess,
  val ipv6Global: Boolean,
  val at: Long,
) {
  companion object {
    /** Il punto di partenza: nessuna rete osservata ancora. */
    val OFFLINE = NetworkSnapshot(
      place = NetPlace.OFFLINE,
      underlying = null,
      home = HomeMatch.Unknown,
      vpn = VpnObservation.NONE,
      localNetwork = LocalNetworkAccess.UNKNOWN,
      ipv6Global = false,
      at = 0L,
    )
  }
}

/**
 * L'ambiente di rete (P2): l'unico posto che decide "casa", "VPN di casa" e "rete estranea".
 *
 * Implementa anche il segnale `HomeNetworkSignal` gia' previsto dal motore delle regole e traduce la propria
 * istantanea nel `NetworkState` che le regole usano ([toNetworkState]): cosi' "casa" ha una sola definizione
 * in tutta l'app.
 */
interface NetworkEnvironment : HomeNetworkSignal {
  val snapshot: StateFlow<NetworkSnapshot>

  /** La rete in uso come la vedono le regole delle attivita' (core-rules). */
  fun toNetworkState(): NetworkState
}
