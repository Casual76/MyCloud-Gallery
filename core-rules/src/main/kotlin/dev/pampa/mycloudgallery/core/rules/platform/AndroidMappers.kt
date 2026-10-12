package dev.pampa.mycloudgallery.core.rules.platform

import android.os.BatteryManager
import android.os.PowerManager
import dev.pampa.mycloudgallery.core.rules.NetworkState
import dev.pampa.mycloudgallery.core.rules.NetworkTransport
import dev.pampa.mycloudgallery.core.rules.ThermalStatus

/**
 * Le regole di traduzione da cio' che dice Android a cio' che capisce il motore, SENZA toccare
 * Android: prendono solo numeri e booleani (le costanti di `BatteryManager` e `PowerManager` sono
 * inlined dal compilatore), quindi si provano con test JVM puri.
 */
internal object AndroidMappers {

  /**
   * "In carica" = alimentazione collegata: `EXTRA_PLUGGED` diverso da zero (AC, USB, wireless, dock).
   * **Non** `EXTRA_STATUS`: con la Protezione batteria Samsung lo stato e' NOT_CHARGING all'80-90% con il
   * cavo collegato, e il telefono deve contare come in carica (docs/android.md, scoperta 1; E-P16).
   */
  fun isPluggedIn(pluggedExtra: Int): Boolean = pluggedExtra > 0

  /** Collegato ma la carica e' ferma (Protezione batteria): solo informativo, per la UI. */
  fun isChargingPaused(pluggedExtra: Int, statusExtra: Int): Boolean =
    isPluggedIn(pluggedExtra) && statusExtra == BatteryManager.BATTERY_STATUS_NOT_CHARGING

  /** Livello in percentuale da `EXTRA_LEVEL` / `EXTRA_SCALE`. Senza dati validi: 100 (non blocca per soglia). */
  fun levelPercent(level: Int, scale: Int): Int {
    if (level < 0 || scale <= 0) return 100
    return (level * 100L / scale).toInt().coerceIn(0, 100)
  }

  fun thermalStatus(status: Int): ThermalStatus =
    when {
      status <= PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NONE
      status == PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
      status == PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
      status == PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
      status == PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
      status == PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.EMERGENCY
      else -> ThermalStatus.SHUTDOWN
    }

  /** `getThermalHeadroom` risponde NaN se non supportato (o se chiamato troppo spesso): qui diventa null. */
  fun thermalHeadroom(raw: Float): Float? = raw.takeIf { !it.isNaN() && it >= 0f }

  /** Cio' che serve di `NetworkCapabilities`, ridotto a booleani. */
  data class CapabilityFlags(
    val wifi: Boolean = false,
    val cellular: Boolean = false,
    val ethernet: Boolean = false,
    val vpn: Boolean = false,
    /** `NET_CAPABILITY_NOT_METERED` o `TEMPORARILY_NOT_METERED`. */
    val notMetered: Boolean = false,
    /** `NET_CAPABILITY_NOT_ROAMING`. */
    val notRoaming: Boolean = true,
    /** `NET_CAPABILITY_VALIDATED`: falso con pagina di accesso o senza internet. */
    val validated: Boolean = false,
  ) {
    val hasUnderlyingTransport: Boolean get() = wifi || cellular || ethernet
  }

  /**
   * La rete in uso. Una VPN eredita i trasporti della rete sottostante (es. {VPN, WIFI}); se non li
   * porta si usa [underlying], la rete reale trovata a parte. [NetworkState.isHome] resta falso: lo
   * mette il provider dal segnale della Fase 2.
   */
  fun network(active: CapabilityFlags?, underlying: CapabilityFlags? = null): NetworkState {
    if (active == null) return NetworkState.OFFLINE
    val facts =
      if (active.vpn && !active.hasUnderlyingTransport && underlying != null) {
        underlying.copy(vpn = true, validated = active.validated && underlying.validated)
      } else {
        active
      }
    val transport =
      when {
        facts.wifi -> NetworkTransport.WIFI
        facts.cellular -> NetworkTransport.CELLULAR
        facts.ethernet -> NetworkTransport.ETHERNET
        else -> NetworkTransport.NONE
      }
    if (transport == NetworkTransport.NONE) return NetworkState.OFFLINE
    return NetworkState(
      transport = transport,
      viaVpn = facts.vpn,
      metered = !facts.notMetered,
      // Il roaming ha senso solo sul cellulare; il Wi-Fi non e' mai in roaming.
      roaming = transport == NetworkTransport.CELLULAR && !facts.notRoaming,
      validated = facts.validated,
    )
  }
}
