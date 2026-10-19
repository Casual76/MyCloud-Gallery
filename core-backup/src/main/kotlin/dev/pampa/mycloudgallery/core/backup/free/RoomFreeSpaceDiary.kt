package dev.pampa.mycloudgallery.core.backup.free

import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceExclusion
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceKind
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunId
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunState
import dev.pampa.mycloudgallery.core.backup.contract.PhonePlanHash
import dev.pampa.mycloudgallery.core.backup.contract.TrashMode
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.entity.FreeSpaceItemEntity
import dev.pampa.mycloudgallery.core.database.entity.FreeSpaceRunEntity
import dev.pampa.mycloudgallery.core.database.transaction
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.VersionId

/**
 * Il diario di "Libera spazio" su Room (`free_space_run`, `free_space_item`, schema v4 di P1, **senza colonne nuove**: D27, una sola
 * migrazione per la fase), con **le stesse regole** del diario in memoria: identita' di giro ed elemento che non cambiano, transizioni di
 * [FreeItemTransitions], un giro finito che non riparte, lotti che non tornano indietro. Una transazione per chiamata.
 *
 * **Cosa le colonne v4 non tengono** (e quindi torna null, come il contratto [FreeSpaceDiary] prevede): la validita' della verifica
 * ([FreeRunRecord.validity]: inizio monotono e conteggio dei riavvii), il motivo di un blocco, la riga del telefono e il suo timbro, il tipo,
 * le copie rilette delle versioni (resta la posizione e il timbro della copia della versione del telefono), il segno "con il guardiano". Il
 * motore ([SafeFreeSpace]) le ricorda per intero finche' il processo vive; dopo la sua morte un giro a meta' **non va avanti** (scaduto: si
 * rifa' dal piano, come dice il commento dell'entita' di P1) e le righe consegnate ad Android restano tracciate per posizione del telefono
 * (`phone_location_id`): finche' la lettura del telefono non le risolve sono "in dubbio" (P14: `forPhoneLocation`).
 *
 * Gli istanti `created_at`/`updated_at` sono l'ora del NAS ([BackupClock.nasNowMs]) quando c'e', mai l'orologio del telefono (D31): servono
 * solo a chi guarda il diario, nessuna decisione li usa.
 */
class RoomFreeSpaceDiary(private val database: GalleryDatabase, private val clock: BackupClock) : FreeSpaceDiary {

  private val dao get() = database.freeSpaceDao()

  override suspend fun create(run: NewFreeRun, items: List<FreeItemRecord>): FreeRunRecord = database.transaction {
    require(items.withIndex().all { (i, item) -> item.index == i }) { "Gli elementi di un giro hanno indici da 0 in ordine." }
    require(items.all { it.state == FreeItemState.GUARD || it.state == FreeItemState.PLANNED }) { "Un giro nuovo ha solo elementi da guardare o da verificare." }
    val now = clock.nasNowMs() ?: 0L
    val id = dao.insertRun(
      FreeSpaceRunEntity(
        state = run.state.code,
        filter = run.kind.code,
        excludeRecentDays = run.excludeRecentDays,
        planHash = run.planHash.hex,
        deleteMode = modeOf(run.permanent),
        itemCount = items.size,
        totalBytes = items.sumOf { it.size },
        batchesTotal = 0,
        batchesDone = 0,
        createdAt = now,
        updatedAt = now,
      ),
    )
    if (items.isNotEmpty()) dao.insertItems(items.map { it.toEntity(id, now, previous = null) })
    FreeRunRecord(FreeSpaceRunId(id), run.state, run.kind, run.excludeRecentDays, run.permanent, run.planHash, batchesDone = 0, validity = null)
  }

  override suspend fun run(id: FreeSpaceRunId): FreeRunRecord? = dao.run(id.value)?.toRecord()

  override suspend fun items(id: FreeSpaceRunId): List<FreeItemRecord> = dao.items(id.value).map { it.toRecord() }

