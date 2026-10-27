package dev.pampa.mycloudgallery.ai

import android.content.ComponentCallbacks2
import android.content.Context
import dev.pampa.mycloudgallery.backup.CutoutFence
import dev.pampa.mycloudgallery.backup.FencedCutouts
import dev.pampa.mycloudgallery.backup.MediaStorePhoneBytes
import dev.pampa.mycloudgallery.core.ai.compute.AreaView
import dev.pampa.mycloudgallery.core.ai.compute.DeviceRoleView
import dev.pampa.mycloudgallery.core.ai.contract.AiDiag
import dev.pampa.mycloudgallery.core.ai.contract.SubjectCutter
import dev.pampa.mycloudgallery.core.ai.contract.TextEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
import dev.pampa.mycloudgallery.core.ai.cutout.MagicTouchCutter
import dev.pampa.mycloudgallery.core.ai.ocr.GatedTextReader
import dev.pampa.mycloudgallery.core.ai.ocr.TextReadingShutdown
import dev.pampa.mycloudgallery.core.ai.runtime.LiteRtLmImageEmbedder
import dev.pampa.mycloudgallery.core.ai.runtime.LiteRtLmTextEmbedder
import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.family.contract.RolesSettings
import dev.pampa.mycloudgallery.core.family.roles.OnboardingRolesSettings
import dev.pampa.mycloudgallery.core.family.roles.RolesRepublisher
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaWriter
import dev.pampa.mycloudgallery.core.mediastore.write.MediaStorePhoneMediaWriter
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.models.LocalModels
import dev.pampa.mycloudgallery.core.models.ModelFetchers
import dev.pampa.mycloudgallery.core.models.contract.BundledAssets
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.FileStatReader
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelManager
import dev.pampa.mycloudgallery.core.models.contract.ModelNasArea
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.models.traffic.LedgerModelTraffic
import dev.pampa.mycloudgallery.core.models.verify.AndroidFileStatReader
import dev.pampa.mycloudgallery.core.nas.ops.models.NasModelShelfOpsFactory
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficSource
import dev.pampa.mycloudgallery.core.trust.data.DeviceRole
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.debugtools.DeveloperTools
import dev.pampa.mycloudgallery.feature.viewer.cutout.AndroidCutoutSaver
import dev.pampa.mycloudgallery.feature.viewer.cutout.CutoutShareFiles
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.graph.snapshotOrNull
import dev.pampa.mycloudgallery.search.GeocoderPlaceLookup
import dev.pampa.mycloudgallery.search.LazyQueryEmbedding
import dev.pampa.mycloudgallery.search.SearchGraph
import dev.pampa.mycloudgallery.work.AiWorkScheduling
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Tutto cio' che la Fase 6 aggiunge al processo, montato una volta (Hilt, `AiModule`; i test d'integrazione lo rifanno uguale). */
class AiStack(
  val localModels: LocalModels,
  val search: SearchGraph,
  val ai: AiGraph,
  val personal: PersonalSettingsHub,
  val roles: RolesSettings,
  val textSwitch: AppTextReadingSwitch,
  /**
   * Il gestore dei modelli che il resto dell'app vede (pagine, Cerca, ruolo): chiede un lavoro di WorkManager invece di scaricare nel proprio
   * scope (MO-1). I lavori e la Diagnostica usano [localModels] direttamente.
   */
  val requestingModels: ModelManager,
  /** Il ritagliatore del soggetto (P13, D44): costruito al primo ritaglio, rilasciato a riposo, a memoria bassa e all'azzeramento. */
  val cutout: CutoutRuntime,
  /** "Salva come nuova foto" del ritaglio (D47): le foto nuove in `Ritagli/`; `purgeOrphans()` all'avvio. */
  val cutoutPhotos: AppCutoutNewPhoto,
  /** D13: dove stanno i ritagli e la loro sorgente del backup (recinzione delle prove, solo in debug). */
  val cutoutFence: CutoutFence,
  /** I modelli verificati del ritaglio, con l'ultimo esito dello spazio (VR-2): il ritagliatore dice "Spazio insufficiente" invece di "riprova". */
  val cutoutModels: EnsuringModelFiles,
  private val context: Context,
  /** "Trova simili" dal viewer verso la scheda Cerca. */
  val similar: SimilarRequests = SimilarRequests(),
) {
  /**
   * Le porte del viewer della ricerca (testo dal vivo e lettura al volo, "Trova simili", foto in movimento, ritaglio del soggetto), legate
   * all'avvio sulla galleria. [share] e' dove Copia e Condividi del ritaglio mettono il PNG (la cartella del `FileProvider` che c'e' gia'):
   * senza, il ritaglio c'e' e "Salva come nuova foto" funziona, Copia e Condividi no.
   */
  fun viewerHooks(
    graph: AppGraph,
    gallery: GalleryGraph,
    share: CutoutShareEnv? = null,
    textTimeoutMs: Long = GatedViewerTextReader.READ_TIMEOUT_MS,
  ): GalleryGraph.SearchViewerHooks {
    val catalog: suspend () -> MediaCatalog? = { gallery.coreIfReady()?.catalog }
    val diag = { code: DiagCode -> graph.events.code(code) }
    val nas = gallery.nas
    val hq = nas?.let { ReplicaHq(it.blobs, it.locations) }

    // Lettura al volo (D42, §2.10): dall'immagine mostrata, solo con la lettura attiva qui.
    val textReader = GatedViewerTextReader(
      switch = textSwitch,
      reader = ai.parts.reader,
      images = ViewerOcrImages(
        catalog, ai.buffer, ai.phone, { gallery.nas?.preparer }, { ref -> hq?.bytes(ref) }, { ref -> nas?.imageOrigin?.localFile(ref) },
        // R5: l'HQ e' l'anteprima di chi l'ha preparata; il testo letto su di lei vale solo nella share del media, se e' una sola.
        hqShare = { ref -> runCatching { nas?.locations?.scopeOf(ref) }.getOrNull()?.shareKeys?.singleOrNull() },
      ),
      ocr = ai::ocrImage,
      keep = ai::keepReadText,
      recipe = ai.recipe,
      timeoutMs = textTimeoutMs,
    )

    // Ritaglio del soggetto (D44, D45, D47): la migliore immagine che il dispositivo ha gia', mai uno scaricamento.
    val sources = CutoutSources(context.contentResolver, gallery.intents::uriOf, nas?.imageOrigin, hq)
    val names = RememberedSourceName()
    // R18, VR-3: un solo ritaglio negli appunti, che scade; e gli appunti con un ritaglio nostro di un processo morto (il file e' stato tolto) si svuotano.
    val clipboard = share?.let { CutoutClipboard(context, graph.scope).also { CutoutClipboard.clearIfOurs(context) } }
    val files = share?.let { CutoutFiles(it.cache, it.uris, holder = it.holder, revoker = it.revoker, clipboard = clipboard) } ?: CutoutShareFiles { null }
    return GalleryGraph.SearchViewerHooks(
      // VT-2: il viewer rilegge il media aperto quando l'indice cambia (testo arrivato, ritirato, tolto con una revoca).
      textSource = IndexViewerTextSource(search.mediaText, search.index.version.drop(1).map { }.conflate()),
      textReader = textReader,
      similarSearch = SearchViewerSimilar(search.keys, similar) { key -> search.index.hasVectors(key) },
      motionAnimation = dev.pampa.mycloudgallery.feature.viewer.motion.MotionAnimationSetting { personal.motionAnimation.value },
      motionTraffic = dev.pampa.mycloudgallery.feature.viewer.video.MotionTraffic { bytes, _ ->
        graph.scope.launch {
          graph.trafficLedger.record(TrafficSource.VISIBLE_CONTENT, trafficNetworkOf(graph.environment.snapshot.value), bytes)
        }
      },
      subjectCutter = AppViewerSubjectCutter(cutout, sources, catalog, names, diag, noSpace = { cutoutModels.lastNoSpace }),
      cutoutSaver = DiagCutoutSaver(AndroidCutoutSaver(context, AppCutoutPng(cutout, sources), files, cutoutPhotos, names::nameOf), diag),
    )
  }

  /** `onTrimMemory`: il motore delle query (D35) e il ritagliatore (P13) si rilasciano a memoria bassa. */
  fun onTrimMemory(level: Int) {
    if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) search.onTrimMemory()
    cutout.trim(level)
    // IX-5: il motore delle immagini (440M, circa 800 MB) si rilascia quando il sistema ha davvero poca memoria (in primo piano: RUNNING_LOW e
    // RUNNING_CRITICAL; in cache: MODERATE e COMPLETE), non per il solo passaggio in secondo piano.
    if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
      level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
    ) {
      ai.onTrimMemory()
    }
  }
}

