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
import dev.pampa.mycloudgallery.core.model.ContentKey
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

/** I passi sul database fuori dal salvataggio del corpo, ognuno con i suoi errori di fila ([UnitRunner.queueFailed]). */
private enum class QueueStep { STAGE, MARK_DONE, PUBLISH, UNSUPPORTED }

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

  /**
   * Errori del database di fila, per passo, fuori dal salvataggio del corpo (messa in coda, segno di finita, pubblicazione, "non supportato"): come per i corpi, due di
   * fila nello stesso passo fermano la tappa (O1); un successo dello stesso passo azzera.
   */
  private val queueStreaks = IntArray(QueueStep.entries.size)

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
    if (resource != null && unit.analyzedKeys.isNotEmpty() && ctx.memory.restageDue(unit.id, unit.fingerprint)) {
      stop = restage(unit, state)
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
    val failure = finish(unit, state, stop, lost, lease)
    return UnitEnd(stop ?: failure, lost)
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
        stage(unit, item, state)?.let {
          state.done++
          return it
        }
      }
      MediaOutcome.Refused -> {
        if (markUnsupported(key, state)) {
          engineStreak = 0
          ctx.tally.unsupported++
        } else {
          queueStop(QueueStep.UNSUPPORTED)?.let { return it }
        }
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
      if (markUnsupported(item.contentKey, state)) {
        ctx.memory.strikes.remove(item.contentKey)
        ctx.tally.unsupported++
      }
    } else {
      ctx.memory.strikes[item.contentKey] = strikes
    }
    engineStreak++
    return if (engineStreak >= FacesComputeLimits.ENGINE_FAILURES_TO_STOP) engineStop() else queueStop(QueueStep.UNSUPPORTED)
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

  /**
   * "Non supportato" nel database (una scrittura come il corpo): falso = un errore del database, il media resta da fare (non si segna, si riprova alla tappa dopo) e
   * l'errore si conta come quelli della messa in coda ([queueFailed]).
   */
  private suspend fun markUnsupported(key: ContentKey, state: UnitState): Boolean = try {
    archive { ctx.store.archive.markUnsupported(key, ctx.config.recipe) }
    queueOk(QueueStep.UNSUPPORTED)
    true
  } catch (e: ArchiveClosed) {
    throw e
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    state.complete = false
    queueFailed(QueueStep.UNSUPPORTED)
    false
  }

  /**
   * Un passo sul database fuori dal salvataggio del corpo (la messa in coda dell'outbox, il segno di unita' finita, la pubblicazione) che non riesce con un errore
   * che non e' un `IOException` (SQLite pieno o bloccato: Room lo lancia come `SQLiteException`): O1. Si conta; come per i corpi, due di fila fermano la tappa
   * ([queueStop]), uno solo no. Niente si perde: il corpo e' gia' salvato e l'unita' resta da accodare e da segnare, cosi' la tappa dopo riparte da li' senza rifare un media.
   */
  private fun queueFailed(step: QueueStep) {
    counters.storeFailures.incrementAndGet()
    queueStreaks[step.ordinal]++
  }

  private fun queueOk(step: QueueStep) {
    queueStreaks[step.ordinal] = 0
  }

  /** Il codice con cui la tappa si ferma dopo due errori del database di fila nello stesso passo (come per i corpi: poco spazio o database bloccato). */
  private fun queueStop(step: QueueStep): FacesStop? = if (queueStreaks[step.ordinal] >= STREAK_TO_STOP) FacesStop.STORAGE_LOW else null

  /** L'unita' resta da rifare alla visita dopo: la messa in coda o il segno di finita non sono andati. */
  private fun unitDirty(unit: PlannedUnit, state: UnitState) {
    state.complete = false
    ctx.memory.dirty += unit.id
    ctx.memory.restaged -= unit.id
  }

  /** Dopo il salvataggio, l'outbox (solo per media del NAS: quelli del solo telefono restano locali finche' non arrivano sul NAS). Null = si continua. */
  private suspend fun stage(unit: PlannedUnit, item: FacesItem, state: UnitState): FacesStop? {
    if (item.area == FacesArea.PHONE_ONLY) return null
    try {
      parts.publisher.stage(FacesChange.Faces(item.contentKey))
      ctx.memory.staged += item.contentKey
      queueOk(QueueStep.STAGE)
    } catch (e: CancellationException) {
      throw e
    } catch (e: IllegalStateException) {
      // L'outbox si e' chiusa (generazione cambiata, chiave distrutta): niente piu' accodato (§2.8).
      throw ArchiveClosed()
    } catch (e: IOException) {
      // Il corpo e' salvato: l'unita' non si segna finita e la visita dopo lo rimette in coda.
      counters.publishFailures.incrementAndGet()
      unitDirty(unit, state)
    } catch (e: Exception) {
      // O1: un errore del database (SQLite) nell'outbox, guardato come il salvataggio del corpo.
      counters.publishFailures.incrementAndGet()
      unitDirty(unit, state)
      queueFailed(QueueStep.STAGE)
      return queueStop(QueueStep.STAGE)
    }
    return null
  }

  /**
   * I corpi propri di un'unita' che non risultano accodati in questo processo si rimettono in coda, senza ricalcolarli: dopo una morte fra il salvataggio e la messa
   * in coda, e per un media calcolato dai byte del telefono che poi e' arrivato sul NAS anche dentro un'unita' gia' finita (riga 5 della tabella del 2.3, O2: il corpo
   * proprio non era mai stato accodato e gli altri dispositivi lo ricalcolavano). Tutti i corpi propri, vincenti o no (come il pubblicatore: il contributo di un
   * dispositivo non si restringe mai); solo con la generazione e lo stato di adesso; una volta (`staged`) e idempotente (il pubblicatore non rimette due volte la stessa
   * chiave). Null = si continua; altrimenti perche' la tappa si ferma.
   */
  private suspend fun restage(unit: PlannedUnit, state: UnitState): FacesStop? {
    val todo = unit.analyzedKeys.filter { it !in ctx.memory.staged }
    var failed = false
    var stop: FacesStop? = null
    if (todo.isNotEmpty()) {
      // La generazione cambiata o l'attivazione che non c'e' piu' fermano la tappa prima di mettere in coda qualunque cosa (§2.8, D30).
      ctx.stateGate()?.let { return it }
      val own = try {
        archive { ctx.store.archive.bodiesOf(parts.deviceId, todo) }
      } catch (e: ArchiveClosed) {
        throw e
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        failed = true
        queueFailed(QueueStep.STAGE)
        stop = queueStop(QueueStep.STAGE)
        emptyMap()
      }
      for ((key, body) in own) {
        if (body.author != parts.deviceId || key in ctx.memory.staged) continue
        try {
          parts.publisher.stage(FacesChange.Faces(key))
          ctx.memory.staged += key
          queueOk(QueueStep.STAGE)
          counters.restaged.incrementAndGet()
          state.restaged++
        } catch (e: CancellationException) {
          throw e
        } catch (e: IllegalStateException) {
          throw ArchiveClosed()
        } catch (e: IOException) {
          counters.publishFailures.incrementAndGet()
          failed = true
        } catch (e: Exception) {
          // O1: un errore del database nell'outbox.
          counters.publishFailures.incrementAndGet()
          failed = true
          queueFailed(QueueStep.STAGE)
          stop = queueStop(QueueStep.STAGE)
          if (stop != null) break
        }
      }
    }
    if (failed) {
      unitDirty(unit, state)
    } else {
      ctx.memory.restaged[unit.id] = unit.fingerprint
      ctx.memory.dirty -= unit.id
    }
    return stop
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

  /** Null = tutto a posto; altrimenti il codice con cui la tappa si ferma (due errori del database di fila nel segno di finita o nella pubblicazione, O1). */
  private suspend fun finish(unit: PlannedUnit, state: UnitState, stop: FacesStop?, lost: Boolean, lease: FacesLease.Held?): FacesStop? {
    var failure: FacesStop? = null
    if (state.computed > 0 && ctx.left().isPositive()) group()
    val resource = unit.resource
    if (resource != null && !ctx.fenced() && (state.computed > 0 || state.restaged > 0)) failure = flush()
    // Una visita per la sola rimessa in coda di un'unita' gia' finita non la segna di nuovo (nessuna scrittura per niente).
    if (resource != null && state.complete && stop == null && !lost && !unit.done) failure = markDone(unit, resource) ?: failure
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
    return failure
  }

  /**
   * Il segno di unita' finita. Un errore del database che non e' un `IOException` (O1) non fa cadere la tappa: l'unita' resta da segnare (la visita dopo, senza
   * ricalcolare niente, lo rifa') e, come per i corpi, due errori di fila fermano la tappa. Null = segnata, o un errore solo.
   */
  private suspend fun markDone(unit: PlannedUnit, resource: FacesResource.WorkUnit): FacesStop? {
    try {
      ctx.store.units.markDone(resource, ctx.config.recipe, parts.clock.nasNowMillis())
      queueOk(QueueStep.MARK_DONE)
    } catch (e: CancellationException) {
      throw e
    } catch (e: IllegalStateException) {
      throw ArchiveClosed()
    } catch (e: Exception) {
      ctx.memory.dirty += unit.id
      ctx.memory.restaged -= unit.id
      queueFailed(QueueStep.MARK_DONE)
      return queueStop(QueueStep.MARK_DONE)
    }
    return null
  }

  /** A fine tappa, se in coda c'e' ancora qualcosa (una pubblicazione che non e' andata, un'altra tappa) e la recinzione non c'e': un'altra occasione. */
  suspend fun flushPending(): FacesStop? {
    if (ctx.fenced() || parts.publisher.pending.value <= 0) return null
    return flush()
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

  /** La pubblicazione a fine unita'. Null = si continua; un errore del database (O1) lo conta come la messa in coda: al secondo di fila la tappa si ferma. */
  private suspend fun flush(): FacesStop? {
    ctx.status.value = FacesStatus.Running(ctx.tally.analyzed, ctx.tally.analyzed, FacesPhase.PUBLISH)
    try {
      if (parts.publisher.flush(FlushReason.UNIT_FINISHED) is FacesPublishOutcome.Failed) {
        counters.publishFailures.incrementAndGet()
        ctx.nasDown = true
      } else {
        queueOk(QueueStep.PUBLISH)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      counters.publishFailures.incrementAndGet()
      ctx.nasDown = true
    } catch (e: Exception) {
      // O1: un errore del database (outbox, stato della replica) a meta' della pubblicazione: il NAS non c'entra, e niente si perde (l'outbox ha ancora le chiavi).
      counters.publishFailures.incrementAndGet()
      queueFailed(QueueStep.PUBLISH)
      return queueStop(QueueStep.PUBLISH)
    }
    return null
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
