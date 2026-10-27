package dev.pampa.mycloudgallery.albums

import dev.pampa.mycloudgallery.core.albums.contract.AlbumClocks
import dev.pampa.mycloudgallery.core.albums.contract.AlbumFamily
import dev.pampa.mycloudgallery.core.albums.contract.AlbumMover
import dev.pampa.mycloudgallery.core.albums.contract.AlbumUploader
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteGate
import dev.pampa.mycloudgallery.core.albums.contract.SafeCopyRequester
import dev.pampa.mycloudgallery.core.albums.contract.TicketStatus
import dev.pampa.mycloudgallery.core.albums.personal.CatalogAlbums
import dev.pampa.mycloudgallery.core.albums.personal.PersonalAlbumCopies
import dev.pampa.mycloudgallery.core.albums.share.BringBackCandidates
import dev.pampa.mycloudgallery.core.albums.share.FolderAdoptions
import dev.pampa.mycloudgallery.core.albums.share.PersonalKeptCopyStore
import dev.pampa.mycloudgallery.core.albums.share.RevokedItemsRestore
import dev.pampa.mycloudgallery.core.albums.share.ShareOrchestrator
import dev.pampa.mycloudgallery.core.albums.share.ShareOrchestratorPorts
import dev.pampa.mycloudgallery.core.albums.share.SharedFromPersonal
import dev.pampa.mycloudgallery.core.albums.shared.AlbumIdentity
import dev.pampa.mycloudgallery.core.albums.shared.AlbumReadGate
import dev.pampa.mycloudgallery.core.albums.shared.LevelHistory
import dev.pampa.mycloudgallery.core.albums.shared.NasAlbumPublisher
import dev.pampa.mycloudgallery.core.albums.shared.NasSharedAlbumSync
import dev.pampa.mycloudgallery.core.albums.shared.ReplicaSharedAlbums
import dev.pampa.mycloudgallery.core.albums.shared.SharedAlbumReplica
import dev.pampa.mycloudgallery.core.albums.smart.CatalogSmartCandidates
import dev.pampa.mycloudgallery.core.albums.smart.DeviceNormalizer
import dev.pampa.mycloudgallery.core.albums.smart.EngineContentSearch
import dev.pampa.mycloudgallery.core.albums.smart.NoSmartContentSearch
import dev.pampa.mycloudgallery.core.albums.smart.RoomSmartRows
import dev.pampa.mycloudgallery.core.albums.smart.RuleSmartNameInterpreter
import dev.pampa.mycloudgallery.core.albums.smart.RulesSmartEngine
import dev.pampa.mycloudgallery.core.albums.smart.SmartContentSearch
import dev.pampa.mycloudgallery.core.albums.smart.SmartRecompute
import dev.pampa.mycloudgallery.core.albums.trips.CatalogTrips
import dev.pampa.mycloudgallery.core.albums.contract.TripReport
import dev.pampa.mycloudgallery.core.albums.upkeep.SmartUpkeep
import dev.pampa.mycloudgallery.core.albums.upkeep.TripUpkeep
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbums
import dev.pampa.mycloudgallery.core.places.contract.PlaceNames
import dev.pampa.mycloudgallery.core.places.contract.ReverseGeocoder
import dev.pampa.mycloudgallery.core.search.contract.SearchEngine
import dev.pampa.mycloudgallery.core.search.contract.Today
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.search.GeocoderPlaceLookup
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

/**
 * Gli album di questo processo quando ci sono catalogo e dati personali (Fase 7, P13; §1.4): album personali e smart, viaggi, album condivisi e
 * condivisione, tutti sopra le stesse porte. Lo costruisce [AlbumsGraph] una volta; [scope] e' suo e si annulla con [AlbumsGraph.stop].
 */
class AlbumsLive internal constructor(
  val scope: CoroutineScope,
  val catalog: MediaCatalog,
  val personal: PersonalStore,
  val albums: CatalogAlbums,
  val copies: PersonalAlbumCopies,
  val keptStore: PersonalKeptCopyStore,
  val smartRows: RoomSmartRows,
  val smart: RulesSmartEngine,
  val recompute: SmartRecompute,
  val interpreter: RuleSmartNameInterpreter,
  val trips: CatalogTrips,
  val shared: SharedLive,
  /**
   * La ricerca per contenuto degli smart (D15): [SmartContentSearch.ready] dice se le regole "descrizione" ed "esempi" contano adesso. Prima era un
   * booleano fisso "c'e' un motore" (sempre vero con Hilt) e `SMART_NO_SEARCH` non scattava mai (DG-1); il motore c'e' sempre, **pronto** no.
   */
  val content: SmartContentSearch,
  /** Cio' che tiene al passo gli smart dopo ogni calcolo (AS-1..AS-6) e quando si cercano i viaggi (VG-1). */
  val smartUpkeep: SmartUpkeep,
  val tripUpkeep: TripUpkeep,
)

