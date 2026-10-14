package dev.pampa.mycloudgallery.core.index.replica.pack

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * I PACCHI della replica (D15): file in `noBackupFilesDir/replica/packs/`, mai uno per miniatura.
 *
 * - **Segmenti** ([writeImmutable]): il segmento d'immagini scaricato cosi' com'e', scritto su un temporaneo e rinominato
 *   in modo atomico; il nome e' l'impronta del segmento (base32 di Crockford: sicuro su ogni filesystem). Immutabile:
 *   una ripetizione dopo un crash trova lo stesso file.
 * - **Propri** ([append]): le immagini preparate qui, in sola aggiunta; la lunghezza valida la tiene il database, quindi
 *   i byte scritti prima di un crash e mai registrati si tagliano alla prossima aggiunta.
 * - **Lettura** ([slice]): mappatura in memoria in sola lettura, una per pacco (rifatta solo se un pacco proprio e'
 *   cresciuto), e finestre senza copie (`ByteBuffer` diretto, nessun array sul heap).
 *
 * Su Android un file mappato si cancella subito (Linux); dove il sistema lo impedisce (Windows nei test JVM) la
 * cancellazione si riprova a [sweep].
 */
class PackDirectory(val root: File) {

  private class Mapping(val buffer: MappedByteBuffer, val length: Long)

  private val mappings = ConcurrentHashMap<String, Mapping>()
  private val appendLocks = ConcurrentHashMap<String, Any>()
  private val pendingDeletes = ConcurrentLinkedQueue<String>()

  /** Quante mappature sono state create (per provare che le letture non rimappano ne' copiano). */
  val mapCount: AtomicInteger = AtomicInteger(0)

  /** Il file di un pacco: solo nomi scritti dall'app o impronte base32, mai un percorso. */
  fun fileOf(name: String): File {
    require(NAME.matches(name)) { "Nome di pacco non valido." }
    return File(root, name)
  }

  /** Scrive un pacco immutabile; idempotente se il file c'e' gia' con la stessa lunghezza. */
  fun writeImmutable(name: String, bytes: ByteArray) {
    val target = fileOf(name)
    if (target.isFile && target.length() == bytes.size.toLong()) return
    ensureRoot()
    val temp = File(root, "$TEMP_PREFIX$name")
    RandomAccessFile(temp, "rw").use { raf ->
      raf.setLength(0)
      raf.write(bytes)
      raf.fd.sync()
    }
    try {
      Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }

  /**
   * Aggiunge [bytes] in coda al pacco proprio [name] la cui lunghezza valida (dal database) e' [validLength]; i byte oltre
   * (un crash fra scrittura e registrazione) si sovrascrivono. Restituisce l'offset dei byte aggiunti.
   */
  fun append(name: String, validLength: Long, bytes: ByteArray): Long {
    val file = fileOf(name)
    ensureRoot()
    synchronized(appendLocks.getOrPut(name) { Any() }) {
      RandomAccessFile(file, "rw").use { raf ->
        raf.seek(validLength)
        raf.write(bytes)
        raf.fd.sync()
      }
    }
    return validLength
  }

  /**
   * La finestra [offset], [length] del pacco [name], in sola lettura e senza copie. Lancia [IOException] se il pacco non
   * c'e' piu' (replica azzerata nel frattempo).
   */
  fun slice(name: String, offset: Long, length: Int): ByteBuffer {
    require(offset >= 0 && length > 0) { "Finestra non valida." }
    val end = offset + length
    val mapping = mapping(name, end)
    if (end > mapping.length) throw IOException("finestra oltre il pacco")
    val window = mapping.buffer.duplicate()
    window.position(offset.toInt())
    window.limit(end.toInt())
    return window.slice()
  }

  /** Dimentica la mappatura e cancella il file (o lo rimanda a [sweep]). */
  fun delete(name: String) {
    mappings.remove(name)
    val file = fileOf(name)
    if (file.exists() && !file.delete()) pendingDeletes += name
  }

  /**
   * All'avvio: cancella i temporanei, i pacchi che il database non conosce piu' ([known]) e le cancellazioni rimandate.
   */
  fun sweep(known: Set<String>) {
    val files = root.listFiles() ?: return
    for (file in files) {
      val name = file.name
      val stray = name.startsWith(TEMP_PREFIX) || (NAME.matches(name) && name !in known)
      if (stray && mappings[name] == null) {
        if (!file.delete()) GalleryLog.w(TAG) { "Pacco non cancellabile, si riprova al prossimo avvio." }
      }
    }
    repeat(pendingDeletes.size) {
      val name = pendingDeletes.poll() ?: return@repeat
      if (name !in known) {
        val file = File(root, name)
        if (file.exists() && !file.delete()) pendingDeletes += name
      }
    }
  }

  /** I byte dei pacchi su disco (lo spazio vero, per la Diagnostica). */
  fun bytesOnDisk(): Long = root.listFiles()?.filter { NAME.matches(it.name) }?.sumOf { it.length() } ?: 0L

  private fun mapping(name: String, end: Long): Mapping {
    mappings[name]?.let { if (it.length >= end) return it }
    synchronized(appendLocks.getOrPut(name) { Any() }) {
      mappings[name]?.let { if (it.length >= end) return it }
      val file = fileOf(name)
      if (!file.isFile) throw IOException("pacco assente")
      val mapping = FileChannel.open(file.toPath()).use { channel ->
        val size = channel.size()
        if (size > Int.MAX_VALUE) throw IOException("pacco troppo grande")
        Mapping(channel.map(FileChannel.MapMode.READ_ONLY, 0, size), size)
      }
      mapCount.incrementAndGet()
      mappings[name] = mapping
      return mapping
    }
  }

  private fun ensureRoot() {
    if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) throw IOException("cartella dei pacchi non creabile")
  }

  private companion object {
    const val TAG = "ReplicaPacks"
    const val TEMP_PREFIX = "tmp-"
    val NAME = Regex("^[0-9a-z][0-9a-z-]{0,79}$")
  }
}
