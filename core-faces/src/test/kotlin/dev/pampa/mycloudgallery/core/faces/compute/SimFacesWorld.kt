package dev.pampa.mycloudgallery.core.faces.compute

import dev.pampa.mycloudgallery.core.ai.testing.FakeFaceEngines
import dev.pampa.mycloudgallery.core.faces.contract.AreaKind
import dev.pampa.mycloudgallery.core.faces.contract.FaceInput
import dev.pampa.mycloudgallery.core.faces.contract.FaceInputs
import dev.pampa.mycloudgallery.core.faces.contract.FacesArea
import dev.pampa.mycloudgallery.core.faces.contract.FacesCatalog
import dev.pampa.mycloudgallery.core.faces.contract.FacesComputeLimits
import dev.pampa.mycloudgallery.core.faces.contract.FacesItem
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocal
import dev.pampa.mycloudgallery.core.faces.contract.FacesScope
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.IndexerState
import dev.pampa.mycloudgallery.core.faces.contract.InputWait
import dev.pampa.mycloudgallery.core.faces.contract.PrivateAreaState
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesRequirements
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesSwitch
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesArchive
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesLocalStore
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlin.time.Duration

/** Il catalogo di un dispositivo sul NAS finto: la sua area privata, l'area comune e i suoi media del solo telefono. Mai l'area di un altro account. */
class LiveCatalog(private val device: SimDevice) : FacesCatalog {
  /** Quante volte il calcolo ha letto il catalogo (per "un ambito non suo non apre niente"). */
  var calls = 0
    private set

  private fun media(area: FacesArea): Collection<SimMedia> = when (area) {
    FacesArea.PRIVATE -> device.nas.mediaIn(device.account.privateShare).values
    FacesArea.COMMON -> device.nas.mediaIn(device.nas.commonShare).values
    FacesArea.PHONE_ONLY -> device.phone.values
  }

  override suspend fun months(area: FacesArea): List<String> {
    calls++
    return media(area).map { it.month }.distinct().sortedDescending()
  }

  override suspend fun items(area: FacesArea, month: String): List<FacesItem> {
    calls++
    return media(area).filter { it.month == month }.map { it.item(area) }.sortedBy { it.contentKey.hex }
  }

  override suspend fun cellKeys(media: Collection<MediaId>): Map<MediaId, Set<ContentKey>> = emptyMap()

  override suspend fun mediaWith(keys: Collection<ContentKey>): Map<ContentKey, Set<MediaId>> = emptyMap()
}

/**
 * Gli ingressi di un dispositivo: il buffer (anteprime preparate o scaricate), l'indice della share (sul NAS finto, una chiamata che puo' cadere),
 * il telefono. Si possono scrivere a mano gli esiti ([scripted], [hqScripted]) e si contano le chiamate.
 */
class SimInputs(private val device: SimDevice) : FaceInputs, FacesPrefetching {
  val scripted = HashMap<ContentKey, FaceInput>()
  val hqScripted = HashMap<ContentKey, FaceInput>()
  val previewed = ArrayList<ContentKey>()
  var previewCalls = 0
    private set
  var hqCalls = 0
    private set
  var frameCalls = 0
    private set
  var sharpestCalls = 0
    private set
  var prefetchCalls = 0
    private set
  var prefetchedItems = 0
    private set

  /** Quanti fotogrammi in piu' del dovuto restituisce la porta di un video (il calcolo ne prende al piu' 4). */
  var extraFrames = 0
  var prefetching = false

  private fun mediaOf(item: FacesItem): SimMedia? = device.phone[item.contentKey] ?: device.nas.find(item.contentKey)

  /** Vero se l'anteprima e' qui o nell'indice; la lettura dall'indice conta come una chiamata al NAS (puo' cadere). */
  private fun reachable(item: FacesItem, media: SimMedia): Boolean {
    if (item.area == FacesArea.PHONE_ONLY || item.contentKey in device.buffer) return true
    if (!media.previewOnNas) return false
    device.nas.call { device.nas.previewBytesRead += 150_000L }
    return true
  }

