package dev.pampa.mycloudgallery.core.ai.chaos

import dev.pampa.mycloudgallery.core.ai.testing.ProcessDeath
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking

// UN GIRO della prova delle interruzioni del calcolo (§5.5): le "vite" del processo di Anna una dopo l'altra sullo stesso "disco" (indice
// locale, file dei vettori, uscita e voci in attesa, buffer, cartella dei modelli). Il piano ([AiRoundPlan]) fa morire il processo a ogni
// salvataggio (prima del file, a meta' del record, prima della riga, dopo la riga), fa cadere la rete prima e dopo le chiamate al NAS,
// fa prendere le unita' a un altro dispositivo a meta', chiude e riapre la pubblicazione (F4-D31), uccide e manomette il modello. Gli
// invarianti si guardano dopo ogni morte, a fine di ogni passo, a fine giro e in una rilettura da un processo nuovo che non deve
// ricalcolare niente.

object AiChaosRound {
  /** Dopo tante vite il banco ripristina la rete e chiede di arrivare in fondo. */
  const val MAX_LIVES = 12

  fun run(plan: AiRoundPlan, factory: AiStackFactory = AiStackFactory.REAL, inspect: (AiChaosWorld) -> Unit = {}): AiRoundReport {
    val world = AiChaosWorld(plan, factory)
    val out = ArrayList<AiViolation>()
    try {
      runBlocking { drive(world, out) }
    } catch (t: Throwable) {
      // Un'eccezione che il codice di produzione non ha preso non e' una morte pianificata: e' un difetto da riferire con il seme.
      out += AiViolation(AiInvariant.REACHES_END, AiCheckPoint.END, "eccezione non prevista: ${t.javaClass.simpleName}: ${t.message}")
    } finally {
      world.stats.embedded = world.a.embedded
      world.a.harvest()
      inspect(world)
      world.close()
    }
    return AiRoundReport(plan, out, world.stats)
  }

  private suspend fun drive(world: AiChaosWorld, out: MutableList<AiViolation>) {
    val plan = world.plan
    val verifier = AiChaosVerifier(world)
    var tamperPending = plan.modelTamper
    var lives = 0
    while (lives < MAX_LIVES) {
      lives++
      world.stats.lives++
      if (lives > 1) {
        world.a.restart()
        // La morte nel mezzo di una scrittura del buffer lascia un temporaneo: il processo nuovo lo deve togliere (invariante 9).
        if (plan.orphanTempAfterDeath) File(world.bufferPath, "${"%064x".format(lives)}.analysis.tmp").writeBytes(ByteArray(7))
      }
      try {
        world.model.ensure()
        val results = world.a.engine.runAll(Duration.INFINITE, world.a.control)
        out += verifier.cheap(AiCheckPoint.AFTER_STEP)
        val complete = results.isNotEmpty() && results.all { it.second.stoppedBy == null }
        if (complete) {
          if (tamperPending) {
            tamperPending = false
            world.model.tamperOnce()
          } else {
            break
          }
        }
      } catch (e: ProcessDeath) {
        world.stats.deaths++
        out += verifier.cheap(AiCheckPoint.AFTER_DEATH)
      } catch (e: IOException) {
        // Un guasto che il calcolo non ha preso: per il banco e' come una morte (e il codice di produzione deve prenderli tutti: lo si legge dal giro).
        world.stats.deaths++
        out += AiViolation(AiInvariant.REACHES_END, AiCheckPoint.AFTER_DEATH, "un guasto del NAS e' uscito dal calcolo: ${e.javaClass.simpleName}")
        out += verifier.cheap(AiCheckPoint.AFTER_DEATH)
      }
      if (plan.otherBetweenLives) world.runOther()
    }

    // La rete torna e il disco ha spazio: il lavoro deve arrivare in fondo, anche con un altro dispositivo che lavora sulle stesse unita'.
    world.heal()
    world.a.restart()
    world.model.ensure(ModelTrigger.USER_NOW)
    repeat(6) {
      world.a.engine.runAll(Duration.INFINITE, world.a.control)
      world.runOther()
      out += verifier.cheap(AiCheckPoint.AFTER_STEP)
    }
    out += verifier.end(AiCheckPoint.END)

    // La rilettura finale, da un processo nuovo: la cartella si regge da sola e niente si ricalcola.
    val before = world.a.embedded
    world.a.restart()
    val reread = world.a.engine.runAll(Duration.INFINITE, world.a.control)
    if (reread.sumOf { it.second.computed } != 0 || world.a.embedded != before) {
      out += AiViolation(AiInvariant.NO_RECOMPUTE, AiCheckPoint.REREAD, "un processo nuovo a lavoro finito ha ricalcolato ${reread.sumOf { it.second.computed }} media")
    }
    out += verifier.end(AiCheckPoint.REREAD)
  }
}
