package dev.pampa.mycloudgallery.core.models

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.models.contract.FetchRefusal
import dev.pampa.mycloudgallery.core.models.contract.FetchResult
import dev.pampa.mycloudgallery.core.models.contract.ModelHosts
import dev.pampa.mycloudgallery.core.models.contract.ModelHttpsRoute
import dev.pampa.mycloudgallery.core.models.contract.ModelSource
import dev.pampa.mycloudgallery.core.models.contract.ModelTraffic
import dev.pampa.mycloudgallery.core.models.download.https.ETags
import dev.pampa.mycloudgallery.core.models.download.https.HttpsConnections
import dev.pampa.mycloudgallery.core.models.download.https.HttpsModelFetcher
import dev.pampa.mycloudgallery.core.models.download.https.NetworkBoundConnections
import dev.pampa.mycloudgallery.core.models.support.FileBehavior
import dev.pampa.mycloudgallery.core.models.support.HttpsTestServer
import dev.pampa.mycloudgallery.core.models.support.RecordedRequest
import dev.pampa.mycloudgallery.core.models.support.TestCertificate
import dev.pampa.mycloudgallery.core.models.support.TestConnections
import dev.pampa.mycloudgallery.core.models.support.TestExchange
import dev.pampa.mycloudgallery.core.models.support.TestModels
import dev.pampa.mycloudgallery.core.models.support.TrafficCounter
import dev.pampa.mycloudgallery.core.models.support.serve
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 6, P3 (§2.1 "HTTPS", D17, D33, E-A1, E-S16): lo scaricamento HTTPS contro un server HTTPS di prova del JDK
 * (`com.sun.net.httpserver`) con un certificato di prova fidato SOLO qui. Ripresa a ogni blocco da 1 MiB con `Range` e `If-Range`,
 * `200` al posto di `206`, lunghezze false, redirezioni fuori dagli host ammessi o verso `http`, file oltre la dimensione fissata, e le
 * richieste ispezionate: nessun cookie, nessun parametro, nessuna credenziale.
 */
class HttpsModelFetcherTest {
  private val server = HttpsTestServer()
  private val connections = TestConnections(server)
  private val traffic = TrafficCounter()
  private val bytes = TestModels.bytes()
  private val spec = TestModels.spec(bytes)
  private val dir = TestModels.tempDir()
  private val partial = File(dir, "partial/${spec.sha256.hex}.part")
  private val source = ModelSource.Https(spec.https.single())
  private val pinnedPath = java.net.URI(spec.https.single().value).rawPath
  private val behavior = FileBehavior(bytes)

  private fun fetcher(
    traffic: ModelTraffic = this.traffic,
    connections: HttpsConnections = this.connections,
    readTimeoutMs: Int = 5_000,
    nanos: () -> Long = System::nanoTime,
  ) = HttpsModelFetcher(traffic, connections, Dispatchers.IO, nanos, connectTimeoutMs = 5_000, readTimeoutMs = readTimeoutMs)

  private fun fetch(budget: Duration = 60.seconds, fetcher: HttpsModelFetcher = fetcher()): FetchResult =
    runBlocking { fetcher.fetch(spec, source, partial, budget) }

  @After
  fun stop() {
    server.close()
  }

  private fun serveFile() {
    server.handler = { exchange, _ -> exchange.serve(behavior) }
  }

  // --- Richieste pulite -------------------------------------------------------------------------------------------------------

  @Test
  fun scaricaTuttoConUnaRichiestaPulita() {
    serveFile()
    assertThat(fetch()).isEqualTo(FetchResult.Done)
    assertThat(partial.readBytes()).isEqualTo(bytes)
    assertThat(traffic.total).isEqualTo(bytes.size.toLong())
    assertThat(traffic.records).isAtLeast(4)

    val request = server.requests.single()
    assertThat(request.method).isEqualTo("GET")
    assertThat(request.path).isEqualTo(pinnedPath)
    // Nessun parametro, nessun cookie, nessuna credenziale, nessuna intestazione che identifichi l'installazione.
    assertThat(request.rawQuery).isNull()
    assertThat(request.logicalHost).isEqualTo("huggingface.co")
    assertThat(request.header("User-Agent")).isEqualTo("MyCloudGallery")
    assertThat(request.header("Accept-Encoding")).isEqualTo("identity")
    assertThat(request.has("Range")).isFalse()
    assertThat(request.has("If-Range")).isFalse()
    assertThat(request.headerNames).containsNoneOf("cookie", "authorization", "proxy-authorization", "referer", "origin", "x-forwarded-for")
    assertThat(request.headerNames - ALLOWED_HEADERS).isEmpty()
  }

