package dev.pampa.mycloudgallery.core.faces.sync

import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.FaceEntry
import dev.pampa.mycloudgallery.core.faces.contract.FacesChange
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesSyncReport
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteGate
import dev.pampa.mycloudgallery.core.faces.contract.GroupAssignment
import dev.pampa.mycloudgallery.core.faces.contract.GroupPerson
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesClock
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesSwitch
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesArchive
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceDetectorCode
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceReasons
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.security.MessageDigest
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.hours

/**
 * Il mondo dei test di sync/ (Fase 8, P3): una famiglia vera con due account (`test`, `test2`), le loro aree private in memoria e l'area
 * comune, l'ora del NAS e l'orologio monotono finti, una generazione corrente. Ogni [SyncDevice] ha il suo archivio, i suoi store, la sua
 * replica e il suo pubblicatore veri sopra `NasFacesRemote`. Solo identita' sintetiche, nomi inventati.
 */
class FacesSyncWorld(val flags: FeatureFlags = FeatureFlags.RELEASE) {
  val clock = FakeFacesClock()
  val family = FacesTestFamily(clock)
  val generation: FaceGeneration = FaceGeneration.fromBytes(ByteArray(16) { (it * 13 + 1).toByte() })
  val shares = LinkedHashMap<String, MemoryShare>()

  fun share(name: String): MemoryShare = shares.getOrPut(name) { MemoryShare(name) }

  /** Un dispositivo nuovo approvato dell'account [account] che lavora nell'area privata [share]. */
  fun device(name: String, account: String = "test", share: String = account): SyncDevice =
    SyncDevice(this, name, family.approve(name, account), share(share))

  /** Un dispositivo di cui si conosce gia' la chiave (ripreso dopo una reinstallazione dello stato, o in un altro processo). */
  fun device(name: String, signer: Signer, share: String): SyncDevice = SyncDevice(this, name, signer, share(share))

  companion object {
    fun key(n: Int): ContentKey =
      ContentKey(MessageDigest.getInstance("SHA-256").digest("contenuto-$n".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) })

    fun vector(seed: Int): ShortArray {
      val v = FloatArray(FaceSpaceCode.CURRENT.dimension) { ((it * 31 + seed * 19) % 37 - 18).toFloat() + 0.25f }
      val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x })
      return Float16.encode(FloatArray(v.size) { v[it] / norm })
    }

    fun entry(index: Int, tier: FaceTier = FaceTier.CORE, seed: Int = index): FaceEntry = FaceEntry(
      index = index,
      frame = 0,
      box = FaceBox(index * 7_000, 1_000, index * 7_000 + 6_000, 7_000),
      landmarks = ShortArray(10) { (it * 500).toShort() },
      score = 210,
      tier = tier,
      reasons = if (tier == FaceTier.CORE) 0 else FaceReasons.SMALL,
      sidePx = if (tier == FaceTier.CORE) 96 else 30,
      yaw = 5,
      sharpness = 120,
      vector = if (tier.hasVector) vector(seed) else null,
    )

    fun person(n: Int): PersonId = PersonId.fromBytes(MessageDigest.getInstance("SHA-256").digest("persona-$n".toByteArray()).copyOf(16))
  }
}

/** Un dispositivo dell'account: archivio, store, replica e pubblicatore veri; [restart] simula la morte del processo. */
class SyncDevice(val world: FacesSyncWorld, val name: String, val signer: Signer, val share: MemoryShare) {
  val id: String = TrustIds.deviceIdOf(signer.publicKey)
  val switch = FakeFacesSwitch(initial = FacesState.On(world.generation, HereState.ACTIVE))
  var archive = InMemoryFacesArchive(world.generation)
  var replicaStore = InMemoryFacesReplicaStore()
  var publishStore = InMemoryFacesPublishStore()

  /** Le chiavi che "M3 dei volti" ammette (null = tutte quelle chieste). */
  var attested: Set<ContentKey>? = null

  /** Il cancello delle scritture (D35): null = aperto. */
  var block: FacesWriteBlock? = null

  /** Ogni scrittura riuscita di questo dispositivo sulla share: chi e dove. */
  val written: MutableList<Pair<String, SharePath>> = java.util.Collections.synchronizedList(mutableListOf())

  /** Ogni tentativo di scrittura di questo dispositivo (anche fallito per un guasto). */
  val attempts: MutableList<Pair<String, SharePath>> = java.util.Collections.synchronizedList(mutableListOf())

