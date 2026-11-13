package dev.pampa.mycloudgallery.core.index.testing

import dev.pampa.mycloudgallery.core.index.contract.BlobLocation
import dev.pampa.mycloudgallery.core.index.contract.BlobPut
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.IndexPublisher
import dev.pampa.mycloudgallery.core.index.contract.IndexSync
import dev.pampa.mycloudgallery.core.index.contract.IndexSyncStatus
import dev.pampa.mycloudgallery.core.index.contract.IndexWriteGate
import dev.pampa.mycloudgallery.core.index.contract.KeepAliveResult
import dev.pampa.mycloudgallery.core.index.contract.MetaChange
import dev.pampa.mycloudgallery.core.index.contract.OwnIndexSink
import dev.pampa.mycloudgallery.core.index.contract.PendingCounts
import dev.pampa.mycloudgallery.core.index.contract.ProvisionalThumbnails
import dev.pampa.mycloudgallery.core.index.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import dev.pampa.mycloudgallery.core.index.contract.ReplicaBlobs
import dev.pampa.mycloudgallery.core.index.contract.ReplicaQuality
import dev.pampa.mycloudgallery.core.index.contract.ReplicaQualityState
import dev.pampa.mycloudgallery.core.index.contract.ReplicaRecords
import dev.pampa.mycloudgallery.core.index.contract.SpaceEstimate
import dev.pampa.mycloudgallery.core.index.contract.SyncReport
import dev.pampa.mycloudgallery.core.index.contract.SyncScope
import dev.pampa.mycloudgallery.core.index.contract.UnitClaim
import dev.pampa.mycloudgallery.core.index.contract.UnitLease
import dev.pampa.mycloudgallery.core.index.contract.UnitOutcome
import dev.pampa.mycloudgallery.core.index.contract.UnitPriority
import dev.pampa.mycloudgallery.core.index.contract.WorkItem
import dev.pampa.mycloudgallery.core.index.contract.WorkPass
import dev.pampa.mycloudgallery.core.index.contract.WorkUnitKey
import dev.pampa.mycloudgallery.core.index.contract.WorkUnits
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow

// I fake di core-index (P0, D6 della Fase 2): in src/main per i test di P3–P8 e di :app. Mai nella release
// (tools/check.ps1). Funzionano davvero (seq, outbox, blocchi, D6, priorita'), ma senza database, pacchi su disco ne'
// NAS: sono il terreno comune su cui i pacchetti paralleli si incontrano in P11.

/** Un cancello di scrittura per share, programmabile. */
class MapWriteGate(val blocks: MutableMap<String, WriteBlock> = HashMap()) : IndexWriteGate {
  override suspend fun blockFor(share: String): WriteBlock? = blocks[share.lowercase(Locale.ROOT)]

  fun block(share: String, block: WriteBlock?) {
    if (block == null) blocks.remove(share.lowercase(Locale.ROOT)) else blocks[share.lowercase(Locale.ROOT)] = block
  }
}

/**
 * Il pubblicatore finto: un'outbox in memoria per share, `seq` assegnati in ordine (D5), il cancello rispettato **prima**
 * di pubblicare, la replica locale ([sink]) aggiornata subito a ogni [stage] (D31). Con [remote] i dati pubblicati
 * diventano teste e segmenti di [deviceId] leggibili dagli altri "dispositivi" del test.
 */