  @Test
  fun ilClientNonRimandaMaiUnCookieNeppureSeIlServerNeManda() {
    behavior.extraHeaders = mapOf("Set-Cookie" to "sid=abc123; Path=/; Secure", "Set-Cookie2" to "x=y")
    behavior.cutBodyAt = TestModels.BLOCK.toLong()
    serveFile()
    assertThat(fetch()).isEqualTo(FetchResult.Partial(TestModels.BLOCK.toLong()))
    behavior.cutBodyAt = null
    assertThat(fetch()).isEqualTo(FetchResult.Done)
    assertThat(server.requests).hasSize(2)
    server.requests.forEach { assertThat(it.headerNames).containsNoneOf("cookie", "cookie2") }
  }

  @Test
  fun conUnGestoreGlobaleDeiCookieNonSiParteNeppure() {
    serveFile()
    val previous = CookieHandler.getDefault()
    CookieHandler.setDefault(CookieManager())
    try {
      assertThat(fetch()).isEqualTo(FetchResult.Refused(FetchRefusal.COOKIE_HANDLER))
    } finally {
      CookieHandler.setDefault(previous)
    }
    assertThat(connections.opened).isEmpty()
    assertThat(server.requests).isEmpty()
  }

  @Test
  fun unaSorgenteCheNonEHttpsSiRifiuta() {
    val result = runBlocking { fetcher().fetch(spec, ModelSource.PcFolder("test-comune"), partial, 10.seconds) }
    assertThat(result).isEqualTo(FetchResult.Refused(FetchRefusal.SOURCE_KIND))
    assertThat(server.requests).isEmpty()
  }

  @Test
  fun uncertificatoNonFidatoNonScaricaNiente() {
    serveFile()
    // La fiducia di sistema (nessuna connessione di prova): il certificato autofirmato del server non e' accettato.
    val untrusting = HttpsConnections { url ->
      val uri = java.net.URI(url)
      URL("https", "localhost", server.port, uri.rawPath).openConnection() as HttpsURLConnection
    }
    val result = fetch(fetcher = fetcher(connections = untrusting))
    assertThat(result).isEqualTo(FetchResult.NetworkError)
    assertThat(server.requests).isEmpty()
    assertThat(partial.exists()).isFalse()
  }

  // --- Ripresa (E-A1) ---------------------------------------------------------------------------------------------------------

  @Test
  fun riprendeAOgniBloccoDa1MiBConRangeEIfRange() {
    for (blocks in 1..3) {
      val partialFile = File(dir, "k$blocks/${spec.sha256.hex}.part")
      server.requests.clear()
      val before = traffic.total
      // Il server taglia dopo `blocks` MiB piu' una coda: il client tiene i blocchi interi e butta la coda.
      behavior.cutBodyAt = blocks.toLong() * TestModels.BLOCK + 500
      serveFile()
      val first = runBlocking { fetcher().fetch(spec, source, partialFile, 60.seconds) }
      assertThat(first).isEqualTo(FetchResult.Partial(blocks.toLong() * TestModels.BLOCK))
      assertThat(partialFile.length()).isEqualTo(blocks.toLong() * TestModels.BLOCK)
      assertThat(ETags.read(partialFile)).isEqualTo("\"v1\"")

      behavior.cutBodyAt = null
      val second = runBlocking { fetcher().fetch(spec, source, partialFile, 60.seconds) }
      assertThat(second).isEqualTo(FetchResult.Done)
      assertThat(partialFile.readBytes()).isEqualTo(bytes)

      val resumed = server.requests.last()
      assertThat(resumed.header("Range")).isEqualTo("bytes=${blocks.toLong() * TestModels.BLOCK}-")
      assertThat(resumed.header("If-Range")).isEqualTo("\"v1\"")
      assertThat(resumed.rawQuery).isNull()
      // Invariante 3 (§5.5): un'interruzione costa al piu' 1 MiB riscaricato.
      assertThat(traffic.total - before - bytes.size).isAtMost(TestModels.BLOCK.toLong())
    }
  }

  @Test
  fun lungoIlParzialeUnaCodaStrappataSiButta() {
    // Un processo morto a meta' blocco: 1 MiB buono piu' 777 byte di coda. La ripresa riparte dal confine.
    partial.parentFile.mkdirs()
    partial.writeBytes(bytes.copyOf(TestModels.BLOCK + 777))
    ETags.save(partial, "\"v1\"")
    serveFile()
    assertThat(fetch()).isEqualTo(FetchResult.Done)
    assertThat(partial.readBytes()).isEqualTo(bytes)
    assertThat(server.requests.single().header("Range")).isEqualTo("bytes=${TestModels.BLOCK}-")
  }