/**
 * Il MONTAGGIO della Fase 6 (P11) sopra il grafo dell'app ([graph]) e la galleria ([gallery]): modelli (P3) con le loro porte, lettura
 * del testo (D41) con le impostazioni personali e l'esclusione locale, ruoli (D15), motore di ricerca (P6) e AI (P4, P5, P2).
 *
 * @param diag i codici della Diagnostica dei motori (solo codici, D40).
 */
object AiWiring {

  fun build(
    context: Context,
    graph: AppGraph,
    gallery: GalleryGraph,
    diag: (DiagCode) -> Unit = {},
    imageEmbedder: ((LocalModels) -> dev.pampa.mycloudgallery.core.ai.contract.ImageEmbedder)? = null,
    queryEmbedder: ((LocalModels) -> TextEmbedder)? = null,
    reader: ((() -> dev.pampa.mycloudgallery.core.ai.contract.TextReadingState) -> GatedTextReader)? = null,
    fetcher: ModelFetcher? = null,
    /** Solo i test d'integrazione (motori finti, nessun file di modello): gli ingressi e la prontezza dei modelli. */
    inputs: dev.pampa.mycloudgallery.core.ai.compute.AiInputs? = null,
    modelReady: (suspend (dev.pampa.mycloudgallery.core.models.contract.ModelId) -> Boolean)? = null,
    /** D13: la recinzione del telefono (debug con un account di prova); in release null. I test la scelgono. */
    fence: BackupFence? = DeveloperTools.backupFence(),
    /** D13: le cartelle recintate dei ritagli (solo debug, come [fence]); in release null. */
    fencedCutouts: FencedCutouts? = DeveloperTools.fencedCutouts(),
    /** Solo i test d'integrazione: il ritagliatore (finto) e lo scrittore di `Ritagli/` (finto) al posto di MagicTouch e del MediaStore. */
    cutter: ((ModelFiles) -> SubjectCutter)? = null,
    cutoutWriter: ((relativePath: String) -> PhoneMediaWriter)? = null,
    /** Fase 8 (P13, D26): la ricerca per persona (il ponte di `FacesModule`); di serie nessuna. */
    people: dev.pampa.mycloudgallery.core.search.contract.PeopleLookup = dev.pampa.mycloudgallery.core.search.noop.NoPeopleLookup,
  ): AiStack {
    val scope = graph.scope
    val noBackup = context.noBackupFilesDir
    val realFetcher by lazy {
      fetcher ?: ModelFetchers.create(
        nas = graph.session.mediaReader(),
        assets = BundledAssets { name -> runCatching { context.assets.open(name) }.getOrNull() },
        traffic = LedgerModelTraffic(graph.trafficLedger) { trafficNetworkOf(graph.environment.snapshot.value) },
        // R12: HTTPS esce dalla rete che il cancello ha giudicato (la stessa su cui si registra il traffico), non da quella predefinita.
        httpsRoute = AppModelHttpsRoute({ graph.environment.snapshot.value }, graph.platform.networkLookup),
      )
    }
    // Lo scaffale dei modelli sul NAS (MO-4, D18, I5): copie solo di dispositivi approvati e non dismessi, scrittura a flusso nella propria
    // cartella, cancello F4-D31 dell'indice (`IndexWriteRules`). Nasce con il motore del NAS (il dispositivo ha il suo id solo allora).
    val shelfGate = RulesModelShelfGate { gallery.nas?.runtime?.ai?.rules }
    val shelfDevices = TrustedShelfDevices { graph.nasInputs.registry() }
    val models = LocalModels(
      root = File(noBackup, AiGraph.MODELS_DIR),
      fetcher = ModelFetcher { spec, source, partial, budget -> realFetcher.fetch(spec, source, partial, budget) },
      networkGate = AppModelNetworkGate { graph.environment.snapshot.value },
      shelfFor = { files ->
        AppModelShelf(
          files = files,
          deviceId = { gallery.nas?.runtime?.deviceId },
          reader = { graph.session.mediaReader() },
          ops = { id -> NasModelShelfOpsFactory.create(graph.session, id) },
          gate = shelfGate,
          devices = shelfDevices,
        )
      },
      area = ModelNasArea { graph.onboardingStore.snapshotOrNull()?.commonShare },
      clock = graph.nasClock,
      // I1: lo `stat` del file preso alla verifica, per `PATH_CHECKED`. Un errore del sistema vale "nessuno stat": il motore ripiega sul descrittore.
      statReader = FileStatReader { fd -> runCatching { AndroidFileStatReader().stat(fd) }.getOrNull() },
    )
    // MO-1, MO-2: "Scarica" della pagina, di Cerca e l'accensione del ruolo chiedono un lavoro di WorkManager (non scaricano nel loro scope).
    val requestingModels = RequestingModelManager(models.manager, scope) { id, trigger -> AiWorkScheduling.fetchNow(context, trigger, id) }
    val aiModels = AiModels(models.manager, models.files) { models.refresh() }
    val cacheRoot = File(File(noBackup, AiGraph.MODELS_DIR), "cache")

    // --- Ricerca (P6) -------------------------------------------------------------------------------------------------------
    val query = LazyQueryEmbedding(
      scope = scope,
      modelReady = {
        modelReady?.invoke(ModelCatalog.QUERY_270M.id)
          ?: (models.manager.states.value[ModelCatalog.QUERY_270M.id] is ModelState.Ready || models.files.open(ModelCatalog.QUERY_270M.id)?.use { true } == true)
      },
      factory = { queryEmbedder?.invoke(models) ?: LiteRtLmTextEmbedder(models.files, cacheRoot, diag = diag) },
    )
    // --- Copertura (revisione A, PR-1): aree senza indicizzatori e motivi, dai dati che questo dispositivo ha gia' -------------------
    var aiRef: AiGraph? = null
    var searchRef: SearchGraph? = null
    var readingRef: AppTextReadingSwitch? = null
    val coverage = AppCoverage(
      areas = { areasOf(graph) },
      devices = { familyRoles(graph, aiRef?.indexer?.value == true) },
      nasNow = { graph.nasClock.nowMillisOrNull() },
      catalog = { gallery.coreIfReady()?.catalog },
      indexerHere = { aiRef?.indexer?.value == true },
      modelReadyHere = {
        modelReady?.invoke(ModelCatalog.INDEX_440M.id) ?: (models.manager.states.value[ModelCatalog.INDEX_440M.id] is ModelState.Ready)
      },
      unsupported = { aiRef?.hooks?.unsupported },
      // D42: nessun testo letto da nessuno e la lettura non attiva per questo account.
      // Revisione B (LT-2): con l'avviso da vedere qui (AwaitingNotice) l'account ha la lettura accesa (altri dispositivi leggono): non e' "spenta".
      readingOff = {
        val reading = readingRef?.state?.value
        reading !is TextReadingState.OnForAccount && reading !is TextReadingState.AwaitingNotice && (searchRef?.index?.sizes?.second ?: 0) == 0
      },
      told = { diag(AiDiag.AI_NO_INDEXER_AREA) },
    )
    val search = SearchGraph(
      scope = scope,
      dir = File(noBackup, SearchGraph.DIR),
      catalog = { gallery.coreIfReady()?.catalog },
      database = gallery.database,
      snapshot = gallery.repository.snapshot,
      places = GeocoderPlaceLookup(gallery.places),
      embedding = query,
      queryModelBytes = ModelCatalog.QUERY_270M.sizeBytes,
      modelMissing = { modelReady == null && models.manager.states.value[ModelCatalog.QUERY_270M.id] !is ModelState.Ready },
      coverageReasons = coverage,
      coverageNotices = coverage,
      people = people,
    )
    searchRef = search

    // --- Lettura del testo (D41) ---------------------------------------------------------------------------------------------
    val organizerParts = gallery.nas?.organizer?.partsState
    val stores: StateFlow<LedgerPersonalStore?> =
      organizerParts?.map { it?.personalStore }?.stateIn(scope, SharingStarted.Eagerly, organizerParts.value?.personalStore)
        ?: kotlinx.coroutines.flow.MutableStateFlow(null)
    val personal = PersonalSettingsHub(stores, scope, PersonalLocalSettings(File(noBackup, PersonalLocalSettings.FILE)))
    val memory = TextReadingMemory(File(noBackup, TextReadingMemory.FILE))
    lateinit var switch: AppTextReadingSwitch
    // Il lettore si costruisce SOLO cosi' (P4): da spenti la lambda del motore non viene mai chiamata e ML Kit non si carica. Il cancello
    // legge lo stato di adesso (`currentState`), non il flusso: lo spegnimento vale prima di qualunque altra lettura (R7).
    val gated = reader?.invoke { switch.currentState() } ?: GatedTextReader.create(context, { switch.currentState() }, diag)
    val shutdown by lazy { TextReadingShutdown.create(context, gated, diag) }
    switch = AppTextReadingSwitch(
      scope = scope,
      choice = personal.textReading,
      // La scelta dell'utente vale subito qui; al registro dei dati personali arriva quando c'e' (anche "da datare", senza l'ora del NAS).
      setChoice = { choice ->
        val settings = personal.search.value
        if (settings == null) false else { settings.setTextReading(choice); true }
      },
      memory = memory,
      shutdown = { shutdown },
    )
    readingRef = switch

    // --- Ruoli (D15) ---------------------------------------------------------------------------------------------------------
    val roles = OnboardingRolesSettings(
      graph.onboardingStore,
      RolesRepublisher { runCatching { graph.statuses.publishIfDue(Purpose.INTERACTIVE) }.getOrDefault(false) },
      scope,
    )

    // --- Ritaglio del soggetto (P13, D44, D47) ------------------------------------------------------------------------------
    // Il modello e' nell'APK: la prima volta si mette fra i modelli verificati (copia locale, senza rete) e MagicTouch lo apre dai file verificati.
    val cutoutModels = EnsuringModelFiles(models.files) { id -> models.manager.ensure(id, ModelTrigger.USER_NOW, 30.seconds) }
    val cutout = CutoutRuntime(
      scope = scope,
      factory = { cutter?.invoke(cutoutModels) ?: MagicTouchCutter.create(cutoutModels) },
      maxPixels = gallery.imagingBudget.viewerMaxPixels,
    )
    // --- AI (P4, P5, P2) -----------------------------------------------------------------------------------------------------
    val images = imageEmbedder?.invoke(models) ?: LiteRtLmImageEmbedder(models.files, cacheRoot, diag = diag)
    val ai = AiGraph(
      context = context,
      scope = scope,
      noBackup = noBackup,
      database = gallery.database,
      search = search,
      parts = AiGraphParts(
        models = aiModels,
        textSwitch = switch,
        reader = gated,
        shutdown = { shutdown },
        roles = roles,
        shares = {
          val snapshot = graph.onboardingStore.snapshotOrNull()
          listOfNotNull(snapshot?.privateShare, snapshot?.commonShare).distinctBy { it.lowercase(java.util.Locale.ROOT) }
        },
        nas = { gallery.nas?.runtime?.ai },
        traffic = { bytes -> scope.launch { graph.trafficLedger.record(TrafficSource.AI_COMPUTE, trafficNetworkOf(graph.environment.snapshot.value), bytes) } },
        images = images,
        inputs = inputs,
        modelReady = modelReady?.let { ready -> { ready(ModelCatalog.INDEX_440M.id) } },
        // P11b (§2.2, D10): i byte del telefono per i media del solo telefono, con la recinzione di D13 (debug con un account di prova).
        phoneBytes = MediaStorePhoneBytes(
          rows = { gallery.coreOrNull?.phone?.rows },
          access = { gallery.coreOrNull?.phone?.access?.state?.value ?: PhoneAccess.NONE },
          fence = fence,
          account = { graph.account.value?.user },
        ),
        catalog = { gallery.coreIfReady()?.catalog },
        fence = fence,
        account = { graph.account.value?.user },
        releaseCutout = cutout::stop,
      ),
    )

    aiRef = ai

    val cutoutFence = CutoutFence(fence, fencedCutouts) { graph.account.value?.user }
    val cutoutPhotos = AppCutoutNewPhoto(
      catalog = { gallery.coreIfReady()?.catalog },
      writerFor = cutoutWriter ?: { path -> MediaStorePhoneMediaWriter(context, relativePath = path) },
      folder = cutoutFence::folder,
      nowMillis = gallery.clock,
      // VR-1, R19: il segno "nascosto" di un ritaglio dai Nascosti viaggia ai miei dispositivi subito, come ogni "Nascondi".
      onHiddenSaved = { gallery.nas?.organizer?.personalChanged() },
    )
    return AiStack(models, search, ai, personal, roles, switch, requestingModels, cutout, cutoutPhotos, cutoutFence, cutoutModels, context)
  }

