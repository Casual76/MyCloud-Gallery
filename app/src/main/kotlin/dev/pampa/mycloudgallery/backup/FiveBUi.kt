package dev.pampa.mycloudgallery.backup

import android.content.Context
import android.content.Intent
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
import dev.pampa.mycloudgallery.core.model.ExifOrientation
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
import dev.pampa.mycloudgallery.feature.integrations.share.ShareIntents
import dev.pampa.mycloudgallery.feature.integrations.share.ShareLease
import dev.pampa.mycloudgallery.feature.integrations.share.SharePreparation
import dev.pampa.mycloudgallery.feature.integrations.share.ShareStop
import dev.pampa.mycloudgallery.feature.integrations.share.shareStopText
import dev.pampa.mycloudgallery.feature.settings.sharing.SharingSettings
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Un foglio della 5B che `:app` apre alla radice, sopra il viewer (i feature non si conoscono: il viewer chiede, `:app` mostra). */
sealed interface FiveBSheet {
  data class Versions(val mediaId: MediaId, val start: VersionsStart) : FiveBSheet

  class Correction(val subject: CorrectionSubject, val mode: CorrectionMode) : FiveBSheet

  data class PhoneOnly(val ids: Set<MediaId>) : FiveBSheet
}

/**
 * Le copie del NAS di versioni **precedenti** da mettere nel cestino del NAS con il foglio "Elimina…" della 4B (D2: il backup non toglie mai
 * niente da solo; la conferma nasce dal tocco nel foglio): "Da decidere" (l'originale di un video modificato) ed "Elimina le intermedie…".
 * [taskId]: l'attivita' "Da decidere" che si chiude quando l'operazione parte.
 */
class VersionDeleteRequest(val target: NasActionTarget, val taskId: PendingTaskId?, val serial: Long) {
  override fun toString(): String = "VersionDeleteRequest(${target.everywhereItems.size} copie)"
}

/** Un intento che `:app` lancia dall'activity (serve un'activity viva): l'editor di "Modifica con…" o la scelta dell'app di "Condividi". */
sealed interface FiveBLaunch {
  val serial: Long

  class Edit(val intent: Intent, val session: EditSession, override val serial: Long) : FiveBLaunch

  class Share(val intent: Intent, override val serial: Long) : FiveBLaunch
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
  private val sharing: SharingSettings?,
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

  fun openFreeSpace(selection: Set<MediaId>?) {
    freeSpaceSelection = selection?.takeIf { it.isNotEmpty() }
    freeSpaceFlow.value = next()
  }

  // --- "Elimina…" delle versioni precedenti -------------------------------------------------------------------------------------

  private val deleteFlow = MutableStateFlow<VersionDeleteRequest?>(null)

  val deleteRequest: StateFlow<VersionDeleteRequest?> = deleteFlow.asStateFlow()

