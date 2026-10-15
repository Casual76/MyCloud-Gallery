package dev.pampa.mycloudgallery.core.nasops.chaos

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.ListOutcome
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaLimits
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasRandomFile
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.ops.CopyOutcome
import dev.pampa.mycloudgallery.core.nas.ops.DeleteOutcome
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOps
import dev.pampa.mycloudgallery.core.nas.ops.NasSpaceInfo
import dev.pampa.mycloudgallery.core.nas.ops.NasTempWriter
import dev.pampa.mycloudgallery.core.nas.ops.PublishOutcome
import dev.pampa.mycloudgallery.core.nas.ops.RenameOutcome
import dev.pampa.mycloudgallery.core.nas.ops.RmdirOutcome
import dev.pampa.mycloudgallery.core.nas.ops.TempFinish
import dev.pampa.mycloudgallery.core.nasops.contract.AcceptedPlan
import dev.pampa.mycloudgallery.core.nasops.contract.LocalTrashRecord
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemRecord
import dev.pampa.mycloudgallery.core.nasops.contract.OpRecord
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.OpStateMachine
import dev.pampa.mycloudgallery.core.nasops.contract.OpsStore
import dev.pampa.mycloudgallery.core.nasops.contract.PauseReason
import dev.pampa.mycloudgallery.core.nasops.contract.PlanHash
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import dev.pampa.mycloudgallery.core.nasops.contract.PurgedTally
import dev.pampa.mycloudgallery.core.nasops.contract.TrashManifest
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.flow.Flow

// I decoratori che portano i colpi (ChaosFaults.kt) dentro le tre porte del codice sotto prova: le primitive di scrittura (NasFileOps),
// la lettura (NasMediaReader) e il diario (OpsStore). Sono tutti sottili: contano la chiamata, lasciano che il colpo scatti PRIMA o
// DOPO, poi passano al vero. Il codice sotto prova non li distingue da un NAS che cade; il NAS (finto o vero) non li vede.

/** Le primitive di scrittura con i colpi. */
class ChaosFileOps(
  private val inner: NasFileOps,
  private val injector: Injector,
  private val incarnation: Incarnation,
) : NasFileOps {
  override val ownerDeviceId: String get() = inner.ownerDeviceId

  override suspend fun mkdirs(share: String, dir: SharePath, purpose: Purpose): NasResult<Unit> =
    injector.nas(Primitive.MKDIRS, incarnation) { inner.mkdirs(share, dir, purpose) }

  override suspend fun rename(share: String, from: SharePath, to: SharePath, expect: NasStamp?, purpose: Purpose): NasResult<RenameOutcome> =
    injector.nas(Primitive.RENAME, incarnation) { inner.rename(share, from, to, expect, purpose) }

  override suspend fun copyToTemp(
    fromShare: String,
    from: SharePath,
    toShare: String,
    temp: SharePath,
    expect: NasStamp?,
    purpose: Purpose,
  ): NasResult<CopyOutcome> = injector.nas(Primitive.COPY_TO_TEMP, incarnation) { inner.copyToTemp(fromShare, from, toShare, temp, expect, purpose) }

  override suspend fun openTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<NasTempWriter> =
    when (val opened = injector.nas(Primitive.OPEN_TEMP, incarnation) { inner.openTemp(share, temp, purpose) }) {
      is NasResult.Err -> opened
      is NasResult.Ok -> NasResult.Ok(ChaosTempWriter(opened.value, injector, incarnation))
    }

  override suspend fun publishTemp(share: String, temp: SharePath, final: SharePath, purpose: Purpose): NasResult<PublishOutcome> =
    injector.nas(Primitive.PUBLISH_TEMP, incarnation) { inner.publishTemp(share, temp, final, purpose) }

  override suspend fun trashMove(share: String, path: SharePath, trashId: String, expect: NasStamp?, purpose: Purpose): NasResult<RenameOutcome> =
    injector.nas(Primitive.TRASH_MOVE, incarnation) { inner.trashMove(share, path, trashId, expect, purpose) }

  override suspend fun deleteIfUnchanged(share: String, path: SharePath, size: Long, purpose: Purpose, expect: NasStamp?): NasResult<DeleteOutcome> =
    injector.nas(Primitive.DELETE_IF_UNCHANGED, incarnation) { inner.deleteIfUnchanged(share, path, size, purpose, expect) }

  // Una lettura del proprio cestino (R3): cade come un elenco.
  override suspend fun parentIdentity(share: String, dir: SharePath, purpose: Purpose): NasResult<Long?> =
    injector.nas(Primitive.LIST, incarnation) { inner.parentIdentity(share, dir, purpose) }

  override suspend fun deleteOwnTemp(share: String, temp: SharePath, purpose: Purpose): NasResult<DeleteOutcome> =
    injector.nas(Primitive.DELETE_OWN_TEMP, incarnation) { inner.deleteOwnTemp(share, temp, purpose) }

  override suspend fun rmdirIfEmpty(share: String, dir: SharePath, purpose: Purpose): NasResult<RmdirOutcome> =
    injector.nas(Primitive.RMDIR_IF_EMPTY, incarnation) { inner.rmdirIfEmpty(share, dir, purpose) }

  override suspend fun space(share: String, purpose: Purpose): NasResult<NasSpaceInfo> =
    injector.nas(Primitive.SPACE, incarnation) { inner.space(share, purpose) }
}

