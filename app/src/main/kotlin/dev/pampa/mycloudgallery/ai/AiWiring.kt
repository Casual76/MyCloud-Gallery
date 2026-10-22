package dev.pampa.mycloudgallery.ai

import android.content.Context
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingSwitch
import dev.pampa.mycloudgallery.core.ai.contract.TextEmbedder
import dev.pampa.mycloudgallery.core.ai.ocr.GatedTextReader
import dev.pampa.mycloudgallery.core.ai.ocr.TextReadingShutdown
import dev.pampa.mycloudgallery.core.ai.runtime.LiteRtLmImageEmbedder
import dev.pampa.mycloudgallery.core.ai.runtime.LiteRtLmTextEmbedder
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.family.contract.RolesSettings
import dev.pampa.mycloudgallery.core.family.roles.OnboardingRolesSettings
import dev.pampa.mycloudgallery.core.family.roles.RolesRepublisher
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.models.LocalModels
import dev.pampa.mycloudgallery.core.models.ModelFetchers
import dev.pampa.mycloudgallery.core.models.contract.BundledAssets
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.models.contract.ModelNasArea
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.traffic.LedgerModelTraffic
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.personal.contract.MotionPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.SearchPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.TextReadingChoice
import dev.pampa.mycloudgallery.core.personal.settings.SyncedMotionSettings
import dev.pampa.mycloudgallery.core.personal.settings.SyncedSearchSettings
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficSource
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.graph.snapshotOrNull
import dev.pampa.mycloudgallery.search.GeocoderPlaceLookup
import dev.pampa.mycloudgallery.search.LazyQueryEmbedding
import dev.pampa.mycloudgallery.search.SearchGraph
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Le impostazioni personali della Fase 6 (`text_reading`, `search_order`, `motion_animation`) sopra il registro dei dati personali del
 * NAS (4B), che c'e' solo quando l'organizzazione del NAS e' agganciata: prima, null (vale l'ultima scelta ricordata o il default).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonalSettingsHub(stores: StateFlow<LedgerPersonalStore?>, scope: CoroutineScope) {
  val search: StateFlow<SearchPersonalSettings?> = stores.map { s -> s?.let { SyncedSearchSettings(it, scope) } }
    .stateIn(scope, SharingStarted.Eagerly, null)

  val motion: StateFlow<MotionPersonalSettings?> = stores.map { s -> s?.let { SyncedMotionSettings(it, scope) } }
    .stateIn(scope, SharingStarted.Eagerly, null)

  val textReading: StateFlow<TextReadingChoice?> = search.flatMapLatest { s -> s?.textReading ?: flowOf(null) }
    .stateIn(scope, SharingStarted.Eagerly, null)
}

/** Tutto cio' che la Fase 6 aggiunge al processo, montato una volta (Hilt, `AiModule`; i test d'integrazione lo rifanno uguale). */
class AiStack(
  val localModels: LocalModels,
  val search: SearchGraph,
  val ai: AiGraph,
  val personal: PersonalSettingsHub,
  val roles: RolesSettings,
  val textSwitch: TextReadingSwitch,
  /** "Scarica ora" sui dati mobili: la conferma data dall'utente per lo scaricamento in corso (D17, §2.7). */
  val mobileConfirmed: AtomicBoolean,
)

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
  ): AiStack {
    val scope = graph.scope
    val noBackup = context.noBackupFilesDir
    val mobileConfirmed = AtomicBoolean(false)
    val realFetcher by lazy {
      fetcher ?: ModelFetchers.create(
        nas = graph.session.mediaReader(),
        assets = BundledAssets { name -> runCatching { context.assets.open(name) }.getOrNull() },
        traffic = LedgerModelTraffic(graph.trafficLedger) { trafficNetworkOf(graph.environment.snapshot.value) },
      )
    }
    val models = LocalModels(
      root = File(noBackup, AiGraph.MODELS_DIR),
      fetcher = ModelFetcher { spec, source, partial, budget -> realFetcher.fetch(spec, source, partial, budget) },
      networkGate = AppModelNetworkGate({ graph.environment.snapshot.value }, { mobileConfirmed.get() }),
      // La copia sul NAS (D18) resta spenta: manca lo scrittore a flusso in core-nas (punto aperto di P3). Lo scaffale e' il null-object.
      area = ModelNasArea { graph.onboardingStore.snapshotOrNull()?.commonShare },
      clock = graph.nasClock,
    )
    val aiModels = AiModels(models.manager, models.files) { models.refresh() }
    val cacheRoot = File(File(noBackup, AiGraph.MODELS_DIR), "cache")

    // --- Ricerca (P6) -------------------------------------------------------------------------------------------------------
    val query = LazyQueryEmbedding(
      scope = scope,
      modelReady = { models.manager.states.value[ModelCatalog.QUERY_270M.id] is ModelState.Ready || models.files.open(ModelCatalog.QUERY_270M.id)?.use { true } == true },
      factory = { queryEmbedder?.invoke(models) ?: LiteRtLmTextEmbedder(models.files, cacheRoot, diag = diag) },
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
      modelMissing = { models.manager.states.value[ModelCatalog.QUERY_270M.id] !is ModelState.Ready },
    )

    // --- Lettura del testo (D41) ---------------------------------------------------------------------------------------------
    val organizerParts = gallery.nas?.organizer?.partsState
    val stores: StateFlow<LedgerPersonalStore?> =
      organizerParts?.map { it?.personalStore }?.stateIn(scope, SharingStarted.Eagerly, organizerParts.value?.personalStore)
        ?: kotlinx.coroutines.flow.MutableStateFlow(null)
    val personal = PersonalSettingsHub(stores, scope)
    val memory = TextReadingMemory(File(noBackup, TextReadingMemory.FILE))
    lateinit var switch: AppTextReadingSwitch
    // Il lettore si costruisce SOLO cosi' (P4): da spenti la lambda del motore non viene mai chiamata e ML Kit non si carica.
    val gated = reader?.invoke { switch.state.value } ?: GatedTextReader.create(context, { switch.state.value }, diag)
    val shutdown by lazy { TextReadingShutdown.create(context, gated, diag) }
    switch = AppTextReadingSwitch(
      scope = scope,
      choice = personal.textReading,
      setChoice = { choice -> personal.search.value?.setTextReading(choice)?.let { true } ?: false },
      memory = memory,
      shutdown = { shutdown },
    )

    // --- Ruoli (D15) ---------------------------------------------------------------------------------------------------------
    val roles = OnboardingRolesSettings(
      graph.onboardingStore,
      RolesRepublisher { runCatching { graph.statuses.publishIfDue(Purpose.INTERACTIVE) }.getOrDefault(false) },
      scope,
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
      ),
    )
    // Il riparatore dell'indice: la replica `ai` riconsegna (la rifusione la rifa' il prossimo giro di sincronizzazione).
    return AiStack(models, search, ai, personal, roles, switch, mobileConfirmed)
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