  private fun requestVersionDelete(mediaId: MediaId, versions: List<VersionId>, taskId: PendingTaskId?) {
    scope.launch {
      val target = versionCopiesTarget(mediaId, versions)
      if (target == null) {
        say(R.string.fiveb_edit_unavailable, ok = false)
        return@launch
      }
      deleteFlow.value = VersionDeleteRequest(target, taskId, next())
    }
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
    say(R.string.fiveb_delete_started, ok = true)
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
        // L'editor non c'e' (Samsung Gallery assente): la sessione si chiude e la copia in cache si toglie.
        scope.launch { runCatching { integrations.editSessions()?.cancel(launch.session) } }
        say(R.string.fiveb_edit_no_gallery, ok = false)
      }
      is FiveBLaunch.Share -> if (!ok) {
        releaseShare()
        shareFlow.value = BackupShareUi.Idle
      }
    }
  }

  /**
   * L'app torna davanti all'utente (dalla condivisione o dall'editor): i permessi della condivisione si revocano e le copie si tolgono (D26),
   * le sessioni di "Modifica con…" aperte si leggono (`settle`, D25).
   */
  fun onResume() {
    if (shareFlow.value is BackupShareUi.Ready && launchFlow.value == null) {
      releaseShare()
      shareFlow.value = BackupShareUi.Idle
    }
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

  // --- "Condividi" (D26) --------------------------------------------------------------------------------------------------------

  private val shareFlow = MutableStateFlow<BackupShareUi>(BackupShareUi.Idle)
  private var shareJob: Job? = null
  private var lease: ShareLease? = null
  private var lastShare: List<MediaId> = emptyList()

  private fun releaseShare() {
    lease?.release()
    lease = null
  }

  private fun startShare(ids: List<MediaId>, removeLocation: Boolean) {
    shareJob?.cancel()
    releaseShare()
    lastShare = ids
    shareFlow.value = BackupShareUi.Preparing(0, ids.size, removeLocation)
    shareJob = scope.launch {
      val prepared = integrations.share.prepare(ids, removeLocation) { done, total ->
        shareFlow.value = BackupShareUi.Preparing(done, total, removeLocation)
      }
      when (prepared) {
        is SharePreparation.Ready -> {
          lease = prepared.lease
          shareFlow.value = BackupShareUi.Ready(verified = prepared.stripped, direct = prepared.files.size - prepared.stripped)
          launchFlow.value = FiveBLaunch.Share(Intent.createChooser(ShareIntents.send(prepared.files), null), next())
        }
        is SharePreparation.Stopped -> shareFlow.value = BackupShareUi.Refused(
          message = shareStopText(context, prepared.stop),
          // L41: "Condividi con la posizione" solo se e' la posizione che non si toglie, e solo come scelta esplicita dell'utente.
          canShareWithLocation = removeLocation && prepared.stop in WITH_LOCATION_CHOICE,
        )
      }
    }
  }

  // --- Le voci del backup nel viewer ---------------------------------------------------------------------------------------------

  val mediaActions: BackupMediaActions = object : BackupMediaActions {
    override val capabilities: BackupMenuCapabilities = BackupMenuCapabilities(
      correct = true,
      editWith = true,
      viewOriginal = false,
      share = true,
      phoneOnlyDelete = true,
    )

    override suspend fun versionPlan(id: MediaId): NasVersionPlan? = runCatching { fiveB.versionKeeper.plan(id) }.getOrNull()

    override fun perform(action: BackupMenuAction, id: MediaId) {
      when (action) {
        BackupMenuAction.VERSIONS -> sheetFlow.value = FiveBSheet.Versions(id, VersionsStart.LIST)
        BackupMenuAction.RESTORE_ORIGINAL -> sheetFlow.value = FiveBSheet.Versions(id, VersionsStart.RESTORE)
        BackupMenuAction.CORRECT -> openCorrection(id, CorrectionMode.ALL)
        BackupMenuAction.ROTATE -> openCorrection(id, CorrectionMode.ROTATE)
        BackupMenuAction.EDIT_WITH -> startEdit(id)
        BackupMenuAction.PHONE_ONLY_DELETE -> sheetFlow.value = FiveBSheet.PhoneOnly(setOf(id))
        // Non dichiarata (`viewOriginal = false`): il viewer non la mostra.
        BackupMenuAction.VIEW_ORIGINAL -> Unit
      }
    }

    override val removeLocation: StateFlow<Boolean> = sharing?.removeLocation ?: MutableStateFlow(false)

    override val shareState: StateFlow<BackupShareUi> = shareFlow.asStateFlow()

    override fun share(ids: List<MediaId>) = startShare(ids, removeLocation.value)

    override fun shareWithLocation() {
      if (lastShare.isNotEmpty()) startShare(lastShare, removeLocation = false)
    }

    override fun dismissShare() {
      shareJob?.cancel()
      releaseShare()
      shareFlow.value = BackupShareUi.Idle
    }

    override val notice: StateFlow<BackupNotice?> = noticeFlow.asStateFlow()

    override fun dismissNotice() {
      noticeFlow.value = null
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

  /** "Modifica con…" (D25): la sessione (telefono → Samsung Gallery; solo-NAS → copia in cache e `ACTION_EDIT`), poi l'intento dall'activity. */
  private fun startEdit(id: MediaId) {
    scope.launch {
      val sessions = integrations.editSessions() ?: return@launch say(R.string.fiveb_edit_unavailable, ok = false)
      val cat = catalog() ?: return@launch say(R.string.fiveb_edit_unavailable, ok = false)
      val media = cat.media(id) ?: return@launch say(R.string.fiveb_edit_unavailable, ok = false)
      val onPhone = cat.phoneRefs(listOf(id))[id] != null
      val mime = cat.versionMetadata(media.currentVersionId)?.mimeType ?: if (media.kind == MediaKind.VIDEO) "video/*" else "image/*"
      val isVideo = media.kind == MediaKind.VIDEO
      when (val begin = sessions.begin(id, if (onPhone) EditTarget.PHONE_GALLERY else EditTarget.NAS_CACHE)) {
        is EditBegin.Started -> {
          val intent = EditIntents.forSession(begin.session, integrations.uris, integrations.shareCache, mime, isVideo)
          if (intent == null) {
            runCatching { sessions.cancel(begin.session) }
            say(R.string.fiveb_edit_unavailable, ok = false)
          } else {
            launchFlow.value = FiveBLaunch.Edit(intent, begin.session, next())
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

    /** Le fermate per cui ha senso offrire "Condividi con la posizione" (la posizione non si toglie da questo file). */
    val WITH_LOCATION_CHOICE = setOf(ShareStop.CANNOT_REMOVE_LOCATION, ShareStop.LOCATION_REMAINS)
  }
}
