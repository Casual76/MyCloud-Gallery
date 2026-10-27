package dev.pampa.mycloudgallery.core.albums.shared

import dev.pampa.mycloudgallery.core.albums.contract.AlbumSyncReport
import dev.pampa.mycloudgallery.core.albums.contract.FlushReason
import dev.pampa.mycloudgallery.core.albums.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.albums.contract.PublishRules
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumFamily
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumWriteGate
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.data.AlbumEntry
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.Dispatchers

/**
 * Un dispositivo vero della prova (P2): pubblicatore, replica, proiezione e album condivisi veri, sopra il NAS finto e la famiglia vera. Gli
 * store sono "il database": [restart] ricrea tutto il resto sopra gli stessi store (la morte del processo). [library] e' il suo catalogo
 * (attestazione e visibilita'); [fs] avvolge l'area comune (guasti del banco).
 */
class AlbumDevice(
  val world: AlbumWorld,
  @Volatile var signer: Signer,
  val nasUser: String,
  val library: FakeAlbumLibrary = FakeAlbumLibrary(),
  private val fs: (NasFileSystem) -> NasFileSystem = { it },
  /** R2: quali errori di I/O sono di un file (come lo lega :app con gli errori del NAS). */
  private val ioErrors: AlbumIoErrors = AlbumIoErrors.SESSION,
  /** I tetti del giro della replica (R2, R13). */
  private val limits: AlbumSyncLimits = AlbumSyncLimits(),
) {
  val account: AccountId get() = AccountId.of(nasUser)
  val deviceId: String get() = TrustIds.deviceIdOf(signer.publicKey)

  val identity: AlbumIdentity = object : AlbumIdentity {
    override val deviceId: String get() = this@AlbumDevice.deviceId
    override val signer: Signer get() = this@AlbumDevice.signer

    override fun registry(): RegistryState = world.family.state
  }

  val gate = FakeAlbumWriteGate()
  val albumFamily = FakeAlbumFamily(account = AccountId.of(nasUser), common = world.nas.shareName)
  var replicaStore = MemoryAlbumReplicaStore()
  var publishStore = MemoryAlbumPublishStore()

  /** R1: i segni locali (visto attivo, chiusura vista, copia scritta): "il disco", come gli store. */
  var marks: SharedAlbumMarks = MemorySharedAlbumMarks()
  val choices = MemorySharedAlbumChoices()

  lateinit var replica: SharedAlbumReplica
  lateinit var publisher: NasAlbumPublisher
  lateinit var sync: NasSharedAlbumSync
  lateinit var shared: ReplicaSharedAlbums

  /** I rapporti di tutti i giri (per i conteggi della Diagnostica). */
  val reports: MutableList<AlbumSyncReport> = mutableListOf()

  init {
    restart()
  }

  /** Un processo nuovo sopra gli stessi store (morte del processo). */
  fun restart() {
    val share = { _: String -> fs(world.nas) }
    replica = SharedAlbumReplica(identity, replicaStore, library, marks = marks)
    publisher = NasAlbumPublisher(
      identity, albumFamily, share, publishStore, gate, world.clocks,
      levels = { id -> replica.view(id)?.merged?.levels ?: replica.orphans.value[id]?.levels ?: LevelHistory.NONE },
      io = Dispatchers.Unconfined,
      // Come :app (AlbumsLive): R12, la copia del mio contributo nella replica.
      ownReplica = { id -> replicaStore.contribution(id, deviceId) },
    )
    replica.local = publisher
    sync = NasSharedAlbumSync(
      identity, share, replicaStore, replica, world.clocks, { true }, limits, ownAlbums = { publishStore.albums() }, io = Dispatchers.Unconfined,
      ioErrors = ioErrors, ownSegmentMissing = { id -> publisher.repairSegments(id) },
    )
    shared = ReplicaSharedAlbums(identity, albumFamily, replica, publisher, gate, world.clocks, library, choices)
  }

  /** Un dispositivo nuovo dello stesso account al posto di questo (stessi cataloghi, store nuovi): per diventare admin, per esempio. */
  fun replaceWith(newSigner: Signer) {
    signer = newSigner
    replicaStore = MemoryAlbumReplicaStore()
    publishStore = MemoryAlbumPublishStore()
    marks = MemorySharedAlbumMarks()
    restart()
  }

  /** Pubblica cio' che ha in coda (il lavoro della sincronizzazione), un album alla volta. */
  suspend fun flushAll(): List<PublishOutcome> = publishStore.albums().map { publisher.flush(it, FlushReason.SYNC_ROUND) }

  suspend fun syncNow(): AlbumSyncReport = sync.sync(1.hours).also { reports += it }

  fun view(album: AlbumId): SharedAlbumView? = replica.view(album)

  /** Un album condiviso nuovo, creato da qui (definizione e livello, come il passo 3 del §2.5). */
  suspend fun createAlbum(title: String, level: ShareLevel, nonce: ByteArray = world.nextNonce()): AlbumId {
    val album = AlbumId.derived(account, nonce)
    val definition = SharedAlbumChange.Definition(
      title, SharePath.of("Album condivisi", title), SharedState.ACTIVE, account, nonce.joinToString("") { "%02x".format(it) }, null,
    )
    publisher.stage(album, listOf(definition, SharedAlbumChange.Level(level)))
    replica.recompute(listOf(album))
    return album
  }

  override fun toString(): String = "AlbumDevice($nasUser)"
}

