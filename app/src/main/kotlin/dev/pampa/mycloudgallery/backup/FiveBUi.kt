package dev.pampa.mycloudgallery.backup

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.backup.contract.EditBegin
import dev.pampa.mycloudgallery.core.backup.contract.EditOutcome
import dev.pampa.mycloudgallery.core.backup.contract.EditSession
import dev.pampa.mycloudgallery.core.backup.contract.EditTarget
import dev.pampa.mycloudgallery.core.backup.contract.EditUnavailable
import dev.pampa.mycloudgallery.core.backup.contract.NasVersionPlan
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskId
import dev.pampa.mycloudgallery.core.backup.free.EverywhereArea
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.metadata.write.ExifMetadataWriter
import dev.pampa.mycloudgallery.core.model.ExifOrientation
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nasops.contract.NasItem
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperations
import dev.pampa.mycloudgallery.feature.backup.free.PhoneOnlyAssessor
import dev.pampa.mycloudgallery.feature.backup.free.PhoneSystemDialogs
import dev.pampa.mycloudgallery.feature.backup.tasks.PendingTaskActions
import dev.pampa.mycloudgallery.feature.backup.versions.CorrectionMode
import dev.pampa.mycloudgallery.feature.backup.versions.CorrectionSubject
import dev.pampa.mycloudgallery.feature.backup.versions.VersionActions
import dev.pampa.mycloudgallery.feature.backup.versions.VersionsStart
import dev.pampa.mycloudgallery.feature.integrations.edit.EditIntents
import dev.pampa.mycloudgallery.feature.viewer.menu.BackupMediaActions
import dev.pampa.mycloudgallery.feature.viewer.menu.BackupMenuAction
import dev.pampa.mycloudgallery.feature.viewer.menu.BackupMenuCapabilities
import dev.pampa.mycloudgallery.feature.viewer.menu.BackupNotice
import dev.pampa.mycloudgallery.feature.viewer.menu.BackupShareUi
import dev.pampa.mycloudgallery.feature.viewer.menu.EverywhereSummary
import dev.pampa.mycloudgallery.feature.viewer.menu.NasActionTarget
import dev.pampa.mycloudgallery.feature.viewer.menu.NasLocationInfo
import dev.pampa.mycloudgallery.feature.viewer.menu.NasMediaActions
import dev.pampa.mycloudgallery.feature.viewer.menu.NasAreaScope
import dev.pampa.mycloudgallery.integrations.IntegrationsGraph
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Un foglio della 5B che `:app` apre alla radice, sopra il viewer (i feature non si conoscono: il viewer chiede, `:app` mostra). */
sealed interface FiveBSheet {
  data class Versions(val mediaId: MediaId, val start: VersionsStart) : FiveBSheet

  class Correction(val subject: CorrectionSubject, val mode: CorrectionMode) : FiveBSheet

  /**
   * "Elimina solo dal telefono". [fromViewer] (correzioni 5B, LS-1): dal viewer, per un media che non risulta al sicuro sul NAS il foglio offre il
   * cestino di Android dopo l'avviso; [isVideo] sceglie le parole.
   */
  data class PhoneOnly(val ids: Set<MediaId>, val fromViewer: Boolean = false, val isVideo: Boolean = false) : FiveBSheet
}

/**
 * Correzioni 5B (LS-6): una richiesta di aprire "Libera spazio" con la sua selezione ([selection] null = tutto il sicuro). La rotta la prende una
 * volta, entrando, e se ne ricorda il numero ([serial]): dopo una ricreazione dell'activity la ritrova qui; dopo la morte del processo, se la
 * selezione non e' stata salvata nella rotta, la pagina si chiude invece di allargarsi a "tutto il sicuro".
 */
class FreeSpaceRequest(val serial: Long, val selection: Set<MediaId>?) {
  override fun toString(): String = "FreeSpaceRequest(#$serial, ${selection?.size ?: "tutti"})"
}

/** Correzioni 5B (LS-1, LS-14): il cestino di Android per delle righe del telefono, da lanciare dall'activity (una volta sola). */
class PhoneTrashRequest(val sender: IntentSender, val serial: Long) {
  override fun toString(): String = "PhoneTrashRequest(#$serial)"
}

