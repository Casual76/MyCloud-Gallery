package dev.pampa.mycloudgallery.spike.nasprobe

import android.content.Context
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build
import java.net.InetAddress

/**
 * Test 1: rete. Descrive la rete attiva e ogni rete disponibile e prova la porta 445 del NAS sia dalla rete
 * predefinita sia con socket legato esplicitamente a ciascuna rete Wi-Fi (per capire se si puo' bypassare una VPN).
 * Non chiede alcun permesso di posizione: l'SSID compare solo se Android lo concede senza.
 */
suspend fun testNetwork(ctx: Context, s: ProbeSettings): TestResult {
    val b = ResultBuilder("1", "Rete")
    return try {
        val cm = connectivityManager(ctx)
        val active = cm.activeNetwork
        val nets = allNetworks(ctx)

        // Il NAS risolto dalla rete predefinita: serve per capire se sta nella sottorete di ogni rete.
        val resolvedDefault = if (s.host.isNotBlank()) resolveHost(s.host, null) else null

        val described = nets.mapIndexed { i, n ->
            describeNetwork(cm.getNetworkCapabilities(n), cm.getLinkProperties(n), n == active, i, resolvedDefault?.address)
        }
        b.put("reteAttivaPresente", active != null)
        b.put("retiVisibili", nets.size)
        b.put("reti", described)
        val vpnActive = described.any { (it["vpn"] as? Boolean) == true }
        b.put("vpnAttiva", vpnActive)
        b.put("reteAttivaEVpn", described.any { it["attiva"] == true && it["vpn"] == true })

        if (s.host.isBlank()) {
            b.put("raggiungibilitaNas", "host vuoto: prova saltata")
            return b.ok()
        }

        // Raggiungibilita' della porta 445.
        val reach = linkedMapOf<String, Any?>()
        reach["porta"] = SMB_PORT
        reach["timeoutMs"] = 3000
        reach["dnsRetePredefinita"] = mapOf(
            "ms" to r1(resolvedDefault!!.ms),
            "indirizzo" to resolvedDefault.address?.hostAddress,
            "errore" to resolvedDefault.error,
        )
        val defaultProbe = resolvedDefault.address?.let { tcpProbe(it, SMB_PORT, null, 3000) }
        reach["retePredefinita"] = probeToMap(defaultProbe, "DNS non risolto")

        val wifis = wifiNetworks(ctx)
        val wifiResults = wifis.mapIndexed { i, net ->
            val res = resolveHost(s.host, net)
            val probe = res.address?.let { tcpProbe(it, SMB_PORT, net, 3000) }
            linkedMapOf<String, Any?>(
                "indice" to i,
                "dnsMs" to r1(res.ms),
                "indirizzo" to res.address?.hostAddress,
                "dnsErrore" to res.error,
            ).also { it.putAll(probeToMap(probe, "DNS non risolto")) }
        }
        reach["legatoAWifi"] = wifiResults
        b.put("raggiungibilitaNas", reach)

        val defaultOk = defaultProbe?.reachable == true
        val wifiOk = wifiResults.any { it["raggiungibile"] == true }
        b.put("nasRaggiungibileDaPredefinita", defaultOk)
        b.put("nasRaggiungibileLegandoWifi", wifiOk)
        // Se c'e' una VPN e il Wi-Fi diretto raggiunge il NAS, il traffico locale puo' uscire dal tunnel.
        b.put("bypassVpnPossibile", if (vpnActive) wifiOk else null)
        b.ok()
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        b.fail(t, s.sensitiveTokens)
    }
}

private fun probeToMap(p: TcpProbe?, whenNull: String): Map<String, Any?> =
    if (p == null) mapOf("raggiungibile" to false, "errore" to whenNull)
    else linkedMapOf("raggiungibile" to p.reachable, "connessioneMs" to r1(p.connectMs), "errore" to p.error)

private fun describeNetwork(
    caps: NetworkCapabilities?,
    lp: LinkProperties?,
    isActive: Boolean,
    index: Int,
    nasAddress: InetAddress?,
): Map<String, Any?> {
    val m = linkedMapOf<String, Any?>()
    m["indice"] = index
    m["attiva"] = isActive
    if (caps != null) {
        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELLULAR")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETHERNET")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("BLUETOOTH")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)) add("WIFI_AWARE")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN)) add("LOWPAN")
        }
        m["trasporti"] = transports
        m["vpn"] = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        m["notMetered"] = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        m["notRoaming"] = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        m["notVpn"] = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        m["internet"] = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        m["validata"] = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        m["bandaStimataGiuKbps"] = caps.linkDownstreamBandwidthKbps
        m["bandaStimataSuKbps"] = caps.linkUpstreamBandwidthKbps
        (caps.transportInfo as? WifiInfo)?.let { m["wifi"] = describeWifi(it) }
    } else {
        m["capacita"] = "non disponibili"
    }
    if (lp != null) {
        m["interfaccia"] = lp.interfaceName
        m["indirizziTablet"] = lp.linkAddresses.map { "${it.address.hostAddress}/${it.prefixLength}" }
        m["gateway"] = lp.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway?.hostAddress }.distinct()
        m["dns"] = lp.dnsServers.mapNotNull { it.hostAddress }
        m["dominiRicerca"] = lp.domains?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList<String>()
        m["dnsPrivatoAttivo"] = lp.isPrivateDnsActive
        m["mtu"] = lp.mtu
        if (nasAddress != null) {
            m["nasNellaSottorete"] = lp.linkAddresses.any { sameSubnetV4(nasAddress, it.address, it.prefixLength) }
        }
    } else {
        m["linkProperties"] = "non disponibili"
    }
    return m
}

/** Campi del Wi-Fi leggibili senza permesso di posizione. SSID solo se non oscurato. */
private fun describeWifi(w: WifiInfo): Map<String, Any?> {
    val m = linkedMapOf<String, Any?>()
    val raw = w.ssid
    val ssid = raw?.trim('"')?.takeIf { it.isNotEmpty() && !it.equals("<unknown ssid>", ignoreCase = true) }
    m["ssidDisponibile"] = ssid != null
    if (ssid != null) m["ssid"] = ssid
    m["rssiDbm"] = w.rssi
    m["velocitaLinkMbps"] = w.linkSpeed
    m["velocitaRxMbps"] = w.rxLinkSpeedMbps
    m["velocitaTxMbps"] = w.txLinkSpeedMbps
    m["frequenzaMhz"] = w.frequency
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        m["standardWifi"] = when (w.wifiStandard) {
            1 -> "legacy"
            4 -> "11n"
            5 -> "11ac"
            6 -> "11ax"
            7 -> "11ad"
            8 -> "11be"
            else -> "sconosciuto(${w.wifiStandard})"
        }
    }
    return m
}
