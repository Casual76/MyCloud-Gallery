package dev.pampa.mycloudgallery.core.index.sync

import dev.pampa.mycloudgallery.core.index.contract.HeadFetch
import dev.pampa.mycloudgallery.core.index.contract.IndexHeadView
import dev.pampa.mycloudgallery.core.index.contract.IndexSync
import dev.pampa.mycloudgallery.core.index.contract.IndexSyncStatus
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.RemoteIndex
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.index.contract.SegmentFetch
import dev.pampa.mycloudgallery.core.index.contract.SyncReport
import dev.pampa.mycloudgallery.core.index.contract.SyncScope
import dev.pampa.mycloudgallery.core.index.quality.ReplicaQualityController
import dev.pampa.mycloudgallery.core.index.replica.DeviceState
import dev.pampa.mycloudgallery.core.index.replica.LocalReplica
import dev.pampa.mycloudgallery.core.index.replica.PathKeys
import dev.pampa.mycloudgallery.core.index.replica.store.HeadApplication
import dev.pampa.mycloudgallery.core.index.replica.store.HeadRow
import dev.pampa.mycloudgallery.core.index.replica.store.RecordRow
import dev.pampa.mycloudgallery.core.index.replica.store.ReplicaStore
import dev.pampa.mycloudgallery.core.index.replica.store.SegmentRow
import dev.pampa.mycloudgallery.core.index.replica.store.SegmentState
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentContent
import dev.pampa.mycloudgallery.core.trust.nas.NasClock
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * La SINCRONIZZAZIONE dell'indice condiviso (docs/fase4-progetto.md §2.2; D5–D8, D16, D23; E-I5, E-I7, E-I8, E-I9,
 * E-I15, E-I17, E-I18). Una per processo; un giro alla volta.
 *
 * Un giro:
 * 1. **Prima di partire** il [gate] (registro fresco, percorso, regole). Poi, per ogni share dell'ambito:
 * 2. **Dispositivi**: un elenco di `devices/` (gia' filtrato sugli approvati e dismessi dal [remote]). Un rifiuto
 *    `ACCESS_DENIED`/`SHARE_MISSING` in due giorni diversi toglie la share (E-I8, [ShareAccessPolicy]).
 * 3. **Teste**: per ogni dispositivo una lettura dalla versione nota (di solito "nessuna novita'"). La **propria** testa si
 *    legge solo quando la replica non la conosce (ricostruzione da zero, E-I7): i propri dati arrivano gia' da
 *    `OwnIndexSink`. Cosi' la verifica senza novita' con 3 share e 6 dispositivi costa 3 + 3 × 5 = 18 richieste.
 * 4. **Testa nuova** (D5): si scaricano i segmenti `meta` nuovi, poi **una** transazione salva testa, elenco dei segmenti,
 *    record, segmenti tolti e percorsi "da rifondere". Dopo tutte le teste della share i percorsi si rifondono (D6) e
 *    vanno al catalogo con l'adozione d'identita' (D7); un crash in mezzo si recupera al giro dopo. Un segmento assente
 *    o rifiutato non ferma gli altri e trattiene i dati vecchi della sua classe (E-I5, E-I17).
 * 5. **Dispositivi spariti** da `devices/`: approvati o dismessi = indice cancellato, la replica resta (E-I5); revocati,
 *    persi o sconosciuti = dati tolti (E-I15). Una testa rifiutata per revoca fa lo stesso.
 * 6. **Immagini**: i segmenti delle classi richieste ([SyncScope.classes] ∩ S, M, HQ; nessuna con `visibleOnly`, D23) in
 *    ordine di classe (S, poi M, poi HQ), un pacco ciascuno (D15). Il punto di ripresa e' lo stato dei segmenti nel
 *    database: un giro interrotto dal [budget] riprende da li'.
 * 7. **Qualita'**: un passo di convergenza ([ReplicaQualityController.converge]).
 *
 * Il tempo del budget si misura con l'orologio monotono ([ticker]); le date con l'ora del NAS ([nasClock]).
 */
class IndexSyncEngine(
  private val remote: RemoteIndex,
  private val replica: LocalReplica,
  private val store: ReplicaStore,
  private val gate: SyncGate,
  private val standings: DeviceStandings,
  private val nasClock: NasClock,
  private val quality: ReplicaQualityController? = null,
  val counters: IndexCounters = IndexCounters(),
  private val ticker: () -> Long = System::nanoTime,
) : IndexSync {

  private val access = ShareAccessPolicy(store, nasClock)
  private val mutable = MutableStateFlow<IndexSyncStatus>(IndexSyncStatus.Idle)
  private val mutex = Mutex()

  override val status: StateFlow<IndexSyncStatus> = mutable

  override suspend fun sync(scope: SyncScope, budget: Duration): SyncReport = mutex.withLock {
    Round(scope, budget).run().also { counters.lastReport = it }
  }

  private inner class Round(val scope: SyncScope, budget: Duration) {
    private val start = ticker()
    private val budgetNanos = if (budget.isInfinite()) Long.MAX_VALUE else budget.inWholeNanoseconds
    private val names: Map<String, String> = scope.shares.associateBy { PathKeys.share(it) }.toSortedMap()
    private val blobClasses: Set<SegmentClass> =
      if (scope.visibleOnly) emptySet() else scope.classes.intersect(ReplicaQualityController.DOWNLOADABLE)

    private var headsRead = 0
    private var headsApplied = 0
    private val headsRejected = HashMap<RejectReason, Int>()
    private var segmentsDownloaded = 0
    private val segmentsRejected = HashMap<RejectReason, Int>()
    private var segmentsMissing = 0
    private var bytesDownloaded = 0L
    private var bytesTotal = 0L
    private var recordsApplied = 0
    private var identityConflicts = 0
    private var complete = true
    private var sharesAnswered = 0
    private val failures = ArrayList<RemoteFailure>()

    private fun timeUp(): Boolean = ticker() - start >= budgetNanos

    suspend fun run(): SyncReport {
      gate.check()?.let {
        mutable.value = IndexSyncStatus.Unavailable(RemoteFailure.NOT_ALLOWED)
        complete = false
        return report()
      }
      replica.open()
      // Percorsi lasciati "da rifondere" da un giro interrotto fra la replica e il catalogo.
      countConflicts(replica.flushAll().identityConflicts)
      for ((key, name) in names) {
        if (timeUp()) {
          complete = false
          break
        }
        syncShare(key, name)
        // Una fusione per share, dopo tutte le teste: D6 e D7 vedono tutti i dispositivi insieme.
        countConflicts(replica.flush(key).identityConflicts)
      }
      downloadImages()
      if (!scope.visibleOnly) skipUnwanted()
      quality?.converge()
      mutable.value = when {
        sharesAnswered == 0 && failures.isNotEmpty() -> IndexSyncStatus.Unavailable(failures.first())
        complete -> IndexSyncStatus.UpToDate(nasClock.nowMillis())
        failures.isNotEmpty() -> IndexSyncStatus.Unavailable(failures.first())
        else -> IndexSyncStatus.Aligning(bytesDownloaded, maxOf(bytesTotal, bytesDownloaded), eta())
      }
      return report()
    }

    private fun report() = SyncReport(
      headsRead = headsRead,
      headsApplied = headsApplied,
      headsRejected = headsRejected.toMap(),
      segmentsDownloaded = segmentsDownloaded,
      segmentsRejected = segmentsRejected.toMap(),
      segmentsMissing = segmentsMissing,
      bytesDownloaded = bytesDownloaded,
      recordsApplied = recordsApplied,
      identityConflicts = identityConflicts,
      complete = complete,
    )

    private fun eta(): Long? {
      val elapsedMs = (ticker() - start) / 1_000_000
      if (bytesDownloaded <= 0 || elapsedMs <= 0) return null
      return (bytesTotal - bytesDownloaded).coerceAtLeast(0) * elapsedMs / bytesDownloaded
    }

    private fun fail(failure: RemoteFailure) {
      failures += failure
      complete = false
    }

    // --- Teste ----------------------------------------------------------------------------------------------------

    private suspend fun syncShare(key: String, name: String) {
      val devices = when (val listing = remote.devices(name)) {
        is RemoteResult.Failed -> {
          fail(listing.failure)
          if (access.onFailure(key, listing.failure) == ShareAccessPolicy.Verdict.DROP) {
            replica.forgetShare(key)
            counters.sharesDropped.incrementAndGet()
          }
          return
        }
        is RemoteResult.Ok -> listing.value
      }
      access.onSuccess(key)
      sharesAnswered++
      val known = replica.devices(key)
      val listed = devices.distinct().sorted().take(TrustLimits.MAX_INDEX_DEVICES_PER_SHARE)
      for (device in listed) {
        if (timeUp()) {
          complete = false
          return
        }
        val state = known[device]
        if (device == replica.ownDeviceId && state?.head != null) continue
        headsRead++
        when (val read = remote.latestHead(name, device, state?.head?.mark)) {
          is RemoteResult.Failed -> {
            fail(read.failure)
            if (read.failure == RemoteFailure.UNREACHABLE) return
          }
          is RemoteResult.Ok -> when (val fetch = read.value) {
            HeadFetch.NoChange, HeadFetch.None -> Unit
            is HeadFetch.Rejected -> onRejected(key, device, fetch.reason, state)
            is HeadFetch.Accepted -> if (!applyAccepted(key, fetch.head, state)) return
          }
        }
      }
      val present = listed.toHashSet()
      for (device in known.keys) {
        if (device in present || device == replica.ownDeviceId) continue
        when (standings.standing(device)) {
          DeviceStanding.APPROVED, DeviceStanding.RETIRED -> Unit
          DeviceStanding.REVOKED, DeviceStanding.LOST, DeviceStanding.UNKNOWN -> forget(key, device)
        }
      }
    }

    private suspend fun onRejected(key: String, device: String, reason: RejectReason, state: DeviceState?) {
      headsRejected.merge(reason, 1, Int::plus)
      when (reason) {
        RejectReason.ROLLBACK, RejectReason.EQUIVOCATION -> counters.rollbacks.incrementAndGet()
        RejectReason.NEWER_FORMAT -> counters.newerFormat.incrementAndGet()
        else -> counters.reject(reason)
      }
      if ((reason == RejectReason.SIGNER_REVOKED || reason == RejectReason.SIGNER_NOT_APPROVED) && state != null) forget(key, device)
    }

    private suspend fun forget(key: String, device: String) {
      replica.forgetDevice(key, device)
      counters.devicesForgotten.incrementAndGet()
    }

    private fun countConflicts(n: Int) {
      if (n <= 0) return
      identityConflicts += n
      counters.identityConflicts.addAndGet(n)
    }

    /** Falso se la rete e' caduta (si smette di leggere la share); niente e' stato applicato in quel caso. */
    private suspend fun applyAccepted(key: String, head: IndexHeadView, state: DeviceState?): Boolean {
      val previous = state?.segments.orEmpty().associateBy { it.hash }
      val listed = head.segments.filter { it.cls in SegmentClass.INDEX_CLASSES }.distinctBy { it.ref.hash }
      val listedHashes = listed.mapTo(HashSet()) { it.ref.hash }

      // I segmenti `meta` nuovi (o assenti/rifiutati con la testa precedente: si riprovano una volta per testa).
      val outcome = HashMap<String, SegmentState>()
      val records = HashMap<String, List<RecordRow>>()
      var count = 0
      var maxSeq = 0L
      for (segment in listed) {
        if (segment.cls != SegmentClass.META || previous[segment.ref.hash]?.state == SegmentState.APPLIED) continue
        val fetch = when (val read = remote.segment(head, segment)) {
          is RemoteResult.Failed -> {
            fail(read.failure)
            return read.failure != RemoteFailure.UNREACHABLE
          }
          is RemoteResult.Ok -> read.value
        }
        bytesTotal += segment.ref.size
        val rows = when (fetch) {
          is SegmentFetch.Ok -> metaRows(head.deviceId, segment, fetch.content)
          SegmentFetch.Missing -> null.also { missing() }
          is SegmentFetch.Rejected -> null.also { rejectSegment(fetch.reason) }
        }
        if (fetch is SegmentFetch.Ok) {
          segmentsDownloaded++
          bytesDownloaded += segment.ref.size
        }
        if (rows == null) {
          outcome[segment.ref.hash] = if (fetch is SegmentFetch.Missing) SegmentState.MISSING else SegmentState.REJECTED
        } else {
          outcome[segment.ref.hash] = SegmentState.APPLIED
          records[segment.ref.hash] = rows
          count += rows.size
          rows.forEach { if (it.record.seq > maxSeq) maxSeq = it.record.seq }
        }
      }
      if (count > TrustLimits.MAX_RECORDS_PER_DEVICE_SHARE) {
        headsRejected.merge(RejectReason.TOO_LARGE, 1, Int::plus)
        counters.reject(RejectReason.TOO_LARGE)
        return true
      }

      val rows = listed.map { segment ->
        val hash = segment.ref.hash
        val before = previous[hash]
        val segmentState = when {
          segment.cls == SegmentClass.META -> outcome[hash] ?: SegmentState.APPLIED
          segment.cls !in ReplicaQualityController.DOWNLOADABLE -> SegmentState.SKIPPED
          before?.state == SegmentState.APPLIED -> SegmentState.APPLIED
          segment.cls in blobClasses -> SegmentState.PENDING
          else -> SegmentState.SKIPPED
        }
        SegmentRow(
          share = key,
          deviceId = head.deviceId,
          hash = hash,
          cls = segment.cls,
          size = segment.ref.size,
          state = segmentState,
          listed = true,
          packId = before?.packId?.takeIf { segmentState == SegmentState.APPLIED },
        )
      }
      // E-I17 e "prima costruire, poi togliere": finche' una classe della testa nuova non e' tutta applicata (segmenti in
      // attesa, saltati coi dati mobili, assenti o rifiutati) i segmenti vecchi della stessa classe restano, trattenuti.
      // Quelli in attesa li toglie [LocalReplica.dropRetained] quando la classe si completa; gli assenti e i rifiutati
      // restano fino a una testa nuova.
      val incomplete = rows.filter { it.state != SegmentState.APPLIED }.mapTo(HashSet()) { it.cls }
      val retained = previous.values
        .filter { it.hash !in listedHashes && it.cls in incomplete && it.state.hasData }
        .map { it.copy(listed = false) }
      val dropped = previous.keys - listedHashes - retained.mapTo(HashSet()) { it.hash }
      val prune = if (head.deviceId == replica.ownDeviceId && SegmentClass.META !in incomplete && maxSeq > 0) maxSeq else null
      val headRow = HeadRow(key, head.deviceId, head.version, head.mark.contentHash, head.registryVersion, head.createdAtNasMillis)
      replica.applyHead(HeadApplication(headRow, rows + retained, records, dropped, prune))
      headsApplied++
      recordsApplied += count
      return true
    }

    /** Le righe di un segmento `meta` verificato; null (e conteggio) se il contenuto non e' quello atteso. */
    private fun metaRows(device: String, segment: ClassedSegment, content: SegmentContent): List<RecordRow>? {
      if (content !is SegmentContent.Meta) {
        rejectSegment(RejectReason.WRONG_TYPE)
        return null
      }
      // Difesa in profondita': il codec di P3 valida gia' i percorsi (formato §8.1); un percorso dentro la cartella
      // dell'app o ostile scarta il segmento intero, come farebbe il codec.
      val rows = ArrayList<RecordRow>(content.records.size)
      for (record in content.records) {
        if (!isMediaPath(record.path) || record.seq !in 1..TrustLimits.MAX_RECORD_SEQ) {
          rejectSegment(RejectReason.HOSTILE_PATH)
          return null
        }
        rows += RecordRow(device, segment.ref.hash, PathKeys.of(record.path), record)
      }
      return rows
    }

    private fun isMediaPath(path: SharePath): Boolean =
      runCatching { SharePath.parseMediaPath(path.value) == path }.getOrDefault(false)

    private fun rejectSegment(reason: RejectReason) {
      segmentsRejected.merge(reason, 1, Int::plus)
      counters.reject(reason)
    }

    private fun missing() {
      segmentsMissing++
      counters.segmentsMissing.incrementAndGet()
    }

    // --- Immagini -------------------------------------------------------------------------------------------------

    private suspend fun downloadImages() {
      if (blobClasses.isEmpty()) return
      val todo = store.segmentsWhere(setOf(SegmentState.PENDING, SegmentState.SKIPPED), blobClasses)
        .filter { it.listed && it.share in names }
        .sortedWith(compareBy<SegmentRow> { CLASS_ORDER.indexOf(it.cls) }.thenBy { it.share }.thenBy { it.deviceId }.thenBy { it.hash })
      if (todo.isEmpty()) return
      bytesTotal += todo.sumOf { it.size }
      val heads = HashMap<Pair<String, String>, IndexHeadView?>()
      mutable.value = IndexSyncStatus.Aligning(bytesDownloaded, bytesTotal, null)
      for (segment in todo) {
        if (timeUp()) {
          complete = false
          break
        }
        val head = heads.getOrPut(segment.share to segment.deviceId) { headView(segment.share, segment.deviceId) } ?: continue
        val fetch = when (val read = remote.segment(head, segment.ref)) {
          is RemoteResult.Failed -> {
            fail(read.failure)
            if (read.failure == RemoteFailure.UNREACHABLE) break else continue
          }
          is RemoteResult.Ok -> read.value
        }
        when (fetch) {
          is SegmentFetch.Ok -> {
            val content = fetch.content
            if (content is SegmentContent.Blobs && content.cls == segment.cls) {
              if (replica.applyBlobSegment(segment, content)) {
                segmentsDownloaded++
                bytesDownloaded += segment.size
                countConflicts(replica.dropRetained(segment.share, segment.deviceId, segment.cls).identityConflicts)
              }
            } else {
              rejectSegment(RejectReason.WRONG_TYPE)
              replica.setSegmentState(segment, SegmentState.REJECTED)
            }
          }
          SegmentFetch.Missing -> {
            missing()
            replica.setSegmentState(segment, SegmentState.MISSING)
          }
          is SegmentFetch.Rejected -> {
            rejectSegment(fetch.reason)
            replica.setSegmentState(segment, SegmentState.REJECTED)
          }
        }
        mutable.value = IndexSyncStatus.Aligning(bytesDownloaded, bytesTotal, eta())
      }
    }

    /** Il livello e' sceso: cio' che era in attesa per classi non piu' richieste torna "saltato". */
    private suspend fun skipUnwanted() {
      val unwanted = ReplicaQualityController.DOWNLOADABLE - blobClasses
      if (unwanted.isEmpty()) return
      store.segmentsWhere(setOf(SegmentState.PENDING), unwanted).forEach { replica.setSegmentState(it, SegmentState.SKIPPED) }
    }

    /** La testa accettata, ricostruita dalla replica, per riscaricare un segmento senza rileggerla. */
    private suspend fun headView(shareKey: String, device: String): IndexHeadView? {
      val state = replica.devices(shareKey)[device] ?: return null
      val head = state.head ?: return null
      return IndexHeadView(
        share = names[shareKey] ?: shareKey,
        deviceId = device,
        version = head.version,
        registryVersion = head.registryVersion,
        createdAtNasMillis = head.createdAtNasMillis,
        mark = head.mark,
        segments = state.segments.filter { it.listed }.map { it.ref }.sortedBy { it.ref.hash },
        registryBehind = false,
      )
    }
  }

  private companion object {
    /** Prima le miniature piccole (la griglia si riempie), poi le medie, poi l'HQ (§2.2, E-I9). */
    val CLASS_ORDER: List<SegmentClass> = listOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.HQ)
  }
}
