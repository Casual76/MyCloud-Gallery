package dev.pampa.mycloudgallery.core.ai.compute

import dev.pampa.mycloudgallery.core.ai.contract.AiBuffer
import dev.pampa.mycloudgallery.core.ai.contract.AiCandidate
import dev.pampa.mycloudgallery.core.ai.contract.AiDiag
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.contract.AiRunResult
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.AiStop
import dev.pampa.mycloudgallery.core.ai.contract.AiWaitReason
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.KeepAliveResult
import dev.pampa.mycloudgallery.core.index.contract.UnitClaim
import dev.pampa.mycloudgallery.core.index.contract.UnitOutcome
import dev.pampa.mycloudgallery.core.index.contract.WorkPass
import dev.pampa.mycloudgallery.core.index.contract.WorkUnitKey
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Una tappa del calcolo (§2.2, tabella "La tappa di un'unita'"). Vive per una chiamata di [AiComputeEngine.run]: tempo, regole,
 * conteggi. Le tre strade: [share] (unita' con prenotazione, o solo buffer con `LOCAL_BUFFER_ONLY`), [phoneOnly].
 */
internal class ComputeSession(
  private val parts: AiComputeParts,
  computer: MediaComputer,
  private val recipe: AiRecipe,
  private val config: AiComputeConfig,
  private val counters: AiComputeCounters,
  private val nanos: () -> Long,
  private val pause: suspend (Duration) -> Unit,
  private val budget: Duration,
  private val control: AiRunControl,
  private val state: MutableStateFlow<AiStatus>,
  /** IX-7: gli errori passeggeri del motore per chiave, per tutto il processo (li tiene il motore). */
  strikes: MutableMap<ContentKey, Int> = HashMap(),
) {
  private val start = nanos()
  private val tally = Tally()
  private val worker = ItemWorker(parts, computer, recipe, counters, strikes)
  private var pace: AiPace.Go = AiPace.Go()

  private fun elapsed(): Duration = (nanos() - start).nanoseconds

  private fun left(): Duration = budget - elapsed()

  fun result(stop: AiStop?): AiRunResult =
    AiRunResult(tally.computed, tally.skipped, tally.waiting, tally.failed, stop, parts.images.backend.value)

  /** E-A17: sotto 1 GiB libero il buffer si svuota e il calcolo si ferma. */
  suspend fun storageLow(): Boolean {
    if (parts.freeSpace.usableBytes() >= AiBuffer.MIN_FREE_BYTES) return false
    val buffer = parts.buffer
    if (buffer is DiskAiBuffer) buffer.clear() else buffer.evict(tally.finished)
    counters.storageLow.incrementAndGet()
    // IX-4: un problema con la sua azione ("Libera spazio"), non solo un'attesa.
    state.value = AiStatus.Problem(AiDiag.AI_STORAGE_LOW)
    return true
  }

  /** Il controllo prima di ogni lotto: tempo, spazio, ruolo, regole. Null = si continua (con [pace] aggiornato). */
  private suspend fun gate(): AiStop? {
    if (!parts.roles.indexer.value) return AiStop.ROLE_OFF
    if (!left().isPositive()) return AiStop.BUDGET
    if (storageLow()) return AiStop.STORAGE_LOW
    return when (val p = control.check()) {
      is AiPace.Halt -> p.stop
      is AiPace.Go -> {
        pace = p
        null
      }
    }
  }

  /** Il controllo prima di ogni media (E-A13: il ruolo spento ferma al media dopo; il tempo idem). */
  private fun quickGate(): AiStop? = when {
    !parts.roles.indexer.value -> AiStop.ROLE_OFF
    !left().isPositive() -> AiStop.BUDGET
    else -> null
  }

  private fun batchSize() = if (pace.reduced) config.reducedBatch else config.batch

  private suspend fun breath() {
    if (pace.reduced && config.reducedPause.isPositive()) pause(config.reducedPause)
  }

  // ---- Share -----------------------------------------------------------------------------------------------------------

  /**
   * AI-D1 (trovato da P10): una morte fra il salvataggio di un media e lo svuotamento di fine unita' (o di fine tappa del telefono)
   * lasciava le sue anteprime nel buffer per sempre, perche' [Tally.finished] conosce solo i media di questa tappa e un media salvato
   * non torna piu' candidato. A inizio tappa si tolgono quindi i blob dei media che [AiComputeParts.results] dice finiti qui; in una
   * share con la rete anche quelli che un altro indicizzatore ha gia' fatto (un'unita' finita da lui non torna piu' da noi).
   */
  private suspend fun pruneFinished(share: String?) {
    val buffer = parts.buffer as? DiskAiBuffer ?: return
    val done = try {
      val keys = buffer.keys().toList()
      val others = if (share == null || keys.isEmpty()) emptyMap() else othersOf(share, keys)
      keys.filter { worker.finishedHere(it, share, others[it].orEmpty()) }
    } catch (e: IOException) {
      return
    }
    if (done.isNotEmpty()) buffer.evict(done)
  }

  suspend fun share(share: String): AiRunResult {
    gate()?.let { return finish(it) }
    pruneFinished(share.takeUnless { pace.localOnly })
    if (pace.localOnly) return bufferOnly(share)
    releasePending(share)
    // Le unita' si lasciano tutte a fine tappa (cosi' un'unita' con media ancora in attesa non torna in questo giro e non blocca le
    // altre). Una morte del processo le lascia prenotate fino alla scadenza (E-I2): nessun `finally`.
    val ends = ArrayList<Pair<UnitClaim, UnitOutcome>>()
    var stop: AiStop? = null
    val seen = HashSet<WorkUnitKey>()
    for (round in 0 until config.maxUnitsPerRun) {
      // Le regole si chiedono una volta per lotto: la prima l'ha gia' chiesta l'inizio della tappa.
      if (round > 0) stop = gate()
      if (stop != null) break
      if (pace.localOnly) {
        stop = AiStop.BUFFER_ONLY
        break
      }
      val claim = try {
        parts.units.next(share, WorkPass.AI, parts.priority)
      } catch (e: IOException) {
        stop = AiStop.NAS_UNREACHABLE
        break
      } ?: break
      if (!seen.add(claim.key)) {
        ends += claim to UnitOutcome.Interrupted("AGAIN")
        break
      }
      val end = unit(share, claim)
      when {
        end.lost -> Unit
        end.stop != null -> {
          ends += end.claim to UnitOutcome.Interrupted(end.stop.name)
          stop = end.stop
        }
        else -> ends += end.claim to UnitOutcome.Completed(end.computed, end.failed)
      }
      if (stop != null) break
    }
    for ((claim, outcome) in ends) safeFinish(claim, outcome)
    if (stop == AiStop.BUFFER_ONLY) return bufferOnly(share)
    return finish(stop)
  }

  /** D8, F4-D31: le voci in attesa di una share che ora si apre si pubblicano, **senza ricalcolo**. */
  private suspend fun releasePending(share: String) {
    val moved = try {
      parts.publisher.releasePending(share)
    } catch (e: IOException) {
      0
    }
    if (moved > 0) {
      counters.pendingReleased.addAndGet(moved)
      flush(share, FlushReason.UNIT_FINISHED)
    }
  }

  private class UnitEnd(val claim: UnitClaim, val stop: AiStop?, val lost: Boolean, val computed: Int, val failed: Int)

  private suspend fun unit(share: String, first: UnitClaim): UnitEnd {
    var claim = first
    var renewedAt = nanos()
    val computedBefore = tally.computed
    val failedBefore = tally.failed
    val keys = claim.items.map { it.contentKey }
    val others = othersOf(share, keys)
    val sources = try {
      parts.sources.sources(keys)
    } catch (e: IOException) {
      emptyMap()
    }
    var queue = claim.items
    var index = 0
    var stop: AiStop? = null
    var lastDone: ContentKey? = null
    while (index < queue.size) {
      // Il primo lotto dell'unita' parte con il verdetto appena chiesto da chi ha preso l'unita'.
      if (index > 0) stop = gate()
      if (stop != null) break
      if (pace.localOnly) {
        stop = AiStop.BUFFER_ONLY
        break
      }
      if ((nanos() - renewedAt).nanoseconds >= config.keepAliveEvery) {
        renewedAt = nanos()
        when (val r = runCatchingIo { parts.units.keepAlive(claim) }) {
          is KeepAliveResult.Renewed -> {
            claim = r.claim
            val still = r.claim.items.mapTo(HashSet()) { it.contentKey }
            queue = queue.take(index) + queue.drop(index).filter { it.contentKey in still }
          }
          KeepAliveResult.Lost -> {
            counters.unitsLost.incrementAndGet()
            flush(share, FlushReason.UNIT_FINISHED)
            return UnitEnd(claim, null, lost = true, 0, 0)
          }
          null -> Unit
        }
      }
      val batch = queue.subList(index, minOf(queue.size, index + batchSize()))
      // IX-6: le voci del lotto (ripubblicate senza ricalcolo o appena calcolate) vanno nell'outbox insieme: M3 una volta per lotto.
      val staged = ArrayList<dev.pampa.mycloudgallery.core.index.ai.AiEntry>()
      val planned = batch.map { item ->
        val missing = item.missing.mapNotNullTo(LinkedHashSet()) { AiClass.of(it) }
        worker.plan(share, item.contentKey, item.kind, sources[item.contentKey] ?: MediaSource.OTHER, missing, others[item.contentKey].orEmpty(), staged)
      }
      worker.prefetch(share, planned, left(), config.fetchBatch)
      for (item in planned) {
        quickGate()?.let { stop = it }
        if (stop != null) break
        val end = worker.process(share, item, localOnly = false, staged = staged)
        if (end == ItemEnd.MODEL_MISSING) {
          stop = AiStop.MODEL_MISSING
          break
        }
        tally.count(end, item.key)
        lastDone = item.key
        running(queue.size)
      }
      index += batch.size
      stageAll(share, staged)
      flush(share, FlushReason.PERIODIC)
      control.checkpoint(AiCheckpoint(share, claim.key.resource, lastDone?.hex))
      if (stop != null) break
      breath()
    }
    flush(share, FlushReason.UNIT_FINISHED)
    parts.buffer.evict(tally.finished)
    return UnitEnd(claim, stop, lost = false, tally.computed - computedBefore, tally.failed - failedBefore)
  }

  private suspend fun othersOf(share: String, keys: List<ContentKey>): Map<ContentKey, Set<AiClass>> = try {
    val vec = parts.doneByOthers.done(share, keys, recipe.version)
    if (recipe.ocrVersion == recipe.version) {
      vec
    } else {
      val ocr = parts.doneByOthers.done(share, keys, recipe.ocrVersion)
      keys.associateWith { k -> vec[k].orEmpty().filter { it == AiClass.VEC }.toSet() + ocr[k].orEmpty().filter { it == AiClass.OCR } }
    }
  } catch (e: IOException) {
    emptyMap()
  }

  /** IX-6: le voci di un lotto nell'outbox in una chiamata (M3 una volta). Una morte prima lascia i risultati salvati: si ripubblicano. */
  private suspend fun stageAll(share: String, staged: List<dev.pampa.mycloudgallery.core.index.ai.AiEntry>) {
    if (staged.isEmpty()) return
    try {
      parts.publisher.stage(share, staged)
    } catch (e: IOException) {
      counters.flushFailures.incrementAndGet()
    }
  }

  private suspend fun flush(share: String, reason: FlushReason) {
    try {
      parts.publisher.flush(share, reason)
    } catch (e: IOException) {
      counters.flushFailures.incrementAndGet()
    }
  }

  private suspend fun safeFinish(claim: UnitClaim, outcome: UnitOutcome) {
    runCatchingIo { parts.units.finish(claim, outcome) }
  }

  private suspend fun <T> runCatchingIo(block: suspend () -> T): T? = try {
    block()
  } catch (e: IOException) {
    null
  }

  // ---- Solo buffer e telefono --------------------------------------------------------------------------------------------

  /**
   * E-A6: senza rete utile si calcola cio' che c'e' gia' nel buffer per questa share (dal catalogo locale, [AiCandidate]); niente
   * unita', niente NAS. I risultati vanno nell'outbox e si pubblicano al primo giro con il NAS.
   */
  private suspend fun bufferOnly(share: String): AiRunResult {
    val list = parts.candidates.forShare(share, recipe.version, config.localBatchLimit)
    val others = othersOf(share, list.map { it.contentKey })
    val stop = local(share, list, others) { candidate -> parts.buffer.get(candidate.contentKey, inputClassOf(candidate)) != null }
    if (stop != null && stop != AiStop.BUFFER_ONLY) return finish(stop)
    state.value = AiStatus.BufferOnlyDone(tally.computed)
    return result(AiStop.BUFFER_ONLY)
  }

  /** I media del solo telefono: dai byte del telefono, risultati solo locali (nessuna pubblicazione). */
  suspend fun phoneOnly(): AiRunResult {
    gate()?.let { return finish(it) }
    pruneFinished(null)
    val list = parts.candidates.phoneOnly(recipe.version, config.localBatchLimit)
    return finish(local(null, list, emptyMap()) { true })
  }

  private suspend fun local(share: String?, list: List<AiCandidate>, others: Map<ContentKey, Set<AiClass>>, usable: suspend (AiCandidate) -> Boolean): AiStop? {
    val chosen = list.filter { usable(it) }
    var index = 0
    while (index < chosen.size) {
      if (index > 0) gate()?.let { return it }
      val batch = chosen.subList(index, minOf(chosen.size, index + batchSize()))
      val staged = ArrayList<dev.pampa.mycloudgallery.core.index.ai.AiEntry>()
      for (candidate in batch) {
        quickGate()?.let {
          if (share != null) stageAll(share, staged)
          return it
        }
        val planned = worker.plan(share, candidate.contentKey, candidate.kind, candidate.source, candidate.missing, others[candidate.contentKey].orEmpty(), staged)
        val end = worker.process(share, planned, localOnly = true, staged = staged)
        if (end == ItemEnd.MODEL_MISSING) {
          if (share != null) stageAll(share, staged)
          return AiStop.MODEL_MISSING
        }
        tally.count(end, candidate.contentKey)
        running(chosen.size)
      }
      if (share != null) stageAll(share, staged)
      index += batch.size
      control.checkpoint(AiCheckpoint(share ?: PHONE, null, batch.lastOrNull()?.contentKey?.hex))
      breath()
    }
    parts.buffer.evict(tally.finished)
    return null
  }

  private fun inputClassOf(candidate: AiCandidate) =
    Planned(candidate.contentKey, candidate.kind, candidate.source, emptySet()).inputClass

  private fun running(total: Int) {
    val done = tally.computed + tally.skipped + tally.failed + tally.waiting
    val seconds = elapsed().inWholeMilliseconds / 1000.0
    val rate = if (seconds > 0 && tally.computed > 0) tally.computed / seconds else 0.0
    val eta = if (rate > 0) ((total - done).coerceAtLeast(0) / rate / 60.0).toInt() else null
    state.value = AiStatus.Running(done, maxOf(total, done), eta, parts.images.backend.value)
  }

  private fun finish(stop: AiStop?): AiRunResult {
    state.value = if (stop == null) AiStatus.Idle(null) else AiComputeEngine.waitingFor(stop)
    return result(stop)
  }

  private companion object {
    const val PHONE = "telefono"
  }
}
