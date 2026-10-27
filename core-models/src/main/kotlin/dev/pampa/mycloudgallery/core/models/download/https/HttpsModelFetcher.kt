package dev.pampa.mycloudgallery.core.models.download.https

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.models.contract.FetchRefusal
import dev.pampa.mycloudgallery.core.models.contract.FetchResult
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.models.contract.ModelHosts
import dev.pampa.mycloudgallery.core.models.contract.ModelHttpsRoute
import dev.pampa.mycloudgallery.core.models.contract.ModelLimits
import dev.pampa.mycloudgallery.core.models.contract.ModelSource
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.contract.ModelTraffic
import dev.pampa.mycloudgallery.core.models.download.PartialFile
import java.io.File
import java.io.IOException
import java.net.CookieHandler
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// Lo SCARICAMENTO HTTPS dei modelli (docs/fase6-progetto.md §2.1 "HTTPS", D17, D33, E-A1, E-S16). L'UNICO codice HTTPS dell'app.
//
// Cosa fa, e cosa non fa mai:
//  - `HttpsURLConnection` della piattaforma, fiducia di sistema (nessun pinning: gli host dei CDN cambiano; l'integrita' la da'
//    l'impronta del catalogo, che il GESTORE verifica sul file finito; qui si controllano solo le lunghezze);
//  - solo `https`, solo host ammessi ([ModelHosts]) a OGNI salto; redirezioni seguite a mano, al piu' [ModelHosts.MAX_REDIRECTS]
//    ([RedirectPolicy]);
//  - nessun cookie (se un gestore globale dei cookie esiste non si parte), nessuna credenziale, nessun parametro aggiunto da noi,
//    nessuna cache HTTP, nessuna compressione (`Accept-Encoding: identity`: i conti dei byte devono tornare), `User-Agent` fisso;
//  - ripresa a blocchi da 1 MiB: `Range: bytes=<n>-` piu' `If-Range` con l'ETag forte salvato; un `200` al posto di `206` riparte
//    da zero; un `206` che non comincia dove si e' chiesto o che non torna con la dimensione fissata si rifiuta;
//  - mai piu' byte della dimensione fissata: una lunghezza dichiarata diversa si rifiuta prima di leggere, un flusso piu' lungo si
//    ferma e si scarta ([FetchResult.Mismatch]);
//  - scadenze di 15 s per la connessione e 30 s per ogni lettura; il tempo del chiamante si rispetta ai confini di blocco;
//  - traffico registrato a ogni blocco; annullamento = chiusura della connessione, e `fetch` **aspetta** (non annullabile, con un tetto di un
//    secondo, [ABORT_WAIT_MS]) che il lavoro staccato finisca: un secondo scaricamento che parte subito dopo trova il parziale fermo, non un lavoro
//    vecchio che lo tronca (R13). Nessuna scrittura (ne' del parziale ne' del validatore) dopo un annullamento;
//  - un `200` al posto di `206` in ripresa (il CDN cambia ETag a ogni richiesta, `If-Range` non torna) riparte da zero **una volta** e da
//    quel momento il parziale riprende con il solo `Range`, senza `If-Range` (R13): l'integrita' la da' l'impronta finale.
// Il gestore (non questa classe) decide la sorgente, il cancello di rete, lo spazio, l'impronta e la rinomina in `models/<sha256>`.

