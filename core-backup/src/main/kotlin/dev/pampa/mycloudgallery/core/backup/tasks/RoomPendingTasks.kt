package dev.pampa.mycloudgallery.core.backup.tasks

import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.PendingTask
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskId
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskKind
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskState
import dev.pampa.mycloudgallery.core.backup.contract.PendingTasks
import dev.pampa.mycloudgallery.core.backup.versions.VideoOriginalTasks
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.entity.PendingTaskEntity
import dev.pampa.mycloudgallery.core.database.transaction
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.VersionId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Le attivita' in sospeso su Room (`pending_task`, schema v4; Fase 5B, P8; docs/fase5-progetto.md D20, §1.5 `PendingTasksState`, §2.6
 * "Da decidere"; contratto [PendingTasks] di P0-B). La prima e l'unica di oggi: "Hai modificato questo video. L'originale occupa X sul NAS:
 * vuoi eliminarlo?", che il motore delle versioni apre ([originalKept]) quando un video modificato ha spostato il suo originale in
 * `Versioni/`.
 *
 * - **Una sola per versione, per sempre**: un'attivita' che c'e' gia' (aperta, tenuta o fatta) non si riapre; "Tienilo" vale per sempre
 *   (l'originale resta come versione e non si propone piu').
 * - **Non cancella niente** (D2): "Elimina l'originale" passa dal foglio "Elimina…" di `feature-nas` (cestino del NAS, `UserConfirmation`),
 *   che `:app` apre; a cancellazione avviata `:app` chiama [complete]. Ignorata, resta in "Da decidere" (E-E4).
 * - Gli istanti sono l'ora del NAS (F4-D32); senza, l'ultima nota del diario (mai l'orologio del telefono, D31).
 *
 * [tasks] parte vuota: `:app` chiama [refresh] all'avvio (e ogni scrittura di qui la aggiorna).
 */
class RoomPendingTasks(private val database: GalleryDatabase, private val clock: BackupClock) : PendingTasks, VideoOriginalTasks {

  private val dao get() = database.pendingTaskDao()

  private val flow = MutableStateFlow<List<PendingTask>>(emptyList())

  override val tasks: StateFlow<List<PendingTask>> = flow.asStateFlow()

  /** Rilegge le attivita' aperte (all'avvio, o dopo che un altro processo le ha cambiate). */
  suspend fun refresh() {
    flow.value = dao.inState(PendingTaskState.OPEN.code).mapNotNull { it.toTask() }
  }

  /** Tutte le attivita' di ogni stato, dalla piu' vecchia (Diagnostica e test). */
  suspend fun all(): List<PendingTask> =
    PendingTaskState.entries.flatMap { state -> dao.inState(state.code) }.sortedBy { it.taskId }.mapNotNull { it.toTask() }

  /** D20: l'originale di un video modificato e' in `Versioni/`. Idempotente per versione: mai due attivita' per lo stesso originale. */
  override suspend fun originalKept(mediaId: MediaId, versionId: VersionId, bytes: Long) {
    require(bytes >= 0) { "Dimensione negativa." }
    database.transaction {
      val known = PendingTaskState.entries.flatMap { dao.inState(it.code) }
        .any { it.kind == PendingTaskKind.EDITED_VIDEO_ORIGINAL.code && it.versionId == versionId.value }
      if (!known) {
        val now = clock.nasNowMs() ?: lastKnownNow()
        dao.insert(
          PendingTaskEntity(
            kind = PendingTaskKind.EDITED_VIDEO_ORIGINAL.code,
            state = PendingTaskState.OPEN.code,
            mediaId = mediaId.value,
            versionId = versionId.value,
            bytes = bytes,
            createdAt = now,
            updatedAt = now,
          ),
        )
      }
    }
    refresh()
  }

  /** "Tienilo": solo da aperta; l'originale resta come versione e l'attivita' non si propone piu'. */
  override suspend fun dismiss(id: PendingTaskId) = move(id, from = setOf(PendingTaskState.OPEN), to = PendingTaskState.KEPT)

  /** La cancellazione della 4B e' partita (la chiama `:app`): da aperta o tenuta (l'utente ha cambiato idea dal viewer). */
  override suspend fun complete(id: PendingTaskId) =
    move(id, from = setOf(PendingTaskState.OPEN, PendingTaskState.KEPT), to = PendingTaskState.DONE)

  private suspend fun move(id: PendingTaskId, from: Set<PendingTaskState>, to: PendingTaskState) {
    database.transaction {
      val row = dao.get(id.value) ?: return@transaction
      val state = STATES[row.state] ?: return@transaction
      if (state !in from) return@transaction
      dao.update(row.copy(state = to.code, updatedAt = clock.nasNowMs() ?: row.updatedAt))
    }
    refresh()
  }

  /** Senza ora del NAS: l'istante piu' recente gia' scritto (le attivita' restano in ordine), mai l'orologio del telefono. */
  private suspend fun lastKnownNow(): Long =
    PendingTaskState.entries.flatMap { dao.inState(it.code) }.maxOfOrNull { maxOf(it.createdAt, it.updatedAt) } ?: 0L

  private fun PendingTaskEntity.toTask(): PendingTask? {
    val kind = KINDS[kind] ?: return null
    val state = STATES[state] ?: return null
    return PendingTask(PendingTaskId(taskId), kind, state, MediaId(mediaId), VersionId(versionId), bytes, createdAt)
  }

  private companion object {
    val KINDS: Map<String, PendingTaskKind> = PendingTaskKind.entries.associateBy { it.code }
    val STATES: Map<String, PendingTaskState> = PendingTaskState.entries.associateBy { it.code }
  }
}
