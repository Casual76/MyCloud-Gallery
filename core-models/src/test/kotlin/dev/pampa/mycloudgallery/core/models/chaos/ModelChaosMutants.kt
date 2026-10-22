package dev.pampa.mycloudgallery.core.models.chaos

import dev.pampa.mycloudgallery.core.models.contract.EnsureResult
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelId
import dev.pampa.mycloudgallery.core.models.contract.ModelManager
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.models.contract.VerifiedModel
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.time.Duration

// Gli ERRORI INTRODOTTI APPOSTA dello scaricamento (ModelChaosMutationTest, riga P10 del §4.4): due difetti che un gestore dei modelli
// potrebbe avere, scritti come uno stack rotto che avvolge le porte del gestore sano (`ModelManager`, `ModelFiles`) e ne riproduce
// l'EFFETTO. Il codice di produzione non si tocca: come per le fasi 4 e 5, ogni difetto avvolge un pezzo dello stack e rompe una promessa.
// Gli altri due difetti della riga P10 (pubblicare prima di salvare, salvare dopo aver pubblicato) riguardano il calcolo e stanno nel banco
// di core-ai (`core.ai.chaos.AiChaosMutants`).
//
//  1. CARICARE PRIMA DI VERIFICARE: `open` consegna `models/<sha256>` senza ricalcolare dimensione e impronta (e senza il descrittore su cui
//     la verifica e' stata fatta): un file alterato dopo la promozione arriva al motore;
//  2. ACCETTARE UN PARZIALE: quando un passo finisce senza il modello (rete caduta, tempo finito), il file parziale viene promosso a
//     `models/<sha256>` com'e', senza controllare niente.

/** Uno stack rotto: lo stack sano con le porte avvolte da [wrap]. */
class ModelMutant(private val base: ModelStackFactory, val label: String, private val wrap: (ModelChaosWorld, ModelStack) -> ModelStack) : ModelStackFactory {
  override fun create(world: ModelChaosWorld): ModelStack = wrap(world, base.create(world))

  override fun toString(): String = "difetto iniettato: $label"
}

object ModelMutants {

  /** 1. Caricare prima di verificare. */
  fun loadBeforeVerify(base: ModelStackFactory = ModelStackFactory.REAL): ModelStackFactory =
    ModelMutant(base, "il caricamento non verifica dimensione e impronta") { world, real ->
      ModelStack(real.manager, UnverifiedFiles(world), real.shelf, real.refresh)
    }

  private class UnverifiedFiles(private val world: ModelChaosWorld) : ModelFiles {
    // Effetto: il file che c'e' in models/ si consegna com'e'. Niente descrittore: nessuna verifica e' stata fatta su quello che si carica.
    override suspend fun open(id: ModelId): VerifiedModel? =
      world.finalFile.takeIf { it.isFile }?.let { VerifiedModel(world.spec, it, System.nanoTime()) }
  }

  /** 2. Accettare un parziale. */
  fun acceptPartial(base: ModelStackFactory = ModelStackFactory.REAL): ModelStackFactory =
    ModelMutant(base, "un parziale si promuove a modello senza controllarlo") { world, real ->
      ModelStack(PromotingManager(real.manager, world.partialFile, world.finalFile), real.files, real.shelf, real.refresh)
    }

  private class PromotingManager(private val inner: ModelManager, private val partial: File, private val final: File) : ModelManager by inner {
    override suspend fun ensure(id: ModelId, trigger: ModelTrigger, budget: Duration): EnsureResult {
      val result = inner.ensure(id, trigger, budget)
      if (result !is EnsureResult.Ready && partial.isFile && partial.length() > 0 && !final.exists()) {
        Files.move(partial.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
      }
      return result
    }
  }
}
