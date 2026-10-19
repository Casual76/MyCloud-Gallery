package dev.pampa.mycloudgallery.core.network.vpn

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.network.contract.ControlPermissionOutcome
import dev.pampa.mycloudgallery.core.network.contract.VpnCapability
import dev.pampa.mycloudgallery.core.network.contract.VpnControlConfig
import dev.pampa.mycloudgallery.core.network.contract.VpnController
import dev.pampa.mycloudgallery.core.network.contract.WireGuardPermission
import dev.pampa.mycloudgallery.core.network.testing.FakeNetworkEnvironment
import dev.pampa.mycloudgallery.core.network.testing.FakeVpnController
import dev.pampa.mycloudgallery.core.network.testing.FakeWireGuardPlatform
import dev.pampa.mycloudgallery.core.network.testing.InMemoryVpnControlConfigStore
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Il permesso `CONTROL_TUNNELS` di WireGuard (verifica funzionale, RE-1): un nome solo per il manifest, la piattaforma e la richiesta
 * a runtime, l'esito del dialogo letto sempre allo stesso modo, e il finto che si comporta come il vero.
 */
@RunWith(RobolectricTestRunner::class)
class WireGuardPermissionTest {

  private val application: Application = ApplicationProvider.getApplicationContext()

  @Test
  fun ilNomeDelPermessoEQuelloDelManifestEDellaPiattaforma() {
    val manifest = File("src/main/AndroidManifest.xml").readText()
    assertThat(manifest).contains("""<uses-permission android:name="${WireGuardPermission.NAME}" />""")

    shadowOf(application).grantPermissions(WireGuardPermission.NAME)
    assertThat(AndroidWireGuardPlatform(application).hasControlPermission()).isTrue()
  }

  @Test
  fun lEsitoDelDialogo_concesso_negatoRichiedibile_negatoPerSempre() {
    assertThat(WireGuardPermission.outcomeOf(granted = true, canAskAgain = false)).isEqualTo(ControlPermissionOutcome.GRANTED)
    assertThat(WireGuardPermission.outcomeOf(granted = true, canAskAgain = true)).isEqualTo(ControlPermissionOutcome.GRANTED)
    assertThat(WireGuardPermission.outcomeOf(granted = false, canAskAgain = true)).isEqualTo(ControlPermissionOutcome.DENIED)
    assertThat(WireGuardPermission.outcomeOf(granted = false, canAskAgain = false)).isEqualTo(ControlPermissionOutcome.DENIED_FOR_GOOD)
  }

  /** Il finto parte come il vero e cambia come lui: solo rileggendo (`FakeVpnController` nascondeva RE-1 partendo da `READY`). */
  @Test
  fun ilFintoSiComportaComeIlVero_ilPermessoConcessoSiVedeSoloRileggendo() {
    val platform = FakeWireGuardPlatform(installed = true, permission = false)
    val real = WireGuardVpnController(platform, InMemoryVpnControlConfigStore(VpnControlConfig(enabled = true, tunnelName = "casa")), FakeNetworkEnvironment())
    val fake = FakeVpnController(VpnCapability.PERMISSION_MISSING).apply { capabilityOnRefresh += VpnCapability.READY }

    val observed = mutableMapOf<String, List<VpnCapability>>()
    for ((name, controller) in listOf<Pair<String, VpnController>>("vero" to real, "finto" to fake)) {
      val seen = mutableListOf(controller.capability.value)
      platform.permission = true
      seen += controller.capability.value // concesso, ma nessuno ha riletto
      controller.refresh()
      seen += controller.capability.value
      observed[name] = seen
    }

    assertThat(observed["vero"]).containsExactly(VpnCapability.PERMISSION_MISSING, VpnCapability.PERMISSION_MISSING, VpnCapability.READY).inOrder()
    assertThat(observed["finto"]).isEqualTo(observed["vero"])
  }
}
