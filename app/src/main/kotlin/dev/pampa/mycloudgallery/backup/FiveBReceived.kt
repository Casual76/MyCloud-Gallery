package dev.pampa.mycloudgallery.backup

import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.pampa.mycloudgallery.core.backup.contract.BackupGate
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupItemId
import dev.pampa.mycloudgallery.core.backup.contract.BackupProblemCode
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupSummary
import dev.pampa.mycloudgallery.core.backup.contract.StopBy
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.WaitReason
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.entity.IncomingItemEntity
import dev.pampa.mycloudgallery.core.database.entity.IncomingQueueRow
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedDiscard
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedId
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedItem
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedPhase
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedQueue
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedRetry
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedStatus
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

// "RICEVUTI IN CODA" sul grafo vero (correzioni 5B, IN-7 della verifica funzionale; docs/fase5-progetto.md §2.7, D24, E-X8). Un file che un'altra app
// condivide nasce con un media **invisibile** (presenza `none`): finche' non e' sul NAS la Libreria non lo mostra, e questa e' l'unica strada per
// sapere a che punto e'. Qui l'elenco si legge dal diario degli arrivi (`incoming_item`, unito all'elemento del diario del backup), "Riprova" rimette in
// coda con gli **stessi cancelli** di "Salva sul NAS" (D12, R2, D13, la share, il congelamento: li guarda `AppIncomingQueue.blockOf`) e "Scarta" toglie
// la riga e la copia sul telefono e **non tocca mai il NAS**. Nei log solo codici.

/**
 * Lo stato di un file ricevuto in una parola, da cio' che il diario e il motore dicono adesso. Funzioni pure (si provano su JVM).
 */
internal object ReceivedStates {

  /**
   * Il punto di un file ricevuto. Per un file ancora in coda decide, nell'ordine: il cancello della **sua** share (la prova, il sola lettura, il
   * congelamento, la pausa dell'utente: sono cio' che lo ferma davvero), poi il motore (se sta girando e l'elemento e' a meta' cammino), poi il
   * problema ricordato dal giro (NAS pieno, quota, password, NAS spento) o l'attesa delle regole (rete). Un file che il motore ha lasciato (`FAILED`,
   * `GONE`) ha il suo problema.
   */
  fun of(row: IncomingQueueRow, backup: BackupStatus, gate: BackupGateState?, hasLocalCopy: Boolean): ReceivedStatus {
    when (row.item.state) {
      IncomingStates.GONE -> return ReceivedStatus.COPY_MISSING
      IncomingStates.FAILED -> return when (row.itemProblem) {
        BackupProblemCode.UNREADABLE.code -> if (hasLocalCopy) ReceivedStatus.COPY_CHANGED else ReceivedStatus.COPY_MISSING
        BackupProblemCode.VERIFY_FAILED.code -> ReceivedStatus.NOT_INTACT
        BackupProblemCode.UNEXPECTED.code -> ReceivedStatus.FAILED
        // Senza un problema ricordato l'unica via a `FAILED` e' una destinazione fuori dalle radici ammesse.
        null -> ReceivedStatus.FOLDER_REFUSED
        else -> ReceivedStatus.FAILED
      }
    }
    when (gate) {
      BackupGateState.TEST_ONLY -> return ReceivedStatus.TRIAL
      BackupGateState.READ_ONLY_AREA -> return ReceivedStatus.FOLDER_LOCKED
      BackupGateState.FROZEN, BackupGateState.WRITES_STOPPED -> return ReceivedStatus.FROZEN
      BackupGateState.PAUSED -> return ReceivedStatus.PAUSED
      BackupGateState.NOT_READY, BackupGateState.NO_PRIVATE_AREA -> return ReceivedStatus.UNAVAILABLE
      BackupGateState.OPEN, null -> Unit
    }
    val running = backup is BackupStatus.Running
    when (row.itemState) {
      UploadState.UPLOADING.code -> if (running) return ReceivedStatus.UPLOADING
      UploadState.UPLOADED.code, UploadState.VERIFIED.code, UploadState.PUBLISHING.code, UploadState.PUBLISHED.code, UploadState.LINKED.code ->
        if (running) return ReceivedStatus.VERIFYING
    }
    return when (backup) {
      is BackupStatus.Problem -> when (backup.code) {
        BackupProblemCode.NAS_FULL -> ReceivedStatus.NAS_FULL
        BackupProblemCode.QUOTA -> ReceivedStatus.QUOTA
        BackupProblemCode.AUTH -> ReceivedStatus.PASSWORD
        BackupProblemCode.NAS_UNREACHABLE -> ReceivedStatus.NETWORK
        BackupProblemCode.READ_ONLY_AREA -> ReceivedStatus.FOLDER_LOCKED
        BackupProblemCode.FROZEN, BackupProblemCode.WRITES_STOPPED -> ReceivedStatus.FROZEN
        BackupProblemCode.TEST_ONLY -> ReceivedStatus.TRIAL
        else -> ReceivedStatus.QUEUED
      }
      is BackupStatus.Waiting -> when (backup.reason) {
        WaitReason.NAS, WaitReason.WIFI, WaitReason.HOME_WIFI, WaitReason.DATA_CAP -> ReceivedStatus.NETWORK
        // D-13: un file occupato sul NAS e' un'attesa breve: il file ricevuto resta in coda e riparte da solo.
        WaitReason.NAS_BUSY -> ReceivedStatus.QUEUED
        else -> ReceivedStatus.QUEUED
      }
      is BackupStatus.Stopped -> if (backup.by == StopBy.USER) ReceivedStatus.PAUSED else ReceivedStatus.QUEUED
      BackupStatus.TestOnly -> ReceivedStatus.TRIAL
      is BackupStatus.NotConfigured -> ReceivedStatus.UNAVAILABLE
      else -> ReceivedStatus.QUEUED
    }
  }

