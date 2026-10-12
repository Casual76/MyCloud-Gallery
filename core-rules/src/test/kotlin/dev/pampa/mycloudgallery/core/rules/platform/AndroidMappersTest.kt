package dev.pampa.mycloudgallery.core.rules.platform

import android.os.BatteryManager
import android.os.PowerManager
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.rules.NetworkState
import dev.pampa.mycloudgallery.core.rules.NetworkTransport
import dev.pampa.mycloudgallery.core.rules.ThermalStatus
import dev.pampa.mycloudgallery.core.rules.platform.AndroidMappers.CapabilityFlags
import org.junit.Test

/** Le traduzioni da Android al motore, senza Android (solo numeri e booleani). */
class AndroidMappersTest {

  @Test
  fun inCarica_eAlimentazioneCollegata_nonLoStatoDiCarica() {
    // EXTRA_PLUGGED: 1 = AC, 2 = USB, 4 = wireless, 8 = dock; 0 = a batteria.
    assertThat(AndroidMappers.isPluggedIn(BatteryManager.BATTERY_PLUGGED_AC)).isTrue()
    assertThat(AndroidMappers.isPluggedIn(BatteryManager.BATTERY_PLUGGED_USB)).isTrue()
    assertThat(AndroidMappers.isPluggedIn(BatteryManager.BATTERY_PLUGGED_WIRELESS)).isTrue()
    assertThat(AndroidMappers.isPluggedIn(8)).isTrue()
    assertThat(AndroidMappers.isPluggedIn(0)).isFalse()
    assertThat(AndroidMappers.isPluggedIn(-1)).isFalse()
  }

  @Test
  fun protezioneBatteria_collegatoMaCaricaSospesa_siRiconoscePerLaUi_maContaComeInCarica() {
    // S25: 80%, USB collegato, status 4 = NOT_CHARGING (docs/android.md 2.2 bis).
    val plugged = BatteryManager.BATTERY_PLUGGED_USB
    val notCharging = BatteryManager.BATTERY_STATUS_NOT_CHARGING

    assertThat(AndroidMappers.isPluggedIn(plugged)).isTrue()
    assertThat(AndroidMappers.isChargingPaused(plugged, notCharging)).isTrue()
    assertThat(AndroidMappers.isChargingPaused(plugged, BatteryManager.BATTERY_STATUS_CHARGING)).isFalse()
    // Scollegato non e' "carica sospesa", e' scollegato.
    assertThat(AndroidMappers.isChargingPaused(0, notCharging)).isFalse()
  }

  @Test
  fun livelloInPercentuale() {
    assertThat(AndroidMappers.levelPercent(80, 100)).isEqualTo(80)
    assertThat(AndroidMappers.levelPercent(4, 5)).isEqualTo(80)
    assertThat(AndroidMappers.levelPercent(0, 100)).isEqualTo(0)
    assertThat(AndroidMappers.levelPercent(120, 100)).isEqualTo(100)
    // Dati mancanti: 100, per non bloccare per soglia un telefono di cui non si sa niente.
    assertThat(AndroidMappers.levelPercent(-1, 100)).isEqualTo(100)
    assertThat(AndroidMappers.levelPercent(50, 0)).isEqualTo(100)
  }

  @Test
  fun statoTermico() {
    assertThat(AndroidMappers.thermalStatus(PowerManager.THERMAL_STATUS_NONE)).isEqualTo(ThermalStatus.NONE)
    assertThat(AndroidMappers.thermalStatus(PowerManager.THERMAL_STATUS_LIGHT)).isEqualTo(ThermalStatus.LIGHT)
    assertThat(AndroidMappers.thermalStatus(PowerManager.THERMAL_STATUS_MODERATE)).isEqualTo(ThermalStatus.MODERATE)
    assertThat(AndroidMappers.thermalStatus(PowerManager.THERMAL_STATUS_SEVERE)).isEqualTo(ThermalStatus.SEVERE)
    assertThat(AndroidMappers.thermalStatus(PowerManager.THERMAL_STATUS_CRITICAL)).isEqualTo(ThermalStatus.CRITICAL)
    assertThat(AndroidMappers.thermalStatus(PowerManager.THERMAL_STATUS_EMERGENCY)).isEqualTo(ThermalStatus.EMERGENCY)
    assertThat(AndroidMappers.thermalStatus(PowerManager.THERMAL_STATUS_SHUTDOWN)).isEqualTo(ThermalStatus.SHUTDOWN)
    assertThat(AndroidMappers.thermalStatus(99)).isEqualTo(ThermalStatus.SHUTDOWN)
    assertThat(AndroidMappers.thermalStatus(-1)).isEqualTo(ThermalStatus.NONE)
  }