  val identity = object : FacesSyncIdentity {
    override val deviceId: String = id
    override val signer: Signer = this@SyncDevice.signer

    override fun registry() = world.family.state
  }

  val remote = NasFacesRemote(RecordingShare(share, id, written, attempts), id, { world.family.state.registry.config.commonShare })
  val archives = FacesArchives { g -> archive.takeIf { it.generation == g && !it.closed } }
  val attestation = FacesAttestation { keys -> attested?.let { allowed -> keys.filterTo(LinkedHashSet()) { it in allowed } } ?: keys.toSet() }
  val gate = FacesWriteGate { block }

  lateinit var publisher: NasFacesPublisher
    private set
  lateinit var sync: NasFacesSync
    private set

  init {
    restart()
  }

  /** Un processo nuovo (la morte del processo); con [loseLocal] anche lo stato locale perso (reinstallazione dei dati, non della chiave). */
  fun restart(loseLocal: Boolean = false) {
    if (loseLocal) {
      archive = InMemoryFacesArchive(world.generation)
      replicaStore = InMemoryFacesReplicaStore()
      publishStore = InMemoryFacesPublishStore()
    }
    publisher = NasFacesPublisher(identity, switch, remote, archives, publishStore, attestation, gate, world.clock, world.flags)
    sync = NasFacesSync(identity, switch, remote, archives, replicaStore, gate, world.clock, publisher, world.flags)
  }

  /** Il calcolo di un contenuto (P4): il corpo con l'impronta giusta nell'archivio, e in coda per la pubblicazione. */
  suspend fun compute(n: Int, faces: Int = 2, recipe: Int = 1): FaceBody {
    val entries = (0 until faces).map { SyncWorldFaces.entry(it, if (it % 3 == 2) FaceTier.WEAK else FaceTier.CORE, seed = n * 10 + it) }
    val body = FacesWire.build(FacesSyncWorld.key(n), FaceSpaceCode.CURRENT, FaceDetectorCode.YUNET_2023MAR, recipe, FaceSource.ANALYSIS, id, entries)!!
    archive.saveBody(body)
    publisher.stage(FacesChange.Faces(body.contentKey))
    return body
  }

  /** Nomi e correzioni (P9): voci con l'ora del NAS di adesso (null = da datare), nell'archivio e in coda. */
  suspend fun edit(vararg edits: PeopleEdit, dated: Boolean = true) {
    archive.addPeople(edits.map { PeopleRecord(it, if (dated) world.clock.nasNow else null, id) })
    publisher.stage(FacesChange.People(edits.toList()))
  }

  /** Il custode (P5): un'istantanea [seq] con una persona per gruppo di volti [groups] (indice del contenuto, indice del volto). */
  suspend fun snapshot(seq: Long, base: Long, groups: Map<PersonId, List<Pair<Int, Int>>>) {
    val winners = archive.winners(groups.values.flatten().map { FacesSyncWorld.key(it.first) })
    val assignments = LinkedHashMap<dev.pampa.mycloudgallery.core.model.FaceRef, GroupAssignment>()
    for ((person, faces) in groups) {
      for ((n, index) in faces) {
        val body = winners.getValue(FacesSyncWorld.key(n))
        val ref = body.refOf(index)
        assignments[ref] = GroupAssignment(ref, FaceLocator(body.contentKey, 0, body.faces[index].box), person, 0.8f, AssignOrigin.AUTO)
      }
    }
    val persons = groups.mapValues { (person, faces) ->
      val exemplars = faces.take(3).map { (n, index) -> winners.getValue(FacesSyncWorld.key(n)).refOf(index) }
      GroupPerson(person, PersonState.ACTIVE, null, 1, seq, exemplars)
    }
    archive.saveGrouping(GroupingState(seq, base, persons, assignments))
    publisher.stage(FacesChange.Snapshot(seq))
  }

  suspend fun round(): FacesSyncReport = sync.sync(1.hours)

  override fun toString(): String = "SyncDevice($name)"
}

/** I volti sintetici dei test (gli stessi del mondo, accessibili anche dagli oggetti senza istanza). */
object SyncWorldFaces {
  fun entry(index: Int, tier: FaceTier, seed: Int): FaceEntry = FacesSyncWorld.entry(index, tier, seed)
}