  @Test
  fun unParzialeGiaCompletoNonChiedeNiente() {
    partial.parentFile.mkdirs()
    partial.writeBytes(bytes)
    assertThat(fetch()).isEqualTo(FetchResult.Done)
    assertThat(server.requests).isEmpty()
  }

  @Test
  fun unParzialePiuLungoDelModelloSiButtaESiRiparteDaZero() {
    partial.parentFile.mkdirs()
    partial.writeBytes(ByteArray(bytes.size + 10))
    serveFile()
    assertThat(fetch()).isEqualTo(FetchResult.Done)
    assertThat(partial.readBytes()).isEqualTo(bytes)
    assertThat(server.requests.single().has("Range")).isFalse()
  }

  @Test
  fun un200AlPostoDel206RipartiDaZero() {
    partial.parentFile.mkdirs()
    partial.writeBytes(bytes.copyOf(TestModels.BLOCK))
    behavior.honorRange = false
    serveFile()
    assertThat(fetch()).isEqualTo(FetchResult.Done)
    assertThat(partial.readBytes()).isEqualTo(bytes)
    assertThat(server.requests.single().header("Range")).isEqualTo("bytes=${TestModels.BLOCK}-")
    // Tutto il file e' stato letto di nuovo (l'interruzione + il 200 dichiarato nel §5.5: "salvo un 200 del server").
    assertThat(traffic.total).isEqualTo(bytes.size.toLong())
  }

  @Test
  fun unValidatoreCheNonTornaFaRicominciareDaZero() {
    behavior.cutBodyAt = TestModels.BLOCK.toLong() + 100
    serveFile()
    assertThat(fetch()).isEqualTo(FetchResult.Partial(TestModels.BLOCK.toLong()))
    // Il file sul server e' cambiato (ETag nuovo): `If-Range` non torna, il server risponde 200 con tutto il file.
    behavior.cutBodyAt = null
    behavior.etag = "\"v2\""
    assertThat(fetch()).isEqualTo(FetchResult.Done)
    assertThat(partial.readBytes()).isEqualTo(bytes)
    val resumed = server.requests.last()
    assertThat(resumed.header("If-Range")).isEqualTo("\"v1\"")
    // R13: da quel `200` il parziale non ha piu' un validatore: il CDN lo cambia a ogni richiesta e `If-Range` non tornerebbe mai.
    assertThat(ETags.read(partial)).isNull()
  }

  @Test
  fun senzaUnValidatoreForteSiRiprendeSoloConRange() {
    for (etag in listOf<String?>(null, "W/\"debole\"", "senza-virgolette")) {
      val partialFile = File(dir, "e${etag.hashCode()}/${spec.sha256.hex}.part")
      server.requests.clear()
      behavior.etag = etag
      behavior.cutBodyAt = TestModels.BLOCK.toLong()
      serveFile()
      assertThat(runBlocking { fetcher().fetch(spec, source, partialFile, 60.seconds) }).isEqualTo(FetchResult.Partial(TestModels.BLOCK.toLong()))
      behavior.cutBodyAt = null
      assertThat(runBlocking { fetcher().fetch(spec, source, partialFile, 60.seconds) }).isEqualTo(FetchResult.Done)
      assertThat(server.requests.last().header("Range")).isEqualTo("bytes=${TestModels.BLOCK}-")
      assertThat(server.requests.last().has("If-Range")).isFalse()
    }
  }

  @Test
  fun un416FaRicominciareDaZeroUnaVoltaSola() {
    partial.parentFile.mkdirs()
    partial.writeBytes(bytes.copyOf(TestModels.BLOCK))
    server.handler = { exchange, request ->
      if (request.has("Range")) {
        exchange.responseHeaders.add("Content-Range", "bytes */${bytes.size}")
        exchange.sendResponseHeaders(416, -1)
      } else {
        exchange.serve(behavior)
      }
    }
    assertThat(fetch()).isEqualTo(FetchResult.Done)
    assertThat(partial.readBytes()).isEqualTo(bytes)
    assertThat(server.requests.map { it.has("Range") }).containsExactly(true, false).inOrder()

    // Un server che risponde sempre 416 non fa girare a vuoto: una volta sola, poi si ferma.
    partial.writeBytes(bytes.copyOf(TestModels.BLOCK))
    server.requests.clear()
    server.handler = { exchange, _ ->
      exchange.sendResponseHeaders(416, -1)
    }
    assertThat(fetch()).isEqualTo(FetchResult.NetworkError)
    assertThat(server.requests.size).isAtMost(2)
  }