/**
 * Le copie del NAS di versioni **precedenti** da mettere nel cestino del NAS con il foglio "Elimina…" della 4B (D2: il backup non toglie mai
 * niente da solo; la conferma nasce dal tocco nel foglio): "Da decidere" (l'originale di un video modificato) ed "Elimina le intermedie…".
 * [taskId]: l'attivita' "Da decidere" che si chiude quando l'operazione parte.
 */
class VersionDeleteRequest(val target: NasActionTarget, val taskId: PendingTaskId?, val serial: Long) {
  override fun toString(): String = "VersionDeleteRequest(${target.everywhereItems.size} copie)"
}

/**
 * Un intento che `:app` lancia dall'activity (serve un'activity viva): l'editor di "Modifica con…". La scelta dell'app di "Condividi" la lancia
 * `ShareHostEffect` (correzioni 5B: anche senza backup).
 */
sealed interface FiveBLaunch {
  val serial: Long

  class Edit(val intent: Intent, val session: EditSession, override val serial: Long, val isVideo: Boolean = false) : FiveBLaunch
}

/**
 * Le porte delle **schermate della 5B** (P12) legate al grafo vero (P14): i fogli alla radice (Versioni, Correggi, "Elimina solo dal telefono"),
 * "Libera spazio" con la selezione, "Da decidere" ed "Elimina le intermedie…" (foglio "Elimina…" della 4B), e le voci del backup nel viewer
 * ([mediaActions]: Versioni, Ripristina originale, Correggi, Ruota, "Modifica con…", "Condividi" con o senza posizione, "Ovunque"). Uno per
 * processo; nessuna voce e' un null-object: ogni capacita' dichiarata ha il suo effetto (lo prova `FiveBButtonsTest`).
 *
 * **Niente parte da solo**: ogni metodo e' la risposta a un tocco; le conferme nascono nei fogli (feature-backup, feature-viewer); gli
 * intenti verso le altre app li lancia l'activity ([FiveBLaunch]). Nei log solo codici.
 */
