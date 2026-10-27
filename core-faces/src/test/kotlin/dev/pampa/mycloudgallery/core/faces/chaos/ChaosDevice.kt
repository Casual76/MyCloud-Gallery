package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.faces.compute.ComputeStore
import dev.pampa.mycloudgallery.core.faces.compute.ComputeStoreOpen
import dev.pampa.mycloudgallery.core.faces.compute.FacesAreas
import dev.pampa.mycloudgallery.core.faces.compute.FacesComputeEngine
import dev.pampa.mycloudgallery.core.faces.compute.FacesComputeParts
import dev.pampa.mycloudgallery.core.faces.compute.FacesRefresh
import dev.pampa.mycloudgallery.core.faces.compute.FacesStoreSource
import dev.pampa.mycloudgallery.core.faces.compute.SimDevice
import dev.pampa.mycloudgallery.core.faces.compute.Sha256FaceBodyDigest
import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.FacesActivationConsent
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteGate
import dev.pampa.mycloudgallery.core.faces.contract.GoneContents
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.IndexerState
import dev.pampa.mycloudgallery.core.faces.contract.PrivateAreaState
import dev.pampa.mycloudgallery.core.faces.erase.AnnounceResult
import dev.pampa.mycloudgallery.core.faces.erase.DefaultFacesEraser
import dev.pampa.mycloudgallery.core.faces.erase.EraseAnnouncer
import dev.pampa.mycloudgallery.core.faces.erase.FacesAccountFacts
import dev.pampa.mycloudgallery.core.faces.erase.FacesEraseFence
import dev.pampa.mycloudgallery.core.faces.erase.FacesEraseIdentity
import dev.pampa.mycloudgallery.core.faces.erase.FacesEraseParts
import dev.pampa.mycloudgallery.core.faces.erase.FacesLocalErase
import dev.pampa.mycloudgallery.core.faces.erase.FacesNasJanitor
import dev.pampa.mycloudgallery.core.faces.erase.Death
import dev.pampa.mycloudgallery.core.faces.erase.WorldJournal
import dev.pampa.mycloudgallery.core.faces.grouping.CustodianGrouping
import dev.pampa.mycloudgallery.core.faces.store.FacesUnavailable
import dev.pampa.mycloudgallery.core.faces.store.FacesUnitsDone
import dev.pampa.mycloudgallery.core.faces.switch.DefaultFacesSwitch
import dev.pampa.mycloudgallery.core.faces.switch.FacesLocalDataEraser
import dev.pampa.mycloudgallery.core.faces.sync.FacesArchives
import dev.pampa.mycloudgallery.core.faces.sync.FacesAttestation
import dev.pampa.mycloudgallery.core.faces.sync.FacesSyncIdentity
import dev.pampa.mycloudgallery.core.faces.sync.NasFacesPublisher
import dev.pampa.mycloudgallery.core.faces.sync.NasFacesSync
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesRequirements
import dev.pampa.mycloudgallery.core.faces.testing.FakeSearchHistoryPurge
import dev.pampa.mycloudgallery.core.faces.testing.FakeSmartAlbumsPeople
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesLocalStore
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/**
 * UN DISPOSITIVO DEL BANCO: tutti i pezzi veri del calcolo, della pubblicazione, della replica, del raggruppamento e di "Disattiva e cancella" di Fase 8
 * legati insieme come li lega `:app` (P13): l'interruttore vero (P7), il recinto e la cancellazione vera (P8), il pubblicatore e la replica veri (P3), le
 * prenotazioni, il calcolo vero (P4) e il raggruppamento con il custode (P5), sopra i salvataggi con la morte del processo ([ChaosWraps]).
 *
 * Lo stato "su disco" ([localStore], [journal], [local], [personal], [history], [albums]) sopravvive a [restart] (la morte del processo); interruttore,
 * recinto, cancellazione, pubblicatore, replica, prenotazioni, calcolo e raggruppamento sono del processo e si ricostruiscono.
 */
internal class ChaosDevice(val world: ChaosWorld, val index: Int, val name: String, val signer: Signer) {
  val id: String = TrustIds.deviceIdOf(signer.publicKey)
  val inj: ChaosInjector get() = world.inj

  // --- Su disco -----------------------------------------------------------------------------------------------------------------
  val localStore = InMemoryFacesLocalStore()
  val journal = WorldJournal()
  val local = ChaosLocalData(world.inj, index, filesFirst = world.mutant == Mutant.FILES_BEFORE_KEY, onWipe = { g -> world.wiped(this, g) }) { g, body -> world.bodySaved(this, g, body) }
  val personal = ChaosPersonal(world.inj, index, id, world.heads, world.clock, skipHook = world.mutant == Mutant.ANNOUNCE_BEFORE_ERASING)
  val history = FakeSearchHistoryPurge()
  val albums = FakeSmartAlbumsPeople(albums = 1)
  val requirements = FakeFacesRequirements(
    privateArea = PrivateAreaState.Declared(world.nas.privateShare),
    indexers = IndexerState(accountIndexers = 1, thisDevice = true, canBecomeIndexer = true),
  )