  @Test
  fun leIntestazioniDellaRipresaArrivanoAncheAlCdnDopoLaRedirezione() {
    val cdn = "https://cas-bridge.xethub.hf.co/obj/abc?X-Amz-Signature=deadbeef&X-Amz-Expires=3600"
    behavior.cutBodyAt = 2L * TestModels.BLOCK + 10
    server.handler = { exchange, request ->
      if (request.logicalHost == "huggingface.co") {
        exchange.responseHeaders.add("Location", cdn)
        exchange.sendResponseHeaders(302, -1)
      } else {
        exchange.serve(behavior)
      }
    }
    assertThat(fetch()).isEqualTo(FetchResult.Partial(2L * TestModels.BLOCK))
    behavior.cutBodyAt = null
    assertThat(fetch()).isEqualTo(FetchResult.Done)
    assertThat(partial.readBytes()).isEqualTo(bytes)
    val hops = server.requests.takeLast(2)
    assertThat(hops[0].logicalHost).isEqualTo("huggingface.co")
    assertThat(hops[1].logicalHost).isEqualTo("cas-bridge.xethub.hf.co")
    assertThat(hops[1].header("Range")).isEqualTo("bytes=${2L * TestModels.BLOCK}-")
    assertThat(hops[1].header("If-Range")).isEqualTo("\"v1\"")
  }

  // --- Lunghezze false e file oltre la dimensione fissata (E-S16) -------------------------------------------------------------

  @Test
  fun unaLunghezzaDichiarataOltreLaDimensioneFissataSiRifiutaPrimaDiLeggere() {
    val bigger = FileBehavior(bytes + ByteArray(100))
    server.handler = { exchange, _ -> exchange.serve(bigger) }
    assertThat(fetch()).isEqualTo(FetchResult.SizeMismatch)
    assertThat(partial.exists()).isFalse()
    assertThat(traffic.total).isEqualTo(0)
  }

  @Test
  fun unaLunghezzaDichiarataPiuCortaDellaDimensioneFissataSiRifiuta() {
    val shorter = FileBehavior(bytes.copyOf(bytes.size - 100))
    server.handler = { exchange, _ -> exchange.serve(shorter) }
    assertThat(fetch()).isEqualTo(FetchResult.SizeMismatch)
    assertThat(partial.exists()).isFalse()
  }

  @Test
  fun unFlussoPiuLungoDelDichiaratoSiFermaESiScarta() {
    // Senza `Content-Length` (a pezzi) il server puo' mandare piu' del dovuto: il client si ferma alla dimensione e lo vede.
    behavior.chunked = true
    behavior.extraTail = 4096
    serveFile()
    assertThat(fetch()).isEqualTo(FetchResult.Mismatch)
    // Mai piu' byte della dimensione fissata nel parziale.
    assertThat(partial.length()).isAtMost(bytes.size.toLong())
  }

  @Test
  fun unFlussoPiuLungoMandatoInUnaSolaScritturaNonSiLeggeOltreLaDimensione() {
    // L'ultimo blocco e' corto (1.000 byte) e la coda (3.000) sta nello stesso pezzo: e' il tetto sulle letture a impedire di tirarla dentro.
    val small = TestModels.bytes(TestModels.BLOCK + 1_000, seed = 31)
    val smallSpec = TestModels.spec(small)
    val smallPartial = File(dir, "small/${smallSpec.sha256.hex}.part")
    val tailed = FileBehavior(small, chunked = true, extraTail = 3_000, extraInSameWrite = true)
    server.handler = { exchange, _ -> exchange.serve(tailed) }
    val result = runBlocking { fetcher().fetch(smallSpec, ModelSource.Https(smallSpec.https.single()), smallPartial, 60.seconds) }
    assertThat(result).isEqualTo(FetchResult.Mismatch)
    assertThat(smallPartial.length()).isAtMost(small.size.toLong())
  }

  @Test
  fun un206CoerenteMaCheComincia1ByteDopoIlPuntoChiestoSiRifiuta() {
    partial.parentFile.mkdirs()
    partial.writeBytes(bytes.copyOf(TestModels.BLOCK))
    val reference = partial.readBytes()
    val from = TestModels.BLOCK + 1
    server.handler = { exchange, _ ->
      exchange.responseHeaders.add("Content-Range", "bytes $from-${bytes.size - 1}/${bytes.size}")
      exchange.sendResponseHeaders(206, (bytes.size - from).toLong())
      exchange.responseBody.write(bytes, from, bytes.size - from)
    }
    // La lunghezza torna con l'intervallo dichiarato, ma l'intervallo non e' quello chiesto: accodarlo sposterebbe tutti i byte.
    assertThat(fetch()).isEqualTo(FetchResult.Refused(FetchRefusal.CONTENT_RANGE))
    assertThat(partial.readBytes()).isEqualTo(reference)
  }

