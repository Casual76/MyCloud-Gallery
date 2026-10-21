package dev.pampa.mycloudgallery.core.prepare.run

import android.media.MediaDataSource
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.index.contract.BlobPut
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.IndexPublisher
import dev.pampa.mycloudgallery.core.index.contract.KeepAliveResult
import dev.pampa.mycloudgallery.core.index.contract.MetaChange
import dev.pampa.mycloudgallery.core.index.contract.UnitClaim
import dev.pampa.mycloudgallery.core.index.contract.UnitOutcome
import dev.pampa.mycloudgallery.core.index.contract.UnitPriority
import dev.pampa.mycloudgallery.core.index.contract.WorkItem
import dev.pampa.mycloudgallery.core.index.contract.WorkPass
import dev.pampa.mycloudgallery.core.index.contract.WorkUnits
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.prepare.contract.AnalysisSink
import dev.pampa.mycloudgallery.core.prepare.contract.PrepAttempts
import dev.pampa.mycloudgallery.core.prepare.contract.PrepBudget
import dev.pampa.mycloudgallery.core.prepare.contract.PrepClock
import dev.pampa.mycloudgallery.core.prepare.contract.PrepControl
import dev.pampa.mycloudgallery.core.prepare.contract.PrepFailCode
import dev.pampa.mycloudgallery.core.prepare.contract.PrepInputs
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOpen
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOutput
import dev.pampa.mycloudgallery.core.prepare.contract.PrepRecordSource
import dev.pampa.mycloudgallery.core.prepare.contract.PrepResult
import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import dev.pampa.mycloudgallery.core.prepare.contract.PrepStop
import dev.pampa.mycloudgallery.core.prepare.contract.PrepStorage
import dev.pampa.mycloudgallery.core.prepare.contract.PrepVerdict
import dev.pampa.mycloudgallery.core.prepare.contract.PreparationRun
import dev.pampa.mycloudgallery.core.prepare.contract.PrepareSource
import dev.pampa.mycloudgallery.core.prepare.contract.Preparer
import dev.pampa.mycloudgallery.core.prepare.contract.RunResult
import dev.pampa.mycloudgallery.core.trust.data.MetaFile
import dev.pampa.mycloudgallery.core.trust.data.PrepFailure
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Il giro di preparazione vero (§2.4, D13, D14, D22): prende le unita' di lavoro una dopo l'altra (`WorkUnits.next`: prima
 * quelle con media visibili, poi dal mese piu' recente), per ogni media apre l'originale ([PrepInputs]), lo prepara
 * ([Preparer]) e mette cio' che ne esce nell'outbox ([IndexPublisher.stage]): il media in corso e' l'unico che un'interruzione
 * puo' far perdere. A fine unita' `flush` e `finish`.
 *
 * - **Parallelismo**: al massimo [PrepBudget.parallelMedia] media insieme (uno solo con [PrepVerdict.Slow]).
 * - **Tappe**: il giro si ferma a `budget` (orologio monotono) o quando le regole dicono [PrepVerdict.Stop]; l'unita' in corso
 *   si chiude `Interrupted` e il lavoro fatto e' gia' nell'outbox.
 * - **Prenotazione**: rinnovata ogni 20 minuti; se la perdiamo si finisce il media in corso e si lascia l'unita'.
 * - **Pubblicazione**: dalle regole di `core-index` (D31: sulle share vere resta in locale). Un media il cui contenuto non
 *   corrisponde piu' alla chiave del lavoro (il file e' cambiato dopo l'elenco) non pubblica niente (D8).
 * - **Fallimenti**: `DECODER_MISSING` e `TOO_LARGE` si pubblicano subito nel record (D14, E-M3); `DECODE_FAILED` dopo cinque
 *   tentativi (E-M10); `NAS_UNREACHABLE` ferma il giro senza contare; `SOURCE_CHANGED` si salta.
 *
 * Non scrive mai niente sui media e non conosce il NAS. L'ora e' quella monotona di [clock].
 */
class DefaultPreparationRun(
  private val workUnits: WorkUnits,
  private val inputs: PrepInputs,
  private val preparer: Preparer,
  private val publisher: IndexPublisher,
  private val records: PrepRecordSource,
  private val limits: PrepBudget,
  private val decoderFamily: String,
  private val attempts: PrepAttempts = PrepAttempts.NONE,
  private val control: PrepControl = PrepControl.ALWAYS,
  private val clock: PrepClock = PrepClock.SYSTEM,
  private val renewEvery: Duration = 20.minutes,
  private val slowPause: Duration = 300.milliseconds,
  private val onStart: () -> Unit = {},
  private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
  /** R2: lo spazio libero del telefono; sotto [PrepStorage.MIN_FREE_BYTES] il giro si ferma con [PrepStop.STORAGE]. */
  private val storage: PrepStorage? = null,
  /**
   * Fase 6 (D10, P5): dove vanno `analysis` e `frames` appena preparati quando questo dispositivo e' Indicizzatore (il buffer di
   * core-ai, legato da :app). [AnalysisSink.NONE] = nessun buffer. Non blocca e non fa fallire mai la preparazione.
   */
  private val analysisSink: AnalysisSink = AnalysisSink.NONE,
) : PreparationRun {

  private class Tally {
    val units = AtomicInteger()
    val prepared = AtomicInteger()
    val failed = AtomicInteger()
    val handedOver = AtomicInteger()
    val bytes = AtomicLong()

    /** I media gia' provati in questo giro (chiavi di contenuto): un'unita' che torna con gli stessi non si rifa'. */
    val attempted: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
  }

  private class UnitState(var claim: UnitClaim) {
    val halt = AtomicReference<PrepStop?>(null)
    @Volatile var leaseLost = false
    val prepared = AtomicInteger()
    val failed = AtomicInteger()
    var lastRenewNanos = 0L
    val renewLock = Mutex()
  }

  override suspend fun run(share: String, budget: Duration, priority: UnitPriority): RunResult {
    val tally = Tally()
    val started = clock.nanos()
    fun elapsed(): Duration = (clock.nanos() - started).nanoseconds
    onStart()
    var stopped: PrepStop? = null
    while (stopped == null) {
      val verdict = control.verdict()
      if (verdict is PrepVerdict.Stop) {
        stopped = verdict.by
        break
      }
      if (elapsed() >= budget) {
        stopped = PrepStop.BUDGET
        break
      }
      val claim = workUnits.next(share, WorkPass.PREP, priority)
      if (claim == null) {
        stopped = PrepStop.NO_WORK
        break
      }
      tally.units.incrementAndGet()
      stopped = processUnit(share, claim, tally, budget, ::elapsed)
    }
    return RunResult(
      units = tally.units.get(),
      prepared = tally.prepared.get(),
      failed = tally.failed.get(),
      handedOver = tally.handedOver.get(),
      bytesRead = tally.bytes.get(),
      stoppedBy = stopped,
    )
  }

  // --- Un'unita' ---------------------------------------------------------------------------------------------------

  /** Lavora un'unita'. Restituisce perche' il giro deve fermarsi, o null se si puo' prendere la prossima. */
  private suspend fun processUnit(share: String, claim: UnitClaim, tally: Tally, budget: Duration, elapsed: () -> Duration): PrepStop? {
    val unit = UnitState(claim).also { it.lastRenewNanos = clock.nanos() }
    val stageLock = Mutex()
    val slowLock = Mutex()
    var finished = false
    try {
      coroutineScope {
        val permits = Semaphore(limits.parallelMedia)
        for (item in claim.items) {
          launch(dispatcher) {
            permits.withPermit {
              if (unit.halt.get() != null) return@withPermit
              when (val verdict = control.verdict()) {
                is PrepVerdict.Stop -> {
                  unit.halt.compareAndSet(null, verdict.by)
                  return@withPermit
                }
                PrepVerdict.Slow -> slowLock.withLock {
                  if (unit.halt.get() != null) return@withLock
                  if (!stillOk(share, unit, budget, elapsed)) return@withLock
                  safely(item, unit, tally) { prepareItem(share, item, unit, tally, stageLock) }
                  delay(slowPause)
                }
                PrepVerdict.Continue -> {
                  if (!stillOk(share, unit, budget, elapsed)) return@withPermit
                  safely(item, unit, tally) { prepareItem(share, item, unit, tally, stageLock) }
                }
              }
            }
          }
        }
      }
      finished = true
    } finally {
      // Anche se il giro e' annullato: quello che e' nell'outbox va pubblicato e l'unita' lasciata, altrimenti resta
      // prenotata per un'ora senza che nessuno ci lavori.
      withContext(NonCancellable) {
        publisher.flush(share, FlushReason.UNIT_FINISHED)
        if (!unit.leaseLost) {
          val halt = unit.halt.get()
          val outcome = when {
            !finished -> UnitOutcome.Interrupted("CANCELLED")
            halt == null -> UnitOutcome.Completed(unit.prepared.get(), unit.failed.get())
            else -> UnitOutcome.Interrupted(codeOf(halt))
          }
          workUnits.finish(unit.claim, outcome)
        }
      }
    }
    // Una prenotazione persa non ferma il giro: l'unita' e' di un altro, si prende la prossima.
    if (unit.leaseLost) return null
    unit.halt.get()?.let { return it }
    // Nessun media preparato ne' fallito: gli altri sono da saltare (senza record, file cambiato, famiglia gia' fallita). L'unita'
    // tornerebbe identica al prossimo `next`, quindi non c'e' niente da fare adesso.
    return if (unit.prepared.get() + unit.failed.get() == 0) PrepStop.NO_WORK else null
  }

  /**
   * Un media che lancia un'eccezione (un database pieno, un'apertura scritta male) non ferma l'unita': conta come fallito e
   * il giro prosegue. Il motivo resta in Diagnostica come classe dell'eccezione, mai come testo (potrebbe avere un nome di file).
   */
  private suspend fun safely(item: WorkItem, unit: UnitState, tally: Tally, block: suspend () -> Unit) {
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w("prepare") { "media saltato: ${e.javaClass.simpleName} (${item.kind})" }
      unit.failed.incrementAndGet()
      tally.failed.incrementAndGet()
    }
  }

  /** Il budget della tappa, la prenotazione da rinnovare: false = non si comincia un altro media. */
  private suspend fun stillOk(share: String, unit: UnitState, budget: Duration, elapsed: () -> Duration): Boolean {
    if (elapsed() >= budget) {
      unit.halt.compareAndSet(null, PrepStop.BUDGET)
      return false
    }
    val space = storage
    if (space != null && runCatching { space.usableBytes() }.getOrDefault(0L) < PrepStorage.MIN_FREE_BYTES) {
      unit.halt.compareAndSet(null, PrepStop.STORAGE)
      return false
    }
    unit.renewLock.withLock {
      if ((clock.nanos() - unit.lastRenewNanos).nanoseconds >= renewEvery) {
        when (val renewed = workUnits.keepAlive(unit.claim)) {
          is KeepAliveResult.Renewed -> {
            unit.claim = renewed.claim
            unit.lastRenewNanos = clock.nanos()
          }
          KeepAliveResult.Lost -> {
            // Un altro ha preso l'unita': si finisce il media in corso e la si lascia (E-I2). Non e' un arresto del giro.
            unit.leaseLost = true
            unit.halt.compareAndSet(null, PrepStop.BLOCKED)
          }
        }
      }
    }
    return unit.halt.get() == null
  }

  private fun codeOf(stop: PrepStop): String = when (stop) {
    PrepStop.BUDGET -> "BUDGET"
    PrepStop.RULES -> "RULES"
    PrepStop.UNAVAILABLE -> "NAS_UNREACHABLE"
    PrepStop.BLOCKED -> "BLOCKED"
    PrepStop.NO_WORK -> "NO_WORK"
    PrepStop.STORAGE -> "PHONE_STORAGE"
  }

  // --- Un media ----------------------------------------------------------------------------------------------------

  private suspend fun prepareItem(share: String, item: WorkItem, unit: UnitState, tally: Tally, stageLock: Mutex) {
    // Un media si prova una volta per giro: i tentativi di E-M10 sono di giri diversi (a ore di distanza), non cinque di fila.
    if (!tally.attempted.add(item.contentKey.hex)) return
    // D14: questa famiglia di decoder ha gia' fallito per questo media; un altro Preparatore ci riprova.
    if (decoderFamily in item.failedDecoders) return
    val classes = item.missing.filterTo(LinkedHashSet()) { it.isContentBlob }
    if (classes.isEmpty()) return
    val base = records.baseFor(share, item) ?: return

    val ready = when (val open = inputs.open(share, item)) {
      is PrepOpen.Ready -> open
      is PrepOpen.Unavailable -> {
        when (open.code) {
          PrepFailCode.NAS_UNREACHABLE -> unit.halt.compareAndSet(null, PrepStop.UNAVAILABLE)
          PrepFailCode.TOO_LARGE -> giveUp(share, item, base, PrepFailCode.TOO_LARGE, unit, tally, stageLock)
          else -> Unit
        }
        return
      }
    }
    val original = ready.source
    if (sizeOf(original) != item.stamp.size) {
      // Il file e' cambiato dopo l'elenco: lo rilevera' la prossima scansione.
      runCatching { ready.close() }
      return
    }
    val read = AtomicLong()
    val source = when (original) {
      is PrepareSource.Bytes -> PrepareSource.Bytes(CountingMediaBytes(original.bytes) { read.addAndGet(it) }, original.kind, original.mime)
      is PrepareSource.Local -> original.also { read.set(it.file.length()) }
    }
    val result = try {
      preparer.prepare(source, PrepSpec(classes = classes))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      PrepResult.Failed(PrepFailCode.DECODE_FAILED, retryable = false)
    } finally {
      runCatching { ready.close() }
    }
    tally.bytes.addAndGet(read.get())

    when (result) {
      is PrepResult.Done -> publishDone(share, item, base, result, unit, tally, stageLock)
      is PrepResult.Failed -> handleFailure(share, item, base, result, unit, tally, stageLock)
    }
  }

  private fun sizeOf(source: PrepareSource): Long = when (source) {
    is PrepareSource.Bytes -> source.bytes.size
    is PrepareSource.Local -> source.file.length()
  }

  private suspend fun publishDone(share: String, item: WorkItem, base: MetaFile, done: PrepResult.Done, unit: UnitState, tally: Tally, stageLock: Mutex) {
    // D8: l'hash completo e le immagini valgono per la chiave di contenuto del lavoro solo se i byte letti sono quelli.
    done.quickHash?.let { if (ContentKey.of(it, item.stamp.size) != item.contentKey) return }
    if (done.outputs.isEmpty()) return
    val blobs = done.outputs.map { output ->
      when (output) {
        is PrepOutput.Image -> BlobPut.Image(output.cls, item.contentKey, output.bytes)
        is PrepOutput.Frames -> BlobPut.Frames(item.contentKey, output.frames)
      }
    }
    stageLock.withLock { publisher.stage(share, listOf(MetaChange.Put(item.path, PrepRecords.merge(base, done))), blobs) }
    unit.prepared.incrementAndGet()
    tally.prepared.incrementAndGet()
    deliverAnalysis(item.contentKey, done.outputs)
  }

  /**
   * D10: le anteprime di analisi e i fotogrammi vanno anche al buffer degli indicizzatori (con F4-D31 acceso la pubblicazione li
   * butterebbe, R2 della 4A). Dopo la messa in outbox: un errore del buffer non tocca cio' che si pubblica.
   */
  private suspend fun deliverAnalysis(contentKey: ContentKey, outputs: List<PrepOutput>) {
    val wanted = outputs.filter { it.cls == SegmentClass.ANALYSIS || it.cls == SegmentClass.FRAMES }
    if (wanted.isEmpty() || analysisSink === AnalysisSink.NONE) return
    try {
      analysisSink.deliver(contentKey, wanted)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w("prepare") { "buffer di analisi: consegna fallita (${e.javaClass.simpleName})" }
    }
  }

  private suspend fun handleFailure(share: String, item: WorkItem, base: MetaFile, failed: PrepResult.Failed, unit: UnitState, tally: Tally, stageLock: Mutex) {
    when (failed.code) {
      PrepFailCode.DECODER_MISSING -> {
        giveUp(share, item, base, failed.code, unit, tally, stageLock, failed.decoderFamily ?: decoderFamily)
        tally.handedOver.incrementAndGet()
      }
      PrepFailCode.TOO_LARGE -> giveUp(share, item, base, failed.code, unit, tally, stageLock)
      PrepFailCode.DECODE_FAILED -> {
        val count = attempts.failed(share, item, failed.code)
        unit.failed.incrementAndGet()
        tally.failed.incrementAndGet()
        if (count >= PrepAttempts.MAX_ATTEMPTS) publishFailure(share, item, base, failed.code, decoderFamily, stageLock)
      }
      PrepFailCode.NAS_UNREACHABLE -> unit.halt.compareAndSet(null, PrepStop.UNAVAILABLE)
      // Il file e' cambiato o il disco locale ha avuto un problema: non e' colpa del media, si riprova al giro dopo.
      PrepFailCode.SOURCE_CHANGED, PrepFailCode.INTERRUPTED -> Unit
    }
  }

  /** Un fallimento definitivo: si pubblica nel record e non si riprova (D14, E-M3). */
  private suspend fun giveUp(
    share: String,
    item: WorkItem,
    base: MetaFile,
    code: PrepFailCode,
    unit: UnitState,
    tally: Tally,
    stageLock: Mutex,
    family: String = decoderFamily,
  ) {
    publishFailure(share, item, base, code, family, stageLock)
    unit.failed.incrementAndGet()
    tally.failed.incrementAndGet()
  }

  private suspend fun publishFailure(share: String, item: WorkItem, base: MetaFile, code: PrepFailCode, family: String, stageLock: Mutex) {
    val record = base.copy(failure = PrepFailure(code.name, family))
    stageLock.withLock { publisher.stage(share, listOf(MetaChange.Put(item.path, record)), emptyList()) }
  }
}

