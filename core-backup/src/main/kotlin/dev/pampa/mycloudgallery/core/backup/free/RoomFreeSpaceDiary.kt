package dev.pampa.mycloudgallery.core.backup.free

import dev.pampa.mycloudgallery.core.backup.contract.BackupClock
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceBlock
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
 * Il diario di "Libera spazio" su Room (`free_space_run`, `free_space_item`, schema v4), con **le stesse regole** del diario in memoria:
 * identita' di giro ed elemento che non cambiano, transizioni di [FreeItemTransitions], un giro finito che non riparte, lotti che non tornano
 * indietro. Una transazione per chiamata.
 *
 * **Diario completo dal P14** (colonne facoltative di P9, aggiunte alla v4 prima del suo rilascio): la validita' della verifica
 * ([FreeRunRecord.validity]: inizio monotono e conteggio dei riavvii), il motivo di un blocco, la riga del telefono e il suo timbro, il tipo,
 * le copie rilette di tutte le versioni da proteggere e il segno "con il guardiano". Cosi' un giro a meta' **riprende dopo la morte del
 * processo nello stesso avvio** (la validita' resta quella di quando e' cominciato, 6 ore monotone), mai dopo un riavvio. Le righe consegnate
 * ad Android restano tracciate per posizione del telefono (`phone_location_id`): finche' la lettura del telefono non le risolve sono "in
 * dubbio"; [handedPhoneLocations] e' una query sola sull'indice di `state`.
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
        validFromMonotonic = run.validity?.startMonotonicMs,
        bootCount = run.validity?.bootCount,
        blocked = run.blocked?.code,
      ),
    )
    if (items.isNotEmpty()) dao.insertItems(items.map { it.toEntity(id, now, previous = null) })
    FreeRunRecord(
      FreeSpaceRunId(id), run.state, run.kind, run.excludeRecentDays, run.permanent, run.planHash, batchesDone = 0, validity = run.validity,
      blocked = run.blocked,
    )
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
        // LS-15: solo gli elementi che questa chiamata cambia, per indice (prima si rileggevano tutti quelli del giro a ogni scrittura).
        val existing = items.map { it.index }.distinct().chunked(INDEX_CHUNK).flatMap { dao.itemsAt(run.id.value, it) }.associateBy { it.itemIndex }
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
      dao.updateRun(
        stored.copy(
          state = run.state.code,
          batchesDone = run.batchesDone,
          updatedAt = now,
          // La validita' nasce con il giro e non si allunga: si scrive solo se il diario non l'aveva.
          validFromMonotonic = stored.validFromMonotonic ?: run.validity?.startMonotonicMs,
          bootCount = if (stored.validFromMonotonic != null) stored.bootCount else run.validity?.bootCount,
          blocked = run.blocked?.code ?: stored.blocked,
        ),
      )
    }
  }

  override suspend fun runs(): List<FreeRunRecord> = dao.runsInState(RUN_STATES.keys.toList()).map { it.toRecord() }

  /** LS-2: solo i giri non finiti (una query sullo stato, non tutta la storia). */
  override suspend fun openRuns(): List<FreeRunRecord> = dao.runsInState(OPEN_RUN_STATES).map { it.toRecord() }

  /** LS-15: solo i giri con righe consegnate ad Android e non risolte, dall'indice di `free_space_item.state`. */
  override suspend fun runsToSettle(): List<FreeRunRecord> =
    dao.itemsInStates(HANDED_UNRESOLVED).map { it.runId }.distinct().sorted().mapNotNull { dao.run(it)?.toRecord() }

  /** P14: una query sola sull'indice di `free_space_item.state` (non scorre tutti i giri). */
  override suspend fun handedPhoneLocations(): Set<LocationId> =
    dao.phoneLocationsInStates(HANDED_UNRESOLVED).mapTo(HashSet()) { LocationId(it) }

  /**
   * P14 (la lettura del telefono): le posizioni del telefono che "Libera spazio" ha consegnato ad Android, liberate o ancora in dubbio
   * (`in_dialog`, `freed`, `in_doubt`). Una posizione che sparisce dal MediaStore ed e' qui e' **liberata**, non persa.
   */
  suspend fun freedOrHandedPhoneLocations(): Set<LocationId> =
    dao.phoneLocationsInStates(HANDED_OR_FREED).mapTo(HashSet()) { LocationId(it) }

  private companion object {
    val RUN_STATES: Map<String, FreeSpaceRunState> = FreeSpaceRunState.entries.associateBy { it.code }
    val ITEM_STATES: Map<String, FreeItemState> = FreeItemState.entries.associateBy { it.code }
    val KINDS: Map<String, FreeSpaceKind> = FreeSpaceKind.entries.associateBy { it.code }
    val REASONS: Map<String, FreeSpaceExclusion> = FreeSpaceExclusion.entries.associateBy { it.code }

    fun modeOf(permanent: Boolean): String = if (permanent) TrashMode.DELETE_NOW.code else TrashMode.ANDROID_TRASH.code

    /** Quanti indici per interrogazione di `itemsAt`: SQLite su Android 10 ammette 999 variabili, una e' il giro. */
    const val INDEX_CHUNK = 500

    val HANDED_UNRESOLVED: List<String> = listOf(FreeItemState.IN_DIALOG.code, FreeItemState.IN_DOUBT.code)
    val OPEN_RUN_STATES: List<String> = FreeSpaceRunState.entries.filter { !it.terminal }.map { it.code }
    val HANDED_OR_FREED: List<String> = listOf(FreeItemState.IN_DIALOG.code, FreeItemState.IN_DOUBT.code, FreeItemState.FREED.code)
    val BLOCKS: Map<String, FreeSpaceBlock> = FreeSpaceBlock.entries.associateBy { it.code }

    fun FreeSpaceRunEntity.toRecord(): FreeRunRecord = FreeRunRecord(
      id = FreeSpaceRunId(runId),
      state = RUN_STATES.getValue(state),
      kind = KINDS.getValue(filter),
      excludeRecentDays = excludeRecentDays,
      permanent = deleteMode == TrashMode.DELETE_NOW.code,
      planHash = PhonePlanHash(planHash),
      batchesDone = batchesDone,
      validity = validFromMonotonic?.let { FreeValidity(it, bootCount) },
      blocked = blocked?.let { BLOCKS[it] },
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
        phoneVolume = phone?.volume ?: previous?.phoneVolume,
        phoneMediaStoreId = phone?.mediaStoreId ?: previous?.phoneMediaStoreId,
        phoneMediaStoreVersion = phone?.mediaStoreVersion ?: previous?.phoneMediaStoreVersion,
        phoneSize = phoneStamp?.size ?: previous?.phoneSize,
        phoneModifiedAt = phoneStamp?.modifiedAtMillis ?: previous?.phoneModifiedAt,
        phoneGeneration = if (phoneStamp != null) phoneStamp.generation else previous?.phoneGeneration,
        mime = mime ?: previous?.mime,
        needsGuardian = needsGuardian || previous?.needsGuardian == true,
        checks = checks?.let(::encodeChecks) ?: previous?.checks,
      )
    }

    fun FreeSpaceItemEntity.toRecord(): FreeItemRecord = FreeItemRecord(
      index = itemIndex,
      state = ITEM_STATES.getValue(state),
      mediaId = MediaId(mediaId),
      versionId = VersionId(versionId),
      phoneLocation = LocationId(phoneLocationId),
      phone = phoneRef(phoneVolume, phoneMediaStoreId, phoneMediaStoreVersion),
      phoneStamp = phoneSize?.let { s -> phoneModifiedAt?.let { m -> FileStamp(s, m, phoneGeneration) } },
      mime = mime,
      size = size,
      needsGuardian = needsGuardian,
      plannedCopy = if (verifiedSize != null && verifiedModifiedAt != null) NasStamp(verifiedSize!!, verifiedModifiedAt!!, verifiedFileId) else null,
      checks = checks?.let(::decodeChecks),
      batchIndex = batchIndex,
      reason = reason?.let { REASONS.getValue(it) },
    )

    fun phoneRef(volume: String?, id: Long?, version: String?): PhoneRef? =
      if (volume != null && id != null && id > 0 && version != null) PhoneRef(volume, id, version) else null

    // Le copie rilette: una riga per copia, campi separati da `|`; share e percorso con `%`, `|` e a capo scritti come `%25`, `%7C`, `%0A`.
    private fun esc(v: String): String = v.replace("%", "%25").replace("|", "%7C").replace("\n", "%0A")

    private fun unesc(v: String): String = v.replace("%0A", "\n").replace("%7C", "|").replace("%25", "%")

    fun encodeChecks(checks: List<FreeCheck>): String = checks.joinToString("\n") { c ->
      listOf(c.versionId.value, c.location.value.toString(), esc(c.share), esc(c.path.value), c.stamp.size.toString(),
        c.stamp.modifiedAtMillis.toString(), c.stamp.fileId?.toString() ?: "").joinToString("|")
    }

    /** Le copie rilette, o null se la colonna non si legge (un giro con le copie illeggibili non va avanti: fallisce chiuso). */
    fun decodeChecks(text: String): List<FreeCheck>? = try {
      if (text.isEmpty()) {
        emptyList()
      } else {
        text.split("\n").map { line ->
          val f = line.split("|")
          require(f.size == 7) { "Copia riletta non valida." }
          FreeCheck(
            versionId = VersionId(f[0]),
            location = LocationId(f[1].toLong()),
            share = unesc(f[2]),
            path = SharePath.parse(unesc(f[3])),
            stamp = NasStamp(f[4].toLong(), f[5].toLong(), f[6].takeIf { it.isNotEmpty() }?.toLong()),
          )
        }
      }
    } catch (_: IllegalArgumentException) {
      null
    }
  }
}
