package dev.pampa.mycloudgallery.spike.nasprobe

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

const val SMB_PORT = 445

/** Scope staccato per le chiamate bloccanti che non rispettano l'annullamento (DNS). */
private val detachedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Esegue una chiamata bloccante senza aspettarla oltre `timeoutMs`: se scade restituisce null e il thread
 * resta occupato finche' la chiamata non termina da sola.
 */
suspend fun <T : Any> blockingWithTimeout(timeoutMs: Long, block: () -> T): Result<T>? {
    val deferred = detachedScope.async { runCatching(block) }
    return withTimeoutOrNull(timeoutMs) { deferred.await() }
}

fun connectivityManager(ctx: Context): ConnectivityManager =
    ctx.getSystemService(ConnectivityManager::class.java)

@Suppress("DEPRECATION")
fun allNetworks(ctx: Context): List<Network> = connectivityManager(ctx).allNetworks.toList()

/** Reti Wi-Fi che non sono VPN (la VPN sopra il Wi-Fi ha anche il trasporto Wi-Fi in alcuni casi). */
fun wifiNetworks(ctx: Context): List<Network> {
    val cm = connectivityManager(ctx)
    return allNetworks(ctx).filter { n ->
        val caps = cm.getNetworkCapabilities(n)
        caps != null &&
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}

/** Risultato di una risoluzione DNS. */
data class Resolved(val address: InetAddress?, val ms: Double, val error: String?)

/** Risolve l'host tramite la rete indicata (null = rete predefinita). Preferisce IPv4. */
suspend fun resolveHost(host: String, network: Network?, timeoutMs: Long = 5000): Resolved {
    val t0 = System.nanoTime()
    val res = blockingWithTimeout(timeoutMs) {
        val all = if (network != null) network.getAllByName(host) else InetAddress.getAllByName(host)
        all.firstOrNull { it is Inet4Address } ?: all.first()
    }
    val ms = (System.nanoTime() - t0) / 1_000_000.0
    return when {
        res == null -> Resolved(null, ms, "timeout DNS")
        res.isSuccess -> Resolved(res.getOrNull(), ms, null)
        else -> Resolved(null, ms, res.exceptionOrNull()?.javaClass?.simpleName ?: "errore DNS")
    }
}

/** Esito di una prova TCP. */
data class TcpProbe(val reachable: Boolean, val connectMs: Double, val error: String?)

/**
 * Prova a connettersi in TCP a `address:port`. Se `network` non e' null il socket e' creato dalla
 * `socketFactory` di quella rete, cioe' legato esplicitamente ad essa.
 */
suspend fun tcpProbe(address: InetAddress, port: Int, network: Network?, timeoutMs: Int): TcpProbe =
    kotlinx.coroutines.withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        var socket: Socket? = null
        try {
            socket = if (network != null) network.socketFactory.createSocket() else Socket()
            socket.connect(InetSocketAddress(address, port), timeoutMs)
            TcpProbe(true, (System.nanoTime() - t0) / 1_000_000.0, null)
        } catch (t: Throwable) {
            TcpProbe(false, (System.nanoTime() - t0) / 1_000_000.0, t.javaClass.simpleName)
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
        }
    }

/** Vero se `candidate` sta nella stessa sottorete IPv4 di `base/prefix`. */
fun sameSubnetV4(candidate: InetAddress, base: InetAddress, prefix: Int): Boolean {
    if (candidate !is Inet4Address || base !is Inet4Address || prefix !in 0..32) return false
    val a = candidate.address
    val b = base.address
    var bits = prefix
    for (i in 0 until 4) {
        if (bits <= 0) break
        val take = minOf(8, bits)
        val mask = (0xFF shl (8 - take)) and 0xFF
        if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
        bits -= take
    }
    return true
}
