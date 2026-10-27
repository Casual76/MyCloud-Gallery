package dev.pampa.mycloudgallery.core.faces.compute

import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.AlignedFace
import dev.pampa.mycloudgallery.core.ai.contract.DetectPass
import dev.pampa.mycloudgallery.core.ai.contract.DetectedFace
import dev.pampa.mycloudgallery.core.ai.contract.FaceDetector
import dev.pampa.mycloudgallery.core.ai.contract.FaceEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngines
import dev.pampa.mycloudgallery.core.ai.contract.FaceImage
import dev.pampa.mycloudgallery.core.ai.testing.FakeFaceEngines
import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.FacesChange
import dev.pampa.mycloudgallery.core.faces.contract.FacesLease
import dev.pampa.mycloudgallery.core.faces.contract.FacesLeases
import dev.pampa.mycloudgallery.core.faces.contract.FacesPermit
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublishOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublisher
import dev.pampa.mycloudgallery.core.faces.contract.FacesResource
import dev.pampa.mycloudgallery.core.faces.contract.FacesRunControl
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock
import dev.pampa.mycloudgallery.core.faces.contract.FaceGrouping
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import dev.pampa.mycloudgallery.core.faces.contract.GroupingReport
import dev.pampa.mycloudgallery.core.faces.contract.HolderState
import dev.pampa.mycloudgallery.core.faces.store.FacesUnitsDone
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesArchive
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import kotlin.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** La morte del processo (un `Error`: nessun `catch (Exception)` della produzione la ferma). */
class ProcessDeath : Error("morte simulata del processo")

/** Le unita' finite di un dispositivo (su disco: sopravvivono alla morte del processo). */
class MemoryUnits : FacesUnitsDone {
  private val done = HashMap<FacesResource.WorkUnit, Int>()
  var marks = 0
    private set

  /** Non segna per le prossime [failMarks] richieste (un errore del database: disco pieno o database bloccato, O1) lanciando [failure]. */
  var failMarks: Int = 0
  var failure: () -> Throwable = { RuntimeException("database pieno") }

  /** Un'azione prima di rispondere a "e' finita?" (lo stato di Persone cambia a meta' tappa). */
  var onIsDone: () -> Unit = {}

  override suspend fun markDone(unit: FacesResource.WorkUnit, recipe: Int, doneAtNas: Long?) {
    if (failMarks > 0) {
      failMarks--
      throw failure()
    }
    marks++
    done[unit] = maxOf(done[unit] ?: 0, recipe)
  }

  override suspend fun isDone(unit: FacesResource.WorkUnit, recipe: Int): Boolean {
    onIsDone()
    return (done[unit] ?: 0) >= recipe
  }

  override suspend fun done(recipe: Int): Set<FacesResource.WorkUnit> = done.filterValues { it >= recipe }.keys
}

/**
 * Un archivio che muore dopo il salvataggio numero [dieAfterSave] (il corpo e' salvato, il processo cade), che non salva per [failSaves] chiamate
 * (un errore del database: disco pieno) e che conta i salvataggi per chiave.
 */
class CrashingArchive(val inner: FacesArchive) : FacesArchive by inner {
  var dieAfterSave: Int = -1
  var failSaves: Int = 0

  /** Cosa lancia un salvataggio che fallisce (di serie un errore qualunque; con Robolectric un `SQLiteFullException`). */
  var failure: () -> Throwable = { RuntimeException("database pieno") }
  var saves: Int = 0
    private set
  val perKey = HashMap<ContentKey, Int>()
  val unsupported = HashSet<ContentKey>()

  override suspend fun saveBody(body: FaceBody) {
    if (failSaves > 0) {
      failSaves--
      throw failure()
    }
    inner.saveBody(body)
    saves++
    perKey[body.contentKey] = (perKey[body.contentKey] ?: 0) + 1
    if (saves == dieAfterSave) throw ProcessDeath()
  }

  override suspend fun markUnsupported(key: ContentKey, recipe: Int) {
    unsupported += key
    inner.markUnsupported(key, recipe)
  }
}

/** Le prenotazioni sotto la generazione sul NAS finto: 1 ora d'ora del NAS, `Busy` se le tiene un altro, `Lost` al rinnovo se le ha prese un altro. */
class SimLeases(private val nas: SimFacesNas, private val device: String, private val generation: FaceGeneration) : FacesLeases {
  var acquired = 0
    private set
  var busy = 0
    private set
  var released = 0
    private set
  var calls = 0
    private set

  /** Un blocco del cancello (congelamento...): le prenotazioni rispondono `Blocked`. */
  var block: FacesWriteBlock? = null
  val resources = ArrayList<FacesResource>()

  override suspend fun acquire(generation: FaceGeneration, resource: FacesResource): FacesLease {
    calls++
    check(generation == this.generation) { "prenotazione di un'altra generazione" }
    block?.let { return FacesLease.Blocked(it) }
    return nas.call {
      val key = generation to resource
      val current = nas.leases[key]
      if (current == null || current.second <= nas.now || current.first == device) {
        acquired++
        resources += resource
        take(key)
      } else {
        busy++
        FacesLease.Busy
      }
    }
  }

  override suspend fun renew(generation: FaceGeneration, resource: FacesResource, held: FacesLease.Held): FacesLease {
    calls++
    check(generation == this.generation) { "prenotazione di un'altra generazione" }
    return nas.call {
      val key = generation to resource
      val current = nas.leases[key]
      if (current == null || current.second <= nas.now || current.first == device) take(key) else FacesLease.Lost
    }
  }

