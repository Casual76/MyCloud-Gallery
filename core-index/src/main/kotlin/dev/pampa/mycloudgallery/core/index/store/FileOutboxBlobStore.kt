package dev.pampa.mycloudgallery.core.index.store

import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.nas.LocalNames
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * I byte dell'outbox su disco (`noBackupFilesDir/index-outbox/`, fuori dai backup come la replica): un file per blob,
 * con un nome derivato da un hash (`LocalNames`, §8.3: mai testo letto dal NAS, mai un'estensione). La scrittura e'
 * atomica: temporaneo, `fsync`, rinomina; un file a meta' non ha mai il nome definitivo, e [retainOnly] toglie i
 * temporanei rimasti da una morte del processo.
 */
class FileOutboxBlobStore(
  private val dir: File,
  private val io: CoroutineDispatcher = Dispatchers.IO,
) : OutboxBlobStore {

  private val random = SecureRandom()

  override suspend fun write(share: String, cls: SegmentClass, bytes: ByteArray): String = withContext(io) {
    if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cartella dell'outbox non creata")
    val nonce = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val handle = LocalNames.of("index-outbox", share.lowercase(Locale.ROOT), cls.wire, nonce)
    val temp = File(dir, "$handle$TEMP_SUFFIX")
    FileOutputStream(temp).use { out ->
      out.write(bytes)
      out.flush()
      out.fd.sync()
    }
    val target = File(dir, handle)
    if (!temp.renameTo(target)) {
      temp.delete()
      throw IOException("blob dell'outbox non pubblicato")
    }
    handle
  }

  override suspend fun read(handle: String): ByteArray? = withContext(io) {
    if (!isHandle(handle)) return@withContext null
    val file = File(dir, handle)
    if (!file.isFile) null else file.readBytes()
  }

  override suspend fun delete(handles: Collection<String>) = withContext(io) {
    handles.filter(::isHandle).forEach { File(dir, it).delete() }
  }

  override suspend fun retainOnly(referenced: Set<String>) = withContext(io) {
    dir.listFiles()?.forEach { file -> if (file.name !in referenced) file.delete() }
    Unit
  }

  override suspend fun handles(): Set<String> = withContext(io) {
    dir.listFiles()?.map { it.name }?.filter(::isHandle)?.toSet().orEmpty()
  }

  /** Solo i nomi che questa classe produce: un handle non diventa mai un percorso fuori dalla cartella. */
  private fun isHandle(name: String): Boolean = HANDLE.matches(name)

  private companion object {
    const val TEMP_SUFFIX = "-tmp"
    val HANDLE = Regex("^m[0-9a-z]{52}$")
  }
}
