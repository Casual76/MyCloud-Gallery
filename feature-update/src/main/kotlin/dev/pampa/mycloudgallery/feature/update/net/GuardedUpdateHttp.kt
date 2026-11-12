package dev.pampa.mycloudgallery.feature.update.net

import dev.antigravity.fluidengine.net.EngineHttp
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// IL CLIENT DELL'AGGIORNAMENTO (pacchetto U, 12/11/2026; docs/sicurezza.md C482, C483; la TERZA eccezione di rete dichiarata, dopo i modelli e la mappa).
//
// `EngineHttp` dell'engine (3.0.4) segue i rimandi di `download` a mano verso QUALUNQUE host (fino a 5) e quelli di `readText` con
// `HttpURLConnection`, senza un elenco: non si puo' vincolare senza toccare l'engine, e l'engine non si tocca. Ma la classe e' `open` e i due metodi
// pure: questa sottoclasse li RISCRIVE entrambi (nessuna chiamata a `super`), e l'engine (EngineAppUpdater, AndroidAppUpdateInstaller) la usa senza
// saperlo. Le regole:
//  - solo `https`, solo gli host dell'elenco chiuso a OGNI salto ([HostGate]): l'elenco ha i suoi host, lo scaricamento i suoi;
//  - rimandi seguiti a mano, al piu' `maxRedirects`; un rimando fuori elenco ferma tutto;
//  - nessun cookie (se qualcuno installasse un gestore globale dei cookie, si rifiuta di partire), nessuna cache, nessun header dell'app: lo
//    User-Agent generico (`MyCloudGallery/<versione>`) e `Accept`; gli header che l'engine passerebbe si ignorano;
//  - l'elenco si legge al piu' `maxManifestBytes`;
//  - un APK si scarica solo con un peso dichiarato fra 1 byte e `maxDownloadBytes`: si smette appena i byte superano il peso (un server che manda di
//    piu' non riempie la memoria), e alla fine devono essere esattamente quelli; `.part` poi rinomina, come l'engine;
//  - l'annullamento si guarda a ogni blocco letto (nell'engine no).
// Tutto `internal` (Phase6ArchitectureTest): da fuori si ottiene solo con `UpdateHttp.create`.

/** Perche' il client ha rifiutato una richiesta (prove e Diagnostica; all'utente arriva solo «Download non riuscito» o «Non riesco a controllare»). */
internal enum class UpdateNetRefusal {
  HOST,
  REDIRECT_LIMIT,
  HTTP_STATUS,
  TOO_LARGE,
  SIZE,
  COOKIE_HANDLER,
  NOT_HTTPS,
}

internal class UpdateNetException(val refusal: UpdateNetRefusal, message: String) : IOException(message)

