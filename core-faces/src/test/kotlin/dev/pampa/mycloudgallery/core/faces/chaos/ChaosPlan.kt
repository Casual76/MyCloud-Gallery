package dev.pampa.mycloudgallery.core.faces.chaos

import java.util.Random

// IL PIANO DI UN PASSO: quali colpi, dove e quando. Dipende solo dal seme del giro e dal tipo di passo: lo stesso giro ha gli stessi colpi.
// Come negli altri banchi: da 0 a 4 colpi a passo (25/25/25/15/10%), ognuno a una superficie che quel passo tocca davvero, alla chiamata n-esima,
// prima o dopo l'effetto, di un tipo scelto fra quelli che hanno senso per quella superficie.

/** I passi che l'utente (o il sistema) fa in un giro. */
internal enum class StepKind(val label: String, val weight: Int) {
  COMPUTE("calcolo", 26),
  COMPUTE_FLIP("calcolo con la generazione che cambia", 8),
  SYNC("giro dei dati e dei volti", 12),
  NAME("nome dato a una persona", 6),
  NEW_MEDIA("media nuovi", 5),
  ERASE("Disattiva e cancella", 12),
  REACTIVATE("riattivazione", 5),
  POWER_OFF("dispositivo spento", 4),
  POWER_ON("dispositivo che torna", 6),
  FREEZE("congelamento", 3),
  RECYCLE("cestino di rete", 2),
  TIME("il tempo passa", 6),
  NIGHTLY("manutenzione notturna", 9),
  EXCLUDE("esclusione di un dispositivo", 2),
  RESTART("morte del processo senza colpo", 4),
  ;

  companion object {
    fun pick(random: Random, allowed: (StepKind) -> Boolean): StepKind {
      val options = entries.filter(allowed)
      var roll = random.nextInt(options.sumOf { it.weight })
      for (kind in options) {
        roll -= kind.weight
        if (roll < 0) return kind
      }
      return options.last()
    }
  }
}

/** Che cosa il NAS del giro sa fare (il simulatore SMB e il NAS vero non hanno una porta della cancellazione a parte: ogni richiesta e' una chiamata SMB). */
internal class Capabilities(val separateEraseOps: Boolean, val noSpace: Boolean, val recycleBin: Boolean) {
  companion object {
    val MEMORY = Capabilities(separateEraseOps = true, noSpace = true, recycleBin = true)
    val SIMULATOR = Capabilities(separateEraseOps = false, noSpace = true, recycleBin = true)
    val REAL = Capabilities(separateEraseOps = false, noSpace = false, recycleBin = false)
  }
}

internal object ChaosPlan {

  /** Le superfici dove un salvataggio che fallisce e' un caso previsto dalla produzione (`StateFiles`: un `IOException`). */
  private val HANDLED_FAILURES = setOf(Surface.JOURNAL, Surface.LOCAL)

  private class Choice(val surface: Surface, val weight: Int)

  private fun table(kind: StepKind, caps: Capabilities): List<Choice> {
    val erase = if (caps.separateEraseOps) Surface.ERASE_OPS else Surface.NAS
    return when (kind) {
      StepKind.COMPUTE, StepKind.COMPUTE_FLIP -> listOf(
        Choice(Surface.NAS, 30), Choice(Surface.DB_BODY, 14), Choice(Surface.DB_UNITS, 8), Choice(Surface.DB_GROUPING, 8),
        Choice(Surface.OUTBOX, 10), Choice(Surface.REPLICA, 5), Choice(Surface.LOCAL, 2), Choice(Surface.DB_PEOPLE, 1),
      )
      StepKind.SYNC, StepKind.NIGHTLY -> listOf(
        Choice(Surface.PERSONAL, 20), Choice(Surface.REGISTRY, 12), Choice(Surface.NAS, 24), Choice(Surface.REPLICA, 14),
        Choice(Surface.OUTBOX, 10), Choice(erase, 6), Choice(Surface.DB_GROUPING, 6), Choice(Surface.JOURNAL, 4), Choice(Surface.LOCAL, 4),
      )
      StepKind.ERASE, StepKind.EXCLUDE -> listOf(
        Choice(Surface.JOURNAL, 28), Choice(Surface.LOCAL, 16), Choice(Surface.REGISTRY, 12), Choice(Surface.PERSONAL, 12),
        Choice(erase, 14), Choice(Surface.NAS, 10), Choice(Surface.DB_PEOPLE, 2), Choice(Surface.OUTBOX, 3),
      )
      StepKind.NAME -> listOf(Choice(Surface.DB_PEOPLE, 40), Choice(Surface.OUTBOX, 30), Choice(Surface.NAS, 10), Choice(Surface.REGISTRY, 5))
      StepKind.REACTIVATE -> listOf(Choice(Surface.LOCAL, 30), Choice(Surface.REGISTRY, 30), Choice(Surface.PERSONAL, 30), Choice(Surface.NAS, 10))
      StepKind.POWER_ON, StepKind.RESTART -> listOf(Choice(Surface.JOURNAL, 25), Choice(Surface.PERSONAL, 20), Choice(Surface.NAS, 25), Choice(erase, 15), Choice(Surface.LOCAL, 15))
      else -> emptyList()
    }
  }

