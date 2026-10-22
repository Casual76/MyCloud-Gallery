package dev.pampa.mycloudgallery.core.ai.compute

import dev.pampa.mycloudgallery.core.ai.contract.AiBuffer
import dev.pampa.mycloudgallery.core.index.ai.AnalysisFetcher
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Il buffer di analisi degli indicizzatori sul disco (D10; docs/fase4-progetto.md §2.10): `noBackupFilesDir/ai-buffer/`, fuori dai
 * backup. Un file per (chiave di contenuto, classe), solo `analysis`, `frames` e `hq` ([AnalysisFetcher.FETCHABLE]).
 *
 * - **Tetti**: al piu' [maxBytes] (500 MB); [put] rifiuta (falso, mai un'eccezione) oltre il tetto o se sul telefono restano meno di
 *   [AiBuffer.MIN_FREE_BYTES] (1 GiB, E-A17): chi scarica si ferma, la preparazione butta in silenzio.
 * - **Morte del processo**: ogni blob si scrive in un temporaneo e si rinomina; all'apertura i temporanei orfani si tolgono e i byte
 *   si ricontano dai file veri (invariante 9 del §5.5).
 * - [evict] toglie cio' che e' calcolato, [clear] tutto (E-A17: "buffer svuotato, calcolo fermo").
 * - **Provenienza** (revisione A, R5; [AiOriginBuffer]): ogni file comincia con un'intestazione che dice da dove viene il blob (proprio, o
 *   l'anteprima di un dispositivo attestata in una share). Per una share valgono solo i blob propri o di quella share ([getFor]); la revoca
 *   di un dispositivo toglie le sue anteprime ([forgetDevice]). I file di prima, senza intestazione, si tolgono all'apertura (e' una cache).
 *
 * I nomi dei file sono la chiave in esadecimale e la classe: mai un nome scelto da fuori.
 */
