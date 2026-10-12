package dev.pampa.mycloudgallery.core.network.wol

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.network.contract.ProbeResult
import dev.pampa.mycloudgallery.core.network.contract.ReachabilityProbe
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.contract.WolOutcome
import dev.pampa.mycloudgallery.core.network.contract.WolTarget
import dev.pampa.mycloudgallery.core.network.link.LinkAddr
import dev.pampa.mycloudgallery.core.network.testing.FakeNasRouteGate
import dev.pampa.mycloudgallery.core.network.testing.LinkFactsSamples
import dev.pampa.mycloudgallery.core.network.testing.RecordingSocketBinding
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Wake-on-LAN a tempo virtuale (E-R5, E-R6): dietro il flag spento, solo a casa, mai dalla VPN. */
@OptIn(ExperimentalCoroutinesApi::class)
class WakeOnLanTest {

  private val gate = FakeNasRouteGate().apply { allow(Via.HOME_LAN, RecordingSocketBinding("wifi-home")) }
  private val nas = LinkFactsSamples.nas()
  private val target = WolTarget("aa:bb:cc:dd:ee:ff", nas)

  /** La sonda risponde in base al tempo virtuale. */
  private class ScriptedProbe(private val scope: TestScope) : ReachabilityProbe {
    var calls = 0
    var script: (elapsedMillis: Long) -> ProbeResult = { ProbeResult.TimedOut }

    override suspend fun tcp445(route: NasRoute.Allowed, nas: NasAddress, timeout: Duration): ProbeResult {
      calls++
      return script(scope.testScheduler.currentTime)
    }
  }

  private class TimedSender(private val scope: TestScope) : MagicPacketSender {
    /** (istante in ms, rete, lunghezza, porta). */
    val sent = mutableListOf<List<Any>>()

    override fun send(networkId: String, packet: ByteArray, port: Int): Boolean {
      sent += listOf(scope.testScheduler.currentTime, networkId, packet.size, port)
      return true
    }
  }

  private val debugOn = FeatureFlags.forBuild(debugBuild = true, wol = true)

  private fun TestScope.wol(flags: FeatureFlags = debugOn, probe: ScriptedProbe, sender: MagicPacketSender) =
    DefaultWakeOnLan(flags, gate, probe, sender, timeSource = testScheduler.timeSource)

  // --- Il flag -------------------------------------------------------------------------------------------

  @Test
  fun inRelease_ilWolEspento_enonPartePerNullaNemmenoLaSonda() = runTest {
    val probe = ScriptedProbe(this)
    val sender = TimedSender(this)

    for (flags in listOf(FeatureFlags.RELEASE, FeatureFlags.forBuild(debugBuild = false, wol = true))) {
      val wol = wol(flags, probe, sender)
      assertThat(wol.enabled).isFalse()
      assertThat(wol.wake(target)).isEqualTo(WolOutcome.NotAllowed)
    }

    assertThat(probe.calls).isEqualTo(0)
    assertThat(sender.sent).isEmpty()
  }

  // --- Dove si puo' --------------------------------------------------------------------------------------

  @Test
  fun soloACasa_mai_attraversoLaVpn_nemmenoSeLaVpnEDiCasa() = runTest {
    val probe = ScriptedProbe(this)
    val sender = TimedSender(this)
    val wol = wol(probe = probe, sender = sender)

    gate.allow(Via.HOME_LAN_THROUGH_VPN)
    assertThat(wol.wake(target)).isEqualTo(WolOutcome.NotAllowed)
    gate.allow(Via.VPN)
    assertThat(wol.wake(target)).isEqualTo(WolOutcome.NotAllowed)
    gate.deny(DenyReason.UNTRUSTED_NETWORK)
    assertThat(wol.wake(target)).isEqualTo(WolOutcome.NotAllowed)
    gate.deny(DenyReason.LOCAL_NETWORK_DENIED)
    assertThat(wol.wake(target)).isEqualTo(WolOutcome.NotAllowed)

    assertThat(probe.calls).isEqualTo(0)
    assertThat(sender.sent).isEmpty()
  }