/** Lo scrittore di un temporaneo (ripiego dal telefono, E-D12): ogni scrittura e la chiusura sono punti dove cade un colpo. */
class ChaosTempWriter(
  private val inner: NasTempWriter,
  private val injector: Injector,
  private val incarnation: Incarnation,
) : NasTempWriter {
  override val temp: SharePath get() = inner.temp

  override suspend fun write(offset: Long, buf: ByteArray, off: Int, len: Int): NasResult<Unit> =
    injector.nas(Primitive.WRITE_TEMP, incarnation) { inner.write(offset, buf, off, len) }

  override suspend fun finish(expectedSize: Long): NasResult<TempFinish> =
    injector.nas(Primitive.FINISH_TEMP, incarnation) { inner.finish(expectedSize) }

  /** Chiudere non tocca il NAS: dopo la morte non fa niente. */
  override fun close() {
    if (!incarnation.dead) inner.close()
  }
}

/** La lettura con i colpi. Il file aperto resta avvolto: le sue letture a flusso sono punti dove la connessione puo' cadere. */
class ChaosReader(
  private val inner: NasMediaReader,
  private val injector: Injector,
  private val incarnation: Incarnation,
) : NasMediaReader {

  override suspend fun list(
    share: String,
    dir: SharePath?,
    purpose: Purpose,
    pageSize: Int,
    onPage: suspend (List<NasEntry>) -> Unit,
  ): NasResult<ListOutcome> = injector.nas(Primitive.LIST, incarnation) { inner.list(share, dir, purpose, pageSize, onPage) }

  override suspend fun stat(share: String, path: SharePath, purpose: Purpose): NasResult<NasEntry?> =
    injector.nas(Primitive.STAT, incarnation) { inner.stat(share, path, purpose) }

  override suspend fun open(share: String, path: SharePath, purpose: Purpose): NasResult<NasRandomFile> =
    when (val opened = injector.nas(Primitive.OPEN, incarnation) { inner.open(share, path, purpose) }) {
      is NasResult.Err -> opened
      is NasResult.Ok -> NasResult.Ok(ChaosRandomFile(opened.value, injector, incarnation))
    }

  override suspend fun readAll(file: NasRandomFile, sink: (ByteArray, Int) -> Unit, progress: ((Long) -> Unit)?): NasResult<Sha256> {
    // Il lettore vero riconosce solo i suoi file aperti: gli si restituisce quello che ha dato.
    val real = (file as? ChaosRandomFile)?.inner ?: file
    return injector.nas(Primitive.READ_ALL, incarnation) { inner.readAll(real, sink, progress) }
  }
}

class ChaosRandomFile(
  val inner: NasRandomFile,
  private val injector: Injector,
  private val incarnation: Incarnation,
) : NasRandomFile {
  override val size: Long get() = inner.size
  override val stamp: NasStamp get() = inner.stamp

  override suspend fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int =
    injector.read(incarnation) { inner.read(offset, buf, off, len) }

  override suspend fun readRange(range: LongRange): ByteArray {
    incarnation.checkAlive()
    return inner.readRange(range)
  }

  override fun close() {
    if (!incarnation.dead) inner.close()
  }
}

/**
 * Il diario con la morte del processo prima o dopo ogni scrittura, e un controllo in piu': ogni passo di un elemento deve essere una
 * transizione della macchina a stati ([OpStateMachine]), anche se il diario sotto non lo controllasse. [violations] racconta quelle che
 * non lo sono. Le letture passano senza colpi: se il processo muore dopo una lettura e' come se morisse prima della scrittura successiva.
 */