  override suspend fun release(generation: FaceGeneration, resource: FacesResource, held: FacesLease.Held) {
    calls++
    check(generation == this.generation) { "prenotazione di un'altra generazione" }
    nas.call {
      val key = generation to resource
      if (nas.leases[key]?.first == device) nas.leases.remove(key)
      released++
    }
  }

  private fun take(key: Pair<FaceGeneration, FacesResource>): FacesLease.Held {
    val until = nas.now + HOUR
    nas.leases[key] = device to until
    // Il numero della prenotazione cresce per risorsa: oltre il primo, qualcuno l'ha gia' tenuta.
    val number = (nas.leaseNumbers[key] ?: 0L) + 1
    nas.leaseNumbers[key] = number
    return FacesLease.Held(number, until)
  }

  companion object {
    const val HOUR: Long = 60L * 60 * 1000
  }
}

/**
 * Il pubblicatore finto (P3 lo consegnera' vero): l'outbox e' durevole (sopravvive alla morte del processo), la pubblicazione legge l'archivio al
 * momento e scrive sul NAS finto solo i corpi **propri** (autore = questo dispositivo), sotto la propria generazione.
 */
class SimPublisher(
  private val nas: SimFacesNas,
  private val device: String,
  private val generation: FaceGeneration,
  private val archive: () -> FacesArchive,
) : FacesPublisher {
  val staged = LinkedHashSet<ContentKey>()
  var stageCalls = 0
    private set
  var flushCalls = 0
    private set
  var published = 0
    private set
  var block: FacesWriteBlock? = null
  var faultyStage = false

  /** Non accoda per le prossime [failStage] richieste, e non pubblica per le prossime [failFlush]: un errore del database che non e' un `IOException` (O1). */
  var failStage = 0
  var failFlush = 0
  var failure: () -> Throwable = { RuntimeException("database pieno") }
  val reasons = ArrayList<FlushReason>()
  private val count = MutableStateFlow(0)
  override val pending: StateFlow<Int> = count

  override suspend fun stage(change: FacesChange) {
    stageCalls++
    if (failStage > 0) {
      failStage--
      throw failure()
    }
    if (change is FacesChange.Faces) {
      if (faultyStage) nas.call { Unit }
      staged += change.contentKey
    }
    count.value = staged.size
  }

  override suspend fun flush(reason: FlushReason): FacesPublishOutcome {
    flushCalls++
    reasons += reason
    if (failFlush > 0) {
      failFlush--
      throw failure()
    }
    if (staged.isEmpty()) return FacesPublishOutcome.NothingToPublish
    block?.let { return FacesPublishOutcome.Blocked(it) }
    val mine = archive().winners(staged.toList()).filterValues { it.author == device }
    nas.call {
      nas.writes += device to generation
      val bucket = nas.published.getOrPut(generation) { HashMap() }.getOrPut(device) { HashMap() }
      for ((key, body) in mine) bucket[key] = body
    }
    published += mine.size
    staged.clear()
    count.value = 0
    return FacesPublishOutcome.Published(published.toLong(), mine.size)
  }
}

/** Il raggruppamento di prova: conta le chiamate a fine unita' e il tempo che gli resta. */
class SpyGrouping : FaceGrouping {
  override val holder: StateFlow<HolderState> = MutableStateFlow(HolderState.Unknown)
  var incrementals = 0
    private set
  val budgets = ArrayList<Duration>()
  var failWith: RuntimeException? = null

  override suspend fun incremental(budget: Duration): GroupingReport {
    incrementals++
    budgets += budget
    failWith?.let { throw it }
    return GroupingReport.EMPTY
  }

  override suspend fun nightly(budget: Duration): GroupingReport = GroupingReport.EMPTY

  override suspend fun recluster(budget: Duration): GroupingReport = GroupingReport.EMPTY
}

/** Le regole di `AI_COMPUTE` a mano (E-A5): `Go` di serie, il caldo o l'arresto. */
class SimControl : FacesRunControl {
  var permit: FacesPermit = FacesPermit.Go(reducedPace = false)
  var asked = 0
    private set

  /** Un'azione prima di rispondere alla n-esima domanda (le regole cambiano a meta' tappa). */
  var onAsk: (Int) -> Unit = {}

  override suspend fun permit(): FacesPermit {
    asked++
    onAsk(asked)
    return permit
  }
}

/** Il rilevatore e l'embedder dei motori finti con il tempo che passa (l'orologio del dispositivo avanza ad ogni chiamata) e le passate usate. */
class TickingEngines(val fake: FakeFaceEngines, private val clock: SimClock, private val detectMillis: Long = 40, private val embedMillis: Long = 5) {
  val passes = ArrayList<DetectPass>()
  var onDetect: (Int) -> Unit = {}
  val failDetect = ArrayDeque<AiEngineError>()

  private val detector = object : FaceDetector {
    override suspend fun detect(image: FaceImage, pass: DetectPass): List<DetectedFace> {
      passes += pass
      onDetect(passes.size)
      failDetect.removeFirstOrNull()?.let { throw AiEngineException(it) }
      clock.advanceMillis(detectMillis)
      // Tempo virtuale: due dispositivi nello stesso test si alternano davvero (e si incontrano sulle prenotazioni).
      delay(detectMillis)
      return fake.detector.detect(image, pass)
    }

    override suspend fun release() = fake.detector.release()
  }

  private val embedder = object : FaceEmbedder {
    override val space = fake.embedder.space

    override suspend fun embed(faces: List<AlignedFace>): List<FloatArray?> {
      clock.advanceMillis(embedMillis * faces.size)
      return fake.embedder.embed(faces)
    }

    override suspend fun release() = fake.embedder.release()
  }

  val engines: FaceEngines get() = FaceEngines(detector, embedder, fake.aligner, fake.quality)
  val detects: Int get() = passes.size
}
