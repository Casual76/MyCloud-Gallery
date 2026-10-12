package dev.pampa.mycloudgallery.core.network.vpn

import android.app.Application
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Gli intent veri di WireGuard (docs/research/smb-vpn.md 3.1): broadcast esplicito, azione, extra `tunnel`.
 * Che WireGuard li accetti su Android 16/17 si prova sul campo (M10, T-18).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidWireGuardPlatformTest {

  private val application: Application = ApplicationProvider.getApplicationContext()
  private val platform = AndroidWireGuardPlatform(application)

  private fun installWireGuard() {
    val info = PackageInfo().apply { packageName = AndroidWireGuardPlatform.WIREGUARD_PACKAGE }
    shadowOf(application.packageManager).installPackage(info)
  }

  @Test
  fun nonInstallato_nonVisibile() {
    assertThat(platform.isInstalled()).isFalse()
    assertThat(platform.launchIntent()).isNull()
  }

  @Test
  fun installato() {
    installWireGuard()

    assertThat(platform.isInstalled()).isTrue()
  }

  @Test
  fun ilPermessoDiControlloEQuelloDiWireGuard() {
    assertThat(platform.hasControlPermission()).isFalse()

    shadowOf(application).grantPermissions("com.wireguard.android.permission.CONTROL_TUNNELS")

    assertThat(platform.hasControlPermission()).isTrue()
  }

  @Test
  fun accendere_unBroadcastEsplicitoAWireGuardConIlNomeDelTunnel() {
    platform.sendTunnelCommand(up = true, tunnelName = "casa")

    val intent = shadowOf(application).broadcastIntents.single()
    assertThat(intent.action).isEqualTo("com.wireguard.android.action.SET_TUNNEL_UP")
    assertThat(intent.`package`).isEqualTo("com.wireguard.android")
    assertThat(intent.getStringExtra("tunnel")).isEqualTo("casa")
    // Esplicito: nessun'altra app puo' riceverlo.
    assertThat(intent.component).isNull()
    assertThat(intent.data).isNull()
  }

  @Test
  fun spegnere_usaLAzioneGiu() {
    platform.sendTunnelCommand(up = false, tunnelName = "casa")

    assertThat(shadowOf(application).broadcastIntents.single().action).isEqualTo("com.wireguard.android.action.SET_TUNNEL_DOWN")
  }

  @Test
  fun unNomeStranoNonDiventaMaiUnIntent() {
    for (bad in listOf("", "casa; x", "a b", "0123456789abcdef", "../x")) {
      assertThrows(IllegalArgumentException::class.java) { platform.sendTunnelCommand(up = true, tunnelName = bad) }
    }

    assertThat(shadowOf(application).broadcastIntents).isEmpty()
  }

  @Test
  fun unaltroPacchetto_siPuoConfigurare() {
    val other = AndroidWireGuardPlatform(application, wireGuardPackage = "org.example.wg")
    shadowOf(application).grantPermissions("org.example.wg.permission.CONTROL_TUNNELS")

    other.sendTunnelCommand(up = true, tunnelName = "casa")

    assertThat(other.hasControlPermission()).isTrue()
    assertThat(shadowOf(application).broadcastIntents.single().`package`).isEqualTo("org.example.wg")
  }
}
