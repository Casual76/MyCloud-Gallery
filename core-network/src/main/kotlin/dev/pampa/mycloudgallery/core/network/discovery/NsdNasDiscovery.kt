package dev.pampa.mycloudgallery.core.network.discovery

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import dev.pampa.mycloudgallery.core.common.coroutines.DefaultDispatcherProvider
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import dev.pampa.mycloudgallery.core.network.contract.DiscoveredNas
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.network.contract.NasDiscovery
import dev.pampa.mycloudgallery.core.network.env.NetworkLookup
import dev.pampa.mycloudgallery.core.network.env.NetworkPathSource
import dev.pampa.mycloudgallery.core.network.link.IpAddr
import dev.pampa.mycloudgallery.core.network.link.LinkFacts
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Quali indirizzi scoperti si accettano. **Solo quelli sulla rete locale** (nella stessa sottorete dell'interfaccia
 * del Wi-Fi): un dispositivo malevolo sulla LAN potrebbe annunciare un `_smb._tcp` con un indirizzo qualunque, o un
 * nome che il DNS fa puntare altrove, e l'app finirebbe per connettersi a un host di Internet. L'IPv6 link-local
 * (`fe80::`) non si usa: servirebbe la zona, e un IP letterale non la porta.
 */
object LanAddressFilter {

  /** Il primo indirizzo utilizzabile fra [candidates] (meglio un IPv4), o null. */
  fun pick(candidates: List<IpAddr>, lan: LinkFacts): IpAddr? =
    candidates.filter { onLink(it, lan) }.minWithOrNull(compareBy { if (it.isV4) 0 else 1 })

  fun onLink(ip: IpAddr, lan: LinkFacts): Boolean {
    if (ip.isLinkLocal) return false
    return lan.addresses.any { it.ip.isV4 == ip.isV4 && it.prefix.contains(ip) && it.ip != ip }
  }
}

/**
 * La scoperta del NAS **solo durante l'onboarding** (passo 2, docs/rete-casa-vpn.md): NSD per `_smb._tcp` sul Wi-Fi
 * attuale e, in alternativa, un nome digitato risolto con il DNS di quel Wi-Fi.
 *
 *  - Mai sulla rete predefinita: con una VPN o i dati mobili non c'e' niente da scoprire, e il DNS di una rete
 *    estranea non e' affidabile. Senza un Wi-Fi/Ethernet valido il flusso termina subito, vuoto.
 *  - I risultati sono filtrati da [LanAddressFilter]: solo indirizzi della sottorete locale.
 *  - I nomi annunciati servono solo a mostrare: sono ripuliti (lunghezza, caratteri di controllo).
 *  - Le risoluzioni NSD vanno una alla volta (Android non ne ammette piu' di una: `FAILURE_ALREADY_ACTIVE`).
 */
class NsdNasDiscovery(
  context: Context,
  private val paths: NetworkPathSource,
  private val networks: NetworkLookup,
  private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
) : NasDiscovery {

  private val context: Context = context.applicationContext ?: context

  override fun discover(timeout: Duration): Flow<DiscoveredNas> = callbackFlow {
    val lan = currentLan()
    val nsd = context.getSystemService(NsdManager::class.java)
    if (lan == null || nsd == null) {
      close()
      return@callbackFlow
    }
    val (facts, network) = lan
    val pending = Channel<NsdServiceInfo>(Channel.UNLIMITED)

    val listener = object : NsdManager.DiscoveryListener {
      override fun onDiscoveryStarted(serviceType: String) = Unit

      override fun onServiceFound(serviceInfo: NsdServiceInfo) {
        if (serviceInfo.serviceType.orEmpty().startsWith(SERVICE_TYPE)) pending.trySend(serviceInfo)
      }

      override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

      override fun onDiscoveryStopped(serviceType: String) = Unit

      override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
        close()
      }

      override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    launch(dispatchers.io) {
      for (info in pending) {
        val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT) { resolve(nsd, info) } ?: continue
        val name = sanitizeName(resolved.serviceName)
        val address = LanAddressFilter.pick(addressesOf(resolved), facts) ?: continue
        val nas = NasAddress.parseOrNull(address.toText()) ?: continue
        trySend(DiscoveredNas(name, nas))
      }
    }
    launch {
      delay(timeout)
      close()
    }

    startDiscovery(nsd, listener, network)
    awaitClose {
      pending.close()
      try {
        nsd.stopServiceDiscovery(listener)
      } catch (_: IllegalArgumentException) {
        // la scoperta non era partita
      }
    }
  }

  override suspend fun resolveOnWifi(name: String): InetAddress? {
    val clean = name.trim()
    if (!isPlausibleHostName(clean)) return null
    val (facts, network) = currentLan() ?: return null
    return withContext(dispatchers.io) {
      val candidates = try {
        network.getAllByName(clean).mapNotNull { IpAddr.fromBytes(it.address) }
      } catch (_: UnknownHostException) {
        emptyList()
      }
      LanAddressFilter.pick(candidates, facts)?.let { InetAddress.getByAddress(it.toBytes()) }
    }
  }

  /** Il Wi-Fi/Ethernet attuale (non VPN, non dietro una pagina di accesso) con la sua `Network`. */
  private fun currentLan(): Pair<LinkFacts, Network>? {
    val observation = paths.observationNow()
    val facts = observation.lans
      .filter { !it.captivePortal }
      .maxWithOrNull(compareBy({ it.networkId == observation.defaultNetworkId }, { it.validated }))
      ?: return null
    return networks.network(facts.networkId)?.let { facts to it }
  }

  private fun startDiscovery(nsd: NsdManager, listener: NsdManager.DiscoveryListener, network: Network) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, network, DIRECT_EXECUTOR, listener)
    } else {
      // Prima di Android 13 non si sceglie la rete: NSD gira sul Wi-Fi.
      nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }
  }

  @Suppress("DEPRECATION") // resolveService e' deprecato da Android 14 ma funziona; l'alternativa ha un'altra forma per ogni SDK.
  private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
    suspendCancellableCoroutine { continuation ->
      nsd.resolveService(
        info,
        object : NsdManager.ResolveListener {
          override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            if (continuation.isActive) continuation.resume(null)
          }

          override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            if (continuation.isActive) continuation.resume(serviceInfo)
          }
        },
      )
    }

  @Suppress("DEPRECATION")
  private fun addressesOf(info: NsdServiceInfo): List<IpAddr> {
    val raw: List<InetAddress> =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) info.hostAddresses else listOfNotNull(info.host)
    return raw.mapNotNull { IpAddr.fromBytes(it.address) }
  }

  internal companion object {
    const val SERVICE_TYPE = "_smb._tcp"
    private val RESOLVE_TIMEOUT = 5.seconds
    private const val MAX_NAME_CHARS = 63
    private const val MAX_HOST_CHARS = 253
    private val DIRECT_EXECUTOR = Executor { it.run() }
    private val HOST_LABEL = Regex("[A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?")

    /** Solo per mostrarlo: niente caratteri di controllo, al massimo 63 caratteri. */
    fun sanitizeName(raw: String?): String {
      val clean = raw.orEmpty().filter { !it.isISOControl() }.trim().take(MAX_NAME_CHARS)
      return clean.ifEmpty { "NAS" }
    }

    /** Un nome DNS plausibile: etichette di lettere, cifre, `-` e `_`. Niente spazi, niente URL, niente indirizzi. */
    fun isPlausibleHostName(name: String): Boolean =
      name.isNotEmpty() && name.length <= MAX_HOST_CHARS && name.trimEnd('.').split('.').all { HOST_LABEL.matches(it) }
  }
}
