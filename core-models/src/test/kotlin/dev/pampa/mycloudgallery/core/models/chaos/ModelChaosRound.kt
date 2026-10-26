package dev.pampa.mycloudgallery.core.models.chaos

import dev.pampa.mycloudgallery.core.models.contract.EnsureResult
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.models.contract.ShelfResult
import dev.pampa.mycloudgallery.core.models.support.HttpsTestServer
import java.io.RandomAccessFile
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking

// UN GIRO della prova delle interruzioni dello scaricamento (§5.5): le "vite" del processo una dopo l'altra sulla stessa cartella `models/`,
// ognuna con un gestore nuovo; il piano ([ModelRoundPlan]) fa morire il processo a ogni salvataggio, prima e dopo le chiamate ai fetcher,
// fa rispondere `200` al posto di `206`, cambia il file sul NAS e sul server a meta', manomette il file pronto, toglie lo spazio e il
// cancello di rete. Gli invarianti si guardano dopo ogni morte, a fine di ogni passo, a fine giro e in una rilettura da un processo nuovo.

object ModelChaosRound {
  /** Dopo tante vite il banco ripristina la rete (come "la sera il Wi-Fi di casa torna") e chiede di arrivare in fondo. */
  const val MAX_LIVES = 10

  fun run(plan: ModelRoundPlan, server: HttpsTestServer, factory: ModelStackFactory = ModelStackFactory.REAL): ModelRoundReport {
    val world = ModelChaosWorld(plan, server)
    val violations = ArrayList<ModelViolation>()
    try {
      runBlocking { drive(world, factory, violations) }
    } catch (t: Throwable) {
      // Un'eccezione che il codice di produzione non ha preso non e' una morte pianificata: e' un difetto da riferire con il seme.
      violations += ModelViolation(ModelInvariant.REACHES_END, CheckPoint.END, "eccezione non prevista: ${t.javaClass.simpleName}: ${t.message}")
    } finally {
      world.stats.mismatches = world.counters.mismatch
      world.stats.nasOpens = world.log.opens.get()
      world.stats.nasReads = world.log.reads.get()
      world.stats.nasFaultsFired = world.nas.firedFaults
      world.close()
    }
    return ModelRoundReport(plan, violations, world.stats)
  }

  private suspend fun drive(world: ModelChaosWorld, factory: ModelStackFactory, out: MutableList<ModelViolation>) {
    val plan = world.plan
    val verifier = ModelChaosVerifier(world)
    val random = Random(plan.seed xor 0x5DEECE66DL)
    var tampered = false
    var lives = 0
    while (lives < ModelChaosRound.MAX_LIVES) {
      lives++
      world.stats.lives++
      val stack = factory.create(world)
      try {
        // Un processo nuovo guarda subito cio' che la morte ha lasciato: un caricamento a freddo e, a volte, il controllo all'avvio.
        out += verifier.load(stack.files, CheckPoint.AFTER_DEATH)
        if (random.nextInt(3) == 0) stack.refresh()
        val result = stack.manager.ensure(world.spec.id, ModelTrigger.RULES, 10.minutes)
        world.stats.steps++
        out += verifier.cheap(CheckPoint.AFTER_STEP)
        out += verifier.load(stack.files, CheckPoint.AFTER_STEP)
        if (result is EnsureResult.Ready) {
          if (plan.tamper && !tampered) {
            tampered = true
            tamper(world)
            // E-A11: un file alterato dopo la verifica si rifiuta al caricamento (e si toglie), poi il processo dopo lo riscarica.
            out += verifier.load(stack.files, CheckPoint.AFTER_STEP)
            out += verifier.cheap(CheckPoint.AFTER_STEP)
          } else {
            break
          }
        }
      } catch (e: ChaosModelDied) {
        world.stats.deaths++
        out += verifier.cheap(CheckPoint.AFTER_DEATH)
      }
    }

    // La rete torna: "Scarica ora" (che salta le quarantene delle sorgenti) deve portare il modello in fondo.
    world.heal()
    var stack = factory.create(world)
    repeat(3) {
      if (stack.manager.states.value[world.spec.id] !is ModelState.Ready) {
        stack.manager.ensure(world.spec.id, ModelTrigger.USER_NOW, 10.minutes)
        world.stats.steps++
        out += verifier.cheap(CheckPoint.AFTER_STEP)
      }
    }
    out += verifier.load(stack.files, CheckPoint.END)
    if (plan.shelfFailsFirst) world.nas.failNextShelfWrite()
    var shelf = stack.shelf.publish(world.spec, world.share)
    if (shelf is ShelfResult.Failed) shelf = stack.shelf.publish(world.spec, world.share)
    world.stats.shelfPublications++
    if (shelf != ShelfResult.Published && shelf != ShelfResult.AlreadyThere) {
      out += ModelViolation(ModelInvariant.REACHES_END, CheckPoint.END, "la copia sul NAS non e' stata pubblicata: $shelf")
    }
    out += verifier.end(CheckPoint.END) { stack.manager.states.value[world.spec.id] }

    // La rilettura finale, da un processo nuovo: la cartella si regge da sola.
    stack = factory.create(world)
    stack.refresh()
    out += verifier.load(stack.files, CheckPoint.REREAD)
    out += verifier.end(CheckPoint.REREAD) { stack.manager.states.value[world.spec.id] }
  }

  /** Un solo byte di `models/<sha256>` cambia (disco, o un altro processo): la verifica a ogni apertura deve accorgersene. */
  private fun tamper(world: ModelChaosWorld) {
    val file = world.finalFile
    if (!file.isFile) return
    world.stats.tampered++
    file.setWritable(true) // il file verificato e' in sola lettura (I1)
    RandomAccessFile(file, "rw").use {
      val at = (world.plan.seed % file.length().coerceAtLeast(1)).coerceAtLeast(0)
      it.seek(at)
      val old = it.read()
      it.seek(at)
      it.write(old xor 0x01)
    }
  }
}
