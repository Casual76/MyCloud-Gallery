package dev.pampa.mycloudgallery.faces

import android.content.Context
import androidx.work.WorkManager
import dev.pampa.mycloudgallery.ai.AiStack
import dev.pampa.mycloudgallery.ai.AiWiring
import dev.pampa.mycloudgallery.ai.CheckedPreparer
import dev.pampa.mycloudgallery.ai.CutoutSources
import dev.pampa.mycloudgallery.ai.ReplicaHq
import dev.pampa.mycloudgallery.ai.EnsuringModelFiles
import dev.pampa.mycloudgallery.albums.AlbumsGraph
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngines
import dev.pampa.mycloudgallery.core.ai.faces.FaceEnginesFactory
import dev.pampa.mycloudgallery.core.database.CatalogPhoneOnly
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.common.perf.InstantShapes
import dev.pampa.mycloudgallery.core.database.faces.FacesDatabases
import dev.pampa.mycloudgallery.core.faces.compute.FacesBacklog
import dev.pampa.mycloudgallery.core.faces.compute.FacesBufferKeepers
import dev.pampa.mycloudgallery.core.faces.compute.FacesComputeCounters
import dev.pampa.mycloudgallery.core.faces.compute.FacesComputeEngine
import dev.pampa.mycloudgallery.core.faces.compute.FacesComputeParts
import dev.pampa.mycloudgallery.core.faces.compute.FacesRefresh
import dev.pampa.mycloudgallery.core.faces.compute.Sha256FaceBodyDigest
import dev.pampa.mycloudgallery.core.faces.contract.ClusterSnapshots
import dev.pampa.mycloudgallery.core.faces.compute.FacesFreeSpace
import dev.pampa.mycloudgallery.core.faces.contract.FaceInputs
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.PeopleArchiveSource
import dev.pampa.mycloudgallery.core.faces.contract.PeopleVariant
import dev.pampa.mycloudgallery.core.faces.contract.PersonIdSource
import dev.pampa.mycloudgallery.core.faces.erase.AnnounceResult
import dev.pampa.mycloudgallery.core.faces.erase.DefaultFacesEraser
import dev.pampa.mycloudgallery.core.faces.erase.EraseAnnouncer
import dev.pampa.mycloudgallery.core.faces.erase.EraseStopWork
import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.erase.FacesAccountFacts
import dev.pampa.mycloudgallery.core.faces.erase.FacesEraseFence
import dev.pampa.mycloudgallery.core.faces.erase.FacesEraseParts
import dev.pampa.mycloudgallery.core.faces.erase.FacesLocalErase
import dev.pampa.mycloudgallery.core.faces.erase.FacesNasJanitor
import dev.pampa.mycloudgallery.core.faces.erase.FileEraseJournalStore
import dev.pampa.mycloudgallery.core.faces.erase.StoresLocalData
import dev.pampa.mycloudgallery.core.faces.grouping.CustodianGrouping
import dev.pampa.mycloudgallery.core.faces.grouping.FastGrouping
import dev.pampa.mycloudgallery.core.faces.noop.NoClusterSnapshots
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesEraseOps
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesLeases
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesPublisher
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesRemote
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesSync
import dev.pampa.mycloudgallery.core.faces.people.ArchivePeople
import dev.pampa.mycloudgallery.core.faces.people.FacesCatalogCells
import dev.pampa.mycloudgallery.core.faces.people.PeopleVisibility
import dev.pampa.mycloudgallery.core.faces.store.FacesRecovery
import dev.pampa.mycloudgallery.core.faces.store.FacesStores
import dev.pampa.mycloudgallery.core.faces.switch.DefaultFacesSwitch
import dev.pampa.mycloudgallery.core.faces.switch.FacesLocalDataEraser
import dev.pampa.mycloudgallery.core.faces.switch.LocalEraseWhy
import dev.pampa.mycloudgallery.core.faces.switch.FileFacesLocalStore
import dev.pampa.mycloudgallery.core.faces.sync.NasFacesLeases
import dev.pampa.mycloudgallery.core.faces.sync.NasFacesPublisher
import dev.pampa.mycloudgallery.core.faces.sync.NasFacesRemote
import dev.pampa.mycloudgallery.core.faces.sync.NasFacesSync
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.nasops.faces.FacesNasConfig
import dev.pampa.mycloudgallery.core.nasops.faces.FacesNasServices
import dev.pampa.mycloudgallery.core.nasops.faces.FacesNasWiring
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStop
import dev.pampa.mycloudgallery.core.personal.settings.SyncedFacesSettings
import dev.pampa.mycloudgallery.core.personal.sync.PersonalHeadsRecord
import dev.pampa.mycloudgallery.core.security.faces.FacesKeyStore
import dev.pampa.mycloudgallery.debugtools.DeveloperTools
import dev.pampa.mycloudgallery.feature.people.crops.GuardedPeopleCrops
import dev.pampa.mycloudgallery.feature.people.list.PEOPLE_INSTANT_SHAPE_CODE
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.nas.CatalogPresenceSql
import dev.pampa.mycloudgallery.work.FacesWorkScheduling
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Come si montano le persone nel processo (Fase 8, P13; docs/fase8-progetto.md §1.4): le porte di core-faces legate a cio' che l'app ha gia' (registro,
 * aree, orologi, cancelli dell'indice e recinzioni D34 e D33, catalogo, buffer di analisi, indice della share, byte del telefono, dati personali, ricerca,
 * album). Le stesse righe le usano i test d'integrazione (Robolectric), cambiando solo la piattaforma sotto [AppGraph].
 *
 * **Un solo [FacesStores] per processo** (la memoria dei volti e quella degli stati sono per negozio) e **ogni apertura passa dal recinto**
 * ([FacesEraseFence]). L'interruttore, il recinto e la cancellazione nascono insieme, **prima** di qualunque apertura: un diario di cancellazione rimasto
 * a meta' chiude il recinto appena `DefaultFacesEraser` lo legge.
 */
