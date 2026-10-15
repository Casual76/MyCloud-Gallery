package dev.pampa.mycloudgallery.core.nasops.chaos

import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import java.util.EnumMap
import kotlin.random.Random

// La prova delle 500 interruzioni (docs/fase4-progetto.md §5.5, P15): le INTERRUZIONI. Un'interruzione e' un colpo che cade a un punto
// preciso di una operazione: la connessione che cade, la sessione che scade, il socket che si ferma, o la MORTE DEL PROCESSO. Ogni colpo
// scatta PRIMA che il NAS (o il diario) abbia applicato la chiamata, oppure DOPO (la modifica c'e', la risposta si perde).
//
// Questo file non sa niente di SMB ne' di Room: sono i punti di aggancio (`Primitive`), le regole (`FaultRule`) e l'esecutore dei colpi
// (`Injector`). I decoratori che lo chiamano stanno in ChaosWrappers.kt, e valgono sia sul NAS finto sia sul NAS vero.

/** Il processo e' morto qui. E' un `Error` apposta: il codice sotto prova non deve poterlo trattare come un guasto qualunque. */
class ProcessDeath(val point: Int, val primitive: Primitive, val moment: Moment) :
  Error("morte del processo al punto $point ($primitive, $moment)", null, false, false)

/** Un'incarnazione del processo: dopo la morte ogni chiamata fatta dai suoi oggetti si ferma, come se il processo non ci fosse piu'. */
class Incarnation(val number: Int) {
  @Volatile var dead: Boolean = false
    internal set

  @Volatile private var diedAt: ProcessDeath? = null

  internal fun die(death: ProcessDeath): Nothing {
    dead = true
    diedAt = death
    throw death
  }

  /** Chiamata dopo la morte (un finally, una coroutine rimasta): non tocca niente, ripete la stessa morte. */
  internal fun checkAlive() {
    diedAt?.let { throw it }
  }
}

/** Il momento del colpo rispetto alla chiamata. */
enum class Moment { BEFORE, AFTER }

/** Che colpo cade. */
enum class FaultKind(val label: String) {
  /** Wi-Fi perso, Samba riavviato: la chiamata fallisce con `Timeout`. */
  DROP("connessione caduta"),

  /** `USER_SESSION_DELETED`: la richiesta risponde `SessionExpired`. */
  EXPIRE_SESSION("sessione scaduta"),

  /** Il socket non risponde piu' (E-R14): la chiamata fallisce con `Stalled`. */
  STALL("socket fermo"),

  /** Il processo viene ucciso (`am kill`, memoria, riavvio): niente prosegue, si riparte dal diario. */
  KILL("morte del processo"),
}

/**
 * I punti dove un colpo puo' cadere: tutte le chiamate al NAS (scrittura e lettura) e tutte le scritture del diario. [side] dice se un
 * colpo PRIMA e uno DOPO sono la stessa cosa (le letture non cambiano niente).
 */
enum class Primitive(val side: Side) {
  // Primitive di scrittura (NasFileOps)
  MKDIRS(Side.NAS_WRITE),
  RENAME(Side.NAS_WRITE),
  COPY_TO_TEMP(Side.NAS_WRITE),
  OPEN_TEMP(Side.NAS_WRITE),
  WRITE_TEMP(Side.NAS_WRITE),
  FINISH_TEMP(Side.NAS_WRITE),
  PUBLISH_TEMP(Side.NAS_WRITE),
  TRASH_MOVE(Side.NAS_WRITE),
  DELETE_IF_UNCHANGED(Side.NAS_WRITE),
  DELETE_OWN_TEMP(Side.NAS_WRITE),
  RMDIR_IF_EMPTY(Side.NAS_WRITE),

  // Letture (NasMediaReader, NasRandomFile) e spazio
  SPACE(Side.NAS_READ),
  LIST(Side.NAS_READ),
  STAT(Side.NAS_READ),
  OPEN(Side.NAS_READ),
  READ_ALL(Side.NAS_READ),
  READ(Side.NAS_READ),

