package dev.pampa.mycloudgallery.core.network.vpn

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.network.TestSnapshots
import dev.pampa.mycloudgallery.core.network.contract.HomeFingerprint
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkAccess
import dev.pampa.mycloudgallery.core.network.contract.NetworkSnapshot
import dev.pampa.mycloudgallery.core.network.contract.VpnAutomationStatus
import dev.pampa.mycloudgallery.core.network.contract.VpnCapability
import dev.pampa.mycloudgallery.core.network.contract.VpnCommandResult
import dev.pampa.mycloudgallery.core.network.contract.VpnController
import dev.pampa.mycloudgallery.core.network.contract.VpnControlConfig
import dev.pampa.mycloudgallery.core.network.contract.VpnSkipReason
import dev.pampa.mycloudgallery.core.network.contract.VpnSuspendReason
import dev.pampa.mycloudgallery.core.network.link.LinkFacts
import dev.pampa.mycloudgallery.core.network.link.NetworkObservation
import dev.pampa.mycloudgallery.core.network.policy.NetworkPolicy
import dev.pampa.mycloudgallery.core.network.testing.FakeNasSessionActivity
import dev.pampa.mycloudgallery.core.network.testing.FakeNetworkEnvironment
import dev.pampa.mycloudgallery.core.network.testing.InMemoryVpnControlConfigStore
import dev.pampa.mycloudgallery.core.network.testing.LinkFactsSamples
import dev.pampa.mycloudgallery.core.rules.AppContext
import dev.pampa.mycloudgallery.core.rules.FakeAppContextSignal
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * L'automazione "spenta a casa, accesa fuori" (§2.4) in tempo virtuale: un comando per cambio di rete, attesa
 * crescente, mai in ciclo, e l'euristica del blocco. WireGuard e' simulato: reagisce come reagirebbe la rete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VpnAutomationTest {

  private val fingerprint: HomeFingerprint = LinkFactsSamples.homeFingerprint()

  /** Il mondo del telefono: dove si trova e se la VPN e' accesa. Le reti cambiano id a ogni spostamento. */
  private inner class World {
    var home = false
    var vpnOn = false

    /** La VPN di casa porta tutto il traffico (`AllowedIPs = 0.0.0.0/0`, la configurazione di fabbrica del FRITZ!Box). */
    var fullTunnel = false
    private var counter = 0
    private var networkId = "cell-0"

    fun arriveHome() {
      home = true
      networkId = "wifi-${++counter}"
    }

    fun leaveHome() {
      home = false
      networkId = "cell-${++counter}"
    }

    fun snapshot(): NetworkSnapshot {
      val networks: List<LinkFacts> =
        if (home) {
          val wifi = LinkFactsSamples.homeWifi(id = networkId)
          val vpn = if (fullTunnel) LinkFactsSamples.homeVpnFullTunnel(over = dev.pampa.mycloudgallery.core.network.contract.NetTransport.WIFI) else LinkFactsSamples.homeVpn()
          if (vpnOn) listOf(vpn, wifi) else listOf(wifi)
        } else {
          val cell = LinkFactsSamples.cellular(id = networkId)
          if (vpnOn) listOf(LinkFactsSamples.homeVpn(over = dev.pampa.mycloudgallery.core.network.contract.NetTransport.CELLULAR), cell) else listOf(cell)
        }
      return NetworkPolicy.evaluate(NetworkObservation(networks.first().networkId, networks), fingerprint, LocalNetworkAccess.GRANTED_IMPLICIT, 0L).snapshot
    }
  }

  private val world = World()
  private val environment = FakeNetworkEnvironment()
  private val store = InMemoryVpnControlConfigStore(VpnControlConfig(enabled = true, tunnelName = "casa"))
  private val appContext = FakeAppContextSignal(AppContext(inForeground = true))
  private val session = FakeNasSessionActivity()

  private fun publish() {
    environment.current = world.snapshot()
  }

  /** WireGuard simulato: registra i comandi e, a comando, cambia la rete. */
  private inner class Wg(val scope: TestScope) : VpnController {
    override val capability: MutableStateFlow<VpnCapability> = MutableStateFlow(VpnCapability.READY)
    val commands = mutableListOf<Boolean>()
    val times = mutableListOf<Long>()

    /** Cosa succede a un comando: di fabbrica cambia la rete dopo un secondo e conferma. */
    var behavior: suspend (up: Boolean) -> VpnCommandResult = { up ->
      delay(1.seconds)
      world.vpnOn = up
      publish()
      VpnCommandResult.Confirmed
    }

    override suspend fun setTunnel(up: Boolean, timeout: Duration): VpnCommandResult {
      commands += up
      times += scope.testScheduler.currentTime
      return behavior(up)
    }

    override fun openWireGuardIntent(): Intent? = null
  }

  private fun TestScope.automation(wg: Wg, tuning: VpnAutomationTuning = VpnAutomationTuning()): DefaultVpnAutomation {
    val automation = DefaultVpnAutomation(
      environment = environment,
      controller = wg,
      configStore = store,
      appContext = appContext,
      session = session,
      clockMillis = { testScheduler.currentTime },
      tuning = tuning,
    )
    automation.attach(backgroundScope)
    runCurrent()
    return automation
  }

  private fun TestScope.settle(by: Duration = 30.seconds) {
    runCurrent()
    advanceTimeBy(by)
    runCurrent()
  }

  // --- La regola -----------------------------------------------------------------------------------------

  @Test
  fun dieciEntrateEUscite_ognunaSeguLaRegola_unComandoPerCambioDiRete_nessunCiclo() = runTest {
    val wg = Wg(this)
    world.leaveHome()
    world.vpnOn = true // si parte fuori casa con la VPN accesa
    publish()
    automation(wg)
    settle()
    assertThat(wg.commands).isEmpty()

    repeat(5) {
      world.arriveHome() // arrivo: la VPN e' accesa, la rete e' nuova -> SET_TUNNEL_DOWN
      publish()
      settle()
      world.leaveHome() // uscita: la VPN e' spenta, la rete e' nuova -> SET_TUNNEL_UP
      publish()
      settle()
    }

    assertThat(wg.commands).containsExactly(false, true, false, true, false, true, false, true, false, true).inOrder()
    // Dopo, anche lasciando passare molto tempo, nessun comando in piu': non c'e' nessun ciclo.
    settle(30.minutes)
    assertThat(wg.commands).hasSize(10)
  }

  @Test
  fun lastatoSegueIlPercorso_Idle_Waiting_Commanding_Done() = runTest {
    val wg = Wg(this)
    session.isBusy.value = true
    world.leaveHome()
    world.vpnOn = true
    publish()
    val automation = automation(wg)
    assertThat(automation.status.value).isEqualTo(VpnAutomationStatus.Idle)

    world.arriveHome()
    publish()
    runCurrent()
    // La sessione NAS sta lavorando: si aspetta, senza comandare.
    assertThat(automation.status.value).isInstanceOf(VpnAutomationStatus.WaitingForIdleSession::class.java)
    assertThat(wg.commands).isEmpty()

    session.isBusy.value = false
    runCurrent()
    assertThat(automation.status.value).isEqualTo(VpnAutomationStatus.Commanding(up = false, at = testScheduler.currentTime))

    settle()
    assertThat(automation.status.value).isEqualTo(VpnAutomationStatus.Done(up = false, result = VpnCommandResult.Confirmed))
  }

  @Test
  fun inCasa_laSessioneSempreOccupata_siAspettanoDueMinutiPoiSiSpegne() = runTest {
    val wg = Wg(this)
    session.isBusy.value = true
    world.leaveHome()
    world.vpnOn = true
    publish()
    automation(wg)
    world.arriveHome()
    publish()

    advanceTimeBy(119.seconds)
    runCurrent()
    assertThat(wg.commands).isEmpty()
    advanceTimeBy(2.seconds)
    runCurrent()

    assertThat(wg.commands).containsExactly(false)
    assertThat(wg.times.single()).isAtLeast(120_000L)
  }

  @Test
  fun l_attesaDellaSessioneNonRicominciaSeIlPassoRiparte() = runTest {
    val wg = Wg(this)
    session.isBusy.value = true
    world.leaveHome()
    world.vpnOn = true
    publish()
    automation(wg)
    world.arriveHome()
    publish()
    advanceTimeBy(100.seconds)
    runCurrent()

    // L'app va in background: il passo riparte, ma i due minuti contano dall'arrivo, non dal riavvio.
    appContext.context.value = AppContext(inForeground = false)
    runCurrent()
    advanceTimeBy(21.seconds)
    runCurrent()

    assertThat(wg.commands).containsExactly(false)
  }

  /**
   * Revisione 2, F2: "casa" si riconosce dall'ULA, che un Wi-Fi gemello puo' imitare. Una VPN che porta tutto il
   * traffico del telefono non si spegne mai da sola: il NAS si raggiunge attraverso il tunnel.
   */
  @Test
  fun inCasa_unaVpnCheportaTuttoIlTrafficoNonSiSpegneDaSola() = runTest {
    val wg = Wg(this)
    world.fullTunnel = true
    world.vpnOn = true
    world.arriveHome()
    val snapshot = world.snapshot()
    assertThat(snapshot.home).isEqualTo(dev.pampa.mycloudgallery.core.network.contract.HomeMatch.Match)
    assertThat(snapshot.vpn).isEqualTo(dev.pampa.mycloudgallery.core.network.contract.VpnObservation.HOME_TUNNEL)
    assertThat(snapshot.vpnCarriesInternet).isTrue()
    publish()
    automation(wg)
    settle(5.minutes)
    assertThat(wg.commands).isEmpty()

    // La stessa casa con una VPN che porta solo il traffico verso casa: si spegne (una volta).
    world.fullTunnel = false
    world.arriveHome()
    publish()
    settle(5.minutes)
    assertThat(wg.commands).containsExactly(false)
  }

  @Test
  fun inCasa_siAspettaSoloSeLaVpnESpentaIlComandoNonServe() = runTest {
    val wg = Wg(this)
    world.arriveHome()
    publish() // a casa, VPN gia' spenta
    automation(wg)
    settle()

    assertThat(wg.commands).isEmpty()
  }

  @Test
  fun fuoriCasa_laVpnSiAccendeSoloUnaVolta_ancheSeLaReteSiAggiornaPiuVolte() = runTest {
    val wg = Wg(this)
    wg.behavior = { delay(10.seconds); VpnCommandResult.NotObserved }
    world.leaveHome()
    publish()
    automation(wg)
    settle()

    // La stessa rete viene ridescritta piu' volte (capacita', proprieta'): niente nuovi comandi.
    repeat(5) {
      environment.current = world.snapshot().copy(at = it + 1L)
      settle(5.seconds)
    }

    assertThat(wg.commands).containsExactly(true)
  }

  @Test
  fun seL_utenteRiaccendeLaVpnAMano_aCasa_sulLaStessaRete_nonLaSiRispegne() = runTest {
    val wg = Wg(this)
    world.leaveHome()
    world.vpnOn = true
    publish()
    automation(wg)
    world.arriveHome()
    publish()
    settle()
    assertThat(wg.commands).containsExactly(false)

    // L'utente accende WireGuard a mano mentre e' ancora sul Wi-Fi di casa.
    world.vpnOn = true
    publish()
    settle(10.minutes)

    assertThat(wg.commands).containsExactly(false)
  }

  // --- Quando non si fa niente ---------------------------------------------------------------------------

  @Test
  fun controlloNonAttivo_oCapacitaMancante_nessunComando() = runTest {
    val wg = Wg(this)
    store.config.value = VpnControlConfig(enabled = false, tunnelName = "casa")
    world.leaveHome()
    publish()
    val automation = automation(wg)
    settle()
    assertThat(automation.status.value).isEqualTo(VpnAutomationStatus.Off)

    store.config.value = VpnControlConfig(enabled = true, tunnelName = "casa")
    wg.capability.value = VpnCapability.PERMISSION_MISSING
    settle()
    assertThat(automation.status.value).isEqualTo(VpnAutomationStatus.Off)
    assertThat(wg.commands).isEmpty()

    // Appena tutto e' pronto, l'automazione parte da sola.
    wg.capability.value = VpnCapability.READY
    settle()
    assertThat(wg.commands).containsExactly(true)
  }

  @Test
  fun nessunComandoSuUnaVpnEstranea_suUnaReteSenzaInternetOSenzaRete() = runTest {
    val wg = Wg(this)
    automation(wg)
    val foreign = NetworkPolicy.evaluate(
      LinkFactsSamples.observation(LinkFactsSamples.foreignVpn(), LinkFactsSamples.cafeWifi()),
      fingerprint,
      LocalNetworkAccess.GRANTED_IMPLICIT,
      0L,
    ).snapshot
    val noInternet = NetworkPolicy.evaluate(LinkFactsSamples.observation(LinkFactsSamples.cafeWifi(validated = false)), fingerprint, LocalNetworkAccess.GRANTED_IMPLICIT, 0L).snapshot
    val captive = NetworkPolicy.evaluate(LinkFactsSamples.observation(LinkFactsSamples.cafeWifi(validated = false, captivePortal = true)), fingerprint, LocalNetworkAccess.GRANTED_IMPLICIT, 0L).snapshot

    for (snapshot in listOf(foreign, noInternet, captive, NetworkSnapshot.OFFLINE)) {
      environment.current = snapshot
      settle()
    }

    assertThat(wg.commands).isEmpty()
  }

  @Test
  fun senzaImpronta_suUnWifiNonSiSaSeSiEFuoriCasa_nessunComando() = runTest {
    val wg = Wg(this)
    automation(wg)
    // Un Wi-Fi qualunque ma senza impronta: casa e' "sconosciuta", non "diversa".
    environment.current = NetworkPolicy.evaluate(LinkFactsSamples.observation(LinkFactsSamples.cafeWifi()), null, LocalNetworkAccess.GRANTED_IMPLICIT, 0L).snapshot
    settle()

    assertThat(wg.commands).isEmpty()
  }

  @Test
  fun suUnWifiChePerSomigliaACasaMaNonLoE_siAccendeLaVpn_dopoLAssestamento() = runTest {
    val wg = Wg(this)
    automation(wg)
    environment.current = NetworkPolicy.evaluate(LinkFactsSamples.observation(LinkFactsSamples.friendWifi()), fingerprint, LocalNetworkAccess.GRANTED_IMPLICIT, 0L).snapshot

    advanceTimeBy(7.seconds)
    runCurrent()
    assertThat(wg.commands).isEmpty()
    advanceTimeBy(2.seconds)
    runCurrent()

    assertThat(wg.commands).containsExactly(true)
  }

  @Test
  fun arrivoACasa_l_UlaArrivaDopoL_Ipv4_laVpnNonSiAccendeMai() = runTest {
    val wg = Wg(this)
    automation(wg)
    // Il Wi-Fi si collega: per un attimo c'e' solo l'IPv4 (e il dominio), l'ULA non e' ancora arrivato.
    val withoutUla = LinkFactsSamples.homeWifi(id = "wifi-1").let {
      LinkFacts(it.networkId, it.transports, addresses = it.addresses.filter { a -> a.ip.isV4 }, routes = it.routes, dnsServers = it.dnsServers, domains = it.domains)
    }
    environment.current = NetworkPolicy.evaluate(LinkFactsSamples.observation(withoutUla), fingerprint, LocalNetworkAccess.GRANTED_IMPLICIT, 0L).snapshot
    runCurrent()
    advanceTimeBy(2.seconds)
    runCurrent()

    // Due secondi dopo l'annuncio del router porta l'ULA: e' casa.
    environment.current = TestSnapshots.homeNoVpn("wifi-1")
    settle()

    assertThat(wg.commands).isEmpty()
  }

  @Test
  fun suiDatiMobili_nonCeNessunaAttesa() = runTest {
    val wg = Wg(this)
    automation(wg)
    environment.current = TestSnapshots.awayNoVpn()
    runCurrent()

    assertThat(wg.commands).containsExactly(true)
  }

  // --- Esiti e attesa crescente --------------------------------------------------------------------------

  @Test
  fun comandiSenzaEffetto_attesaCrescente_epoiLAutomazioneSiSospende() = runTest {
    val wg = Wg(this)
    wg.behavior = { delay(10.seconds); VpnCommandResult.NotObserved }
    world.leaveHome()
    world.vpnOn = true
    publish()
    val automation = automation(wg)

    // A casa il comando non ha mai effetto (WireGuard lo ignora). Il Wi-Fi si riconnette due volte (rete nuova).
    world.arriveHome()
    publish() // t = 0: primo comando, senza effetto a t = 10 -> prossimo non prima di t = 40
    runCurrent()
    advanceTimeBy(20.seconds)
    runCurrent()
    world.arriveHome()
    publish() // t = 20: rete nuova, ma l'attesa non e' finita
    runCurrent()
    assertThat(automation.status.value).isEqualTo(VpnAutomationStatus.Done(up = false, result = VpnCommandResult.Skipped(VpnSkipReason.RATE_LIMITED)))
    advanceTimeBy(40.seconds)
    runCurrent() // t = 60: il secondo comando e' partito a t = 40 e non ha avuto effetto (t = 50) -> prossimo non prima di t = 110
    world.arriveHome()
    publish()
    runCurrent()
    advanceTimeBy(5.minutes)
    runCurrent()

    // Il secondo e il terzo comando hanno aspettato 30 s e 60 s dopo la fine del precedente.
    assertThat(wg.commands).containsExactly(false, false, false)
    assertThat(wg.times).containsExactly(0L, 40_000L, 110_000L).inOrder()
    assertThat(automation.status.value).isEqualTo(VpnAutomationStatus.Suspended(VpnSuspendReason.NOT_CONTROLLABLE))
    assertThat(store.suspension.value).isEqualTo(VpnSuspendReason.NOT_CONTROLLABLE)

    // Sospesa: altri arrivi non producono comandi.
    world.arriveHome()
    publish()
    settle(10.minutes)
    assertThat(wg.commands).hasSize(3)
  }

  @Test
  fun unAspettoChePerdeIlSuoScopo_tornaInattivo() = runTest {
    val wg = Wg(this)
    wg.behavior = { delay(10.seconds); VpnCommandResult.NotObserved }
    world.leaveHome()
    world.vpnOn = true
    publish()
    val automation = automation(wg)
    world.arriveHome()
    publish()
    settle(15.seconds)

    // Il Wi-Fi si riconnette durante l'attesa: lo stato dice "aspetto"...
    world.arriveHome()
    publish()
    runCurrent()
    assertThat(automation.status.value).isEqualTo(VpnAutomationStatus.Done(up = false, result = VpnCommandResult.Skipped(VpnSkipReason.RATE_LIMITED)))

    // ...ma se esce di casa il bisogno non c'e' piu' (la VPN accesa fuori casa va bene): niente piu' attesa.
    world.leaveHome()
    publish()
    runCurrent()

    assertThat(automation.status.value).isEqualTo(VpnAutomationStatus.Idle)
    assertThat(wg.commands).hasSize(1)
  }

  @Test
  fun attesaCrescente_30s_60s_120s_conMassimo() {
    val tuning = VpnAutomationTuning()

    assertThat(tuning.backoff(0)).isEqualTo(Duration.ZERO)
    assertThat(tuning.backoff(1)).isEqualTo(30.seconds)
    assertThat(tuning.backoff(2)).isEqualTo(60.seconds)
    assertThat(tuning.backoff(3)).isEqualTo(120.seconds)
    assertThat(tuning.backoff(20)).isEqualTo(10.minutes)
  }

  @Test
  fun unComandoRiuscitoAzzeraLAttesa() = runTest {
    val wg = Wg(this)
    var fail = true
    val works = wg.behavior
    wg.behavior = { up -> if (fail) { delay(10.seconds); VpnCommandResult.NotObserved } else works(up) }
    world.leaveHome()
    world.vpnOn = true
    publish()
    automation(wg)

    world.arriveHome()
    publish()
    settle(15.seconds)
    assertThat(wg.commands).hasSize(1)

    // Dopo l'attesa (30 s dall'insuccesso) il secondo arrivo va a buon fine.
    fail = false
    world.leaveHome()
    publish()
    world.arriveHome()
    publish()
    settle(2.minutes)
    assertThat(wg.commands).hasSize(2)
    assertThat(world.vpnOn).isFalse()

    // Ora l'attesa e' azzerata: la prossima uscita comanda subito.
    world.leaveHome()
    publish()
    runCurrent()
    assertThat(wg.commands).hasSize(3)
    assertThat(wg.commands.last()).isTrue()
  }

  @Test
  fun euristicaDelBlocco_spegnendoLaVpnLaReteSparisce_siSospendeEPerSempre() = runTest {
    val wg = Wg(this)
    wg.behavior = { delay(10.seconds); VpnCommandResult.LostConnectivity }
    world.leaveHome()
    world.vpnOn = true
    publish()
    val automation = automation(wg)

    world.arriveHome()
    publish()
    settle(30.seconds)

    assertThat(wg.commands).containsExactly(false)
    assertThat(automation.status.value).isEqualTo(VpnAutomationStatus.Suspended(VpnSuspendReason.LOCKDOWN_SUSPECTED))
    assertThat(store.suspension.value).isEqualTo(VpnSuspendReason.LOCKDOWN_SUSPECTED)

    // Anche dopo molte uscite e rientri, non tocca piu' la VPN.
    repeat(3) {
      world.leaveHome()
      publish()
      settle(5.minutes)
      world.arriveHome()
      world.vpnOn = true
      publish()
      settle(5.minutes)
    }
    assertThat(wg.commands).hasSize(1)
  }

  @Test
  fun lSospensioneSopravvive_alRiavvioDelProcesso_eSiTogliesoloConUnAzioneDellUtente() = runTest {
    val wg = Wg(this)
    store.suspension.value = VpnSuspendReason.LOCKDOWN_SUSPECTED
    world.leaveHome()
    publish()
    val automation = automation(wg)
    settle()

    // Un'automazione nuova (processo nuovo) legge la sospensione e non comanda.
    assertThat(automation.status.value).isEqualTo(VpnAutomationStatus.Suspended(VpnSuspendReason.LOCKDOWN_SUSPECTED))
    assertThat(wg.commands).isEmpty()

    // L'utente ha corretto WireGuard e salva di nuovo: ricomincia.
    store.save(VpnControlConfig(enabled = true, tunnelName = "casa"))
    settle()
    assertThat(store.suspension.value).isNull()
    assertThat(wg.commands).containsExactly(true)
  }

  @Test
  fun unComandoSaltato_nonEUnInsuccesso_laProssimaVoltaSiProvaSubito() = runTest {
    val wg = Wg(this)
    var skip = true
    val works = wg.behavior
    wg.behavior = { up -> if (skip) VpnCommandResult.Skipped(VpnSkipReason.WIREGUARD_MISSING) else works(up) }
    world.leaveHome()
    publish()
    val automation = automation(wg)
    settle()
    assertThat(automation.status.value).isEqualTo(VpnAutomationStatus.Done(up = true, result = VpnCommandResult.Skipped(VpnSkipReason.WIREGUARD_MISSING)))

    skip = false
    world.arriveHome()
    publish()
    world.leaveHome()
    publish()
    runCurrent()

    // Nessuna attesa: un comando saltato non e' stato un tentativo fallito.
    assertThat(wg.commands).hasSize(2)
    assertThat(store.suspension.value).isNull()
  }

  @Test
  fun unControlloreCheLancia_nonFaCadereLoScope() = runTest {
    val wg = Wg(this)
    wg.behavior = { throw IllegalStateException("errore del sistema") }
    world.leaveHome()
    publish()
    val automation = automation(wg)
    settle()

    assertThat(automation.status.value).isEqualTo(VpnAutomationStatus.Done(up = true, result = VpnCommandResult.NotObserved))
    // L'automazione e' viva: un cambio di rete successivo e' ancora gestito (dopo l'attesa).
    world.leaveHome()
    publish()
    advanceTimeBy(1.minutes)
    runCurrent()
    assertThat(wg.commands.size).isAtLeast(2)
  }

  // --- Primo piano e background --------------------------------------------------------------------------

  @Test
  fun dopoUnInsuccesso_inBackgroundNonSiRitenta_inPrimoPianoSi() = runTest {
    val wg = Wg(this)
    wg.behavior = { delay(10.seconds); VpnCommandResult.NotObserved }
    appContext.context.value = AppContext(inForeground = false)
    world.leaveHome()
    world.vpnOn = true
    publish()
    val automation = automation(wg)

    // Primo tentativo in background: consentito (uno solo).
    world.arriveHome()
    publish()
    settle(15.seconds)
    assertThat(wg.commands).hasSize(1)

    // Nuovo arrivo, ancora in background, dopo l'attesa: non si ritenta.
    world.leaveHome()
    publish()
    world.arriveHome()
    publish()
    settle(5.minutes)
    assertThat(wg.commands).hasSize(1)
    assertThat(automation.status.value).isEqualTo(VpnAutomationStatus.Done(up = false, result = VpnCommandResult.Skipped(VpnSkipReason.BACKGROUND_LIMIT)))

    // L'app torna in primo piano: si riprova.
    appContext.context.value = AppContext(inForeground = true)
    settle(2.minutes)
    assertThat(wg.commands).hasSize(2)
  }
}
