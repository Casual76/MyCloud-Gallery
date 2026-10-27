package dev.pampa.mycloudgallery.core.faces.compute

import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngines
import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.FacesArea
import dev.pampa.mycloudgallery.core.faces.contract.FacesChange
import dev.pampa.mycloudgallery.core.faces.contract.FacesComputeLimits
import dev.pampa.mycloudgallery.core.faces.contract.FacesDiag
import dev.pampa.mycloudgallery.core.faces.contract.FacesItem
import dev.pampa.mycloudgallery.core.faces.contract.FacesLease
import dev.pampa.mycloudgallery.core.faces.contract.FacesPhase
import dev.pampa.mycloudgallery.core.faces.contract.FacesResource
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.FacesStop
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublishOutcome
import dev.pampa.mycloudgallery.core.faces.contract.InputWait
import java.io.IOException
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CancellationException

/** I motori dei volti di una tappa: chiesti alla prima volta che servono (mai se non c'e' niente da calcolare) e rilasciati a fine tappa. */
internal class EngineHolder(private val ctx: RunContext) {
  private var engines: FaceEngines? = null
  private var computer: FaceMediaComputer? = null
  private var failed = false

  /** Null = il motore non parte (nessun motore, o `INIT_FAILED`): la tappa si ferma con `FACES_ENGINE_FAILED`. */
  suspend fun computer(): FaceMediaComputer? {
    computer?.let { return it }
    if (failed) return null
    val made = try {
      ctx.parts.engines()
    } catch (e: CancellationException) {
      throw e
    } catch (e: AiEngineException) {
      null
    }
    if (made == null) {
      failed = true
      return null
    }
    engines = made
    return FaceMediaComputer(
      made, ctx.parts.inputs, ctx.parts.deviceId, ctx.parts.detector, ctx.parts.digest, ctx.config.recipe, ctx.counters,
    ).also { computer = it }
  }

  /** A fine tappa, a `onTrimMemory` e allo stop (IX-5): i motori si rilasciano. Idempotente. */
  suspend fun release() {
    val held = engines ?: return
    engines = null
    computer = null
    try {
      held.release()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Un rilascio che fallisce non cambia l'esito della tappa.
    }
  }
}

/** Lo stato di un'unita' mentre si lavora: cosa e' rimasto in sospeso (attese, errori, messe in coda fallite) e quanto e' stato fatto. */
private class UnitState {
  var complete = true
  var computed = 0
  var restaged = 0
  var done = 0
}

/** Come e' finita un'unita'. [stop] != null ferma la tappa; [lost] = la prenotazione e' persa: si cambia unita'. */
internal class UnitEnd(val stop: FacesStop?, val lost: Boolean = false)

/**
 * LA TAPPA DI UN'UNITA' (docs/fase8-progetto.md §2.3, tabella "La tappa di un'unita'"): prenotazione, lotti (le regole una volta per lotto),
 * media uno alla volta con il **salvataggio subito** e poi la messa in coda, fine unita' con raggruppamento, pubblicazione, segno di finita e
 * prenotazione lasciata. Ordine sempre uguale (invarianti del §5.5): **salvare** (`FacesArchive.saveBody`, una transazione per contenuto)
 * **prima** di mettere in coda (`FacesPublisher.stage`) e di pubblicare; dopo una morte del processo `FacesArchive.analyzed` dice cosa non
 * rifare. Le prenotazioni non si lasciano in un `finally`: una morte le lascia fino alla scadenza (E-I2).
 */
internal class UnitRunner(private val ctx: RunContext, private val engine: EngineHolder) {
  private val parts get() = ctx.parts
  private val counters get() = ctx.counters
  private var engineStreak = 0
  private var storeStreak = 0

  /** Quanti media si sono messi in fila per lo scaricamento in anticipo, contando dall'inizio dell'unita'. */
  private var prefetchedUntil = 0

