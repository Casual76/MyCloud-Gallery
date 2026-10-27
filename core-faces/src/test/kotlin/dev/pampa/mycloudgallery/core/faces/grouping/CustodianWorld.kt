package dev.pampa.mycloudgallery.core.faces.grouping

import dev.pampa.mycloudgallery.core.faces.contract.ClusterSnapshot
import dev.pampa.mycloudgallery.core.faces.contract.ClusterSnapshots
import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.FaceEntry
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.FacesChange
import dev.pampa.mycloudgallery.core.faces.contract.FacesLease
import dev.pampa.mycloudgallery.core.faces.contract.FacesLeases
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublishOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesPublisher
import dev.pampa.mycloudgallery.core.faces.contract.FacesResource
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.noop.NoGoneContents
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesArchive
import dev.pampa.mycloudgallery.core.model.FaceDetectorCode
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceReasons
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.Float16
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Il NAS finto del custode (P5, D21): le istantanee pubblicate da ogni dispositivo dell'account, la prenotazione `faces/<G>/cluster`
 * (1 ora d'ora del NAS, [now] in millisecondi) e i modi in cui il NAS si comporta male (contesa, cancello chiuso, errore). E' il "fake di
 * P3" per le prove del custode: P3 consegnera' la replica vera (`ClusterSnapshots`) e il pubblicatore vero.
 */
internal class ClusterNas(val generation: FaceGeneration = FaceGeneration.fromBytes(ByteArray(16) { 7 })) {
  val published = LinkedHashMap<String, MutableList<ClusterSnapshot>>()
  var now: Long = 1_800_000_000_000L
  var holder: String? = null
  var until: Long = 0
  var contended = false
  var blocked: FacesWriteBlock? = null
  var failing = false
  private var number = 0L

  val snapshots = ClusterSnapshots { g -> if (g != generation) emptyList() else published.values.mapNotNull { it.lastOrNull() } }

  fun leases(device: String): FacesLeases = object : FacesLeases {
    override suspend fun acquire(generation: FaceGeneration, resource: FacesResource): FacesLease {
      check(resource == FacesResource.Cluster && generation == this@ClusterNas.generation)
      trouble()?.let { return it }
      if (holder == null || now >= until || holder == device) return take(device)
      return FacesLease.Busy
    }

    override suspend fun renew(generation: FaceGeneration, resource: FacesResource, held: FacesLease.Held): FacesLease {
      trouble()?.let { return it }
      return when {
        holder == device -> take(device)
        holder == null || now >= until -> take(device)
        else -> FacesLease.Lost
      }
    }

    override suspend fun release(generation: FaceGeneration, resource: FacesResource, held: FacesLease.Held) {
      if (holder == device) holder = null
    }
  }

  private fun trouble(): FacesLease? = when {
    blocked != null -> FacesLease.Blocked(blocked!!)
    failing -> FacesLease.Failed
    contended -> FacesLease.Contended
    else -> null
  }

  private fun take(device: String): FacesLease.Held {
    holder = device
    until = now + HOUR
    return FacesLease.Held(++number, until)
  }

  fun latest(device: String): ClusterSnapshot? = published[device]?.lastOrNull()

  companion object {
    const val HOUR: Long = 60L * 60 * 1000
  }
}

/**
 * Un dispositivo dell'account con Persone attiva: il suo archivio (i corpi dei volti come li da' la replica), il pubblicatore finto (mette
 * in coda le istantanee e le pubblica sul [ClusterNas] leggendo l'archivio al momento, come fara' P3) e il lavoro del raggruppamento.
 */
internal class ClusterDevice(
  val id: String,
  val nas: ClusterNas,
  idsSeed: Long,
  params: GroupingParams = GroupingParams.DEFAULT,
) {
  var archive: InMemoryFacesArchive = InMemoryFacesArchive(nas.generation)
  var offline = false
  val staged = sortedSetOf<Long>()

  val publisher = object : FacesPublisher {
    private val count = MutableStateFlow(0)
    override val pending: StateFlow<Int> = count

    override suspend fun stage(change: FacesChange) {
      if (change is FacesChange.Snapshot) staged += change.seq
      count.value = staged.size
    }

    override suspend fun flush(reason: FlushReason): FacesPublishOutcome {
      if (staged.isEmpty()) return FacesPublishOutcome.NothingToPublish
      if (offline) return FacesPublishOutcome.Failed
      nas.published.getOrPut(id) { ArrayList() } += ClusterSnapshot(id, archive.grouping())
      staged.clear()
      count.value = 0
      return FacesPublishOutcome.Published(nas.published.getValue(id).size.toLong(), 1)
    }
  }

  val grouping = CustodianGrouping(
    archives = { archive },
    deviceId = id,
    leases = nas.leases(id),
    snapshots = nas.snapshots,
    publisher = publisher,
    gone = NoGoneContents,
    engine = FastGrouping(),
    ids = GroupingStories.ids(idsSeed),
    params = params,
  )

  suspend fun receive(faces: List<GroupFace>) {
    for (body in bodies(faces)) archive.saveBody(body)
  }

  suspend fun name(edit: PeopleEdit, at: Long) {
    archive.addPeople(listOf(PeopleRecord(edit, at, id)))
  }

  companion object {
    /** I corpi `faces` (uno per contenuto, autore "calc") dei volti sintetici: indici contigui, vettori in float16 (senza perdite). */
    fun bodies(faces: List<GroupFace>): List<FaceBody> = faces.groupBy { it.ref.contentKey }.map { (key, list) ->
      val sorted = list.sortedBy { it.ref.index }
      require(sorted.map { it.ref.index } == sorted.indices.toList()) { "Indici non contigui." }
      FaceBody(
        key, FaceSpaceCode.CURRENT, FaceDetectorCode.YUNET_2023MAR, 1, FaceSource.ANALYSIS, "calc",
        sorted.map { f ->
          FaceEntry(
            index = f.ref.index, frame = f.ref.frame, box = f.locator.box, landmarks = ShortArray(10), score = 220, tier = f.tier,
            reasons = if (f.tier == FaceTier.CORE) 0 else FaceReasons.SMALL, sidePx = 96, yaw = 0, sharpness = 180,
            vector = Float16.encode(f.vector()),
          )
        },
        sorted.first().ref.bodyDigest,
      )
    }
  }
}

/** Un archivio che si chiude a meta' giro (generazione cambiata, chiave distrutta): dopo [closeAfter] chiamate lancia come quello vero. */
internal class ClosingArchive(private val inner: InMemoryFacesArchive, private var closeAfter: Int) : FacesArchive by inner {
  private fun tick() {
    if (--closeAfter < 0) inner.close()
  }

  override suspend fun groupFaces(): List<GroupFace> = inner.groupFaces().also { tick() }

  override suspend fun people(): Map<dev.pampa.mycloudgallery.core.faces.contract.PeopleKey, PeopleRecord> = inner.people().also { tick() }

  override suspend fun grouping(): dev.pampa.mycloudgallery.core.faces.contract.GroupingState = inner.grouping().also { tick() }
}