  // Scritture del diario (OpsStore): la morte puo' cadere prima o dopo che il database le abbia scritte
  STORE_CREATE(Side.STORE),
  STORE_UPDATE_ITEM(Side.STORE),
  STORE_SET_OP_STATE(Side.STORE),
  STORE_RECORD_TRASH(Side.STORE),
  STORE_MARK_TRASH(Side.STORE),
}

enum class Side { NAS_WRITE, NAS_READ, STORE }

/** Dove scatta una regola: all'n-esima chiamata al NAS, alla n-esima scrittura del diario, o alla n-esima chiamata di una primitiva. */
sealed interface Trigger {
  data class NasCall(val n: Int) : Trigger

  data class StoreWrite(val n: Int) : Trigger

  data class OfPrimitive(val primitive: Primitive, val n: Int) : Trigger
}

/** Una regola: scatta una volta sola. */
class FaultRule(val trigger: Trigger, val moment: Moment, val kind: FaultKind) {
  @Volatile var used: Boolean = false
    internal set

  override fun toString(): String = "$kind $moment @ $trigger"
}

/** Un colpo scattato, per il racconto di un giro che fallisce e per le statistiche. */
data class FiredFault(val point: Int, val primitive: Primitive, val moment: Moment, val kind: FaultKind, val applied: Boolean) {
  override fun toString(): String = "#$point $primitive ${kind.label} ${if (moment == Moment.BEFORE) "prima" else "dopo"}"
}

/**
 * I colpi di un giro. Le chiamate si contano solo a colpi armati ([arm]): il caricamento iniziale e le verifiche, che il test fa per
 * conto suo, non contano e non vengono mai colpite.
 */
