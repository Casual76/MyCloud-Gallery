package dev.pampa.mycloudgallery.core.network.testing

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.network.contract.NetworkSnapshot
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.NetworkClassifier
import org.junit.Test

/**
 * Il fake dell'ambiente di rete traduce con la stessa regola dell'ambiente vero: chi lo usa nei test a valle vede
 * "casa" e "VPN" come li vede l'app.
 */
class FakeNetworkEnvironmentTest {

  private fun classOf(snapshot: NetworkSnapshot) = NetworkClassifier.classify(FakeNetworkEnvironment(snapshot).toNetworkState())

  @Test
  fun leIstantaneeDiProva_siTraducono_comeNellApp() {
    assertThat(classOf(Snapshots.home())).isEqualTo(NetworkClass.HOME_WIFI)
    assertThat(classOf(Snapshots.awayVpnWifi())).isEqualTo(NetworkClass.EXTERNAL_WIFI)
    assertThat(classOf(Snapshots.awayVpnMobile())).isEqualTo(NetworkClass.MOBILE)
    assertThat(classOf(Snapshots.roaming())).isEqualTo(NetworkClass.ROAMING)
    assertThat(classOf(Snapshots.vpnOff())).isEqualTo(NetworkClass.UNTRUSTED)
    assertThat(classOf(Snapshots.untrusted())).isEqualTo(NetworkClass.UNTRUSTED)
    // Una VPN di terzi non rende raggiungibile il NAS: per le regole la rete non e' riconosciuta (E-S16).
    assertThat(classOf(Snapshots.foreignVpn())).isEqualTo(NetworkClass.UNTRUSTED)
    assertThat(classOf(Snapshots.offline())).isEqualTo(NetworkClass.OFFLINE)
  }

  @Test
  fun assegnareLIstantanea_aggiornaAncheIsAtHome() {
    val env = FakeNetworkEnvironment(Snapshots.offline())
    assertThat(env.isAtHome.value).isFalse()

    env.current = Snapshots.home()
    assertThat(env.isAtHome.value).isTrue()
    env.current = Snapshots.awayVpnMobile()
    assertThat(env.isAtHome.value).isFalse()
  }
}
