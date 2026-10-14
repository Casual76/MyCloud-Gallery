package dev.pampa.mycloudgallery.core.prepare.noop

import dev.pampa.mycloudgallery.core.index.contract.UnitPriority
import dev.pampa.mycloudgallery.core.index.contract.WorkItem
import dev.pampa.mycloudgallery.core.prepare.contract.PrepFailCode
import dev.pampa.mycloudgallery.core.prepare.contract.PrepInputs
import dev.pampa.mycloudgallery.core.prepare.contract.PrepOpen
import dev.pampa.mycloudgallery.core.prepare.contract.PrepResult
import dev.pampa.mycloudgallery.core.prepare.contract.PrepSpec
import dev.pampa.mycloudgallery.core.prepare.contract.PrepStop
import dev.pampa.mycloudgallery.core.prepare.contract.PreparationRun
import dev.pampa.mycloudgallery.core.prepare.contract.PrepareSource
import dev.pampa.mycloudgallery.core.prepare.contract.Preparer
import dev.pampa.mycloudgallery.core.prepare.contract.RunResult
import kotlin.time.Duration

// I null-object di core-prepare (release-safe): nessuna preparazione. Falliscono chiusi e senza contare tentativi.

/** Non prepara niente: ogni media risulta "interrotto" (si riprovera' quando ci sara' il preparatore vero). */
object NoPreparer : Preparer {
  override suspend fun prepare(source: PrepareSource, spec: PrepSpec): PrepResult =
    PrepResult.Failed(PrepFailCode.INTERRUPTED, retryable = true)
}

/** Nessun originale apribile. */
object NoPrepInputs : PrepInputs {
  override suspend fun open(share: String, item: WorkItem): PrepOpen = PrepOpen.Unavailable(PrepFailCode.NAS_UNREACHABLE)
}

/** Nessun giro di preparazione. */
object NoPreparationRun : PreparationRun {
  override suspend fun run(share: String, budget: Duration, priority: UnitPriority): RunResult =
    RunResult(units = 0, prepared = 0, failed = 0, handedOver = 0, bytesRead = 0, stoppedBy = PrepStop.NO_WORK)
}