class DiskAiBuffer(
  private val dir: File,
  private val freeSpace: AiFreeSpace = AiFreeSpace { dir.usableSpace },
  val maxBytes: Long = AiBuffer.MAX_BYTES,
  private val maxBlobBytes: Int = MAX_BLOB_BYTES,
) : AiBuffer, AiOriginBuffer {

  private val lock = Any()
  private val sizes = HashMap<Pair<ContentKey, SegmentClass>, Long>()
  private val origins = HashMap<Pair<ContentKey, SegmentClass>, AiInputOrigin>()
  private val used = MutableStateFlow(0L)

  override val usedBytes: StateFlow<Long> get() = used

  init {
    require(maxBytes > 0 && maxBlobBytes > 0) { "Tetti non validi." }
    synchronized(lock) { rescan() }
  }

  override suspend fun put(key: ContentKey, cls: SegmentClass, bytes: ByteArray): Boolean = putFrom(key, cls, bytes, AiInputOrigin.Own)

  override suspend fun putFrom(key: ContentKey, cls: SegmentClass, bytes: ByteArray, from: AiInputOrigin): Boolean = synchronized(lock) {
    if (cls !in AnalysisFetcher.FETCHABLE || bytes.isEmpty() || bytes.size > maxBlobBytes) return false
    val header = header(from)
    // Il tetto conta i byte dei blob (come prima); l'intestazione (poche decine di byte) conta solo per lo spazio libero.
    val total = bytes.size.toLong()
    if (freeSpace.usableBytes() - (header.size + total) < AiBuffer.MIN_FREE_BYTES) return false
    val old = sizes[key to cls] ?: 0L
    if (used.value - old + total > maxBytes) return false
    val target = fileOf(key, cls)
    val tmp = File(dir, target.name + TMP)
    try {
      dir.mkdirs()
      tmp.outputStream().use { out ->
        out.write(header)
        out.write(bytes)
        out.fd.sync()
      }
      if (!tmp.renameTo(target)) {
        // Su alcuni file system la rinomina non sostituisce: si toglie il vecchio e si riprova.
        target.delete()
        if (!tmp.renameTo(target)) throw IOException("rinomina fallita")
      }
    } catch (e: IOException) {
      tmp.delete()
      return false
    }
    sizes[key to cls] = total
    origins[key to cls] = from
    used.value = used.value - old + total
    true
  }

  /** Il blob com'e', di qualunque provenienza (contratto `AiBuffer`): chi deve distinguere usa [getFor]. */
  override suspend fun get(key: ContentKey, cls: SegmentClass): ByteArray? = read(key, cls)?.bytes

  override suspend fun getFor(key: ContentKey, cls: SegmentClass, share: String?): AiBlob? {
    if (synchronized(lock) { origins[key to cls] }?.validFor(share) != true) return null
    return read(key, cls)?.takeIf { it.from.validFor(share) }
  }

  override fun containsFor(key: ContentKey, cls: SegmentClass, share: String?): Boolean =
    synchronized(lock) { origins[key to cls]?.validFor(share) == true }

  override suspend fun forgetDevice(device: String) {
    synchronized(lock) {
      val gone = origins.filterValues { it is AiInputOrigin.Preview && (it.device == device || it.device == null) }.keys.toList()
      for ((key, cls) in gone) {
        fileOf(key, cls).delete()
        forget(key, cls)
      }
    }
  }

  private fun read(key: ContentKey, cls: SegmentClass): AiBlob? = synchronized(lock) {
    if (sizes[key to cls] == null) return null
    val bytes = try {
      fileOf(key, cls).readBytes()
    } catch (e: IOException) {
      forget(key, cls)
      return null
    }
    val parsed = parse(bytes)
    if (parsed == null) {
      fileOf(key, cls).delete()
      forget(key, cls)
    }
    parsed
  }

  override suspend fun evict(done: Collection<ContentKey>) {
    synchronized(lock) {
      val wanted = done.toHashSet()
      for ((key, cls) in sizes.keys.filter { it.first in wanted }) {
        fileOf(key, cls).delete()
        forget(key, cls)
      }
    }
  }

  /** Tutto via (E-A17). */
  fun clear() {
    synchronized(lock) {
      dir.listFiles()?.forEach { it.delete() }
      sizes.clear()
      origins.clear()
      used.value = 0L
    }
  }

  /** Le chiavi con almeno un blob (per il calcolo solo-buffer, E-A6). */
  fun keys(): Set<ContentKey> = synchronized(lock) { sizes.keys.mapTo(LinkedHashSet()) { it.first } }

  fun contains(key: ContentKey, cls: SegmentClass): Boolean = synchronized(lock) { (key to cls) in sizes }

  private fun forget(key: ContentKey, cls: SegmentClass) {
    origins.remove(key to cls)
    val size = sizes.remove(key to cls) ?: return
    used.value -= size
  }

  /** Ricostruisce lo stato dai file: via i temporanei, i nomi che non sono nostri e i file senza intestazione (di prima della revisione A). */
  private fun rescan() {
    sizes.clear()
    origins.clear()
    var total = 0L
    for (file in dir.listFiles().orEmpty()) {
      val parsed = parseName(file.name)
      val head = if (parsed != null && file.isFile && file.length() > 0L) headerOf(file) else null
      val size = head?.let { file.length() - it.second } ?: 0L
      if (parsed == null || head == null || size <= 0L) {
        file.delete()
        continue
      }
      sizes[parsed] = size
      origins[parsed] = head.first
      total += size
    }
    used.value = total
  }

  private fun fileOf(key: ContentKey, cls: SegmentClass) = File(dir, "${key.hex}.${cls.wire}")

  private fun parseName(name: String): Pair<ContentKey, SegmentClass>? {
    val dot = name.indexOf('.')
    if (dot != 64) return null
    val hex = name.substring(0, dot)
    val cls = AnalysisFetcher.FETCHABLE.firstOrNull { it.wire == name.substring(dot + 1) } ?: return null
    return runCatching { ContentKey(hex) }.getOrNull()?.let { it to cls }
  }

  override fun toString(): String = "DiskAiBuffer(${used.value} B)"

  companion object {
    private const val TMP = ".tmp"

    /** Un blob oltre questo non si tiene (i fotogrammi stanno in 2 MiB, l'HQ e' molto meno). */
    const val MAX_BLOB_BYTES: Int = 4 * 1024 * 1024

    private const val MAGIC = 0x4D434750 // "MCGP"
    private const val VERSION = 1
    private const val MAX_HEADER = 2 * 1024

    /** L'intestazione: `MCGP` · versione · tipo (0 proprio, 1 anteprima) · share · dispositivo (vuoto = non noto). */
    internal fun header(from: AiInputOrigin): ByteArray {
      val bytes = ByteArrayOutputStream(64)
      val out = DataOutputStream(bytes)
      out.writeInt(MAGIC)
      out.writeByte(VERSION)
      when (from) {
        AiInputOrigin.Own -> out.writeByte(0)
        is AiInputOrigin.Preview -> {
          out.writeByte(1)
          for (text in listOf(from.share, from.device.orEmpty())) {
            val utf = text.toByteArray(Charsets.UTF_8)
            require(utf.size <= MAX_HEADER) { "Nome troppo lungo." }
            out.writeShort(utf.size)
            out.write(utf)
          }
        }
      }
      out.flush()
      return bytes.toByteArray()
    }

    /** Il blob e la sua provenienza, o null se l'intestazione non torna. */
    internal fun parse(bytes: ByteArray): AiBlob? {
      val b = ByteBuffer.wrap(bytes)
      return runCatching {
        if (b.int != MAGIC || b.get().toInt() != VERSION) return null
        val from = when (b.get().toInt()) {
          0 -> AiInputOrigin.Own
          1 -> {
            val share = utf(b)
            val device = utf(b)
            AiInputOrigin.Preview(share, device.ifEmpty { null })
          }
          else -> return null
        }
        if (!b.hasRemaining()) return null
        AiBlob(bytes.copyOfRange(b.position(), bytes.size), from)
      }.getOrNull()
    }

    private fun utf(b: ByteBuffer): String {
      val n = b.short.toInt() and 0xffff
      require(n <= MAX_HEADER) { "Intestazione troppo lunga." }
      return ByteArray(n).also { b.get(it) }.toString(Charsets.UTF_8)
    }

    /** La provenienza e la lunghezza dell'intestazione di un file del buffer, o null se non e' un file di questo formato. */
    private fun headerOf(file: File): Pair<AiInputOrigin, Int>? = runCatching {
      file.inputStream().use { input ->
        val head = ByteArray(minOf(file.length(), (MAX_HEADER * 2 + 16).toLong()).toInt())
        var read = 0
        while (read < head.size) {
          val n = input.read(head, read, head.size - read)
          if (n < 0) break
          read += n
        }
        parseHeaderOnly(head.copyOf(read))
      }
    }.getOrNull()

    private fun parseHeaderOnly(bytes: ByteArray): Pair<AiInputOrigin, Int>? {
      val b = ByteBuffer.wrap(bytes)
      if (b.remaining() < 6 || b.int != MAGIC || b.get().toInt() != VERSION) return null
      val from = when (b.get().toInt()) {
        0 -> AiInputOrigin.Own
        1 -> AiInputOrigin.Preview(utf(b), utf(b).ifEmpty { null })
        else -> return null
      }
      return from to b.position()
    }
  }
}