class Injector(
  private val stats: ChaosStats? = null,
  /** Cosa fare quando la connessione cade davvero (sul NAS vero: invalidare la sessione, cosi' la prossima chiamata si riconnette). */
  private val onConnectionLost: suspend () -> Unit = {},
) {
  @Volatile var armed: Boolean = false
    private set

  private var rules: List<FaultRule> = emptyList()

  /** Le primitive che non si colpiscono e non si contano (difetti noti del codice sotto prova, dichiarati dal suo [ChaosStackFactory]). */
  var excluded: Set<Primitive> = emptySet()

  private var nasCalls = 0
  private var storeWrites = 0
  private var points = 0
  private val perPrimitive = EnumMap<Primitive, Int>(Primitive::class.java)

  /** I colpi scattati, in ordine (in tutta la vita dell'iniettore, non solo dell'ultimo [load]). */
  val fired: MutableList<FiredFault> = ArrayList()

  /** Quante chiamate (per tipo) sono passate a colpi armati dall'ultimo [load]. */
  val callsSeen: Map<Primitive, Int> get() = perPrimitive

  /** Ricomincia i conteggi con nuove regole e arma l'iniettore: da qui le chiamate contano e i colpi scattano. */
  fun load(newRules: List<FaultRule>) {
    rules = newRules
    nasCalls = 0
    storeWrites = 0
    points = 0
    perPrimitive.clear()
    armed = true
  }

  /** Le regole non ancora scattate (per il racconto di un giro). */
  fun pending(): List<FaultRule> = rules.filter { !it.used }

  fun disarm() {
    armed = false
  }

  /** Una chiamata al NAS che restituisce un [NasResult]. */
  suspend fun <T> nas(primitive: Primitive, incarnation: Incarnation, block: suspend () -> NasResult<T>): NasResult<T> {
    incarnation.checkAlive()
    if (!armed || primitive in excluded) return block()
    val (nasIndex, primitiveIndex) = countNas(primitive)
    stats?.call(primitive)
    val before = pick(primitive, nasIndex, 0, primitiveIndex, Moment.BEFORE)
    if (before != null) return hit(before, primitive, Moment.BEFORE, incarnation, applied = false)
    val result = block()
    incarnation.checkAlive()
    val after = pick(primitive, nasIndex, 0, primitiveIndex, Moment.AFTER)
    // La risposta si perde: la modifica (se c'era) e' avvenuta, chi chiama non lo sa.
    if (after != null) return hit(after, primitive, Moment.AFTER, incarnation, applied = true)
    return result
  }

  /** Una lettura a flusso di un file aperto (`NasRandomFile.read`): il colpo diventa una `NasIoException`, come sul NAS vero. */
  suspend fun read(incarnation: Incarnation, block: suspend () -> Int): Int {
    incarnation.checkAlive()
    if (!armed || Primitive.READ in excluded) return block()
    val (nasIndex, primitiveIndex) = countNas(Primitive.READ)
    stats?.call(Primitive.READ)
    val before = pick(Primitive.READ, nasIndex, 0, primitiveIndex, Moment.BEFORE)
    if (before != null) {
      val result = hit<Unit>(before, Primitive.READ, Moment.BEFORE, incarnation, applied = false)
      throw NasIoException((result as NasResult.Err).error)
    }
    return block()
  }

  /** Una scrittura del diario: la morte cade prima o dopo che sia stata scritta. */
  suspend fun <T> store(primitive: Primitive, incarnation: Incarnation, block: suspend () -> T): T {
    incarnation.checkAlive()
    if (!armed) return block()
    val storeIndex = ++storeWrites
    val primitiveIndex = bump(primitive)
    stats?.call(primitive)
    val point = ++points
    if (pick(primitive, 0, storeIndex, primitiveIndex, Moment.BEFORE) != null) {
      record(point, primitive, Moment.BEFORE, FaultKind.KILL, applied = false)
      incarnation.die(ProcessDeath(point, primitive, Moment.BEFORE))
    }
    val value = block()
    incarnation.checkAlive()
    if (pick(primitive, 0, storeIndex, primitiveIndex, Moment.AFTER) != null) {
      record(point, primitive, Moment.AFTER, FaultKind.KILL, applied = true)
      incarnation.die(ProcessDeath(point, primitive, Moment.AFTER))
    }
    return value
  }

  private fun countNas(primitive: Primitive): Pair<Int, Int> {
    nasCalls++
    points++
    return nasCalls to bump(primitive)
  }

  private fun bump(primitive: Primitive): Int {
    val next = (perPrimitive[primitive] ?: 0) + 1
    perPrimitive[primitive] = next
    return next
  }

  /** La prima regola non ancora usata che scatta a questo punto, in questo momento. */
  private fun pick(primitive: Primitive, nasIndex: Int, storeIndex: Int, primitiveIndex: Int, moment: Moment): FaultRule? {
    for (rule in rules) {
      if (rule.used || rule.moment != moment) continue
      val matches = when (val t = rule.trigger) {
        is Trigger.NasCall -> primitive.side != Side.STORE && t.n == nasIndex
        is Trigger.StoreWrite -> primitive.side == Side.STORE && t.n == storeIndex
        is Trigger.OfPrimitive -> t.primitive == primitive && t.n == primitiveIndex
      }
      // Per le scritture del diario esiste solo la morte; per una lettura "dopo" e' uguale a "prima": non si spreca un colpo.
      if (!matches) continue
      if (primitive.side == Side.NAS_READ && moment == Moment.AFTER && rule.kind != FaultKind.KILL) continue
      rule.used = true
      return rule
    }
    return null
  }

  private suspend fun <T> hit(rule: FaultRule, primitive: Primitive, moment: Moment, incarnation: Incarnation, applied: Boolean): NasResult<T> {
    record(points, primitive, moment, rule.kind, applied)
    return when (rule.kind) {
      FaultKind.DROP -> {
        onConnectionLost()
        NasResult.Err(NasError.Timeout)
      }
      FaultKind.EXPIRE_SESSION -> NasResult.Err(NasError.SessionExpired)
      FaultKind.STALL -> {
        onConnectionLost()
        NasResult.Err(NasError.Stalled)
      }
      FaultKind.KILL -> incarnation.die(ProcessDeath(points, primitive, moment))
    }
  }

  private fun record(point: Int, primitive: Primitive, moment: Moment, kind: FaultKind, applied: Boolean) {
    val fault = FiredFault(point, primitive, moment, kind, applied)
    fired += fault
    stats?.fired(fault)
  }

  companion object {
    /**
     * Le regole casuali di un passo, ripetibili dal seme. La forma (§5.5): da 1 a [maxFaults] colpi; meta' puntati a una primitiva
     * precisa (cosi' anche quelle rare, come `deleteOwnTemp` o `rmdirIfEmpty`, vengono colpite), gli altri a un punto qualunque del
     * flusso delle chiamate al NAS o delle scritture del diario. [estimate] e' una stima dei punti del passo: i colpi oltre la fine
     * semplicemente non scattano.
     */
    fun randomRules(
      seed: Long,
      estimate: Int,
      maxFaults: Int = 6,
      usedPrimitives: List<Primitive> = emptyList(),
      mix: FaultMix = FaultMix.DEFAULT,
    ): List<FaultRule> {
      val random = Random(seed)
      val count = mix.faultCount(random, maxFaults)
      val rules = ArrayList<FaultRule>()
      repeat(count) {
        val storeFault = random.nextDouble() < mix.storeShare
        val moment = if (random.nextBoolean()) Moment.BEFORE else Moment.AFTER
        if (storeFault) {
          rules += FaultRule(Trigger.StoreWrite(1 + random.nextInt(maxOf(2, estimate))), moment, FaultKind.KILL)
        } else {
          val kind = mix.nasKind(random)
          val trigger = if (usedPrimitives.isNotEmpty() && random.nextDouble() < mix.targetedShare) {
            Trigger.OfPrimitive(usedPrimitives[random.nextInt(usedPrimitives.size)], 1 + geometric(random, 0.45))
          } else {
            Trigger.NasCall(1 + random.nextInt(maxOf(2, estimate)))
          }
          rules += FaultRule(trigger, moment, kind)
        }
      }
      return rules
    }

    private fun geometric(random: Random, p: Double): Int {
      var n = 0
      while (random.nextDouble() > p && n < 12) n++
      return n
    }
  }
}

