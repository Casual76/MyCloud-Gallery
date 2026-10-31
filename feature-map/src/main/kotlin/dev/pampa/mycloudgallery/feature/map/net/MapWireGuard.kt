package dev.pampa.mycloudgallery.feature.map.net

import dev.pampa.mycloudgallery.core.map.contract.DownloadBudget
import dev.pampa.mycloudgallery.core.map.contract.MapHost
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapResponseRules
import dev.pampa.mycloudgallery.core.map.contract.MapTraffic
import dev.pampa.mycloudgallery.core.map.contract.RefuseReason
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer

/** Gli header fissi delle richieste della mappa (§2.8; condizioni d'uso di OpenFreeMap lette il 30/10, `docs/decisioni.md` "Fase 9 P1"). */
internal object MapTileHeaders {
  /**
   * Per guardare la mappa: fisso e generico, il nome del motore senza versione, senza app, dispositivo o Android. Le condizioni d'uso non ne
   * chiedono uno per la visualizzazione; uno uguale per tutte le app MapLibre non distingue questa famiglia.
   */
  const val USER_AGENT = "MapLibre Android"

  /**
   * Per le tessere di uno scaricamento confermato di una regione (D26, P5): le condizioni d'uso chiedono, per lo scaricamento offline, "un
   * User-Agent unico che identifichi l'app". Fisso, senza versione ne' dispositivo; il server vede gia' quale zona si scarica (L265).
   */
  const val USER_AGENT_DOWNLOAD = "MyCloudGallery-offline/1"
}

/** Il DNS del client: risolve **solo** l'unico host della mappa; ogni altro nome e' un errore prima di qualunque pacchetto (difesa in piu'). */
internal class OnlyMapHostDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
  override fun lookup(hostname: String): List<InetAddress> {
    if (hostname != MapHost.TILES) throw UnknownHostException("mappa: host non ammesso")
    return delegate.lookup(hostname)
  }
}

/**
 * L'ultimo controllo prima e dopo il filo (intercettore di **rete** del client, P1): la richiesta che sta per partire e' ancora `GET` HTTPS
 * verso l'unico host sulla 443 e porta solo gli header ammessi (quelli del cancello piu' `Host`, `Connection` e `Accept-Encoding` di OkHttp);
 * la risposta non supera [MapResponseRules.MAX_BYTES] **sulla rete** (compressa), e i byte arrivati vanno nel registro del traffico
 * ([MapTraffic], proposta 3) e nei contatori. Se qualcosa non torna la richiesta non parte (o la risposta si butta): `HOST` o `CONTENT`.
 */
internal class MapWireGuard(
  private val traffic: MapTraffic,
  private val network: () -> MapNetwork,
  private val counters: MapNetCounters,
) : Interceptor {

  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    checkOutgoing(request)
    return guarded(chain.proceed(request))
  }

  /** La richiesta che sta per partire: `GET` HTTPS sull'unico host e sulla 443, con i soli header ammessi. Altrimenti non parte. */
  fun checkOutgoing(request: Request) {
    val url = request.url
    val headersOk = request.headers.names().all { it.lowercase() in WIRE_HEADERS }
    if (request.method != MapHost.METHOD || url.scheme != MapHost.SCHEME || url.host != MapHost.TILES || url.port != MapHost.PORT || !headersOk) {
      throw MapRequestRefused(RefuseReason.HOST)
    }
  }

  /** La risposta con il corpo contato e limitato sui byte della rete (oltre il tetto dichiarato: buttata subito). */
  fun guarded(response: Response): Response {
    val body = response.body ?: return response
    if (body.contentLength() > MapResponseRules.MAX_BYTES) {
      response.close()
      throw MapRequestRefused(RefuseReason.CONTENT)
    }
    return response.newBuilder().body(counted(response, body)).build()
  }

  /** Il corpo che si legge contando i byte sul filo: oltre il tetto e' un rifiuto; alla fine (o alla chiusura) i byte vanno registrati una volta. */
  private fun counted(response: Response, body: ResponseBody): ResponseBody {
    val recorded = AtomicBoolean(false)
    // P5: se la richiesta e' di uno scaricamento di regione, i byte veri sul filo vanno anche nel suo conto (50 MB): man mano che arrivano, cosi' lo scaricamento si ferma subito.
    val budget = response.request.tag(DownloadBudget::class.java)
    var total = 0L
    val net = network()
    fun record() {
      if (recorded.compareAndSet(false, true)) {
        counters.bytes(total)
        traffic.record(total, net)
      }
    }
    val source: Source = object : ForwardingSource(body.source()) {
      override fun read(sink: Buffer, byteCount: Long): Long {
        val read = super.read(sink, byteCount)
        if (read == -1L) {
          record()
          return read
        }
        total += read
        budget?.received(read)
        if (total > MapResponseRules.MAX_BYTES) {
          record()
          throw MapRequestRefused(RefuseReason.CONTENT)
        }
        return read
      }

      override fun close() {
        record()
        super.close()
      }
    }
    val buffered: BufferedSource = source.buffer()
    return object : ResponseBody() {
      override fun contentType() = body.contentType()
      override fun contentLength() = body.contentLength()
      override fun source(): BufferedSource = buffered
    }
  }

  private companion object {
    /** Gli header che possono partire: quelli del cancello e i tre che OkHttp aggiunge da se'. Nessun cookie (il client non ne ha). */
    val WIRE_HEADERS = setOf("user-agent", "if-modified-since", "host", "connection", "accept-encoding")
  }
}
