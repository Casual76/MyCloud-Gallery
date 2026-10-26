package dev.pampa.mycloudgallery.ai

import android.content.Context
import dev.pampa.mycloudgallery.core.ai.compute.AiComputeEngine
import dev.pampa.mycloudgallery.core.ai.compute.AiComputeParts
import dev.pampa.mycloudgallery.core.ai.compute.AiFreeSpace
import dev.pampa.mycloudgallery.core.ai.compute.AiInputOrigin
import dev.pampa.mycloudgallery.core.ai.compute.AiInputs
import dev.pampa.mycloudgallery.core.ai.compute.AiPhoneMedia
import dev.pampa.mycloudgallery.core.ai.compute.AiRunControl
import dev.pampa.mycloudgallery.core.ai.compute.BufferAnalysisSink
import dev.pampa.mycloudgallery.core.ai.compute.DiskAiBuffer
import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.contract.AiRoles
import dev.pampa.mycloudgallery.core.ai.contract.AiRunResult
import dev.pampa.mycloudgallery.core.ai.contract.AiScope
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.AiWaitReason
import dev.pampa.mycloudgallery.core.ai.contract.ImageEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.ModelImage
import dev.pampa.mycloudgallery.core.ai.contract.OcrImage
import dev.pampa.mycloudgallery.core.ai.contract.TextReader
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingSwitch
import dev.pampa.mycloudgallery.core.ai.ocr.TextReadingShutdown
import dev.pampa.mycloudgallery.core.ai.runtime.ModelInputs
import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.family.contract.RolesSettings
import dev.pampa.mycloudgallery.core.index.ai.AiEntry
import dev.pampa.mycloudgallery.core.index.ai.AiIndexCounters
import dev.pampa.mycloudgallery.core.index.ai.AiTraffic
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.VideoFrame
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.OcrSource
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelManager
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.DeviceRole
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.nas.NasAiHooks
import dev.pampa.mycloudgallery.nas.NasAiRuntime
import dev.pampa.mycloudgallery.nas.NoUnitPriority
import dev.pampa.mycloudgallery.search.SearchGraph
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Cio' che `AiGraph` riceve dal resto dell'app: modelli e motori (gia' montati, [AiModels]), interruttore e spegnimento della lettura del
 * testo, ruoli, le share di questo dispositivo (prima la privata propria, poi la comune, D14), la parte `ai` del motore del NAS quando c'e'
 * e i contatori per la Diagnostica.
 */
class AiGraphParts(
  val models: AiModels,
  val textSwitch: TextReadingSwitch,
  val reader: TextReader,
  val shutdown: () -> TextReadingShutdown,
  val roles: RolesSettings,
  val shares: suspend () -> List<String>,
  val nas: () -> NasAiRuntime?,
  val traffic: (Long) -> Unit,
  val images: ImageEmbedder,
  /** Gli ingressi dei motori: di serie `ModelInputs` (D5); i test d'integrazione ne danno di finti, con i motori finti. */
  val inputs: AiInputs? = null,
  /** Il modello del calcolo c'e' ed e' verificato: di serie `ModelFiles.open` (D16); i test, con i motori finti, senza file. */
  val modelReady: (suspend () -> Boolean)? = null,
  /**
   * Cio' che il catalogo ha detto dei contenuti elencati (chiave -> media, versione, sorgente): lo riempiono l'elenco dei mesi del NAS e quello
   * dei media del solo telefono, lo leggono i byte del telefono ([phoneBytes]) e la sorgente degli screenshot (D12).
   */
  val facts: ContentFacts = ContentFacts(),
  /**
   * I byte del telefono per il calcolo (P11b, §2.2): i media del solo telefono e quelli del NAS che sono anche qui. Null = nessuno dei due
   * (i montaggi senza la lettura del telefono): i media del solo telefono non si calcolano. [catalog], [fence] (D13) e [account] sono quelli
   * della galleria.
   */
  val phoneBytes: PhoneBytes? = null,
  val catalog: suspend () -> MediaCatalog? = { null },
  val fence: BackupFence? = null,
  val account: () -> String? = { null },
  /** L'azzeramento rilascia anche il ritagliatore del soggetto (P13): il modello occupa circa 240 MB. */
  val releaseCutout: suspend () -> Unit = {},
)

/**
 * Il motore delle immagini con un interruttore: dopo [stopped] ogni `embed` annulla la tappa invece di riaprire il motore (AZ-1). `release` passa
 * sempre: chiudere e' sempre lecito.
 */
internal class StoppableImageEmbedder(private val inner: ImageEmbedder) : ImageEmbedder {
  @Volatile var stopped = false

  override val backend: StateFlow<AiBackend> get() = inner.backend

  override suspend fun embed(images: List<ModelImage>): List<FloatArray> {
    if (stopped) throw kotlinx.coroutines.CancellationException("Calcolo fermo: azzeramento.")
    return inner.embed(images)
  }

  override suspend fun release() = inner.release()
}

/** I modelli di questo dispositivo (P3): il gestore e i file verificati, con `refresh()` da chiamare all'avvio. */
class AiModels(val manager: ModelManager, val files: ModelFiles, val refresh: suspend () -> Unit)

/**
 * IL GRAFO DELL'AI (docs/fase6-progetto.md §1.4, P11): modelli (P3), motori (P4), lettura del testo dietro il cancello (D41), calcolo
 * "una volta per tutta la famiglia" (P5) con il buffer degli indicizzatori, pubblicazione e replica `ai` (P2, nella parte `ai` del motore
 * del NAS: [hooks]), e le porte verso l'indice locale di ricerca ([search]).
 *
 * - Il calcolo gira **solo** dentro `AiComputeWorker` (tappe da 8 minuti, `StagedRun`): [runStage].
 * - [stop] **per primo** all'azzeramento, insieme a `SearchGraph.stop` (§1.4): niente parte piu', i motori si rilasciano.
 */
class AiGraph(
  context: Context,
  scope: CoroutineScope,
  noBackup: File,
  database: GalleryDatabase,
  val search: SearchGraph,
  val parts: AiGraphParts,
  val recipe: AiRecipe = AiRecipe.CURRENT,
) {
  private val job = SupervisorJob(scope.coroutineContext[Job])
  private val graphScope = CoroutineScope(scope.coroutineContext + job)

  /**
   * Il motore delle immagini che il calcolo, la misura breve e la pagina usano: lo stesso di `parts.images`, ma che **dopo [stop] non si riapre
   * piu'** (AZ-1: una tappa in corso all'azzeramento riapriva il 440M al primo `embed` dopo il rilascio) e che si rilascia a fine tappa
   * (IX-5).
   */
  val images: ImageEmbedder = StoppableImageEmbedder(parts.images)

  val models: AiModels get() = parts.models
  val textSwitch: TextReadingSwitch get() = parts.textSwitch

  /** Indicizzatore (D15): il ruolo scelto per questo dispositivo. */
  val indexer: StateFlow<Boolean> = parts.roles.roles.map { DeviceRole.INDEXER in it }
    .stateIn(graphScope, SharingStarted.Eagerly, DeviceRole.INDEXER in parts.roles.roles.value)

  val textReadingHere: StateFlow<Boolean> = parts.textSwitch.state.map { it.activeHere }
    .stateIn(graphScope, SharingStarted.Eagerly, parts.textSwitch.state.value.activeHere)

  /** Il buffer di analisi degli indicizzatori (D10): `noBackupFilesDir/ai-buffer`. */
  val buffer = DiskAiBuffer(
    File(noBackup, BUFFER_DIR),
    // Lo spazio del volume di `noBackupFilesDir`: `File.usableSpace` di una cartella che non c'e' ancora vale 0, e il buffer rifiuterebbe
    // tutto (trovato dal test della catena, P11).
    AiFreeSpace { noBackup.usableSpace },
  )

  /** Dove la preparazione (del NAS e del backup) consegna `analysis` e `frames` sugli indicizzatori (D10, P5). */
  val analysisSink = BufferAnalysisSink(buffer) { indexer.value }

  val unsupported = FileAiUnsupported(File(noBackup, FileAiUnsupported.FILE))

  /** Revisione A (R4, R5): il dato, la provenienza e il corpo da ripubblicare stanno insieme nell'indice locale. */
  val results = IndexAiResults(search.index) { search.open() }
  val replicaStore = RoomAiReplicaStore(database)
  val publishStore = RoomAiPublishStore(database)
  val replicaSink = IndexReplicaSink(search.index) { search.open() }
  val indexCounters = AiIndexCounters()

  /** Il telefono per il calcolo (P11b): i media del solo telefono da fare e l'apertura dei byte; null senza `parts.phoneBytes`. */
  val phone: AppAiPhone? = parts.phoneBytes?.let { bytes ->
    AppAiPhone(parts.catalog, bytes, results, unsupported, parts.facts, recipe, parts.fence, parts.account)
  }

  /** Le porte che il grafo del NAS usa per le teste `ai` (P2). */
  val hooks = NasAiHooks(
    replicaStore = replicaStore,
    publishStore = publishStore,
    unsupported = unsupported,
    sink = { replicaSink },
    traffic = AiTraffic { parts.traffic(it) },
    wanted = { wantedHere() },
    analysisSink = { analysisSink.takeIf { indexer.value } },
    counters = indexCounters,
    recipe = recipe,
    facts = parts.facts,
    // Revisione A: la revoca toglie anche le anteprime del revocato dal buffer (R5); la compattazione delle teste proprie (R14).
    buffer = buffer,
    ownBodies = IndexOwnBodies(search.index) { search.open() },
    compactions = FileCompactionMemory(File(noBackup, FileCompactionMemory.FILE)),
  )

  init {
    // L'indice che perde un dato (record rovinato, registro rotto) lo chiede di nuovo alla replica `ai`; R15: si toglie il segno
    // "consegnato", cosi' il giro dopo riconsegna anche lo stesso vincitore. Cio' che era calcolato qui lo rifa' il calcolo.
    search.repair.target = ReplicaIndexRepair(replicaStore)
  }

  /** Le classi che servono qui (D41): `vec` su un indicizzatore, `ocr` solo con la lettura attiva qui. */
  fun wantedHere(): Set<AiClass> = when {
    !indexer.value -> emptySet()
    textReadingHere.value -> setOf(AiClass.VEC, AiClass.OCR)
    else -> setOf(AiClass.VEC)
  }

  private val roles = object : AiRoles {
    override val indexer: StateFlow<Boolean> get() = this@AiGraph.indexer
    override val textReadingHere: StateFlow<Boolean> get() = this@AiGraph.textReadingHere

    override suspend fun shares(): List<String> = parts.shares()
  }

  private val inputs: AiInputs = parts.inputs ?: object : AiInputs {
    override fun image(analysis: ByteArray, recipe: AiRecipe): ModelImage? = ModelInputs.fromAnalysis(analysis, recipe)

    override fun frames(frames: List<VideoFrame>, recipe: AiRecipe): List<ModelImage> = ModelInputs.fromFrames(frames, recipe)

    override fun ocr(bytes: ByteArray, source: OcrSource): OcrImage? = ModelInputs.forOcr(bytes, source)
  }

  /** L'immagine per l'OCR da dei byte (D5): la stessa strada del calcolo, anche per la lettura al volo del viewer (P11b). */
  fun ocrImage(bytes: ByteArray, source: OcrSource): OcrImage? = inputs.ocr(bytes, source)

  /**
   * Il testo letto al volo dal viewer (D42, §2.10) segue il percorso normale: **salvato** subito nell'indice locale (e nelle righe per il
   * testo dal vivo), poi messo nell'outbox `ai` di ogni share che contiene il file (M3: una chiave di un file solo del telefono non va in
   * nessuna) e pubblicato in coda (`PERIODIC`: al piu' ogni 5 minuti per share; il resto lo fa il giro periodico del NAS). F4-D31: sulle
   * share vere resta in attesa, come i risultati del calcolo. Mai un errore: il testo e' gia' sullo schermo.
   */
  suspend fun keepReadText(key: ContentKey, body: OcrBody, from: AiInputOrigin = AiInputOrigin.Own) {
    try {
      results.saveText(key, body, from)
    } catch (e: IOException) {
      return
    }
    val rt = parts.nas() ?: return
    val staged = ArrayList<String>()
    for (share in runCatching { parts.shares() }.getOrDefault(emptyList())) {
      // R5: un testo letto sull'anteprima di un altro dispositivo si pubblica solo nella share di quell'anteprima.
      if (!from.validFor(share)) continue
      if (key !in rt.content.owned(share, listOf(key))) continue
      try {
        rt.publisher.stage(share, listOf(AiEntry.Text(key, body)))
        staged += share
      } catch (e: IOException) {
        // Resta salvato qui: la prossima tappa del calcolo lo rimette nell'outbox senza rileggerlo (`restage`).
      }
    }
    if (staged.isEmpty()) return
    graphScope.launch {
      for (share in staged) runCatching { rt.publisher.flush(share, FlushReason.PERIODIC) }
    }
  }

  /** L'ultimo stato visto, anche da un altro processo (IX-2): la pagina non parte piu' da "non analizza le foto". */
  val statusMemory = AiStatusMemory(File(noBackup, AiStatusMemory.FILE))

  private val statusFlow = MutableStateFlow<AiStatus>(statusMemory.load() ?: AiStatus.Idle(null))

  /**
   * Lo stato del calcolo per la pagina Ricerca e modelli (§1.5). Parte dall'ultimo stato ricordato, o da "fermo, niente da fare": il ruolo
   * spento lo dice `AppIndexerControl` (`Off`), non questo flusso.
   */
  val status: StateFlow<AiStatus> = statusFlow.asStateFlow()

  /**
   * IX-2: com'e' finita una tappa, in parole della pagina. Le attese delle regole che fermano una tappa **prima** di partire non passano dal
   * motore e non arrivavano mai alla pagina; il motore dice "regole" anche per il caldo e per il tempo finito. Qui il motivo vero (caldo,
   * rete, NAS) e "riprendo fra poco" per una tappa finita per tempo. Si ricorda su file: la pagina lo ritrova in un altro processo.
   */
  fun noteStage(outcome: StageOutcome) {
    val note: AiStatus? = when (outcome) {
      is StageOutcome.Waiting -> AiStatus.Waiting(AiStatusMemory.waitReasonOf(outcome.reason))
      is StageOutcome.Stopped -> AiStatus.Waiting(AiStatusMemory.waitReasonOf(outcome.reason))
      is StageOutcome.Yielded -> AiStatus.Waiting(AiWaitReason.NEXT_STAGE)
      is StageOutcome.NeedsConfirmation -> AiStatus.Waiting(AiWaitReason.RULES)
      is StageOutcome.Finished -> null
    }
    // Un problema del motore o dello spazio resta quello che e' (e' piu' utile di "in attesa").
    if (note != null && statusFlow.value !is AiStatus.Problem) statusFlow.value = note
    statusMemory.save(statusFlow.value)
  }

  private val engineLock = Mutex()
  private var engine: Pair<NasAiRuntime, AiComputeEngine>? = null

  @Volatile private var stopped = false

  /** Il calcolo sulla parte `ai` del motore del NAS di adesso (un motore nuovo se il NAS e' ripartito). Null senza NAS. */
  private suspend fun engineFor(rt: NasAiRuntime): AiComputeEngine = engineLock.withLock {
    engine?.takeIf { it.first === rt }?.second?.let { return@withLock it }
    val computeParts = AiComputeParts(
      roles = roles,
      units = rt.units,
      priority = NoUnitPriority,
      candidates = CatalogAiCandidates(rt.work, { textReadingHere.value }, phone),
      buffer = buffer,
      fetcher = rt.fetcher,
      doneByOthers = rt.classes,
      publisher = rt.publisher,
      results = results,
      images = images,
      reader = { parts.reader },
      inputs = inputs,
      // P11b (§2.2, D10): le anteprime dai byte del telefono per i media del solo telefono (e per quelli del NAS che sono anche qui); la
      // preparazione controlla che i byte siano quelli del catalogo (D8). La sorgente degli screenshot (D12) viene dall'elenco dei mesi.
      preparer = phone?.let { p -> CheckedPreparer(rt.preparer, p::expectedQuickHash) },
      phone = phone ?: AiPhoneMedia.NONE,
      sources = parts.facts,
      localBodies = results,
      unsupported = unsupported,
      freeSpace = AiFreeSpace { noBackupDir.usableSpace },
      modelReady = { modelReady() },
    )
    val created = AiComputeEngine(computeParts, recipe)
    engine = rt to created
    graphScope.launch {
      created.status.collect {
        statusFlow.value = it
        statusMemory.save(it)
      }
    }
    created
  }

  private val noBackupDir = noBackup

  /** Il modello del calcolo (440M) c'e' ed e' verificato (`ModelFiles.open`: dimensione e SHA-256 a ogni apertura, D16). */
  private suspend fun modelReady(): Boolean =
    parts.modelReady?.invoke() ?: (parts.models.files.open(ModelCatalog.INDEX_440M.id)?.use { true } ?: false)

  /**
   * Una tappa del calcolo (D22): tutti gli ambiti di questo dispositivo in ordine (area privata propria, comune, solo telefono) con le
   * regole di [control]. Null senza NAS o a grafo fermo (niente da fare).
   */
  suspend fun runStage(budget: Duration, control: AiRunControl): List<Pair<AiScope, AiRunResult>>? {
    if (stopped) return null
    if (!indexer.value) {
      statusFlow.value = AiStatus.Off
      return emptyList()
    }
    val rt = parts.nas() ?: return null
    // L'indice locale e' dove il calcolo salva: in un processo nato per il lavoro si apre qui (idempotente).
    if (!search.open()) return null
    try {
      return engineFor(rt).runAll(budget, control)
    } finally {
      // IX-5: il 440M su GPU pesa circa 800 MB di PSS e non deve restare nel processo della galleria a tappa finita (la prossima lo riapre).
      withContext(NonCancellable) { runCatching { images.release() } }
    }
  }

  /** Memoria bassa (`onTrimMemory`, IX-5): il motore delle immagini si rilascia (si riapre da solo al prossimo `embed`). */
  fun onTrimMemory() {
    graphScope.launch { runCatching { images.release() } }
  }

  /** Il lavoro che resta su questo dispositivo (§2.8, `AiBacklog`): null senza NAS. */
  suspend fun backlog(): dev.pampa.mycloudgallery.core.rules.estimate.AiWork? {
    val rt = parts.nas() ?: return null
    return dev.pampa.mycloudgallery.core.ai.compute.AiBacklog(roles, CatalogAiCandidates(rt.work, { textReadingHere.value }, phone), buffer, recipe).count()
  }

  /** Il contatore dei contatori del calcolo di adesso (Diagnostica). */
  fun computeCounters() = engine?.second?.counters

  /**
   * L'azzeramento (§1.4), **per primo** con `SearchGraph.stop`: niente parte piu', i motori si rilasciano (il lettore del testo con lo
   * spegnimento di DataTransport), il buffer si svuota. Poi l'eraser toglie i file. Idempotente.
   */
  suspend fun stop() {
    if (stopped) return
    stopped = true
    // AZ-1: da qui il motore delle immagini non si riapre piu' (una tappa in corso si ferma al primo `embed`), poi si rilascia.
    (images as StoppableImageEmbedder).stopped = true
    job.cancel()
    runCatching { images.release() }
    statusMemory.clear()
    runCatching { parts.releaseCutout() }
    runCatching { parts.shutdown().onTurnedOff() }
    runCatching { buffer.clear() }
  }

  companion object {
    /** `noBackupFilesDir/models`: modelli verificati, parziali, programmi compilati (P3, P4). */
    const val MODELS_DIR = "models"
    const val BUFFER_DIR = "ai-buffer"
  }
}