  /**
   * Il numero di colpi di un passo: 0-4 (25/25/25/15/10%); sul NAS vero, dove ogni giro costa decine di secondi, 1-5 (10/25/30/20/15%) e le chiamate colpite
   * piu' vicine, cosi' le 400 interruzioni cadono in qualche decina di giri e non in qualche centinaio.
   */
  private fun count(random: Random, dense: Boolean): Int {
    val r = random.nextInt(100)
    if (dense) {
      return when {
        r < 10 -> 1
        r < 35 -> 2
        r < 65 -> 3
        r < 85 -> 4
        else -> 5
      }
    }
    return when {
      r < 25 -> 0
      r < 50 -> 1
      r < 75 -> 2
      r < 90 -> 3
      else -> 4
    }
  }

  /** I colpi di un passo di tipo [kind], per i dispositivi [actors] (indici). */
  fun blows(random: Random, kind: StepKind, actors: List<Int>, caps: Capabilities, databaseErrors: Boolean = false, dense: Boolean = false): List<Blow> {
    val choices = table(kind, caps)
    if (choices.isEmpty() || actors.isEmpty()) return emptyList()
    val out = ArrayList<Blow>()
    repeat(count(random, dense)) {
      val surface = pickSurface(random, choices)
      val device = actors[random.nextInt(actors.size)]
      out += blow(random, device, surface, caps, databaseErrors, dense)
    }
    if (kind == StepKind.COMPUTE_FLIP) {
      // Il cambio di generazione cade al salvataggio n-esimo di un corpo del dispositivo che calcola.
      out += Blow(actors.first(), Surface.DB_BODY, 1 + random.nextInt(4), Moment.BEFORE, FaultKind.FLIP, flip = random.nextInt(3))
    }
    return out
  }

  private fun pickSurface(random: Random, choices: List<Choice>): Surface {
    var roll = random.nextInt(choices.sumOf { it.weight })
    for (c in choices) {
      roll -= c.weight
      if (roll < 0) return c.surface
    }
    return choices.last().surface
  }

  private fun blow(random: Random, device: Int, surface: Surface, caps: Capabilities, databaseErrors: Boolean, dense: Boolean): Blow {
    val moment = if (random.nextBoolean()) Moment.BEFORE else Moment.AFTER
    val nth = when (surface) {
      Surface.NAS -> 1 + geometric(random, if (dense) 7 else 14, 60)
      Surface.ERASE_OPS -> 1 + geometric(random, if (dense) 3 else 5, 40)
      else -> 1 + geometric(random, 2, 12)
    }
    val kind = when {
      // Un salvataggio che non riesce: dove la produzione lo gestisce (i file di stato) o, con `databaseErrors`, ovunque (§F6, O1).
      surface.isStore -> if (random.nextInt(100) < 80 || !(databaseErrors || surface in HANDLED_FAILURES)) FaultKind.KILL else FaultKind.IO_FAIL
      surface == Surface.PERSONAL || surface == Surface.NAS || surface == Surface.ERASE_OPS -> networkKind(random, caps, surface)
      else -> FaultKind.KILL
    }
    // Un salvataggio che fallisce fallisce prima di scrivere; una morte cade prima o dopo.
    val m = if (kind == FaultKind.IO_FAIL) Moment.BEFORE else moment
    return Blow(device, surface, nth, m, kind)
  }

  /** Rete, sessione, stallo, spazio, morte: 40/12/12/8/28 sul NAS; la lettura dei dati personali non occupa spazio. */
  private fun networkKind(random: Random, caps: Capabilities, surface: Surface): FaultKind {
    val r = random.nextInt(100)
    return when {
      r < 40 -> FaultKind.DROP
      r < 52 -> FaultKind.EXPIRE_SESSION
      r < 64 -> FaultKind.STALL
      r < 72 -> if (caps.noSpace && surface == Surface.NAS) FaultKind.NO_SPACE else FaultKind.DROP
      else -> FaultKind.KILL
    }
  }

  private fun geometric(random: Random, mean: Int, cap: Int): Int {
    var n = 0
    val p = 1.0 / (mean + 1)
    while (n < cap && random.nextDouble() > p) n++
    return n
  }
}
