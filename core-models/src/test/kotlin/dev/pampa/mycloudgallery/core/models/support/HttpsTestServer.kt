package dev.pampa.mycloudgallery.core.models.support

import dev.pampa.mycloudgallery.core.models.download.https.HttpsConnections
import java.io.Closeable
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

// Il SERVER HTTPS DI PROVA (docs/fase6-progetto.md §5.1, P3): `com.sun.net.httpserver.HttpsServer` del JDK con un certificato
// generato QUI, al volo, con il `keytool` del JDK che esegue i test, in una cartella temporanea. Il certificato e la sua chiave non
// stanno nel repository (check.ps1 vieta .p12/.jks/.pem) e sono fidati SOLO da `HttpsTestServer.clientContext`, che il codice di
// produzione non vede: `HttpsModelFetcher` usa la fiducia di sistema e la sostituzione la fa solo `TestConnections`, internal.

/** Un certificato autofirmato per `localhost` / `127.0.0.1`, valido pochi giorni, generato una volta per processo di test. */
object TestCertificate {
  private const val PASSWORD = "prova-solo-test"

  private val keyStore: KeyStore by lazy {
    val dir = Files.createTempDirectory("mcg-test-cert").toFile().apply { deleteOnExit() }
    val file = File(dir, "test.p12").apply { deleteOnExit() }
    val keytool = File(System.getProperty("java.home"), "bin/" + (if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool"))
    check(keytool.isFile) { "keytool non trovato in ${keytool.path}: i test HTTPS lo usano per generare il certificato di prova." }
    val process = ProcessBuilder(
      keytool.path, "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3",
      "-storetype", "PKCS12", "-keystore", file.path, "-storepass", PASSWORD, "-keypass", PASSWORD,
      "-dname", "CN=localhost", "-ext", "san=dns:localhost,ip:127.0.0.1",
    ).redirectErrorStream(true).start()
    val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
    check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "keytool e' fallito: $output" }
    KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, PASSWORD.toCharArray()) } }
  }

  /** Il contesto del SERVER (la chiave) e quello del CLIENT (si fida solo di questo certificato). */
  val serverContext: SSLContext by lazy {
    val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, PASSWORD.toCharArray()) }
    SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
  }

  val clientContext: SSLContext by lazy {
    val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
    SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
  }
}

/** Una richiesta arrivata al server di prova, per ispezionarla (nessun cookie, nessun parametro, intestazioni). */
class RecordedRequest(
  val method: String,
  val path: String,
  val rawQuery: String?,
  val headers: Map<String, List<String>>,
  /** L'host "logico" (quello dell'indirizzo dell'app, prima che `TestConnections` lo mappi su localhost). */
  val logicalHost: String?,
) {
  fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

  fun has(name: String): Boolean = headers.keys.any { it.equals(name, ignoreCase = true) }

  /** Tutte le intestazioni, in minuscolo, senza i valori (per verificare cosa il client manda). */
  val headerNames: Set<String> get() = headers.keys.map { it.lowercase() }.toSet()

  override fun toString(): String = "$method $path${rawQuery?.let { "?$it" } ?: ""}"
}

/** Il server: una funzione [handler] decide la risposta di ogni richiesta; [requests] le ricorda tutte, in ordine. */
class HttpsTestServer : Closeable {
  val requests: MutableList<RecordedRequest> = CopyOnWriteArrayList()

  @Volatile var handler: (TestExchange, RecordedRequest) -> Unit = { exchange, _ ->
    exchange.sendResponseHeaders(404, -1)
  }

  private val server = JdkHttpsServer(TestCertificate.serverContext) { exchange ->
    val recorded = RecordedRequest(
      method = exchange.requestMethod,
      path = exchange.requestURI.rawPath,
      rawQuery = exchange.requestURI.rawQuery,
      headers = exchange.requestHeaders.asMap(),
      logicalHost = exchange.requestHeaders.getFirst(TestConnections.LOGICAL_HOST_HEADER),
    )
    requests += recorded
    handler(exchange, recorded)
  }

  val port: Int get() = server.port

  override fun close() {
    server.close()
  }
}

/**
 * Le connessioni dei test: l'indirizzo "logico" (`https://huggingface.co/...`, quello che l'app userebbe) diventa
 * `https://localhost:<porta>/...` sul server di prova, con la fiducia nel solo certificato di prova. Registra gli indirizzi chiesti:
 * un host non ammesso non deve mai arrivarci.
 */
internal class TestConnections(private val server: HttpsTestServer) : HttpsConnections {
  val opened: MutableList<String> = CopyOnWriteArrayList()

  override fun open(url: String): HttpsURLConnection {
    opened += url
    val uri = URI(url)
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    val real = URL("https", "localhost", server.port, uri.rawPath.orEmpty() + query)
    val connection = real.openConnection() as HttpsURLConnection
    connection.sslSocketFactory = TestCertificate.clientContext.socketFactory
    connection.setRequestProperty(LOGICAL_HOST_HEADER, uri.host)
    return connection
  }

  companion object {
    const val LOGICAL_HOST_HEADER = "X-Test-Logical-Host"
  }
}