/** La parte degli album condivisi: replica, pubblicatore, condivisione e le proposte che non spostano niente (D39, D31, E-SH20). */
class SharedLive internal constructor(
  val replicaStore: RoomAlbumReplicaStore,
  val publishStore: RoomAlbumPublishStore,
  val replica: SharedAlbumReplica,
  val publisher: NasAlbumPublisher,
  val sync: NasSharedAlbumSync,
  val albums: ReplicaSharedAlbums,
  val diary: RoomShareDiary,
  val orchestrator: ShareOrchestrator,
  val ports: ShareOrchestratorPorts,
  val revoked: RevokedItemsRestore,
  val adoptions: FolderAdoptions,
  val bringBack: BringBackCandidates,
  val folders: CatalogCommonFolders,
  /** Gli album lasciati e gli avvisi chiusi ("Non mostrare piu'"): anche per gli avvisi di :app (E-SH16, E-SH20). */
  val choices: PersonalSharedAlbumChoices,
)

/** Cio' che serve per montare [AlbumsLive]: tutto gia' pronto nel resto dell'app (nessuna rete, nessun NAS qui). */
internal class AlbumsInputs(
  val database: GalleryDatabase,
  val catalog: MediaCatalog,
  val personal: PersonalStore,
  val visible: VisibleMedia,
  val mask: HiddenMask,
  val identity: AlbumIdentity,
  val family: AlbumFamily,
  val clocks: AlbumClocks,
  val writeGate: AlbumWriteGate,
  val readGate: AlbumReadGate,
  val fileSystem: (String) -> NasFileSystem,
  val publishAuthor: () -> Pair<String, dev.pampa.mycloudgallery.core.model.AccountId>?,
  val mover: AlbumMover,
  val uploader: AlbumUploader,
  val tickets: TicketStatus,
  val safeCopies: SafeCopyRequester,
  val search: SearchEngine?,
  val places: ReverseGeocoder,
  val placeNames: PlaceNames,
  val today: Today,
  val deviceNow: () -> Long,
  val noticesFile: File,
  val seenFile: File,
  /** La cartella `Backup/<dispositivo>/` di questo telefono (la regola B degli smart: "dello stesso dispositivo"); null se il backup non c'e'. */
  val ownBackupFolder: () -> String? = { null },
  /** Un errore di un giro dei motori degli album (si conta in `roundErrors`, mai il messaggio). */
  val onError: (Exception) -> Unit = {},
  /** Il resoconto di una ricerca dei viaggi fuori dalla notte (Diagnostica: solo numeri). */
  val onTripReport: (TripReport) -> Unit = {},
)