object FacesWiring {

  /** Il segno di "ultimo giro del calcolo", in `noBackupFilesDir`; l'azzeramento e la cancellazione lo tolgono. */
  const val HEARTBEAT: String = "heartbeat-faces-compute"

  /** Il segno "una tappa del calcolo dei volti e' in corso" (E-A16): se alla tappa dopo c'e' ancora, il processo e' morto durante la tappa. */
  const val STAGE_MARK: String = "faces-stage-running"

  /** Tutti i file locali dei volti in `noBackupFilesDir` che non sono nel database della galleria (con i loro temporanei): li toglie l'azzeramento. */
  val FILES: List<String> = listOf(
    FacesStores.DIRECTORY,
    FacesKeyStore.DIRECTORY,
    FileFacesLocalStore.FILE,
    "${FileFacesLocalStore.FILE}.tmp",
    FileEraseJournalStore.FILE,
    "${FileEraseJournalStore.FILE}.tmp",
    PersonalHeadsRecord.FILE,
    "${PersonalHeadsRecord.FILE}.tmp",
    HEARTBEAT,
    "$HEARTBEAT.tmp",
    STAGE_MARK,
    "$STAGE_MARK.tmp",
  )

  /**
   * Solo i test d'integrazione (come `cutter` e `cutoutWriter` di `AiWiring.build`): i motori dei volti finti al posto di YuNet e SFace, gli ingressi
   * (le immagini finte delle scene sintetiche) e i modelli "pronti" senza gli asset dell'APK. Di serie niente: nell'app non si passa mai.
   */
  class Testing(
    val engines: (suspend () -> FaceEngines?)? = null,
    val inputs: ((FaceInputs) -> FaceInputs)? = null,
    val modelsReady: (suspend () -> Boolean)? = null,
  )

