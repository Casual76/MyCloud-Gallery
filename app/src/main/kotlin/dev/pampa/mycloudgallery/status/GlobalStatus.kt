package dev.pampa.mycloudgallery.status

import androidx.annotation.StringRes
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkAccess
import dev.pampa.mycloudgallery.core.network.contract.NetPlace
import dev.pampa.mycloudgallery.core.network.contract.NetworkSnapshot
import dev.pampa.mycloudgallery.core.network.contract.ProbeResult
import dev.pampa.mycloudgallery.core.network.contract.VpnObservation

/** Cosa succede toccando l'indicatore. */
enum class StatusAction {
  /** I dettagli: la Diagnostica. */
  OPEN_DIAGNOSTICS,

  /** "VPN spenta · Apri WireGuard". */
  OPEN_WIREGUARD,

  /** "Serve la password del NAS": l'account NAS nelle Impostazioni. */
  ENTER_PASSWORD,
}

/**
 * L'indicatore di stato globale (docs/fase2-progetto.md §3.5): una frase sola che dice dove si e' rispetto
 * al NAS e se il NAS c'e'. Una voce per riga della tabella, piu' due casi che la tabella lascia impliciti:
 * [HOME_IDLE] (a casa, sessione chiusa per inattivita': normale) e [LOCAL_NETWORK_BLOCKED] (detto prima di
 * "NAS non raggiungibile", come chiede android.md 7.3).
 *
 * Il tono non e' l'unico segnale: ogni stato ha la sua frase.
 */
enum class GlobalStatus(@StringRes val label: Int, val tone: FluidTone, val action: StatusAction) {
  HOME_CONNECTED(R.string.status_home_connected, FluidTone.Success, StatusAction.OPEN_DIAGNOSTICS),
  HOME_IDLE(R.string.status_home_idle, FluidTone.Neutral, StatusAction.OPEN_DIAGNOSTICS),
  AWAY_VPN_WIFI(R.string.status_away_vpn_wifi, FluidTone.Info, StatusAction.OPEN_DIAGNOSTICS),
  AWAY_VPN_MOBILE(R.string.status_away_vpn_mobile, FluidTone.Info, StatusAction.OPEN_DIAGNOSTICS),
  ROAMING(R.string.status_roaming, FluidTone.Warning, StatusAction.OPEN_DIAGNOSTICS),
  VPN_OFF(R.string.status_vpn_off, FluidTone.Warning, StatusAction.OPEN_WIREGUARD),
  UNTRUSTED(R.string.status_untrusted, FluidTone.Warning, StatusAction.OPEN_DIAGNOSTICS),
  FOREIGN_VPN(R.string.status_foreign_vpn, FluidTone.Warning, StatusAction.OPEN_DIAGNOSTICS),
  WAKING_NAS(R.string.status_waking_nas, FluidTone.Info, StatusAction.OPEN_DIAGNOSTICS),
  NAS_UNREACHABLE(R.string.status_nas_unreachable, FluidTone.Warning, StatusAction.OPEN_DIAGNOSTICS),
  LOCAL_NETWORK_BLOCKED(R.string.status_local_network_blocked, FluidTone.Danger, StatusAction.OPEN_DIAGNOSTICS),
  NEEDS_PASSWORD(R.string.status_needs_password, FluidTone.Danger, StatusAction.ENTER_PASSWORD),
  OFFLINE(R.string.status_offline, FluidTone.Neutral, StatusAction.OPEN_DIAGNOSTICS),
  ;

  companion object {

    /**
     * Lo stato da mostrare. L'ordine conta: prima cio' che chiede un gesto all'utente (la password), poi la
     * rete (senza un percorso il NAS non c'entra), poi il NAS.
     */
    fun from(
      network: NetworkSnapshot,
      nas: NasConnState,
      probe: ProbeResult? = null,
      breakerOpen: Boolean = false,
    ): GlobalStatus {
      // L'interruttore e' persistente: dopo un riavvio la sessione e' "a riposo" ma la password va comunque reinserita.
      if (nas is NasConnState.AuthBlocked || breakerOpen) return NEEDS_PASSWORD
      // La chiave del Keystore invalidata (E-S9, RE-9): nessuna password da provare. Non e' "NAS non raggiungibile": il NAS risponde, e
      // serve una password nuova.
      if (nas is NasConnState.Unavailable && nas.error == NasError.CredentialsMissing) return NEEDS_PASSWORD
      when (network.place) {
        NetPlace.OFFLINE -> return OFFLINE
        NetPlace.UNTRUSTED -> return if (network.vpn.isForeign()) FOREIGN_VPN else UNTRUSTED
        NetPlace.VPN_OFF -> return VPN_OFF
        NetPlace.ROAMING -> return ROAMING
        NetPlace.HOME, NetPlace.AWAY_VPN_WIFI, NetPlace.AWAY_VPN_MOBILE -> Unit
      }
      when (nas) {
        is NasConnState.WakingUp -> return WAKING_NAS
        is NasConnState.Unavailable ->
          return if (nas.error == NasError.LocalNetworkBlocked) LOCAL_NETWORK_BLOCKED else NAS_UNREACHABLE
        is NasConnState.Ready -> Unit
        NasConnState.Idle, NasConnState.Connecting -> {
          // Senza una sessione aperta: la rete locale negata si dice prima di "NAS non raggiungibile" (android.md
          // 7.3), poi l'esito della sonda TCP 445 sul percorso di adesso (§3.5, "percorso consentito ma la sonda fallisce").
          if (network.place == NetPlace.HOME && network.localNetwork == LocalNetworkAccess.DENIED) return LOCAL_NETWORK_BLOCKED
          if (probe != null && probe !is ProbeResult.Open) return NAS_UNREACHABLE
        }
        is NasConnState.AuthBlocked -> Unit
      }
      return when (network.place) {
        NetPlace.HOME -> if (nas is NasConnState.Ready) HOME_CONNECTED else HOME_IDLE
        NetPlace.AWAY_VPN_WIFI -> AWAY_VPN_WIFI
        else -> AWAY_VPN_MOBILE
      }
    }

    private fun VpnObservation.isForeign(): Boolean = this == VpnObservation.FOREIGN_VPN || this == VpnObservation.UNKNOWN
  }
}
