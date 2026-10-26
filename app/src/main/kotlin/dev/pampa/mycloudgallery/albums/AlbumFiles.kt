package dev.pampa.mycloudgallery.albums

import dev.pampa.mycloudgallery.core.albums.smart.SmartSeenMarks
import dev.pampa.mycloudgallery.core.backup.contract.BackupItemId
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.safety.SafeCopyRequestLog
import dev.pampa.mycloudgallery.core.backup.upload.AlbumUploadIntent
import dev.pampa.mycloudgallery.core.backup.upload.AlbumUploadItem
import dev.pampa.mycloudgallery.core.backup.upload.AlbumUploadJournal
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// I PICCOLI REGISTRI DUREVOLI DEGLI ALBUM (Fase 7, P13; L76), in `noBackupFilesDir/albums/` (fuori dai backup; l'azzeramento toglie la
// cartella). Lo schema v6 non ha tabelle per loro (P1 non le prevedeva): sono pochi dati, scritti di rado e sempre interi. Ogni scrittura e'
// atomica (file temporaneo con `fsync`, poi `ATOMIC_MOVE`, come il registro dei dati personali): dopo la morte del processo c'e' la versione
// di prima o quella nuova, mai una a meta'. Un file illeggibile vale "vuoto" (si perde la memoria, mai si inventa).
// Mai titoli, percorsi o id nei log (D37): solo numeri.

/** Un file scritto per intero in modo atomico, letto per intero. */
internal class DurableFile(private val file: File) {
  fun read(maxBytes: Long = MAX_BYTES): ByteArray? = try {
    if (!file.isFile || file.length() > maxBytes) null else file.readBytes()
  } catch (e: IOException) {
    null
  }

  fun write(bytes: ByteArray) {
    val dir = file.absoluteFile.parentFile
    if (dir != null && !dir.isDirectory) dir.mkdirs()
    val temp = File(dir, file.name + ".tmp")
    FileOutputStream(temp).use { out ->
      out.write(bytes)
      out.fd.sync()
    }
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  }

  companion object {
    const val MAX_BYTES: Long = 16L * 1024 * 1024
  }
}

/**
 * Un registro a chiavi, tenuto in memoria e riscritto per intero a ogni modifica. [encode]/[decode] sono il formato del file (con il suo
 * numero di versione dentro); [name] e' solo per i log.
 */
internal abstract class DurableRecords<K, V>(file: File, private val name: String) {
  private val durable = DurableFile(file)
  private val mutex = Mutex()
  private var loaded: LinkedHashMap<K, V>? = null

  protected abstract fun encode(out: DataOutputStream, records: Map<K, V>)

  protected abstract fun decode(input: DataInputStream): LinkedHashMap<K, V>

  private fun load(): LinkedHashMap<K, V> = loaded ?: run {
    val bytes = durable.read()
    val map = if (bytes == null) LinkedHashMap() else try {
      DataInputStream(ByteArrayInputStream(bytes)).use { decode(it) }
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "$name illeggibile: si riparte vuoti" }
      LinkedHashMap()
    } catch (e: RuntimeException) {
      GalleryLog.w(TAG) { "$name illeggibile: si riparte vuoti" }
      LinkedHashMap()
    }
    loaded = map
    map
  }

  suspend fun <T> read(block: (Map<K, V>) -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block(load()) } }

  /** Modifica e salva: se il salvataggio fallisce l'eccezione arriva a chi chiama e la memoria torna com'era (nessuna scrittura a meta'). */
  suspend fun <T> update(block: (MutableMap<K, V>) -> T): T = mutex.withLock {
    withContext(Dispatchers.IO) {
      val current = load()
      val next = LinkedHashMap(current)
      val result = block(next)
      if (next != current) {
        val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { encode(it, next) } }.toByteArray()
        durable.write(bytes)
        loaded = next
      }
      result
    }
  }

  private companion object {
    const val TAG = "AlbumFiles"
  }
}

/**
 * [AlbumUploadJournal] su disco (P4: "lo lega a una tabella o a un file"): l'intenzione di un caricamento verso un album si scrive **prima**
 * degli elementi del diario del backup, gli elementi dopo; una chiave gia' vista non ne crea un secondo (idempotenza della porta).
 */
internal class FileAlbumUploadJournal(file: File) : AlbumUploadJournal {
  private val records = object : DurableRecords<String, AlbumUploadIntent>(file, "registro dei caricamenti verso gli album") {
    override fun encode(out: DataOutputStream, records: Map<String, AlbumUploadIntent>) {
      out.writeInt(VERSION)
      out.writeInt(records.size)
      for ((key, intent) in records) {
        out.writeUTF(key)
        out.writeUTF(intent.share)
        out.writeUTF(intent.folder.value)
        out.writeInt(intent.items.size)
        for (item in intent.items) writeItem(out, item)
        val created = intent.created
        out.writeBoolean(created != null)
        if (created != null) {
          out.writeInt(created.size)
          created.forEach { out.writeLong(it.value) }
        }
      }
    }

    override fun decode(input: DataInputStream): LinkedHashMap<String, AlbumUploadIntent> {
      if (input.readInt() != VERSION) throw IOException("versione")
      val out = LinkedHashMap<String, AlbumUploadIntent>()
      repeat(input.readInt()) {
        val key = input.readUTF()
        val share = input.readUTF()
        val folder = SharePath.parse(input.readUTF())
        val items = List(input.readInt()) { readItem(input) }
        val created = if (input.readBoolean()) List(input.readInt()) { BackupItemId(input.readLong()) } else null
        out[key] = AlbumUploadIntent(share, folder, items, created)
      }
      return out
    }
  }