/**
 * La distribuzione dei guasti (§5.5). Ogni giro ha da 1 a 6 colpi; di ciascuno si sceglie prima DOVE (una chiamata al NAS o una
 * scrittura del diario), poi COSA (per il NAS: connessione caduta, sessione scaduta, socket fermo, morte del processo) e QUANDO
 * (prima o dopo che la chiamata sia stata applicata, uno su due). Le scritture del diario ammettono solo la morte.
 */
class FaultMix(
  /** Quota dei colpi che cadono su una scrittura del diario (sempre morte del processo). */
  val storeShare: Double,
  /** Quota dei colpi sul NAS puntati a una primitiva precisa invece che a un punto qualunque. */
  val targetedShare: Double,
  private val dropWeight: Int,
  private val expireWeight: Int,
  private val stallWeight: Int,
  private val killWeight: Int,
  private val countWeights: List<Int>,
) {
  fun nasKind(random: Random): FaultKind {
    var pick = random.nextInt(dropWeight + expireWeight + stallWeight + killWeight)
    for ((kind, weight) in listOf(
      FaultKind.DROP to dropWeight,
      FaultKind.EXPIRE_SESSION to expireWeight,
      FaultKind.STALL to stallWeight,
      FaultKind.KILL to killWeight,
    )) {
      if (pick < weight) return kind
      pick -= weight
    }
    return FaultKind.DROP
  }

  fun faultCount(random: Random, max: Int): Int {
    var pick = random.nextInt(countWeights.sum())
    for ((index, weight) in countWeights.withIndex()) {
      if (pick < weight) return minOf(index + 1, max)
      pick -= weight
    }
    return 1
  }

  companion object {
    /**
     * Colpi per passo 1..6 come 25/25/20/15/10/5. Dove: 25% sulle scritture del diario (sempre morte del processo), 75% sulle chiamate al
     * NAS (meta' puntati a una primitiva del passo). Cosa, sulle chiamate al NAS: 40% connessione caduta, 12% sessione scaduta, 12% socket
     * fermo, 36% morte del processo. In totale circa meta' dei colpi sono morti del processo.
     */
    val DEFAULT = FaultMix(
      storeShare = 0.25,
      targetedShare = 0.5,
      dropWeight = 40,
      expireWeight = 12,
      stallWeight = 12,
      killWeight = 36,
      countWeights = listOf(25, 25, 20, 15, 10, 5),
    )

    /** Un colpo solo per passo (il runner contro il NAS vero: ogni passo vale una interruzione); le scritture del diario non costano rete. */
    val SINGLE = FaultMix(
      storeShare = 0.25,
      targetedShare = 0.5,
      dropWeight = 40,
      expireWeight = 10,
      stallWeight = 10,
      killWeight = 40,
      countWeights = listOf(1),
    )
  }
}

