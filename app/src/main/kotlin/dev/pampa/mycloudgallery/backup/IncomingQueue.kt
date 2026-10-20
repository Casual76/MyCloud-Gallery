package dev.pampa.mycloudgallery.backup

import android.media.MediaDataSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.BackupGate
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupItemId
import dev.pampa.mycloudgallery.core.backup.contract.DestinationArea
import dev.pampa.mycloudgallery.core.backup.contract.IncomingItem
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStage
import dev.pampa.mycloudgallery.core.backup.contract.NasDestination
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.store.RoomBackupStore
import dev.pampa.mycloudgallery.core.backup.upload.IncomingLocalFile
import dev.pampa.mycloudgallery.core.backup.upload.IncomingLocalFiles
import dev.pampa.mycloudgallery.core.backup.upload.IncomingUploadPorts
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.CatalogIncomingMedia
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.entity.IncomingItemEntity
import dev.pampa.mycloudgallery.core.database.transaction
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedRetry
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedStatus
import dev.pampa.mycloudgallery.feature.integrations.receive.IncomingFiles
import dev.pampa.mycloudgallery.feature.integrations.receive.IncomingQueue
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// I FILE IN ARRIVO nel diario del backup (Fase 5B, P14; docs/fase5-progetto.md §2.7 "Ricevere condivisioni", D24, E-X8, E-S11). La coda che
// `DefaultIncomingUploads` (P11) chiama **dopo** il tocco su "Salva sul NAS" e la conferma ricontrollata: per ogni file copiato in
// `noBackupFilesDir/incoming/` nasce un elemento `INCOMING` del diario (`backup_item`) legato alla sua riga di `incoming_item`; il motore del
// backup lo carica con il caricamento sicuro di sempre e **solo dopo** la copia locale si toglie. Nessun nome, percorso o share nei log.

/** Gli stati di `incoming_item` (codici stabili della colonna `state`). */
object IncomingStates {
  /** In coda: il file locale c'e' e l'elemento del diario lo carichera'. */
  const val QUEUED: String = "queued"

  /** Caricato, riletto e collegato nel catalogo: la copia locale e' stata tolta. */
  const val UPLOADED: String = "uploaded"

  /** La copia locale non c'era piu' (niente caricato). */
  const val GONE: String = "gone"

  /** Non caricato (verifica fallita, copia locale cambiata, destinazione rifiutata): la copia locale **resta**. */
  const val FAILED: String = "failed"

  /**
   * Le condivisioni che la pulizia all'avvio deve tenere (file ancora utili all'utente). IN-7: anche [GONE], perche' un arrivo "sparito" per un'unione
   * del catalogo (non perche' la copia mancava) ha ancora il suo file, e adesso l'utente lo puo' riprovare o scartare da "Ricevuti in coda".
   */
  val KEEP: List<String> = listOf(QUEUED, FAILED, GONE)

  /** IN-7: i file che non si sono caricati e aspettano una scelta dell'utente ("Riprova" o "Scarta"): il piede della Libreria e la notifica li contano. */
  val PROBLEMS: List<String> = listOf(FAILED, GONE)

  /** IN-7: quelli che "Ricevuti in coda" elenca: in coda e con un problema (i caricati non servono piu'). */
  val LISTED: List<String> = listOf(QUEUED, FAILED, GONE)
}

/**
 * IN-8 della verifica funzionale 5B: i metadati di un file in arrivo, letti dai **byte locali** (la copia in `incoming/`) appena nasce il suo media.
 * Il cammino `INCOMING` del motore va da `LINKED` a `DONE` senza preparare ne' leggere niente, e la scansione vede la posizione come gia' nota: senza
 * questo il media ricevuto restava senza data (1/1/1970 in fondo alla timeline), senza tipo e senza segnaposto. Lo lega `IntegrationsGraph` (ha
 * l'estrattore della Libreria); senza, il media nasce com'era (con la data di arrivo al posto di 1970).
 */
fun interface IncomingMetadata {
  /** Legge [file] (di tipo [mime]) e scrive i metadati nella versione [versionId]. Non lancia: un file che non si legge si dice al caricamento. */
  suspend fun read(mediaId: MediaId, versionId: VersionId, file: File, mime: String, displayName: String?, arrivedAtMs: Long)
}

/**
 * La data provvisoria di un file in arrivo (IN-8 della verifica funzionale 5B): l'ora del NAS se la si conosce, altrimenti quella della copia sul telefono,
 * **mai 0** (l'1/1/1970, in fondo alla timeline). La data di scatto vera, se il file ne ha una, la scrive subito dopo la lettura dei metadati.
 */