  /**
   * Si puo' scartare? **Solo prima che i byte siano completi sul NAS**: da `UPLOADED` in poi il motore finisce da solo (verifica, pubblica, collega) e
   * il file arriverebbe sul NAS lo stesso, quindi non e' "scartabile" ma "caricato". Con il motore in giro ([running]) un file gia' letto (`HASHED`: i byte
   * possono essere in memoria del motore, che li carica senza la copia locale) o a meta' caricamento (`UPLOADING`) sta salendo adesso: si scarta dopo.
   * Uno ancora `PLANNED` aspetta il suo turno e si scarta (il motore, quando lo raggiunge, non trova la copia e lo chiude come sparito). I file che il
   * motore ha lasciato (`FAILED`, `GONE`) si scartano sempre.
   */
  fun canDiscard(row: IncomingQueueRow, running: Boolean): Boolean {
    if (row.item.state != IncomingStates.QUEUED) return true
    return when (row.itemState) {
      null, UploadState.PLANNED.code, UploadState.FAILED.code, UploadState.GONE.code -> true
      UploadState.HASHED.code, UploadState.UPLOADING.code -> !running
      else -> false
    }
  }
}

/**
 * La coda dei file ricevuti per la pagina Backup. Legge `incoming_item` (con l'elemento del diario) e lo stato del motore; le azioni sono dell'utente,
 * mai automatiche. [onChanged] avvisa chi mostra lo stato del backup (il piede della Libreria conta i problemi) che qualcosa e' cambiato.
 */
