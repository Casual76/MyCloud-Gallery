package dev.pampa.mycloudgallery.core.faces.store

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.faces.dao.ChunkRef
import dev.pampa.mycloudgallery.core.database.faces.dao.FacesSyncDao
import dev.pampa.mycloudgallery.core.database.faces.dao.ReplicaWrite
import dev.pampa.mycloudgallery.core.database.faces.entity.FacesChunkEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.ReplicaDeviceEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.ReplicaSegmentEntity
import dev.pampa.mycloudgallery.core.database.faces.entity.ReplicaSegmentStateEntity
import dev.pampa.mycloudgallery.core.faces.sync.DeviceReplica
import dev.pampa.mycloudgallery.core.faces.sync.FacesReplicaState
import dev.pampa.mycloudgallery.core.faces.sync.FacesReplicaStore
import dev.pampa.mycloudgallery.core.faces.sync.SegmentWait
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.security.faces.FacesDataKey
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.ClusterEntry
import dev.pampa.mycloudgallery.core.trust.data.FacesDocs
import dev.pampa.mycloudgallery.core.trust.data.FacesSnapshotDoc
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import dev.pampa.mycloudgallery.core.trust.data.SnapshotMark
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * La replica dei volti sul database (Fase 8, P6; `docs/fase8-progetto.md` D14, D15, §2.4): `FacesReplicaStore` di P3 con lo stato che **sopravvive
 * alla morte del processo** (il giro dopo non rilegge tutte le teste e tutti i segmenti dal NAS). Vale per la generazione del negozio che la
 * tiene; un'altra generazione e' vuota in lettura e un errore in scrittura (il database di una generazione non ospita le altre).
 *
 * - **Cifrato** (C39): le voci `cluster` (persone con il centroide, assegnazioni) stanno solo in `faces_chunk`, sigillate (`ClusterChunks`);
 *   il resto sono impronte, numeri di versione e nomi di file.
 * - **Atomico e durevole**: ogni [save] e' una transazione (`FacesSyncDao.writeReplica`); se fallisce, la transazione torna indietro e lo stato
 *   di prima e' quello che si rilegge.
 * - **Veloce**: lo stato si carica **una volta** per negozio aperto e poi si tiene l'ultimo stato scritto in memoria ([mirror]): [load] non
 *   decodifica niente (P5 lo chiede a ogni suo giro) e [save] scrive **solo la differenza** (un dispositivo che non cambia, un segmento che
 *   c'era gia': un segmento e' il suo contenuto, la sua impronta lo identifica).
 * - Una riga che non si legge (un blocco che non si apre) non rompe lo stato: il segmento o l'istantanea tornano "da rifare" e la replica li
 *   riscarica al giro dopo.
 *
 * Chiuso (negozio chiuso o cancellato): ogni chiamata lancia `IllegalStateException`, come l'archivio.
 */
internal class RoomFacesReplicaStore(
  private val generation: FaceGeneration,
  private val dao: FacesSyncDao,
  private val key: FacesDataKey,
) : FacesReplicaStore {

  private val lock = Mutex()

  /** L'ultimo stato letto o scritto: cio' che il database contiene. */
  private var mirror: FacesReplicaState? = null

  @Volatile private var closed = false

  /** Da qui ogni chiamata lancia (negozio ritirato per la cancellazione) e lo stato in memoria si lascia andare. */
  fun retire() {
    closed = true
    mirror = null
  }

  override suspend fun load(generation: FaceGeneration): FacesReplicaState {
    check(!closed) { "Replica dei volti chiusa." }
    if (generation != this.generation) return FacesReplicaState.EMPTY
    return lock.withLock { current() }
  }

  override suspend fun save(generation: FaceGeneration, state: FacesReplicaState) {
    check(!closed) { "Replica dei volti chiusa." }
    check(generation == this.generation) { "Lo stato di un'altra generazione non si salva in questo database." }
    lock.withLock {
      val old = current()
      if (old === state) return
      val write = diff(old, state)
      dao.writeReplica(write)
      mirror = state
    }
  }

  private suspend fun current(): FacesReplicaState = mirror ?: read().also { mirror = it }

  // --- Lettura ---------------------------------------------------------------------------------------------------------------------

  private suspend fun read(): FacesReplicaState {
    val segments = dao.replicaSegments().groupBy { it.deviceId }
    val states = dao.replicaStates().groupBy { it.deviceId }
    val clusters = dao.chunks(ClusterChunks.REPLICA_CLUSTER).groupBy { ClusterChunks.Key(it.scope, it.owner, it.name) }
    val snapshots = dao.chunks(ClusterChunks.REPLICA_SNAPSHOT).groupBy { it.owner }
    val devices = LinkedHashMap<String, DeviceReplica>()
    for (row in dao.replicaDevices().sortedBy { it.deviceId }) {
      devices[row.deviceId] = deviceOf(row, segments[row.deviceId].orEmpty(), states[row.deviceId].orEmpty(), clusters, snapshots[row.deviceId].orEmpty())
    }
    return FacesReplicaState(devices, dao.replicaRevoked().toSet())
  }

  private fun deviceOf(
    row: ReplicaDeviceEntity,
    segmentRows: List<ReplicaSegmentEntity>,
    stateRows: List<ReplicaSegmentStateEntity>,
    clusters: Map<ClusterChunks.Key, List<FacesChunkEntity>>,
    snapshotRows: List<FacesChunkEntity>,
  ): DeviceReplica {
    val segments = segmentRows.sortedBy { it.position }.mapNotNull { segmentOf(it) }
    var done = stateRows.filter { it.done }.mapTo(LinkedHashSet()) { it.hash }
    var counts = stateRows.filter { it.entries != null }.associate { it.hash to it.entries!! }
    // R5, R7 (schema v2): i segmenti in attesa.
    val waits = stateRows.filter { !it.done && it.retryAt != null && it.attempts > 0 }.associate { it.hash to SegmentWait(it.attempts, it.retryAt!!) }
    var checked = mark(row.checkedVersion, row.checkedHash)
    // I segmenti `cluster` fatti si rileggono dai blocchi; uno che non si legge torna "da fare" (e l'istantanea da rigiudicare).
    val cluster = LinkedHashMap<String, List<ClusterEntry>>()
    for (segment in segments.filter { it.cls == SegmentClass.CLUSTER }) {
      val rows = clusters[ClusterChunks.Key(ClusterChunks.REPLICA_CLUSTER, row.deviceId, segment.ref.hash)] ?: continue
      val entries = ClusterChunks.decode(ClusterChunks.Key(ClusterChunks.REPLICA_CLUSTER, row.deviceId, segment.ref.hash), rows, key)
      if (entries == null) {
        done = LinkedHashSet(done - segment.ref.hash)
        counts = counts - segment.ref.hash
        checked = null
      } else {
        cluster[segment.ref.hash] = entries
      }
    }
    var snapshot: FacesSnapshotDoc? = null
    var snapshotHead = mark(row.snapshotHeadVersion, row.snapshotHeadHash)
    val snapMark = if (row.snapSeq != null && row.snapBase != null) SnapshotMark(row.snapSeq!!, row.snapBase!!) else null
    if (snapMark != null) {
      val rows = snapshotRows.filter { it.name == SNAPSHOT_NAME }
      val entries = ClusterChunks.decode(ClusterChunks.Key(ClusterChunks.REPLICA_SNAPSHOT, row.deviceId, SNAPSHOT_NAME), rows, key)
      snapshot = entries?.let { (FacesDocs.checkSnapshot(snapMark, it) as? Verdict.Accepted)?.value }
      if (snapshot == null) {
        snapshotHead = null
        checked = null
        GalleryLog.w(TAG) { "un'istantanea della replica non si legge: si rigiudica" }
      }
    }
    return DeviceReplica(
      mark = mark(row.markVersion, row.markHash),
      segments = segments,
      snapshotMark = if (row.snapshotSeq != null && row.snapshotBase != null) SnapshotMark(row.snapshotSeq!!, row.snapshotBase!!) else null,
      done = done,
      counts = counts,
      cluster = cluster,
      snapshot = snapshot,
      snapshotHead = snapshotHead,
      checked = checked,
      waits = waits.filterKeys { it !in done },
    )
  }

  private fun mark(version: Long?, hash: String?): HeadMark? = if (version != null && hash != null) HeadMark(version, hash) else null

  private fun segmentOf(row: ReplicaSegmentEntity): ClassedSegment? = try {
    val cls = SegmentClass.entries.firstOrNull { it.code == row.cls } ?: return null
    ClassedSegment(cls, SegmentRef(row.hash, row.size))
  } catch (_: RuntimeException) {
    null
  }

  // --- Scrittura: la differenza -------------------------------------------------------------------------------------------------------

  private fun diff(old: FacesReplicaState, new: FacesReplicaState): ReplicaWrite {
    val removedDevices = old.devices.keys.filter { it !in new.devices }
    val devices = ArrayList<ReplicaDeviceEntity>()
    val rewritten = ArrayList<String>()
    val segments = ArrayList<ReplicaSegmentEntity>()
    val states = ArrayList<ReplicaSegmentStateEntity>()
    val removedChunks = ArrayList<ChunkRef>()
    val chunks = ArrayList<FacesChunkEntity>()
    for ((id, replica) in new.devices) {
      val before = old.devices[id]
      if (before === replica) continue
      val entity = entityOf(id, replica)
      if (before == null || !sameScalars(entityOf(id, before), entity)) devices += entity
      if (before == null || before.segments != replica.segments || before.done != replica.done || before.counts != replica.counts || before.waits != replica.waits) {
        rewritten += id
        replica.segments.forEachIndexed { position, s -> segments += ReplicaSegmentEntity(id, position, s.cls.code, s.ref.hash, s.ref.size) }
        for (hash in replica.done + replica.counts.keys + replica.waits.keys) {
          val wait = replica.waits[hash]?.takeIf { hash !in replica.done }
          states += ReplicaSegmentStateEntity(id, hash, hash in replica.done, replica.counts[hash], wait?.attempts ?: 0, wait?.retryAtNas)
        }
      }
      // Un segmento e' il suo contenuto: se l'impronta c'era gia' non si riscrive.
      val oldCluster = before?.cluster.orEmpty()
      for (hash in oldCluster.keys) if (hash !in replica.cluster) removedChunks += ChunkRef(ClusterChunks.REPLICA_CLUSTER, id, hash)
      for ((hash, entries) in replica.cluster) {
        if (hash in oldCluster) continue
        chunks += ClusterChunks.encode(ClusterChunks.Key(ClusterChunks.REPLICA_CLUSTER, id, hash), entries, key)
      }
      if (!sameSnapshot(before, replica)) {
        removedChunks += ChunkRef(ClusterChunks.REPLICA_SNAPSHOT, id, SNAPSHOT_NAME)
        replica.snapshot?.let { doc ->
          chunks += ClusterChunks.encode(ClusterChunks.Key(ClusterChunks.REPLICA_SNAPSHOT, id, SNAPSHOT_NAME), doc.persons + doc.assignments, key)
        }
      }
    }
    return ReplicaWrite(
      removedDevices = removedDevices,
      devices = devices,
      rewrittenDevices = rewritten,
      segments = segments,
      states = states,
      removedChunks = removedChunks,
      chunks = chunks,
      addedRevoked = new.revoked.filter { it !in old.revoked },
      removedRevoked = old.revoked.filter { it !in new.revoked },
    )
  }

  private fun entityOf(id: String, replica: DeviceReplica) = ReplicaDeviceEntity(
    deviceId = id,
    markVersion = replica.mark?.version,
    markHash = replica.mark?.contentHash,
    snapshotSeq = replica.snapshotMark?.seq,
    snapshotBase = replica.snapshotMark?.base,
    snapshotHeadVersion = replica.snapshotHead?.version,
    snapshotHeadHash = replica.snapshotHead?.contentHash,
    snapSeq = replica.snapshot?.mark?.seq,
    snapBase = replica.snapshot?.mark?.base,
    checkedVersion = replica.checked?.version,
    checkedHash = replica.checked?.contentHash,
  )

  private fun sameScalars(a: ReplicaDeviceEntity, b: ReplicaDeviceEntity): Boolean =
    a.markVersion == b.markVersion && a.markHash == b.markHash && a.snapshotSeq == b.snapshotSeq && a.snapshotBase == b.snapshotBase &&
      a.snapshotHeadVersion == b.snapshotHeadVersion && a.snapshotHeadHash == b.snapshotHeadHash && a.snapSeq == b.snapSeq &&
      a.snapBase == b.snapBase && a.checkedVersion == b.checkedVersion && a.checkedHash == b.checkedHash

  /**
   * L'istantanea completa e coerente di un dispositivo e' determinata dalla sua testa (le voci sono i segmenti `cluster` di quella testa): la
   * stessa testa e la stessa `seq` sono lo stesso contenuto, e non si riscrive.
   */
  private fun sameSnapshot(before: DeviceReplica?, after: DeviceReplica): Boolean {
    val a = before?.snapshot
    val b = after.snapshot
    if (a === b) return true
    if (a == null || b == null) return false
    return a.mark == b.mark && before.snapshotHead == after.snapshotHead && a.persons.size == b.persons.size && a.assignments.size == b.assignments.size
  }

  override fun toString(): String = "RoomFacesReplicaStore"

  private companion object {
    const val TAG = "FacesReplica"
    const val SNAPSHOT_NAME = "0"
  }
}
