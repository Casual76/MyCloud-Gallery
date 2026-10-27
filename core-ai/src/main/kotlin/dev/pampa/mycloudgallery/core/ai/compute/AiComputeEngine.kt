package dev.pampa.mycloudgallery.core.ai.compute

import dev.pampa.mycloudgallery.core.ai.contract.AiBuffer
import dev.pampa.mycloudgallery.core.ai.contract.AiCandidates
import dev.pampa.mycloudgallery.core.ai.contract.AiComputeRun
import dev.pampa.mycloudgallery.core.ai.contract.AiDiag
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.contract.AiResults
import dev.pampa.mycloudgallery.core.ai.contract.AiRoles
import dev.pampa.mycloudgallery.core.ai.contract.AiRunResult
import dev.pampa.mycloudgallery.core.ai.contract.AiScope
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.AiStop
import dev.pampa.mycloudgallery.core.ai.contract.AiWaitReason
import dev.pampa.mycloudgallery.core.ai.contract.BufferKeepers
import dev.pampa.mycloudgallery.core.ai.contract.ImageEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.TextReader
import dev.pampa.mycloudgallery.core.index.ai.AiDoneClasses
import dev.pampa.mycloudgallery.core.index.ai.AiIndexPublisher
import dev.pampa.mycloudgallery.core.index.ai.AnalysisFetcher
import dev.pampa.mycloudgallery.core.index.contract.UnitPriority
import dev.pampa.mycloudgallery.core.index.contract.WorkUnits
import dev.pampa.mycloudgallery.core.prepare.contract.Preparer
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tutto cio' che serve al calcolo (le porte di P0, P2 e di [ComputePorts.kt]). Un oggetto solo, cosi' :app (P11) lo compone una
 * volta in `AiGraph` e i test ne cambiano un pezzo con `copy`.
 *
 * Il lettore del testo arriva **solo** come provider ([reader]): il codice OCR (ML Kit, P4) si raggiunge al primo uso e solo con la
 * lettura attiva qui (D41; regola di P0: una classe nostra che nomina ML Kit, caricata all'avvio, trascinerebbe le sue classi).
 */
data class AiComputeParts(
  val roles: AiRoles,
  val units: WorkUnits,
  val priority: UnitPriority,
  val candidates: AiCandidates,
  val buffer: AiBuffer,
  val fetcher: AnalysisFetcher,
  val doneByOthers: AiDoneClasses,
  val publisher: AiIndexPublisher,
  val results: AiResults,
  val images: ImageEmbedder,
  val reader: () -> TextReader,
  val inputs: AiInputs,
  val preparer: Preparer? = null,
  val phone: AiPhoneMedia = AiPhoneMedia.NONE,
  val sources: AiMediaSources = AiMediaSources.UNKNOWN,
  val localBodies: AiLocalBodies = AiLocalBodies.NONE,
  val unsupported: AiUnsupported = AiUnsupported.NONE,
  val freeSpace: AiFreeSpace = AiFreeSpace.UNLIMITED,
  /** Il modello del calcolo (440M) c'e' ed e' verificato (`ModelFiles`, P3). */
  val modelReady: suspend () -> Boolean = { true },
  /**
   * Fase 8 (D17; firma di P0, la chiamata in `ComputeSession.pruneFinished` e' di P4): chi tiene ancora un'anteprima del buffer (il
   * calcolo dei volti). Di serie nessuno: il buffer si svuota come nella Fase 6.
   */
  val keepers: BufferKeepers = BufferKeepers.NONE,
)

/**
 * IL CALCOLO AI (docs/fase6-progetto.md §2.2, D9-D11, D14, D21, D22; E-A5, E-A6, E-A9, E-A10, E-A12, E-A13, E-A17, E-I1, E-I2,
 * E-I11): vettori e testo letto calcolati **una volta per tutta la famiglia**, sulle anteprime (mai sugli originali del NAS), con le
 * prenotazioni delle unita' di lavoro, salvati **subito** per media e pubblicati firmati nella testa `ai` della share.
 *
 * Una tappa ([run]) lavora su un ambito:
 * - [AiScope.Share]: **solo** se la share e' fra le aree di questo dispositivo (`AiRoles.shares`: l'area privata del suo account e
 *   l'area comune, D14). Un'altra area privata non si tocca mai: nessuna chiamata a unita', NAS o pubblicatore. Con le regole in
 *   `LOCAL_BUFFER_ONLY` (E-A6) lavora sul buffer senza NAS; altrimenti sulle unita' (`WorkUnits` con `WorkPass.AI`).
 * - [AiScope.PhoneOnly]: i media del solo telefono, dai byte del telefono; risultati **solo locali**.
 *
 * Ordine sempre uguale (invarianti del §5.5): **salvare** ([AiResults]) **prima** di mettere nell'outbox ([AiIndexPublisher.stage]) e
 * di pubblicare. Dopo una morte del processo [AiResults.saved] dice cosa non rifare: nessun media salvato si ricalcola. Cio' che era
 * salvato ma non ancora nell'outbox si rimette li' da [AiLocalBodies], senza ricalcolo.
 *
 * [status] e' lo stato per la pagina Ricerca e modelli (§1.5): solo numeri e codici.
 */
class AiComputeEngine(
  private val parts: AiComputeParts,
  private val recipe: AiRecipe = AiRecipe.CURRENT,
  private val config: AiComputeConfig = AiComputeConfig.DEFAULT,
  val counters: AiComputeCounters = AiComputeCounters(),
  /** L'orologio monotono (D39): mai l'ora del telefono. */
  private val nanos: () -> Long = { System.nanoTime() },
  /** La pausa fra due lotti col caldo (E-A5); nei test e' il tempo virtuale. */
  private val pause: suspend (Duration) -> Unit = { delay(it) },
) : AiComputeRun {

  private val state = MutableStateFlow<AiStatus>(AiStatus.Idle(null))
  val status: StateFlow<AiStatus> get() = state

  private val computer = MediaComputer(parts.images, parts.reader, parts.inputs, recipe, counters)

  /** IX-7: gli errori passeggeri del motore per chiave in questo processo (un media che fallisce sempre non si riprova senza fine). */
  private val strikes: MutableMap<dev.pampa.mycloudgallery.core.model.ContentKey, Int> =
    java.util.Collections.synchronizedMap(object : LinkedHashMap<dev.pampa.mycloudgallery.core.model.ContentKey, Int>() {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<dev.pampa.mycloudgallery.core.model.ContentKey, Int>?): Boolean = size > 4_096
    })

  override suspend fun run(scope: AiScope, budget: Duration): AiRunResult = run(scope, budget, AiRunControl.ALWAYS)

  /** Una tappa con le regole di [control] (in :app: `StageScope` di `StagedRun`, vedi [AiStageBatch]). */
  suspend fun run(scope: AiScope, budget: Duration, control: AiRunControl): AiRunResult {
    val session = ComputeSession(parts, computer, recipe, config, counters, nanos, pause, budget, control, state, strikes)
    if (!parts.roles.indexer.value) {
      state.value = AiStatus.Off
      return session.result(AiStop.ROLE_OFF)
    }
    if (!parts.modelReady()) {
      state.value = AiStatus.ModelMissing
      return session.result(AiStop.MODEL_MISSING)
    }
    if (session.storageLow()) return session.result(AiStop.STORAGE_LOW)
    val result = when (scope) {
      is AiScope.Share -> {
        if (!isMine(scope.share)) {
          // D14: mai un'altra area privata. Non si chiama nessuna porta (ne' NAS, ne' catalogo, ne' pubblicatore).
          counters.foreignScopes.incrementAndGet()
          return session.result(null)
        }
        session.share(scope.share)
      }
      AiScope.PhoneOnly -> session.phoneOnly()
    }
    // IX-4: il modello c'era ed era verificato (controllato sopra), ma il motore non e' partito ne' su GPU ne' su CPU: non e' "serve il modello",
    // e' un problema del motore (con il tasto "Diagnostica"). Il codice lo conta il motore (`AI_ENGINE_INIT_FAILED`).
    if (result.stoppedBy == AiStop.MODEL_MISSING) state.value = AiStatus.Problem(AiDiag.AI_ENGINE_INIT_FAILED)
    return result
  }

  /**
   * Tutti gli ambiti di questo dispositivo in ordine (D14: prima l'area privata propria, poi l'area comune, poi i media del solo
   * telefono), finche' il tempo non finisce o le regole non fermano. Comodo per `AiComputeWorker` e per i test.
   */
  suspend fun runAll(budget: Duration, control: AiRunControl = AiRunControl.ALWAYS): List<Pair<AiScope, AiRunResult>> {
    val start = nanos()
    val out = ArrayList<Pair<AiScope, AiRunResult>>()
    val scopes = parts.roles.shares().map { AiScope.Share(it) } + AiScope.PhoneOnly
    for (scope in scopes) {
      val left = budget - (nanos() - start).nanoseconds
      if (!left.isPositive()) break
      val result = run(scope, left, control)
      out += scope to result
      if (result.stoppedBy != null && result.stoppedBy != AiStop.BUFFER_ONLY) break
    }
    return out
  }

  private suspend fun isMine(share: String): Boolean {
    val wanted = share.lowercase(Locale.ROOT)
    return parts.roles.shares().any { it.lowercase(Locale.ROOT) == wanted }
  }

  companion object {
    /** Lo stato quando il calcolo aspetta per [stop] (per la pagina Ricerca e modelli). */
    fun waitingFor(stop: AiStop): AiStatus = when (stop) {
      AiStop.ROLE_OFF -> AiStatus.Off
      AiStop.MODEL_MISSING -> AiStatus.ModelMissing
      // IX-4: poco spazio e' un problema con la sua azione ("Libera spazio"), non solo un'attesa.
      AiStop.STORAGE_LOW -> AiStatus.Problem(AiDiag.AI_STORAGE_LOW)
      AiStop.NAS_UNREACHABLE -> AiStatus.Waiting(AiWaitReason.NAS_UNREACHABLE)
      AiStop.RULES -> AiStatus.Waiting(AiWaitReason.RULES)
      // IX-2: la tappa e' finita per tempo, non per le regole: riprende fra poco.
      AiStop.BUDGET -> AiStatus.Waiting(AiWaitReason.NEXT_STAGE)
      AiStop.BUFFER_ONLY -> AiStatus.Waiting(AiWaitReason.NO_NETWORK)
    }
  }
}