class AppReceivedQueue(
  private val database: GalleryDatabase,
  private val queue: AppIncomingQueue,
  private val files: AppIncomingLocalFiles,
  private val gate: BackupGate,
  /** Lo stato del backup: si chiede al primo uso, quando il grafo del backup e' gia' nato. */
  private val backupStatus: () -> StateFlow<BackupStatus>,
  private val scope: CoroutineScope,
  private val onChanged: () -> Unit = {},
) : ReceivedQueue {

  private val dao get() = database.incomingDao()

  override val items: StateFlow<List<ReceivedItem>> by lazy {
    // Il motore che avanza cambia lo stato a ogni passo (contatori, byte): all'elenco serve solo cio' che decide una frase, non quanti file sono fatti.
    val status = backupStatus().map { it.coarse() }.distinctUntilChanged()
    combine(dao.observeQueue(IncomingStates.LISTED), status) { rows, backup -> rows.map { toItem(it, backup) }.sortedBy { it.phase.ordinal } }
      .distinctUntilChanged()
      .flowOn(Dispatchers.Default)
      .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())
  }

  /** Quanti file non si sono caricati e aspettano una scelta ("Riprova" o "Scarta"): il piede della Libreria li conta. */
  suspend fun problemCount(): Int = withContext(Dispatchers.IO) { dao.countInStates(IncomingStates.PROBLEMS) }

  /** Lo stesso conto come flusso: la notifica dei problemi lo segue. */
  fun problems(): Flow<Int> = dao.observeCountInStates(IncomingStates.PROBLEMS).distinctUntilChanged()

  private fun toItem(row: IncomingQueueRow, backup: BackupStatus): ReceivedItem {
    val item = row.item
    val hasCopy = files.localFileOf(item) != null
    val gateState = item.destShare?.let { gate.stateOf(it) }
    val status = ReceivedStates.of(row, backup, gateState, hasCopy)
    val running = backup is BackupStatus.Running
    return ReceivedItem(
      id = ReceivedId(item.incomingId),
      status = status,
      folder = item.destFolder.orEmpty(),
      isVideo = item.mime.startsWith("video/", ignoreCase = true),
      size = item.size,
      canRetry = status.phase != ReceivedPhase.UPLOADING && status != ReceivedStatus.COPY_MISSING,
      canDiscard = status.phase != ReceivedPhase.UPLOADING && ReceivedStates.canDiscard(row, running),
    )
  }

  override suspend fun thumbnail(id: ReceivedId, sidePx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    val row = dao.get(id.value) ?: return@withContext null
    val file = files.localFileOf(row) ?: return@withContext null
    val size = Size(sidePx.coerceAtLeast(1), sidePx.coerceAtLeast(1))
    try {
      val bitmap: Bitmap = if (row.mime.startsWith("video/", ignoreCase = true)) {
        ThumbnailUtils.createVideoThumbnail(file, size, null)
      } else {
        ThumbnailUtils.createImageThumbnail(file, size, null)
      }
      bitmap.asImageBitmap()
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      null
    } catch (e: RuntimeException) {
      GalleryLog.w(TAG) { "miniatura di un arrivo non letta (${e.javaClass.simpleName})" }
      null
    }
  }

  override suspend fun retry(id: ReceivedId): ReceivedRetry {
    val row = withContext(Dispatchers.IO) { dao.queueRow(id.value) } ?: return ReceivedRetry.Unknown
    val item = row.item
    if (item.state == IncomingStates.UPLOADED) return ReceivedRetry.AlreadySaved
    // Gli stessi cancelli di "Salva sul NAS": se uno vieta, non cambia niente e l'utente sa quale.
    val dest = queue.destinationOf(item) ?: return ReceivedRetry.Blocked(ReceivedStatus.FOLDER_LOCKED)
    queue.blockOf(dest)?.let { return ReceivedRetry.Blocked(it) }
    val itemDone = row.itemState == UploadState.DONE.code || row.itemState == UploadState.LINKED_EXISTING.code
    if (item.state == IncomingStates.QUEUED && itemDone) {
      // Il motore ha finito ma la riga non l'ha saputo (morte del processo fra i due passi): si sistema adesso, e il file e' sul NAS.
      val state = UploadState.entries.first { it.code == row.itemState }
      item.backupItemId?.let { files.finished(BackupItemId(it), state) }
      onChanged()
      return ReceivedRetry.AlreadySaved
    }
    // Un file in coda il cui elemento del diario e' ancora aperto non ha bisogno di una copia nuova: basta chiedere il lavoro (la sua copia locale serve
    // finche' il motore la legge; da `UPLOADED` in poi rilegge dal NAS).
    val open = item.state == IncomingStates.QUEUED && row.itemState != null && row.itemState !in TERMINAL_ITEM_STATES
    if (open) {
      if (row.itemState in READS_LOCAL_COPY && files.localFileOf(item) == null) {
        markGone(item)
        onChanged()
        return ReceivedRetry.NoCopy
      }
      queue.askJob(item.size)
      onChanged()
      return ReceivedRetry.Queued
    }
    // `FAILED`, `GONE`, o in coda con un elemento che ha gia' rinunciato: l'elemento terminale non si riapre, ne nasce uno nuovo dalla copia locale.
    val file = files.localFileOf(item)
    if (file == null) {
      if (item.state != IncomingStates.GONE) markGone(item)
      onChanged()
      return ReceivedRetry.NoCopy
    }
    val outcome = queue.requeue(item, dest, file)
    if (outcome != ReceivedRetry.Queued) return outcome
    queue.askJob(item.size)
    onChanged()
    return ReceivedRetry.Queued
  }

  override suspend fun discard(id: ReceivedId): ReceivedDiscard {
    val row = withContext(Dispatchers.IO) { dao.queueRow(id.value) } ?: return ReceivedDiscard.Unknown
    val item = row.item
    if (item.state == IncomingStates.UPLOADED) return ReceivedDiscard.AlreadySaved
    val running = backupStatus().value is BackupStatus.Running
    if (!ReceivedStates.canDiscard(row, running)) {
      // Sta salendo adesso, oppure e' gia' sul NAS (o sta per essere pubblicato): non e' "scartabile", e' "caricato". Il NAS non si tocca.
      return if (row.itemState == UploadState.UPLOADING.code || row.itemState == UploadState.HASHED.code) ReceivedDiscard.Uploading else ReceivedDiscard.AlreadySaved
    }
    // Prima la copia locale, poi la riga: se la copia non se ne va la riga resta e si puo' riprovare (mai una copia orfana che nessuna riga cita).
    if (!files.discardLocal(item)) return ReceivedDiscard.Unknown
    withContext(Dispatchers.IO) { dao.delete(item.incomingId) }
    onChanged()
    return ReceivedDiscard.Discarded
  }

  private suspend fun markGone(item: IncomingItemEntity) = withContext(Dispatchers.IO) {
    dao.update(item.copy(state = IncomingStates.GONE))
  }

  /** Lo stato del backup senza i numeri che cambiano a ogni passo: l'elenco si rifa' solo se cambia una frase. */
  private fun BackupStatus.coarse(): BackupStatus = when (this) {
    is BackupStatus.Running -> BackupStatus.Running(kind, 0, 0, 0L, 0L, null, trigger)
    is BackupStatus.Waiting -> BackupStatus.Waiting(0, 0L, reason)
    is BackupStatus.Problem -> BackupStatus.Problem(code, 0)
    is BackupStatus.UpToDate -> BackupStatus.UpToDate(0, null)
    is BackupStatus.FirstBackupReady -> BackupStatus.FirstBackupReady(BackupSummary.EMPTY)
    is BackupStatus.NotConfigured, BackupStatus.TestOnly, is BackupStatus.Stopped -> this
  }

  private companion object {
    const val TAG = "BackupArrivi"
    const val STOP_TIMEOUT_MS = 5_000L

    /** Gli stati dell'elemento del diario che hanno finito (o rinunciato): un file "in coda" con uno di questi ha bisogno di un elemento nuovo. */
    val TERMINAL_ITEM_STATES: Set<String> = setOf(UploadState.DONE.code, UploadState.LINKED_EXISTING.code, UploadState.GONE.code, UploadState.FAILED.code)

    /** Gli stati in cui il motore legge ancora la copia locale: dopo, rilegge dal NAS e la copia sul telefono non serve piu'. */
    val READS_LOCAL_COPY: Set<String?> = setOf(UploadState.PLANNED.code, UploadState.HASHED.code, UploadState.UPLOADING.code)
  }
}