class FiveBUi(
  private val context: Context,
  private val backup: BackupGraph,
  private val catalog: suspend () -> MediaCatalog?,
  private val operations: () -> NasOperations?,
  private val monotonicMs: () -> Long,
  private val integrations: IntegrationsGraph,
  private val scope: CoroutineScope,
) {

  private val fiveB get() = backup.fiveB
  private var serial = 0L

  private fun next(): Long = synchronized(this) { ++serial }

  // --- Porte di feature-backup ----------------------------------------------------------------------------------------------------

  /** I dialoghi di sistema (Android 11+), dietro la recinzione di D13. */
  val systemDialogs: PhoneSystemDialogs get() = fiveB.systemDialogs

  /** "Elimina solo dal telefono": dal catalogo locale (D22, E-B14). */
  val phoneOnlyAssessor: PhoneOnlyAssessor = PhoneOnlyAssessor { ids -> fiveB.assessPhoneOnly(ids) }

  /** "Da decidere" → "Elimina l'originale": il foglio "Elimina…" della 4B sulle copie del NAS di quella versione; l'attivita' si chiude quando parte. */
  val pendingTaskActions: PendingTaskActions = PendingTaskActions { task -> requestVersionDelete(task.mediaId, listOf(task.versionId), task.id) }

  /** "Elimina le intermedie…" dal foglio Versioni; "Vedi" una versione nel viewer non c'e' (il viewer mostra solo l'attuale): la voce non compare. */
  val versionActions: VersionActions = object : VersionActions {
    override val canDeleteIntermediates: Boolean get() = true

    override fun deleteIntermediates(mediaId: MediaId, versions: List<VersionId>) = requestVersionDelete(mediaId, versions, null)
  }

  // --- Fogli alla radice -------------------------------------------------------------------------------------------------------

  private val sheetFlow = MutableStateFlow<FiveBSheet?>(null)

  val sheet: StateFlow<FiveBSheet?> = sheetFlow.asStateFlow()

  fun dismissSheet() {
    sheetFlow.value = null
  }

  /** "Libera spazio" con una selezione (dalla Libreria o da "Elimina solo dal telefono"): la pagina la legge entrando. */
  @Volatile
  var freeSpaceSelection: Set<MediaId>? = null
    private set

  private val freeSpaceFlow = MutableStateFlow(0L)

  /** Ogni valore nuovo chiede alla navigazione di aprire "Libera spazio" (con [freeSpaceSelection]). */
  val freeSpaceRequests: StateFlow<Long> = freeSpaceFlow.asStateFlow()

  /** LS-6: la richiesta che la prossima rotta "Libera spazio" prendera' entrando, e le ultime fatte (per ritrovarle dopo una ricreazione). */
  @Volatile
  private var pendingRequest: FreeSpaceRequest? = null
  private val recentRequests = java.util.concurrent.ConcurrentHashMap<Long, FreeSpaceRequest>()

  fun openFreeSpace(selection: Set<MediaId>?) {
    val request = FreeSpaceRequest(next(), selection?.takeIf { it.isNotEmpty() })
    freeSpaceSelection = request.selection
    recentRequests[request.serial] = request
    recentRequests.keys.sorted().dropLast(KEPT_REQUESTS).forEach { recentRequests.remove(it) }
    pendingRequest = request
    freeSpaceFlow.value = request.serial
  }

  /**
   * LS-6: la rotta "Libera spazio" che si apre prende la richiesta che l'ha aperta, una volta. Null = nessuna (aperta da altrove, per esempio dal
   * problema "Memoria del telefono quasi piena"): tutto il sicuro.
   */
  fun takeFreeSpaceRequest(): FreeSpaceRequest? = synchronized(this) { pendingRequest.also { pendingRequest = null } }

  /** LS-6: una richiesta di prima (la rotta ricreata la ritrova); null dopo la morte del processo. */
  fun freeSpaceRequest(serial: Long): FreeSpaceRequest? = recentRequests[serial]

  // --- Correzioni 5B (LS-2): il giro di "Libera spazio" fuori dalla pagina -------------------------------------------------------

  /** Il giro del processo: la notifica "Pronto" chiede di riaprire la pagina ([FiveBFreeSpaceRuns.openRequest]). */
  val freeRuns: FiveBFreeSpaceRuns get() = fiveB.freeRuns

  // --- Correzioni 5B (LS-1, LS-14): il cestino di Android chiesto dalla radice -------------------------------------------------

  private val trashFlow = MutableStateFlow<PhoneTrashRequest?>(null)

  /** Il dialogo del cestino di Android da lanciare dall'activity (`FiveBHostEffect`). */
  val trashRequests: StateFlow<PhoneTrashRequest?> = trashFlow.asStateFlow()

  /** L'activity ha lanciato [request]: non si rilancia. */
  fun trashLaunched(request: PhoneTrashRequest) {
    trashFlow.compareAndSet(request, null)
  }

  /**
   * LS-1, decisione dell'utente "Elimina con avviso": dal foglio "Elimina solo dal telefono", dopo l'avviso "non e' sul NAS" e il tocco su "Elimina
   * dal telefono", **come nella 4B**: il dialogo del sistema per il cestino di Android (30 giorni, si recupera da Eliminati di recente) per le
   * righe del telefono di [ids]. L'app non toglie niente da sola: il permesso lo da' l'utente nel dialogo. Nessuna copia del NAS si tocca.
   */
  fun trashFromPhone(ids: Set<MediaId>) {
    scope.launch {
      val refs = phoneCopies(ids)
      if (refs.isEmpty()) return@launch sayHere(R.string.fiveb_trash_nothing)
      askTrash(refs)
    }
  }

  private fun askTrash(refs: List<MediaRef.Phone>) {
    val sender = try {
      backup.fiveB.trashRequest(refs)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "richiesta del cestino non riuscita (${e.javaClass.simpleName})" }
      null
    }
    if (sender == null) sayHere(R.string.fiveb_trash_failed) else trashFlow.value = PhoneTrashRequest(sender, next())
  }

  /** Le righe del telefono presenti di [ids], dal catalogo (tutte le copie del telefono di ogni media). */
  private suspend fun phoneCopies(ids: Set<MediaId>): List<MediaRef.Phone> {
    val cat = catalog() ?: return emptyList()
    return ids.flatMap { id ->
      val media = cat.media(id) ?: return@flatMap emptyList()
      val mime = cat.versionMetadata(media.currentVersionId)?.mimeType
      media.locations.filter { it.state == LocationState.PRESENT }.mapNotNull { loc ->
        val phone = loc.location as? Location.Phone ?: return@mapNotNull null
        val rowId = phone.mediaStoreId ?: return@mapNotNull null
        MediaRef.Phone(phone.volume, rowId, mime)
      }
    }.distinct()
  }

  // --- "Elimina…" delle versioni precedenti -------------------------------------------------------------------------------------

  private val deleteFlow = MutableStateFlow<VersionDeleteRequest?>(null)

  val deleteRequest: StateFlow<VersionDeleteRequest?> = deleteFlow.asStateFlow()

  private fun requestVersionDelete(mediaId: MediaId, versions: List<VersionId>, taskId: PendingTaskId?) {
    scope.launch {
      val target = versionCopiesTarget(mediaId, versions)
      if (target == null) {
        // VE-6: la frase giusta ("niente da eliminare", non "non posso aprire la modifica") e dove l'utente e' (non nel viewer piu' tardi).
        sayHere(R.string.fiveb_delete_nothing)
        return@launch
      }
      deleteFlow.value = VersionDeleteRequest(target, taskId, next())
    }
  }

  /**
   * VE-6: il foglio "Elimina…" non parte (il NAS non risponde, l'area e' in sola lettura...): prima restava aperto con il tasto spento e il motivo
   * non si vedeva da nessuna parte. Adesso il motivo si dice e la richiesta si toglie.
   */
  fun deleteRefused(request: VersionDeleteRequest, text: String) {
    if (deleteFlow.value === request) deleteFlow.value = null
    rootNoticeFlow.value = text
  }

  /** Le azioni del foglio "Elimina…" per una richiesta: il bersaglio e' gia' deciso (le copie di quelle versioni), le operazioni sono della 4B. */
  fun deleteActions(request: VersionDeleteRequest): NasMediaActions? {
    val ops = operations() ?: return null
    return object : NasMediaActions {
      override val operations: NasOperations get() = ops

      override fun monotonicMs(): Long = this@FiveBUi.monotonicMs()

      override suspend fun target(ref: MediaRef.Nas): NasActionTarget = request.target
    }
  }

  /** L'operazione e' partita: l'attivita' "Da decidere" (se c'e') si chiude (`PendingTasks.complete`). */
  fun deleteStarted(request: VersionDeleteRequest) {
    if (deleteFlow.value === request) deleteFlow.value = null
    // VE-6: la frase si vede adesso, alla radice (prima la mostrava solo il viewer, alla prossima foto aperta).
    sayHere(R.string.fiveb_delete_started)
    val task = request.taskId ?: return
    scope.launch { runCatching { fiveB.pendingTasks.complete(task) } }
  }

  /** Le copie del NAS (dell'account, D13 compreso) delle versioni [versions] di [mediaId], mai quella attuale. Null se non ce ne sono. */
  private suspend fun versionCopiesTarget(mediaId: MediaId, versions: List<VersionId>): NasActionTarget? {
    val plan = fiveB.everywhere(mediaId) ?: return null
    if (plan.blocked != null) return null
    val wanted = versions.toSet()
    val copies = plan.nasCopies.filter { it.versionId in wanted && !it.isCurrent }
    val first = copies.firstOrNull() ?: return null
    fun itemOf(c: dev.pampa.mycloudgallery.core.backup.free.EverywhereCopy) =
      NasItem(share = c.share, path = c.path, isFolder = false, size = c.stamp.size, stamp = c.stamp)
    val media = catalog()?.media(mediaId)
    return NasActionTarget(
      item = itemOf(first),
      scope = if (first.area == EverywhereArea.COMMON) NasAreaScope.COMMON else NasAreaScope.PRIVATE,
      writable = true,
      onPhone = false,
      isVideo = media?.kind == MediaKind.VIDEO,
      others = copies.drop(1).map { c ->
        NasLocationInfo(
          kind = if (c.area == EverywhereArea.COMMON) NasLocationInfo.Kind.NAS_COMMON else NasLocationInfo.Kind.NAS_PRIVATE,
          writable = true,
          item = itemOf(c),
        )
      },
    )
  }

  // --- Intenti verso le altre app ------------------------------------------------------------------------------------------------

  private val launchFlow = MutableStateFlow<FiveBLaunch?>(null)

  val launches: StateFlow<FiveBLaunch?> = launchFlow.asStateFlow()

  /** L'activity ha lanciato (o non ha potuto lanciare) [launch]. */
  fun launched(launch: FiveBLaunch, ok: Boolean) {
    if (launchFlow.value === launch) launchFlow.value = null
    when (launch) {
      is FiveBLaunch.Edit -> if (ok) {
        say(R.string.fiveb_edit_started, ok = true)
      } else {
        // L'editor non c'e': la sessione si chiude e la copia in cache si toglie. MC-5: la frase dipende da cosa si apriva (Galleria Samsung per un
        // media del telefono o una copia scaricata; un editor qualunque per un solo-NAS, con "Scarica e modifica in Galleria" come strada).
        scope.launch { runCatching { integrations.editSessions()?.cancel(launch.session) } }
        val text = when {
          launch.session.target != EditTarget.NAS_CACHE -> R.string.fiveb_edit_no_gallery
          launch.isVideo -> R.string.fiveb_edit_no_editor_video
          else -> R.string.fiveb_edit_no_editor
        }
        say(text, ok = false)
      }
    }
  }

  /**
   * L'app torna davanti all'utente (dall'editor): le sessioni di "Modifica con…" aperte si leggono (`settle`, D25). Le copie delle condivisioni
   * non si toccano qui: le rilascia il custode di `AppShares` quando l'altra app ha finito (correzioni 5B, MC-1).
   */
  fun onResume() {
    scope.launch { settleEdits() }
  }

  private suspend fun settleEdits() {
    val sessions = integrations.editSessions() ?: return
    for (session in integrations.openEditSessions()) {
      val outcome = try {
        sessions.settle(session)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "sessione di modifica non letta (${e.javaClass.simpleName})" }
        continue
      }
      when (outcome) {
        is EditOutcome.NewVersion, is EditOutcome.NewVersionFromCopy -> say(R.string.fiveb_edit_new_version, ok = true)
        EditOutcome.NewMedia -> say(R.string.fiveb_edit_new_media, ok = true)
        EditOutcome.RestoredOriginal -> say(R.string.fiveb_edit_restored, ok = true)
        EditOutcome.Unchanged, EditOutcome.Unreadable, EditOutcome.Expired -> Unit
      }
    }
  }

  // --- La frase breve dopo un'azione ---------------------------------------------------------------------------------------------

  private val noticeFlow = MutableStateFlow<BackupNotice?>(null)

  private fun say(text: Int, ok: Boolean) {
    noticeFlow.value = BackupNotice(context.getString(text), ok)
  }

  // VE-6: le frasi delle azioni nate fuori dal viewer (fogli alla radice, "Da decidere"): si dicono adesso, dove l'utente e'.
  private val rootNoticeFlow = MutableStateFlow<String?>(null)

  /** Una frase per l'utente fuori dal viewer (la mostra `FiveBHostEffect`), null se non ce n'e'. */
  val rootNotice: StateFlow<String?> = rootNoticeFlow.asStateFlow()

  fun dismissRootNotice() {
    rootNoticeFlow.value = null
  }

  private fun sayHere(text: Int) {
    rootNoticeFlow.value = context.getString(text)
  }

  // --- "Condividi" (D26) --------------------------------------------------------------------------------------------------------

  /** Correzioni 5B (R4, MC-1): "Condividi" del viewer e' il canale del viewer di `AppShares`, lo stesso che c'e' anche senza backup. */
  private val shares get() = integrations.shares

  // --- Le voci del backup nel viewer ---------------------------------------------------------------------------------------------

  val mediaActions: BackupMediaActions = object : BackupMediaActions {
    override val capabilities: BackupMenuCapabilities = BackupMenuCapabilities(
      correct = true,
      editWith = true,
      viewOriginal = false,
      share = true,
      phoneOnlyDelete = true,
      // MC-5: "Scarica e modifica in Galleria" per i solo-NAS.
      editDownload = true,
    )

    override suspend fun versionPlan(id: MediaId): NasVersionPlan? = runCatching { fiveB.versionKeeper.plan(id) }.getOrNull()

    /** VE-4: si ruota solo un formato che si scrive nel file (JPEG, PNG, WebP): la rotazione di un HEIC o di un RAW non la mostrerebbe nessuno. */
    override suspend fun canRotate(id: MediaId): Boolean {
      val cat = catalog() ?: return false
      val media = cat.media(id) ?: return false
      if (media.kind == MediaKind.VIDEO) return false
      val mime = cat.versionMetadata(media.currentVersionId)?.mimeType ?: return false
      return ExifMetadataWriter().canWrite(mime)
    }

    override fun perform(action: BackupMenuAction, id: MediaId) {
      when (action) {
        BackupMenuAction.VERSIONS -> sheetFlow.value = FiveBSheet.Versions(id, VersionsStart.LIST)
        BackupMenuAction.RESTORE_ORIGINAL -> sheetFlow.value = FiveBSheet.Versions(id, VersionsStart.RESTORE)
        BackupMenuAction.CORRECT -> openCorrection(id, CorrectionMode.ALL)
        BackupMenuAction.ROTATE -> openCorrection(id, CorrectionMode.ROTATE)
        BackupMenuAction.EDIT_WITH -> startEdit(id)
        BackupMenuAction.EDIT_DOWNLOAD -> startEdit(id, download = true)
        // LS-1: dal viewer il foglio sa offrire il cestino di Android per un media che non risulta al sicuro sul NAS.
        BackupMenuAction.PHONE_ONLY_DELETE -> scope.launch {
          val video = catalog()?.media(id)?.kind == MediaKind.VIDEO
          sheetFlow.value = FiveBSheet.PhoneOnly(setOf(id), fromViewer = true, isVideo = video)
        }
        // Non dichiarata (`viewOriginal = false`): il viewer non la mostra.
        BackupMenuAction.VIEW_ORIGINAL -> Unit
      }
    }

    override val removeLocation: StateFlow<Boolean> get() = shares.removeLocation

    override val shareState: StateFlow<BackupShareUi> get() = shares.viewer.state

    override fun share(ids: List<MediaId>) = shares.viewer.start(ids)

    override fun shareWithLocation() = shares.viewer.shareWithLocation()

    override fun dismissShare() = shares.viewer.cancel()

    override fun hideShareNotice() = shares.viewer.hideNotice()

    override val notice: StateFlow<BackupNotice?> = noticeFlow.asStateFlow()

    override fun dismissNotice() {
      noticeFlow.value = null
    }

    /**
     * LS-14: "Ovunque" toglie dal telefono **tutte** le copie del media (anche quella di "Scarica sul telefono" in `Pictures/MyCloud Gallery`, che il
     * backup altrimenti ricaricherebbe), con un solo dialogo del sistema. Con una copia sola resta il cestino del viewer (falso), come prima.
     */
    override suspend fun deleteEverywhereFromPhone(id: MediaId): Boolean {
      val refs = phoneCopies(setOf(id))
      if (refs.size <= 1) return false
      askTrash(refs)
      return true
    }

    override suspend fun everywhere(id: MediaId): EverywhereSummary? {
      val plan = fiveB.everywhere(id) ?: return null
      if (plan.blocked != null) return null
      // Le altre aree le elenca gia' il foglio (le copie in `others`): qui solo le versioni precedenti.
      return EverywhereSummary(previousVersions = plan.previousVersions)
    }
  }

  private fun openCorrection(id: MediaId, mode: CorrectionMode) {
    scope.launch {
      val subject = subjectOf(id) ?: return@launch say(R.string.fiveb_edit_unavailable, ok = false)
      sheetFlow.value = FiveBSheet.Correction(subject, mode)
    }
  }

  /** I valori di adesso per precompilare "Correggi" (versione attuale, dal catalogo). */
  private suspend fun subjectOf(id: MediaId): CorrectionSubject? {
    val cat = catalog() ?: return null
    val media = cat.media(id) ?: return null
    val meta = cat.versionMetadata(media.currentVersionId)
    val capture = meta?.captureTime
    val local = capture?.let { Instant.ofEpochMilli(it.utcMillis).atOffset(ZoneOffset.ofTotalSeconds(it.offsetMinutes * 60)).toLocalDateTime() }
    val degrees = meta?.orientationDegrees ?: 0
    val mirrored = meta?.mirrored ?: false
    return CorrectionSubject(
      mediaId = id,
      takenAt = local,
      offsetMinutes = capture?.offsetMinutes,
      placeLabel = null,
      hasLocation = meta?.location != null,
      orientation = ExifOrientation.entries.firstOrNull { it.degrees == degrees && it.mirrored == mirrored } ?: ExifOrientation.NORMAL,
      isVideo = media.kind == MediaKind.VIDEO,
    )
  }

  /**
   * "Modifica con…" (D25): la sessione (telefono → Samsung Gallery; solo-NAS → copia in cache e `ACTION_EDIT`, o con [download] "Scarica e
   * modifica in Galleria", MC-5), poi l'intento dall'activity.
   *
   * MC-4: per un solo-NAS la modifica deve poter diventare una versione sul NAS (un solito percorso nell'area privata; dalla 5B non si scrive
   * nell'area comune; con D13 sotto la radice di prova). Se non puo', lo si dice **prima** di aprire l'editor: prima la modifica si accettava
   * ("Salvata: e' la nuova versione") e non si caricava mai, viveva solo nell'app.
   */
  private fun startEdit(id: MediaId, download: Boolean = false) {
    scope.launch {
      val sessions = integrations.editSessions() ?: return@launch say(R.string.fiveb_edit_unavailable, ok = false)
      val cat = catalog() ?: return@launch say(R.string.fiveb_edit_unavailable, ok = false)
      val media = cat.media(id) ?: return@launch say(R.string.fiveb_edit_unavailable, ok = false)
      val onPhone = cat.phoneRefs(listOf(id))[id] != null
      val mime = cat.versionMetadata(media.currentVersionId)?.mimeType ?: if (media.kind == MediaKind.VIDEO) "video/*" else "image/*"
      val isVideo = media.kind == MediaKind.VIDEO
      val target = when {
        onPhone -> EditTarget.PHONE_GALLERY
        download -> EditTarget.NAS_DOWNLOAD
        else -> EditTarget.NAS_CACHE
      }
      if (target != EditTarget.PHONE_GALLERY && !fiveB.editVersionReady(id)) return@launch say(R.string.fiveb_edit_not_saveable, ok = false)
      when (val begin = sessions.begin(id, target)) {
        is EditBegin.Started -> {
          val intent = EditIntents.forSession(begin.session, integrations.uris, integrations.shareCache, mime, isVideo)
          if (intent == null) {
            runCatching { sessions.cancel(begin.session) }
            say(R.string.fiveb_edit_unavailable, ok = false)
          } else {
            launchFlow.value = FiveBLaunch.Edit(intent, begin.session, next(), isVideo = isVideo)
          }
        }
        is EditBegin.Unavailable -> say(unavailableText(begin.reason), ok = false)
      }
    }
  }

  private fun unavailableText(reason: EditUnavailable): Int = when (reason) {
    EditUnavailable.NOT_MINE -> R.string.fiveb_edit_not_mine
    EditUnavailable.NAS_UNREACHABLE -> R.string.fiveb_edit_nas_unreachable
    EditUnavailable.PHONE_FULL -> R.string.fiveb_edit_phone_full
    else -> R.string.fiveb_edit_unavailable
  }

  private companion object {
    const val TAG = "BackupFiveBUi"

    /** LS-6: quante richieste di "Libera spazio" si ricordano per le rotte ricreate. */
    const val KEPT_REQUESTS = 8
  }
}