internal class GuardedUpdateHttp(
  private val agent: String,
  private val manifestHosts: Set<String>,
  private val downloadHosts: Set<String>,
  private val maxManifestBytes: Int,
  private val maxDownloadBytes: Long,
  private val maxRedirects: Int,
  /** Apre la connessione (senza connettersi); le prove ne passano una finta. */
  private val opener: (String) -> HttpURLConnection = ::openHttps,
  /** C'e' un gestore globale dei cookie? Allora non si parte. */
  private val cookieHandlerInstalled: () -> Boolean = { CookieHandler.getDefault() != null },
  private val io: CoroutineDispatcher = Dispatchers.IO,
) : EngineHttp(userAgent = agent) {

  override suspend fun readText(url: String, headers: Map<String, String>): String = withContext(io) {
    refuseCookies()
    val connection = follow(url, manifestHosts, accept = "application/json", identity = false)
    try {
      connection.inputStream.use { readCapped(it, maxManifestBytes) }.toString(Charsets.UTF_8)
    } finally {
      connection.disconnect()
    }
  }

  override suspend fun download(
    url: String,
    target: File,
    expectedBytes: Long,
    headers: Map<String, String>,
    onProgress: (progress: Float, downloaded: Long, total: Long) -> Unit,
  ): File = withContext(io) {
    refuseCookies()
    if (expectedBytes <= 0L || expectedBytes > maxDownloadBytes) {
      throw UpdateNetException(UpdateNetRefusal.SIZE, "Peso dell'aggiornamento non ammesso.")
    }
    target.parentFile?.mkdirs()
    val partial = File(target.parentFile, "${target.name}.part")
    partial.delete()
    var connection: HttpURLConnection? = null
    try {
      connection = follow(url, downloadHosts, accept = "application/octet-stream", identity = true)
      val declared = connection.contentLengthLong
      if (declared >= 0 && declared != expectedBytes) {
        throw UpdateNetException(UpdateNetRefusal.SIZE, "Il file annunciato pesa $declared byte invece di $expectedBytes.")
      }
      var downloaded = 0L
      connection.inputStream.use { input ->
        FileOutputStream(partial).use { output ->
          val buffer = ByteArray(BUFFER_BYTES)
          while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read == -1) break
            downloaded += read
            if (downloaded > expectedBytes) throw UpdateNetException(UpdateNetRefusal.TOO_LARGE, "Il file supera il peso annunciato.")
            output.write(buffer, 0, read)
            onProgress((downloaded.toFloat() / expectedBytes.toFloat()).coerceIn(0f, 1f), downloaded, expectedBytes)
          }
          output.fd.sync()
        }
      }
      if (downloaded != expectedBytes) {
        throw UpdateNetException(UpdateNetRefusal.SIZE, "File incompleto: $downloaded byte invece di $expectedBytes.")
      }
      if (target.exists()) target.delete()
      if (!partial.renameTo(target)) throw IOException("Impossibile finalizzare il file scaricato.")
      target
    } finally {
      connection?.disconnect()
      if (partial.exists()) partial.delete()
    }
  }

  private fun refuseCookies() {
    if (cookieHandlerInstalled()) throw UpdateNetException(UpdateNetRefusal.COOKIE_HANDLER, "Gestore dei cookie installato: richiesta rifiutata.")
  }

  /** La risposta 2xx di [first] seguendo a mano i rimandi, ogni salto dentro [hosts]; chi chiama la chiude. */
  private suspend fun follow(first: String, hosts: Set<String>, accept: String, identity: Boolean): HttpURLConnection {
    var current = HostGate.admit(first, hosts) ?: throw UpdateNetException(UpdateNetRefusal.HOST, "Indirizzo non ammesso.")
    var hops = 0
    while (true) {
      coroutineContext.ensureActive()
      val connection = opener(current)
      try {
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.defaultUseCaches = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", agent)
        connection.setRequestProperty("Accept", accept)
        // Lo scaricamento senza compressione: i byte dichiarati e quelli letti devono essere gli stessi dell'APK pubblicato.
        if (identity) connection.setRequestProperty("Accept-Encoding", "identity")
        val code = connection.responseCode
        if (code in 300..399) {
          val location = connection.getHeaderField("Location")
          connection.disconnect()
          if (++hops > maxRedirects) throw UpdateNetException(UpdateNetRefusal.REDIRECT_LIMIT, "Troppi rimandi.")
          current = HostGate.redirect(current, location, hosts)
            ?: throw UpdateNetException(UpdateNetRefusal.HOST, "Rimando verso un indirizzo non ammesso.")
          continue
        }
        if (code !in 200..299) {
          connection.disconnect()
          throw UpdateNetException(UpdateNetRefusal.HTTP_STATUS, "Richiesta non riuscita ($code).")
        }
        return connection
      } catch (e: IOException) {
        connection.disconnect()
        throw e
      }
    }
  }

  /** Legge al piu' [max] byte; oltre, smette e rifiuta. */
  private fun readCapped(input: InputStream, max: Int): ByteArray {
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(BUFFER_BYTES)
    var total = 0
    while (true) {
      val read = input.read(chunk)
      if (read == -1) break
      total += read
      if (total > max) throw UpdateNetException(UpdateNetRefusal.TOO_LARGE, "Elenco delle versioni troppo grande.")
      buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
  }

  companion object {
    private const val BUFFER_BYTES = 64 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    /** La connessione vera: solo HTTPS della piattaforma (fiducia di sistema; il traffico in chiaro e' comunque vietato dal manifest). */
    private fun openHttps(url: String): HttpURLConnection =
      URL(url).openConnection() as? HttpsURLConnection ?: throw UpdateNetException(UpdateNetRefusal.NOT_HTTPS, "Non e' una connessione HTTPS.")
  }
}
