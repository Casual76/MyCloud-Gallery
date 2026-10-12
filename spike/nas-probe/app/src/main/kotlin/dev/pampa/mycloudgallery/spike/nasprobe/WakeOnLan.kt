package dev.pampa.mycloudgallery.spike.nasprobe

import android.content.Context
import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

private const val WOL_PORT = 9
private const val WOL_TIMEOUT_S = 180

/** Accetta `AA:BB:CC:DD:EE:FF`, `AA-BB-CC-DD-EE-FF` o 12 cifre esadecimali. */
fun parseMac(text: String): ByteArray? {
    val hex = text.trim().replace(":", "").replace("-", "").replace(".", "")
    if (hex.length != 12 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun magicPacket(mac: ByteArray): ByteArray {
    val p = ByteArray(6 + 16 * 6)
    for (i in 0 until 6) p[i] = 0xFF.toByte()
    for (r in 0 until 16) System.arraycopy(mac, 0, p, 6 + r * 6, 6)
    return p
}

/** Indirizzo di broadcast diretto della sottorete IPv4 della rete data, se calcolabile. */
private fun directedBroadcast(ctx: Context, network: Network): InetAddress? {
    val lp = connectivityManager(ctx).getLinkProperties(network) ?: return null
    val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address && it.prefixLength in 8..30 } ?: return null
    val ip = la.address.address
    val mask = (-1 shl (32 - la.prefixLength))
    val ipInt = ((ip[0].toInt() and 0xFF) shl 24) or ((ip[1].toInt() and 0xFF) shl 16) or
        ((ip[2].toInt() and 0xFF) shl 8) or (ip[3].toInt() and 0xFF)
    val b = ipInt or mask.inv()
    return InetAddress.getByAddress(
        byteArrayOf((b ushr 24).toByte(), (b ushr 16).toByte(), (b ushr 8).toByte(), b.toByte()),
    )
}

/**
 * Wake-on-LAN manuale: invia il magic packet UDP in broadcast (porta 9) e misura dopo quanti secondi la porta
 * 445 del NAS risponde (timeout 180 s). Usa la rete Wi-Fi se c'e', altrimenti quella predefinita.
 * Non viene mai lanciato in automatico.
 */
suspend fun testWakeOnLan(ctx: Context, s: ProbeSettings, onStatus: (String) -> Unit): TestResult =
    withContext(Dispatchers.IO) {
        val b = ResultBuilder("WOL", "Wake-on-LAN")
        try {
            val mac = parseMac(s.mac) ?: return@withContext b.fail("MAC address non valido (atteso AA:BB:CC:DD:EE:FF)")
            if (s.host.isBlank()) return@withContext b.fail("host vuoto: serve per sapere quando la porta 445 risponde")

            val wifi: Network? = wifiNetworks(ctx).firstOrNull()
            b.put("reteUsata", if (wifi != null) "Wi-Fi (socket legato)" else "predefinita")
            b.put("portaUdp", WOL_PORT)
            b.put("timeoutS", WOL_TIMEOUT_S)

            onStatus("Wake-on-LAN: risolvo il NAS...")
            val resolved = resolveHost(s.host, wifi)
            val nas = resolved.address
                ?: return@withContext b.fail("DNS non risolto (${resolved.error}): senza indirizzo non posso misurare la porta 445")

            val before = tcpProbe(nas, SMB_PORT, wifi, 1000)
            b.put("giaRaggiungibilePrimaDelPacchetto", before.reachable)

            // Magic packet in broadcast: limitato e diretto, due giri.
            val targets = buildList {
                add(InetAddress.getByName("255.255.255.255"))
                wifi?.let { directedBroadcast(ctx, it) }?.let { add(it) }
            }
            val data = magicPacket(mac)
            val sendT0 = System.nanoTime()
            var sent = 0
            DatagramSocket().use { sock ->
                sock.broadcast = true
                wifi?.bindSocket(sock)
                repeat(2) { round ->
                    for (t in targets) {
                        try {
                            sock.send(DatagramPacket(data, data.size, t, WOL_PORT))
                            sent++
                        } catch (_: Throwable) {
                        }
                    }
                    if (round == 0) delay(200)
                }
            }
            b.put("pacchettiInviati", sent)
            b.put("indirizziBroadcast", targets.size)
            if (sent == 0) return@withContext b.fail("nessun pacchetto inviato")

            // Sondaggio della porta 445 una volta al secondo.
            var waitedS = -1.0
            var attempts = 0
            while (true) {
                ensureActive()
                val elapsedS = (System.nanoTime() - sendT0) / 1_000_000_000.0
                if (elapsedS > WOL_TIMEOUT_S) break
                onStatus("Wake-on-LAN: attendo il NAS... ${elapsedS.toInt()} s / $WOL_TIMEOUT_S s")
                attempts++
                val p = tcpProbe(nas, SMB_PORT, wifi, 1000)
                if (p.reachable) {
                    waitedS = (System.nanoTime() - sendT0) / 1_000_000_000.0
                    break
                }
                // tcpProbe dura gia' fino a 1 s se la porta e' muta; se rifiuta subito, aspetta il resto del secondo.
                if (p.connectMs < 1000) delay((1000 - p.connectMs).toLong())
            }
            b.put("tentativiPorta445", attempts)
            if (waitedS >= 0) {
                b.put("secondiPrimaDellaRisposta445", r1(waitedS))
                b.ok()
            } else {
                b.put("secondiPrimaDellaRisposta445", null)
                b.fail("la porta 445 non ha risposto entro $WOL_TIMEOUT_S s")
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            b.fail(t, s.sensitiveTokens)
        }
    }
