package dev.pampa.mycloudgallery.core.faces.erase

import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.FacesActivationConsent
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.FacesChange
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteGate
import dev.pampa.mycloudgallery.core.faces.contract.FlushReason
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleKey
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.contract.PrivateAreaState
import dev.pampa.mycloudgallery.core.faces.store.LocalErase
import dev.pampa.mycloudgallery.core.faces.switch.DefaultFacesSwitch
import dev.pampa.mycloudgallery.core.faces.switch.FacesLocalDataEraser
import dev.pampa.mycloudgallery.core.faces.switch.FacesPrePublishHook
import dev.pampa.mycloudgallery.core.faces.sync.FacesArchives
import dev.pampa.mycloudgallery.core.faces.sync.FacesAttestation
import dev.pampa.mycloudgallery.core.faces.sync.FacesSyncIdentity
import dev.pampa.mycloudgallery.core.faces.sync.FacesSyncWorld
import dev.pampa.mycloudgallery.core.faces.sync.FacesWire
import dev.pampa.mycloudgallery.core.faces.sync.InMemoryFacesPublishStore
import dev.pampa.mycloudgallery.core.faces.sync.NasFacesPublisher
import dev.pampa.mycloudgallery.core.faces.sync.NasFacesRemote
import dev.pampa.mycloudgallery.core.faces.sync.RecordingShare
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesRequirements
import dev.pampa.mycloudgallery.core.faces.testing.FakeSearchHistoryPurge
import dev.pampa.mycloudgallery.core.faces.testing.FakeSmartAlbumsPeople
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesArchive
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesLocalStore
import dev.pampa.mycloudgallery.core.model.FaceDetectorCode
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.personal.contract.HeadValue
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PrePublish
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import java.io.IOException
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/**
 * I dati dei volti di un dispositivo del mondo, "su disco": per generazione la chiave del Keystore e il database (qui un [InMemoryFacesArchive]).
 * [erase] toglie la chiave **per prima** e lo scrive in [log]; [failErase] fa fallire le prossime cancellazioni (un file bloccato).
 */
internal class WorldLocalData : FacesLocalData {
  class Gen(val archive: InMemoryFacesArchive) {
    var key = true
    var files = true
  }

  val gens = LinkedHashMap<FaceGeneration, Gen>()
  val log: MutableList<String> = mutableListOf()
  var failErase = 0
  var failPeople = 0

  /** L'archivio di [generation] (null se non c'e'); con [create] lo fa nascere con la sua chiave (solo il recinto lo chiede). */
  fun archive(generation: FaceGeneration, create: Boolean): FacesArchive? = synchronized(this) {
    gens[generation]?.takeIf { it.files && it.key }?.archive
      ?: if (create && gens[generation] == null) Gen(InMemoryFacesArchive(generation)).also { gens[generation] = it }.archive else null
  }

  fun has(generation: FaceGeneration): Boolean = synchronized(this) { gens[generation]?.let { it.files || it.key } == true }

  override suspend fun generationsOnDisk(): List<FaceGeneration> = synchronized(this) { gens.filterValues { it.files || it.key }.keys.toList() }

  override suspend fun hasData(generation: FaceGeneration): Boolean = has(generation)

  override suspend fun people(generation: FaceGeneration): Map<PeopleKey, PeopleRecord>? {
    if (failPeople > 0) {
      failPeople--
      throw IOException("guasto")
    }
    val gen = synchronized(this) { gens[generation]?.takeIf { it.files && it.key } } ?: return emptyMap()
    return gen.archive.people()
  }

  override suspend fun erase(generation: FaceGeneration): LocalErase = synchronized(this) {
    if (failErase > 0) {
      failErase--
      throw IOException("guasto")
    }
    val gen = gens[generation] ?: return LocalErase(keyDestroyed = true, filesGone = true)
    if (gen.key) {
      log += "key:${generation.base32}"
      gen.key = false
    }
    gen.archive.close()
    if (gen.files) {
      log += "files:${generation.base32}"
      gen.files = false
    }
    gens.remove(generation)
    LocalErase(keyDestroyed = true, filesGone = true)
  }
}

/** Il diario in memoria, "su disco"; [dieAt] fa morire il processo al salvataggio numero N, prima o dopo la scrittura. */
internal class WorldJournal : EraseJournalStore {
  var stored: EraseJournal? = null
  var saves = 0
  var dieAt = -1
  var dieAfter = false
  var failSaves = 0

  override fun load(): EraseJournalRead = stored?.let { EraseJournalRead.Present(it) } ?: EraseJournalRead.Absent

