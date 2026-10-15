package dev.pampa.mycloudgallery.core.nasops.testing

import dev.pampa.mycloudgallery.core.nasops.contract.AcceptedPlan
import dev.pampa.mycloudgallery.core.nasops.contract.ItemFlow
import dev.pampa.mycloudgallery.core.nasops.contract.LocalTrashRecord
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemRecord
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemState
import dev.pampa.mycloudgallery.core.nasops.contract.OpRecord
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.OpStateMachine
import dev.pampa.mycloudgallery.core.nasops.contract.OpsStore
import dev.pampa.mycloudgallery.core.nasops.contract.PlanHash
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import dev.pampa.mycloudgallery.core.nasops.contract.PurgedTally
import dev.pampa.mycloudgallery.core.nasops.contract.TrashManifest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

// I fake di core-nasops (P0-B): in src/main per i test di P12, P14, P15 e :app. Mai nella release (tools/check.ps1: nessuna classe di un
// package `testing` nel dex).

/**
 * Il diario in memoria. Si comporta come quello Room: scritture atomiche per metodo, **una transizione fuori da [OpStateMachine] e'
 * un errore di programmazione** (cosi' ogni test e il banco delle 500 interruzioni controllano gratis che l'esecuzione non salti
 * un passo), nessuna voce senza il suo [AcceptedPlan].
 *
 * Una "morte del processo" si simula buttando via gli oggetti che lo usano e tenendo **questa** istanza (il database su file): il diario
 * sopravvive, il resto no. [history] elenca ogni transizione scritta, per i controlli del banco.
 */
class InMemoryOpsStore : OpsStore {

  private val lock = Any()
  private val ops = LinkedHashMap<OpId, OpRecord>()
  private val items = HashMap<OpId, MutableList<OpItemRecord>>()
  private val trash = LinkedHashMap<String, LocalTrashRecord>()
  private val opsFlow = MutableStateFlow<List<OpRecord>>(emptyList())
  private val trashFlow = MutableStateFlow<List<LocalTrashRecord>>(emptyList())

  /** Ogni cambio di stato di un elemento, nell'ordine: (operazione, indice, da, a). */
  data class Transition(val opId: OpId, val index: Int, val flow: ItemFlow, val from: OpItemState, val to: OpItemState)

  private val transitions = mutableListOf<Transition>()

  val history: List<Transition> get() = synchronized(lock) { transitions.toList() }

  /** Quante scritture di elementi ha visto (per "ogni passo si scrive prima di farlo"). */
  var itemWrites: Int = 0
    private set

  override suspend fun create(accepted: AcceptedPlan, id: OpId, accountId: String, nowNas: Long): OpRecord = synchronized(lock) {
    // Idempotente sull'impronta finche' l'operazione non e' chiusa (P12, O1 di P15: un piano uguale a uno chiuso e' una richiesta nuova).
    ops.values.firstOrNull { it.planHash == accepted.plan.hash && !it.state.terminal }?.let { return it }
    require(id !in ops) { "Operazione gia' presente." }
    val plan = accepted.plan
    plan.items.forEach { item ->
      require(OpStateMachine.flowOf(plan.kind, item.srcShare, item.dstShare, item.srcPath, item.dstPath) == item.flow) {
        "Il cammino dell'elemento non e' quello che ricava la macchina a stati."
      }
    }
    val record = OpRecord(
      id = id,
      kind = plan.kind,
      state = OpState.PLANNED,
      planHash = plan.hash,
      itemCount = plan.items.size,
      totalBytes = plan.items.sumOf { it.size },
      errorCode = null,
      createdAtNas = nowNas,
      updatedAtNas = nowNas,
    )
    ops[id] = record
    items[id] = plan.items.mapTo(mutableListOf()) { item ->
      OpItemRecord(
        opId = id,
        index = item.index,
        kind = plan.kind,
        state = OpItemState.PLANNED,
        isFolder = item.isFolder,
        accountId = accountId,
        srcShare = item.srcShare,
        srcPath = item.srcPath,
        dstShare = item.dstShare,
        dstPath = item.dstPath,
        tempName = null,
        size = item.size,
        stamp = item.stamp,
        sha256 = null,
        trashId = item.trashId,
        attempts = 0,
        nextAttemptAtNas = null,
        errorCode = null,
        updatedAtNas = nowNas,
      )
    }
    publishOps()
    record
  }

  override suspend fun op(id: OpId): OpRecord? = synchronized(lock) { ops[id] }

  override suspend fun opByPlanHash(hash: PlanHash): OpRecord? = synchronized(lock) {
    val same = ops.values.filter { it.planHash == hash }
    same.firstOrNull { !it.state.terminal } ?: same.lastOrNull()
  }

  override suspend fun ops(states: Set<OpState>): List<OpRecord> = synchronized(lock) { ops.values.filter { it.state in states } }