  @Test
  fun unMacNonValido_nonEUnNas() = runTest {
    val probe = ScriptedProbe(this)
    val sender = TimedSender(this)
    val wol = wol(probe = probe, sender = sender)

    for (mac in listOf("", "aa:bb:cc:dd:ee", "aa:bb:cc:dd:ee:gg", "ff:ff:ff:ff:ff:ff", "00:00:00:00:00:00", "aabbccddeeff", "aa:bb:cc:dd:ee:ff:00")) {
      assertThat(wol.wake(WolTarget(mac, nas))).isEqualTo(WolOutcome.NotAllowed)
    }

    assertThat(sender.sent).isEmpty()
  }

  // --- Esiti ---------------------------------------------------------------------------------------------

  @Test
  fun giaAcceso_nessunPacchetto() = runTest {
    val probe = ScriptedProbe(this).apply { script = { ProbeResult.Open(5.seconds) } }
    val sender = TimedSender(this)

    val result = wol(probe = probe, sender = sender).wake(target)

    assertThat(result).isEqualTo(WolOutcome.AlreadyOn)
    assertThat(sender.sent).isEmpty()
    assertThat(probe.calls).isEqualTo(1)
  }

  @Test
  fun laSchedaRispondeMaSmbNo_eAcceso_nessunPacchetto() = runTest {
    val probe = ScriptedProbe(this).apply { script = { ProbeResult.Refused } }
    val sender = TimedSender(this)

    assertThat(wol(probe = probe, sender = sender).wake(target)).isEqualTo(WolOutcome.AlreadyOn)
    assertThat(sender.sent).isEmpty()
  }

  @Test
  fun siSveglia_dopoQuarantottoSecondi_treInviEUnaSondaOgniDueSecondi() = runTest {
    val probe = ScriptedProbe(this).apply { script = { t -> if (t >= 47_000L) ProbeResult.Open(1.seconds) else ProbeResult.TimedOut } }
    val sender = TimedSender(this)

    val result = wol(probe = probe, sender = sender).wake(target)

    // La sonda e' ogni 2 s: la prima volta che vede la porta aperta e' a 48 s dal primo pacchetto.
    assertThat(result).isEqualTo(WolOutcome.Woke(48.seconds))
    // Pacchetti a 0, 10 e 20 secondi: al massimo tre in trenta secondi. Sul Wi-Fi di casa, porta 9, 102 byte.
    assertThat(sender.sent).containsExactly(
      listOf(0L, "wifi-home", MagicPacket.SIZE, 9),
      listOf(10_000L, "wifi-home", MagicPacket.SIZE, 9),
      listOf(20_000L, "wifi-home", MagicPacket.SIZE, 9),
    ).inOrder()
    assertThat(probe.calls).isEqualTo(1 + 24)
  }

  @Test
  fun siSvegliaDopoPocoPrimaDelSecondoInvio_unPacchettoSolo() = runTest {
    val probe = ScriptedProbe(this).apply { script = { t -> if (t >= 5_000L) ProbeResult.Open(1.seconds) else ProbeResult.TimedOut } }
    val sender = TimedSender(this)

    val result = wol(probe = probe, sender = sender).wake(target)

    assertThat(result).isInstanceOf(WolOutcome.Woke::class.java)
    assertThat(sender.sent).hasSize(1)
  }

  @Test
  fun E_R6_nonSiSveglia_treInviIn30sEPoiNoEffect_senzaRitentare() = runTest {
    val probe = ScriptedProbe(this)
    val sender = TimedSender(this)
    val wol = wol(probe = probe, sender = sender)
    val start = testScheduler.currentTime

    val result = wol.wake(target)

    assertThat(result).isEqualTo(WolOutcome.NoEffect)
    assertThat(sender.sent.map { it[0] }).containsExactly(0L, 10_000L, 20_000L).inOrder()
    // Il tetto e' 120 secondi dal primo pacchetto.
    assertThat(testScheduler.currentTime - start).isAtLeast(120_000L)
    assertThat(testScheduler.currentTime - start).isAtMost(122_000L)

    // Un nuovo tentativo subito dopo non manda niente e non sonda nemmeno (E-R6: nessun nuovo tentativo).
    val callsBefore = probe.calls
    assertThat(wol.wake(target)).isEqualTo(WolOutcome.NoEffect)
    assertThat(probe.calls).isEqualTo(callsBefore)
    assertThat(sender.sent).hasSize(3)

    // Passata la pausa, l'utente puo' riprovare.
    advanceTimeBy(6.minutes)
    probe.script = { ProbeResult.Open(1.seconds) }
    assertThat(wol.wake(target)).isEqualTo(WolOutcome.AlreadyOn)
  }