internal object IncomingArrival {
  fun provisionalMs(nasNowMs: Long?, file: File): Long = nasNowMs ?: file.lastModified().takeIf { it > 0L } ?: 1L
}

/**
 * La coda dei file in arrivo (porta `IncomingQueue` di feature-integrations). [destinationAllowed]: la share e' l'area privata o comune
 * dell'account con l'area giusta, il cancello degli arrivi non la chiude per sempre (D12, R2, sola lettura, area altrui: no; congelamento,
 * emergenza e pausa: si mette in coda e si aspetta), la cartella non e' l'area dell'app ne' `Versioni/` e, con la recinzione di D13, sta sotto
 * la radice di prova. [enqueue]: per ogni file il media del catalogo (gia' noto per gli stessi byte, o nuovo e invisibile finche' la copia
 * verificata non c'e'), poi l'elemento `INCOMING` e la riga di `incoming_item` nella stessa transazione. Nome occupato → "nome (2)" lo fa il motore.
 */
class AppIncomingQueue(
  private val database: GalleryDatabase,
  private val catalog: suspend () -> MediaCatalog?,
  private val store: RoomBackupStore,
  private val gate: BackupGate,
  private val privateShare: () -> String?,
  private val commonShare: () -> String?,
  private val fenceRoot: () -> SharePath?,
  private val clock: BackupClock,
  /** Dopo una messa in coda: il lavoro che carica (avviato dall'utente se grande, D24). */
  private val onQueued: (userInitiated: Boolean) -> Unit,
) : IncomingQueue {

  /** IN-8: la lettura dei metadati dei file in arrivo. La mette `IntegrationsGraph` (ha l'estrattore); null = il media nasce senza. */
  @Volatile
  var metadata: IncomingMetadata? = null

  override suspend fun destinationAllowed(dest: NasDestination): Boolean = blockOf(dest) == null

  /**
   * IN-7: perche' [dest] non e' ammessa adesso, con le **stesse** regole di [destinationAllowed] (D12, R2, D13, la share, il congelamento...); null =
   * ammessa. "Riprova" di "Ricevuti in coda" lo chiede prima di rimettere in coda e dice all'utente quale cancello lo vieta.
   */
  suspend fun blockOf(dest: NasDestination): ReceivedStatus? {
    val share = when (dest.area) {
      DestinationArea.PRIVATE -> privateShare()
      DestinationArea.COMMON -> commonShare()
    } ?: return ReceivedStatus.UNAVAILABLE
    if (!share.equals(dest.share, ignoreCase = true)) return ReceivedStatus.FOLDER_LOCKED
    when (gate.stateOf(dest.share)) {
      BackupGateState.OPEN, BackupGateState.PAUSED, BackupGateState.FROZEN, BackupGateState.WRITES_STOPPED -> Unit
      BackupGateState.TEST_ONLY -> return ReceivedStatus.TRIAL
      BackupGateState.READ_ONLY_AREA -> return ReceivedStatus.FOLDER_LOCKED
      BackupGateState.NOT_READY, BackupGateState.NO_PRIVATE_AREA -> return ReceivedStatus.UNAVAILABLE
    }
    // La stessa regola che il motore applica prima di caricare (un file di prova nella cartella scelta).
    val allowed = runCatching { IncomingUploadPorts(NoLocalFiles, gate, fenceRoot()).allows(dest.folder.child(PROBE_NAME)) }.getOrDefault(false)
    return if (allowed) null else ReceivedStatus.FOLDER_LOCKED
  }

  /**
   * IN-7: la destinazione di un arrivo gia' in coda, dalla sua riga: la share e la cartella di allora e l'area che quella share ha **adesso** (null se non
   * e' piu' l'area privata o comune dell'utente, o se la riga non ha una cartella valida).
   */
  fun destinationOf(row: IncomingItemEntity): NasDestination? {
    val share = row.destShare ?: return null
    val folder = runCatching { SharePath.parse(row.destFolder ?: return null) }.getOrNull() ?: return null
    val area = when {
      share.equals(privateShare(), ignoreCase = true) -> DestinationArea.PRIVATE
      share.equals(commonShare(), ignoreCase = true) -> DestinationArea.COMMON
      else -> return null
    }
    return NasDestination(share, folder, area)
  }

  override suspend fun enqueue(stage: IncomingStage, dest: NasDestination, files: IncomingFiles): Int {
    val cat = catalog() ?: return 0
    var queued = 0
    var bytes = 0L
    for (item in stage.accepted) {
      val file = files.fileOf(stage.id, item) ?: continue
      val quick = withContext(Dispatchers.IO) { quickHashOf(file, item.size) } ?: continue
      // IN-8: la data provvisoria del media e' quella dell'arrivo (l'ora del NAS, o quella della copia sul telefono), mai 1/1/1970; la data di scatto vera,
      // se il file ne ha una, arriva subito sotto dai suoi byte.
      val arrivedAt = IncomingArrival.provisionalMs(clock.nasNowMs(), file)
      val media = cat.incomingMedia(Fingerprint(item.size, quick, item.sha256), kindOf(item.mime), arrivedAt)
      if (media.created) readMetadata(media, file, item.mime, item.displayName, arrivedAt)
      val planned = dest.folder.child(IncomingNames.of(item))
      database.transaction {
        val created = store.createIncoming(
          NewBackupItem(
            kind = UploadKind.INCOMING,
            mediaId = media.mediaId,
            versionId = media.versionId,
            size = item.size,
            phone = null,
            expected = null,
            share = dest.share,
            planned = planned,
            quickHash = quick,
            sha256 = item.sha256,
          ),
        )
        val now = clock.nasNowMs() ?: 0L
        database.incomingDao().insert(
          IncomingItemEntity(
            stageId = stage.id.value,
            state = IncomingStates.QUEUED,
            fileName = item.name,
            displayName = item.displayName,
            sha256 = item.sha256.hex,
            size = item.size,
            mime = item.mime,
            destShare = dest.share,
            destFolder = dest.folder.value,
            backupItemId = created.id.value,
            createdAt = now,
            updatedAt = now,
          ),
        )
      }
      queued++
      bytes += item.size
    }
    if (queued > 0) onQueued(bytes >= dev.pampa.mycloudgallery.core.backup.contract.IncomingLimits.USER_INITIATED_JOB_BYTES)
    return queued
  }

  /**
   * IN-7, "Riprova" di "Ricevuti in coda": mette di nuovo in coda un arrivo che non si e' caricato ([IncomingStates.FAILED] o [IncomingStates.GONE]) e
   * di cui la copia locale [file] c'e' ancora. L'elemento del diario che ha rinunciato e' terminale e non si riapre mai: ne nasce **uno nuovo**
   * (`createIncoming`, stessi byte, stessa destinazione) e la riga di `incoming_item` torna "in coda" legata a lui, nella stessa transazione. Il media
   * e la versione si cercano di nuovo per gli stessi byte ([MediaCatalog.incomingMedia], con le regole di R15). Chi chiama ha gia' controllato i cancelli
   * ([blockOf]) e chiede il lavoro dopo ([askJob]). [ReceivedRetry.NoCopy] se la copia non si legge piu' (di un'altra dimensione), `Blocked(UNAVAILABLE)`
   * se il catalogo non c'e' ancora.
   */
  suspend fun requeue(row: IncomingItemEntity, dest: NasDestination, file: File): ReceivedRetry {
    val cat = catalog() ?: return ReceivedRetry.Blocked(ReceivedStatus.UNAVAILABLE)
    val quick = withContext(Dispatchers.IO) { quickHashOf(file, row.size) } ?: return ReceivedRetry.NoCopy
    val sha = Sha256(row.sha256)
    val arrivedAt = IncomingArrival.provisionalMs(clock.nasNowMs(), file)
    val media = cat.incomingMedia(Fingerprint(row.size, quick, sha), kindOf(row.mime), arrivedAt)
    if (media.created) readMetadata(media, file, row.mime, row.displayName, arrivedAt)
    val item = IncomingItem(row.fileName, sha, row.size, row.mime, row.displayName)
    database.transaction {
      val created = store.createIncoming(
        NewBackupItem(
          kind = UploadKind.INCOMING,
          mediaId = media.mediaId,
          versionId = media.versionId,
          size = row.size,
          phone = null,
          expected = null,
          share = dest.share,
          planned = dest.folder.child(IncomingNames.of(item)),
          quickHash = quick,
          sha256 = sha,
        ),
      )
      database.incomingDao().update(row.copy(state = IncomingStates.QUEUED, backupItemId = created.id.value, updatedAt = clock.nasNowMs() ?: row.updatedAt))
    }
    return ReceivedRetry.Queued
  }

  /**
   * IN-7: chiede il lavoro che carica, dopo un "Riprova": un lavoro dell'utente (IN-5), avviato da un tocco, UIDT se il file e' grande (come "Salva sul
   * NAS"). [bytes] e' il peso del file.
   */
  fun askJob(bytes: Long) {
    onQueued(bytes >= dev.pampa.mycloudgallery.core.backup.contract.IncomingLimits.USER_INITIATED_JOB_BYTES)
  }

  private suspend fun readMetadata(media: CatalogIncomingMedia, file: File, mime: String, displayName: String?, arrivedAt: Long) {
    val reader = metadata ?: return
    try {
      reader.read(media.mediaId, media.versionId, file, mime, displayName, arrivedAt)
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "metadati di un arrivo non letti (${e.javaClass.simpleName}): resta la data di arrivo" }
    }
  }

  private fun kindOf(mime: String): MediaKind = if (mime.lowercase(Locale.ROOT).startsWith("video/")) MediaKind.VIDEO else MediaKind.PHOTO

  private object NoLocalFiles : IncomingLocalFiles {
    override suspend fun open(id: BackupItemId): IncomingLocalFile? = null

    override suspend fun finished(id: BackupItemId, state: UploadState) = Unit
  }

  companion object {
    private const val TAG = "BackupArrivi"
    private const val PROBE_NAME = "file.jpg"

    /** `qh1` di un file locale (testa e coda come la lettura del telefono, `QuickHashAlgorithm`). Null se il file non si legge o e' cambiato. */
    fun quickHashOf(file: File, size: Long): QuickHash? = try {
      RandomAccessFile(file, "r").use { raf ->
        if (raf.length() != size) return null
        val split = size > 2L * QuickHashAlgorithm.CHUNK_BYTES
        val head = ByteArray(if (split) QuickHashAlgorithm.CHUNK_BYTES else size.toInt())
        raf.readFully(head)
        val tail = ByteArray(if (split) QuickHashAlgorithm.CHUNK_BYTES else 0)
        if (split) {
          raf.seek(size - tail.size)
          raf.readFully(tail)
        }
        QuickHashAlgorithm.compute(size, head, tail)
      }
    } catch (_: IOException) {
      null
    }
  }
}