/** La fusione del record che la scansione conosce con cio' che la preparazione ha scoperto (§2.4 "Uscite"). */
object PrepRecords {
  /** Il record di un media preparato: classi, hash completo, dimensioni e durata corrette, segnaposto. Cancella un `fail` precedente. */
  fun merge(base: MetaFile, done: PrepResult.Done): MetaFile = base.copy(
    fullHash = done.fullHash ?: base.fullHash,
    width = done.facts?.width ?: base.width,
    height = done.facts?.height ?: base.height,
    durationMs = done.facts?.durationMs ?: base.durationMs,
    thumbHash = done.thumbHash ?: base.thumbHash,
    prepared = base.prepared or SegmentClass.maskOf(done.outputs.map { it.cls }),
    failure = null,
  )
}

/** Conta i byte chiesti a un [MediaBytes] (per `RunResult.bytesRead`); la sorgente di sistema non passa di qui. */
class CountingMediaBytes(private val delegate: MediaBytes, private val onRead: (Long) -> Unit) : MediaBytes {
  override val size: Long get() = delegate.size

  override suspend fun read(range: LongRange): ByteArray {
    val part = delegate.read(range)
    onRead(part.size.toLong())
    return part
  }

  override fun dataSource(): MediaDataSource = delegate.dataSource()
}
