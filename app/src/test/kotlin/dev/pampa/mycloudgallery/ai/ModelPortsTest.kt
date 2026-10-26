package dev.pampa.mycloudgallery.ai

import android.app.Application
import android.net.Network
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.models.contract.EnsureResult
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelGateReason
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkVerdict
import dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.models.testing.FakeModelManager
import dev.pampa.mycloudgallery.core.network.contract.HomeMatch
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkAccess
import dev.pampa.mycloudgallery.core.network.contract.NetPlace
import dev.pampa.mycloudgallery.core.network.contract.NetTransport
import dev.pampa.mycloudgallery.core.network.contract.NetworkSnapshot
import dev.pampa.mycloudgallery.core.network.contract.UnderlyingNetwork
import dev.pampa.mycloudgallery.core.network.contract.VpnObservation
import dev.pampa.mycloudgallery.core.network.env.NetworkLookup
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficNetwork
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

/**
 * Le porte dei modelli che `:app` lega (Fase 6, revisione B): il cancello di rete per **ogni** sorgente (R11) con la conferma come parte della
 * richiesta (MO-2), la rete su cui escono le connessioni HTTPS (R12) e il gestore che chiede un lavoro invece di scaricare nello scope della
 * pagina (MO-1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ModelPortsTest {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

  @After
  fun tearDown() {
    scope.cancel()
  }

  private fun snapshot(
    transport: NetTransport = NetTransport.WIFI,
    metered: Boolean = false,
    roaming: Boolean = false,
    validated: Boolean = true,
    captive: Boolean = false,
    id: String = "net-1",
    offline: Boolean = false,
  ) = NetworkSnapshot(
    place = if (offline) NetPlace.OFFLINE else NetPlace.HOME,
    underlying = if (offline) null else UnderlyingNetwork(id, transport, metered, roaming, validated, captive),
    home = HomeMatch.Unknown,
    vpn = VpnObservation.NONE,
    localNetwork = LocalNetworkAccess.GRANTED,
    ipv6Global = false,
    at = 0L,
  )

  private fun verdict(s: NetworkSnapshot, trigger: ModelTrigger, source: ModelSourceKind, bytes: Long = 370L) =
    runBlocking { AppModelNetworkGate { s }.verdict(trigger, bytes, source) }

  private val everySource = ModelSourceKind.entries.filter { it != ModelSourceKind.BUNDLED }

  // --- R11 / MO-2: il cancello per ogni sorgente --------------------------------------------------------------------------------------

  @Test
  fun senzaReteNiente() {
    for (source in everySource) {
      assertThat(verdict(snapshot(offline = true), ModelTrigger.USER_NOW, source)).isEqualTo(ModelNetworkVerdict.Refused(ModelGateReason.OFFLINE))
    }
  }

  @Test
  fun suUnWifiNonAConsumoPassanoTutteLeSorgentiEOgniMotivo() {
    for (source in everySource) {
      for (trigger in ModelTrigger.entries) {
        assertThat(verdict(snapshot(), trigger, source)).isEqualTo(ModelNetworkVerdict.Allowed)
      }
    }
  }

  @Test
  fun inRoamingMaiNemmenoConLaConferma() {
    for (source in everySource) {
      for (trigger in ModelTrigger.entries) {
        assertThat(verdict(snapshot(transport = NetTransport.CELLULAR, metered = true, roaming = true), trigger, source))
          .isEqualTo(ModelNetworkVerdict.Refused(ModelGateReason.ROAMING))
      }
    }
  }

  /** R11: con la VPN accesa sui dati mobili il NAS e' raggiungibile: 157-370 MB non si leggono senza conferma, come da HTTPS. */
  @Test
  fun suiDatiMobiliInBackgroundMaiEConLaPaginaServeLaConfermaPerOgniSorgente() {
    val mobile = snapshot(transport = NetTransport.CELLULAR, metered = true)
    for (source in everySource) {
      assertThat(verdict(mobile, ModelTrigger.RULES, source)).isEqualTo(ModelNetworkVerdict.Refused(ModelGateReason.METERED))
      assertThat(verdict(mobile, ModelTrigger.ROLE_CHANGED, source)).isEqualTo(ModelNetworkVerdict.Refused(ModelGateReason.METERED))
      assertThat(verdict(mobile, ModelTrigger.USER_NOW, source, bytes = 123L)).isEqualTo(ModelNetworkVerdict.NeedsConfirmation(123L))
      // MO-2: la conferma e' parte della richiesta, non una bandiera condivisa.
      assertThat(verdict(mobile, ModelTrigger.USER_CONFIRMED, source)).isEqualTo(ModelNetworkVerdict.Allowed)
    }
  }

  @Test
  fun unWifiAConsumoSiTrattaComeIDatiMobili() {
    val metered = snapshot(metered = true)
    assertThat(verdict(metered, ModelTrigger.RULES, ModelSourceKind.HTTPS)).isEqualTo(ModelNetworkVerdict.Refused(ModelGateReason.METERED))
    assertThat(verdict(metered, ModelTrigger.USER_NOW, ModelSourceKind.NAS)).isInstanceOf(ModelNetworkVerdict.NeedsConfirmation::class.java)
  }

  /** F48: il tablet con un Wi-Fi di casa senza Internet (router senza WAN) scarica dal NAS; HTTPS ha bisogno di una rete validata. */
  @Test
  fun senzaInternetIlNasBastaEHttpsNo() {
    val lan = snapshot(validated = false)
    assertThat(verdict(lan, ModelTrigger.USER_NOW, ModelSourceKind.NAS)).isEqualTo(ModelNetworkVerdict.Allowed)
    assertThat(verdict(lan, ModelTrigger.USER_NOW, ModelSourceKind.PC_FOLDER)).isEqualTo(ModelNetworkVerdict.Allowed)
    assertThat(verdict(lan, ModelTrigger.USER_NOW, ModelSourceKind.HTTPS)).isEqualTo(ModelNetworkVerdict.Refused(ModelGateReason.NOT_VALIDATED))
    assertThat(verdict(snapshot(captive = true), ModelTrigger.USER_NOW, ModelSourceKind.HTTPS)).isEqualTo(ModelNetworkVerdict.Refused(ModelGateReason.NOT_VALIDATED))
  }

  @Test
  fun ilRegistroDelTrafficoSegnaLaReteGiudicataDalCancello() {
    assertThat(trafficNetworkOf(snapshot())).isEqualTo(TrafficNetwork.HOME_WIFI)
    assertThat(trafficNetworkOf(snapshot(transport = NetTransport.CELLULAR, metered = true))).isEqualTo(TrafficNetwork.MOBILE)
    assertThat(trafficNetworkOf(snapshot(roaming = true))).isEqualTo(TrafficNetwork.ROAMING)
  }

  // --- R12: la rete su cui esce HTTPS ----------------------------------------------------------------------------------------------------

  /** R12: la connessione usciva dalla rete predefinita del sistema (a casa magari i dati mobili), non da quella che il cancello aveva giudicato. */
  @Test
  fun r12_lAppApreHttpsSullaReteGiudicataDalCancelloNonSuQuellaPredefinita() {
    val wifi = ShadowNetwork.newInstance(11)
    val cellular = ShadowNetwork.newInstance(12)
    val lookup = object : NetworkLookup {
      override fun network(id: String): Network? = when (id) {
        "wifi-casa" -> wifi
        "dati-mobili" -> cellular
        else -> null
      }
    }
    var current = snapshot(id = "wifi-casa")
    val route = AppModelHttpsRoute({ current }, lookup)
    assertThat(route.network()).isSameInstanceAs(wifi)
    // La rete si rilegge a ogni connessione (ogni fetta): se la rete giudicata cambia, cambia anche dove esce.
    current = snapshot(transport = NetTransport.CELLULAR, metered = true, id = "dati-mobili")
    assertThat(route.network()).isSameInstanceAs(cellular)
  }

  @Test
  fun r12_senzaReteGiudicataOConUnaReteCheAndroidNonConosceNonSiApreNiente() {
    val lookup = object : NetworkLookup {
      override fun network(id: String): Network? = null
    }
    assertThat(AppModelHttpsRoute({ snapshot(offline = true) }, lookup).network()).isNull()
    assertThat(AppModelHttpsRoute({ snapshot(id = "sparita") }, lookup).network()).isNull()
  }

  // --- MO-1 / MO-2: la pagina chiede un lavoro, non scarica nel proprio scope ------------------------------------------------------

  @Test
  fun scaricaDallaPaginaChiedeUnLavoroETornaSubitoConLaCodaVisibile() = runBlocking<Unit> {
    val inner = FakeModelManager()
    val requests = mutableListOf<Pair<dev.pampa.mycloudgallery.core.models.contract.ModelId, ModelTrigger>>()
    val manager = RequestingModelManager(inner, scope) { id, trigger -> requests += id to trigger }
    val id = ModelCatalog.INDEX_440M.id

    val result = manager.ensure(id, ModelTrigger.USER_NOW, 1.minutes)

    assertThat(result).isEqualTo(EnsureResult.Deferred(ModelState.Queued(ModelTrigger.USER_NOW)))
    assertThat(requests).containsExactly(id to ModelTrigger.USER_NOW)
    // Il gestore vero non e' stato toccato (scaricherebbe nello scope della pagina): niente `ensure` sul gestore interno.
    assertThat(inner.ensured).isEmpty()
    // La richiesta si vede nello stato finche' il gestore vero non dice di meglio...
    assertThat(manager.states.value[id]).isEqualTo(ModelState.Queued(ModelTrigger.USER_NOW))
    // ...e sparisce quando il gestore vero cambia stato.
    inner.set(id, ModelState.Fetching(ModelSourceKind.HTTPS, 10, 100))
    assertThat(manager.states.value[id]).isEqualTo(ModelState.Fetching(ModelSourceKind.HTTPS, 10, 100))
  }

  @Test
  fun laConfermaSuiDatiMobiliChiedeUnLavoroConIlTriggerDellaConferma() = runBlocking<Unit> {
    val requests = mutableListOf<ModelTrigger>()
    val manager = RequestingModelManager(FakeModelManager(), scope) { _, trigger -> requests += trigger }
    manager.ensure(ModelCatalog.INDEX_440M.id, ModelTrigger.USER_CONFIRMED, 1.minutes)
    assertThat(requests).containsExactly(ModelTrigger.USER_CONFIRMED)
  }

  @Test
  fun leRichiesteInBackgroundVannoDirettamenteAlGestoreVeroEUnModelloProntoNonChiedeNiente() = runBlocking<Unit> {
    val inner = FakeModelManager()
    val requests = mutableListOf<ModelTrigger>()
    val manager = RequestingModelManager(inner, scope) { _, trigger -> requests += trigger }
    val id = ModelCatalog.QUERY_270M.id
    manager.ensure(id, ModelTrigger.RULES, 1.minutes)
    assertThat(inner.ensured).containsExactly(id to ModelTrigger.RULES)
    assertThat(requests).isEmpty()
    inner.set(id, ModelState.Ready(1, ModelSourceKind.NAS, 0))
    assertThat(manager.ensure(id, ModelTrigger.USER_NOW, 1.minutes)).isEqualTo(EnsureResult.Ready)
    assertThat(requests).isEmpty()
  }
}