  /** PR-1: le aree di questo account (la privata, se l'account e' noto) e l'area comune, dall'istantanea dell'onboarding. */
  private fun areasOf(graph: AppGraph): List<AreaView> {
    val snapshot = graph.onboardingStore.snapshotOrNull() ?: return emptyList()
    val account = snapshot.nasUser ?: graph.account.value?.user
    return listOfNotNull(
      snapshot.privateShare?.takeIf { account != null }?.let { AreaView(it, account) },
      snapshot.commonShare?.let { AreaView(it, null) },
    ).distinctBy { it.share.lowercase(java.util.Locale.ROOT) }
  }

  /**
   * PR-1 (D14, E-I12): i dispositivi approvati del registro con il ruolo e l'ultima volta visto dei loro stati firmati; questo dispositivo
   * con il ruolo di adesso ([indexerHere]). Null finche' gli stati non si sono letti: nessuna area si dice scoperta per ipotesi.
   */
  internal fun familyRoles(graph: AppGraph, indexerHere: Boolean): List<DeviceRoleView>? {
    val registry = graph.engine.state?.registry ?: return null
    val statuses = graph.statuses.latest.value
    if (statuses.isEmpty()) return null
    val me = graph.engine.myDeviceId()
    val out = registry.devices.filter { it.status == DeviceStatus.APPROVED && it.id != me }.mapNotNull { d ->
      statuses[d.id]?.let { DeviceRoleView(d.nasUser, DeviceRole.INDEXER in it.roles, it.lastSeenAt) }
    }.toMutableList()
    val account = registry.devices.firstOrNull { it.id == me }?.nasUser ?: graph.account.value?.user
    val now = graph.nasClock.nowMillisOrNull()
    if (account != null && now != null) out += DeviceRoleView(account, indexerHere, now)
    return out
  }

  /** Le aree di questo dispositivo come le vede la pagina (solo il tipo, mai il nome della share). */
  fun areaOf(share: String, graph: AppGraph): NasArea? {
    val snapshot = graph.onboardingStore.snapshotOrNull() ?: return null
    return when {
      snapshot.privateShare.equals(share, ignoreCase = true) -> NasArea.PRIVATE
      snapshot.commonShare.equals(share, ignoreCase = true) -> NasArea.COMMON
      else -> null
    }
  }
}
