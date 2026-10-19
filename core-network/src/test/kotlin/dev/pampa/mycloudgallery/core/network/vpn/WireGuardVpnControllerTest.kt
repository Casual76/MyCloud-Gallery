package dev.pampa.mycloudgallery.core.network.vpn

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.network.TestSnapshots
import dev.pampa.mycloudgallery.core.network.contract.NetworkSnapshot
import dev.pampa.mycloudgallery.core.network.contract.VpnCapability
import dev.pampa.mycloudgallery.core.network.contract.VpnCommandResult
import dev.pampa.mycloudgallery.core.network.contract.VpnController
import dev.pampa.mycloudgallery.core.network.contract.VpnControlConfig
import dev.pampa.mycloudgallery.core.network.contract.VpnSkipReason
import dev.pampa.mycloudgallery.core.network.testing.FakeNetworkEnvironment
import dev.pampa.mycloudgallery.core.network.testing.FakeWireGuardPlatform
import dev.pampa.mycloudgallery.core.network.testing.InMemoryVpnControlConfigStore
import dev.pampa.mycloudgallery.core.network.testing.LinkFactsSamples
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Il controllo di WireGuard: capacita', verifica osservata, ripristino. */
@OptIn(ExperimentalCoroutinesApi::class)
class WireGuardVpnControllerTest {

  private val platform = FakeWireGuardPlatform()
  private val store = InMemoryVpnControlConfigStore(VpnControlConfig(enabled = true, tunnelName = "casa"))
  private val environment = FakeNetworkEnvironment()

  private fun controller() = WireGuardVpnController(platform, store, environment)

  /** WireGuard che, ricevuto il comando, cambia la rete dopo [after]. */
  private fun TestScope.reactWith(after: kotlin.time.Duration = 2.seconds, result: (Boolean) -> NetworkSnapshot?) {
    platform.onCommand = { up, _ ->
      backgroundScope.launch {
        delay(after)
        result(up)?.let { environment.current = it }
      }
    }
  }

  // --- Capacita' -----------------------------------------------------------------------------------------

  @Test
  fun lacapacitaSegueAppPermessoENome_nellOrdine() {
    platform.installed = false
    val controller = controller()
    assertThat(controller.capability.value).isEqualTo(VpnCapability.WIREGUARD_MISSING)

    platform.installed = true
    platform.permission = false
    controller.refresh()
    assertThat(controller.capability.value).isEqualTo(VpnCapability.PERMISSION_MISSING)

    platform.permission = true
    store.config.value = VpnControlConfig(enabled = false, tunnelName = null)
    controller.refresh()
    assertThat(controller.capability.value).isEqualTo(VpnCapability.NOT_CONFIGURED)

    // L'abilitazione e' dell'automazione: per il controllore conta solo il nome del tunnel.
    store.config.value = VpnControlConfig(enabled = false, tunnelName = "casa")
    controller.refresh()
    assertThat(controller.capability.value).isEqualTo(VpnCapability.READY)
  }

  /**
   * Verifica funzionale, RE-1: il permesso di WireGuard lo concede l'utente con il dialogo di sistema, che per l'app e' un'altra
   * activity: nessun ritorno in primo piano, nessun cambio di configurazione. Se il chiamante non rilegge, la capacita' resta "manca il
   * permesso" per sempre (le prove e la VPN gestita dall'app non si accendono).
   */
  @Test
  fun ilPermessoConcessoDalDialogoSiVedeSoloRileggendo() {
    platform.permission = false
    val controller: VpnController = controller()
    assertThat(controller.capability.value).isEqualTo(VpnCapability.PERMISSION_MISSING)

    platform.permission = true
    assertThat(controller.capability.value).isEqualTo(VpnCapability.PERMISSION_MISSING)
    controller.refresh()

    assertThat(controller.capability.value).isEqualTo(VpnCapability.READY)
  }

  @Test
  fun lacapacitaSiRilegge_quandoCambiaLaConfigurazione() = runTest {
    store.config.value = VpnControlConfig.OFF
    val controller = WireGuardVpnController(platform, store, environment, backgroundScope)
    runCurrent()
    assertThat(controller.capability.value).isEqualTo(VpnCapability.NOT_CONFIGURED)

    store.config.value = VpnControlConfig(enabled = true, tunnelName = "casa")
    runCurrent()

    assertThat(controller.capability.value).isEqualTo(VpnCapability.READY)
  }

  @Test
  fun senzaCapacita_nessunIntentEUnMotivoPerIlSalto() = runTest {
    environment.current = TestSnapshots.awayNoVpn()

    platform.installed = false
    assertThat(controller().setTunnel(up = true)).isEqualTo(VpnCommandResult.Skipped(VpnSkipReason.WIREGUARD_MISSING))
    platform.installed = true
    platform.permission = false
    assertThat(controller().setTunnel(up = true)).isEqualTo(VpnCommandResult.Skipped(VpnSkipReason.PERMISSION_MISSING))
    platform.permission = true
    store.config.value = VpnControlConfig.OFF
    assertThat(controller().setTunnel(up = true)).isEqualTo(VpnCommandResult.Skipped(VpnSkipReason.NOT_CONFIGURED))

    assertThat(platform.sent).isEmpty()
  }

  // --- Accendere -----------------------------------------------------------------------------------------

