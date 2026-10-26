package dev.pampa.mycloudgallery.core.backup.upload

import dev.pampa.mycloudgallery.core.backup.contract.BackupGate
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupItem
import dev.pampa.mycloudgallery.core.backup.contract.BackupItemId
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupStore
import dev.pampa.mycloudgallery.core.backup.contract.IncomingDestination
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// Fase 7 (docs/fase7-progetto.md D12, §2.5 passo 4, E-SH1; riga P4 del §4.4: "`AlbumFolder` in `INCOMING`"): i media che stanno SOLO SUL TELEFONO
// si caricano direttamente nella cartella dell'album condiviso, con il cammino `INCOMING` del motore del backup (temporaneo proprio, rilettura
// completa, pubblicazione senza sostituire, "nome (2)", collegamento nel catalogo allo **stesso** media: D29). L'orchestratore della condivisione
// (core-albums) non vede core-backup: :app (P13) lega la sua porta `AlbumUploader` a [AlbumFolderUploads], dopo aver controllato la conferma della
// condivisione, e `TicketStatus` a [AlbumFolderUploads.status]/[AlbumFolderUploads.find].

/** Un media del telefono da caricare nella cartella di un album: dal catalogo locale (riga del telefono e timbro di adesso), mai dal NAS. */
class AlbumUploadItem(
  val mediaId: MediaId,
  val versionId: VersionId,
  val size: Long,
  val phone: PhoneRef,
  val expected: FileStamp,
  val quickHash: QuickHash?,
  /** Il nome del file (quello del telefono): dato dell'utente, mai nei log; uno non valido per il NAS diventa un nome derivato dall'id. */
  val fileName: String,
) {
  init {
    require(size > 0) { "Un file vuoto non e' un media da caricare." }
  }

  override fun toString(): String = "AlbumUploadItem($size B)"
}

/**
 * Il registro **durevole** dei caricamenti verso gli album, per chiave del trasferimento (la `TransferKey` dell'orchestratore): l'intenzione si
 * scrive **prima** di creare gli elementi del diario del backup, gli elementi dopo. Cosi' una chiave gia' vista non crea mai un secondo
 * caricamento e `find` ritrova il caricamento avviato da un processo morto. :app lo lega a una tabella (P1/P13); [MemoryAlbumUploadJournal] in prova.
 */
interface AlbumUploadJournal {
  suspend fun intent(key: String): AlbumUploadIntent?

  suspend fun recordIntent(key: String, intent: AlbumUploadIntent)

  suspend fun recordItems(key: String, items: List<BackupItemId>)
}

/** L'intenzione di un caricamento: dove, quali media (nell'ordine della richiesta) e, una volta creati, gli elementi del diario del backup. */
class AlbumUploadIntent(val share: String, val folder: SharePath, val items: List<AlbumUploadItem>, val created: List<BackupItemId>? = null) {
  override fun toString(): String = "AlbumUploadIntent(${items.size}, creati=${created != null})"
}

class MemoryAlbumUploadJournal : AlbumUploadJournal {
  private val lock = Any()
  private val byKey = LinkedHashMap<String, AlbumUploadIntent>()

  override suspend fun intent(key: String): AlbumUploadIntent? = synchronized(lock) { byKey[key] }

  override suspend fun recordIntent(key: String, intent: AlbumUploadIntent) {
    synchronized(lock) { byKey.putIfAbsent(key, intent) }
  }

  override suspend fun recordItems(key: String, items: List<BackupItemId>) {
    synchronized(lock) { byKey[key]?.let { byKey[key] = AlbumUploadIntent(it.share, it.folder, it.items, items) } }
  }
}

/** Perche' un caricamento verso un album non parte (niente si e' scritto nel diario del backup). */
enum class AlbumUploadRefusal {
  /** La destinazione non e' l'area comune del registro. */
  NOT_COMMON,

  /** La cartella non e' sotto la radice degli album ("Album condivisi") o e' l'area dell'app. */
  FOLDER_NOT_ALLOWED,

  /** La share e' in sola lettura per l'account, in prova (D12 del backup) o la famiglia non e' pronta. */
  WRITES_BLOCKED,
}

sealed interface AlbumUploadStart {
  data class Started(val ticket: String) : AlbumUploadStart

  data class Refused(val reason: AlbumUploadRefusal) : AlbumUploadStart
}

/** L'esito di un file finito (nell'ordine della richiesta). */
enum class AlbumUploadOutcome {
  /** Caricato, riletto, pubblicato e collegato allo stesso media nel catalogo. */
  ARRIVED,

  /** Lo stesso file c'era gia' al nome scelto (stessi byte): collegato, nessuna copia (E-SH12). */
  ALREADY_THERE,

  /** La versione del telefono e' cambiata prima del caricamento (E-B15): niente caricato. */
  SOURCE_CHANGED,

  /** Il file non e' piu' sul telefono. */
  SOURCE_GONE,

  FAILED,
}

sealed interface AlbumUploadStatus {
  data class InProgress(val done: Int, val total: Int) : AlbumUploadStatus

  /** Il cancello della share di destinazione chiude le scritture (congelamento, emergenza, pausa): si riprende da solo. */
  data class Waiting(val done: Int, val total: Int) : AlbumUploadStatus

  data class Ended(val outcomes: List<AlbumUploadOutcome>) : AlbumUploadStatus

  data object Unknown : AlbumUploadStatus
}