  suspend fun run(unit: PlannedUnit): UnitEnd {
    val resource = unit.resource
    var lease: FacesLease.Held? = null
    if (unit.items.isNotEmpty() && resource != null && !ctx.fenced()) {
      when (val got = acquire(resource)) {
        is FacesLease.Held -> {
          lease = got
          // Un'unita' che altri hanno gia' tenuto puo' essere finita da loro: prima i loro dati, poi si decide cosa resta (non si rifa' il lavoro d'altri).
          if (got.number > 1) refresh()
        }
        FacesLease.Busy, FacesLease.Lost -> {
          counters.unitsBusy.incrementAndGet()
          return UnitEnd(null)
        }
        else -> counters.leaseTroubles.incrementAndGet()
      }
    }
    val state = UnitState()
    var stop: FacesStop? = null
    var lost = false
    if (resource != null && unit.analyzedKeys.isNotEmpty() && (unit.id !in ctx.memory.restaged || unit.id in ctx.memory.dirty)) {
      restage(unit, state)
    }
    var renewedAt = parts.clock.monotonicNanos()
    var index = 0
    prefetchedUntil = 0
    while (index < unit.items.size && stop == null && !lost) {
      if (index > 0) stop = ctx.gate()
      if (stop != null) break
      // Con la recinzione (D34) che si e' accesa nel frattempo niente piu' scritture sul NAS, nemmeno il rinnovo: la prenotazione scade da sola.
      if (lease != null && resource != null && !ctx.fenced() && (parts.clock.monotonicNanos() - renewedAt).nanoseconds >= ctx.config.keepAliveEvery) {
        renewedAt = parts.clock.monotonicNanos()
        when (val renewed = renew(resource, lease)) {
          is FacesLease.Held -> lease = renewed
          FacesLease.Lost -> {
            // E-I2: un altro l'ha presa. Quel che e' fatto resta salvato; si pubblica e si cambia unita'.
            counters.unitsLost.incrementAndGet()
            lease = null
            lost = true
          }
          else -> counters.leaseTroubles.incrementAndGet()
        }
        if (lost) break
      }
      val batch = unit.items.subList(index, minOf(unit.items.size, index + ctx.batchSize()))
      val stillTodo = archive { val done = ctx.store.archive.analyzed(batch.map { it.contentKey }, ctx.config.recipe); batch.filter { it.contentKey !in done } }
      prefetch(unit, index, index + batch.size)
      for (item in stillTodo) {
        stop = ctx.quickGate()
        if (stop != null) break
        stop = process(unit, item, state)
        if (stop != null) break
      }
      index += batch.size
      if (stop == null && index < unit.items.size) ctx.breath()
    }
    if (ctx.untouchable(stop)) return UnitEnd(stop)
    finish(unit, state, stop, lost, lease)
    return UnitEnd(stop, lost)
  }

  // ---- Prenotazioni -------------------------------------------------------------------------------------------------------------

  private suspend fun acquire(resource: FacesResource.WorkUnit): FacesLease {
    val got = try {
      parts.leases.acquire(ctx.generation, resource)
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      FacesLease.Failed
    }
    if (got is FacesLease.Failed) ctx.nasDown = true
    return got
  }

  private suspend fun refresh() {
    counters.refreshes.incrementAndGet()
    try {
      parts.refresh.refresh()
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      ctx.nasDown = true
    }
  }

  private suspend fun renew(resource: FacesResource.WorkUnit, held: FacesLease.Held): FacesLease = try {
    parts.leases.renew(ctx.generation, resource, held)
  } catch (e: CancellationException) {
    throw e
  } catch (e: IOException) {
    FacesLease.Failed
  }

  // ---- Un media ------------------------------------------------------------------------------------------------------------------