/**
 * Il nome sul NAS di un file in arrivo: quello che l'altra app ha dato, **ripulito** (niente separatori, controlli, punti iniziali; al piu' 120
 * caratteri; un componente di percorso valido) con l'estensione del **tipo vero** (letto dai byte); senza un nome usabile "Ricevuto-<impronta>".
 * Il nome dell'altra app non entra mai in un percorso locale (quelli derivano dall'hash): solo qui, nella cartella scelta dall'utente.
 */
object IncomingNames {
  fun of(item: IncomingItem): String {
    val ext = extensionOf(item.mime)
    val raw = item.displayName.orEmpty()
      .filterNot { it.isISOControl() || it == '/' || it == '\\' || it == ':' || it == '*' || it == '?' || it == '"' || it == '<' || it == '>' || it == '|' }
      .trim().trimStart('.').take(MAX_STEM)
    val stem = raw.substringBeforeLast('.', raw).trim().ifEmpty { "Ricevuto-" + item.sha256.hex.take(12) }
    val name = "$stem.$ext"
    return if (SharePath.isValidComponent(name)) name else "Ricevuto-${item.sha256.hex.take(12)}.$ext"
  }

  private fun extensionOf(mime: String): String = when (mime.lowercase(Locale.ROOT)) {
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    "image/heic" -> "heic"
    "image/heif" -> "heif"
    "image/avif" -> "avif"
    "image/bmp" -> "bmp"
    "image/x-adobe-dng", "image/dng" -> "dng"
    "video/mp4" -> "mp4"
    "video/quicktime" -> "mov"
    "video/3gpp" -> "3gp"
    "video/webm" -> "webm"
    "video/x-matroska" -> "mkv"
    else -> mime.substringAfter('/', "bin").filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }
  }

  private const val MAX_STEM = 120
}

