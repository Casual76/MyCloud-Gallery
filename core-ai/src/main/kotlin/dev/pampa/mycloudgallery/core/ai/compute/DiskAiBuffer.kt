package dev.pampa.mycloudgallery.core.ai.compute

import dev.pampa.mycloudgallery.core.ai.contract.AiBuffer
import dev.pampa.mycloudgallery.core.index.ai.AnalysisFetcher
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.io.File
import java.io.IOException
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
 *
 * I nomi dei file sono la chiave in esadecimale e la classe: mai un nome scelto da fuori.
 */
class DiskAiBuffer(
  private val dir: File,
  private val freeSpace: AiFreeSpace = AiFreeSpace { dir.usableSpace },
  val maxBytes: Long = AiBuffer.MAX_BYTES,
  private val maxBlobBytes: Int = MAX_BLOB_BYTES,
) : AiBuffer {

  private val lock = Any()
  private val sizes = HashMap<Pair<ContentKey, SegmentClass>, Long>()
  private val used = MutableStateFlow(0L)

  override val usedBytes: StateFlow<Long> get() = used

  init {
    require(maxBytes > 0 && maxBlobBytes > 0) { "Tetti non validi." }
    synchronized(lock) { rescan() }
  }

  override suspend fun put(key: ContentKey, cls: SegmentClass, bytes: ByteArray): Boolean = synchronized(lock) {
    if (cls !in AnalysisFetcher.FETCHABLE || bytes.isEmpty() || bytes.size > maxBlobBytes) return false
    if (freeSpace.usableBytes() - bytes.size < AiBuffer.MIN_FREE_BYTES) return false
    val old = sizes[key to cls] ?: 0L
    if (used.value - old + bytes.size > maxBytes) return false
    val target = fileOf(key, cls)
    val tmp = File(dir, target.name + TMP)
    try {
      dir.mkdirs()
      tmp.outputStream().use { out ->
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
    sizes[key to cls] = bytes.size.toLong()
    used.value = used.value - old + bytes.size
    true
  }

  override suspend fun get(key: ContentKey, cls: SegmentClass): ByteArray? = synchronized(lock) {
    if (sizes[key to cls] == null) return null
    try {
      fileOf(key, cls).readBytes()
    } catch (e: IOException) {
      forget(key, cls)
      null
    }
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
      used.value = 0L
    }
  }

  /** Le chiavi con almeno un blob (per il calcolo solo-buffer, E-A6). */
  fun keys(): Set<ContentKey> = synchronized(lock) { sizes.keys.mapTo(LinkedHashSet()) { it.first } }

  fun contains(key: ContentKey, cls: SegmentClass): Boolean = synchronized(lock) { (key to cls) in sizes }

  private fun forget(key: ContentKey, cls: SegmentClass) {
    val size = sizes.remove(key to cls) ?: return
    used.value -= size
  }

  /** Ricostruisce lo stato dai file: via i temporanei e i nomi che non sono nostri. */
  private fun rescan() {
    sizes.clear()
    var total = 0L
    for (file in dir.listFiles().orEmpty()) {
      val parsed = parse(file.name)
      if (parsed == null || !file.isFile || file.length() <= 0L) {
        file.delete()
        continue
      }
      sizes[parsed] = file.length()
      total += file.length()
    }
    used.value = total
  }

  private fun fileOf(key: ContentKey, cls: SegmentClass) = File(dir, "${key.hex}.${cls.wire}")

  private fun parse(name: String): Pair<ContentKey, SegmentClass>? {
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
  }
}
