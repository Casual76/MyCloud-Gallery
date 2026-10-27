package dev.pampa.mycloudgallery.albums

import dev.pampa.mycloudgallery.core.albums.smart.SmartSeenMarks
import dev.pampa.mycloudgallery.core.backup.contract.BackupItemId
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.safety.SafeCopyRequestLog
import dev.pampa.mycloudgallery.core.backup.upload.AlbumUploadIntent
import dev.pampa.mycloudgallery.core.backup.upload.AlbumUploadItem
import dev.pampa.mycloudgallery.core.backup.upload.AlbumUploadJournal
import dev.pampa.mycloudgallery.core.common.files.StateFiles
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
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// I PICCOLI REGISTRI DUREVOLI DEGLI ALBUM (Fase 7, P13; L76), in `noBackupFilesDir/albums/` (fuori dai backup; l'azzeramento toglie la
// cartella). Lo schema v6 non ha tabelle per loro (P1 non le prevedeva): sono pochi dati, scritti di rado e sempre interi. Ogni scrittura e'
// atomica (file temporaneo con `fsync`, poi `ATOMIC_MOVE`, come il registro dei dati personali): dopo la morte del processo c'e' la versione
// di prima o quella nuova, mai una a meta'.
//
// R9 della revisione di sicurezza della Fase 7: **un file che c'e' ma non si legge** (errore di I/O, oltre il tetto, formato rovinato) **non
// vale "vuoto"** e la prima modifica non lo sovrascrive. Secondo il registro ([Unreadable]): "non so" (ogni lettura e modifica lancia
// [UnreadableRecords], niente in memoria, niente scritto: chi chiede, come `find` per SD-2, riceve un errore e non "nessuno"), oppure, per i
// registri dove "vuoto" e' innocuo (avvisi visti, segni "Nuove"), il file si mette da parte come `<nome>.bad` e si riparte vuoti. I contatori
// letti dal file hanno un tetto legato ai byte che restano (un file rovinato non fa allocare a ogni avvio). Mai titoli, percorsi o id nei log
// (D37): solo numeri.

/** Un registro che c'e' ma non si legge (R9): "non so", non "vuoto". Il messaggio ha solo il nome del registro. */
internal class UnreadableRecords(name: String) : IOException("registro illeggibile: $name")

/**
 * Un file scritto per intero in modo atomico, letto per intero: `StateFiles` di core-common (temporaneo nella stessa cartella forzato su disco,
 * `ATOMIC_MOVE`; lettura breve; un errore non sparisce in silenzio). Il nome del file e' l'etichetta dei log: non contiene mai un id.
 */
internal class DurableFile(private val file: File) {
  /** I byte del file, o null se **non c'e'**. Lancia `IOException` se c'e' ma non si legge, o se supera [maxBytes] (R9: mai "vuoto"). */
  fun read(maxBytes: Long = MAX_BYTES): ByteArray? {
    if (file.isFile && file.length() > maxBytes) throw IOException("file oltre il tetto: ${file.name}")
    val bytes = StateFiles.readBytes(file) ?: return null
    if (bytes.size > maxBytes) throw IOException("file oltre il tetto: ${file.name}")
    return bytes
  }

  fun write(bytes: ByteArray) = StateFiles.writeBytes(file, bytes)

  /** Mette il file da parte come `<nome>.bad` (sostituendo un `.bad` di prima): non si sovrascrive, non si cancella. */
  fun setAside() = StateFiles.replace(file, File(file.path + BAD_SUFFIX), label = file.name + BAD_SUFFIX)

  companion object {
    const val MAX_BYTES: Long = 16L * 1024 * 1024
    const val BAD_SUFFIX = ".bad"
  }
}

/**
 * Un registro a chiavi, tenuto in memoria e riscritto per intero a ogni modifica. [encode]/[decode] sono il formato del file (con il suo
 * numero di versione dentro); [name] e' solo per i log; [unreadablePolicy] dice che cosa vale un file che c'e' ma non si legge (R9).
 */
