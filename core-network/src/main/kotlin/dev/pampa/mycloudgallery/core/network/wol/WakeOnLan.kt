package dev.pampa.mycloudgallery.core.network.wol

import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.network.contract.NasRoute
import dev.pampa.mycloudgallery.core.network.contract.NasRouteGate
import dev.pampa.mycloudgallery.core.network.contract.ProbeResult
import dev.pampa.mycloudgallery.core.network.contract.ReachabilityProbe
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.network.contract.WakeOnLan
import dev.pampa.mycloudgallery.core.network.contract.WolOutcome
import dev.pampa.mycloudgallery.core.network.contract.WolTarget
import dev.pampa.mycloudgallery.core.network.env.NetworkLookup
import dev.pampa.mycloudgallery.core.network.env.NetworkPathSource
import dev.pampa.mycloudgallery.core.network.link.IpAddr
import dev.pampa.mycloudgallery.core.network.link.LinkAddr
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Il pacchetto magico del Wake-on-LAN: 6 byte `FF` e poi 16 volte il MAC (102 byte). */
object MagicPacket {
  const val SIZE = 6 + 16 * 6
  const val MAC_BYTES = 6

  /**
   * Il MAC da `aa:bb:cc:dd:ee:ff` (anche con il trattino). Null se non sono sei byte esadecimali, o se e' il
   * broadcast (`ff:ff:ff:ff:ff:ff`) o tutto zero: non sono NAS.
   */
  fun parseMac(text: String): ByteArray? {
    val parts = text.trim().split(':', '-')
    if (parts.size != MAC_BYTES) return null
    val out = ByteArray(MAC_BYTES)
    for ((i, part) in parts.withIndex()) {
      if (part.length != 2 || !part.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
      out[i] = part.toInt(16).toByte()
    }
    if (out.all { it == 0.toByte() } || out.all { it == 0xFF.toByte() }) return null
    return out
  }

  fun build(mac: ByteArray): ByteArray {
    require(mac.size == MAC_BYTES) { "un MAC ha sei byte" }
    val packet = ByteArray(SIZE)
    for (i in 0 until MAC_BYTES) packet[i] = 0xFF.toByte()
    for (copy in 0 until 16) mac.copyInto(packet, MAC_BYTES + copy * MAC_BYTES)
    return packet
  }

  /** L'indirizzo di broadcast della sottorete dell'interfaccia (es. `192.168.178.255` per un /24). Null per IPv6 o /31-/32. */
  fun directedBroadcast(address: LinkAddr): IpAddr? {
    if (!address.ip.isV4 || address.prefixLength !in 1..MAX_BROADCAST_PREFIX) return null
    val bytes = address.ip.toBytes()
    for (bit in address.prefixLength until 32) {
      bytes[bit / 8] = (bytes[bit / 8].toInt() or (0x80 ushr (bit % 8))).toByte()
    }
    return IpAddr.fromBytes(bytes)
  }

  private const val MAX_BROADCAST_PREFIX = 30
}

/** Manda un pacchetto UDP in broadcast **sulla rete data** (mai sulla predefinita). */
fun interface MagicPacketSender {
  /** True se il datagramma e' partito. */
  fun send(networkId: String, packet: ByteArray, port: Int): Boolean
}

/** Il datagramma vero: socket UDP legato alla `Network` del Wi-Fi, verso il broadcast della sua sottorete IPv4. */
class AndroidMagicPacketSender(
  private val networks: NetworkLookup,
  private val paths: NetworkPathSource,
) : MagicPacketSender {

  override fun send(networkId: String, packet: ByteArray, port: Int): Boolean {
    val network = networks.network(networkId) ?: return false
    val facts = paths.observationNow().byId(networkId) ?: return false
    val broadcast = facts.addresses.firstNotNullOfOrNull(MagicPacket::directedBroadcast) ?: return false
    return try {
      DatagramSocket().use { socket ->
        socket.broadcast = true
        network.bindSocket(socket)
        socket.send(DatagramPacket(packet, packet.size, InetAddress.getByAddress(broadcast.toBytes()), port))
      }
      true
    } catch (_: IOException) {
      false
    }
  }
}

/** I tempi del Wake-on-LAN (§2.5). */
data class WolTuning(
  /** Al massimo tre invii... */
  val maxSends: Int = 3,
  /** ...a 10 secondi l'uno dall'altro (30 secondi in tutto). */
  val sendInterval: Duration = 10.seconds,
  /** Dopo l'ultimo invio si controlla la 445 ogni 2 secondi... */
  val probeInterval: Duration = 2.seconds,
  /** ...fino a un tetto di 120 secondi dal primo invio. */
  val maxWait: Duration = 120.seconds,
  val probeTimeout: Duration = 2.seconds,
  /** Dopo un [WolOutcome.NoEffect] non si ritenta (E-R6) per questo tempo. */
  val noEffectCooldown: Duration = 5.minutes,
  /** La porta "discard" del Wake-on-LAN. */
  val port: Int = 9,
)

/**
 * Wake-on-LAN (docs/fase2-progetto.md §2.5), **dietro `FeatureFlags.wol`** (spento in release: con il NAS sempre
 * acceso non e' verificabile, D5).
 *
 *  - Solo a casa e **mai attraverso la VPN**: serve un percorso [Via.HOME_LAN]. Ogni altro caso e'
 *    [WolOutcome.NotAllowed].
 *  - Il MAC si inserisce a mano (da Android 10 l'ARP non si legge): se non e' valido, [WolOutcome.NotAllowed].
 *  - Prima si prova la 445: se risponde, [WolOutcome.AlreadyOn] e **nessun pacchetto** parte.
 *  - Poi al massimo tre pacchetti (a 0, 10 e 20 secondi) sul Wi-Fi di casa, e la 445 ogni 2 secondi fino a 120
 *    secondi dal primo: se si apre, [WolOutcome.Woke] con il tempo trascorso. Una connessione rifiutata vuol dire
 *    che la scheda di rete risponde ma SMB non e' ancora su: si continua ad aspettare.
 *  - Senza risposta, [WolOutcome.NoEffect] e per [WolTuning.noEffectCooldown] nessun nuovo tentativo (E-R6).
 *  - Il percorso si controlla a ogni giro: se il Wi-Fi cambia a meta', si smette ([WolOutcome.NotAllowed]).
 *  - Un solo risveglio alla volta.
 *
 * Il tempo e' di [timeSource]: i test usano quello virtuale delle coroutine.
 */
class DefaultWakeOnLan(
  private val flags: FeatureFlags,
  private val gate: NasRouteGate,
  private val probe: ReachabilityProbe,
  private val sender: MagicPacketSender,
  private val timeSource: TimeSource = TimeSource.Monotonic,
  private val tuning: WolTuning = WolTuning(),
) : WakeOnLan {

  override val enabled: Boolean get() = flags.wol

  private val mutex = Mutex()
  private var noEffectUntil: TimeMark? = null
  private var noEffectMac: String? = null

  override suspend fun wake(target: WolTarget): WolOutcome {
    if (!enabled) return WolOutcome.NotAllowed
    val mac = MagicPacket.parseMac(target.mac) ?: return WolOutcome.NotAllowed
    return mutex.withLock {
      val cooldown = noEffectUntil
      if (cooldown != null && noEffectMac == target.mac && cooldown.hasNotPassedNow()) return@withLock WolOutcome.NoEffect

      var route = homeRoute() ?: return@withLock WolOutcome.NotAllowed
      // Gia' acceso: niente pacchetti.
      val first = probe.tcp445(route, target.nas, tuning.probeTimeout)
      if (first is ProbeResult.Open || first is ProbeResult.Refused) return@withLock WolOutcome.AlreadyOn

      val packet = MagicPacket.build(mac)
      val started = timeSource.markNow()
      var sends = 0
      var nextSendAt = Duration.ZERO
      while (true) {
        val elapsed = started.elapsedNow()
        if (elapsed >= tuning.maxWait) break
        route = homeRoute() ?: return@withLock WolOutcome.NotAllowed
        if (sends < tuning.maxSends && elapsed >= nextSendAt) {
          sender.send(route.binding.networkId, packet, tuning.port)
          sends++
          nextSendAt += tuning.sendInterval
        }
        delay(tuning.probeInterval)
        val result = probe.tcp445(route, target.nas, tuning.probeTimeout)
        if (result is ProbeResult.Open) return@withLock WolOutcome.Woke(started.elapsedNow())
      }
      noEffectUntil = timeSource.markNow() + tuning.noEffectCooldown
      noEffectMac = target.mac
      WolOutcome.NoEffect
    }
  }

  /** Il percorso di casa senza VPN, o null. */
  private fun homeRoute(): NasRoute.Allowed? = (gate.current() as? NasRoute.Allowed)?.takeIf { it.via == Via.HOME_LAN }
}