/** Le statistiche dei colpi e delle chiamate di un banco: dicono dove la prova ha davvero colpito. */
class ChaosStats {
  private val lock = Any()
  private val calls = EnumMap<Primitive, Int>(Primitive::class.java)
  private val cells = HashMap<Triple<Primitive, Moment, FaultKind>, Int>()

  var rounds: Int = 0
    private set
  var steps: Int = 0
    private set
  var restarts: Int = 0
    private set
  var midChecks: Int = 0
    private set
  var quiescentChecks: Int = 0
    private set
  var filesHashed: Long = 0
    private set
  var bytesHashed: Long = 0
    private set
  var cancels: Int = 0
    private set
  val stepKinds: MutableMap<String, Int> = HashMap()

  /** Quante volte e' comparso ciascun difetto noto dello stack sotto prova (il giro si ferma li'). */
  val knownDefectHits: MutableMap<String, Int> = HashMap()

  fun knownDefect(id: String) = synchronized(lock) { knownDefectHits.merge(id, 1, Int::plus) }

  fun call(primitive: Primitive) = synchronized(lock) { calls.merge(primitive, 1, Int::plus) }

  fun fired(fault: FiredFault) = synchronized(lock) { cells.merge(Triple(fault.primitive, fault.moment, fault.kind), 1, Int::plus) }

  fun round() = synchronized(lock) { rounds++ }

  fun step(kind: String) = synchronized(lock) {
    steps++
    stepKinds.merge(kind, 1, Int::plus)
  }

  fun restart() = synchronized(lock) { restarts++ }

  fun midCheck() = synchronized(lock) { midChecks++ }

  fun quiescentCheck() = synchronized(lock) { quiescentChecks++ }

  fun cancel() = synchronized(lock) { cancels++ }

  fun hashed(files: Int, bytes: Long) = synchronized(lock) {
    filesHashed += files
    bytesHashed += bytes
  }

  val totalFired: Int get() = synchronized(lock) { cells.values.sum() }

  fun firedBy(kind: FaultKind): Int = synchronized(lock) { cells.filterKeys { it.third == kind }.values.sum() }

  fun firedBy(moment: Moment): Int = synchronized(lock) { cells.filterKeys { it.second == moment }.values.sum() }

  fun firedAt(primitive: Primitive, moment: Moment): Int =
    synchronized(lock) { cells.filterKeys { it.first == primitive && it.second == moment }.values.sum() }

  fun callsOf(primitive: Primitive): Int = synchronized(lock) { calls[primitive] ?: 0 }

  /** La tabella primitiva x momento, con le chiamate viste: per il rapporto. */
  fun coverageTable(): String = synchronized(lock) {
    val builder = StringBuilder()
    builder.appendLine("primitiva              chiamate   prima   dopo   (di cui morte)")
    for (primitive in Primitive.entries) {
      val seen = calls[primitive] ?: 0
      if (seen == 0 && cells.keys.none { it.first == primitive }) continue
      val before = cells.filterKeys { it.first == primitive && it.second == Moment.BEFORE }.values.sum()
      val after = cells.filterKeys { it.first == primitive && it.second == Moment.AFTER }.values.sum()
      val kills = cells.filterKeys { it.first == primitive && it.third == FaultKind.KILL }.values.sum()
      builder.appendLine("%-22s %8d %7d %6d   %d".format(primitive.name, seen, before, after, kills))
    }
    builder.toString()
  }
}