  @Test
  fun lAttesaDopoNoEffectVale_solo_perQuelMac() = runTest {
    val probe = ScriptedProbe(this)
    val sender = TimedSender(this)
    val wol = wol(probe = probe, sender = sender)
    wol.wake(target)

    probe.script = { ProbeResult.Open(1.seconds) }
    val other = WolTarget("11:22:33:44:55:66", nas)

    assertThat(wol.wake(other)).isEqualTo(WolOutcome.AlreadyOn)
  }

  @Test
  fun seIlWifiCambiaAMeta_siSmette() = runTest {
    val probe = ScriptedProbe(this)
    val sender = TimedSender(this)
    val wol = wol(probe = probe, sender = sender)

    val pending = async { wol.wake(target) }
    advanceTimeBy(15.seconds)
    runCurrent()
    // Il telefono lascia casa: il percorso non e' piu' il Wi-Fi di casa.
    gate.deny(DenyReason.VPN_OFF_AWAY)
    advanceTimeBy(5.seconds)
    runCurrent()

    assertThat(pending.await()).isEqualTo(WolOutcome.NotAllowed)
    // Aveva mandato i primi due pacchetti (0 e 10 s); dopo il cambio di rete non ne manda altri.
    assertThat(sender.sent).hasSize(2)
  }

  @Test
  fun unaConnessioneRifiutataDuranteLAttesa_siContinuaAspettareFinoAllaPortaAperta() = runTest {
    val probe = ScriptedProbe(this).apply {
      script = { t -> if (t < 20_000L) ProbeResult.TimedOut else if (t < 40_000L) ProbeResult.Refused else ProbeResult.Open(1.seconds) }
    }
    val sender = TimedSender(this)

    val result = wol(probe = probe, sender = sender).wake(target)

    assertThat(result).isInstanceOf(WolOutcome.Woke::class.java)
    assertThat((result as WolOutcome.Woke).after).isAtLeast(40.seconds)
  }

  @Test
  fun unSoloRisvegliAllaVolta() = runTest {
    val probe = ScriptedProbe(this).apply { script = { t -> if (t >= 20_000L) ProbeResult.Open(1.seconds) else ProbeResult.TimedOut } }
    val sender = TimedSender(this)
    val wol = wol(probe = probe, sender = sender)

    val first = async { wol.wake(target) }
    val second = async { wol.wake(target) }
    advanceTimeBy(60.seconds)
    runCurrent()

    assertThat(first.await()).isInstanceOf(WolOutcome.Woke::class.java)
    // Il secondo trova il NAS gia' acceso: non manda altri pacchetti (il NAS e' apparso a 20 s, dopo due invii).
    assertThat(second.await()).isEqualTo(WolOutcome.AlreadyOn)
    assertThat(sender.sent).hasSize(2)
  }

  // --- Il pacchetto --------------------------------------------------------------------------------------

  @Test
  fun ilPacchettoMagico_seiFFEPoiSedicVolteIlMac() {
    val mac = MagicPacket.parseMac("AA-bb-CC-dd-EE-ff")!!
    val packet = MagicPacket.build(mac)

    assertThat(packet).hasLength(102)
    assertThat(packet.copyOfRange(0, 6).all { it == 0xFF.toByte() }).isTrue()
    for (i in 0 until 16) assertThat(packet.copyOfRange(6 + i * 6, 12 + i * 6)).isEqualTo(mac)
    assertThat(mac.map { it.toInt() and 0xff }).containsExactly(0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF).inOrder()
  }

  @Test
  fun ilBroadcastDellaSottorete() {
    fun broadcast(ip: String, length: Int) = MagicPacket.directedBroadcast(LinkAddr(LinkFactsSamples.ip(ip), length))?.toText()

    assertThat(broadcast("192.168.178.20", 24)).isEqualTo("192.168.178.255")
    assertThat(broadcast("10.20.30.41", 22)).isEqualTo("10.20.31.255")
    assertThat(broadcast("172.20.10.2", 28)).isEqualTo("172.20.10.15")
    assertThat(broadcast("192.168.178.20", 32)).isNull()
    assertThat(broadcast("192.168.178.20", 31)).isNull()
    assertThat(broadcast("fd12:3456:789a::1", 64)).isNull()
  }
}
