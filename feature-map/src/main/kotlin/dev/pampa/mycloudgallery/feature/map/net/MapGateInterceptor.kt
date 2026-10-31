package dev.pampa.mycloudgallery.feature.map.net

import dev.pampa.mycloudgallery.core.map.contract.MapHost
import dev.pampa.mycloudgallery.core.map.contract.MapNetState
import dev.pampa.mycloudgallery.core.map.contract.MapRequestFacts
import dev.pampa.mycloudgallery.core.map.contract.MapRequestGate
import dev.pampa.mycloudgallery.core.map.contract.MapResponseRules
import dev.pampa.mycloudgallery.core.map.contract.MapVerdict
import dev.pampa.mycloudgallery.core.map.contract.RefuseReason
import dev.pampa.mycloudgallery.core.map.contract.TileKey
import java.io.IOException
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * IL CANCELLO, NEL CLIENT (Fase 9, P1; docs/fase9-progetto.md §2.8, D22, D23; E-MAP1-E-MAP4, E-MAP13). Il primo intercettore del client che
 * MapLibre usa: vede **ogni** richiesta prima che si apra un socket (anche prima del DNS) e la fa uscire solo se la decisione pura ([gate],
 * `TilePolicy` legata da :app) dice `Allow`. Con un `Allow`:
 *  - la richiesta che esce e' **ricostruita da zero**: `GET https://tiles.openfreemap.org` + il percorso deciso (la versione corrente al posto di
 *    `v`), senza query; degli header di MapLibre resta solo `If-Modified-Since` (la cache: una data, che non distingue i client; mai `If-None-Match`, che riporterebbe al server un valore che ha scelto lui), e il `User-Agent` e' fisso
 *    ([MapTileHeaders]); niente cookie, `Referer`, `Range`, `Authorization` o altro. OkHttp aggiunge poi solo `Host`, `Connection` e
 *    `Accept-Encoding: gzip` (e [MapWireGuard] lo ricontrolla prima che la richiesta parta);
 *  - un redirect si segue a mano solo se l'indirizzo nuovo passa la stessa decisione (al piu' [MAX_REDIRECTS]), altrimenti `REDIRECT`;
 *  - una risposta 200 si tiene solo con il tipo giusto (tessera vettoriale o JSON) e non oltre [MAX_DECODED_BYTES] dopo la decompressione
 *    (i byte sulla rete li conta e limita [MapWireGuard]); altrimenti `CONTENT`; degli header della risposta restano solo quelli della cache;
 *  - un 5xx, un 429 o una rete che non risponde fanno partire l'attesa crescente ([ServerBackoff], E-MAP3).
 * Senza versione nota prima il TileJSON (lo stesso cancello, una richiesta sola anche con molte tessere in coda). Ogni rifiuto e' contato per
 * motivo; i messaggi non nominano mai una tessera.
 */
