package dev.pampa.mycloudgallery.core.albums.testing

import dev.pampa.mycloudgallery.core.albums.contract.ShareDiary
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemRecord
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpId
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpRecord
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanHash
import dev.pampa.mycloudgallery.core.albums.contract.ShareStateMachine

/** La morte simulata del processo a un salvataggio del diario (banco delle interruzioni, P5). */
class SimulatedDeath : RuntimeException("morte simulata del processo")

/**
 * Il diario della condivisione in memoria (Fase 7, P0): quello dell'esecutore di riferimento e dei banchi. Sopravvive a un
 * `InMemoryShareRunner` nuovo (e' il "disco"): cosi' si prova la ripresa dopo la morte del processo. Ogni passo nuovo deve essere ammesso
 * da [ShareStateMachine] (un passo saltato e' un errore di programmazione, come nel diario vero).
 *
 * [crashAtSave]: al salvataggio numero N il processo "muore" ([SimulatedDeath]); con [crashAfterWrite] la scrittura arriva sul disco
 * prima della morte, senza no.
 */
class InMemoryShareDiary : ShareDiary {
  private val ops = LinkedHashMap<ShareOpId, ShareOpRecord>()
  private val items = LinkedHashMap<ShareOpId, MutableList<ShareItemRecord>>()

  var saves: Int = 0
    private set
  var crashAtSave: Int? = null
  var crashAfterWrite: Boolean = false

  private inline fun save(write: () -> Unit) {
    saves++
    val crash = crashAtSave == saves
    if (crash && !crashAfterWrite) throw SimulatedDeath()
    write()
    if (crash) throw SimulatedDeath()
  }

  /** Tutte le operazioni (anche finite) e tutti gli elementi: per gli invarianti del §5.5. */
  fun allOps(): List<ShareOpRecord> = ops.values.toList()

  fun allItems(): List<ShareItemRecord> = items.values.flatten()

  override suspend fun create(op: ShareOpRecord, items: List<ShareItemRecord>) = save {
    require(op.id !in ops) { "Operazione gia' nel diario." }
    ops[op.id] = op
    this.items[op.id] = items.toMutableList()
  }

  override suspend fun opByPlan(planHash: SharePlanHash): ShareOpRecord? = ops.values.firstOrNull { it.planHash == planHash }

  override suspend fun openOps(): List<ShareOpRecord> = ops.values.filter { !it.finished }

  /** Le chiuse, dall'ultima creata (l'ordine d'inserimento: il diario vero ordina per ora dell'ultima scrittura). */
  override suspend fun closedOps(limit: Int): List<ShareOpRecord> = ops.values.filter { it.finished }.reversed().take(limit)

  override suspend fun items(op: ShareOpId): List<ShareItemRecord> = items[op].orEmpty().toList()

  override suspend fun update(item: ShareItemRecord) = save {
    val list = items[item.op] ?: error("Operazione sconosciuta.")
    val at = list.indexOfFirst { it.index == item.index }
    check(at >= 0) { "Elemento sconosciuto." }
    val before = list[at]
    check(before.state == item.state || ShareStateMachine.allowed(item.action, before.state, item.state)) {
      "Passo non ammesso: ${before.state.code} -> ${item.state.code}"
    }
    list[at] = item
  }

  override suspend fun finish(op: ShareOpId) = save {
    val record = ops[op] ?: error("Operazione sconosciuta.")
    ops[op] = record.copy(finished = true)
  }
}