/**
 * Le copie locali dei file in arrivo per il motore (`IncomingLocalFiles` di core-backup), da `incoming_item` e dalla cartella `incoming/`.
 * [finished]: `DONE`/`LINKED_EXISTING` = la copia sul NAS e' riletta e collegata, la copia locale si toglie (e la cartella della condivisione
 * se resta vuota); `GONE` e `FAILED` la lasciano (l'utente non perde il file) e segnano la riga.
 */
class AppIncomingLocalFiles(private val database: GalleryDatabase, private val root: File) : IncomingLocalFiles {

  private val dao get() = database.incomingDao()

  override suspend fun open(id: BackupItemId): IncomingLocalFile? = withContext(Dispatchers.IO) {
    for (row in dao.forBackupItem(id.value)) {
      val file = fileOf(row) ?: continue
      return@withContext try {
        LocalFile(file)
      } catch (_: IOException) {
        null
      }
    }
    null
  }

  override suspend fun finished(id: BackupItemId, state: UploadState) {
    val code = when (state) {
      UploadState.DONE, UploadState.LINKED_EXISTING -> IncomingStates.UPLOADED
      UploadState.GONE -> IncomingStates.GONE
      UploadState.FAILED -> IncomingStates.FAILED
      else -> return
    }
    withContext(Dispatchers.IO) {
      for (row in dao.forBackupItem(id.value)) {
        if (row.state != code) dao.update(row.copy(state = code, updatedAt = row.updatedAt))
        if (code != IncomingStates.UPLOADED) continue
        val file = fileOf(row) ?: continue
        if (!file.delete() && file.exists()) GalleryLog.w(TAG) { "copia locale di un arrivo non tolta: la togliera' la pulizia" }
        file.parentFile?.let { dir -> if (dir.listFiles().isNullOrEmpty()) dir.delete() }
      }
    }
  }