  /** I media di questo dispositivo: il catalogo, le anteprime, il telefono e il buffer (i pezzi di P4). */
  val sim = SimDevice(world.media.nas, id, world.media.account, indexer = true, here = HereState.ACTIVE)
  private val engines = ChaosEngines(sim.fake, world.clock)

  @Volatile var online = true

  /** Il processo si sta chiudendo (la morte o un riavvio): l'interruttore vecchio, fermandosi, emette "spento", che non e' uno stato di Persone. */
  @Volatile var stopping = false

  // --- Del processo -------------------------------------------------------------------------------------------------------------
  lateinit var scope: CoroutineScope
    private set
  lateinit var switch: DefaultFacesSwitch
    private set
  lateinit var fence: FacesEraseFence
    private set
  lateinit var localErase: FacesLocalErase
    private set
  lateinit var eraser: DefaultFacesEraser
    private set
  lateinit var facts: FacesAccountFacts
    private set
  lateinit var janitor: FacesNasJanitor
    private set
  lateinit var publisher: NasFacesPublisher
    private set
  lateinit var replica: NasFacesSync
    private set
  lateinit var kit: ChaosKit
    private set
  lateinit var leases: ChaosLeases
    private set
  lateinit var grouping: CustodianGrouping
    private set
  lateinit var compute: FacesComputeEngine
    private set

  private var counter = 0

  inner class Identity : FacesEraseIdentity, FacesSyncIdentity {
    override val deviceId: String = id
    override val signer: Signer = this@ChaosDevice.signer

    override fun registry() = world.family.state
  }

  val identity: Identity = Identity()

  /** L'interruttore come lo vedono chi pubblica, replica e prenota (con l'errore `PUBLISH_WITHOUT_FRESH_STATE` non guarda la freschezza). */
  val publishSwitch: FacesSwitch get() = if (world.mutant == Mutant.PUBLISH_WITHOUT_FRESH_STATE) AlwaysFreshSwitch(switch) else switch

  private val gate = FacesWriteGate { if (world.nas.frozen) FacesWriteBlock.FROZEN else null }

  init {
    restart()
  }

  /** Un processo nuovo: niente in memoria (freschezza compresa), tutto cio' che e' su disco resta. */
  fun restart() {
    if (::scope.isInitialized) shutdown()
    inj.revive(index)
    stopping = false
    // La morte del processo dentro una coroutine dell'interruttore non e' un crollo del banco: il dispositivo e' segnato morto e il banco lo riavvia. Ogni altra eccezione
    // uscita da una coroutine (in :app chiuderebbe l'app) non si rilancia, perche' il gestore globale la darebbe al test dopo e il banco non la vedrebbe: si raccoglie e la
    // giudica il verificatore (invariante 10, O3).
    scope = CoroutineScope(Dispatchers.Unconfined + Job() + CoroutineExceptionHandler { _, e -> if (e !is Death) world.escapedFromScope(name, e) })
    val store = ChaosLocalStore(localStore, inj, index)
    // L'interruttore cancella i dati locali con la stessa operazione di "Disattiva e cancella" (come la lega :app, P13).
    val eraserPort = FacesLocalDataEraser { g, why -> localErase.erase(g, why) }
    switch = DefaultFacesSwitch(scope, personal, store, requirements, eraserPort, world.clock, newGeneration = { nextGeneration() })
    fence = FacesEraseFence(switch)
    localErase = FacesLocalErase(local, fence, history = history)
    facts = FacesAccountFacts(switch, personal, identity)
    world.nas.sever(index)
    kit = world.nas.kit(this)
    val ops = world.nas.eraseOps(this, facts)
    val announcer = EraseAnnouncer { if (personalRound()) AnnounceResult.Completed else AnnounceResult.Waiting(EraseWait.NAS) }
    eraser = DefaultFacesEraser(
      FacesEraseParts(
        journal = ChaosJournal(journal, inj, index),
        switch = switch,
        settings = personal,
        localStore = store,
        requirements = requirements,
        local = local,
        fence = fence,
        localErase = localErase,
        albums = albums,
        history = history,
        nas = ops,
        announcer = announcer,
        heads = personal.headsView,
        identity = identity,
        clock = world.clock,
      ),
    )
    janitor = FacesNasJanitor(ops, facts, identity, personal.headsView, world.clock)
    leases = ChaosLeases(kit.leases, world, this)
    val archives = fence.archives(FacesArchives { g -> local.archive(g, create = true) })
    val publishStore = ChaosPublishStore(local.publishStore, inj, index)
    publisher = NasFacesPublisher(identity, publishSwitch, kit.remote, archives, publishStore, FacesAttestation { it.toSet() }, gate, world.clock, FeatureFlags.RELEASE)
    replica = NasFacesSync(
      identity, publishSwitch, kit.remote, archives, ChaosReplicaStore(local.replicaStore, inj, index), gate, world.clock, publisher, FeatureFlags.RELEASE,
    )
    grouping = CustodianGrouping(
      archives = {
        switch.currentState().generationOrNull?.let { g -> fence.open<FacesArchive>(g) { local.archive(g, create = true) } }
      },
      deviceId = id,
      leases = leases,
      snapshots = replica,
      publisher = publisher,
      gone = GoneContents { emptySet() },
      ids = world.personIds,
    )
    compute = FacesComputeEngine(parts(), pause = { world.clock.advance(it.inWholeMilliseconds) })
    world.watch(this)
  }