  @Test
  fun accendere_siConfermaSoloQuandoComparaLaVpnDiCasa() = runTest {
    environment.current = TestSnapshots.awayNoVpn()
    reactWith { up -> if (up) TestSnapshots.awayTunnel() else null }

    val result = controller().setTunnel(up = true)

    assertThat(result).isEqualTo(VpnCommandResult.Confirmed)
    assertThat(platform.sent).containsExactly(true to "casa")
  }

  @Test
  fun accendere_unIntentChiWireGuardIgnora_nonEUnaVpnAccesa_NotObserved_dopoIlTempo() = runTest {
    environment.current = TestSnapshots.awayNoVpn()
    val before = testScheduler.currentTime

    val result = controller().setTunnel(up = true, timeout = 10.seconds)

    assertThat(result).isEqualTo(VpnCommandResult.NotObserved)
    assertThat(testScheduler.currentTime - before).isEqualTo(10_000L)
    // L'intent e' partito, ma nessuno puo' dire "VPN accesa".
    assertThat(platform.sent).containsExactly(true to "casa")
  }

  @Test
  fun accendere_unaVpnEstraneaCheCompareNonVale() = runTest {
    environment.current = TestSnapshots.awayNoVpn()
    reactWith { up -> if (up) TestSnapshots.foreignVpn() else null }

    val result = controller().setTunnel(up = true, timeout = 5.seconds)

    assertThat(result).isEqualTo(VpnCommandResult.NotObserved)
  }

  @Test
  fun accendere_giaAcceso_nessunIntent() = runTest {
    environment.current = TestSnapshots.awayTunnel()

    assertThat(controller().setTunnel(up = true)).isEqualTo(VpnCommandResult.Skipped(VpnSkipReason.ALREADY_IN_STATE))
    assertThat(platform.sent).isEmpty()
  }

  // --- Spegnere ------------------------------------------------------------------------------------------

  @Test
  fun spegnere_siConfermaQuandoLaVpnSparisceELaReteResta() = runTest {
    environment.current = TestSnapshots.homeTunnel()
    reactWith { up -> if (!up) TestSnapshots.homeNoVpn() else null }

    val result = controller().setTunnel(up = false)

    assertThat(result).isEqualTo(VpnCommandResult.Confirmed)
    assertThat(platform.sent).containsExactly(false to "casa")
  }

  @Test
  fun spegnere_laVpnSparisceMaLaReteNonEValidata_siRiaccendeSubito_LostConnectivity() = runTest {
    environment.current = TestSnapshots.homeTunnel()
    // "VPN sempre attiva con blocco": il tunnel scende e il telefono resta senza rete.
    reactWith { up -> if (!up) TestSnapshots.snapshot(LinkFactsSamples.homeWifi(validated = false)) else null }

    val result = controller().setTunnel(up = false, timeout = 10.seconds)

    assertThat(result).isEqualTo(VpnCommandResult.LostConnectivity)
    // Prima SET_TUNNEL_DOWN, poi - senza aspettare nessuno - SET_TUNNEL_UP per ridare la rete.
    assertThat(platform.sent).containsExactly(false to "casa", true to "casa").inOrder()
  }

  @Test
  fun spegnere_nonSuccedeNiente_NotObserved_eNessunRipristino() = runTest {
    environment.current = TestSnapshots.homeTunnel()

    val result = controller().setTunnel(up = false, timeout = 10.seconds)

    assertThat(result).isEqualTo(VpnCommandResult.NotObserved)
    assertThat(platform.sent).containsExactly(false to "casa")
  }

  @Test
  fun spegnere_giaSpento_oUnaVpnEstranea_nessunIntent() = runTest {
    environment.current = TestSnapshots.homeNoVpn()
    assertThat(controller().setTunnel(up = false)).isEqualTo(VpnCommandResult.Skipped(VpnSkipReason.ALREADY_IN_STATE))

    // Una VPN di terzi non e' la nostra: non si finge di averla spenta e non si manda nessun comando.
    environment.current = TestSnapshots.foreignVpn()
    assertThat(controller().setTunnel(up = false)).isEqualTo(VpnCommandResult.Skipped(VpnSkipReason.ALREADY_IN_STATE))
    assertThat(platform.sent).isEmpty()
  }

  // --- Un comando alla volta -------------------------------------------------------------------------------

  @Test
  fun dueRichiesteInsieme_unSoloIntent() = runTest {
    environment.current = TestSnapshots.awayNoVpn()
    reactWith { up -> if (up) TestSnapshots.awayTunnel() else null }
    val controller = controller()

    val first = async { controller.setTunnel(up = true) }
    val second = async { controller.setTunnel(up = true) }
    advanceTimeBy(30.seconds)
    runCurrent()

    assertThat(first.await()).isEqualTo(VpnCommandResult.Confirmed)
    assertThat(second.await()).isEqualTo(VpnCommandResult.Skipped(VpnSkipReason.ALREADY_IN_STATE))
    assertThat(platform.sent).hasSize(1)
  }

  @Test
  fun ilNomeDelTunnelSiValida_unNomeStranoNonDiventaMaiUnIntent() {
    val valid = dev.pampa.mycloudgallery.core.network.contract.WireGuardTunnelNames
    assertThat(valid.isValid("casa")).isTrue()
    assertThat(valid.isValid("a-b_c=d+e.f")).isTrue()
    assertThat(valid.isValid("casa; rm -rf")).isFalse()
    assertThat(valid.isValid("")).isFalse()
    assertThat(valid.isValid(null)).isFalse()
    assertThat(valid.isValid("0123456789abcdef")).isFalse()
  }
}