/**
 * IN-7: chi avvisa l'utente quando un file ricevuto non si carica, anche se ha chiuso l'app. Segue il conto dei file con un problema ([problems]):
 * - la **prima lettura** del processo e' il punto di partenza e non avvisa (i problemi di prima li dicono il piede della Libreria e la pagina Backup);
 * - un conto che **sale** con l'app in secondo piano posta "N file ricevuti non salvati" e chiede che, al ritorno, l'app porti l'utente alla pagina
 *   Backup ([openRequest]); con l'app davanti non serve (lo vede il piede, e la pagina e' a un tocco);
 * - un conto che scende aggiorna il numero della notifica che questo processo ha postato; a zero la notifica se ne va.
 * Solo numeri.
 */
class IncomingProblemWatcher(
  private val problems: Flow<Int>,
  private val notices: dev.pampa.mycloudgallery.notify.IncomingProblemNotices,
  private val foreground: () -> Boolean,
  private val openRequest: () -> Unit,
) {

  suspend fun run() {
    var last: Int? = null
    var posted = false
    problems.collect { count ->
      val before = last
      last = count
      when {
        count <= 0 -> {
          notices.clear()
          posted = false
        }
        before == null -> Unit
        count > before -> if (!foreground()) {
          notices.problems(count)
          posted = true
          openRequest()
        }
        posted -> notices.problems(count)
      }
    }
  }
}
