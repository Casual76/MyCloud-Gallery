package dev.pampa.mycloudgallery.core.prepare.engine

import dev.pampa.mycloudgallery.core.prepare.contract.PrepBudget
import kotlinx.coroutines.sync.Semaphore

/**
 * I cancelli condivisi da tutte le preparazioni di un [DefaultPreparer] (§2.4, rischio "memoria con foto enormi"):
 * - [heavy]: le decodifiche pesanti (sorgente oltre 24 MP) una alla volta (`PrepBudget.heavyDecodes`);
 * - [large]: le foto grandi tenute in memoria (oltre 16 MiB) una alla volta: due array da 64 MiB sono 128 MiB.
 */
internal class PrepGate(budget: PrepBudget) {
  val heavy = Semaphore(budget.heavyDecodes)
  val large = Semaphore(1)
}

/** Una finestra sulla memoria della preparazione, per i test: il picco delle bitmap allocate da una foto. */
fun interface PrepProbe {
  fun photoPeakBytes(bytes: Long)

  companion object {
    val NONE: PrepProbe = PrepProbe { }
  }
}