  @Test
  fun unFlussoAPezziDellaDimensioneGiustaVaBene() {
    behavior.chunked = true
    serveFile()
    assertThat(fetch()).isEqualTo(FetchResult.Done)
    assertThat(partial.readBytes()).isEqualTo(bytes)
  }

  @Test
  fun unFlussoAPezziPiuCortoDellaDimensioneFissataEUnaInterruzione() {
    val shorter = FileBehavior(bytes.copyOf(2 * TestModels.BLOCK + 5), chunked = true)
    server.handler = { exchange, _ -> exchange.serve(shorter) }
    // Il flusso finisce regolarmente ma troppo presto: non e' un file completo, e' un'interruzione (si riprendera').
    assertThat(fetch()).isEqualTo(FetchResult.Partial(2L * TestModels.BLOCK))
  }

  @Test
  fun unCorpoPiuCortoDelDichiaratoEUnaInterruzioneCheSiRiprende() {
    behavior.cutBodyAt = TestModels.BLOCK + 123L
    serveFile()
    assertThat(fetch()).isEqualTo(FetchResult.Partial(TestModels.BLOCK.toLong()))
  }

  @Test
  fun unContentRangeCheNonTornaSiRifiutaENonSiAccodaNiente() {
    partial.parentFile.mkdirs()
    partial.writeBytes(bytes.copyOf(TestModels.BLOCK))
    val reference = partial.readBytes()
    val cases = mapOf<String, (Long, Long, Long) -> String>(
      "inizio diverso da quello chiesto" to { _, end, total -> "bytes 0-$end/$total" },
      "fine prima della fine del file" to { from, _, total -> "bytes $from-${total - 2}/$total" },
      "formato strano" to { from, end, total -> "items $from-$end/$total" },
      "senza totale e inizio sbagliato" to { _, end, _ -> "bytes 5-$end/*" },
    )
    for ((name, header) in cases) {
      behavior.contentRange = header
      serveFile()
      assertWithName(name, fetch(), FetchResult.Refused(FetchRefusal.CONTENT_RANGE))
      assertThat(partial.readBytes()).isEqualTo(reference)
    }
    // Un totale diverso da quello fissato e' un altro file.
    behavior.contentRange = { from, end, total -> "bytes $from-$end/${total + 1}" }
    serveFile()
    assertThat(fetch()).isEqualTo(FetchResult.SizeMismatch)
  }

  private fun assertWithName(name: String, actual: FetchResult, expected: FetchResult) {
    assertThat(actual).isEqualTo(expected)
    assertThat(name).isNotEmpty()
  }

  @Test
  fun un206SenzaRangeChiestoESospetto() {
    server.handler = { exchange, _ ->
      exchange.responseHeaders.add("Content-Range", "bytes 0-${bytes.size - 1}/${bytes.size}")
      exchange.sendResponseHeaders(206, bytes.size.toLong())
      exchange.responseBody.write(bytes)
    }
    assertThat(fetch()).isEqualTo(FetchResult.Refused(FetchRefusal.CONTENT_RANGE))
  }

  @Test
  fun unaCodificaDelContenutoSiRifiuta() {
    behavior.contentEncoding = "gzip"
    serveFile()
    assertThat(fetch()).isEqualTo(FetchResult.Refused(FetchRefusal.ENCODING))
    assertThat(partial.exists()).isFalse()
  }

  // --- Redirezioni (E-S16) ----------------------------------------------------------------------------------------------------

  @Test
  fun siSeguonoLeRedirezioniVersoHostAmmessiConLaQueryDelServer() {
    val signed = "X-Amz-Signature=deadbeef&X-Amz-Expires=3600"
    server.handler = { exchange, request ->
      when (request.logicalHost) {
        "huggingface.co" -> {
          exchange.responseHeaders.add("Location", "https://cas-bridge.xethub.hf.co/obj/abc?$signed")
          exchange.sendResponseHeaders(302, -1)
        }
        else -> exchange.serve(behavior)
      }
    }
    assertThat(fetch()).isEqualTo(FetchResult.Done)
    assertThat(partial.readBytes()).isEqualTo(bytes)
    val (first, second) = server.requests
    // La prima richiesta e' pulita; la seconda porta esattamente la firma scelta dal CDN e niente altro.
    assertThat(first.rawQuery).isNull()
    assertThat(second.rawQuery).isEqualTo(signed)
    assertThat(second.path).isEqualTo("/obj/abc")
    assertThat(second.logicalHost).isEqualTo("cas-bridge.xethub.hf.co")
    assertThat(connections.opened.map { java.net.URI(it).host }).containsExactly("huggingface.co", "cas-bridge.xethub.hf.co").inOrder()
    server.requests.forEach { assertThat(it.headerNames).containsNoneOf("cookie", "authorization", "referer") }
  }