  override suspend fun update(run: FreeRunRecord, items: List<FreeItemRecord>) {
    database.transaction {
      val stored = dao.run(run.id.value) ?: throw IllegalArgumentException("Giro sconosciuto.")
      require(stored.planHash == run.planHash.hex && stored.deleteMode == modeOf(run.permanent)) { "L'identita' di un giro non cambia." }
      val before = RUN_STATES.getValue(stored.state)
      check(!before.terminal || before == run.state) { "Un giro finito non riparte: ${before.code} -> ${run.state.code}." }
      check(run.batchesDone >= stored.batchesDone) { "I lotti fatti non tornano indietro." }
      val now = clock.nasNowMs() ?: stored.updatedAt
      if (items.isNotEmpty()) {
        val existing = dao.items(run.id.value).associateBy { it.itemIndex }
        for (item in items) {
          val old = existing[item.index] ?: throw IllegalArgumentException("Elemento sconosciuto.")
          require(
            old.mediaId == item.mediaId.value && old.versionId == item.versionId.value && old.phoneLocationId == item.phoneLocation.value &&
              old.size == item.size,
          ) { "L'identita' di un elemento non cambia." }
          val oldState = ITEM_STATES.getValue(old.state)
          check(FreeItemTransitions.canTransition(oldState, item.state)) { "Transizione non ammessa: ${oldState.code} -> ${item.state.code}." }
          check(!oldState.handedToAndroid || old.batchIndex == item.batchIndex || item.state == FreeItemState.DROPPED) {
            "Un elemento consegnato ad Android non cambia lotto."
          }
          dao.updateItem(item.toEntity(run.id.value, now, previous = old))
        }
      }
      dao.updateRun(stored.copy(state = run.state.code, batchesDone = run.batchesDone, updatedAt = now))
    }
  }

  override suspend fun runs(): List<FreeRunRecord> = dao.runsInState(RUN_STATES.keys.toList()).map { it.toRecord() }

  private companion object {
    val RUN_STATES: Map<String, FreeSpaceRunState> = FreeSpaceRunState.entries.associateBy { it.code }
    val ITEM_STATES: Map<String, FreeItemState> = FreeItemState.entries.associateBy { it.code }
    val KINDS: Map<String, FreeSpaceKind> = FreeSpaceKind.entries.associateBy { it.code }
    val REASONS: Map<String, FreeSpaceExclusion> = FreeSpaceExclusion.entries.associateBy { it.code }

    fun modeOf(permanent: Boolean): String = if (permanent) TrashMode.DELETE_NOW.code else TrashMode.ANDROID_TRASH.code

    fun FreeSpaceRunEntity.toRecord(): FreeRunRecord = FreeRunRecord(
      id = FreeSpaceRunId(runId),
      state = RUN_STATES.getValue(state),
      kind = KINDS.getValue(filter),
      excludeRecentDays = excludeRecentDays,
      permanent = deleteMode == TrashMode.DELETE_NOW.code,
      planHash = PhonePlanHash(planHash),
      batchesDone = batchesDone,
      validity = null,
    )

    /**
     * La copia della versione del telefono: quella riletta (dopo la verifica) o quella decisa nel piano (prima); se l'elemento letto dal
     * diario non le porta piu' (campi non conservati), restano quelle di [previous].
     */
    fun FreeItemRecord.toEntity(runId: Long, now: Long, previous: FreeSpaceItemEntity?): FreeSpaceItemEntity {
      val check = checks?.firstOrNull { it.versionId == versionId }
      val stamp = check?.stamp ?: plannedCopy
      return FreeSpaceItemEntity(
        runId = runId,
        itemIndex = index,
        state = state.code,
        mediaId = mediaId.value,
        versionId = versionId.value,
        phoneLocationId = phoneLocation.value,
        nasLocationId = check?.location?.value ?: previous?.nasLocationId,
        size = size,
        verifiedSize = stamp?.size ?: previous?.verifiedSize,
        verifiedModifiedAt = stamp?.modifiedAtMillis ?: previous?.verifiedModifiedAt,
        verifiedFileId = if (stamp != null) stamp.fileId else previous?.verifiedFileId,
        batchIndex = batchIndex,
        reason = reason?.code,
        updatedAt = now,
      )
    }

    fun FreeSpaceItemEntity.toRecord(): FreeItemRecord = FreeItemRecord(
      index = itemIndex,
      state = ITEM_STATES.getValue(state),
      mediaId = MediaId(mediaId),
      versionId = VersionId(versionId),
      phoneLocation = LocationId(phoneLocationId),
      phone = null,
      phoneStamp = null,
      mime = null,
      size = size,
      needsGuardian = false,
      plannedCopy = if (verifiedSize != null && verifiedModifiedAt != null) NasStamp(verifiedSize!!, verifiedModifiedAt!!, verifiedFileId) else null,
      checks = null,
      batchIndex = batchIndex,
      reason = reason?.let { REASONS.getValue(it) },
    )
  }
}
