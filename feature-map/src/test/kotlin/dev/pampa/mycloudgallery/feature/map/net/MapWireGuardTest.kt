package dev.pampa.mycloudgallery.feature.map.net

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.map.contract.MapHost
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapResponseRules
import dev.pampa.mycloudgallery.core.map.contract.RefuseReason
import dev.pampa.mycloudgallery.core.map.testing.RecordingMapTraffic
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

/**
 * L'ultimo controllo prima del filo e il conteggio dei byte (P1, §2.8): richieste fuori dalle regole che non partono, risposte oltre il tetto
 * sulla rete, byte registrati una volta sola nel registro del traffico; il DNS che risolve solo l'host della mappa. Sulla JVM, senza rete.
 */
class MapWireGuardTest {

  private val traffic = RecordingMapTraffic()
  private val counters = MapNetCounters()
  private val guard = MapWireGuard(traffic, { MapNetwork.WIFI }, counters)

  private fun request(url: String = "https://${MapHost.TILES}/planet/v1/1/0/0.pbf", headers: Map<String, String> = emptyMap()): Request =
    Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()

  private fun response(body: ByteArray, declared: Boolean = true): Response {
    val type = "application/vnd.mapbox-vector-tile".toMediaType()
    val responseBody = if (declared) body.toResponseBody(type) else okio.Buffer().write(body).asResponseBody(type, -1)
    return Response.Builder().request(request()).protocol(Protocol.HTTP_2).code(200).message("OK").body(responseBody).build()
  }

  @Test
  fun laRichiestaParteSoloSeERimastaNelleRegole() {
    guard.checkOutgoing(
      request(headers = mapOf("User-Agent" to MapTileHeaders.USER_AGENT, "Host" to MapHost.TILES, "Connection" to "Keep-Alive", "Accept-Encoding" to "gzip", "If-None-Match" to "\"e\"")),
    )
    val bad = listOf(
      request(headers = mapOf("Cookie" to "a=b")), request(headers = mapOf("Referer" to "https://x/")), request(headers = mapOf("X-Id" to "1")),
      request(url = "https://other.example/planet"), request(url = "https://${MapHost.TILES}:8443/planet"),
      Request.Builder().url("https://${MapHost.TILES}/planet").head().build(),
    )
    for (r in bad) {
      val e = runCatching { guard.checkOutgoing(r) }.exceptionOrNull()
      assertWithMessage(r.toString()).that((e as MapRequestRefused).reason).isEqualTo(RefuseReason.HOST)
    }
  }

  @Test
  fun iByteSullaReteSiContanoUnaVoltaESiLimitano() {
    val ok = guard.guarded(response(ByteArray(1_000)))
    assertThat(ok.body!!.bytes()).hasLength(1_000)
    ok.close()
    assertThat(traffic.bytes[MapNetwork.WIFI]).isEqualTo(1_000)
    assertThat(counters.snapshot().bytes).isEqualTo(1_000)

    val declaredTooBig = runCatching { guard.guarded(response(ByteArray(MapResponseRules.MAX_BYTES.toInt() + 1))) }.exceptionOrNull()
    assertThat((declaredTooBig as MapRequestRefused).reason).isEqualTo(RefuseReason.CONTENT)

    val undeclared = guard.guarded(response(ByteArray(MapResponseRules.MAX_BYTES.toInt() + 10), declared = false))
    val e = runCatching { undeclared.body!!.bytes() }.exceptionOrNull()
    assertThat((e as MapRequestRefused).reason).isEqualTo(RefuseReason.CONTENT)
    undeclared.close()
    assertThat(traffic.bytes[MapNetwork.WIFI]!!).isGreaterThan(MapResponseRules.MAX_BYTES)
  }

  @Test
  fun ilDnsRisolveSoloLHostDellaMappa() {
    val asked = mutableListOf<String>()
    val dns = OnlyMapHostDns(
      object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
          asked += hostname
          return emptyList()
        }
      },
    )
    for (host in listOf("example.org", "tiles.openfreemap.org.", "TILES.openfreemap.org", "openfreemap.org", "192.168.1.10", "")) {
      assertWithMessage(host).that(runCatching { dns.lookup(host) }.exceptionOrNull()).isInstanceOf(UnknownHostException::class.java)
    }
    assertThat(asked).isEmpty()
    dns.lookup(MapHost.TILES)
    assertThat(asked).containsExactly(MapHost.TILES)
  }
}