internal abstract class DurableRecords<K, V>(
  file: File,
  private val name: String,
  private val unreadablePolicy: Unreadable = Unreadable.SET_ASIDE,
) {
  /** Che cosa vale un registro che c'e' ma non si legge (R9). */
  enum class Unreadable {
    /** "Non so": ogni lettura e modifica lancia [UnreadableRecords] finche' il file non si rilegge; il file resta com'e'. */
    UNKNOWN,

    /** Il file va da parte come `<nome>.bad` e il registro riparte vuoto (solo dove "vuoto" e' innocuo). */
    SET_ASIDE,
  }

  private val durable = DurableFile(file)
  private val mutex = Mutex()
  private var loaded: LinkedHashMap<K, V>? = null

  /** Quante volte il file non si e' potuto leggere (Diagnostica: solo il numero). */
  @Volatile var unreadable: Int = 0
    private set

  protected abstract fun encode(out: DataOutputStream, records: Map<K, V>)

  protected abstract fun decode(input: DataInputStream): LinkedHashMap<K, V>

  /** Un contatore letto dal file: mai negativo e mai piu' grande di quanto i byte rimasti possano contenere ([minBytes] per elemento). */
  protected fun DataInputStream.count(minBytes: Int): Int {
    val n = readInt()
    if (n < 0 || n.toLong() * minBytes.coerceAtLeast(1) > available()) throw IOException("contatore oltre i byte rimasti")
    return n
  }

  private fun load(): LinkedHashMap<K, V> = loaded ?: run {
    val map = try {
      val bytes = durable.read()
      if (bytes == null) LinkedHashMap() else DataInputStream(ByteArrayInputStream(bytes)).use { decode(it) }
    } catch (e: IOException) {
      unreadable(e)
    } catch (e: RuntimeException) {
      unreadable(e)
    }
    loaded = map
    map
  }

  /** R9: niente "vuoto" in memoria per un file che c'e': "non so", oppure il file da parte e si riparte vuoti. */
  private fun unreadable(e: Exception): LinkedHashMap<K, V> {
    unreadable++
    return when (unreadablePolicy) {
      Unreadable.UNKNOWN -> {
        GalleryLog.w(TAG) { "$name illeggibile (${e.javaClass.simpleName}): non so, il file resta com'e'" }
        throw UnreadableRecords(name)
      }
      Unreadable.SET_ASIDE -> {
        // Se nemmeno lo spostamento riesce, l'eccezione arriva a chi chiama: niente in memoria, niente sovrascritto.
        durable.setAside()
        GalleryLog.w(TAG) { "$name illeggibile (${e.javaClass.simpleName}): messo da parte, si riparte vuoti" }
        LinkedHashMap()
      }
    }
  }

  suspend fun <T> read(block: (Map<K, V>) -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block(load()) } }

  /**
   * Modifica e salva: se il salvataggio fallisce l'eccezione arriva a chi chiama e la memoria torna com'era (nessuna scrittura a meta'). Un
   * registro "non so" ([Unreadable.UNKNOWN]) non si modifica: [UnreadableRecords] arriva a chi chiama e il file resta com'e' (R9).
   */
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
  // R9: "non so" se illeggibile (SD-2: `find` deve poter dire "non lo so", non "nessun caricamento").
  private val records = object : DurableRecords<String, AlbumUploadIntent>(file, "registro dei caricamenti verso gli album", DurableRecords.Unreadable.UNKNOWN) {
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
      repeat(input.count(MIN_RECORD_BYTES)) {
        val key = input.readUTF()
        val share = input.readUTF()
        val folder = SharePath.parse(input.readUTF())
        val items = List(input.count(MIN_ITEM_BYTES)) { readItem(input) }
        val created = if (input.readBoolean()) List(input.count(Long.SIZE_BYTES)) { BackupItemId(input.readLong()) } else null
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

  /** Le chiavi delle intenzioni ricordate adesso (la prima meta' di [TransferRegisters.prune]). */
  suspend fun keys(): Set<String> = records.read { it.keys.toSet() }

  /**
   * Toglie le intenzioni di [keys], chiavi che nessuna operazione aperta della condivisione cita piu': nessuno le chiede piu' (`find` e lo stato
   * servono solo agli elementi di un'operazione aperta), e il registro non cresce senza fine verso il tetto (R9). Gli elementi del diario del
   * backup gia' creati vanno avanti da soli.
   */
  suspend fun forget(keys: Set<String>): Int = if (keys.isEmpty()) 0 else records.update { map -> keys.count { map.remove(it) != null } }

  /** Quante volte il file non si e' potuto leggere (Diagnostica). */
  val unreadable: Int get() = records.unreadable

  private companion object {
    const val VERSION = 1

    /** I byte minimi di un'intenzione e di un elemento (tetti dei contatori letti dal file, R9). */
    const val MIN_RECORD_BYTES = 11
    const val MIN_ITEM_BYTES = 40

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
  // R9: se illeggibile il file va da parte (non si sovrascrive) e si riparte vuoti. Qui "vuoto" non perde una copia: le richieste accelerano il
  // guardiano, che trova comunque dal catalogo i media propri con la sola copia comune (`BackupGraph.guardianHasWork`, `GuardScope.All`).
  // Con "non so" invece una richiesta nuova non si registrerebbe e la condivisione resterebbe ferma all'ultimo passo.
  private val records = object : DurableRecords<MediaId, Boolean>(file, "richieste del guardiano") {
    override fun encode(out: DataOutputStream, records: Map<MediaId, Boolean>) {
      out.writeInt(VERSION)
      out.writeInt(records.size)
      records.keys.forEach { out.writeUTF(it.value) }
    }

    override fun decode(input: DataInputStream): LinkedHashMap<MediaId, Boolean> {
      if (input.readInt() != VERSION) throw IOException("versione")
      val out = LinkedHashMap<MediaId, Boolean>()
      repeat(input.count(2)) { out[MediaId(input.readUTF())] = true }
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
 * Revisione B (SH-2): le strisce "N non andati" che l'utente ha chiuso, per operazione della condivisione. **Locali al dispositivo** come il diario;
 * su file con `StateFiles` (scrittura atomica). Un file illeggibile vale "nessuna chiusa": al piu' una striscia torna a farsi vedere.
 */
internal class FileShareProblemMarks(file: File) : dev.pampa.mycloudgallery.core.albums.share.ShareProblemMarks {
  private val records = object : DurableRecords<String, Boolean>(file, "strisce dei problemi della condivisione chiuse") {
    override fun encode(out: DataOutputStream, records: Map<String, Boolean>) {
      out.writeInt(VERSION)
      out.writeInt(records.size)
      records.keys.forEach(out::writeUTF)
    }

    override fun decode(input: DataInputStream): LinkedHashMap<String, Boolean> {
      if (input.readInt() != VERSION) throw IOException("versione")
      val out = LinkedHashMap<String, Boolean>()
      repeat(input.readInt()) { out[input.readUTF()] = true }
      return out
    }
  }

  override suspend fun dismissed(): Set<dev.pampa.mycloudgallery.core.albums.contract.ShareOpId> =
    records.read { map -> map.keys.mapNotNullTo(LinkedHashSet()) { runCatching { dev.pampa.mycloudgallery.core.albums.contract.ShareOpId(it) }.getOrNull() } }

  override suspend fun dismiss(op: dev.pampa.mycloudgallery.core.albums.contract.ShareOpId) {
    records.update { map ->
      map[op.value] = true
      // Le piu' vecchie escono: il diario mostra al piu' le ultime 20 chiuse (ShareOrchestrator.MAX_PROBLEM_OPS).
      while (map.size > MAX_MARKS) map.remove(map.keys.first())
    }
  }

  private companion object {
    const val VERSION = 1
    const val MAX_MARKS = 200
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
      repeat(input.count(10)) { out[AlbumId(input.readUTF())] = input.readLong() }
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

/**
 * I segni locali degli album condivisi (R1 della revisione di sicurezza della Fase 7): l'album visto attivo, la prima chiusura vista (definitiva),
 * la copia personale di Q5 (b) gia' scritta o ritirata. Di **questo dispositivo**, mai sincronizzati. Illeggibile: il file va da parte e si
 * riparte vuoti (si perdono solo dei "visto": una copia non nasce, una chiusura gia' vista non e' piu' imposta; R9).
 */
internal class FileSharedAlbumMarks(file: File) : dev.pampa.mycloudgallery.core.albums.shared.SharedAlbumMarks {
  private val records = object : DurableRecords<AlbumId, dev.pampa.mycloudgallery.core.albums.shared.AlbumMarks>(file, "segni degli album condivisi") {
    override fun encode(out: DataOutputStream, records: Map<AlbumId, dev.pampa.mycloudgallery.core.albums.shared.AlbumMarks>) {
      out.writeInt(VERSION)
      out.writeInt(records.size)
      for ((album, mark) in records) {
        out.writeUTF(album.value)
        out.writeBoolean(mark.seenActive)
        val closure = mark.closure
        out.writeBoolean(closure != null)
        if (closure != null) {
          out.writeUTF(closure.state.code)
          out.writeBoolean(closure.afterClose != null)
          out.writeUTF(closure.afterClose.orEmpty())
          out.writeLong(closure.at)
          out.writeUTF(closure.author)
          out.writeUTF(closure.title)
          out.writeUTF(closure.folder.value)
        }
        val copy = mark.copy
        out.writeBoolean(copy != null)
        if (copy != null) {
          out.writeUTF(copy.copy.value)
          out.writeUTF(copy.creator.value)
          out.writeInt(copy.items)
          out.writeBoolean(copy.cause != null)
          out.writeUTF(copy.cause.orEmpty())
          out.writeBoolean(copy.withdrawn)
        }
      }
    }

    override fun decode(input: DataInputStream): LinkedHashMap<AlbumId, dev.pampa.mycloudgallery.core.albums.shared.AlbumMarks> {
      if (input.readInt() != VERSION) throw IOException("versione")
      val out = LinkedHashMap<AlbumId, dev.pampa.mycloudgallery.core.albums.shared.AlbumMarks>()
      repeat(input.count(5)) {
        val album = AlbumId(input.readUTF())
        val seen = input.readBoolean()
        val closure = if (!input.readBoolean()) null else {
          val state = dev.pampa.mycloudgallery.core.model.enumByCodeOrNull<dev.pampa.mycloudgallery.core.albums.contract.SharedState>(input.readUTF())
            ?: throw IOException("stato")
          val hasAfter = input.readBoolean()
          val after = input.readUTF().takeIf { hasAfter }
          dev.pampa.mycloudgallery.core.albums.shared.ClosureMark(state, after, input.readLong(), input.readUTF(), input.readUTF(), SharePath.parse(input.readUTF()))
        }
        val copy = if (!input.readBoolean()) null else {
          val id = AlbumId(input.readUTF())
          val creator = dev.pampa.mycloudgallery.core.model.AccountId(input.readUTF())
          val items = input.readInt()
          val hasCause = input.readBoolean()
          val cause = input.readUTF().takeIf { hasCause }
          dev.pampa.mycloudgallery.core.albums.shared.CopyMark(id, creator, items, cause, input.readBoolean())
        }
        out[album] = dev.pampa.mycloudgallery.core.albums.shared.AlbumMarks(seen, closure, copy)
      }
      return out
    }
  }

  override suspend fun all(): Map<AlbumId, dev.pampa.mycloudgallery.core.albums.shared.AlbumMarks> = records.read { LinkedHashMap(it) }

  override suspend fun update(change: (MutableMap<AlbumId, dev.pampa.mycloudgallery.core.albums.shared.AlbumMarks>) -> Unit) {
    records.update { change(it) }
  }

  private companion object {
    const val VERSION = 1
  }
}