  @Test
  fun unaRedirezioneRelativaRestaSuLHostCorrente() {
    server.handler = { exchange, request ->
      if (request.path == pinnedPath) {
        exchange.responseHeaders.add("Location", "/spostato/model.litertlm")
        exchange.sendResponseHeaders(301, -1)
      } else {
        exchange.serve(behavior)
      }
    }
    assertThat(fetch()).isEqualTo(FetchResult.Done)
    assertThat(server.requests.map { it.logicalHost }.toSet()).containsExactly("huggingface.co")
  }

  @Test
  fun unaRedirezioneFuoriDagliHostAmmessiSiFermaPrimaDiConnettersi() {
    val targets = listOf(
      "https://evil.example.org/model",
      "https://huggingface.co.evil.example.org/model",
      "https://evilhf.co/model",
      "https://hf.co/model",
      "https://127.0.0.1:1/model",
      "https://192.168.1.10/model",
    )
    for (target in targets) {
      connections.opened.clear()
      server.requests.clear()
      server.handler = { exchange, _ ->
        exchange.responseHeaders.add("Location", target)
        exchange.sendResponseHeaders(302, -1)
      }
      val result = fetch()
      assertThat(result).isInstanceOf(FetchResult.Refused::class.java)
      assertThat(FetchRefusal.isHostile((result as FetchResult.Refused).reason)).isTrue()
      // Solo il primo indirizzo (quello fissato) e' stato aperto: l'host non ammesso non e' mai stato contattato.
      assertThat(connections.opened).hasSize(1)
      assertThat(connections.opened.single()).isEqualTo(spec.https.single().value)
      assertThat(server.requests).hasSize(1)
    }
    assertThat(traffic.total).isEqualTo(0)
  }

  @Test
  fun unaRedirezioneVersoHttpInChiaroSiFerma() {
    for (target in listOf("http://huggingface.co/model", "http://cdn-lfs.hf.co/model")) {
      connections.opened.clear()
      server.handler = { exchange, _ ->
        exchange.responseHeaders.add("Location", target)
        exchange.sendResponseHeaders(307, -1)
      }
      assertThat(fetch()).isEqualTo(FetchResult.Refused(FetchRefusal.REDIRECT_SCHEME))
      assertThat(connections.opened).hasSize(1)
    }
  }

  @Test
  fun unaRedirezioneConUtentePortaOFrammentoSiFerma() {
    for (target in listOf("https://user:pw@huggingface.co/m", "https://huggingface.co:8443/m", "https://huggingface.co/m#frag")) {
      connections.opened.clear()
      server.handler = { exchange, _ ->
        exchange.responseHeaders.add("Location", target)
        exchange.sendResponseHeaders(302, -1)
      }
      assertThat(fetch()).isEqualTo(FetchResult.Refused(FetchRefusal.REDIRECT_FORMAT))
      assertThat(connections.opened).hasSize(1)
    }
  }

  @Test
  fun unaRedirezioneSenzaLocationSiFerma() {
    server.handler = { exchange, _ -> exchange.sendResponseHeaders(302, -1) }
    assertThat(fetch()).isEqualTo(FetchResult.Refused(FetchRefusal.REDIRECT_FORMAT))
  }

  @Test
  fun alPiuCinqueRedirezioni() {
    var hop = 0
    server.handler = { exchange, _ ->
      hop++
      exchange.responseHeaders.add("Location", "https://cdn-lfs.hf.co/loop/$hop")
      exchange.sendResponseHeaders(302, -1)
    }
    assertThat(fetch()).isEqualTo(FetchResult.Refused(FetchRefusal.REDIRECT_LIMIT))
    // La richiesta fissata piu' le cinque redirezioni seguite; la sesta redirezione non si segue (nessuna settima connessione).
    assertThat(connections.opened).hasSize(1 + ModelHosts.MAX_REDIRECTS)
  }

  @Test
  fun cinqueRedirezioniSiSeguono() {
    var hop = 0
    server.handler = { exchange, _ ->
      if (hop < ModelHosts.MAX_REDIRECTS) {
        hop++
        exchange.responseHeaders.add("Location", "https://cdn-lfs.hf.co/step/$hop")
        exchange.sendResponseHeaders(302, -1)
      } else {
        exchange.serve(behavior)
      }
    }
    assertThat(fetch()).isEqualTo(FetchResult.Done)
  }

  // --- Codici HTTP ------------------------------------------------------------------------------------------------------------

