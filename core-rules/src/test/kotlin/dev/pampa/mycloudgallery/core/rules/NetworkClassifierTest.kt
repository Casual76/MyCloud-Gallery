package dev.pampa.mycloudgallery.core.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NetworkClassifierTest {

  private fun classify(
    transport: NetworkTransport,
    viaVpn: Boolean = false,
    isHome: Boolean = false,
    metered: Boolean = false,
    roaming: Boolean = false,
    validated: Boolean = true,
  ) = NetworkClassifier.classify(NetworkState(transport, viaVpn, isHome, metered, roaming, validated))

  @Test
  fun wifiDiCasa_conOSenzaVpn() {
    assertThat(classify(NetworkTransport.WIFI, isHome = true)).isEqualTo(NetworkClass.HOME_WIFI)
    assertThat(classify(NetworkTransport.WIFI, isHome = true, viaVpn = true)).isEqualTo(NetworkClass.HOME_WIFI)
    assertThat(classify(NetworkTransport.ETHERNET, isHome = true)).isEqualTo(NetworkClass.HOME_WIFI)
  }

  @Test
  fun wifiDiCasaMarcatoAConsumo_restaCasa_ilTrafficoVersoIlNasELocale() {
    assertThat(classify(NetworkTransport.WIFI, isHome = true, metered = true)).isEqualTo(NetworkClass.HOME_WIFI)
  }

  @Test
  fun altroWifiViaVpn_eWifiEsterno() {
    assertThat(classify(NetworkTransport.WIFI, viaVpn = true)).isEqualTo(NetworkClass.EXTERNAL_WIFI)
  }

  @Test
  fun wifiAConsumoOHotspot_conLaVpn_eComeDatiMobili_E_R13() {
    assertThat(classify(NetworkTransport.WIFI, viaVpn = true, metered = true)).isEqualTo(NetworkClass.MOBILE)
  }

  @Test
  fun wifiSconosciutoSenzaVpn_nonSiContattaIlNas() {
    assertThat(classify(NetworkTransport.WIFI)).isEqualTo(NetworkClass.UNTRUSTED)
    assertThat(classify(NetworkTransport.WIFI, metered = true)).isEqualTo(NetworkClass.UNTRUSTED)
  }

  @Test
  fun datiMobili_serveLaVpn() {
    assertThat(classify(NetworkTransport.CELLULAR, viaVpn = true, metered = true)).isEqualTo(NetworkClass.MOBILE)
    assertThat(classify(NetworkTransport.CELLULAR, metered = true)).isEqualTo(NetworkClass.UNTRUSTED)
  }

  @Test
  fun roaming_vinceSuTutto() {
    assertThat(classify(NetworkTransport.CELLULAR, viaVpn = true, roaming = true)).isEqualTo(NetworkClass.ROAMING)
    assertThat(classify(NetworkTransport.CELLULAR, roaming = true)).isEqualTo(NetworkClass.ROAMING)
  }

  @Test
  fun nessunaReteOReteNonVerificata_eOffline() {
    assertThat(NetworkClassifier.classify(NetworkState.OFFLINE)).isEqualTo(NetworkClass.OFFLINE)
    // Pagina di accesso (captive portal) o rete senza internet: Android non la "valida".
    assertThat(classify(NetworkTransport.WIFI, isHome = true, validated = false)).isEqualTo(NetworkClass.OFFLINE)
    assertThat(classify(NetworkTransport.CELLULAR, viaVpn = true, validated = false)).isEqualTo(NetworkClass.OFFLINE)
  }

  @Test
  fun soloLeReteChePortanoTrafficoSonoUtilizzabili() {
    assertThat(NetworkClass.entries.filter { it.carriesTraffic })
      .containsExactly(NetworkClass.HOME_WIFI, NetworkClass.EXTERNAL_WIFI, NetworkClass.MOBILE, NetworkClass.ROAMING)
    assertThat(NetworkClass.OFFLINE.asAllowedNetwork()).isNull()
    assertThat(NetworkClass.UNTRUSTED.asAllowedNetwork()).isNull()
    assertThat(NetworkClass.EXTERNAL_WIFI.asAllowedNetwork()).isEqualTo(AllowedNetwork.ANY_WIFI_VPN)
  }
}