  @Test
  fun headroom_nanDiventaNull() {
    assertThat(AndroidMappers.thermalHeadroom(Float.NaN)).isNull()
    assertThat(AndroidMappers.thermalHeadroom(-1f)).isNull()
    assertThat(AndroidMappers.thermalHeadroom(0.42f)).isEqualTo(0.42f)
    assertThat(AndroidMappers.thermalHeadroom(1.2f)).isEqualTo(1.2f)
  }

  // --- Rete ---------------------------------------------------------------------------------------------

  private val wifi = CapabilityFlags(wifi = true, notMetered = true, notRoaming = true, validated = true)

  @Test
  fun wifiNormale() {
    assertThat(AndroidMappers.network(wifi))
      .isEqualTo(NetworkState(NetworkTransport.WIFI, viaVpn = false, metered = false, roaming = false, validated = true))
  }

  @Test
  fun wifiAConsumo() {
    val hotspot = wifi.copy(notMetered = false)

    assertThat(AndroidMappers.network(hotspot).metered).isTrue()
  }

  @Test
  fun cellulare_roamingESenza() {
    val mobile = CapabilityFlags(cellular = true, notMetered = false, notRoaming = true, validated = true)

    assertThat(AndroidMappers.network(mobile).transport).isEqualTo(NetworkTransport.CELLULAR)
    assertThat(AndroidMappers.network(mobile).metered).isTrue()
    assertThat(AndroidMappers.network(mobile).roaming).isFalse()
    assertThat(AndroidMappers.network(mobile.copy(notRoaming = false)).roaming).isTrue()
  }

  @Test
  fun ilWifiNonESmaiInRoaming() {
    assertThat(AndroidMappers.network(wifi.copy(notRoaming = false)).roaming).isFalse()
  }

  @Test
  fun vpnConIlTrasportoDellaReteSottostante() {
    val vpnOverWifi = wifi.copy(vpn = true)
    val vpnOverMobile = CapabilityFlags(cellular = true, vpn = true, notMetered = false, validated = true)

    assertThat(AndroidMappers.network(vpnOverWifi)).isEqualTo(NetworkState(NetworkTransport.WIFI, viaVpn = true))
    assertThat(AndroidMappers.network(vpnOverMobile).transport).isEqualTo(NetworkTransport.CELLULAR)
    assertThat(AndroidMappers.network(vpnOverMobile).viaVpn).isTrue()
  }

  @Test
  fun vpnSenzaTrasporto_siUsaLaReteSottostanteTrovataAParte() {
    val bareVpn = CapabilityFlags(vpn = true, notMetered = true, validated = true)
    val underlying = CapabilityFlags(cellular = true, notMetered = false, notRoaming = false, validated = true)

    val network = AndroidMappers.network(bareVpn, underlying)

    assertThat(network.transport).isEqualTo(NetworkTransport.CELLULAR)
    assertThat(network.viaVpn).isTrue()
    assertThat(network.roaming).isTrue()
    assertThat(network.metered).isTrue()
    // Senza una rete sottostante non c'e' niente di usabile.
    assertThat(AndroidMappers.network(bareVpn, null)).isEqualTo(NetworkState.OFFLINE)
  }

  @Test
  fun reteNonValidata_paginaDiAccesso() {
    assertThat(AndroidMappers.network(wifi.copy(validated = false)).validated).isFalse()
  }

  @Test
  fun nessunaRete_eOffline() {
    assertThat(AndroidMappers.network(null)).isEqualTo(NetworkState.OFFLINE)
    assertThat(AndroidMappers.network(CapabilityFlags(validated = true))).isEqualTo(NetworkState.OFFLINE)
  }

  @Test
  fun ethernet() {
    val ethernet = CapabilityFlags(ethernet = true, notMetered = true, validated = true)

    assertThat(AndroidMappers.network(ethernet).transport).isEqualTo(NetworkTransport.ETHERNET)
  }
}