class ChaosStore(
  private val inner: OpsStore,
  private val injector: Injector,
  private val incarnation: Incarnation,
  private val violations: MutableList<String>,
) : OpsStore {

  override suspend fun create(accepted: AcceptedPlan, id: OpId, accountId: String, nowNas: Long): OpRecord =
    injector.store(Primitive.STORE_CREATE, incarnation) { inner.create(accepted, id, accountId, nowNas) }

  override suspend fun op(id: OpId): OpRecord? {
    incarnation.checkAlive()
    return inner.op(id)
  }

  override suspend fun opByPlanHash(hash: PlanHash): OpRecord? {
    incarnation.checkAlive()
    return inner.opByPlanHash(hash)
  }

  override suspend fun ops(states: Set<OpState>): List<OpRecord> {
    incarnation.checkAlive()
    return inner.ops(states)
  }

  override fun observeOps(): Flow<List<OpRecord>> = inner.observeOps()

  override suspend fun items(id: OpId): List<OpItemRecord> {
    incarnation.checkAlive()
    return inner.items(id)
  }

  override suspend fun itemsToResume(nowNas: Long, limit: Int): List<OpItemRecord> {
    incarnation.checkAlive()
    return inner.itemsToResume(nowNas, limit)
  }

  override suspend fun updateItem(item: OpItemRecord) {
    injector.store(Primitive.STORE_UPDATE_ITEM, incarnation) {
      val old = inner.items(item.opId).firstOrNull { it.index == item.index }
      if (old != null && !OpStateMachine.canTransition(item.flow, old.state, item.state)) {
        violations += "transizione non ammessa dal diario: ${old.state.code} -> ${item.state.code} (${item.flow.code}, elemento ${item.index})"
      }
      inner.updateItem(item)
    }
  }

  override suspend fun setOpState(id: OpId, state: OpState, errorCode: OpErrorCode?, nowNas: Long, pause: PauseReason?) {
    injector.store(Primitive.STORE_SET_OP_STATE, incarnation) { inner.setOpState(id, state, errorCode, nowNas, pause) }
  }

  override suspend fun recordTrash(entry: LocalTrashRecord) {
    injector.store(Primitive.STORE_RECORD_TRASH, incarnation) { inner.recordTrash(entry) }
  }

  override suspend fun trashEntry(trashId: String): LocalTrashRecord? {
    incarnation.checkAlive()
    return inner.trashEntry(trashId)
  }

  override fun observeTrash(): Flow<List<LocalTrashRecord>> = inner.observeTrash()

  override suspend fun markTrash(trashId: String, state: TrashEntryState, nowNas: Long) {
    injector.store(Primitive.STORE_MARK_TRASH, incarnation) { inner.markTrash(trashId, state, nowNas) }
  }

  // Una scrittura della voce del cestino come `markTrash`: la morte puo' cadere prima o dopo, stessa primitiva del banco.
  override suspend fun trashEntered(trashId: String, atNas: Long) {
    injector.store(Primitive.STORE_MARK_TRASH, incarnation) { inner.trashEntered(trashId, atNas) }
  }

  override suspend fun expiredTrash(beforeNas: Long, limit: Int): List<LocalTrashRecord> {
    incarnation.checkAlive()
    return inner.expiredTrash(beforeNas, limit)
  }

  // L'inventario di una cartella cestinata (R3) si scrive con la voce: la morte puo' cadere prima o dopo, come per `recordTrash`.
  override suspend fun saveTrashManifest(trashId: String, manifest: TrashManifest) {
    injector.store(Primitive.STORE_RECORD_TRASH, incarnation) { inner.saveTrashManifest(trashId, manifest) }
  }

  override suspend fun trashManifest(trashId: String): TrashManifest? {
    incarnation.checkAlive()
    return inner.trashManifest(trashId)
  }

  override suspend fun purgedSince(sinceNas: Long): PurgedTally {
    incarnation.checkAlive()
    return inner.purgedSince(sinceNas)
  }
}

/** I limiti dell'elenco usati dal verificatore (stessa costante del lettore vero, per non allargarla di nascosto). */
internal const val LIST_PAGE: Int = NasMediaLimits.DEFAULT_PAGE_SIZE