  override fun save(journal: EraseJournal) {
    if (failSaves > 0) {
      failSaves--
      throw IOException("disco pieno")
    }
    saves++
    if (saves == dieAt && !dieAfter) throw Death()
    stored = journal
    if (saves == dieAt && dieAfter) throw Death()
  }

  override fun clear(): Boolean {
    stored = null
    return true
  }
}

/**
 * Un dispositivo del mondo. Lo stato "su disco" ([localStore], [journal], [local], [personal], [publishStore], [history], [albums]) sopravvive a
 * [restart] (la morte del processo); interruttore, recinto, cancellazione e pubblicatore sono del processo.
 */
internal class EraseDevice(val world: EraseWorld, val name: String, val signer: Signer) {
  val id: String = TrustIds.deviceIdOf(signer.publicKey)

  val localStore = InMemoryFacesLocalStore()
  val journal = WorldJournal()
  val local = WorldLocalData()
  val personal = WorldPersonal(world, id)
  val publishStore = InMemoryFacesPublishStore()
  val history = FakeSearchHistoryPurge()
  val albums = FakeSmartAlbumsPeople(albums = 1)
  val requirements = FakeFacesRequirements(privateArea = PrivateAreaState.Declared("test"))

  @Volatile var online = true

  /** Il gancio risponde "fermo" alle prossime N pubblicazioni (la cancellazione locale che non riesce, come un file bloccato). */
  var holdNext = 0

  /** Se c'e', ogni porta di "Disattiva e cancella" passa dal piano dei guasti (dal prossimo [restart]). */
  var faults: PortFaults? = null

  inner class Identity : FacesEraseIdentity, FacesSyncIdentity {
    override val deviceId: String = id
    override val signer: Signer = this@EraseDevice.signer

    override fun registry() = world.family.state
  }

  val identity: Identity = Identity()

  lateinit var scope: CoroutineScope
  lateinit var switch: DefaultFacesSwitch
  lateinit var fence: FacesEraseFence
  lateinit var localErase: FacesLocalErase
  lateinit var eraser: DefaultFacesEraser
  lateinit var facts: FacesAccountFacts
  lateinit var ops: WorldEraseOps
  lateinit var janitor: FacesNasJanitor
  lateinit var publisher: NasFacesPublisher

  private var computing: FacesArchive? = null
  private var counter = 0

  init {
    restart()
  }

  /** Un processo nuovo: niente in memoria (freschezza compresa), tutto cio' che e' su disco resta. */
  fun restart() {
    if (::scope.isInitialized) shutdown()
    scope = CoroutineScope(Dispatchers.Unconfined + Job())
    // L'interruttore cancella i dati locali con la stessa operazione di "Disattiva e cancella" (come la lega :app, P13).
    val eraserPort = FacesLocalDataEraser { g, why -> localErase.erase(g, why) }
    switch = DefaultFacesSwitch(scope, personal, localStore, requirements, eraserPort, world.clock, newGeneration = { generation() })
    fence = FacesEraseFence(switch)
    localErase = FacesLocalErase(local, fence, history = history)
    facts = FacesAccountFacts(switch, personal, identity)
    ops = WorldEraseOps(world, this)
    val announcer = EraseAnnouncer { if (personalRound()) AnnounceResult.Completed else AnnounceResult.Waiting(EraseWait.NAS) }
    val f = faults
    eraser = DefaultFacesEraser(
      FacesEraseParts(
        journal = if (f != null) FaultyJournal(journal, f) else journal,
        switch = switch,
        settings = if (f != null) FaultySettings(personal, f) else personal,
        localStore = if (f != null) FaultyLocalStore(localStore, f) else localStore,
        requirements = requirements,
        local = if (f != null) FaultyLocalData(local, f) else local,
        fence = fence,
        localErase = if (f != null) FacesLocalErase(FaultyLocalData(local, f), fence, history = FaultyHistory(history, f)) else localErase,
        albums = if (f != null) FaultyAlbums(albums, f) else albums,
        history = if (f != null) FaultyHistory(history, f) else history,
        nas = if (f != null) FaultyOps(ops, f) else ops,
        announcer = if (f != null) FaultyAnnouncer(announcer, f) else announcer,
        heads = if (f != null) FaultyHeads(personal.headsView, f) else personal.headsView,
        identity = identity,
        clock = world.clock,
      ),
    )
    janitor = FacesNasJanitor(ops, facts, identity, personal.headsView, world.clock)
    val remote = NasFacesRemote(RecordingShare(world.share, id, world.written), id, { world.family.state.registry.config.commonShare }, Dispatchers.Unconfined)
    publisher = NasFacesPublisher(
      identity, switch, remote, fence.archives(FacesArchives { g -> local.archive(g, create = false) }), publishStore,
      FacesAttestation { it.toSet() }, FacesWriteGate { if (world.frozen) FacesWriteBlock.FROZEN else null }, world.clock, FeatureFlags.RELEASE,
    )
    computing = null
  }