  override fun observeOps(): Flow<List<OpRecord>> = opsFlow

  override suspend fun items(id: OpId): List<OpItemRecord> = synchronized(lock) { items[id].orEmpty().sortedBy { it.index } }

  override suspend fun itemsToResume(nowNas: Long, limit: Int): List<OpItemRecord> = synchronized(lock) {
    items.values.flatten()
      .filter { it.state in OpStateMachine.resumable && (it.nextAttemptAtNas == null || it.nextAttemptAtNas <= nowNas) }
      .sortedWith(compareBy({ ops[it.opId]?.createdAtNas }, { it.opId.value }, { it.index }))
      .take(limit)
  }

  override suspend fun updateItem(item: OpItemRecord) {
    synchronized(lock) {
      val list = items[item.opId] ?: error("Operazione sconosciuta.")
      val at = list.indexOfFirst { it.index == item.index }
      require(at >= 0) { "Elemento sconosciuto." }
      val old = list[at]
      require(OpStateMachine.canTransition(item.flow, old.state, item.state)) {
        "Transizione non ammessa dalla macchina a stati: ${old.state.code} -> ${item.state.code} (${item.flow.code})."
      }
      if (old.state != item.state) transitions += Transition(item.opId, item.index, item.flow, old.state, item.state)
      itemWrites++
      list[at] = item
    }
  }

  override suspend fun setOpState(id: OpId, state: OpState, errorCode: OpErrorCode?, nowNas: Long) {
    synchronized(lock) {
      val old = ops[id] ?: error("Operazione sconosciuta.")
      ops[id] = old.copy(state = state, errorCode = errorCode, updatedAtNas = nowNas)
      publishOps()
    }
  }

  override suspend fun recordTrash(entry: LocalTrashRecord) {
    synchronized(lock) {
      // Solo per un elemento di un'operazione gia' nel diario: una voce non nasce da un dato letto dal NAS.
      require(entry.opId != null && entry.opId in ops) { "Una voce del cestino nasce da un'operazione del diario." }
      trash.putIfAbsent(entry.trashId, entry)
      trashFlow.value = trash.values.toList()
    }
  }

  override suspend fun trashEntry(trashId: String): LocalTrashRecord? = synchronized(lock) { trash[trashId] }

  override fun observeTrash(): Flow<List<LocalTrashRecord>> = trashFlow.map { list -> list.filter { it.state == TrashEntryState.TRASHED } }

  override suspend fun markTrash(trashId: String, state: TrashEntryState, nowNas: Long) {
    synchronized(lock) {
      val old = trash[trashId] ?: return
      trash[trashId] = old.copy(state = state, updatedAtNas = nowNas)
      if (state != TrashEntryState.TRASHED) manifests.remove(trashId)
      trashFlow.value = trash.values.toList()
    }
  }

  private val manifests = HashMap<String, TrashManifest>()

  override suspend fun saveTrashManifest(trashId: String, manifest: TrashManifest) {
    synchronized(lock) {
      val entry = trash[trashId]
      require(entry == null || entry.state == TrashEntryState.TRASHED) { "Inventario di una voce gia' chiusa." }
      manifests[trashId] = manifest
    }
  }

  override suspend fun trashManifest(trashId: String): TrashManifest? = synchronized(lock) { manifests[trashId] }

  override suspend fun purgedSince(sinceNas: Long): PurgedTally = synchronized(lock) {
    val purged = trash.values.filter { it.state == TrashEntryState.PURGED && it.updatedAtNas >= sinceNas }
    PurgedTally(
      files = purged.sumOf { if (!it.isFolder) 1 else it.fileCount ?: PurgedTally.UNCOUNTED_FOLDER_FILES },
      bytes = purged.sumOf { it.size },
    )
  }

  override suspend fun trashEntered(trashId: String, atNas: Long) {
    synchronized(lock) {
      val old = trash[trashId] ?: return
      // Solo in avanti: i 30 giorni non si accorciano mai (R5).
      if (atNas > old.trashedAtNas) trash[trashId] = old.copy(trashedAtNas = atNas, updatedAtNas = maxOf(old.updatedAtNas, atNas))
      trashFlow.value = trash.values.toList()
    }
  }

  override suspend fun expiredTrash(beforeNas: Long, limit: Int): List<LocalTrashRecord> = synchronized(lock) {
    trash.values.filter { it.state == TrashEntryState.TRASHED && it.trashedAtNas < beforeNas }.sortedBy { it.trashedAtNas }.take(limit)
  }

  private fun publishOps() {
    opsFlow.value = ops.values.toList()
  }

  /** Tutte le voci del diario locale del cestino, in qualunque stato (per i controlli dei test). */
  fun allTrash(): List<LocalTrashRecord> = synchronized(lock) { trash.values.toList() }
}