  override suspend fun intent(key: String): AlbumUploadIntent? = records.read { it[key] }

  override suspend fun recordIntent(key: String, intent: AlbumUploadIntent) {
    records.update { if (key !in it) it[key] = intent }
  }

  override suspend fun recordItems(key: String, items: List<BackupItemId>) {
    records.update { map -> map[key]?.let { map[key] = AlbumUploadIntent(it.share, it.folder, it.items, items) } }
  }

  /** Quante intenzioni ricorda (Diagnostica). */
  suspend fun size(): Int = records.read { it.size }

  private companion object {
    const val VERSION = 1

    fun writeItem(out: DataOutputStream, item: AlbumUploadItem) {
      out.writeUTF(item.mediaId.value)
      out.writeUTF(item.versionId.value)
      out.writeLong(item.size)
      out.writeUTF(item.phone.volume)
      out.writeLong(item.phone.mediaStoreId)
      out.writeUTF(item.phone.mediaStoreVersion)
      out.writeLong(item.expected.size)
      out.writeLong(item.expected.modifiedAtMillis)
      out.writeBoolean(item.expected.generation != null)
      item.expected.generation?.let(out::writeLong)
      out.writeUTF(item.quickHash?.hex.orEmpty())
      out.writeUTF(item.fileName)
    }

    fun readItem(input: DataInputStream): AlbumUploadItem {
      val media = MediaId(input.readUTF())
      val version = VersionId(input.readUTF())
      val size = input.readLong()
      val phone = PhoneRef(input.readUTF(), input.readLong(), input.readUTF())
      val stampSize = input.readLong()
      val modified = input.readLong()
      val generation = if (input.readBoolean()) input.readLong() else null
      val quick = input.readUTF().takeIf { it.isNotEmpty() }?.let(::QuickHash)
      return AlbumUploadItem(media, version, size, phone, FileStamp(stampSize, modified, generation), quick, input.readUTF())
    }
  }
}

/** [SafeCopyRequestLog] su disco (D28): le richieste del guardiano dopo una condivisione sopravvivono alla morte del processo. */
internal class FileSafeCopyRequestLog(file: File) : SafeCopyRequestLog {
  private val records = object : DurableRecords<MediaId, Boolean>(file, "richieste del guardiano") {
    override fun encode(out: DataOutputStream, records: Map<MediaId, Boolean>) {
      out.writeInt(VERSION)
      out.writeInt(records.size)
      records.keys.forEach { out.writeUTF(it.value) }
    }

    override fun decode(input: DataInputStream): LinkedHashMap<MediaId, Boolean> {
      if (input.readInt() != VERSION) throw IOException("versione")
      val out = LinkedHashMap<MediaId, Boolean>()
      repeat(input.readInt()) { out[MediaId(input.readUTF())] = true }
      return out
    }
  }

  override suspend fun add(media: Set<MediaId>) {
    records.update { map -> media.forEach { map[it] = true } }
  }

  override suspend fun pending(): Set<MediaId> = records.read { it.keys.toSet() }

  override suspend fun remove(media: Set<MediaId>) {
    records.update { map -> media.forEach { map.remove(it) } }
  }

  private companion object {
    const val VERSION = 1
  }
}

/**
 * I segni "visto fino a" degli album smart (P6, E-AL7: "Nuove: 12"): **locali al dispositivo**, mai sincronizzati (L84). Ora del NAS (come
 * `added_at` delle righe del motore).
 */
internal class FileSmartSeenMarks(file: File) : SmartSeenMarks {
  private val records = object : DurableRecords<AlbumId, Long>(file, "segni visto degli album smart") {
    override fun encode(out: DataOutputStream, records: Map<AlbumId, Long>) {
      out.writeInt(VERSION)
      out.writeInt(records.size)
      for ((album, at) in records) {
        out.writeUTF(album.value)
        out.writeLong(at)
      }
    }

    override fun decode(input: DataInputStream): LinkedHashMap<AlbumId, Long> {
      if (input.readInt() != VERSION) throw IOException("versione")
      val out = LinkedHashMap<AlbumId, Long>()
      repeat(input.readInt()) { out[AlbumId(input.readUTF())] = input.readLong() }
      return out
    }
  }

  override suspend fun seenUpTo(id: AlbumId): Long? = records.read { it[id] }

  override suspend fun markSeen(id: AlbumId, atMs: Long) {
    records.update { it[id] = atMs }
  }

  private companion object {
    const val VERSION = 1
  }
}