  fun shutdown() {
    switch.stop()
    scope.cancel()
  }

  private fun generation(): FaceGeneration = FaceGeneration.fromBytes(java.security.MessageDigest.getInstance("SHA-256").digest("$name-${++counter}-${world.clock.monotonic}".toByteArray()).copyOf(16))

  /** "Attiva" sulla pagina di attivazione (il consenso lo costruisce la pagina; nelle prove si puo'). */
  suspend fun activate() = switch.activate(FacesActivationConsent(1, world.clock.monotonic))

  suspend fun acceptNotice() = switch.acceptNotice(1)

  /**
   * Un giro dei dati personali con le regole del motore vero: la scelta da datare prende l'ora del NAS, si leggono le teste degli altri
   * (ricordate), l'ultima scrittura vince, il gancio di D30 prima di pubblicare la propria testa, poi lo stato e' fresco. Falso senza NAS.
   */
  suspend fun personalRound(): Boolean {
    if (!online) return false
    val now = world.clock.nasNow!!
    personal.undated?.let { value ->
      // La scelta fatta qui e' piu' nuova di tutto cio' che questo dispositivo sapeva (lo store la data con l'ora del NAS, mai indietro).
      val at = maxOf(now, (personal.dated?.atNas ?: 0L) + 1)
      personal.merge(PersonalEntry(FacesSettingCodec.KEY, at, id, value))
      personal.undated = null
    }
    for ((device, head) in synchronized(world.heads) { world.heads.toMap() }) {
      if (device == id) continue
      synchronized(personal.view) { personal.view[device] = head }
      personal.merge(head.entry)
    }
    personal.emit()
    val state = personal.dated?.let { mapOf(FacesSettingCodec.KEY to it) }.orEmpty()
    val answer = if (holdNext > 0) {
      holdNext--
      PrePublish.Hold("FACES_ERASE_PENDING")
    } else {
      FacesPrePublishHook(switch).beforePublish(state)
    }
    if (answer == PrePublish.Proceed && (!personal.everPublished || personal.lastPublished != personal.dated)) {
      val head = HeadValue(personal.dated, now)
      synchronized(world.heads) { world.heads[id] = head }
      synchronized(personal.view) { personal.view[id] = head }
      personal.lastPublished = personal.dated
      personal.everPublished = true
    }
    switch.onPersonalRoundCompleted()
    return true
  }

  /** L'inizio del calcolo di un media: l'archivio si apre dal recinto (null se non si puo') e si tiene in mano. */
  suspend fun beginCompute(): Boolean {
    val state = switch.currentState() as? FacesState.On ?: return false
    computing = fence.open<FacesArchive>(state.generation) { local.archive(state.generation, create = true) }
    return computing != null
  }

  /** La fine del calcolo: il corpo salvato nell'archivio tenuto in mano (se nel frattempo e' stato chiuso, il calcolo si ferma) e in coda. */
  suspend fun endCompute(n: Int): Boolean {
    val archive = computing ?: return false
    computing = null
    val body = FacesWire.build(FacesSyncWorld.key(n), FaceSpaceCode.CURRENT, FaceDetectorCode.YUNET_2023MAR, 1, FaceSource.ANALYSIS, id, listOf(FacesSyncWorld.entry(0)))!!
    return try {
      archive.saveBody(body)
      publisher.stage(FacesChange.Faces(body.contentKey))
      true
    } catch (e: IllegalStateException) {
      false
    }
  }

  /** Un nome dato a una persona (P9): nell'archivio corrente e una ricerca recente con quel nome nella cronologia. */
  suspend fun name(person: Int, name: String): Boolean {
    val state = switch.currentState() as? FacesState.On ?: return false
    val archive = fence.open<FacesArchive>(state.generation) { local.archive(state.generation, create = true) } ?: return false
    archive.addPeople(listOf(PeopleRecord(PeopleEdit.Name(PersonId.fromBytes(ByteArray(16) { (person + it).toByte() }), name), world.clock.nasNow, id)))
    history.entries += "$name al mare"
    return true
  }

  suspend fun publish() = publisher.flush(FlushReason.UNIT_FINISHED)

  suspend fun resume() = eraser.resume(1.hours)

  override fun toString(): String = "EraseDevice($name)"
}