internal class MapGateInterceptor(
  private val gate: MapRequestGate,
  private val conditions: (currentVersion: String?) -> MapNetState,
  private val tileJson: TileJsonCache,
  private val backoff: ServerBackoff,
  private val counters: MapNetCounters,
) : Interceptor {

  private val tileJsonLock = Any()

  override fun intercept(chain: Interceptor.Chain): Response {
    val original = chain.request()
    val facts = factsOf(original.method, original.url)
    val tile = stableTileOf(facts)
    var verdict = gate.decide(facts, conditions(tileJson.version()))
    // Prima la decisione, poi (se serve) il TileJSON: una tessera che non uscirebbe non fa uscire nemmeno quello.
    if (verdict == MapVerdict.VersionFirst || (verdict is MapVerdict.Allow && tile != null && tileJson.wantsRefresh())) {
      refreshVersion(chain)
      verdict = gate.decide(facts, conditions(tileJson.version()))
    }
    val path = when (verdict) {
      is MapVerdict.Allow -> verdict.path
      MapVerdict.VersionFirst -> {
        counters.noVersion()
        throw MapRequestHeld(null)
      }
      is MapVerdict.Refuse -> throw refusal(verdict.reason)
    }
    val inDownload = tile != null && conditions(null).download?.includes?.invoke(tile) == true
    val response = exchange(chain, outgoing(original, path, inDownload), isTile = tile != null)
    counters.allowed(tile?.z)
    return response.newBuilder().request(original).build()
  }

  /** Esce, segue i redirect ammessi, controlla la risposta. */
  private fun exchange(chain: Interceptor.Chain, first: Request, isTile: Boolean): Response {
    if (backoff.holding()) {
      counters.paused()
      throw MapRequestHeld(null)
    }
    var request = first
    var hops = 0
    while (true) {
      val response = try {
        chain.proceed(request)
      } catch (e: MapRequestRefused) {
        counters.refused(e.reason)
        throw e
      } catch (e: IOException) {
        counters.error()
        backoff.failure()
        throw e
      }
      if (response.code in REDIRECT_CODES) {
        val location = response.header("Location")
        response.close()
        val next = location?.let { request.url.resolve(it) }
        val verdict = next?.let { gate.decide(factsOf("GET", it), conditions(tileJson.version())) }
        if (verdict !is MapVerdict.Allow || ++hops > MAX_REDIRECTS) throw refusal(RefuseReason.REDIRECT)
        request = outgoing(request, verdict.path, keepUserAgent = request.header(USER_AGENT))
        continue
      }
      return checked(response, isTile)
    }
  }

  /** La risposta che MapLibre riceve: solo il necessario, mai oltre le regole. */
  private fun checked(response: Response, isTile: Boolean): Response {
    response.use {
      when {
        response.code == 429 || response.code >= 500 -> {
          counters.error()
          backoff.failure()
          return passStatus(response, body = null)
        }
        response.code == 404 -> {
          if (isTile) tileJson.markSuspect()
          backoff.success()
          return passStatus(response, body = null)
        }
        response.code != 200 -> {
          backoff.success()
          return passStatus(response, body = null)
        }
      }
      val type = response.header("Content-Type")
      if (!MapResponseRules.accepts(isTile, type, null)) throw refusal(RefuseReason.CONTENT)
      val bytes = try {
        readCapped(response)
      } catch (e: MapRequestRefused) {
        counters.refused(e.reason)
        throw e
      } catch (e: IOException) {
        counters.error()
        backoff.failure()
        throw e
      } ?: throw refusal(RefuseReason.CONTENT)
      backoff.success()
      return passStatus(response, body = bytes)
    }
  }

  private fun passStatus(response: Response, body: ByteArray?): Response {
    val kept = Headers.Builder()
    for (name in KEPT_RESPONSE_HEADERS) response.header(name)?.let { kept.add(name, it) }
    val media = response.body?.contentType()
    return response.newBuilder()
      .headers(kept.build())
      .body((body ?: ByteArray(0)).toResponseBody(if (body == null) null else media))
      .build()
  }

  /** Il corpo intero, o null se supera [MAX_DECODED_BYTES] (una tessera "bomba" compressa non si gonfia in memoria). */
  private fun readCapped(response: Response): ByteArray? {
    val source = response.body?.source() ?: return ByteArray(0)
    val buffer = Buffer()
    while (true) {
      val read = source.read(buffer, 64L * 1024)
      if (read == -1L) break
      if (buffer.size > MAX_DECODED_BYTES) return null
    }
    return buffer.readByteArray()
  }

  /** Chiede il TileJSON (una volta per tutte le richieste in coda) e ne ricorda la versione. Non lancia mai: senza versione, niente tessere. */
  private fun refreshVersion(chain: Interceptor.Chain) {
    synchronized(tileJsonLock) {
      if (!tileJson.wantsRefresh()) return
      tileJson.attempted()
      val facts = MapRequestFacts(MapHost.METHOD, MapHost.SCHEME, MapHost.TILES, MapHost.PORT, MapHost.TILEJSON_PATH, null)
      val verdict = gate.decide(facts, conditions(tileJson.version()))
      if (verdict !is MapVerdict.Allow) {
        if (verdict is MapVerdict.Refuse) counters.refused(verdict.reason)
        return
      }
      counters.tileJsonFetch()
      try {
        val request = Request.Builder().url(urlOf(verdict.path)).get().header(USER_AGENT, MapTileHeaders.USER_AGENT).build()
        exchange(chain, request, isTile = false).use { response ->
          if (response.code != 200) return
          val text = response.body?.string() ?: return
          val version = TileJsonReader.version(text)
          if (version == null) counters.refused(RefuseReason.CONTENT) else tileJson.store(version)
        }
      } catch (_: IOException) {
        // Gia' contato (errore, pausa o rifiuto): si riprova piu' tardi, al ritmo di TileJsonCache.
      }
    }
  }

  private fun refusal(reason: RefuseReason): IOException {
    counters.refused(reason)
    return when (reason) {
      RefuseReason.OFF, RefuseReason.NETWORK, RefuseReason.COVERED -> MapRequestHeld(reason)
      RefuseReason.HOST, RefuseReason.PATH, RefuseReason.REDIRECT, RefuseReason.CONTENT -> MapRequestRefused(reason)
    }
  }

  /** La richiesta che esce davvero: costruita da zero sull'unico host, con i soli header ammessi. */
  private fun outgoing(from: Request, path: String, inDownload: Boolean = false, keepUserAgent: String? = null): Request {
    val builder = Request.Builder().url(urlOf(path)).get()
    builder.header(USER_AGENT, keepUserAgent ?: if (inDownload) MapTileHeaders.USER_AGENT_DOWNLOAD else MapTileHeaders.USER_AGENT)
    for (name in KEPT_REQUEST_HEADERS) {
      val value = from.header(name)?.takeIf { it.length <= MAX_HEADER_CHARS && it.all { c -> c in ' '..'~' } } ?: continue
      builder.header(name, value)
    }
    return builder.build()
  }

  private fun urlOf(path: String): HttpUrl =
    HttpUrl.Builder().scheme(MapHost.SCHEME).host(MapHost.TILES).port(MapHost.PORT).encodedPath(path).build()

  companion object {
    const val USER_AGENT = "User-Agent"
    const val MAX_REDIRECTS = 2
    const val MAX_DECODED_BYTES = 4 * MapResponseRules.MAX_BYTES
    const val MAX_HEADER_CHARS = 256
    val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    /**
     * Gli header di MapLibre che possono uscire (validazione della cache): **solo la data** (correzioni I1, 31/10): un `ETag` e' un valore scelto dal server, diverso per ogni client
     * se vuole, e rimandarlo con `If-None-Match` ad ogni convalida, anche da un altro indirizzo, sarebbe un identificativo (L13). Il `User-Agent` lo mette il cancello.
     */
    val KEPT_REQUEST_HEADERS = listOf("If-Modified-Since")

    /** Gli header della risposta che MapLibre legge per la sua cache e per riprovare; tutto il resto (cookie e `ETag` compresi: senza `ETag` in cache non c'e' niente da rimandare) si butta. */
    val KEPT_RESPONSE_HEADERS = listOf("Content-Type", "Last-Modified", "Cache-Control", "Expires", "Retry-After")

    /** I fatti di una richiesta per la decisione (l'host di `HttpUrl` e' gia' minuscolo e in ASCII; la porta e' quella effettiva). */
    fun factsOf(method: String, url: HttpUrl): MapRequestFacts {
      val user = url.encodedUsername
      val password = url.encodedPassword
      return MapRequestFacts(
        method = method,
        scheme = url.scheme,
        host = url.host,
        port = url.port,
        path = url.encodedPath,
        query = url.encodedQuery,
        userInfo = if (user.isEmpty() && password.isEmpty()) null else "$user:$password",
        fragment = url.encodedFragment,
      )
    }

    /** La tessera dell'URL stabile, per i contatori per livello e per il tipo atteso della risposta (null: TileJSON o altro). */
    fun stableTileOf(facts: MapRequestFacts): TileKey? {
      val match = MapHost.STABLE_TILE.matchEntire(facts.path) ?: return null
      val z = match.groupValues[1].toInt()
      if (z > MapHost.MAX_ZOOM) return null
      val side = 1L shl z
      val x = match.groupValues[2].toLong()
      val y = match.groupValues[3].toLong()
      return if (x < side && y < side) TileKey(z, x.toInt(), y.toInt()) else null
    }
  }
}
