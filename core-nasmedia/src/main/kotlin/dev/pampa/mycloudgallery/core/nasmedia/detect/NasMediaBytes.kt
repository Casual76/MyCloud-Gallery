package dev.pampa.mycloudgallery.core.nasmedia.detect

import android.media.MediaDataSource
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking

// Il rilevamento legge i file del NAS a intervalli: testa e coda da 64 KiB per l'impronta, qualche pagina in piu' per i
// metadati, mai il file intero (docs/fase4-progetto.md §2.3, "Rilevamento"). Qui l'adattatore verso core-metadata e il
// contatore dei byte che fa rispettare il tetto fuori casa (D22: 50 MB per giro).

/**
 * Il tetto dei byte letti da un giro di rilevamento (D22: fuori dal Wi-Fi di casa 50 MB, il resto aspetta). Condiviso da
 * tutti i rilevamenti del giro, anche in parallelo: una lettura si **prenota** prima di partire, quindi il totale non
 * supera mai il tetto. `limit == null` = nessun tetto (a casa).
 */
class ReadBudget(private val limit: Long? = null) {

  init {
    require(limit == null || limit >= 0) { "Tetto negativo." }
  }

  private val used = AtomicLong(0)

  /** I byte letti finora (al netto di quelli prenotati e non arrivati). */
  val taken: Long get() = used.get()

  /** Vero se il tetto e' stato raggiunto: non c'e' piu' spazio nemmeno per una lettura da un byte. */
  val exhausted: Boolean get() = limit != null && used.get() >= limit

  /** Vero se resta meno di [bytes] da leggere (con un tetto): non conviene cominciare un altro rilevamento. */
  fun remainingBelow(bytes: Long): Boolean = limit != null && limit - used.get() < bytes

  /** Prenota [bytes]; falso (senza prendere nulla) se superano il tetto. */
  fun tryReserve(bytes: Long): Boolean {
    if (limit == null) {
      used.addAndGet(bytes)
      return true
    }
    while (true) {
      val current = used.get()
      if (current + bytes > limit) return false
      if (used.compareAndSet(current, current + bytes)) return true
    }
  }

  /** Restituisce [bytes] prenotati e non arrivati (file piu' corto del previsto, lettura fallita). */
  fun refund(bytes: Long) {
    if (bytes > 0) used.addAndGet(-bytes)
  }
}

/** Il tetto del giro di rilevamento e' stato raggiunto: il file resta da rilevare al giro dopo (D22). */
class ReadCapReachedException : IOException("Tetto di lettura del giro raggiunto.")

/** Un solo file ha chiesto piu' byte di quanti ne servano a un rilevamento: quasi sempre un decodificatore che legge tutto. */
class ReadLimitExceededException : IOException("Troppi byte letti per un solo rilevamento.")

/**
 * I byte di un file del NAS per `core-metadata`: [MediaBytes] sopra un [NasRandomFile] aperto (una `CREATE`, molte
 * letture). Le regioni lette per l'impronta ([pin]) restano in memoria e le richieste che le toccano non costano un
 * secondo viaggio; il resto si legge a richiesta, e solo se stanno dentro il tetto per file ([perFileLimit]) e dentro il
 * tetto del giro ([budget]).
 *
 * Un errore del NAS durante la lettura lancia `NasIoException` (e si ricorda in [failure]): l'estrattore lo traduce in
 * "file rovinato", ma chi rileva guarda [failure], [capHit] e [limitHit] prima di crederci (un file occupato dal PC, E-N13,
 * non e' mai "corrotto"). Non chiude il file: lo chiude chi l'ha aperto.
 */