class FakeIndexPublisher(
  var gate: IndexWriteGate = IndexWriteGate { null },
  private val sink: OwnIndexSink? = null,
  private val remote: InMemoryRemoteIndex? = null,
  val deviceId: String = "fakedevice00000000000000000",
) : IndexPublisher {

  private class Outbox {
    val records = ArrayList<MetaRecord>()
    val blobs = ArrayList<BlobPut>()
  }

  /** Una pubblicazione riuscita. */
  class Published(val share: String, val headVersion: Long, val records: List<MetaRecord>, val blobs: List<BlobPut>)

  private val outboxes = LinkedHashMap<String, Outbox>()
  private val seqs = HashMap<String, Long>()
  private val versions = HashMap<String, Long>()

  val published: MutableList<Published> = mutableListOf()
  val flushes: MutableList<Pair<String, FlushReason>> = mutableListOf()

  override val pending: MutableStateFlow<PendingCounts> = MutableStateFlow(PendingCounts.EMPTY)

  override suspend fun stage(share: String, changes: List<MetaChange>, blobs: List<BlobPut>) {
    val k = share.lowercase(Locale.ROOT)
    val records = synchronized(this) {
      val box = outboxes.getOrPut(k) { Outbox() }
      val assigned = changes.map { change ->
        val seq = (seqs[k] ?: 0L) + 1
        seqs[k] = seq
        when (change) {
          is MetaChange.Put -> MetaRecord.Present(change.path, seq, change.file)
          is MetaChange.Remove -> MetaRecord.Gone(change.path, seq)
        }
      }
      box.records += assigned
      box.blobs += blobs
      refreshPending()
      assigned
    }
    sink?.applyOwn(share, records, blobs)
  }

  override suspend fun flush(share: String, reason: FlushReason): PublishOutcome {
    val k = share.lowercase(Locale.ROOT)
    flushes += share to reason
    val box = synchronized(this) { outboxes[k] }
    if (box == null || (box.records.isEmpty() && box.blobs.isEmpty())) return PublishOutcome.NothingPending
    gate.blockFor(share)?.let { return PublishOutcome.Blocked(it) }
    val (records, blobs, version) = synchronized(this) {
      val records = box.records.toList()
      val blobs = box.blobs.toList()
      box.records.clear()
      box.blobs.clear()
      val version = (versions[k] ?: 0L) + 1
      versions[k] = version
      refreshPending()
      Triple(records, blobs, version)
    }
    published += Published(share, version, records, blobs)
    remote?.publishOwn(share, deviceId, records, blobs)
    val segments = (if (records.isEmpty()) 0 else 1) + blobs.map { it.cls }.distinct().size
    val bytes = blobs.sumOf { blob ->
      when (blob) {
        is BlobPut.Image -> blob.bytes.size.toLong()
        is BlobPut.Frames -> blob.frames.sumOf { it.jpeg.size.toLong() }
      }
    }
    return PublishOutcome.Published(version, segments, bytes)
  }

  /** Il prossimo `seq` che verra' assegnato in [share] (per i test di ripresa). */
  fun lastSeq(share: String): Long = synchronized(this) { seqs[share.lowercase(Locale.ROOT)] ?: 0 }

  private fun refreshPending() {
    val records = outboxes.values.sumOf { it.records.size }
    val blobs = outboxes.values.sumOf { it.blobs.size }
    val bytes = outboxes.values.sumOf { box ->
      box.blobs.sumOf { if (it is BlobPut.Image) it.bytes.size.toLong() else 0L }
    }
    pending.value = PendingCounts(records, blobs, bytes)
  }
}

/** La finestra visibile, programmabile dal test. */
class FakeUnitPriority(initial: Set<Long> = emptySet()) : UnitPriority {
  override val visible: MutableStateFlow<Set<Long>> = MutableStateFlow(initial)

  /** XS3-1: l'insieme completo delle richieste, se la prova lo vuole diverso da [visible] (di serie vuoto: il selettore ci aggiunge sempre [visible]). */
  override val requested: MutableStateFlow<Set<Long>> = MutableStateFlow(emptySet())
}

/**
 * Le unita' di lavoro finte: il test le offre ([offer]); [next] da' prima quelle con un media visibile, poi dal mese piu'
 * recente (D12), mai una gia' presa o finita. Prenotazioni programmabili ([contended], [loseOnKeepAlive]).
 */
class FakeWorkUnits(var nasNow: () -> Long = { 1_791_936_000_000L }) : WorkUnits {
  private val offered = LinkedHashMap<WorkUnitKey, List<WorkItem>>()
  private val taken = HashSet<WorkUnitKey>()

  /** Si lavora senza prenotazione (cartella delle prenotazioni anomala, E-I1). */
  var contended: Boolean = false

  /** Il prossimo rinnovo trova la prenotazione presa da un altro. */
  var loseOnKeepAlive: Boolean = false