  /** Il file di una riga, solo dentro `incoming/<stage>/` con un nome dall'hash (mai un percorso che esce). */
  private fun fileOf(row: IncomingItemEntity): File? {
    if (!SAFE.matches(row.stageId) || !SAFE.matches(row.fileName)) return null
    val file = File(File(root, row.stageId), row.fileName)
    return file.takeIf { it.isFile }
  }

  /** IN-7: la copia locale di una riga di `incoming_item`, o null se non c'e' piu'. Per la miniatura e per "Riprova" di "Ricevuti in coda". */
  fun localFileOf(row: IncomingItemEntity): File? = fileOf(row)

  /**
   * IN-7, "Scarta": toglie la copia locale di [row] (e la cartella della condivisione se resta vuota). Solo il file di questa riga, dentro `incoming/`: il
   * NAS non c'entra. Vero se la copia non c'e' piu' (tolta adesso o gia' assente).
   */
  suspend fun discardLocal(row: IncomingItemEntity): Boolean = withContext(Dispatchers.IO) {
    val file = fileOf(row) ?: return@withContext true
    val removed = file.delete() || !file.exists()
    if (removed) file.parentFile?.let { dir -> if (dir.listFiles().isNullOrEmpty()) dir.delete() }
    removed
  }

  private class LocalFile(file: File) : IncomingLocalFile {
    private val source = LocalFileBytes(file)

    override val bytes: MediaBytes get() = source

    override fun close() = source.close()
  }

  private companion object {
    const val TAG = "BackupArrivi"
    val SAFE = Regex("^[A-Za-z0-9-]{1,128}$")
  }
}

/**
 * I byte di un file locale (la copia in `incoming/`), a intervalli, per il motore e per la lettura dei metadati (IN-8). Un file aperto una volta sola
 * e chiuso da chi lo usa. Le letture sono sincronizzate: il motore e `MediaMetadataRetriever` possono leggere da thread diversi.
 */
class LocalFileBytes(file: File) : MediaBytes, java.io.Closeable {
  private val raf = RandomAccessFile(file, "r")
  private val lock = Any()
  private val length = raf.length()

  override val size: Long get() = length

  override suspend fun read(range: LongRange): ByteArray = withContext(Dispatchers.IO) {
    synchronized(lock) {
      if (range.isEmpty() || range.first >= length || range.last < 0) return@synchronized ByteArray(0)
      val from = maxOf(range.first, 0L)
      val to = minOf(range.last, length - 1)
      val out = ByteArray((to - from + 1).toInt())
      raf.seek(from)
      raf.readFully(out)
      out
    }
  }

  override fun dataSource(): MediaDataSource = object : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = synchronized(lock) {
      if (position >= length) return -1
      val n = minOf(size.toLong(), length - position).toInt()
      raf.seek(position)
      raf.readFully(buffer, offset, n)
      n
    }

    override fun getSize(): Long = length

    override fun close() = Unit
  }

  override fun close() = raf.close()
}