/** La famiglia della prova: il NAS (area comune `test-comune`), l'ora del NAS comune, il registro firmato, i dispositivi. */
class AlbumWorld {
  val clocks = FakeAlbumClocks(nas = 1_790_000_000_000L)
  val nas = MemoryNasFileSystem("test-comune")
  val family = AlbumTestFamily(clocks, nas)
  val devices: MutableList<AlbumDevice> = mutableListOf()
  private var nonces = 0

  fun nextNonce(): ByteArray = ByteArray(16) { (it * 13 + ++nonces).toByte() }

  /** Un dispositivo approvato dell'account [nasUser]. */
  fun device(
    nasUser: String,
    admin: Boolean = false,
    library: FakeAlbumLibrary = FakeAlbumLibrary(),
    fs: (NasFileSystem) -> NasFileSystem = { it },
    ioErrors: AlbumIoErrors = AlbumIoErrors.SESSION,
    limits: AlbumSyncLimits = AlbumSyncLimits(),
  ): AlbumDevice = AlbumDevice(this, family.approve("Telefono di $nasUser", nasUser, admin), nasUser, library, fs, ioErrors, limits).also { devices += it }

  /** Fa passare il tempo di una testa (5 minuti) e fa pubblicare e leggere tutti, [rounds] volte. */
  suspend fun settle(rounds: Int = 2) {
    repeat(rounds) {
      clocks.advance(PublishRules.MIN_HEAD_INTERVAL_MS + PublishRules.DEBOUNCE_MS)
      devices.forEach { it.flushAll() }
      devices.forEach { it.syncNow() }
    }
  }
}

/**
 * Chi scrive **senza** i controlli di chi scrive (un'app difettosa o modificata su un altro dispositivo dell'account [owner]): un
 * pubblicatore vero (che non controlla i permessi) di un dispositivo suo. Le voci arrivano sul NAS cosi' come sono; decide chi legge.
 */
class RawWriter(private val world: AlbumWorld, val owner: AccountId, private val reader: () -> AlbumDevice) {
  private val device = world.device(owner.value)

  init {
    world.devices.remove(device)
  }

  /** Scrive [changes] in [album], pubblica, fa leggere tutti; restituisce le voci scritte (con la loro ora). */
  suspend fun write(album: AlbumId, changes: List<SharedAlbumChange>): List<AlbumEntry> {
    val levelsOf = { id: AlbumId -> reader().view(id)?.merged?.levels ?: reader().replica.orphans.value[id]?.levels ?: LevelHistory.NONE }
    val publisher = NasAlbumPublisher(device.identity, device.albumFamily, { world.nas }, device.publishStore, device.gate, world.clocks, levels = levelsOf, io = Dispatchers.Unconfined)
    // Un cambio di livello si scrive sempre (anche uguale a quello di adesso): la storia letta con il cambio in coda.
    val raw = changes.map { change ->
      if (change !is SharedAlbumChange.Level) return@map change
      val base = levelsOf(album).changes.map { dev.pampa.mycloudgallery.core.albums.contract.LevelStep(it.at, it.level) }
      val at = maxOf(world.clocks.nas!!, (base.lastOrNull()?.atNas ?: 0L) + 1)
      SharedAlbumChange.LevelHistory(base + dev.pampa.mycloudgallery.core.albums.contract.LevelStep(at, change.level))
    }
    publisher.stage(album, raw)
    val keys = device.publishStore.load(album)!!.staged.keys
    world.clocks.advance(PublishRules.MIN_HEAD_INTERVAL_MS + PublishRules.DEBOUNCE_MS)
    if (keys.isNotEmpty()) check(publisher.flush(album, FlushReason.SYNC_ROUND) is PublishOutcome.Published) { "voce grezza non pubblicata" }
    val published = device.publishStore.load(album)!!.published
    world.settle(1)
    return keys.mapNotNull { published[it]?.entry }
  }
}

/**
 * Teste e segmenti scritti **a mano** sul NAS con la chiave di un dispositivo (vero, revocato, dismesso o sconosciuto): i contributi che un
 * dispositivo compromesso o un'app difettosa potrebbe pubblicare, senza i controlli del pubblicatore.
 */
object RawHeads {
  /** Pubblica i segmenti di [entries] e la testa [version] di [signer] per [album], creata all'ora [createdAt]. Restituisce il segno. */
  fun write(
    world: AlbumWorld,
    signer: dev.pampa.mycloudgallery.core.trust.crypto.Signer,
    album: AlbumId,
    entries: List<AlbumEntry>,
    version: Long,
    createdAt: Long = world.clocks.nas!!,
    segments: List<dev.pampa.mycloudgallery.core.trust.data.EncodedSegment>? = null,
  ): dev.pampa.mycloudgallery.core.trust.data.HeadMark {
    val device = TrustIds.deviceIdOf(signer.publicKey)
    val text = dev.pampa.mycloudgallery.core.trust.data.AlbumIds.text(album)
    val encoded = segments ?: AlbumSegmentPlan.plan(album, entries)
    for (s in encoded) world.nas.publishExclusive(dev.pampa.mycloudgallery.core.trust.nas.NasLayout.albumSegment(text, device, s.ref.hash), s.bytes)
    val head = dev.pampa.mycloudgallery.core.trust.data.AlbumHead(
      world.family.familyId, album, device, version, world.family.state.registry.version, createdAt, encoded.map { it.ref }.sortedBy { it.hash },
    )
    val signed = dev.pampa.mycloudgallery.core.trust.data.AlbumDocs.encodeHead(head, signer)
    world.nas.publishExclusive(dev.pampa.mycloudgallery.core.trust.nas.NasLayout.albumHead(text, device, version), signed.bytes)
    return signed.mark
  }
}