  fun shutdown() {
    stopping = true
    switch.stop()
    scope.cancel()
  }

  private fun parts(): FacesComputeParts = sim.parts().copy(
    switch = switch,
    localStore = ChaosLocalStore(localStore, inj, index),
    requirements = requirements,
    areas = FacesAreas { world.nas.commonShare },
    engines = { engines.engines },
    modelsReady = { true },
    stores = FacesStoreSource { g ->
      val archive = fence.open<FacesArchive>(g) { local.archive(g, create = true) }
      val units = fence.open<FacesUnitsDone>(g) { local.units(g, create = true) }
      if (archive == null || units == null) ComputeStoreOpen.Unavailable(FacesUnavailable.STORAGE) else ComputeStoreOpen.Ready(ComputeStore(archive, units), null)
    },
    publisher = if (world.tracing) ChaosTracingPublisher(publisher, this) else publisher,
    leases = leases,
    grouping = grouping,
    clock = world.clock,
    deviceId = id,
    digest = Sha256FaceBodyDigest,
    refresh = FacesRefresh { replica.sync(1.minutes) },
  )

  private fun nextGeneration(): FaceGeneration =
    FaceGeneration.fromBytes(MessageDigest.getInstance("SHA-256").digest("$name-${++counter}-${world.clock.monotonicMs}-${world.seed}".toByteArray()).copyOf(16))

  /** La generazione che questo dispositivo tratta adesso (null = Persone spenta qui). */
  fun generation(): FaceGeneration? = switch.currentState().generationOrNull

  /** Vero se Persone e' accesa qui e non si sta cancellando (puo' calcolare, pubblicare, replicare). */
  fun isActive(): Boolean = switch.currentState().let { it is FacesState.On && it.here == HereState.ACTIVE }

  /** "Attiva" sulla pagina di attivazione (il consenso lo costruisce la pagina; nelle prove si puo'). */
  suspend fun activate() = switch.activate(FacesActivationConsent(1, world.clock.monotonicNanos()))

  suspend fun acceptNotice() = switch.acceptNotice(1)

  /** Un giro dei dati personali: la lettura e la pubblicazione della testa sono chiamate al NAS (con i colpi); falso se non e' arrivato in fondo. */
  suspend fun personalRound(): Boolean = personal.round(online, switch) { switch.onPersonalRoundCompleted() }

  /** Una tappa del calcolo su tutti gli ambiti di questo dispositivo (privata, comune, solo telefono). */
  suspend fun computeAll(budgetMinutes: Long = 30) = compute.runAll(budgetMinutes.minutes).also { world.media.nas.offline = false }

  /** La ripresa del calcolo parte dall'ora della cache del NAS finto: il dispositivo spento non la raggiunge. */
  fun prepareCompute() {
    world.media.nas.offline = !online
  }

  /** Un nome dato a una persona (P9): nell'archivio corrente e una ricerca recente con quel nome nella cronologia. */
  suspend fun name(name: String): Boolean {
    val g = generation() ?: return false
    if (!isActive()) return false
    val archive = fence.open<FacesArchive>(g) { local.archive(g, create = true) } ?: return false
    val personId = world.personIds.next()
    val edit = dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit.Name(personId, name)
    try {
      archive.addPeople(listOf(dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord(edit, world.clock.nasNowMillis(), id)))
    } catch (e: ChaosDbFault) {
      return false
    }
    history.entries += "$name al mare"
    try {
      publisher.stage(dev.pampa.mycloudgallery.core.faces.contract.FacesChange.People(listOf(edit)))
    } catch (e: ChaosDbFault) {
      // Come `PeopleEditor`: la voce e' nell'archivio e vale gia', una ripresa ripubblica lo stato intero del proprio contributo.
    }
    world.named(g, name, this)
    return true
  }

  override fun toString(): String = "ChaosDevice($name)"
}