  /** Null = si continua; altrimenti perche' la tappa si ferma. */
  private suspend fun process(unit: PlannedUnit, item: FacesItem, state: UnitState): FacesStop? {
    val computer = engine.computer() ?: return engineStop()
    ctx.status.value = FacesStatus.Running(state.done, unit.items.size, FacesPhase.DETECT)
    val started = parts.clock.monotonicNanos()
    val key = item.contentKey
    when (val outcome = computer.compute(item)) {
      is MediaOutcome.Done -> {
        // La generazione (o l'attivazione) puo' essere cambiata mentre si calcolava: da quel momento niente piu' salvato ne' accodato (§2.8).
        ctx.stateGate()?.let { return it }
        if (!save(outcome.body)) {
          state.complete = false
          return if (storeStreak >= STREAK_TO_STOP) FacesStop.STORAGE_LOW else null
        }
        engineStreak = 0
        ctx.memory.strikes.remove(key)
        ctx.tally.analyzed++
        ctx.tally.faces += outcome.body.faces.size
        counters.facesSaved.addAndGet(outcome.body.faces.size)
        state.computed++
        ctx.rates.record(item.kind, outcome.hqUsed, parts.clock.monotonicNanos() - started)
        stage(unit, item, state)
      }
      MediaOutcome.Refused -> {
        archive { ctx.store.archive.markUnsupported(key, ctx.config.recipe) }
        engineStreak = 0
        ctx.tally.unsupported++
      }
      is MediaOutcome.Waiting -> {
        ctx.tally.waiting++
        counters.waitingPreviews.incrementAndGet()
        if (outcome.reason == InputWait.NAS) ctx.nasDown = true
        state.complete = false
      }
      MediaOutcome.Changed -> {
        ctx.tally.skipped++
        state.complete = false
      }
      MediaOutcome.Failed -> return failed(item, state)
      is MediaOutcome.Halt -> {
        state.complete = false
        return halt(outcome.stop)
      }
    }
    state.done++
    return null
  }

  /** IX-7: un errore passeggero non e' un rifiuto. Il media si riprova (al piu' [FacesComputeConfig.maxTransient] volte per processo); due di fila fermano la tappa. */
  private suspend fun failed(item: FacesItem, state: UnitState): FacesStop? {
    state.complete = false
    val strikes = (ctx.memory.strikes[item.contentKey] ?: 0) + 1
    if (strikes >= ctx.config.maxTransient) {
      ctx.memory.strikes.remove(item.contentKey)
      archive { ctx.store.archive.markUnsupported(item.contentKey, ctx.config.recipe) }
      ctx.tally.unsupported++
    } else {
      ctx.memory.strikes[item.contentKey] = strikes
    }
    engineStreak++
    return if (engineStreak >= FacesComputeLimits.ENGINE_FAILURES_TO_STOP) engineStop() else null
  }

  private fun engineStop(): FacesStop = halt(FacesStop.ENGINE_FAILED)

  private fun halt(stop: FacesStop): FacesStop {
    when (stop) {
      FacesStop.ENGINE_FAILED -> {
        counters.engineStops.incrementAndGet()
        parts.signals.report(FacesDiag.FACES_ENGINE_FAILED, 1)
      }
      FacesStop.MODELS_UNAVAILABLE -> {
        counters.modelStops.incrementAndGet()
        parts.signals.report(FacesDiag.FACES_MODEL_MISMATCH, 1)
      }
      else -> Unit
    }
    return stop
  }

  /** Il salvataggio **subito**, una transazione per contenuto. Falso = un errore del database (disco pieno...): il media resta da fare. */
  private suspend fun save(body: FaceBody): Boolean = try {
    archive { ctx.store.archive.saveBody(body) }
    storeStreak = 0
    true
  } catch (e: ArchiveClosed) {
    throw e
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    counters.storeFailures.incrementAndGet()
    storeStreak++
    false
  }

  /** Dopo il salvataggio, l'outbox (solo per media del NAS: quelli del solo telefono restano locali finche' non arrivano sul NAS). */
  private suspend fun stage(unit: PlannedUnit, item: FacesItem, state: UnitState) {
    if (item.area == FacesArea.PHONE_ONLY) return
    try {
      parts.publisher.stage(FacesChange.Faces(item.contentKey))
      ctx.memory.staged += item.contentKey
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      // Il corpo e' salvato: l'unita' non si segna finita e la visita dopo lo rimette in coda.
      counters.publishFailures.incrementAndGet()
      state.complete = false
      ctx.memory.dirty += unit.id
      ctx.memory.restaged -= unit.id
    }
  }

