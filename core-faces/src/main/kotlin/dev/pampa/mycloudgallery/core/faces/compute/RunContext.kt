package dev.pampa.mycloudgallery.core.faces.compute

import dev.pampa.mycloudgallery.core.faces.contract.FacesPermit
import dev.pampa.mycloudgallery.core.faces.contract.FacesRunResult
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesStatus
import dev.pampa.mycloudgallery.core.faces.contract.FacesStop
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.flow.MutableStateFlow

/** Un volto o un salvataggio non vanno piu' avanti perche' l'archivio e' chiuso (generazione cambiata, chiave distrutta): la tappa si ferma. */
internal class ArchiveClosed : Exception("Archivio dei volti chiuso.")

/**
 * Lo stato di Persone **adesso** rispetto alla generazione [generation] con cui il calcolo e' partito (§2.3 "Cancello", R7): null = si
 * continua; la cancellazione in corso, una generazione diversa (attivazione nuova, cancellazione, dati locali di un'altra generazione) o
 * Persone non piu' attiva qui (spenta, in attesa dell'avviso, esclusa, voce sconosciuta) fermano la tappa.
 */
internal fun stateStop(parts: FacesComputeParts, generation: FaceGeneration): FacesStop? {
  val state = parts.switch.currentState()
  val named = state.generationOrNull
  val local = parts.localStore.local.value
  return when {
    local.erasing != null -> if (local.erasing == generation) FacesStop.ERASING else FacesStop.GENERATION_CHANGED
    named != null && named != generation -> FacesStop.GENERATION_CHANGED
    local.dataGeneration != null && local.dataGeneration != generation -> FacesStop.GENERATION_CHANGED
    state is FacesState.Erasing -> FacesStop.ERASING
    state is FacesState.On && state.here != HereState.EXCLUDED -> null
    else -> FacesStop.NOT_ACTIVE
  }
}

/** I conteggi di una tappa (solo numeri). */
internal class Tally {
  var analyzed = 0
  var faces = 0
  var skipped = 0
  var unsupported = 0
  var waiting = 0

  fun result(stop: FacesStop) = FacesRunResult(analyzed, faces, skipped, unsupported, waiting, stop)
}

/**
 * Lo stato di una tappa del calcolo dei volti (§2.3, riga "Cancello"): la generazione con cui e' partita, il tempo, le regole e il ritmo,
 * i conteggi. Vive per una chiamata di [FacesComputeEngine.run]. I controlli sono qui, in un posto solo:
 * - [stateGate]: **prima di ogni media**, con lo stato di adesso (R7: mai quello di quando la tappa e' partita); una generazione diversa, la
 *   cancellazione in corso o Persone non piu' attiva fermano la tappa;
 * - [gate]: **prima di ogni lotto**: in piu' il tempo, lo spazio, il ruolo e le regole di `AI_COMPUTE` (caldo, batteria, rete).
 */
internal class RunContext(
  val parts: FacesComputeParts,
  val config: FacesComputeConfig,
  val counters: FacesComputeCounters,
  val memory: FacesMemory,
  val rates: FacesRateMeter,
  val generation: FaceGeneration,
  val store: ComputeStore,
  private val budget: Duration,
  private val pauseFor: suspend (Duration) -> Unit,
  val status: MutableStateFlow<FacesStatus>,
) {
  private val startNanos = parts.clock.monotonicNanos()
  private var pace: FacesPermit.Go = FacesPermit.Go(reducedPace = false)
  val tally = Tally()

  /** Il NAS non ha risposto (prenotazione, anteprime): la tappa finisce con [FacesStop.NO_NAS] se non c'e' altro (E-A6). */
  var nasDown: Boolean = false

  fun elapsedNanos(): Long = parts.clock.monotonicNanos() - startNanos

  fun left(): Duration = budget - elapsedNanos().nanoseconds

  /** La recinzione (D34): sulle share vere tutto in locale, niente prenotazioni e niente pubblicazioni sul NAS. */
  fun fenced(): Boolean = (parts.switch.currentState() as? FacesState.On)?.here == HereState.LOCAL_ONLY

  /** Lo stato di Persone adesso e `faces-local`: null = si continua. */
  fun stateGate(): FacesStop? = stateStop(parts, generation)

  /** Il controllo prima di ogni media: lo stato e il tempo. */
  fun quickGate(): FacesStop? = stateGate() ?: if (left().isPositive()) null else FacesStop.BUDGET

  /** Il controllo prima di ogni lotto: stato, tempo, spazio (E-A17), ruolo (E-A13) e regole (E-A5). Null = si continua, con [pace] aggiornato. */
  suspend fun gate(): FacesStop? {
    quickGate()?.let { return it }
    if (parts.freeSpace.usableBytes() < FacesFreeSpace.MIN_FREE_BYTES) return FacesStop.STORAGE_LOW
    if (!parts.requirements.indexers().thisDevice) return FacesStop.NOT_INDEXER
    return when (val permit = parts.control.permit()) {
      is FacesPermit.Stop -> permit.stop
      is FacesPermit.Go -> {
        pace = permit
        null
      }
    }
  }

  /** Media per lotto: 20, col caldo 5 (E-A5). */
  fun batchSize(): Int = if (pace.reducedPace) config.reducedBatch else config.batch

  /** Col caldo, una pausa fra un lotto e l'altro. */
  suspend fun breath() {
    if (pace.reducedPace && config.reducedPause.isPositive()) pauseFor(config.reducedPause)
  }

  /** Dopo questi arresti la tappa non tocca piu' niente: niente raggruppamento, niente pubblicazione, nessuna prenotazione lasciata. */
  fun untouchable(stop: FacesStop?): Boolean =
    stop == FacesStop.GENERATION_CHANGED || stop == FacesStop.ERASING || stop == FacesStop.NOT_ACTIVE
}