  val claims: MutableList<UnitClaim> = mutableListOf()
  val renewals: MutableList<UnitClaim> = mutableListOf()
  val finished: MutableList<Pair<WorkUnitKey, UnitOutcome>> = mutableListOf()

  fun offer(key: WorkUnitKey, items: List<WorkItem>) {
    synchronized(this) { offered[key] = items }
  }

  override suspend fun next(share: String, pass: WorkPass, priority: UnitPriority): UnitClaim? {
    val visible = priority.visible.value
    val claim = synchronized(this) {
      val candidates = offered.entries
        .filter { (key, items) -> key.share.equals(share, ignoreCase = true) && key.pass == pass && key !in taken && items.isNotEmpty() }
        .sortedWith(
          compareByDescending<Map.Entry<WorkUnitKey, List<WorkItem>>> { (_, items) -> items.any { it.contentKey.short in visible } }
            .thenByDescending { it.key.month }
            .thenBy { it.key.part },
        )
      val chosen = candidates.firstOrNull() ?: return null
      taken += chosen.key
      val lease = if (contended) UnitLease.Unleased else UnitLease.Held(generation = 1, untilNasMillis = nasNow() + TrustLimits.MAX_LEASE_MILLIS)
      UnitClaim(chosen.key, chosen.value, lease)
    }
    claims += claim
    return claim
  }

  override suspend fun keepAlive(claim: UnitClaim): KeepAliveResult {
    if (loseOnKeepAlive) return KeepAliveResult.Lost
    val lease = when (val current = claim.lease) {
      is UnitLease.Held -> UnitLease.Held(current.generation + 1, nasNow() + TrustLimits.MAX_LEASE_MILLIS)
      UnitLease.Unleased -> UnitLease.Unleased
    }
    val renewed = claim.copy(lease = lease)
    renewals += renewed
    return KeepAliveResult.Renewed(renewed)
  }

  override suspend fun finish(claim: UnitClaim, outcome: UnitOutcome) {
    synchronized(this) {
      finished += claim.key to outcome
      if (outcome is UnitOutcome.Completed) offered.remove(claim.key) else taken.remove(claim.key)
    }
  }
}

/**
 * La replica locale finta: blob per (chiave ridotta, classe), record per (share, percorso, dispositivo) con la scelta
 * D6, miniature provvisorie, e i dati di questo dispositivo ricevuti da [applyOwn]. Nessun pacco su disco: [read]
 * restituisce un buffer in sola lettura sopra la copia in memoria.
 */
