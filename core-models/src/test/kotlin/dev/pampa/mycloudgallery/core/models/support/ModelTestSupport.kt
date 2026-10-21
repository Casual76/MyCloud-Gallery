package dev.pampa.mycloudgallery.core.models.support

import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.contract.ModelTraffic
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

// I mattoni dei test dei modelli (Fase 6, P3): byte di prova, specifiche coerenti con quei byte, cartelle temporanee, un contatore
// di traffico e un file servito via HTTP con la semantica di `Range` / `If-Range` e i suoi guasti.

object TestModels {
  /** Poco piu' di 3 MiB: quattro blocchi da 1 MiB, l'ultimo a meta' (la ripresa si prova a ogni confine). */
  const val SIZE: Int = 3 * 1024 * 1024 + 12_345
  const val BLOCK: Int = 1 shl 20

  fun bytes(size: Int = SIZE, seed: Long = 1): ByteArray = Random(seed).nextBytes(size)

  /** Una specifica del catalogo con la dimensione e l'impronta di [bytes] (stessi indirizzi HTTPS del 270M). */
  fun spec(bytes: ByteArray, base: ModelSpec = ModelCatalog.QUERY_270M): ModelSpec =
    base.copy(sizeBytes = bytes.size.toLong(), sha256 = Sha256.of(bytes))

  /** Una cartella temporanea che sparisce con il test. */
  fun tempDir(name: String = "mcg-models"): File = Files.createTempDirectory(name).toFile().apply { deleteOnExit() }

  /** Gli stessi byte con UN solo byte cambiato in [position]. */
  fun flipped(bytes: ByteArray, position: Int): ByteArray = bytes.copyOf().also { it[position] = (it[position].toInt() xor 0x01).toByte() }
}

/** Un [ModelTraffic] che somma i byte e conta le registrazioni. */
class TrafficCounter : ModelTraffic {
  private val sum = AtomicLong()
  private val count = AtomicLong()

  val total: Long get() = sum.get()
  val records: Long get() = count.get()

  override suspend fun record(bytes: Long) {
    sum.addAndGet(bytes)
    count.incrementAndGet()
  }
}

/** Come si comporta il file servito dal server di prova. Le proprieta' si cambiano fra una richiesta e l'altra. */
class FileBehavior(
  val bytes: ByteArray,
  @Volatile var etag: String? = "\"v1\"",
  /** Falso = ignora `Range` e risponde sempre `200` con tutto il file. */
  @Volatile var honorRange: Boolean = true,
  /** Falso = ignora `If-Range` (risponde `206` anche se il validatore non torna). */
  @Volatile var honorIfRange: Boolean = true,
  /** Se non null, scrive soltanto questi byte di corpo e poi cade la connessione (corpo troncato). */
  @Volatile var cutBodyAt: Long? = null,
  /** Vero = risposta a pezzi (`Transfer-Encoding: chunked`), senza `Content-Length`. */
  @Volatile var chunked: Boolean = false,
  /** Byte in piu' in coda al corpo (solo con [chunked]: un server che manda piu' del dovuto). */
  @Volatile var extraTail: Int = 0,
  /** Con [extraTail]: il corpo e la coda partono con UNA sola scrittura (nessun confine di pezzo a fermare chi legge). */
  @Volatile var extraInSameWrite: Boolean = false,
  @Volatile var contentEncoding: String? = null,
  /** Per `206`: cosa scrivere in `Content-Range` al posto del valore giusto, dato (inizio, fine, totale). */
  @Volatile var contentRange: ((Long, Long, Long) -> String)? = null,
  @Volatile var extraHeaders: Map<String, String> = emptyMap(),
)

private val RANGE = Regex("^bytes=(\\d+)-$")

/** Serve [behavior] a questa richiesta: `200` intero, o `206` dal punto chiesto se `Range` (e `If-Range`) lo permettono. */
fun TestExchange.serve(behavior: FileBehavior) {
  val total = behavior.bytes.size.toLong()
  val range = requestHeaders.getFirst("Range")?.let { RANGE.matchEntire(it.trim())?.groupValues?.get(1)?.toLongOrNull() }
  val validator = requestHeaders.getFirst("If-Range")
  val validatorOk = !behavior.honorIfRange || validator == null || validator == behavior.etag
  val partial = behavior.honorRange && range != null && validatorOk
  if (range != null && behavior.honorRange && range >= total) {
    responseHeaders.add("Content-Range", "bytes */$total")
    sendResponseHeaders(416, -1)
    return
  }
  val from = if (partial) range!! else 0L
  behavior.etag?.let { responseHeaders.add("ETag", it) }
  behavior.contentEncoding?.let { responseHeaders.add("Content-Encoding", it) }
  responseHeaders.add("Accept-Ranges", "bytes")
  behavior.extraHeaders.forEach { (k, v) -> responseHeaders.add(k, v) }
  val length = total - from
  if (partial) {
    val value = behavior.contentRange?.invoke(from, total - 1, total) ?: "bytes $from-${total - 1}/$total"
    responseHeaders.add("Content-Range", value)
  }
  val status = if (partial) 206 else 200
  if (behavior.chunked) sendResponseHeaders(status, 0) else sendResponseHeaders(status, length)
  val limit = behavior.cutBodyAt?.coerceAtMost(length) ?: length
  try {
    val tail = if (behavior.chunked && behavior.extraTail > 0 && behavior.cutBodyAt == null) ByteArray(behavior.extraTail) { 7 } else ByteArray(0)
    if (behavior.extraInSameWrite) {
      responseBody.write(behavior.bytes.copyOfRange(from.toInt(), from.toInt() + limit.toInt()) + tail)
    } else {
      responseBody.write(behavior.bytes, from.toInt(), limit.toInt())
      if (tail.isNotEmpty()) responseBody.write(tail)
    }
    responseBody.flush()
  } catch (e: IOException) {
    // Il client ha chiuso: va bene.
  }
  if (behavior.cutBodyAt != null && limit < length) {
    // Corpo dichiarato piu' lungo di quello scritto: chiudere fa cadere la connessione (il client vede un'interruzione).
    close()
  }
}