  override suspend fun preview(item: FacesItem): FaceInput {
    previewCalls++
    previewed += item.contentKey
    scripted[item.contentKey]?.let { return it }
    val media = mediaOf(item) ?: return FaceInput.Unsupported
    if (!reachable(item, media)) return FaceInput.Waiting(InputWait.NO_PREVIEW)
    return ready(media, 1024, FaceSource.ANALYSIS, 0)
  }

  override suspend fun hq(item: FacesItem): FaceInput {
    hqCalls++
    hqScripted[item.contentKey]?.let { return it }
    val media = mediaOf(item) ?: return FaceInput.Unsupported
    if (!media.hq) return FaceInput.Waiting(InputWait.NO_PREVIEW)
    if (item.area != FacesArea.PHONE_ONLY) device.nas.call { device.nas.previewBytesRead += 400_000L }
    return ready(media, 2048, if (item.area == FacesArea.PHONE_ONLY) FaceSource.PHONE_ORIGINAL else FaceSource.HQ_TILES, 0)
  }

  override suspend fun frames(item: FacesItem): List<FaceInput> {
    frameCalls++
    val media = mediaOf(item) ?: return emptyList()
    if (!reachable(item, media)) return emptyList()
    val used = (media.scene.faces.map { it.frame } + 0).distinct().sorted()
    val count = minOf(8 + 1, used.size + extraFrames)
    return (0 until count).map { f -> ready(media, 1024, FaceSource.FRAMES, if (f < used.size) used[f] else f) }
  }

  override suspend fun sharpest(item: FacesItem): FaceInput {
    sharpestCalls++
    val media = mediaOf(item) ?: return FaceInput.Unsupported
    return ready(media, 1024, FaceSource.ANALYSIS, 0)
  }

  override suspend fun prefetch(items: List<FacesItem>) {
    prefetchCalls++
    prefetchedItems += items.size
    if (!prefetching) return
    device.nas.call { Unit }
    items.forEach { device.buffer += it.contentKey }
  }

  private fun ready(media: SimMedia, longSide: Int, source: FaceSource, frame: Int): FaceInput =
    FaceInput.Ready(device.fake.image(media.scene, frame = frame, longSide = longSide), source)
}

/**
 * Un dispositivo dell'account sul NAS finto. Cio' che sopravvive alla morte del processo (archivio, unita' finite, outbox, buffer, telefono) e
 * i pezzi di un processo ([engine]) ricreati da [restart].
 */