internal class HttpsModelFetcher(
  private val traffic: ModelTraffic,
  private val connections: HttpsConnections = PlatformConnections,
  private val io: CoroutineDispatcher = Dispatchers.IO,
  private val nanos: () -> Long = System::nanoTime,
  private val connectTimeoutMs: Int = ModelLimits.CONNECT_TIMEOUT_MS,
  private val readTimeoutMs: Int = ModelLimits.READ_TIMEOUT_MS,
  /**
   * R12: la rete su cui escono le connessioni (quella approvata dal cancello), riletta a ogni connessione, cioe' a ogni fetta. Senza una rete
   * approvata la connessione non si apre e **non** si ripiega sulla rete predefinita del sistema (che potrebbe essere quella dei dati mobili).
   * Null (i test, un telefono senza il registro di rete): la rete predefinita, com'era.
   */
  private val route: ModelHttpsRoute? = null,
) : ModelFetcher {

  /**
   * La connessione in corso. Una lettura bloccata sul socket non si interrompe da un'altra coroutine, e chiudere la connessione puo'
   * aspettare quella lettura (su alcune JVM un `SSLSocket` si chiude solo quando chi legge ritorna): per questo la chiusura parte su un
   * altro thread e chi ha annullato non l'aspetta.
   */
  private inner class Attempt {
    @Volatile var connection: HttpsURLConnection? = null

    fun abort() {
      val current = connection ?: return
      detached.launch {
        try {
          current.disconnect()
        } catch (e: RuntimeException) {
          // Chiusa o gia' persa: non importa.
        }
      }
    }
  }

  /** Dove gira il lavoro bloccante: fuori dalla coroutine del chiamante, cosi' un annullamento ritorna subito (il lavoro finisce da solo). */
  private val detached = CoroutineScope(SupervisorJob() + io)

  override suspend fun fetch(spec: ModelSpec, source: ModelSource, partial: File, budget: Duration): FetchResult {
    val https = source as? ModelSource.Https ?: return FetchResult.Refused(FetchRefusal.SOURCE_KIND)
    // I cookie non si usano mai: HttpURLConnection li userebbe se qualcuno installasse un gestore globale. Si parte solo senza.
    if (CookieHandler.getDefault() != null) return FetchResult.Refused(FetchRefusal.COOKIE_HANDLER)
    val attempt = Attempt()
    val start = nanos()
    val budgetNanos = if (budget.isInfinite()) Long.MAX_VALUE else budget.inWholeNanoseconds.coerceAtLeast(0)
    val outcome = CompletableDeferred<FetchResult>()
    val worker = detached.launch {
      try {
        outcome.complete(transfer(spec, https.url.value, partial, start, budgetNanos, attempt))
      } catch (e: CancellationException) {
        outcome.cancel(e)
      } catch (t: Throwable) {
        outcome.completeExceptionally(t)
      }
    }
    try {
      return outcome.await()
    } catch (c: CancellationException) {
      // Chi ha chiesto ha rinunciato: il lavoro si ferma al prossimo controllo (non scrive piu' niente) e la connessione si chiude.
      worker.cancel()
      attempt.abort()
      // Il lavoro staccato si ferma entro la scadenza della lettura, ma qui non si aspetta cosi' a lungo: la connessione e' appena stata
      // chiusa e di norma la lettura bloccata ritorna subito. Aspettiamo anche se siamo stati annullati (R13).
      withContext(NonCancellable) { withTimeoutOrNull(ABORT_WAIT_MS) { worker.join() } }
      throw c
    }
  }

  private suspend fun transfer(spec: ModelSpec, firstUrl: String, partial: File, start: Long, budgetNanos: Long, attempt: Attempt): FetchResult {
    val total = spec.sizeBytes
    var offset = PartialFile.resumePoint(partial, total)
    if (offset == total) return FetchResult.Done
    var url = firstUrl
    var hops = 0
    var rangeRefused = false
    while (true) {
      coroutineContext.ensureActive()
      if (elapsed(start) >= budgetNanos) return progress(partial, total)
      val connection = try {
        openConnection(url)
      } catch (e: IOException) {
        return progress(partial, total)
      }
      attempt.connection = connection
      try {
        prepare(connection, offset, if (offset > 0) ETags.read(partial) else null)
        val status = connection.responseCode
        // Annullati mentre si aspettava la risposta: niente di quello che segue (validatore, apertura e troncamento del parziale) deve avvenire,
        // un altro scaricamento potrebbe gia' usare il parziale (R13).
        coroutineContext.ensureActive()
        when {
          status in REDIRECTS -> {
            when (val decision = RedirectPolicy.decide(url, connection.getHeaderField("Location"))) {
              is RedirectDecision.Refuse -> return FetchResult.Refused(decision.reason)
              is RedirectDecision.Follow -> {
                if (++hops > ModelHosts.MAX_REDIRECTS) return FetchResult.Refused(FetchRefusal.REDIRECT_LIMIT)
                url = decision.url
              }
            }
          }
          status == 200 -> {
            if (connection.contentEncoding.isCompressed()) return FetchResult.Refused(FetchRefusal.ENCODING)
            val declared = connection.getHeaderFieldLong("Content-Length", -1)
            // Una lunghezza dichiarata diversa da quella fissata e' un altro file: non si legge un byte (E-S16). Il parziale non c'entra.
            if (declared >= 0 && declared != total) return FetchResult.SizeMismatch
            // Un `200` in ripresa: il validatore non torna piu' (o il server non conosce `Range`). Si riparte da zero, e da qui in poi il
            // parziale riprende col solo `Range` (R13); un `200` alla prima richiesta salva l'ETag come sempre.
            if (offset > 0) ETags.markNoValidator(partial) else ETags.save(partial, connection.getHeaderField("ETag"))
            // Il server ha ignorato `Range` (o `If-Range` non tornava): si riparte da zero (§2.1).
            return receive(connection, spec, partial, startAt = 0, start, budgetNanos)
          }
          status == 206 -> {
            if (offset == 0L) return FetchResult.Refused(FetchRefusal.CONTENT_RANGE)
            if (connection.contentEncoding.isCompressed()) return FetchResult.Refused(FetchRefusal.ENCODING)
            val range = ContentRange.parse(connection.getHeaderField("Content-Range")) ?: return FetchResult.Refused(FetchRefusal.CONTENT_RANGE)
            if (range.start != offset || range.end != total - 1) return FetchResult.Refused(FetchRefusal.CONTENT_RANGE)
            if (range.total != null && range.total != total) return FetchResult.SizeMismatch
            val declared = connection.getHeaderFieldLong("Content-Length", -1)
            if (declared >= 0 && declared != range.end - range.start + 1) return FetchResult.Refused(FetchRefusal.CONTENT_RANGE)
            ETags.save(partial, connection.getHeaderField("ETag"))
            return receive(connection, spec, partial, startAt = offset, start, budgetNanos)
          }
          status == 404 || status == 410 -> return FetchResult.NotFound
          status == 416 -> {
            // Il punto di ripresa non esiste per il server: il parziale non e' di questo file. Una volta sola, da zero.
            if (offset == 0L || rangeRefused) return FetchResult.NetworkError
            rangeRefused = true
            partial.delete()
            StateFiles.delete(ETags.sidecar(partial))
            offset = 0
            url = firstUrl
            hops = 0
          }
          status == 408 || status == 429 || status in 500..599 -> return progress(partial, total)
          else -> return FetchResult.Refused(FetchRefusal.HTTP_STATUS)
        }
      } catch (e: IOException) {
        return progress(partial, total)
      } finally {
        attempt.connection = null
        connection.disconnect()
      }
    }
  }

  /**
   * Apre (senza ancora connettersi) la connessione verso [url]. Con una [route] la apre sulla `Network` approvata (R12); il codice sta qui, in
   * questa classe e non in una sua a parte, perche' R8 fonde in un'unica classe le classi piccole e simili e il controllo sul dex di release
   * attribuirebbe la chiamata alla classe sbagliata.
   */
  @Throws(IOException::class)
  private fun openConnection(url: String): HttpsURLConnection {
    val bound = route ?: return connections.open(url)
    val network = bound.network() ?: throw IOException("Nessuna rete approvata per lo scaricamento.")
    return network.openConnection(URL(url)) as? HttpsURLConnection ?: throw IOException("Non e' una connessione HTTPS.")
  }

  private fun prepare(connection: HttpsURLConnection, offset: Long, validator: String?) {
    connection.requestMethod = "GET"
    connection.instanceFollowRedirects = false
    connection.useCaches = false
    connection.defaultUseCaches = false
    connection.doInput = true
    connection.connectTimeout = connectTimeoutMs
    connection.readTimeout = readTimeoutMs
    connection.setRequestProperty("User-Agent", ModelLimits.USER_AGENT)
    // Una connessione per fetta, mai riusata: cosi' chiuderla (annullamento) non aspetta una lettura bloccata sul socket.
    connection.setRequestProperty("Connection", "close")
    connection.setRequestProperty("Accept", "application/octet-stream")
    // Nessuna compressione: i byte dichiarati e quelli letti devono essere gli stessi del file fissato.
    connection.setRequestProperty("Accept-Encoding", "identity")
    if (offset > 0) {
      connection.setRequestProperty("Range", "bytes=$offset-")
      if (validator != null) connection.setRequestProperty("If-Range", validator)
    }
  }

  /** Legge il corpo in blocchi da 1 MiB, scrivendo e sincronizzando a ogni blocco; si ferma alla dimensione fissata, mai oltre. */
  private suspend fun receive(
    connection: HttpsURLConnection,
    spec: ModelSpec,
    partial: File,
    startAt: Long,
    start: Long,
    budgetNanos: Long,
  ): FetchResult {
    val total = spec.sizeBytes
    val expected = total - startAt
    coroutineContext.ensureActive()
    val input = try {
      connection.inputStream
    } catch (e: IOException) {
      return progress(partial, total)
    }
    val out = try {
      PartialFile.open(partial, startAt)
    } catch (e: IOException) {
      return if (PartialFile.isNoSpace(e)) FetchResult.NoSpace else FetchResult.NetworkError
    }
    out.use {
      val buffer = ByteArray(ModelLimits.BLOCK_BYTES)
      var filled = 0
      var received = 0L
      while (received < expected) {
        coroutineContext.ensureActive()
        val room = minOf((buffer.size - filled).toLong(), expected - received).toInt()
        val n = try {
          input.read(buffer, filled, room)
        } catch (e: IOException) {
          -1
        }
        if (n < 0) {
          // Chiuso prima del tempo (Wi-Fi caduto, server che muore): il blocco a meta' non entra nel file, ma il traffico e' passato.
          if (filled > 0) traffic.record(filled.toLong())
          return interrupted(out.length)
        }
        filled += n
        received += n
        if (filled == buffer.size || received == expected) {
          // Se nel frattempo e' stato annullato, un blocco arrivato tardi non si scrive: un altro scaricamento potrebbe gia' usare il parziale.
          coroutineContext.ensureActive()
          try {
            out.append(buffer, filled)
          } catch (e: IOException) {
            return if (PartialFile.isNoSpace(e)) FetchResult.NoSpace else interrupted(out.length)
          }
          traffic.record(filled.toLong())
          filled = 0
          if (received < expected && elapsed(start) >= budgetNanos) return FetchResult.Partial(out.length)
        }
      }
    }
    // Tutti i byte attesi sono arrivati: se ce n'e' ancora uno, il file e' piu' grande di quello fissato (E-S16).
    val extra = try {
      input.read()
    } catch (e: IOException) {
      -1
    }
    return if (extra >= 0) FetchResult.Mismatch else FetchResult.Done
  }

  private fun interrupted(length: Long): FetchResult = if (length > 0) FetchResult.Partial(length) else FetchResult.NetworkError

  private fun progress(partial: File, total: Long): FetchResult = interrupted(PartialFile.resumePoint(partial, total))

  private fun elapsed(start: Long): Long = nanos() - start

  private fun String?.isCompressed(): Boolean = this != null && !equals("identity", ignoreCase = true)

  private companion object {
    val REDIRECTS = setOf(301, 302, 303, 307, 308)

    /**
     * Quanto aspetta, al massimo, la fine del lavoro staccato dopo un annullamento. Poco: serve a coprire il lavoro che sta *scrivendo* (pochi
     * millisecondi), non una lettura bloccata sul socket, che prima di scrivere qualunque cosa controlla l'annullamento (`ensureActive`) e si ferma da
     * sola. Chi annulla (`remove`, "Rimuovi") non deve aspettare la scadenza di lettura.
     */
    const val ABORT_WAIT_MS: Long = 1_000
  }
}

/** L'intestazione `Content-Range` di una risposta `206`: `bytes <inizio>-<fine>/<totale|*>`, interi non negativi. */
internal class ContentRange(val start: Long, val end: Long, val total: Long?) {
  companion object {
    private val FORMAT = Regex("^bytes (\\d{1,18})-(\\d{1,18})/(\\d{1,18}|\\*)$")

    fun parse(header: String?): ContentRange? {
      val m = FORMAT.matchEntire(header?.trim() ?: return null) ?: return null
      val start = m.groupValues[1].toLongOrNull() ?: return null
      val end = m.groupValues[2].toLongOrNull() ?: return null
      val total = if (m.groupValues[3] == "*") null else m.groupValues[3].toLongOrNull() ?: return null
      if (end < start || (total != null && end >= total)) return null
      return ContentRange(start, end, total)
    }
  }
}