class InMemoryReplica(val ownDeviceId: String = "fakedevice00000000000000000") :
  ReplicaBlobs, ReplicaRecords, OwnIndexSink, ProvisionalThumbnails {

  private val blobs = ArrayList<ByteArray>()
  private val blobIndex = HashMap<Pair<Long, SegmentClass>, Int>()
  private val records = HashMap<String, HashMap<SharePath, HashMap<String, MetaRecord>>>()
  private val provisional = HashMap<Long, ByteArray>()

  /** Quante letture di blob (per provare "nessuna copia", "nessuna lettura" e simili). */
  @Volatile var reads: Int = 0
    private set

  @Synchronized
  fun putBlob(contentKey: ContentKey, cls: SegmentClass, bytes: ByteArray) {
    blobIndex[contentKey.short to cls] = blobs.size
    blobs += bytes.copyOf()
  }

  /** Un record di [deviceId] (anche una lapide): vince il `seq` piu' alto dello stesso dispositivo (D5). */
  @Synchronized
  fun putRecord(share: String, deviceId: String, record: MetaRecord) {
    val byDevice = records.getOrPut(share.lowercase(Locale.ROOT)) { HashMap() }.getOrPut(record.path) { HashMap() }
    val previous = byDevice[deviceId]
    if (previous == null || previous.seq < record.seq) byDevice[deviceId] = record
  }

  @Synchronized
  fun blobCount(): Int = blobIndex.size

  @Synchronized
  override fun locate(contentKey: Long, cls: SegmentClass): BlobLocation? =
    blobIndex[contentKey to cls]?.let { BlobLocation(packId = it.toLong(), offset = 0, length = blobs[it].size, cls = cls) }

  @Synchronized
  override fun read(location: BlobLocation): ByteBuffer {
    val bytes = blobs.getOrNull(location.packId.toInt()) ?: throw IOException("pacco assente")
    reads++
    return ByteBuffer.wrap(bytes, location.offset.toInt(), location.length).slice().asReadOnlyBuffer()
  }

  override suspend fun estimate(quality: QualityLevel): SpaceEstimate = synchronized(this) {
    val chosen = blobIndex.filterKeys { it.second in quality.classes }.values
    SpaceEstimate(chosen.sumOf { blobs[it].size.toLong() }, chosen.size)
  }

  override suspend fun matching(share: String, stamps: Map<SharePath, NasStamp>): Map<SharePath, MetaRecord.Present> =
    synchronized(this) { matchingLocked(share, stamps) }

  private fun matchingLocked(share: String, stamps: Map<SharePath, NasStamp>): Map<SharePath, MetaRecord.Present> {
    val byPath = records[share.lowercase(Locale.ROOT)] ?: return emptyMap()
    val out = HashMap<SharePath, MetaRecord.Present>()
    for ((path, stamp) in stamps) {
      val winner = byPath[path].orEmpty().entries
        .mapNotNull { (device, record) -> (record as? MetaRecord.Present)?.takeIf { it.file.stamp.sameAs(stamp) }?.let { device to it } }
        // D6: piu' classi preparate, poi l'id di dispositivo minore.
        .sortedWith(compareByDescending<Pair<String, MetaRecord.Present>> { Integer.bitCount(it.second.file.prepared) }.thenBy { it.first })
        .firstOrNull()
      if (winner != null) out[path] = winner.second
    }
    return out
  }

  override suspend fun applyOwn(share: String, records: List<MetaRecord>, blobs: List<BlobPut>) {
    records.forEach { putRecord(share, ownDeviceId, it) }
    blobs.filterIsInstance<BlobPut.Image>().forEach { putBlob(it.contentKey, it.cls, it.bytes) }
  }

  override suspend fun put(contentKey: ContentKey, jpeg: ByteArray) {
    synchronized(this) { provisional[contentKey.short] = jpeg.copyOf() }
  }

  @Synchronized
  override fun get(contentKey: Long): ByteArray? = provisional[contentKey]?.copyOf()

  override suspend fun forget(contentKey: Long) {
    synchronized(this) { provisional.remove(contentKey) }
  }
}

/** La qualita' finta: [set] cambia il livello e segna "in convergenza" (lo toglie il test con [converged]). */
class FakeReplicaQuality(initial: QualityLevel = QualityLevel.DEFAULT) : ReplicaQuality {
  override val state: MutableStateFlow<ReplicaQualityState> =
    MutableStateFlow(ReplicaQualityState(initial, emptyMap(), currentBytes = 0, converging = false))

  override suspend fun set(level: QualityLevel) {
    state.value = state.value.copy(level = level, converging = level != state.value.level)
  }

  fun converged(currentBytes: Long) {
    state.value = state.value.copy(currentBytes = currentBytes, converging = false)
  }
}

/** La sincronizzazione finta: ricorda gli ambiti chiesti e restituisce [nextReport] (o esegue [onSync]). */
class FakeIndexSync(initial: IndexSyncStatus = IndexSyncStatus.Idle) : IndexSync {
  override val status: MutableStateFlow<IndexSyncStatus> = MutableStateFlow(initial)

  var nextReport: SyncReport = SyncReport.EMPTY
  var onSync: (suspend (SyncScope) -> SyncReport)? = null
  val scopes: MutableList<SyncScope> = mutableListOf()

  override suspend fun sync(scope: SyncScope, budget: Duration): SyncReport {
    scopes += scope
    return onSync?.invoke(scope) ?: nextReport
  }
}

/** Il pubblicatore e la replica di un "dispositivo" finto, collegati come in :app (stage → replica locale). */
fun fakeIndexDevice(
  deviceId: String,
  remote: InMemoryRemoteIndex? = null,
  gate: IndexWriteGate = IndexWriteGate { null },
): Pair<FakeIndexPublisher, InMemoryReplica> {
  val replica = InMemoryReplica(deviceId)
  return FakeIndexPublisher(gate, replica, remote, deviceId) to replica
}