  fun build(
    context: Context,
    graph: AppGraph,
    gallery: GalleryGraph,
    ai: AiStack,
    albums: AlbumsGraph,
    bridge: PeopleBridge,
    testing: Testing? = null,
  ): FacesGraph {
    val noBackup = context.noBackupFilesDir
    val inputs = graph.nasInputs
    val nas = gallery.nas
    val shares = FacesShares(
      privateOf = { inputs.shares().firstOrNull { it.area == NasArea.PRIVATE }?.share },
      commonOf = { inputs.registry()?.registry?.config?.commonShare ?: inputs.shares().firstOrNull { it.area == NasArea.COMMON }?.share },
    )
    val ready: StateFlow<Boolean> = if (nas == null) {
      MutableStateFlow(false)
    } else {
      combine(gallery.started, nas.organizer.partsState) { started, parts -> started && parts != null }.stateIn(graph.scope, SharingStarted.Eagerly, false)
    }
    val signals = FacesSignalsToDiagnostics(graph.events)
    val stores = FacesStores(
      directory = File(noBackup, FacesStores.DIRECTORY),
      databases = FacesDatabases.forContext(context),
      keys = graph.facesKeys,
      onRecovered = { recovery: FacesRecovery -> recovery.diag?.let { signals.report(it, 1) } },
    )
    val graphRef = arrayOfNulls<FacesGraph>(1)
    val build: suspend (CoroutineScope) -> FacesLive? = build@{ scope ->
      val organizer = nas?.organizer ?: return@build null
      val parts = organizer.parts ?: return@build null
      val deviceId = inputs.deviceId() ?: return@build null
      val catalogOf: suspend () -> MediaCatalog? = { gallery.coreIfReady()?.catalog }
      val account: suspend () -> AccountId? = { inputs.account()?.let(AccountId::of) }
      val identity = AppFacesIdentity(inputs)
      val clock = AppFacesClock(inputs, { nas.clockGuard.blocked })
      val writeGate = AppFacesWriteGate(graph.flags, DeveloperTools.shareGuard(), { inputs.account() }, { nas.writeGate })
      val requirements = AppFacesRequirements(
        inputs = inputs,
        flags = graph.flags,
        roles = { AiWiring.familyRoles(graph, ai.ai.indexer.value) },
        indexerHere = { ai.ai.indexer.value },
        nasNow = { inputs.nasClock.nowMillisOrNull() },
      )
      val areas = AppFacesAreas(inputs)

      // --- Interruttore, recinto e cancellazione: insieme, prima di qualunque apertura -----------------------------------------------
      val localStore = FileFacesLocalStore(File(noBackup, FileFacesLocalStore.FILE))
      val settings = SyncedFacesSettings(parts.personalStore, scope)
      val cells = FacesCellIndex(catalogOf)
      val catalogPort = CatalogFacesCatalog(catalogOf, account, shares, ai.ai.parts.facts, cells, phoneAllowed(graph))
      val visibility = PeopleVisibility(gallery.visibleMedia, gallery.hiddenMask)
      val ensuring = EnsuringModelFiles(ai.localModels.files) { id -> ai.localModels.manager.ensure(id, ModelTrigger.USER_NOW, 30.seconds) }
      val cropsRef = arrayOfNulls<GuardedPeopleCrops>(1)
      val peopleRef = arrayOfNulls<ArchivePeople>(1)
      // Un dato nuovo da fuori (replica, custode, calcolo) o un cambio di memoria: le persone si rileggono (dal recinto, quindi dopo una cancellazione in corso).
      val arrivals = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
      val archivesRef = arrayOfNulls<FencedArchives>(1)
      val leftovers = AppFacesLeftovers(
        // R11 (correzioni A): dopo una cancellazione locale non resta niente in memoria: copertine, nomi e raggruppamento delle persone, l'ultimo archivio
        // aperto e la forma ricordata della pagina Persone (numeri che non devono sopravvivere ai dati).
        memory = {
          cropsRef[0]?.clear()
          peopleRef[0]?.release()
          archivesRef[0]?.forget()
          InstantShapes.remove(PEOPLE_INSTANT_SHAPE_CODE)
          // Se la generazione tolta non era quella in uso (una vecchia, un'esclusione a meta') le persone rileggono da capo; se era quella, il recinto non
          // ammette piu' l'archivio e la rilettura da' una lista vuota.
          arrivals.tryEmit(Unit)
        },
        models = {
          for (spec in ModelCatalog.FACES) runCatching { ai.localModels.manager.remove(spec.id) }
          ModelCatalog.FACES.none { spec -> ai.localModels.manager.states.value[spec.id] is ModelState.Ready }
        },
        marks = { dev.pampa.mycloudgallery.core.common.files.StateFiles.delete(File(noBackup, HEARTBEAT)) },
      )
      val started = CompletableDeferred<Unit>()
      lateinit var localErase: FacesLocalErase
      val switch = DefaultFacesSwitch(
        scope = scope,
        account = settings,
        localStore = localStore,
        requirements = requirements,
        eraser = object : FacesLocalDataEraser {
          override suspend fun erase(generation: FaceGeneration, why: LocalEraseWhy): Boolean {
            started.await()
            return localErase.erase(generation, why)
          }

          // R1: l'interruttore guarda anche le generazioni sul disco che `faces-local` non ricorda.
          override suspend fun generationsOnDisk(): List<FaceGeneration>? {
            started.await()
            return localErase.generationsOnDisk()
          }
        },
        clock = clock,
      )
      val fence = FacesEraseFence(switch)
      val history = AppSearchHistoryPurge { ai.search.history }
      localErase = FacesLocalErase(StoresLocalData(stores), fence, leftovers, history)
      val archives = FencedArchives(fence, stores.archives())
      archivesRef[0] = archives
      val replicaStore = FencedReplicaStore(fence, stores.replicaStore())
      val publishStore = FencedPublishStore(fence, stores.publishStore())

      // --- Il NAS: la share privata dichiarata, solo con il registro (D11) --------------------------------------------------------------
      val privateShare = shares.privateShare()
      val fileSystem = privateShare?.let { share -> inputs.session.fileSystem(share) }
      val engines = FacesEngineHolder { testing?.engines?.invoke() ?: FaceEnginesFactory.create(ensuring) }

      // --- "Disattiva e cancella" ---------------------------------------------------------------------------------------------------------
      // I1 (correzioni A): la cancellazione nasce **prima** delle persone. `DefaultFacesEraser` legge il diario e chiude il recinto (`fence.block`) nel suo
      // costruttore; `ArchivePeople`, piu' sotto, lancia subito una lettura che apre l'archivio dal recinto: con questo ordine un diario a meta' chiude il
      // recinto prima di ogni apertura (C130), per costruzione e non per fortuna.
      val services: FacesNasServices? = fileSystem?.let {
        FacesNasWiring.build(
          inputs.session,
          FacesNasConfig(
            deviceId = deviceId,
            accountId = inputs.account().orEmpty(),
            facts = AccountEraseFacts(FacesAccountFacts(switch, settings, identity)),
            privateShare = { shares.privateShare() },
            commonShare = { shares.commonShare() },
            gate = writeGate.asEraseGate(),
            clocks = organizer.clocks,
            registry = { inputs.registry() },
            publishedSegments = { generation -> publishStore.load(generation).diary },
            catalogPresence = CatalogPresenceSql(gallery.database, AccountId.of(inputs.account().orEmpty())),
          ),
        )
      }
      val eraseOps = services?.let { NasFacesEraseOps(it.eraser) { shares.privateShare() } } ?: NoFacesEraseOps
      val announcer = EraseAnnouncer { budget ->
        val report = organizer.syncPersonal(budget)
        if (report?.stoppedBy == PersonalStop.COMPLETE) AnnounceResult.Completed else AnnounceResult.Waiting(EraseWait.NAS)
      }
      val eraser = DefaultFacesEraser(
        FacesEraseParts(
          journal = FileEraseJournalStore(File(noBackup, FileEraseJournalStore.FILE)),
          switch = switch,
          settings = settings,
          localStore = localStore,
          requirements = requirements,
          local = StoresLocalData(stores),
          fence = fence,
          localErase = localErase,
          albums = AppSmartAlbumsPeople.over { albums.live.value },
          history = history,
          nas = eraseOps,
          announcer = announcer,
          heads = organizer.personalHeads,
          identity = identity,
          clock = clock,
          stopWork = EraseStopWork {
            runCatching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(FacesWorkScheduling.COMPUTE_WORK) }
            engines.release()
          },
        ),
      )
      val janitor = FacesNasJanitor(eraseOps, FacesAccountFacts(switch, settings, identity), identity, organizer.personalHeads, clock)

      // --- Il NAS: replica, pubblicazione e prenotazioni (D11) --------------------------------------------------------------
      val remote = fileSystem?.let { fs -> NasFacesRemote(fs, deviceId, { inputs.registry()?.registry?.config?.commonShare }) } ?: NoFacesRemote()
      val attestation = CatalogFacesAttestation(catalogOf, account, shares)
      val publisher = if (fileSystem != null) {
        NasFacesPublisher(identity, switch, remote, archives, publishStore, attestation, writeGate, clock, graph.flags)
      } else {
        NoFacesPublisher
      }
      val sync = if (fileSystem != null) {
        NasFacesSync(identity, switch, remote, archives, replicaStore, writeGate, clock, publisher, graph.flags)
      } else {
        NoFacesSync
      }
      val leases = if (fileSystem != null) NasFacesLeases(identity, switch, fileSystem, clock, writeGate, graph.flags) else NoFacesLeases
      val snapshots: ClusterSnapshots = (sync as? ClusterSnapshots) ?: NoClusterSnapshots
      val grouping = CustodianGrouping(
        archives = { currentArchive(switch, archives) },
        deviceId = deviceId,
        leases = leases,
        snapshots = snapshots,
        publisher = publisher,
        gone = CatalogGoneContents(catalogOf, cells),
        engine = FastGrouping(),
        ids = PersonIdSource.RANDOM,
      )

      // --- Il calcolo --------------------------------------------------------------------------------------------------------------------
      val inputsPort = AppFaceInputs(
        buffer = ai.ai.buffer,
        fetcher = { nas.runtime?.ai?.fetcher },
        phone = ai.ai.phone,
        preparer = { nas.runtime?.ai?.preparer?.let { p -> ai.ai.phone?.let { phone -> CheckedPreparer(p, phone::expectedQuickHash) } ?: p } },
        shares = shares,
      )
      val control = StageFacesControl()
      val counters = FacesComputeCounters()
      val compute = FacesComputeEngine(
        FacesComputeParts(
          switch = switch,
          localStore = localStore,
          requirements = requirements,
          areas = areas,
          catalog = catalogPort,
          inputs = testing?.inputs?.invoke(inputsPort) ?: inputsPort,
          engines = { engines.get() },
          modelsReady = testing?.modelsReady ?: { ModelCatalog.FACES.all { spec -> ensuring.open(spec.id)?.use { true } == true } },
          stores = FencedStoreSource(fence, stores),
          publisher = publisher,
          leases = leases,
          grouping = grouping,
          control = control,
          clock = clock,
          deviceId = deviceId,
          digest = Sha256FaceBodyDigest,
          freeSpace = FacesFreeSpace { noBackup.usableSpace },
          signals = signals,
          refresh = if (fileSystem != null) FacesRefresh { sync.sync(5.seconds) } else FacesRefresh.NONE,
        ),
        counters = counters,
      )
      val backlog = FacesBacklog(switch, requirements, catalogPort, archive = { archives.opened() })

      // --- Le persone ----------------------------------------------------------------------------------------------------------------------
      val progress: StateFlow<Int?> = compute.status.map { status ->
        (status as? FacesStatus.Running)?.takeIf { it.total > 0 }?.let { (it.done * 100L / it.total).toInt().coerceIn(0, 100) }
      }.stateIn(scope, SharingStarted.Eagerly, null)
      val available = switch.state.map { it.activeHere }.stateIn(scope, SharingStarted.Eagerly, switch.currentState().activeHere)
      val people = ArchivePeople(
        archives = PeopleArchiveSource { currentArchive(switch, archives) },
        cells = FacesCatalogCells(catalogPort),
        visibility = visibility,
        publisher = publisher,
        clock = clock,
        author = deviceId,
        scope = scope,
        ids = PersonIdSource.RANDOM,
        variant = MutableStateFlow(PeopleVariant.DEFAULT),
        availability = available,
        progress = progress,
        triggers = arrivals,
      )
      peopleRef[0] = people
      val crops = GuardedPeopleCrops(AppPeopleCropSource(cells, ai.ai.buffer, catalogOf, CutoutSources(context.contentResolver, gallery.intents::uriOf, nas.imageOrigin, ReplicaHq(nas.blobs, nas.locations))), people)
      cropsRef[0] = crops

      started.complete(Unit)

      FacesLive(
        scope = scope,
        stores = stores,
        switch = switch,
        settings = settings,
        fence = fence,
        eraser = eraser,
        janitor = janitor,
        sync = sync,
        publisher = publisher,
        compute = compute,
        counters = counters,
        grouping = grouping,
        runControl = control,
        people = people,
        crops = crops,
        archives = archives,
        engines = engines,
        requirements = requirements,
        backlog = backlog,
        arrivals = arrivals,
        cleaning = services?.cleaning,
        privateShare = { shares.privateShare() },
        onPeopleChanged = { scope.launch { runCatching { albums.live.value?.smartUpkeep?.peopleChanged() } } },
        mounted = {
          organizer.onPersonalRoundCompleted = { switch.onPersonalRoundCompleted() }
          val lookup = AppPeopleLookup(people, FacesCatalogCells(catalogPort), visibility, progress)
          bridge.bind(lookup, PeopleBridge.membershipOf(lookup, people), scope)
          ai.ai.keepers = FacesBufferKeepers(switch, requirements, catalogPort, archive = { archives.opened() })
        },
        requirementsChanged = graph.account.map { },
      )
    }
    val workRequest: (FacesGraph.FacesWork) -> Unit = { work ->
      when (work) {
        FacesGraph.FacesWork.COMPUTE -> FacesWorkScheduling.computeNow(context)
        FacesGraph.FacesWork.ERASE -> FacesWorkScheduling.eraseNow(context)
      }
    }
    return FacesGraph(graph.scope, build, ready, workRequest) { nas?.organizer?.parts?.personalSync?.lastHold?.value }.also { faces ->
      graphRef[0] = faces
      // Il gancio dei dati personali nasce con il processo: finche' le persone non sono montate, con dati dei volti sul disco non si pubblica.
      nas?.organizer?.prePublish = FacesPrePublish({ graphRef[0] }, stores)
    }
  }

  /** La generazione corrente di questo dispositivo, se lo tratta adesso, e il suo archivio dietro il recinto; null altrimenti. */
  private suspend fun currentArchive(switch: DefaultFacesSwitch, archives: FencedArchives): dev.pampa.mycloudgallery.core.faces.contract.FacesArchive? {
    val state = switch.currentState()
    if (!state.activeHere) return null
    val generation = state.generationOrNull ?: return null
    return archives.open(generation)
  }

  /** D13 (debug con un account di prova): un media del solo telefono fuori dalle cartelle recintate non si calcola; in release tutti. */
  private fun phoneAllowed(graph: AppGraph): suspend (MediaCatalog, CatalogPhoneOnly) -> Boolean = { catalog, item ->
    val fence = DeveloperTools.backupFence()
    if (fence == null) {
      true
    } else {
      val user = graph.account.value?.user
      val folder = catalog.phoneRefOf(item.mediaId, item.versionId)?.let { place -> catalog.phoneRowFolder(place.volume, place.mediaStoreId) }
      user != null && folder != null && fence.allowsPhoneFolder(user, folder)
    }
  }
}
