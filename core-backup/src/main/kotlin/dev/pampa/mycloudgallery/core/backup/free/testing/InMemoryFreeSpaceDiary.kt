package dev.pampa.mycloudgallery.core.backup.free.testing

import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunId
import dev.pampa.mycloudgallery.core.backup.free.FreeItemRecord
import dev.pampa.mycloudgallery.core.backup.free.FreeItemState
import dev.pampa.mycloudgallery.core.backup.free.FreeItemTransitions
import dev.pampa.mycloudgallery.core.backup.free.FreeRunRecord
import dev.pampa.mycloudgallery.core.backup.free.FreeSpaceDiary
import dev.pampa.mycloudgallery.core.backup.free.NewFreeRun

// Fase 5B, P9: il diario di "Libera spazio" in memoria, per i test di P9, P13 (banco della 5B) e P14. Sopravvive alla "morte del processo"
// (e' il disco) e **rifiuta cio' che il diario vero non deve mai contenere**, come il diario finto del backup: un elemento che cambia
// identita', un indice sconosciuto, una transizione fuori da [FreeItemTransitions] (per esempio un elemento gia' consegnato ad Android che
// torna "verificato", o uno liberato che torna indietro). Mai nella release: tools/check.ps1 vieta i package `testing` nel dex.

/** Il diario in memoria. [writes] conta le scritture (create e update): i punti della morte del processo. */
class InMemoryFreeSpaceDiary : FreeSpaceDiary {

  private val lock = Any()
  private val runs = LinkedHashMap<Long, FreeRunRecord>()
  private val items = HashMap<Long, MutableList<FreeItemRecord>>()
  private var next = 1L

  @Volatile var writes: Int = 0
    private set

  override suspend fun create(run: NewFreeRun, items: List<FreeItemRecord>): FreeRunRecord = synchronized(lock) {
    require(items.withIndex().all { (i, item) -> item.index == i }) { "Gli elementi di un giro hanno indici da 0 in ordine." }
    require(items.all { it.state == FreeItemState.GUARD || it.state == FreeItemState.PLANNED }) { "Un giro nuovo ha solo elementi da guardare o da verificare." }
    val record = FreeRunRecord(
      id = FreeSpaceRunId(next++),
      state = run.state,
      kind = run.kind,
      excludeRecentDays = run.excludeRecentDays,
      permanent = run.permanent,
      planHash = run.planHash,
      batchesDone = 0,
      validity = run.validity,
      blocked = run.blocked,
    )
    runs[record.id.value] = record
    this.items[record.id.value] = items.toMutableList()
    writes++
    record
  }

  override suspend fun run(id: FreeSpaceRunId): FreeRunRecord? = synchronized(lock) { runs[id.value] }

  override suspend fun items(id: FreeSpaceRunId): List<FreeItemRecord> = synchronized(lock) { items[id.value]?.toList().orEmpty() }

  override suspend fun update(run: FreeRunRecord, items: List<FreeItemRecord>) {
    synchronized(lock) {
      val before = runs[run.id.value] ?: throw IllegalArgumentException("Giro sconosciuto.")
      require(before.planHash == run.planHash && before.permanent == run.permanent && before.validity == run.validity) {
        "L'identita' di un giro non cambia."
      }
      check(!before.state.terminal || before.state == run.state) { "Un giro finito non riparte: ${before.state.code} -> ${run.state.code}." }
      check(run.batchesDone >= before.batchesDone) { "I lotti fatti non tornano indietro." }
      val list = this.items.getValue(run.id.value)
      for (item in items) {
        val old = list.getOrNull(item.index) ?: throw IllegalArgumentException("Elemento sconosciuto.")
        require(
          old.mediaId == item.mediaId && old.versionId == item.versionId && old.phoneLocation == item.phoneLocation && old.size == item.size &&
            old.phone == item.phone && old.needsGuardian == item.needsGuardian && old.plannedCopy == item.plannedCopy,
        ) { "L'identita' di un elemento non cambia." }
        check(FreeItemTransitions.canTransition(old.state, item.state)) { "Transizione non ammessa: ${old.state.code} -> ${item.state.code}." }
        check(!old.state.handedToAndroid || old.batchIndex == item.batchIndex || item.state == FreeItemState.DROPPED) {
          "Un elemento consegnato ad Android non cambia lotto."
        }
      }
      runs[run.id.value] = run
      for (item in items) list[item.index] = item
      writes++
    }
  }

  override suspend fun runs(): List<FreeRunRecord> = synchronized(lock) { runs.values.toList() }

  /** Tutti gli elementi di tutti i giri (per gli invarianti dei test). */
  fun everything(): List<Pair<FreeSpaceRunId, FreeItemRecord>> = synchronized(lock) {
    items.entries.sortedBy { it.key }.flatMap { (id, list) -> list.map { FreeSpaceRunId(id) to it } }
  }
}
