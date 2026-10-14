package dev.pampa.mycloudgallery.core.prepare.video

import android.media.MediaDataSource
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.prepare.photo.OriginUnreachableException
import dev.pampa.mycloudgallery.core.prepare.photo.SourceChangedException
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * La cache di lettura a intervalli sopra un [MediaBytes] (§2.4: "letture a intervalli con cache da 4 MiB"). Un video non si
 * scarica mai: il sistema (`MediaMetadataRetriever`) chiede pochi pezzi sparsi (l'inizio, l'indice in coda, il fotogramma
 * chiave di ogni istante) e qui si leggono a **blocchi allineati** (256 KiB), tenendone gli ultimi 16 (4 MiB). Lo stesso
 * blocco non si rilegge due volte (l'indice letto per riconoscere il codec serve poi al sistema), e un salto casuale costa
 * un blocco, non un file.
 *
 * Tutte le letture passano da qui e da `MediaBytes.read`: [originBytes] e' il conto vero dei byte chiesti all'origine
 * (compresi quelli di un blocco riletto dopo essere stato espulso).
 */
internal class BlockCache(
  private val origin: MediaBytes,
  private val blockBytes: Int,
  private val maxBlocks: Int,
) {
  init {
    require(blockBytes > 0 && maxBlocks > 0) { "Cache non valida." }
  }

  val size: Long = origin.size

  private val mutex = Mutex()
  private val blocks = object : LinkedHashMap<Long, ByteArray>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = this.size > maxBlocks
  }
  private val originRead = AtomicLong()
  private val originCalls = AtomicLong()

  /** Impostato quando il lavoro e' annullato: le letture del sistema, che non sa di coroutine, falliscono subito. */
  @Volatile var cancelled: Boolean = false

  /**
   * Il primo errore di lettura dell'origine. Il sistema lo vede solo come "non riesco a leggere" e lo trasforma in un
   * fotogramma nullo: da qui il chiamante capisce che il NAS non rispondeva, non che il video era rovinato.
   */
  @Volatile var failure: IOException? = null
    private set

  /** Quanti byte si sono chiesti all'origine. */
  val originBytes: Long get() = originRead.get()

  /** Quante letture. */
  val originReads: Long get() = originCalls.get()

  /** Fino a [length] byte da [position] (meno a fine file; vuoto oltre). */
  suspend fun read(position: Long, length: Int): ByteArray = mutex.withLock { readLocked(position, length) }

  /** Per `MediaDataSource.readAt`, che il sistema chiama da un suo thread: -1 a fine file, come richiede il contratto. */
  fun readBlocking(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
    if (length == 0) return 0
    if (position >= size) return -1
    val part = runBlocking { read(position, length) }
    if (part.isEmpty()) return -1
    System.arraycopy(part, 0, buffer, offset, part.size)
    return part.size
  }

  private suspend fun readLocked(position: Long, length: Int): ByteArray {
    if (cancelled) throw IOException("Lettura annullata.")
    if (position < 0 || length <= 0 || position >= size) return ByteArray(0)
    val total = minOf(length.toLong(), size - position).toInt()
    val out = ByteArray(total)
    var done = 0
    while (done < total) {
      val at = position + done
      val index = at / blockBytes
      val block = blockAt(index)
      val inBlock = (at - index * blockBytes).toInt()
      val n = minOf(block.size - inBlock, total - done)
      if (n <= 0) throw IOException("Blocco corto.")
      System.arraycopy(block, inBlock, out, done, n)
      done += n
    }
    return out
  }

  private suspend fun blockAt(index: Long): ByteArray {
    blocks[index]?.let { return it }
    if (cancelled) throw IOException("Lettura annullata.")
    val start = index * blockBytes
    val end = minOf(start + blockBytes, size)
    val block = try {
      origin.read(start until end)
    } catch (e: IOException) {
      throw fail(OriginUnreachableException(e))
    }
    originCalls.incrementAndGet()
    originRead.addAndGet(end - start)
    // Un blocco piu' corto del previsto vuol dire che il file e' cambiato (o si e' accorciato) mentre lo si leggeva.
    if (block.size.toLong() != end - start) throw fail(SourceChangedException())
    blocks[index] = block
    return block
  }

  private fun fail(e: IOException): IOException {
    if (failure == null) failure = e
    return e
  }

  fun clear() {
    runBlocking { mutex.withLock { blocks.clear() } }
  }
}

/** `MediaDataSource` per `MediaMetadataRetriever`, sopra la cache a blocchi. Non chiude l'origine: e' di chi ha aperto il file. */
internal class BlockCacheDataSource(private val cache: BlockCache) : MediaDataSource() {
  override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
    cache.readBlocking(position, buffer, offset, size)

  override fun getSize(): Long = cache.size

  override fun close() = Unit
}
