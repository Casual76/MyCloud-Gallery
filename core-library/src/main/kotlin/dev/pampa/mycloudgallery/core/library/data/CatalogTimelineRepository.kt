package dev.pampa.mycloudgallery.core.library.data

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.CatalogChange
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.TimelineRepository
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.FilteredTimelineView
import dev.pampa.mycloudgallery.core.library.index.TimelinePatcher
import dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineView
import dev.pampa.mycloudgallery.core.library.store.SnapshotStore
import dev.pampa.mycloudgallery.core.library.store.SnapshotWriter
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Il repository della timeline (D2, §1.2): un indice a colonne in memoria, sempre allineato al catalogo.
 *
 * **Avvio** ([start]): ci si iscrive subito ai cambiamenti (cosi' nessuno va perso), si legge l'istantanea su file e la
 * si pubblica (il primo fotogramma, con i segnaposto ThumbHash e i riferimenti ai byte gia' pronti), poi si legge il
 * `change_seq` del catalogo: se e' lo stesso del file l'indice e' gia' giusto, altrimenti si ricostruisce. [start]
 * ritorna appena c'e' un indice da mostrare; la riconciliazione continua in [scope] ([reconciled]).
 *
 * **Aggiornamenti**: i cambiamenti arrivano in fila; quelli gia' in coda si accorpano in una patch sola
 * ([TimelinePatcher]). Un salto di `seq` (cambiamenti persi dal flusso) o un errore porta a una ricostruzione.
 *
 * **Istantanea su file**: riscritta al piu' ogni 5 s ([SnapshotWriter]); **subito** se l'indice ha perso elementi, e
 * **prima** di nascondere ([forget]), cosi' un nascosto non resta sul disco (E-H1).
 *
 * Le viste ([view]) per lo stesso filtro sono condivise e seguono l'indice: ogni nuovo indice produce nuove viste,
 * con la mappa del filtro calcolata da chi la legge per primo (di norma il calcolo dei layout).
 */
class CatalogTimelineRepository(
  private val source: TimelineSource,
  private val store: SnapshotStore?,
  private val scope: CoroutineScope,
  private val worker: CoroutineDispatcher = Dispatchers.Default,
  writeDelayMs: Long = SnapshotWriter.DEFAULT_DELAY_MS,
) : TimelineRepository {

  private sealed interface Message {
    class Change(val change: CatalogChange) : Message

    data object Rebuild : Message
  }

  private val snapshotFlow = MutableStateFlow<TimelineSnapshot?>(null)
  override val snapshot: StateFlow<TimelineSnapshot?> = snapshotFlow.asStateFlow()

  private val reconciledFlow = MutableStateFlow(false)

  /** Vero quando l'indice e' stato confrontato con il catalogo (dopo l'istantanea su file). */
  val reconciled: StateFlow<Boolean> = reconciledFlow.asStateFlow()

  private val views = ConcurrentHashMap<LibraryFilter, MutableStateFlow<TimelineView>>()
  private val inbox = Channel<Message>(Channel.UNLIMITED)
  private val started = AtomicBoolean(false)
  private val firstSnapshot = CompletableDeferred<Unit>()
  private val lock = Mutex()
  private val writer: SnapshotWriter? = store?.let { SnapshotWriter(it, scope, writeDelayMs) }
  private val recentRefs = object : LinkedHashMap<MediaId, MediaRef>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MediaId, MediaRef>?): Boolean = size > RECENT_REFS
  }

  private val rebuildCount = AtomicInteger()
  private val patchCount = AtomicInteger()

  /** Quante ricostruzioni complete e quante patch (test, Diagnostica). */
  val rebuilds: Int get() = rebuildCount.get()
  val patches: Int get() = patchCount.get()

  /** Le scritture dell'istantanea arrivate al disco. */
  val snapshotWrites: Int get() = writer?.writes ?: 0

  override suspend fun start() {
    if (started.compareAndSet(false, true)) {
      // Iscritti prima di leggere il seq: un cambiamento fra la lettura e l'iscrizione non va perso.
      scope.launch(start = CoroutineStart.UNDISPATCHED) { source.changes.collect { inbox.send(Message.Change(it)) } }
      scope.launch(worker) { run() }
    }
    firstSnapshot.await()
  }

  override fun view(f: LibraryFilter): StateFlow<TimelineView> =
    views.computeIfAbsent(f) { filter ->
      MutableStateFlow<TimelineView>(snapshotFlow.value?.let { FilteredTimelineView(it, filter) } ?: EmptyTimelineView(filter))
    }

  /** Aspetta che l'indice abbia almeno la sequenza [seq] (dopo una scrittura del catalogo). */
  suspend fun awaitSeq(seq: Long) {
    snapshotFlow.first { it != null && it.seq >= seq }
  }

  /**
   * Il riferimento ai byte di [id]: dall'indice, o dai riferimenti degli elementi appena usciti (il viewer che li
   * mostra ancora per un istante, E-V6). Null se il telefono non ne ha uno leggibile adesso.
   */
  fun refOf(id: MediaId): MediaRef.Phone? {
    val s = snapshotFlow.value as? ColumnarTimelineSnapshot
    if (s != null) {
      val i = s.indexOf(id)
      if (i >= 0) return s.refAt(i)
    }
    return synchronized(recentRefs) { recentRefs[id] as? MediaRef.Phone }
  }

  /**
   * Come [refOf], ma con il riferimento che serve alla griglia e al viewer (Fase 4, D18): la copia del telefono se
   * c'e', altrimenti quella del NAS (media solo-NAS, o copia del telefono non leggibile adesso). Null se non c'e' un
   * riferimento di nessuno dei due: la cella resta sul segnaposto e il viewer lo dice.
   */
  fun mediaRefOf(id: MediaId): MediaRef? {
    val s = snapshotFlow.value as? ColumnarTimelineSnapshot
    if (s != null) {
      val i = s.indexOf(id)
      if (i >= 0) return s.thumbRefAt(i)
    }
    return synchronized(recentRefs) { recentRefs[id] }
  }

  /**
   * Toglie subito [ids] dall'indice e dall'istantanea su file, **prima** che il catalogo li nasconda (E-H1): se il
   * processo muore fra le due scritture, sul disco non resta il nascosto. Il file si marca provvisorio (al prossimo
   * avvio si ricostruisce comunque). Senza un indice in memoria il file si cancella.
   */
  suspend fun forget(ids: Collection<MediaId>) {
    if (ids.isEmpty()) return
    lock.withLock {
      val current = snapshotFlow.value as? ColumnarTimelineSnapshot
      if (current == null) {
        store?.delete()
        return
      }
      val next = TimelinePatcher.remove(current, ids)
      if (next === current) return
      publish(next)
      if (writer?.writeNow(next, provisional = true, sensitive = true) == false) store?.delete()
    }
  }

  /** Il catalogo non ha applicato cio' che ci si aspettava (scrittura fallita dopo [forget]): si ricostruisce. */
  fun requestRebuild() {
    inbox.trySend(Message.Rebuild)
  }

  /** Scrive subito l'istantanea in attesa (alla chiusura del processo, `onTrimMemory`). */
  suspend fun flush() {
    writer?.flush()
  }

  // --- Ciclo ----------------------------------------------------------------------------------------------------

  private suspend fun run() {
    try {
      val loaded = try {
        store?.read()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        null
      }
      lock.withLock {
        if (loaded != null) publish(loaded.snapshot)
        val seq = source.changeSeq()
        if (loaded == null || loaded.provisional || loaded.snapshot.seq != seq) rebuild()
      }
      reconciledFlow.value = true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.e(TAG, e) { "Indice della timeline non letto: si riprova." }
      retryLater()
    } finally {
      firstSnapshot.complete(Unit)
    }
    for (first in inbox) {
      val batch = ArrayList<Message>()
      batch += first
      while (true) batch += inbox.tryReceive().getOrNull() ?: break
      try {
        lock.withLock { apply(batch) }
        failures = 0
        reconciledFlow.value = true
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.e(TAG, e) { "Aggiornamento dell'indice fallito: si ricostruira'." }
        retryLater()
      }
    }
  }

  private var failures = 0

  /** Una ricostruzione piu' tardi, con un'attesa che cresce (un database rotto non deve girare a vuoto). */
  private fun retryLater() {
    failures++
    val wait = (RETRY_BASE_MS shl minOf(failures - 1, 6)).coerceAtMost(RETRY_MAX_MS)
    scope.launch(worker) {
      delay(wait)
      inbox.trySend(Message.Rebuild)
    }
  }

  private suspend fun apply(batch: List<Message>) {
    if (snapshotFlow.value !is ColumnarTimelineSnapshot || batch.any { it is Message.Rebuild }) rebuild()
    var pending = batch.filterIsInstance<Message.Change>().map { it.change }.sortedBy { it.seq }
    var rebuildsLeft = MAX_REBUILDS_PER_BATCH
    while (true) {
      val base = snapshotFlow.value as? ColumnarTimelineSnapshot ?: return
      val baseSeq = base.seq
      pending = pending.filter { it.seq > baseSeq }
      if (pending.isEmpty()) return
      // I cambiamenti contigui da baseSeq + 1 si accorpano in una patch; un buco vuol dire cambiamenti persi.
      var expected = baseSeq + 1
      val ids = LinkedHashSet<MediaId>()
      var structural = false
      var taken = 0
      for (c in pending) {
        if (c.seq < expected) {
          taken++
          continue
        }
        if (c.seq != expected) break
        ids += c.ids
        structural = structural || c.structural
        expected++
        taken++
      }
      if (expected == baseSeq + 1) {
        if (rebuildsLeft-- <= 0) return
        rebuild()
        continue
      }
      patch(base, ids, structural, expected - 1)
      pending = pending.drop(taken)
    }
  }

  private suspend fun patch(current: ColumnarTimelineSnapshot, ids: Set<MediaId>, structural: Boolean, seq: Long) {
    val rows = if (ids.isEmpty()) emptyList() else source.indexRows(ids)
    val refs = if (rows.isEmpty()) emptyMap() else source.phoneRefs(rows.map { MediaId(it.mediaId) })
    // Fase 4: il NAS solo per le righe sul NAS senza una copia leggibile sul telefono (per le altre vale il telefono).
    val onlyNas = rows.filter { CellFlags.has(it.cellFlags, CellFlags.ON_NAS) && it.mediaId !in refs }
    val nasRefs = if (onlyNas.isEmpty()) emptyMap() else source.nasRefs(onlyNas.map { MediaId(it.mediaId) })
    val touchesGroups = rows.any { it.groupId != null } || ids.any { id -> current.indexOf(id).let { it >= 0 && current.groupSizeAt(it) > 1 } }
    val sizes = if (structural || touchesGroups) source.groupSizes() else null
    val result = TimelinePatcher.patch(current, ids, rows, refs, sizes, seq, nasRefs)
    if (result.droppedRefs.isNotEmpty() || result.droppedNasRefs.isNotEmpty()) {
      synchronized(recentRefs) {
        recentRefs.putAll(result.droppedNasRefs)
        recentRefs.putAll(result.droppedRefs)
      }
    }
    patchCount.incrementAndGet()
    publish(result.snapshot)
    if (result.removed > 0) writer?.writeNow(result.snapshot) else writer?.schedule(result.snapshot)
  }

  private suspend fun rebuild() {
    val built = source.buildIndex()
    rebuildCount.incrementAndGet()
    publish(built)
    // Dopo una ricostruzione il file puo' contenere elementi usciti dall'indice: si riscrive subito.
    writer?.writeNow(built)
  }

  private fun publish(s: ColumnarTimelineSnapshot) {
    snapshotFlow.value = s
    for ((filter, flow) in views) flow.value = FilteredTimelineView(s, filter)
    firstSnapshot.complete(Unit)
  }

  private companion object {
    const val TAG = "Library"
    const val RECENT_REFS = 256
    const val MAX_REBUILDS_PER_BATCH = 3
    const val RETRY_BASE_MS = 1_000L
    const val RETRY_MAX_MS = 60_000L
  }
}