  @Test
  fun iCodiciHttpHannoUnEsitoStabile() {
    val expected = mapOf(
      404 to FetchResult.NotFound,
      410 to FetchResult.NotFound,
      403 to FetchResult.Refused(FetchRefusal.HTTP_STATUS),
      401 to FetchResult.Refused(FetchRefusal.HTTP_STATUS),
      304 to FetchResult.Refused(FetchRefusal.HTTP_STATUS),
      500 to FetchResult.NetworkError,
      503 to FetchResult.NetworkError,
      429 to FetchResult.NetworkError,
      408 to FetchResult.NetworkError,
    )
    for ((status, result) in expected) {
      server.handler = { exchange, _ -> exchange.sendResponseHeaders(status, -1) }
      assertThat(fetch()).isEqualTo(result)
    }
  }

  @Test
  fun unServerCheNonRispondeEUnErroreDiRete() {
    server.close()
    assertThat(fetch()).isEqualTo(FetchResult.NetworkError)
  }

  // --- Tempo, annullamento ----------------------------------------------------------------------------------------------------

  @Test
  fun lasciaIlTempoAlChiamanteAiConfiniDiBlocco() {
    val now = java.util.concurrent.atomic.AtomicLong()
    val clock = ModelTraffic { now.addAndGet(1_000_000_000L) }
    serveFile()
    // Ogni blocco registrato "costa" un secondo: con 1,5 s di tempo ci si ferma dopo il secondo blocco.
    val result = fetch(budget = 1500.milliseconds, fetcher = fetcher(traffic = clock, nanos = { now.get() }))
    assertThat(result).isEqualTo(FetchResult.Partial(2L * TestModels.BLOCK))
    assertThat(partial.length()).isEqualTo(2L * TestModels.BLOCK)
  }

  @Test
  fun senzaTempoNonSiApreNiente() {
    serveFile()
    assertThat(fetch(budget = Duration.ZERO)).isEqualTo(FetchResult.NetworkError)
    assertThat(server.requests).isEmpty()
  }

  @Test
  fun unServerChePiantaLaRispostaFaScattareLaScadenzaDiLettura() {
    server.handler = { exchange, _ ->
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.write(bytes, 0, TestModels.BLOCK)
      exchange.responseBody.flush()
      Thread.sleep(10_000)
    }
    val started = System.nanoTime()
    val result = fetch(fetcher = fetcher(readTimeoutMs = 700))
    val seconds = (System.nanoTime() - started) / 1e9
    assertThat(result).isEqualTo(FetchResult.Partial(TestModels.BLOCK.toLong()))
    assertThat(seconds).isLessThan(8.0)
  }

  @Test
  fun annullareChiudeLaConnessioneSubito() {
    server.handler = { exchange, _ ->
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.write(bytes, 0, TestModels.BLOCK)
      exchange.responseBody.flush()
      Thread.sleep(30_000)
    }
    val slow = fetcher(readTimeoutMs = 25_000)
    val seconds = runBlocking {
      val job = launch(Dispatchers.Default) { slow.fetch(spec, source, partial, 120.seconds) }
      delay(1_500)
      val started = System.nanoTime()
      job.cancelAndJoin()
      (System.nanoTime() - started) / 1e9
    }
    // Senza la chiusura della connessione la lettura bloccata durerebbe fino ai 25 s di scadenza.
    assertThat(seconds).isLessThan(5.0)
  }

  // --- R12: la rete approvata dal cancello ---------------------------------------------------------------------------------------------

  /**
   * R12: la connessione usciva sulla rete predefinita del sistema, non su quella che il cancello aveva giudicato (a casa, con i dati mobili resi
   * predefiniti da One UI, fino a 370 MB senza conferma). Con una rotta la rete si chiede a ogni connessione; senza rete approvata la connessione
   * non si apre e **non** si ripiega sulla rete predefinita.
   */
  @Test
  fun r12_senzaUnaReteApprovataLaConnessioneNonSiApreEIlFetcherDiceNonAdesso() {
    serveFile()
    var asked = 0
    val route = ModelHttpsRoute {
      asked++
      null
    }
    assertThrows(IOException::class.java) { NetworkBoundConnections(route).open("https://huggingface.co/x") }
    assertThat(asked).isEqualTo(1)
    asked = 0
    val result = fetch(fetcher = fetcher(connections = NetworkBoundConnections(route)))
    assertThat(result).isEqualTo(FetchResult.NetworkError)
    assertThat(asked).isEqualTo(1)
    // Il server di prova non ha visto niente: nessun ripiego su un'altra via.
    assertThat(server.requests).isEmpty()
    assertThat(traffic.total).isEqualTo(0)
    assertThat(partial.exists()).isFalse()
  }