class NasMediaBytes(
  private val file: NasRandomFile,
  private val budget: ReadBudget? = null,
  private val perFileLimit: Long = Long.MAX_VALUE,
) : MediaBytes {

  private class Region(val start: Long, val bytes: ByteArray) {
    val end: Long get() = start + bytes.size
  }

  private val pinned = ArrayList<Region>(2)
  private val used = AtomicLong(0)

  /** Quanti byte sono stati davvero letti dal NAS (le regioni pinnate comprese). */
  val bytesRead: Long get() = used.get()

  @Volatile var capHit: Boolean = false
    private set

  @Volatile var limitHit: Boolean = false
    private set

  /** L'errore del NAS che ha interrotto una lettura, se c'e' stato. */
  @Volatile var failure: NasError? = null
    private set

  override val size: Long get() = file.size

  /**
   * Legge [range] dal NAS **e la tiene in memoria**: le letture successive che la toccano non tornano sul NAS. Serve a testa
   * e coda, che l'impronta ha gia' letto. Restituisce i byte (piu' corti del previsto se il file finisce prima).
   */
  suspend fun pin(range: LongRange): ByteArray {
    if (range.isEmpty()) return EMPTY
    val start = maxOf(range.first, 0L)
    val end = minOf(range.last + 1, size)
    if (start >= end) return EMPTY
    val data = fetch(start, end)
    if (data.isNotEmpty()) synchronized(pinned) { pinned += Region(start, data) }
    return data
  }

  override suspend fun read(range: LongRange): ByteArray {
    if (range.isEmpty()) return EMPTY
    val start = maxOf(range.first, 0L)
    val end = minOf(range.last + 1, size)
    if (start >= end) return EMPTY
    val total = end - start
    if (total > MAX_SINGLE_READ) {
      limitHit = true
      throw ReadLimitExceededException()
    }
    val out = ByteArray(total.toInt())
    var pos = start
    while (pos < end) {
      val region = pinnedAt(pos)
      if (region != null) {
        val n = minOf(end, region.end) - pos
        System.arraycopy(region.bytes, (pos - region.start).toInt(), out, (pos - start).toInt(), n.toInt())
        pos += n
        continue
      }
      val chunkEnd = minOf(end, nextPinnedStart(pos) ?: end)
      val data = fetch(pos, chunkEnd)
      System.arraycopy(data, 0, out, (pos - start).toInt(), data.size)
      if (data.size < chunkEnd - pos) return out.copyOf((pos - start).toInt() + data.size)
      pos = chunkEnd
    }
    return out
  }

  override fun dataSource(): MediaDataSource = object : MediaDataSource() {
    private val cache = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = this.size > PAGE_CACHE
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
      val total = this@NasMediaBytes.size
      if (position < 0 || position >= total || size <= 0) return -1
      val wanted = minOf(size.toLong(), total - position).toInt()
      return runBlocking {
        var done = 0
        while (done < wanted) {
          val at = position + done
          val index = at / PAGE
          val page = synchronized(cache) { cache[index] } ?: run {
            val from = index * PAGE
            val loaded = read(from until minOf(total, from + PAGE))
            if (loaded.isNotEmpty()) synchronized(cache) { cache[index] = loaded }
            loaded
          }
          val inPage = (at - index * PAGE).toInt()
          if (inPage >= page.size) break
          val n = minOf(page.size - inPage, wanted - done)
          System.arraycopy(page, inPage, buffer, offset + done, n)
          done += n
        }
        if (done == 0) -1 else done
      }
    }

    override fun getSize(): Long = this@NasMediaBytes.size

    override fun close() = Unit
  }

  private fun pinnedAt(pos: Long): Region? = synchronized(pinned) { pinned.firstOrNull { pos >= it.start && pos < it.end } }

  private fun nextPinnedStart(pos: Long): Long? = synchronized(pinned) { pinned.filter { it.start > pos }.minOfOrNull { it.start } }

  /** Una lettura dal NAS di `[from, to)`, prenotata prima di partire e restituita per la parte che non arriva. */
  private suspend fun fetch(from: Long, to: Long): ByteArray {
    val wanted = to - from
    if (used.get() + wanted > perFileLimit) {
      limitHit = true
      throw ReadLimitExceededException()
    }
    if (budget != null && !budget.tryReserve(wanted)) {
      capHit = true
      throw ReadCapReachedException()
    }
    val data = try {
      file.readRange(from until to)
    } catch (e: NasIoException) {
      failure = e.error
      budget?.refund(wanted)
      throw e
    } catch (e: Throwable) {
      budget?.refund(wanted)
      throw e
    }
    if (data.size < wanted) budget?.refund(wanted - data.size)
    used.addAndGet(data.size.toLong())
    return data
  }

  companion object {
    /** Come le pagine di `PagedAccess` di core-metadata: una testa o una coda cadono su pagine intere. */
    const val PAGE: Long = 64L * 1024

    private const val PAGE_CACHE = 16

    /** Nessuna richiesta singola dell'estrattore deve riempire la memoria: oltre, e' un decodificatore che legge tutto. */
    const val MAX_SINGLE_READ: Long = 8L * 1024 * 1024

    private val EMPTY = ByteArray(0)
  }
}
