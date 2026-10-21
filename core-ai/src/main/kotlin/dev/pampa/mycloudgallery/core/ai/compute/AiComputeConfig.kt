package dev.pampa.mycloudgallery.core.ai.compute

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * I numeri del calcolo (docs/fase6-progetto.md §2.2, D22, E-A5). Valori del progetto; i test li stringono.
 *
 * | Voce | Valore | Fonte |
 * |---|---|---|
 * | [batch] | 20 media fra due controlli delle regole | E-A5 ("lotti da 5 invece di 20") |
 * | [reducedBatch] | 5 col caldo (`Pace.REDUCED`) | E-A5 |
 * | [reducedPause] | 2 s fra due lotti col caldo | E-A5, `StageConfig.reducedPaceDelay` |
 * | [fetchBatch] | 50 anteprime per scaricamento | §2.2, riga "Ingressi" |
 * | [keepAliveEvery] | 20 minuti (orologio monotono) | F4-D13, D9 |
 * | [maxUnitsPerRun] | unita' al massimo per tappa | tetto di sicurezza (una tappa dura 8 minuti) |
 * | [localBatchLimit] | candidati letti dal catalogo per il giro solo-buffer e per il telefono | E-A6 |
 */
data class AiComputeConfig(
  val batch: Int = 20,
  val reducedBatch: Int = 5,
  val reducedPause: Duration = 2.seconds,
  val fetchBatch: Int = 50,
  val keepAliveEvery: Duration = 20.minutes,
  val maxUnitsPerRun: Int = 500,
  val localBatchLimit: Int = 2_000,
) {
  init {
    require(batch > 0 && reducedBatch in 1..batch) { "Lotti non validi." }
    require(fetchBatch > 0 && maxUnitsPerRun > 0 && localBatchLimit > 0) { "Tetti non validi." }
    require(!reducedPause.isNegative() && keepAliveEvery.isPositive()) { "Durate non valide." }
  }

  companion object {
    val DEFAULT = AiComputeConfig()
  }
}