  @Test
  fun r12_laRotaDelMontaggioPubblicoEOpzionaleEDiSerieNonCambiaNiente() {
    // `ModelFetchers.create` senza rotta e con rotta producono un fetcher; la rotta e' un'aggiunta, non un obbligo.
    val nas = dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia().reader
    val assets = dev.pampa.mycloudgallery.core.models.contract.BundledAssets { null }
    assertThat(dev.pampa.mycloudgallery.core.models.ModelFetchers.create(nas, assets, traffic)).isNotNull()
    assertThat(dev.pampa.mycloudgallery.core.models.ModelFetchers.create(nas, assets, traffic, httpsRoute = ModelHttpsRoute { null })).isNotNull()
  }

  // --- R13: il validatore dopo un 200, e l'annullamento che non tronca il parziale ---------------------------------------------------

  /** R13: un CDN che cambia ETag a ogni richiesta rispondeva `200` a ogni ripresa e si riscaricava sempre lo stesso prefisso. */
  @Test
  fun r13_dopoUn200InRipresaIlParzialeRiprendeConIlSoloRange() {
    behavior.cutBodyAt = TestModels.BLOCK.toLong() + 100
    serveFile()
    assertThat(fetch()).isEqualTo(FetchResult.Partial(TestModels.BLOCK.toLong()))
    // L'ETag cambia: `If-Range` non torna, `200` con tutto il file; la connessione cade dopo due blocchi.
    behavior.etag = "\"v2\""
    behavior.cutBodyAt = 2L * TestModels.BLOCK + 100
    assertThat(fetch()).isEqualTo(FetchResult.Partial(2L * TestModels.BLOCK))
    assertThat(ETags.read(partial)).isNull()
    // E il CDN cambia ETag ancora: la ripresa ora non manda `If-Range`, quindi riprende dai due blocchi invece di ripartire da zero.
    behavior.etag = "\"v3\""
    behavior.cutBodyAt = null
    assertThat(fetch()).isEqualTo(FetchResult.Done)
    assertThat(partial.readBytes()).isEqualTo(bytes)
    val last = server.requests.last()
    assertThat(last.header("Range")).isEqualTo("bytes=${2L * TestModels.BLOCK}-")
    assertThat(last.has("If-Range")).isFalse()
  }

  /** R13: una risposta arrivata dopo l'annullamento non deve aprire (e troncare) il parziale che un altro scaricamento puo' gia' usare. */
  @Test
  fun r13_annullatoInAttesaDellaRispostaNonTroncaIlParziale() {
    partial.parentFile.mkdirs()
    val two = TestModels.BLOCK * 2
    partial.writeBytes(bytes.copyOf(two))
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val stuck = HttpsConnections { url -> StuckConnection(URL(url), entered, release, bytes) }
    val slow = fetcher(connections = stuck)
    runBlocking {
      val job = launch(Dispatchers.Default) { slow.fetch(spec, source, partial, 120.seconds) }
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue()
      job.cancelAndJoin()
      // Il server risponde adesso `200` con tutto il file (ignora `Range`): nessuno aspetta piu', il lavoro vecchio non deve scrivere.
      release.countDown()
      delay(700)
    }
    assertThat(partial.readBytes()).isEqualTo(bytes.copyOf(two))
    assertThat(ETags.read(partial)).isNull()
  }

  /** Una connessione la cui risposta si sblocca solo quando lo decide il test (e che ignora `disconnect`, come una lettura bloccata sul socket). */
  private class StuckConnection(
    url: URL,
    private val entered: CountDownLatch,
    private val release: CountDownLatch,
    private val body: ByteArray,
  ) : HttpsURLConnection(url) {
    override fun getResponseCode(): Int {
      entered.countDown()
      release.await()
      return 200
    }

    override fun getHeaderField(name: String?): String? = null

    override fun getHeaderFieldLong(name: String?, default: Long): Long = if (name.equals("Content-Length", ignoreCase = true)) body.size.toLong() else default

    override fun getContentEncoding(): String? = null

    override fun getInputStream(): InputStream = ByteArrayInputStream(body)

    override fun disconnect() = Unit

    override fun usingProxy(): Boolean = false

    override fun connect() = Unit

    override fun getCipherSuite(): String = ""

    override fun getLocalCertificates(): Array<Certificate>? = null

    override fun getServerCertificates(): Array<Certificate> = emptyArray()
  }

  private companion object {
    /** Le uniche intestazioni che il client manda (piu' `Host` e `Connection`, di Java); `x-test-logical-host` e' del banco di prova. */
    val ALLOWED_HEADERS = setOf("user-agent", "accept", "accept-encoding", "host", "connection", "x-test-logical-host", "range", "if-range", "pragma", "cache-control")
  }
}

