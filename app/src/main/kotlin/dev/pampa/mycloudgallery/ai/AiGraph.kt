package dev.pampa.mycloudgallery.ai

import android.content.Context
import dev.pampa.mycloudgallery.core.ai.compute.AiCompletion
import dev.pampa.mycloudgallery.core.ai.compute.AiComputeEngine
import dev.pampa.mycloudgallery.core.ai.compute.AiComputeParts
import dev.pampa.mycloudgallery.core.ai.contract.BufferKeepers
import dev.pampa.mycloudgallery.core.ai.compute.AiFreeSpace
import dev.pampa.mycloudgallery.core.ai.compute.AiInputOrigin
import dev.pampa.mycloudgallery.core.ai.compute.AiInputs
import dev.pampa.mycloudgallery.core.ai.compute.AiPhoneMedia
import dev.pampa.mycloudgallery.core.ai.compute.AiRunControl
import dev.pampa.mycloudgallery.core.ai.compute.BufferAnalysisSink
import dev.pampa.mycloudgallery.core.ai.compute.DiskAiBuffer
import dev.pampa.mycloudgallery.core.ai.contract.AiBackend
import dev.pampa.mycloudgallery.core.ai.contract.AiLive
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
import dev.pampa.mycloudgallery.core.index.contract.UnitPriority
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.index.contract.VideoFrame
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
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

import dev.pampa.mycloudgallery.search.SearchGraph
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
  /**
   * Pacchetto X: il verdetto del motore delle regole per una richiesta, com'e' adesso (in :app: `ActivityRulesEngine`). Serve a dire il
   * motivo **vero** di una pausa nello stato ridotto e a rispondere subito a "Analizza adesso" se le regole lo fermano. Null = non
   * valutabile qui (montaggi di prova): lo stato dice "in coda" senza inventare motivi.
   */
  val decide: (suspend (WorkRequest) -> RuleDecision?)? = null,
  /**
   * XS-7: gli eventi che cambiano il verdetto delle regole (impostazioni, stato del sistema: caldo, rete, carica; e il passare del tempo, che
   * apre e chiude la fascia). Ogni evento fa **una** rivalutazione del verdetto (mai il database, mai a ogni fotogramma): lo stato dell'analisi
   * dice cosi' il motivo di adesso, non quello dell'ultima tappa. Null = nessuno (montaggi di prova).
   */
  val decisionTriggers: Flow<Unit>? = null,
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

/**
 * XS-7: **quale richiesta** decide il motivo di una pausa. Dopo una tappa fermata e' la richiesta di quella tappa (un lavoro in background o una
 * richiesta dell'utente, e con quale conferma): il suo verdetto e' il motivo vero. Dopo una tappa finita, o prima di qualunque tappa, vale il
 * lavoro in background (quello che parte da solo).
 */
internal object AnalysisRequests {
  fun background(): WorkRequest = WorkRequest.Background(BackgroundActivity.AI_COMPUTE, writesToNas = true)

  /** La richiesta da valutare: quella dell'ultima tappa ferma, altrimenti il lavoro in background. */
  fun effective(paused: WorkRequest?): WorkRequest = paused ?: background()

  /** La richiesta da ricordare dopo [outcome]: una tappa finita non lascia niente di fermo, le altre ricordano la richiesta con cui giravano. */
  fun pausedAfter(outcome: StageOutcome, request: WorkRequest?): WorkRequest? = if (outcome is StageOutcome.Finished) null else request
}

/**
 * X-R1: uno stato che conta chi lo **guarda**: ogni `collect` aggiunge un osservatore finche' dura. Serve a legare al "qualcuno guarda" gli ascoltatori
 * che altrimenti resterebbero accesi per tutta la vita del grafo.
 */
@OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
private class WatchedStateFlow<T>(private val inner: StateFlow<T>, private val watchers: MutableStateFlow<Int>) : StateFlow<T> by inner {
  override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<T>): Nothing {
    watchers.update { it + 1 }
    try {
      inner.collect(collector)
    } finally {
      watchers.update { it - 1 }
    }
  }
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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

  /** Pacchetto X: i media chiesti adesso dall'utente ("Analizza adesso"), in un file locale: passano davanti nella coda del calcolo. */
  val urgent = AiUrgentFile(File(noBackup, AiUrgentFile.FILE))

  /**
   * Le urgenze come priorita' delle unita' (D12): il mese e la parte che contengono un media chiesto si prendono per primi, con il
   * meccanismo che c'e' gia' (`UnitPriority.visible`, chiavi ridotte). XS-2: sono al piu' [AiUrgentFile.PRIORITY_KEYS], le meno attese per
   * prime (a turno), e il selettore delle unita' lascia sempre posto ai mesi ordinari (`maxPriorityMonthsPerCall`).
   */
  private val urgentPriority: UnitPriority = UrgentUnitPriority(urgent, graphScope)

  /**
   * XS-2, XS-5: la pulizia delle richieste fatta dal **ciclo di lavoro** (a inizio e a fine tappa, e quando l'indice avanza): scadute, finite
   * secondo il criterio del calcolo ([AiCompletion]) o non piu' servibili qui. Non la fa piu' la risoluzione delle miniature della pagina.
   */
  internal val urgentSweep = AiUrgentSweep(urgent, ::requestDone, ::requestServable)

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

  private val liveFlow = MutableStateFlow(AiLive.EMPTY)

  /** Pacchetto X: il calcolo mentre scorre (media di adesso, ultimi finiti, prossimi), per la pagina "Analisi delle foto". */
  val live: StateFlow<AiLive> = liveFlow.asStateFlow()

  /**
   * Il lavoro che resta a questo dispositivo (foto + video), riletto a fine tappa come fa la pagina; null = non ancora letto. XS-7: scende di
   * uno a ogni media finito mentre la tappa gira, senza ricontare il database.
   */
  private val remaining = RemainingTracker()

  /** Il verdetto delle regole per il calcolo, rivalutato quando lo stato o il ruolo cambiano (mai a ogni fotogramma). */
  private val decisionFlow = MutableStateFlow<RuleDecision?>(null)

  /**
   * X-R1: quanti stanno **guardando** lo stato dell'analisi ([analysis]). Gli eventi dell'ambiente (caldo, rete, carica: ascoltatori di sistema)
   * si ascoltano solo mentre qualcuno guarda, non per tutta la vita del grafo: prima partivano alla costruzione, anche in un processo nato per
   * un lavoro e in ogni prova che monta il grafo, e un ascoltatore di sistema che non si registra lasciava un'eccezione in uno scope senza gestore.
   */
  private val watchers = MutableStateFlow(0)

  /** XS-7: l'ultimo verdetto delle regole per la richiesta che decide il motivo della pausa (per le prove). */
  internal val decision: StateFlow<RuleDecision?> get() = decisionFlow

  /**
   * Pacchetto X: l'**unico** stato ridotto dell'analisi per testata, nuvola e pagina "Analisi delle foto" (`AnalysisStatusReducers`).
   * Si aggiorna dagli eventi del motore; il lavoro che resta si rilegge a ogni tappa finita, come la pagina (IX-3).
   */
  val analysis: StateFlow<LibraryAnalysisStatus> = WatchedStateFlow(
    combine(indexer, statusFlow, remaining.state, search.engine.coverage, decisionFlow) { on, status, left, coverage, decision ->
      AnalysisStatusReducers.reduce(AnalysisStatusReducers.Facts(on, status, left, coverage, decision))
    }.stateIn(
      graphScope, SharingStarted.Eagerly,
      AnalysisStatusReducers.reduce(AnalysisStatusReducers.Facts(indexer.value, statusFlow.value, null, search.engine.coverage.value, null)),
    ),
    watchers,
  )

  init {
    graphScope.launch {
      // A ogni tappa finita (e all'avvio): il lavoro che resta e il motivo vero di un'eventuale pausa.
      statusFlow.collect { if (it !is AiStatus.Running) refreshAnalysisFacts() }
    }
    graphScope.launch {
      indexer.collect { refreshAnalysisFacts() }
    }
    // XS-7: il motivo di una pausa si aggiorna quando cambiano ambiente o impostazioni (caldo, rete, carica, fascia): una rivalutazione del
    // verdetto per evento (con un attimo di calma per le raffiche), mai il database e mai a ogni fotogramma. X-R1: solo **mentre qualcuno guarda**
    // lo stato; appena si comincia a guardare, una rivalutazione subito (lo stato puo' essere vecchio di ore), poi gli eventi.
    parts.decisionTriggers?.let { triggers ->
      graphScope.launch {
        watchers.map { it > 0 }.distinctUntilChanged().flatMapLatest { watched ->
          if (!watched) {
            emptyFlow()
          } else {
            flow {
              emit(DecisionTrigger.NOW)
              emitAll(triggers.map { DecisionTrigger.EVENT })
            }.catch { e ->
              // XS3-3: un errore degli ascoltatori di sistema (la registrazione rifiutata, un servizio che manca) non esce da qui senza gestore e non e' ingoiato: una
              // riga nel log (solo il tipo), l'ascolto si ferma e lo stato dice "non so" (nessun verdetto); ricomincia la prossima volta che qualcuno guarda.
              if (e is CancellationException) throw e
              GalleryLog.e(TAG, e) { "eventi dell'ambiente: errore (${e.javaClass.simpleName}), ascolto fermo fino al prossimo sguardo" }
              emit(DecisionTrigger.FAILED)
            }
          }
        }.collectLatest { trigger ->
          when (trigger) {
            DecisionTrigger.NOW -> refreshDecision()
            DecisionTrigger.EVENT -> {
              delay(DECISION_CALM_MILLIS)
              refreshDecision()
            }
            DecisionTrigger.FAILED -> decisionFlow.value = null
          }
        }
      }
    }
    // XS-5: un risultato arrivato dagli altri dispositivi (l'indice avanza) puo' chiudere una richiesta; lo riconosce il ciclo di lavoro, con il
    // criterio del calcolo, a indice fermo da un attimo (mai a ogni salvataggio).
    graphScope.launch {
      combine(search.index.version, urgent.state) { _, asked -> asked }.collectLatest { asked ->
        if (asked.isEmpty()) return@collectLatest
        delay(INDEX_CALM_MILLIS)
        quiet { urgentSweep.sweep(checkServable = false) }
      }
    }
  }

  private suspend fun refreshAnalysisFacts() {
    quiet { backlog() }
    refreshDecision()
  }

  /**
   * XS-7: il verdetto delle regole **della richiesta davvero eseguita**: se l'ultima tappa si e' fermata era un lavoro in background o una
   * richiesta dell'utente (e con quale conferma), e il motivo vero e' quello di quella richiesta, non quello di un'altra. Senza una tappa
   * ferma e' quello del lavoro in background.
   */
  private suspend fun refreshDecision() {
    val request = AnalysisRequests.effective(pausedRequest)
    decisionFlow.value = try {
      parts.decide?.invoke(request)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    }
  }

  /** La richiesta dell'ultima tappa che si e' fermata (null = nessuna ferma: vale il lavoro in background). */
  @Volatile private var pausedRequest: WorkRequest? = null

  private suspend fun quiet(block: suspend () -> Unit) {
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Una comodita': al giro dopo si riprova.
    }
  }

  /**
   * IX-2: com'e' finita una tappa, in parole della pagina. Le attese delle regole che fermano una tappa **prima** di partire non passano dal
   * motore e non arrivavano mai alla pagina; il motore dice "regole" anche per il caldo e per il tempo finito. Qui il motivo vero (caldo,
   * rete, NAS) e "riprendo fra poco" per una tappa finita per tempo. Si ricorda su file: la pagina lo ritrova in un altro processo.
   */
  fun noteStage(outcome: StageOutcome, request: WorkRequest? = null) {
    // XS-7: la richiesta della tappa che si e' fermata decide il motivo che si mostra (prima di aggiornare lo stato, che fa rileggere il verdetto).
    pausedRequest = AnalysisRequests.pausedAfter(outcome, request)
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
    // Lo stato puo' essere lo stesso di prima ma la richiesta no: il verdetto si rilegge comunque.
    graphScope.launch { refreshDecision() }
  }

  /**
   * Fase 8 (P13, D17): chi dice quali anteprime del buffer servono ancora ai volti (il buffer non butta cio' che i volti non hanno ancora analizzato). Lo mette
   * `FacesWiring` quando le persone si montano; di serie nessuno tiene niente (come prima). Letto a ogni tappa: un motore gia' creato lo vede.
   */
  @Volatile var keepers: BufferKeepers = BufferKeepers.NONE

  private val engineLock = Mutex()
  private var engine: Pair<NasAiRuntime, AiComputeEngine>? = null

  @Volatile private var stopped = false

  /** Il calcolo sulla parte `ai` del motore del NAS di adesso (un motore nuovo se il NAS e' ripartito). Null senza NAS. */
  private suspend fun engineFor(rt: NasAiRuntime): AiComputeEngine = engineLock.withLock {
    engine?.takeIf { it.first === rt }?.second?.let { return@withLock it }
    val computeParts = AiComputeParts(
      roles = roles,
      units = rt.units,
      // Pacchetto X: le unita' con un media chiesto adesso vengono per prime (D12), come i media sullo schermo per la preparazione.
      priority = urgentPriority,
      candidates = CatalogAiCandidates(rt.work, { textReadingHere.value }, phone, urgent::mediaOf),
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
      keepers = BufferKeepers { keys -> keepers.stillWanted(keys) },
      urgent = urgent,
      // XS-4: l'ambito che contiene un media chiesto si serve per primo; e se la sua unita' la lavora un altro dispositivo lo stato dice "in attesa".
      requestedIn = { scope, keys -> requestedIn(rt, scope, keys) },
    )
    val created = AiComputeEngine(computeParts, recipe)
    engine = rt to created
    remaining.observed(created.live.value.finished)
    graphScope.launch {
      created.live.collect {
        liveFlow.value = it
        // XS-7: un media finito in piu' = un media in meno da fare (senza ricontare il database).
        remaining.onFinished(it.finished)
      }
    }
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
  suspend fun runStage(budget: Duration, control: AiRunControl, only: Boolean = false): List<Pair<AiScope, AiRunResult>>? {
    if (stopped) return null
    if (!indexer.value) {
      statusFlow.value = AiStatus.Off
      return emptyList()
    }
    val rt = parts.nas() ?: return null
    // L'indice locale e' dove il calcolo salva: in un processo nato per il lavoro si apre qui (idempotente).
    if (!search.open()) return null
    // XS-2, XS-5: a inizio tappa il ciclo di lavoro toglie le richieste scadute, finite o non piu' servibili qui.
    quiet { urgentSweep.sweep(checkServable = true) }
    try {
      // XS-3: con [only] la tappa lavora solo i media chiesti dall'utente ("Analizza adesso") e poi si ferma.
      return engineFor(rt).runAll(budget, control, only)
    } finally {
      // IX-5: il 440M su GPU pesa circa 800 MB di PSS e non deve restare nel processo della galleria a tappa finita (la prossima lo riapre).
      withContext(NonCancellable) {
        runCatching { images.release() }
        // XS-5: a fine tappa anche cio' che un altro dispositivo ha finito nel frattempo.
        quiet { urgentSweep.sweep(checkServable = false) }
      }
    }
  }

  /** Di [keys], quelle che [scope] contiene: i file presenti di una share (regola M3), o per il telefono quelle che nessuna share ha. */
  private suspend fun requestedIn(rt: NasAiRuntime, scope: AiScope, keys: Set<ContentKey>): Set<ContentKey> {
    val shares = parts.shares()
    return when (scope) {
      is AiScope.Share -> rt.content.owned(scope.share, keys)
      AiScope.PhoneOnly -> {
        val owned = HashSet<ContentKey>()
        for (share in shares) owned += rt.content.owned(share, keys)
        keys - owned
      }
    }
  }

  /** Le share di questo dispositivo che contengono [key] (regola M3); vuoto se nessuna (un media del solo telefono, o chiave ignota). */
  private suspend fun ownedShares(rt: NasAiRuntime, key: ContentKey): List<String> =
    parts.shares().filter { key in rt.content.owned(it, listOf(key)) }

  /**
   * XS-5: la richiesta e' finita? **Il criterio del calcolo** ([AiCompletion]): vettore e, con la lettura del testo attiva, anche il testo,
   * con le ricette correnti, da cio' che e' salvato qui per quell'ambito (come `ItemWorker.plan`) e da cio' che altri hanno pubblicato nella
   * share. Un solo vettore con il testo ancora da leggere non e' "fatto".
   */
  private suspend fun requestDone(key: ContentKey, @Suppress("UNUSED_PARAMETER") media: MediaId?): Boolean = isDone(key)

  /**
   * L'**unico** criterio di "fatto" per [key] (XS-5): quello del calcolo ([AiCompletion]), per il visore e per il registro delle richieste.
   * Vettore e, con la lettura del testo attiva qui, anche il testo, con le ricette correnti.
   */
  suspend fun isDone(key: ContentKey): Boolean = doneCheck.isDone(key)

  /** Il criterio, con le porte vere di questo grafo (e finte nelle prove: `AiDoneCheckTest`). */
  private val doneCheck = AiDoneCheck(
    shares = { key -> parts.nas()?.let { ownedShares(it, key) }.orEmpty() },
    savedFor = { key, version, share -> results.savedFor(key, version, share) },
    doneByOthers = { share, key, version -> parts.nas()?.classes?.done(share, listOf(key), version)?.get(key).orEmpty() },
    recipe = recipe,
    reading = { textReadingHere.value },
  )

  /**
   * XS-2: la richiesta si puo' ancora servire qui? No se il media non c'e' piu', e' cambiato di versione, il suo formato non e' supportato,
   * o questo dispositivo non lo vede (ne' in una sua share ne' sul telefono). Null = non si sa (niente NAS, o una richiesta di prima che non
   * ricorda il media): non si toglie niente, scadra'.
   */
  private suspend fun requestServable(key: ContentKey, media: MediaId?): Boolean? {
    val rt = parts.nas() ?: return null
    val cat = parts.catalog() ?: return null
    if (AiClass.VEC in unsupported.classesOf(listOf(key))[key].orEmpty()) return false
    val shares = ownedShares(rt, key)
    for (share in shares) if (AiClass.VEC in unsupported.classesOf(listOf(key), share)[key].orEmpty()) return false
    if (media == null) return null
    val found = cat.media(media) ?: return false
    val version = found.versions.firstOrNull { it.id == found.currentVersionId } ?: return false
    if (runCatching { ContentKey.of(version.fingerprint) }.getOrNull() != key) return false
    if (shares.isNotEmpty()) return true
    return cat.phoneRefOf(found.id, version.id) != null
  }

  /** Memoria bassa (`onTrimMemory`, IX-5): il motore delle immagini si rilascia (si riapre da solo al prossimo `embed`). */
  fun onTrimMemory() {
    graphScope.launch { runCatching { images.release() } }
  }

  /** XS-7: il verdetto delle regole **adesso** per [request] (il motivo vero di una pausa); null se non si puo' valutare (montaggi di prova). */
  suspend fun decisionFor(request: WorkRequest): RuleDecision? = try {
    parts.decide?.invoke(request)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    null
  }

  /** Il lavoro che resta su questo dispositivo (§2.8, `AiBacklog`): null senza NAS. */
  suspend fun backlog(): dev.pampa.mycloudgallery.core.rules.estimate.AiWork? {
    val rt = parts.nas() ?: return null
    val work = dev.pampa.mycloudgallery.core.ai.compute.AiBacklog(roles, CatalogAiCandidates(rt.work, { textReadingHere.value }, phone), buffer, recipe).count()
    // Pacchetto X: chiunque rilegga il lavoro che resta alimenta lo stato ridotto (niente seconda lettura per la pagina).
    remaining.set(((work.photos + work.videos).coerceAtMost(Int.MAX_VALUE.toLong())).toInt())
    return work
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
    urgent.clear()
    runCatching { parts.releaseCutout() }
    runCatching { parts.shutdown().onTurnedOff() }
    runCatching { buffer.clear() }
  }

  /** XS3-3: che cosa fa rivalutare il verdetto delle regole: lo sguardo che comincia, un evento dell'ambiente, o il guasto dell'ascolto (-> "non so"). */
  private enum class DecisionTrigger { NOW, EVENT, FAILED }

  companion object {
    private const val TAG = "AiGraph"

    /** `noBackupFilesDir/models`: modelli verificati, parziali, programmi compilati (P3, P4). */
    const val MODELS_DIR = "models"
    const val BUFFER_DIR = "ai-buffer"

    /** XS-7: un attimo di calma prima di rivalutare le regole dopo un evento (le raffiche di cambi contano una volta). */
    const val DECISION_CALM_MILLIS: Long = 2_000L

    /** XS-5: l'indice fermo da un attimo prima di controllare se un altro dispositivo ha finito una richiesta. */
    const val INDEX_CALM_MILLIS: Long = 3_000L
  }
}
