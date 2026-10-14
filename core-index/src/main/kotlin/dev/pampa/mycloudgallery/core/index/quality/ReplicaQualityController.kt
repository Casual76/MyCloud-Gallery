package dev.pampa.mycloudgallery.core.index.quality

import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import dev.pampa.mycloudgallery.core.index.contract.ReplicaQuality
import dev.pampa.mycloudgallery.core.index.contract.ReplicaQualityState
import dev.pampa.mycloudgallery.core.index.replica.LocalReplica
import dev.pampa.mycloudgallery.core.index.replica.store.ReplicaStore
import dev.pampa.mycloudgallery.core.index.replica.store.SegmentState
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * La QUALITA' delle anteprime tenute sul telefono (D16, E-L6; docs/fase4-progetto.md §1.5 "ReplicaQualityState").
 *
 * - [set] salva il livello, mostra subito la **stima** di ogni livello (dai byte elencati nelle teste, conosciuti prima di
 *   scaricare) e segna "in convergenza"; non scarica ne' cancella niente da se'.
 * - La convergenza la fanno i giri di sincronizzazione, secondo le regole delle attivita': salendo, i segmenti delle classi
 *   nuove passano da `SKIPPED` a scaricati (`IndexSyncEngine`, con le classi di [syncClasses]); scendendo, [converge]
 *   toglie al massimo [maxReleasePerRound] di pacchi per giro (200 MB, §1.5), i piu' grandi prima.
 * - "In convergenza" finche' restano segmenti elencati delle classi del livello non scaricati o pacchi di classi fuori.
 */
class ReplicaQualityController(
  private val store: ReplicaStore,
  private val replica: LocalReplica,
  private val maxReleasePerRound: Long = MAX_RELEASE_BYTES_PER_ROUND,
) : ReplicaQuality {

  private val mutable = MutableStateFlow(ReplicaQualityState(QualityLevel.DEFAULT, emptyMap(), currentBytes = 0, converging = false))
  private val mutex = Mutex()

  override val state: StateFlow<ReplicaQualityState> = mutable

  val level: QualityLevel get() = mutable.value.level

  /** Le classi d'immagine da scaricare per il livello scelto (per `SyncScope.classes`). */
  fun syncClasses(): Set<SegmentClass> = level.classes

  /** All'avvio: rilegge il livello salvato e calcola lo stato. */
  suspend fun load() = mutex.withLock {
    val level = store.quality() ?: QualityLevel.DEFAULT
    replica.keptClasses = level.classes
    refreshLocked(level)
  }

  override suspend fun set(level: QualityLevel) = mutex.withLock {
    store.setQuality(level)
    replica.keptClasses = level.classes
    refreshLocked(level)
  }

  /** Un passo di convergenza verso il basso (E-L6): toglie al massimo [maxReleasePerRound]; restituisce i byte tolti. */
  suspend fun converge(): Long = mutex.withLock {
    val level = level
    val released = if (replica.bytesOutside(level.classes) > 0) replica.releaseOutside(level.classes, maxReleasePerRound) else 0L
    refreshLocked(level)
    released
  }

  /** Ricalcola stime e spazio (dopo un giro di sincronizzazione). */
  suspend fun refresh() = mutex.withLock { refreshLocked(level) }

  private suspend fun refreshLocked(level: QualityLevel) {
    val estimates = QualityLevel.entries.associateWith { replica.estimate(it) }
    val wanted = level.classes.intersect(DOWNLOADABLE)
    val toDownload = store.segmentsWhere(setOf(SegmentState.PENDING, SegmentState.SKIPPED), wanted).any { it.listed }
    val toRelease = replica.bytesOutside(level.classes) > 0
    mutable.value = ReplicaQualityState(level, estimates, replica.currentBytes(), converging = toDownload || toRelease)
  }

  companion object {
    /** §1.5: al massimo 200 MB tolti per giro. */
    const val MAX_RELEASE_BYTES_PER_ROUND: Long = 200L * 1000 * 1000

    /** Le classi d'immagine che la Fase 4 scarica (mai `analysis` ne' `frames`: Indicizzatori, Fase 6). */
    val DOWNLOADABLE: Set<SegmentClass> = setOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.HQ)
  }
}