/**
 * I caricamenti dei media del telefono nella cartella di un album condiviso (E-SH1) come elementi `INCOMING` del diario del backup con la riga del
 * telefono: il motore ([SafeUploadEngine]) li legge dal telefono, li pubblica nella cartella (dentro `IncomingUploadPorts.albumRoot`) senza
 * sostituire e li collega allo **stesso** media. Idempotente per chiave ([AlbumUploadJournal]): la stessa chiave non crea mai un secondo
 * caricamento. Un caricamento avviato non si annulla: un file arriva intero o non arriva (un elemento in corso non si interrompe a meta').
 */
class AlbumFolderUploads(
  private val store: BackupStore,
  private val journal: AlbumUploadJournal,
  private val gate: BackupGate,
  private val ports: IncomingUploadPorts,
  /** L'area comune del registro, null se non c'e'. */
  private val commonShare: () -> String?,
) {

  /** Avvia (o ritrova, per [key]) il caricamento di [items] in [dest]. Chi chiama ha gia' controllato la conferma della condivisione. */
  suspend fun start(key: String, items: List<AlbumUploadItem>, dest: IncomingDestination.AlbumFolder): AlbumUploadStart {
    if (journal.intent(key) != null) {
      ensureCreated(key)
      return AlbumUploadStart.Started(key)
    }
    val common = commonShare() ?: return AlbumUploadStart.Refused(AlbumUploadRefusal.NOT_COMMON)
    if (!dest.share.equals(common, ignoreCase = true)) return AlbumUploadStart.Refused(AlbumUploadRefusal.NOT_COMMON)
    when (gate.stateOf(dest.share)) {
      BackupGateState.OPEN, BackupGateState.PAUSED, BackupGateState.FROZEN, BackupGateState.WRITES_STOPPED -> Unit
      BackupGateState.TEST_ONLY, BackupGateState.READ_ONLY_AREA, BackupGateState.NOT_READY, BackupGateState.NO_PRIVATE_AREA ->
        return AlbumUploadStart.Refused(AlbumUploadRefusal.WRITES_BLOCKED)
    }
    if (items.isEmpty() || items.any { !ports.allowsAlbum(plannedOf(dest.folder, it)) }) return AlbumUploadStart.Refused(AlbumUploadRefusal.FOLDER_NOT_ALLOWED)
    journal.recordIntent(key, AlbumUploadIntent(dest.share, dest.folder, items))
    ensureCreated(key)
    GalleryLog.d(TAG) { "caricamento verso un album: ${items.size} media" }
    return AlbumUploadStart.Started(key)
  }

  /** Gli elementi del diario del backup per [key]: creati adesso se l'intenzione c'e' e gli elementi non ancora (un processo morto in mezzo). */
  private suspend fun ensureCreated(key: String): List<BackupItem> {
    val intent = journal.intent(key) ?: return emptyList()
    intent.created?.let { ids -> return ids.mapNotNull { store.item(it) } }
    // Idempotente: per la stessa versione, nella stessa share e cartella, un elemento aperto c'e' gia' (R14) e si ritrova.
    val created = intent.items.map { item ->
      store.createIncoming(
        NewBackupItem(
          kind = UploadKind.INCOMING,
          mediaId = item.mediaId,
          versionId = item.versionId,
          size = item.size,
          phone = item.phone,
          expected = item.expected,
          share = intent.share,
          planned = plannedOf(intent.folder, item),
          quickHash = item.quickHash,
        ),
      )
    }
    journal.recordItems(key, created.map { it.id })
    return created
  }

  /** Il biglietto del caricamento con chiave [key], se c'e' (anche avviato da un processo morto). */
  suspend fun find(key: String): String? = if (journal.intent(key) != null) key else null

  suspend fun status(ticket: String): AlbumUploadStatus {
    journal.intent(ticket) ?: return AlbumUploadStatus.Unknown
    val items = ensureCreated(ticket)
    if (items.isEmpty()) return AlbumUploadStatus.Unknown
    val done = items.count { it.state.terminal }
    if (done == items.size) return AlbumUploadStatus.Ended(items.map(::outcomeOf))
    val waiting = when (gate.stateOf(items.first().share)) {
      BackupGateState.PAUSED, BackupGateState.FROZEN, BackupGateState.WRITES_STOPPED -> true
      else -> false
    }
    return if (waiting) AlbumUploadStatus.Waiting(done, items.size) else AlbumUploadStatus.InProgress(done, items.size)
  }

  private fun outcomeOf(item: BackupItem): AlbumUploadOutcome = when (item.state) {
    UploadState.DONE -> AlbumUploadOutcome.ARRIVED
    UploadState.LINKED_EXISTING -> AlbumUploadOutcome.ALREADY_THERE
    // GONE: il file non c'e' piu' sul telefono (con il segno "perso"), oppure la versione e' cambiata prima del caricamento (E-B15).
    UploadState.GONE -> if (item.problem == BackupProblemCode.LOST_BEFORE_BACKUP) AlbumUploadOutcome.SOURCE_GONE else AlbumUploadOutcome.SOURCE_CHANGED
    else -> AlbumUploadOutcome.FAILED
  }

  companion object {
    private const val TAG = "BackupAlbum"

    /** Il percorso previsto di [item] nella cartella [folder]: il nome del telefono, o uno derivato dall'id se non e' valido per il NAS. */
    fun plannedOf(folder: SharePath, item: AlbumUploadItem): SharePath {
      val name = item.fileName.takeIf { SharePath.isValidComponent(it) } ?: "${item.mediaId.value}.jpg"
      return folder.child(name)
    }
  }
}