/** Il montaggio, nell'ordine delle dipendenze (condivisi, poi personali e smart, poi viaggi). Nessun I/O qui: lo fanno gli oggetti quando servono. */
internal fun buildAlbumsLive(scope: CoroutineScope, i: AlbumsInputs): AlbumsLive {
  // --- Album condivisi (P2) ---------------------------------------------------------------------------------------------------------
  val replicaStore = RoomAlbumReplicaStore(i.database)
  val publishStore = RoomAlbumPublishStore(i.database, i.publishAuthor)
  val facts = AlbumCatalogFacts { i.catalog.albums }
  val replica = SharedAlbumReplica(i.identity, replicaStore, facts)
  val publisher = NasAlbumPublisher(
    i.identity, i.family, i.fileSystem, publishStore, i.writeGate, i.clocks,
    // La storia dei livelli fusa: un cambio di livello si aggiunge a quella (P2), anche per un album di chi e' uscito (eredita').
    levels = { id -> replica.view(id)?.merged?.levels ?: replica.orphans.value[id]?.levels ?: LevelHistory.NONE },
  )
  replica.local = publisher
  val sync = NasSharedAlbumSync(i.identity, i.fileSystem, replicaStore, replica, i.clocks, i.readGate, ownAlbums = { publishStore.albums() })
  val choices = PersonalSharedAlbumChoices({ i.personal }, i.noticesFile)
  val sharedAlbums = ReplicaSharedAlbums(i.identity, i.family, replica, publisher, i.writeGate, i.clocks, i.visible, choices)
  val folders = CatalogCommonFolders(i.database)

  // --- Condivisione (P4) -------------------------------------------------------------------------------------------------------------
  var catalogAlbums: CatalogAlbums? = null
  val personalAlbums = PersonalAlbums(i.personal)
  val ports = ShareOrchestratorPorts(
    diary = RoomShareDiary(i.database, i.deviceNow),
    facts = facts,
    shared = sharedAlbums,
    allowed = { album, op, media -> sharedAlbums.allowed(album, op, media) },
    mover = i.mover,
    uploader = i.uploader,
    tickets = i.tickets,
    publisher = publisher,
    safeCopies = i.safeCopies,
    origins = PersonalSharedOrigins { i.personal },
    family = i.family,
    gate = i.writeGate,
    clocks = i.clocks,
    personal = { id ->
      catalogAlbums?.content(id)?.first()?.let { content -> SharedFromPersonal(content.album.title, idsOf(content.items)) }
    },
    knownFolders = { (replica.views.value.values.map { it.merged.folder } + replica.orphans.value.values.map { it.folder }).toSet() },
    folderContents = { folder -> folders.contents(folder) },
    lastPosition = { id -> replica.view(id)?.merged?.items?.values?.maxOfOrNull { it.position } },
    linkPersonal = { personalId, sharedId -> personalAlbums.setShared(personalId.value, sharedId.value) },
    afterStage = { id -> replica.recompute(listOf(id)) },
  )
  val orchestrator = ShareOrchestrator(ports)
  sharedAlbums.runner = orchestrator
  val shared = SharedLive(
    replicaStore, publishStore, replica, publisher, sync, sharedAlbums, ports.diary as RoomShareDiary, orchestrator, ports,
    revoked = RevokedItemsRestore(
      i.identity, i.family, replicaStore, replica, publisher, i.writeGate, i.clocks, i.visible,
      allowed = { album, op, media -> sharedAlbums.allowed(album, op, media) },
    ),
    adoptions = FolderAdoptions(ports) { ids -> i.visible.visible(ids) },
    bringBack = BringBackCandidates(ports),
    folders = folders,
    choices = choices,
  )

  // --- Album personali (P3) ----------------------------------------------------------------------------------------------------------
  val albums = CatalogAlbums(
    i.personal, i.catalog, i.database, i.visible, i.mask, i.family, scope,
    shared = sharedAlbums, runner = orchestrator, sharedItems = { id -> sharedAlbums.visibleItems(id) },
  )
  catalogAlbums = albums

  // --- Viaggi ed eventi (P7) ---------------------------------------------------------------------------------------------------------
  // Prima dell'interprete dei nomi (R16): l'interprete chiede ai viaggi dov'e' la casa per non scriverla nel centro di un luogo.
  val trips = CatalogTrips(i.database, CatalogAlbumCandidates { i.catalog.albums }, i.visible, i.mask, i.personal, albums, i.placeNames, i.today, i.clocks, scope)

  // --- Album smart (P6) --------------------------------------------------------------------------------------------------------------
  val smartRows = RoomSmartRows(i.database)
  val smartCandidates = CatalogSmartCandidates(i.catalog)
  val content = i.search?.let { EngineContentSearch(it) } ?: NoSmartContentSearch
  val lookup = GeocoderPlaceLookup(i.places)
  // AS-9: la cartella del proprio backup si legge a ogni uso (le impostazioni del backup si caricano dopo il montaggio).
  val smart = RulesSmartEngine(
    smartRows, smartCandidates, i.visible, i.mask, content, DeviceNormalizer(i.ownBackupFolder), lookup, i.clocks, FileSmartSeenMarks(i.seenFile),
  )
  val recompute = SmartRecompute(smartRows, smartCandidates, smart, i.clocks)
  val interpreter = RuleSmartNameInterpreter(ItalianQueryParser(), i.visible, i.mask, smartCandidates, lookup, content, i.places, home = trips.homeClearance())
  val smartUpkeep = SmartUpkeep(scope, smartRows, recompute, albums, onError = i.onError)
  val tripUpkeep = TripUpkeep(scope, trips, i.clocks, onReport = i.onTripReport, onError = i.onError)

  return AlbumsLive(
    scope, i.catalog, i.personal, albums, PersonalAlbumCopies(i.personal, i.catalog), PersonalKeptCopyStore(i.personal), smartRows, smart, recompute,
    interpreter, trips, shared, content, smartUpkeep, tripUpkeep,
  )
}

/** Gli id dei media di una vista, nell'ordine della vista. */
internal fun idsOf(view: TimelineView): List<MediaId> = List(view.size) { index -> view.snapshot.idAt(view.snapshotIndex(index)) }