  /** Un corpo salvato da questo dispositivo e forse mai accodato (morte fra le due cose) si rimette in coda, senza ricalcolarlo. */
  private suspend fun restage(unit: PlannedUnit, state: UnitState) {
    val winners = archive { ctx.store.archive.winners(unit.analyzedKeys) }
    var failed = false
    for ((key, body) in winners) {
      if (body.author != parts.deviceId || key in ctx.memory.staged) continue
      try {
        parts.publisher.stage(FacesChange.Faces(key))
        ctx.memory.staged += key
        counters.restaged.incrementAndGet()
        state.restaged++
      } catch (e: CancellationException) {
        throw e
      } catch (e: IOException) {
        counters.publishFailures.incrementAndGet()
        failed = true
      }
    }
    if (failed) {
      state.complete = false
      ctx.memory.dirty += unit.id
      ctx.memory.restaged -= unit.id
    } else {
      ctx.memory.restaged += unit.id
      ctx.memory.dirty -= unit.id
    }
  }

  /** Ai media di [from] fino a [to] (esclusi) servono le anteprime: chi sa scaricarle insieme (lotti di 50, D17) le prende in anticipo. */
  private suspend fun prefetch(unit: PlannedUnit, from: Int, to: Int) {
    val pre = parts.inputs as? FacesPrefetching ?: return
    if (to <= prefetchedUntil) return
    val start = maxOf(from, prefetchedUntil)
    val end = minOf(unit.items.size, maxOf(to, start + ctx.config.prefetchAhead))
    prefetchedUntil = end
    val ahead = unit.items.subList(start, end).filter { it.area != FacesArea.PHONE_ONLY }
    if (ahead.isEmpty()) return
    try {
      pre.prefetch(ahead)
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      ctx.nasDown = true
    }
  }

  // ---- Fine unita' ---------------------------------------------------------------------------------------------------------------

  private suspend fun finish(unit: PlannedUnit, state: UnitState, stop: FacesStop?, lost: Boolean, lease: FacesLease.Held?) {
    if (state.computed > 0 && ctx.left().isPositive()) group()
    val resource = unit.resource
    if (resource != null && !ctx.fenced() && (state.computed > 0 || state.restaged > 0)) flush()
    if (resource != null && state.complete && stop == null && !lost) {
      try {
        ctx.store.units.markDone(resource, ctx.config.recipe, parts.clock.nasNowMillis())
      } catch (e: CancellationException) {
        throw e
      } catch (e: IllegalStateException) {
        throw ArchiveClosed()
      }
    }
    // Con la recinzione niente va sul NAS, nemmeno il rilascio: la prenotazione scade da sola (1 ora).
    if (lease != null && resource != null && !ctx.fenced()) {
      try {
        parts.leases.release(ctx.generation, resource, lease)
      } catch (e: CancellationException) {
        throw e
      } catch (e: IOException) {
        counters.leaseTroubles.incrementAndGet()
      }
    }
  }

  /** A fine tappa, se in coda c'e' ancora qualcosa (una pubblicazione che non e' andata, un'altra tappa) e la recinzione non c'e': un'altra occasione. */
  suspend fun flushPending() {
    if (ctx.fenced() || parts.publisher.pending.value <= 0) return
    flush()
  }

  /** A fine unita' l'assegnazione ai gruppi: se questo e' il custode il passo vero, altrimenti la vista provvisoria (D21). Non ferma mai il calcolo. */
  private suspend fun group() {
    ctx.status.value = FacesStatus.Running(ctx.tally.analyzed, ctx.tally.analyzed, FacesPhase.GROUPING)
    try {
      parts.grouping.incremental(ctx.left())
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      counters.groupingFailures.incrementAndGet()
    }
  }

  private suspend fun flush() {
    ctx.status.value = FacesStatus.Running(ctx.tally.analyzed, ctx.tally.analyzed, FacesPhase.PUBLISH)
    try {
      if (parts.publisher.flush(FlushReason.UNIT_FINISHED) is FacesPublishOutcome.Failed) {
        counters.publishFailures.incrementAndGet()
        ctx.nasDown = true
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      counters.publishFailures.incrementAndGet()
      ctx.nasDown = true
    }
  }

  /** Il lavoro con l'archivio: un archivio chiuso (generazione cambiata, chiave distrutta) lancia [ArchiveClosed] e ferma la tappa. */
  private suspend fun <T> archive(block: suspend () -> T): T = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: IllegalStateException) {
    throw ArchiveClosed()
  }

  private companion object {
    /** Due errori del database di fila fermano la tappa (disco pieno). */
    const val STREAK_TO_STOP = 2
  }
}
