package dev.pampa.mycloudgallery.core.nasops.store

import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.dao.NasOpsDao
import dev.pampa.mycloudgallery.core.database.entity.NasOpEntity
import dev.pampa.mycloudgallery.core.database.entity.NasOpItemEntity
import dev.pampa.mycloudgallery.core.database.entity.NasTrashEntryEntity
import dev.pampa.mycloudgallery.core.database.transaction
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.enumByCode
import dev.pampa.mycloudgallery.core.model.enumByCodeOrNull
import dev.pampa.mycloudgallery.core.nasops.contract.AcceptedPlan
import dev.pampa.mycloudgallery.core.nasops.contract.LocalTrashRecord
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemRecord
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemState
import dev.pampa.mycloudgallery.core.nasops.contract.OpKind
import dev.pampa.mycloudgallery.core.nasops.contract.OpRecord
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.OpStateMachine
import dev.pampa.mycloudgallery.core.nasops.contract.OpsStore
import dev.pampa.mycloudgallery.core.nasops.contract.PlanHash
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import dev.pampa.mycloudgallery.core.nasops.contract.TrashReason
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Il diario delle operazioni sopra il database della galleria (schema v3: `nas_op`, `nas_op_item`, `nas_trash_entry`, [NasOpsDao]):
 * la porta [OpsStore] che sopravvive alla morte del processo (§2.7, D24, D25).
 *
 * Come il fake di riferimento (`InMemoryOpsStore`), e provato con le stesse prove:
 *  - [create] scrive l'operazione e **tutti** i suoi elementi in una transazione, solo da un [AcceptedPlan], idempotente sull'impronta
 *    del piano;
 *  - [updateItem] rifiuta una transizione fuori da [OpStateMachine] (errore di programmazione): il diario non contiene mai un salto;
 *  - [recordTrash] solo per un'operazione del diario, e non sovrascrive mai una voce gia' scritta (la prima scrittura vince: l'ora
 *    del cestino e' quella della prima volta).
 *
 * Il tipo dell'operazione non sta nelle righe degli elementi: si legge da `nas_op` (una volta per operazione, poi in memoria). Lo stato
 * corrente degli elementi per il controllo delle transizioni si tiene in memoria dopo la prima lettura di ogni operazione: questo
 * adattatore e' l'unico che scrive il diario, e dopo la morte del processo un adattatore nuovo rilegge dal database.
 */
class RoomOpsStore(private val database: GalleryDatabase) : OpsStore {

  private val dao: NasOpsDao = database.nasOpsDao()
  private val mutex = Mutex()
  private val kinds = HashMap<String, OpKind>()
  private val current = HashMap<String, HashMap<Int, OpItemRecord>>()

  override suspend fun create(accepted: AcceptedPlan, id: OpId, accountId: String, nowNas: Long): OpRecord = mutex.withLock {
    database.transaction {
      // Idempotente sull'impronta finche' l'operazione non e' chiusa: un piano uguale a uno chiuso e' una richiesta nuova (P15, O1).
      dao.opsInState(OpState.entries.filterNot { it.terminal }.map { it.code })
        .firstOrNull { it.planHash == accepted.plan.hash.hex }?.let { return@transaction it.toRecord() }
      require(dao.op(id.value) == null) { "Operazione gia' presente." }
      val plan = accepted.plan
      plan.items.forEach { item ->
        require(OpStateMachine.flowOf(plan.kind, item.srcShare, item.dstShare, item.srcPath, item.dstPath) == item.flow) {
          "Il cammino dell'elemento non e' quello che ricava la macchina a stati."
        }
        require(item.stamp == null || item.isFolder || item.stamp.size == item.size) { "Il timbro di un file ha la sua dimensione." }
      }
      val op = NasOpEntity(
        opId = id.value,
        kind = plan.kind.code,
        state = OpState.PLANNED.code,
        planHash = plan.hash.hex,
        itemCount = plan.items.size,
        totalBytes = plan.items.sumOf { it.size },
        errorCode = null,
        createdAt = nowNas,
        updatedAt = nowNas,
      )
      dao.insertOp(op)
      dao.insertItems(
        plan.items.map { item ->
          NasOpItemEntity(
            opId = id.value,
            itemIndex = item.index,
            state = OpItemState.PLANNED.code,
            isFolder = item.isFolder,
            accountId = accountId,
            srcShare = item.srcShare,
            srcPath = item.srcPath.value,
            dstShare = item.dstShare,
            dstPath = item.dstPath?.value,
            tempName = null,
            size = item.size,
            modifiedAt = item.stamp?.modifiedAtMillis,
            fileId = item.stamp?.fileId,
            sha256 = null,
            trashId = item.trashId,
            attempts = 0,
            nextAttemptAt = null,
            errorCode = null,
            updatedAt = nowNas,
          )
        },
      )
      kinds[id.value] = plan.kind
      op.toRecord()
    }
  }

  override suspend fun op(id: OpId): OpRecord? = dao.op(id.value)?.toRecord()

  /** L'operazione non chiusa con questa impronta, o l'ultima chiusa. */
  override suspend fun opByPlanHash(hash: PlanHash): OpRecord? {
    val same = dao.opsInState(OpState.entries.map { it.code }).filter { it.planHash == hash.hex }.map { it.toRecord() }
    return same.firstOrNull { !it.state.terminal } ?: same.lastOrNull()
  }

  override suspend fun ops(states: Set<OpState>): List<OpRecord> =
    if (states.isEmpty()) emptyList() else dao.opsInState(states.map { it.code }).map { it.toRecord() }

  override fun observeOps(): Flow<List<OpRecord>> = dao.observeOps().map { list -> list.map { it.toRecord() } }

  override suspend fun items(id: OpId): List<OpItemRecord> {
    val kind = kindOf(id.value) ?: return emptyList()
    return dao.items(id.value).map { it.toRecord(kind) }
  }

  override suspend fun itemsToResume(nowNas: Long, limit: Int): List<OpItemRecord> {
    val states = OpStateMachine.resumable.map { it.code }
    val rows = dao.itemsToResume(states, nowNas, limit)
    return rows.mapNotNull { row -> kindOf(row.opId)?.let { row.toRecord(it) } }
      .sortedWith(compareBy({ it.opId.value }, { it.index }))
  }

  override suspend fun updateItem(item: OpItemRecord) {
    mutex.withLock {
      val known = currentOf(item.opId.value)
      val old = known[item.index] ?: throw IllegalArgumentException("Elemento sconosciuto.")
      require(OpStateMachine.canTransition(item.flow, old.state, item.state)) {
        "Transizione non ammessa dalla macchina a stati: ${old.state.code} -> ${item.state.code} (${item.flow.code})."
      }
      require(item.kind == old.kind && item.srcShare == old.srcShare && item.srcPath == old.srcPath) { "L'elemento non cambia identita'." }
      val stamp = item.stamp
      require(stamp == null || item.isFolder || item.size == null || stamp.size == item.size) { "Il timbro di un file ha la sua dimensione." }
      dao.updateItem(item.toEntity())
      known[item.index] = item
    }
  }

  override suspend fun setOpState(id: OpId, state: OpState, errorCode: OpErrorCode?, nowNas: Long) {
    dao.setOpState(id.value, state.code, errorCode?.code, nowNas)
  }

  // --- Diario locale del cestino ---------------------------------------------------------------------------------------------

  override suspend fun recordTrash(entry: LocalTrashRecord) {
    mutex.withLock {
      database.transaction {
        // Solo per un elemento di un'operazione gia' nel diario: una voce non nasce mai da un dato letto dal NAS.
        val opId = requireNotNull(entry.opId) { "Una voce del cestino nasce da un'operazione del diario." }
        require(dao.op(opId.value) != null) { "Una voce del cestino nasce da un'operazione del diario." }
        if (dao.trashEntry(entry.trashId) == null) dao.insertTrashEntry(entry.toEntity())
      }
    }
  }

  override suspend fun trashEntry(trashId: String): LocalTrashRecord? = dao.trashEntry(trashId)?.toRecord()

  override fun observeTrash(): Flow<List<LocalTrashRecord>> = dao.observeTrash().map { list -> list.map { it.toRecord() } }

  override suspend fun markTrash(trashId: String, state: TrashEntryState, nowNas: Long) {
    mutex.withLock {
      database.transaction {
        val old = dao.trashEntry(trashId) ?: return@transaction
        dao.updateTrashEntry(old.copy(state = state.code, updatedAt = nowNas))
      }
    }
  }

  override suspend fun expiredTrash(beforeNas: Long, limit: Int): List<LocalTrashRecord> = dao.expiredTrash(beforeNas, limit).map { it.toRecord() }

  // --- conversioni ------------------------------------------------------------------------------------------------------------

  private suspend fun kindOf(opId: String): OpKind? {
    synchronized(kinds) { kinds[opId] }?.let { return it }
    val kind = dao.op(opId)?.kind?.let { enumByCode<OpKind>(it) } ?: return null
    synchronized(kinds) { kinds[opId] = kind }
    return kind
  }

  private suspend fun currentOf(opId: String): HashMap<Int, OpItemRecord> {
    current[opId]?.let { return it }
    val kind = kindOf(opId) ?: throw IllegalStateException("Operazione sconosciuta.")
    val loaded = dao.items(opId).associateTo(HashMap()) { it.itemIndex to it.toRecord(kind) }
    current[opId] = loaded
    return loaded
  }

  private fun NasOpEntity.toRecord() = OpRecord(
    id = OpId(opId),
    kind = enumByCode(kind),
    state = enumByCode(state),
    planHash = PlanHash(planHash),
    itemCount = itemCount,
    totalBytes = totalBytes,
    errorCode = enumByCodeOrNull<OpErrorCode>(errorCode),
    createdAtNas = createdAt,
    updatedAtNas = updatedAt,
  )

  private fun NasOpItemEntity.toRecord(kind: OpKind) = OpItemRecord(
    opId = OpId(opId),
    index = itemIndex,
    kind = kind,
    state = enumByCode(state),
    isFolder = isFolder,
    accountId = accountId,
    srcShare = srcShare,
    srcPath = SharePath.parse(srcPath),
    dstShare = dstShare,
    dstPath = dstPath?.let(SharePath::parse),
    tempName = tempName?.let(SharePath::parse),
    size = size,
    stamp = modifiedAt?.let { NasStamp(size ?: 0, it, fileId) },
    sha256 = sha256?.let(::Sha256),
    trashId = trashId,
    attempts = attempts,
    nextAttemptAtNas = nextAttemptAt,
    errorCode = enumByCodeOrNull<OpErrorCode>(errorCode),
    updatedAtNas = updatedAt,
  )

  private fun OpItemRecord.toEntity() = NasOpItemEntity(
    opId = opId.value,
    itemIndex = index,
    state = state.code,
    isFolder = isFolder,
    accountId = accountId,
    srcShare = srcShare,
    srcPath = srcPath.value,
    dstShare = dstShare,
    dstPath = dstPath?.value,
    tempName = tempName?.value,
    size = size ?: stamp?.size,
    modifiedAt = stamp?.modifiedAtMillis,
    fileId = stamp?.fileId,
    sha256 = sha256?.hex,
    trashId = trashId,
    attempts = attempts,
    nextAttemptAt = nextAttemptAtNas,
    errorCode = errorCode?.code,
    updatedAt = updatedAtNas,
  )

  private fun NasTrashEntryEntity.toRecord() = LocalTrashRecord(
    trashId = trashId,
    accountId = accountId,
    shareKey = shareKey,
    shareName = shareName,
    originalPath = SharePath.parse(originalPath),
    trashedPath = SharePath.parse(trashedPath),
    reason = enumByCode<TrashReason>(reason),
    isFolder = isFolder,
    size = size,
    fileCount = fileCount,
    trashedAtNas = trashedAt,
    state = enumByCode(state),
    opId = opId?.let(::OpId),
    updatedAtNas = updatedAt,
  )

  private fun LocalTrashRecord.toEntity() = NasTrashEntryEntity(
    trashId = trashId,
    accountId = accountId,
    shareKey = shareKey,
    shareName = shareName,
    originalPath = originalPath.value,
    trashedPath = trashedPath.value,
    reason = reason.code,
    isFolder = isFolder,
    size = size,
    fileCount = fileCount,
    trashedAt = trashedAtNas,
    state = state.code,
    opId = opId?.value,
    updatedAt = updatedAtNas,
  )
}