class SimDevice(
  val nas: SimFacesNas,
  val id: String,
  val account: SimAccount,
  indexer: Boolean = true,
  val here: HereState = HereState.ACTIVE,
) {
  val generation = account.generation
  val clock = SimClock(nas)
  val fake = FakeFaceEngines(nas.identities, previewMinPx = 12, hqMinPx = 6)
  val ticking = TickingEngines(fake, clock)
  val rawArchive = InMemoryFacesArchive(generation)
  val archive = CrashingArchive(rawArchive)
  val units = MemoryUnits()
  val switch = FakeFacesSwitch(initial = FacesState.On(generation, here))
  val localStore = InMemoryFacesLocalStore(FacesLocal(dataGeneration = generation))
  val requirements = FakeFacesRequirements(
    privateArea = PrivateAreaState.Declared(account.privateShare),
    indexers = IndexerState(accountIndexers = 1, thisDevice = indexer, canBecomeIndexer = true),
    localOnly = here == HereState.LOCAL_ONLY,
  )
  val phone = LinkedHashMap<ContentKey, SimMedia>()
  val buffer = HashSet<ContentKey>()
  val catalog = LiveCatalog(this)
  val inputs = SimInputs(this)
  val leases = SimLeases(nas, id, generation)
  val publisher = SimPublisher(nas, id, generation) { archive }
  val grouping = SpyGrouping()
  val control = SimControl()
  val signals = ArrayList<Pair<String, Int>>()
  val pauses = ArrayList<Duration>()

  var modelsReady = true
  var enginesAvailable = true
  var freeBytes = Long.MAX_VALUE
  var opens = 0
    private set

  lateinit var engine: FacesComputeEngine
    private set

  fun parts(): FacesComputeParts = FacesComputeParts(
    switch = switch,
    localStore = localStore,
    requirements = requirements,
    areas = FacesAreas { nas.commonShare },
    catalog = catalog,
    inputs = inputs,
    engines = { if (enginesAvailable) ticking.engines else null },
    modelsReady = { modelsReady },
    stores = FacesStoreSource {
      opens++
      ComputeStoreOpen.Ready(ComputeStore(archive, units), null)
    },
    publisher = publisher,
    leases = leases,
    grouping = grouping,
    control = control,
    clock = clock,
    deviceId = id,
    digest = Sha256FaceBodyDigest,
    freeSpace = FacesFreeSpace { freeBytes },
    signals = FacesSignals { code, count -> signals += code to count },
    refresh = FacesRefresh { replicate() },
  )

  /** Un processo nuovo (dopo una morte): stessi dati su disco, motore ricreato. */
  fun restart(config: FacesComputeConfig = FacesComputeConfig.DEFAULT, parts: FacesComputeParts = parts()) {
    engine = FacesComputeEngine(parts, config, pause = { pauses += it; clock.advanceMillis(it.inWholeMilliseconds) })
  }

  init {
    restart()
  }

  /** La replica dei dati dei volti (P3): i corpi pubblicati da **un altro dispositivo dell'account** nella **stessa generazione**, nel proprio archivio. */
  suspend fun replicate() {
    val devices = nas.published[generation].orEmpty()
    for ((device, bodies) in devices) {
      if (device == id) continue
      bodies.values.forEach { rawArchive.saveBody(it) }
    }
  }

  /** Tutto cio' che le porte hanno visto chiamare: se un ambito non suo non cambia questo numero, non ha toccato niente. */
  fun activity(): Int = leases.calls + publisher.stageCalls + publisher.flushCalls + inputs.previewCalls + inputs.hqCalls + inputs.frameCalls +
    inputs.sharpestCalls + inputs.prefetchCalls + ticking.detects + opens + catalog.calls + grouping.incrementals + nas.faults.calls

  /** L'ambito di una tappa sull'area [kind] di questo dispositivo (la sua area privata, o l'area comune). */
  fun area(kind: AreaKind): FacesScope.Area = FacesScope.Area(if (kind == AreaKind.PRIVATE) account.privateShare else nas.commonShare, kind)

  /** Tutte le chiavi che questo dispositivo vede (area privata, comune, telefono). */
  fun allKeys(): List<ContentKey> = (nas.mediaIn(account.privateShare).keys + nas.mediaIn(nas.commonShare).keys + phone.keys).toList()

  /** Le chiavi con un corpo `faces` della ricetta corrente (o "non supportate") nell'archivio di questo dispositivo. */
  suspend fun analyzed(): Set<ContentKey> = rawArchive.analyzed(allKeys(), FacesComputeLimits.RECIPE)
}

/** La famiglia dei test: un NAS con due account (Anna e Bruno) e i loro dispositivi. */
class SimWorld {
  val nas = SimFacesNas()
  val anna = nas.account("anna")
  val bruno = nas.account("bruno")
  val devices = ArrayList<SimDevice>()

  fun device(account: SimAccount, id: String, indexer: Boolean = true, here: HereState = HereState.ACTIVE): SimDevice =
    SimDevice(nas, id, account, indexer, here).also { devices += it }

  /** I corpi pubblicati dal dispositivo [device] nella generazione di [account]. */
  fun published(account: SimAccount, device: String): Set<ContentKey> = nas.publishedBy(account.generation, device).keys

  companion object {
    val FOREVER: Duration = Duration.INFINITE
  }
}
