package dev.pampa.mycloudgallery.core.nas.testing

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

// Il piano dei guasti del simulatore SMB (docs/fase4-progetto.md §5.2, P1): "all'operazione n caduta della connessione
// PRIMA di applicarla o DOPO averla applicata (risposta persa), sessione scaduta, Stalled". E' cio' che serve ai test
// di "ripresa dopo un guasto a ogni chiamata" della Fase 4 (§4.3) e alla prova delle 500 interruzioni (P15).

/** Quando scatta un guasto rispetto all'operazione: prima che il server la applichi o dopo (risposta persa). */
enum class FaultPhase { BEFORE, AFTER }

/** Che cosa succede. */
sealed interface SimFault {
  /** La connessione cade (Wi-Fi perso, Samba riavviato): ogni chiamata in corso fallisce. */
  data object DropConnection : SimFault

  /** La sessione scade: la richiesta risponde `USER_SESSION_DELETED`. */
  data object ExpireSession : SimFault

  /** Il server non risponde piu' finche' la connessione non viene chiusa (socket muto, E-R14). */
  data object Stall : SimFault

  /** La richiesta risponde con questo NTSTATUS. */
  data class Status(val ntStatus: Long) : SimFault
}

/** Un guasto scattato: per i test che verificano dove il piano ha colpito. */
data class FiredFault(val index: Int, val op: SimOp, val phase: FaultPhase, val fault: SimFault)

/**
 * Un piano di guasti per [InMemorySmbBackend.faults]. Le operazioni si contano dalla prima (1) in poi, tutte insieme
 * oppure solo quelle di un tipo ([SimOp]). Ogni regola scatta una volta; [random] aggiunge guasti casuali ripetibili.
 *
 * ```
 * FaultPlan().at(3, FaultPhase.AFTER, SimFault.DropConnection, op = SimOp.RENAME) // la rinomina avviene, la risposta no
 * ```
 */
class FaultPlan {

  private class Rule(val n: Int, val phase: FaultPhase, val fault: SimFault, val op: SimOp?) {
    @Volatile var used = false
  }

  private class RandomRule(val random: Random, val probability: Double, val phase: FaultPhase, val fault: SimFault, val ops: Set<SimOp>?)

  private val rules = CopyOnWriteArrayList<Rule>()
  private val randomRules = CopyOnWriteArrayList<RandomRule>()
  private val total = AtomicInteger(0)
  private val perOp = java.util.concurrent.ConcurrentHashMap<SimOp, AtomicInteger>()

  /** I guasti scattati, in ordine. */
  val fired: MutableList<FiredFault> = CopyOnWriteArrayList()

  /** Le operazioni contate finora (tutte). */
  val operations: Int get() = total.get()

  /** Alla [n]-esima operazione (di tipo [op], o di qualunque tipo se null), nella fase [phase]. */
  fun at(n: Int, phase: FaultPhase, fault: SimFault, op: SimOp? = null): FaultPlan = apply {
    require(n >= 1) { "Le operazioni si contano da 1." }
    rules += Rule(n, phase, fault, op)
  }

  /** Con probabilita' [probability] a ogni operazione di [ops] (tutte se null), con un seme ripetibile. */
  fun random(seed: Long, probability: Double, phase: FaultPhase, fault: SimFault, ops: Set<SimOp>? = null): FaultPlan = apply {
    require(probability in 0.0..1.0)
    randomRules += RandomRule(Random(seed), probability, phase, fault, ops)
  }

  /** Conta una nuova operazione: restituisce (indice globale, indice per tipo), entrambi da 1. */
  internal fun begin(op: SimOp): Pair<Int, Int> =
    total.incrementAndGet() to perOp.getOrPut(op) { AtomicInteger(0) }.incrementAndGet()

  /** Il guasto da applicare all'operazione [indices] di tipo [op] nella fase [phase], o null. */
  internal fun check(op: SimOp, indices: Pair<Int, Int>, phase: FaultPhase): SimFault? {
    for (rule in rules) {
      if (rule.used || rule.phase != phase) continue
      val index = if (rule.op == null) indices.first else if (rule.op == op) indices.second else continue
      if (index == rule.n) {
        rule.used = true
        fired += FiredFault(indices.first, op, phase, rule.fault)
        return rule.fault
      }
    }
    for (rule in randomRules) {
      if (rule.phase != phase || (rule.ops != null && op !in rule.ops)) continue
      val hit = synchronized(rule) { rule.random.nextDouble() < rule.probability }
      if (hit) {
        fired += FiredFault(indices.first, op, phase, rule.fault)
        return rule.fault
      }
    }
    return null
  }
}

/**
 * Le latenze del simulatore per tipo di operazione, in millisecondi di **tempo virtuale** (si passano a
 * [InMemorySmbBackend.time], mai a un `sleep`). [INVENTORY] usa gli ordini di grandezza misurati sul NAS (inventario,
 * spike): un giro di rete in LAN, elenco da ~0,15 ms per voce (1,9 s per 12 mila file), lettura a flusso ~4 MB/s per
 * flusso (16 MB/s con 4), scrittura ~27 MB/s.
 */
data class SimLatencies(
  /** Ogni richiesta (CREATE, CLOSE, QUERY_INFO, SET_INFO...): un giro di rete. */
  val roundTripMs: Long = 0,
  /** Per ogni voce di un elenco, oltre al giro dell'apertura. */
  val perListEntryUs: Long = 0,
  /** Per MiB letto (una lettura casuale piccola costa almeno un giro). */
  val readPerMiBMs: Long = 0,
  /** Per MiB scritto. */
  val writePerMiBMs: Long = 0,
) {
  companion object {
    val NONE: SimLatencies = SimLatencies()

    val INVENTORY: SimLatencies = SimLatencies(roundTripMs = 2, perListEntryUs = 150, readPerMiBMs = 250, writePerMiBMs = 37)
  }
}

/**
 * L'ora di un NAS con lo scarto e la deriva di quello vero (avanti di ~55 s, +5 s al giorno), che puo' saltare
 * ([jump]: l'NTP sistemato). Si passa al simulatore come `nasTime = simTime::now`.
 *
 * @param elapsed il tempo trascorso (ms) secondo chi guida il test (tipicamente un orologio virtuale).
 */
class SimNasTime(
  private val trueStartMs: Long,
  private val elapsed: () -> Long,
  val offsetMs: Long = DEFAULT_OFFSET_MS,
  val driftMsPerDay: Long = DEFAULT_DRIFT_MS_PER_DAY,
) {
  @Volatile private var jumps = 0L

  /** L'ora del NAS adesso: vera + scarto + deriva accumulata + salti. */
  fun now(): Long {
    val e = elapsed()
    return trueStartMs + e + offsetMs + e * driftMsPerDay / DAY_MS + jumps
  }

  /** L'orologio del NAS salta di [deltaMs] (negativo = indietro). */
  fun jump(deltaMs: Long) {
    jumps += deltaMs
  }

  companion object {
    const val DEFAULT_OFFSET_MS: Long = 58_000
    const val DEFAULT_DRIFT_MS_PER_DAY: Long = 5_000
    private const val DAY_MS: Long = 24L * 60 * 60 * 1000
  }
}
