package dev.pampa.mycloudgallery.core.backup.chaos

import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import java.io.IOException
import java.util.EnumMap
import kotlin.random.Random

// La prova delle 500 interruzioni del BACKUP (docs/fase5-progetto.md §5.4, P6): le INTERRUZIONI. Un'interruzione e' un colpo che cade a un
// punto preciso del caricamento: la connessione che cade, la sessione che scade, il socket che si ferma, il NAS pieno, un file aperto dal PC,
// o la MORTE DEL PROCESSO (a ogni chiamata al NAS, a ogni scrittura del diario e del catalogo, a ogni lettura del telefono). Ogni colpo scatta
// PRIMA che il NAS (o il diario) abbia applicato la chiamata, oppure DOPO (la modifica c'e', la risposta si perde).
//
// Questo file non sa niente di SMB ne' di Room: sono i punti di aggancio ([Primitive]), le regole ([FaultRule]) e l'esecutore dei colpi
// ([Injector]). I decoratori che lo chiamano stanno in BackupChaosWrappers.kt, e valgono sia sul NAS finto, sia sul simulatore SMB, sia
// sull'area di prova del NAS vero. Stesso impianto del banco della 4B (core-nasops/.../chaos/ChaosFaults.kt), con i punti del backup.

/** Il processo e' morto qui. Un `Error` apposta: il codice sotto prova non deve poterlo trattare come un guasto qualunque. */
class ChaosDeath(val point: Int, val primitive: Primitive, val moment: Moment) :
  Error("morte del processo al punto $point ($primitive, $moment)", null, false, false)

/** Un'incarnazione del processo: dopo la morte ogni chiamata fatta dai suoi oggetti si ferma, come se il processo non ci fosse piu'. */
class Incarnation(val number: Int) {
  @Volatile var dead: Boolean = false
    internal set

  @Volatile private var diedAt: ChaosDeath? = null

  internal fun die(death: ChaosDeath): Nothing {
    dead = true
    diedAt = death
    throw death
  }

  /** Chiamata dopo la morte (un `finally`, una coroutine rimasta): non tocca niente, ripete la stessa morte. */
  internal fun checkAlive() {
    diedAt?.let { throw it }
  }
}

/** Il momento del colpo rispetto alla chiamata. */
enum class Moment { BEFORE, AFTER }

/** Che colpo cade. */
enum class FaultKind(val label: String, val bothMoments: Boolean = false) {
  /** Wi-Fi perso, Samba riavviato: la chiamata fallisce con `Timeout`. */
  DROP("connessione caduta", bothMoments = true),

  /** `USER_SESSION_DELETED`: la richiesta risponde `SessionExpired`. */
  EXPIRE_SESSION("sessione scaduta", bothMoments = true),

  /** Il socket non risponde piu' (E-R14): la chiamata fallisce con `Stalled`. */
  STALL("socket fermo", bothMoments = true),

  /** Il processo viene ucciso (`am kill`, memoria, riavvio): niente prosegue, si riparte dal diario. */
  KILL("morte del processo", bothMoments = true),

  /** NAS pieno a meta' di un file (E-R9): la scrittura risponde `NasFull`. */
  DISK_FULL("NAS pieno"),

  /** Il PC tiene aperto il file (E-N13, `SHARING_VIOLATION`): la primitiva risponde `Locked`. */
  LOCKED("file aperto dal PC"),

  /** Password rifiutata (E-R8): l'interruttore apre, il giro si ferma con `AUTH`. */
  AUTH("password rifiutata"),

  /** Il NAS restituisce byte diversi da quelli scritti (E-B18): un byte cambia in una scrittura, senza errore. */
  CORRUPT("byte alterati nella scrittura"),

  /** Una lettura del telefono fallisce (I/O), la riga e il timbro non cambiano. */
  IO_ERROR("lettura del telefono fallita"),

  /** L'utente tocca "Ferma" (o Android ferma il lavoro): il giro si ferma al passo dopo. */
  USER_STOP("l'utente tocca Ferma"),

  /** La share si congela per cambiamenti di massa (D32, E-B19): il cancello si chiude finche' il passo non finisce. */
  FREEZE("share congelata"),

  /** Il PC crea un file con il nome previsto della destinazione di un elemento aperto (E-B3). */
  PC_FILE("il PC crea un file con il nome previsto"),
  ;

  /** Un colpo che non fa fallire la chiamata: la azione avviene e la chiamata prosegue. */
  val isWorldEvent: Boolean get() = this == USER_STOP || this == FREEZE || this == PC_FILE || this == CORRUPT
}

enum class Side { NAS_WRITE, NAS_READ, DIARY, CATALOG, PHONE }

/**
 * I punti dove un colpo puo' cadere: tutte le chiamate al NAS (scrittura e lettura), le scritture del diario e del catalogo, le letture del
 * telefono. [kinds] sono i colpi che hanno senso in quel punto.
 */
enum class Primitive(val side: Side, val kinds: Set<FaultKind>) {
  // Primitive di scrittura (NasBackupOps)
  MKDIRS(Side.NAS_WRITE, NET + DISK_FULL_ONLY + WORLD),
  OPEN_TEMP(Side.NAS_WRITE, NET + DISK_FULL_ONLY + WORLD),
  REOPEN_TEMP(Side.NAS_WRITE, NET + LOCKED_ONLY + WORLD),
  WRITE_TEMP(Side.NAS_WRITE, NET + DISK_FULL_ONLY + CORRUPT_ONLY + WORLD),
  CHECKPOINT(Side.NAS_WRITE, NET + DISK_FULL_ONLY + WORLD),
  FINISH_TEMP(Side.NAS_WRITE, NET + DISK_FULL_ONLY + WORLD),
  COPY_TO_TEMP(Side.NAS_WRITE, NET + WORLD),
  PUBLISH_TEMP(Side.NAS_WRITE, NET + LOCKED_ONLY + WORLD),
  DELETE_OWN_TEMP(Side.NAS_WRITE, NET + LOCKED_ONLY + WORLD),

  // Letture (NasMediaReader, NasRandomFile) e spazio
  SPACE(Side.NAS_READ, NET),
  LIST(Side.NAS_READ, NET),
  STAT(Side.NAS_READ, NET + WORLD),
  OPEN(Side.NAS_READ, NET + WORLD),
  READ_ALL(Side.NAS_READ, NET + WORLD),
  READ(Side.NAS_READ, NET),

  // Scritture del diario e del catalogo: la morte puo' cadere prima o dopo che il database le abbia scritte
  DIARY_CREATE(Side.DIARY, KILL_ONLY),
  DIARY_UPDATE(Side.DIARY, KILL_ONLY),
  CATALOG_RECORD_HASH(Side.CATALOG, KILL_ONLY),
  CATALOG_ATTACH(Side.CATALOG, KILL_ONLY),
  CATALOG_MARK_PROBLEM(Side.CATALOG, KILL_ONLY),
  CATALOG_MARK_VERIFIED(Side.CATALOG, KILL_ONLY),

  // Il telefono
  PHONE_LOOKUP(Side.PHONE, KILL_ONLY),
  PHONE_OPEN(Side.PHONE, KILL_ONLY),
  PHONE_READ(Side.PHONE, KILL_ONLY + IO_ONLY),
  ;

  fun allows(kind: FaultKind, moment: Moment): Boolean {
    if (kind !in kinds) return false
    if (!kind.bothMoments && moment == Moment.AFTER) return false
    // Per una lettura "dopo" e' uguale a "prima" (non cambia niente): non si spreca un colpo, salvo la morte.
    if (side == Side.NAS_READ && moment == Moment.AFTER && kind != FaultKind.KILL) return false
    return true
  }
}

private val NET: Set<FaultKind> = setOf(FaultKind.DROP, FaultKind.EXPIRE_SESSION, FaultKind.STALL, FaultKind.KILL, FaultKind.AUTH)
private val DISK_FULL_ONLY: Set<FaultKind> = setOf(FaultKind.DISK_FULL)
private val LOCKED_ONLY: Set<FaultKind> = setOf(FaultKind.LOCKED)
private val CORRUPT_ONLY: Set<FaultKind> = setOf(FaultKind.CORRUPT)
private val KILL_ONLY: Set<FaultKind> = setOf(FaultKind.KILL)
private val IO_ONLY: Set<FaultKind> = setOf(FaultKind.IO_ERROR)
private val WORLD: Set<FaultKind> = setOf(FaultKind.USER_STOP, FaultKind.FREEZE, FaultKind.PC_FILE)

/** Dove scatta una regola. Con `>=`: una regola riservata a certe primitive scatta alla prima chiamata utile dal punto indicato in poi. */
sealed interface Trigger {
  /** Dalla n-esima chiamata al NAS (scrittura o lettura). */
  data class NasCall(val n: Int) : Trigger

  /** Dalla n-esima scrittura del diario o del catalogo. */
  data class StoreWrite(val n: Int) : Trigger

  /** Dalla n-esima chiamata al telefono. */
  data class PhoneCall(val n: Int) : Trigger

  /** Dalla n-esima chiamata di una primitiva. */
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

/** Le azioni "del mondo" che un colpo puo' chiedere (l'utente tocca Ferma, la share si congela, il PC crea un file). */
interface WorldActions {
  suspend fun userStop()

  suspend fun freeze()

  suspend fun pcFile()

  object NONE : WorldActions {
    override suspend fun userStop() = Unit

    override suspend fun freeze() = Unit

    override suspend fun pcFile() = Unit
  }
}

/**
 * I colpi di un giro. Le chiamate si contano solo a colpi armati ([load]): il caricamento iniziale e le verifiche, che il test fa per conto
 * suo, non contano e non vengono mai colpite.
 */
class Injector(
  private val stats: ChaosStats? = null,
  /** Cosa fare quando la connessione cade davvero (sul simulatore e sul NAS vero: invalidare la sessione, cosi' la chiamata dopo si riconnette). */
  private val onConnectionLost: suspend () -> Unit = {},
  private val actions: WorldActions = WorldActions.NONE,
) {
  @Volatile var armed: Boolean = false
    private set

  private var rules: List<FaultRule> = emptyList()
  private var nasCalls = 0
  private var storeWrites = 0
  private var phoneCalls = 0
  private var points = 0

  /** Le chiamate viste dall'ultimo [load] (al NAS, al diario e al catalogo, al telefono): servono a tarare dove mettere i colpi. */
  val nasCallCount: Int get() = nasCalls
  val storeWriteCount: Int get() = storeWrites
  val phoneCallCount: Int get() = phoneCalls
  private val perPrimitive = EnumMap<Primitive, Int>(Primitive::class.java)

  /** I colpi scattati, in ordine (in tutta la vita dell'iniettore). */
  val fired: MutableList<FiredFault> = ArrayList()

  /** Ricomincia i conteggi con nuove regole e arma l'iniettore: da qui le chiamate contano e i colpi scattano. */
  fun load(newRules: List<FaultRule>) {
    rules = newRules
    nasCalls = 0
    storeWrites = 0
    phoneCalls = 0
    points = 0
    perPrimitive.clear()
    armed = true
  }

  /** Le regole non ancora scattate (per il racconto di un giro). */
  fun pending(): List<FaultRule> = rules.filter { !it.used }

  fun disarm() {
    armed = false
  }

  /** Quante chiamate (per tipo) sono passate a colpi armati dall'ultimo [load]. */
  fun callsOf(primitive: Primitive): Int = perPrimitive[primitive] ?: 0

  /** Una chiamata al NAS che restituisce un [NasResult]. [locked] e' il valore dell'esito "file aperto dal PC" della primitiva, se ne ha uno. */
  suspend fun <T> nas(primitive: Primitive, incarnation: Incarnation, locked: T? = null, block: suspend () -> NasResult<T>): NasResult<T> =
    nasWrite(primitive, incarnation, locked) { block() }

  /** Come [nas], per le scritture: il blocco riceve `corrupt` (vero = alterare un byte di cio' che si scrive, senza dirlo). */
  suspend fun <T> nasWrite(primitive: Primitive, incarnation: Incarnation, locked: T? = null, block: suspend (corrupt: Boolean) -> NasResult<T>): NasResult<T> {
    incarnation.checkAlive()
    if (!armed) return block(false)
    val index = countNas(primitive)
    stats?.call(primitive)
    var corrupt = false
    while (true) {
      val rule = pick(primitive, index, Moment.BEFORE) ?: break
      when (rule.kind) {
        FaultKind.USER_STOP -> {
          record(primitive, Moment.BEFORE, rule.kind, applied = true)
          actions.userStop()
        }
        FaultKind.FREEZE -> {
          record(primitive, Moment.BEFORE, rule.kind, applied = true)
          actions.freeze()
        }
        FaultKind.PC_FILE -> {
          record(primitive, Moment.BEFORE, rule.kind, applied = true)
          actions.pcFile()
        }
        FaultKind.CORRUPT -> {
          record(primitive, Moment.BEFORE, rule.kind, applied = true)
          corrupt = true
        }
        else -> return hit(rule, primitive, Moment.BEFORE, incarnation, locked, applied = false)
      }
    }
    val result = block(corrupt)
    incarnation.checkAlive()
    val after = pick(primitive, index, Moment.AFTER)
    // La risposta si perde: la modifica (se c'era) e' avvenuta, chi chiama non lo sa.
    if (after != null) return hit(after, primitive, Moment.AFTER, incarnation, locked, applied = true)
    return result
  }

  /** Una lettura a flusso di un file aperto (`NasRandomFile.read`): il colpo diventa una `NasIoException`, come sul NAS vero. */
  suspend fun read(incarnation: Incarnation, block: suspend () -> Int): Int {
    incarnation.checkAlive()
    if (!armed) return block()
    val index = countNas(Primitive.READ)
    stats?.call(Primitive.READ)
    val before = pick(Primitive.READ, index, Moment.BEFORE)
    if (before != null) {
      val result = hit<Unit>(before, Primitive.READ, Moment.BEFORE, incarnation, null, applied = false)
      throw dev.pampa.mycloudgallery.core.nas.contract.NasIoException((result as NasResult.Err).error)
    }
    return block()
  }

  /** Una scrittura del diario o del catalogo: la morte cade prima o dopo che sia stata scritta. */
  suspend fun <T> store(primitive: Primitive, incarnation: Incarnation, block: suspend () -> T): T {
    incarnation.checkAlive()
    if (!armed) return block()
    val index = ++storeWrites
    val primitiveIndex = bump(primitive)
    stats?.call(primitive)
    val point = ++points
    if (pick(primitive, index, Moment.BEFORE, primitiveIndex) != null) {
      record(primitive, Moment.BEFORE, FaultKind.KILL, applied = false, point)
      incarnation.die(ChaosDeath(point, primitive, Moment.BEFORE))
    }
    val value = block()
    incarnation.checkAlive()
    if (pick(primitive, index, Moment.AFTER, primitiveIndex) != null) {
      record(primitive, Moment.AFTER, FaultKind.KILL, applied = true, point)
      incarnation.die(ChaosDeath(point, primitive, Moment.AFTER))
    }
    return value
  }

  /** Una chiamata al telefono: la morte prima o dopo; sulla lettura anche un errore di I/O (la riga e il timbro non cambiano). */
  suspend fun <T> phone(primitive: Primitive, incarnation: Incarnation, block: suspend () -> T): T {
    incarnation.checkAlive()
    if (!armed) return block()
    val index = ++phoneCalls
    val primitiveIndex = bump(primitive)
    stats?.call(primitive)
    val point = ++points
    val before = pick(primitive, index, Moment.BEFORE, primitiveIndex)
    if (before != null) {
      record(primitive, Moment.BEFORE, before.kind, applied = false, point)
      if (before.kind == FaultKind.IO_ERROR) throw IOException("lettura del telefono fallita (colpo del banco)")
      incarnation.die(ChaosDeath(point, primitive, Moment.BEFORE))
    }
    val value = block()
    incarnation.checkAlive()
    if (pick(primitive, index, Moment.AFTER, primitiveIndex) != null) {
      record(primitive, Moment.AFTER, FaultKind.KILL, applied = true, point)
      incarnation.die(ChaosDeath(point, primitive, Moment.AFTER))
    }
    return value
  }

  private fun countNas(primitive: Primitive): Int {
    nasCalls++
    points++
    bump(primitive)
    return nasCalls
  }

  private fun bump(primitive: Primitive): Int {
    val next = (perPrimitive[primitive] ?: 0) + 1
    perPrimitive[primitive] = next
    return next
  }

  /** La prima regola non ancora usata che scatta a questo punto, in questo momento, con un colpo che ha senso qui. */
  private fun pick(primitive: Primitive, index: Int, moment: Moment, primitiveIndex: Int = perPrimitive[primitive] ?: 0): FaultRule? {
    for (rule in rules) {
      if (rule.used || rule.moment != moment || !primitive.allows(rule.kind, moment)) continue
      val matches = when (val t = rule.trigger) {
        is Trigger.NasCall -> (primitive.side == Side.NAS_WRITE || primitive.side == Side.NAS_READ) && index >= t.n
        is Trigger.StoreWrite -> (primitive.side == Side.DIARY || primitive.side == Side.CATALOG) && index >= t.n
        is Trigger.PhoneCall -> primitive.side == Side.PHONE && index >= t.n
        is Trigger.OfPrimitive -> t.primitive == primitive && primitiveIndex >= t.n
      }
      if (!matches) continue
      rule.used = true
      return rule
    }
    return null
  }

  private suspend fun <T> hit(
    rule: FaultRule,
    primitive: Primitive,
    moment: Moment,
    incarnation: Incarnation,
    locked: T?,
    applied: Boolean,
  ): NasResult<T> {
    record(primitive, moment, rule.kind, applied)
    return when (rule.kind) {
      FaultKind.DROP -> {
        onConnectionLost()
        NasResult.Err(NasError.Timeout)
      }
      FaultKind.EXPIRE_SESSION -> {
        // La sessione scaduta chiude gli handle che il NAS teneva per lei (come una connessione caduta): la richiesta dopo si riconnette.
        onConnectionLost()
        NasResult.Err(NasError.SessionExpired)
      }
      FaultKind.STALL -> {
        onConnectionLost()
        NasResult.Err(NasError.Stalled)
      }
      FaultKind.KILL -> incarnation.die(ChaosDeath(points, primitive, moment))
      FaultKind.DISK_FULL -> NasResult.Err(NasError.NasFull)
      FaultKind.AUTH -> NasResult.Err(NasError.AuthBlocked(AuthBlockReason.AUTH_FAILED))
      FaultKind.LOCKED -> NasResult.Ok(checkNotNull(locked) { "una primitiva senza esito 'file aperto' non ammette questo colpo" })
      else -> error("colpo ${rule.kind} non gestito qui")
    }
  }

  private fun record(primitive: Primitive, moment: Moment, kind: FaultKind, applied: Boolean, point: Int = points) {
    val fault = FiredFault(point, primitive, moment, kind, applied)
    fired += fault
    stats?.fired(fault)
  }

  companion object {
    /**
     * Le regole casuali di un passo, ripetibili dal seme. La forma: da 1 a [maxFaults] colpi; meta' puntati a una primitiva precisa (cosi'
     * anche quelle rare, come `deleteOwnTemp` o `reopenTemp`, vengono colpite), gli altri a un punto qualunque del flusso delle chiamate
     * al NAS, delle scritture del diario e del catalogo o delle chiamate al telefono. [estimate] e' una stima dei punti del passo: i
     * colpi oltre la fine semplicemente non scattano.
     */
    fun randomRules(random: Random, estimate: Estimate, maxFaults: Int, mix: FaultMix, budget: FaultBudget): List<FaultRule> {
      val count = mix.faultCount(random, maxFaults)
      val rules = ArrayList<FaultRule>()
      repeat(count) {
        val roll = random.nextDouble()
        when {
          roll < mix.storeShare -> {
            val moment = if (random.nextBoolean()) Moment.BEFORE else Moment.AFTER
            val targeted = if (random.nextDouble() < mix.targetedShare) STORE_PRIMITIVES.random(random) else null
            val trigger = if (targeted != null) Trigger.OfPrimitive(targeted, 1 + geometric(random, 0.45)) else Trigger.StoreWrite(1 + random.nextInt(maxOf(2, estimate.store)))
            rules += FaultRule(trigger, moment, FaultKind.KILL)
          }
          roll < mix.storeShare + mix.phoneShare -> {
            val kind = if (budget.takeIo(random)) FaultKind.IO_ERROR else FaultKind.KILL
            val moment = if (kind.bothMoments && random.nextBoolean()) Moment.AFTER else Moment.BEFORE
            val primitive = if (kind == FaultKind.IO_ERROR) Primitive.PHONE_READ else PHONE_PRIMITIVES.random(random)
            val trigger = if (random.nextBoolean()) Trigger.OfPrimitive(primitive, 1 + geometric(random, 0.4)) else Trigger.PhoneCall(1 + random.nextInt(maxOf(2, estimate.phone)))
            rules += FaultRule(trigger, moment, kind)
          }
          else -> {
            var kind = mix.nasKind(random)
            if (kind == FaultKind.CORRUPT && !budget.takeCorrupt()) kind = FaultKind.DROP
            val moment = if (kind.bothMoments && random.nextBoolean()) Moment.AFTER else Moment.BEFORE
            val trigger = if (random.nextDouble() < mix.targetedShare) {
              val candidates = NAS_PRIMITIVES.filter { it.allows(kind, moment) }
              if (candidates.isEmpty()) Trigger.NasCall(1 + random.nextInt(maxOf(2, estimate.nas)))
              else Trigger.OfPrimitive(candidates.random(random), 1 + geometric(random, 0.45))
            } else {
              Trigger.NasCall(1 + random.nextInt(maxOf(2, estimate.nas)))
            }
            rules += FaultRule(trigger, moment, kind)
          }
        }
      }
      return rules
    }

    private fun geometric(random: Random, p: Double): Int {
      var n = 0
      while (random.nextDouble() > p && n < 12) n++
      return n
    }

    private val NAS_PRIMITIVES = Primitive.entries.filter { (it.side == Side.NAS_WRITE || it.side == Side.NAS_READ) && it != Primitive.COPY_TO_TEMP && it != Primitive.SPACE && it != Primitive.LIST }
    private val STORE_PRIMITIVES = Primitive.entries.filter { it.side == Side.DIARY || it.side == Side.CATALOG }
    private val PHONE_PRIMITIVES = Primitive.entries.filter { it.side == Side.PHONE }
  }
}

/** Una stima dei punti di un passo, per posizionare i colpi: i colpi oltre la fine non scattano e non contano. */
class Estimate(val nas: Int, val store: Int, val phone: Int)

/**
 * I colpi che non si possono ripetere senza cambiare il senso della prova, contati sull'intero giro: un NAS che altera i byte tre volte di
 * seguito sullo stesso file porta, giustamente, a `VERIFY_FAILED` (E-B18, tre verifiche non tornate); e il telefono che non si legge per
 * tre volte con la riga invariata e' `UNREADABLE` (D15). Il banco prova i guasti PASSEGGERI: ne mette al piu' due di ciascun tipo.
 */
class FaultBudget(private var corruptLeft: Int = 2, private var ioLeft: Int = 2) {
  fun takeCorrupt(): Boolean {
    if (corruptLeft <= 0) return false
    corruptLeft--
    return true
  }

  fun takeIo(random: Random): Boolean {
    if (ioLeft <= 0 || random.nextInt(100) >= 60) return false
    ioLeft--
    return true
  }
}

/**
 * La distribuzione dei guasti. Ogni passo ha da 1 a [maxFaults] colpi; di ciascuno si sceglie prima DOVE (una scrittura del diario o del
 * catalogo, una lettura del telefono, una chiamata al NAS), poi COSA e QUANDO (prima o dopo che la chiamata sia stata applicata).
 */
class FaultMix(
  /** Quota dei colpi che cadono su una scrittura del diario o del catalogo (sempre morte del processo). */
  val storeShare: Double,
  /** Quota dei colpi sul telefono (morte o errore di lettura). */
  val phoneShare: Double,
  /** Quota dei colpi puntati a una primitiva precisa invece che a un punto qualunque. */
  val targetedShare: Double,
  private val nasWeights: List<Pair<FaultKind, Int>>,
  private val countWeights: List<Int>,
) {
  fun nasKind(random: Random): FaultKind {
    var pick = random.nextInt(nasWeights.sumOf { it.second })
    for ((kind, weight) in nasWeights) {
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
     * Colpi per passo 1..5 come 15/20/25/20/20. Dove: 25% sul diario e sul catalogo (morte), 8% sul telefono, il resto sul NAS (meta' puntati a
     * una primitiva). Cosa, sul NAS: connessione caduta, morte, sessione scaduta, socket fermo, NAS pieno, file aperto dal PC, password
     * rifiutata, byte alterati, "Ferma", congelamento, un file del PC con il nome previsto.
     */
    val DEFAULT = FaultMix(
      storeShare = 0.25,
      phoneShare = 0.08,
      targetedShare = 0.5,
      nasWeights = listOf(
        FaultKind.DROP to 26, FaultKind.KILL to 24, FaultKind.EXPIRE_SESSION to 7, FaultKind.STALL to 7, FaultKind.DISK_FULL to 5,
        FaultKind.LOCKED to 5, FaultKind.AUTH to 2, FaultKind.CORRUPT to 9, FaultKind.USER_STOP to 5, FaultKind.FREEZE to 3,
        FaultKind.PC_FILE to 7,
      ),
      countWeights = listOf(15, 20, 25, 20, 20),
    )

    /** Un colpo solo per passo (il runner contro il NAS vero: ogni passo vale una interruzione); niente colpi del mondo che il NAS vero non fa. */
    val SINGLE = FaultMix(
      storeShare = 0.25,
      phoneShare = 0.08,
      targetedShare = 0.5,
      nasWeights = listOf(
        FaultKind.DROP to 36, FaultKind.KILL to 36, FaultKind.EXPIRE_SESSION to 8, FaultKind.STALL to 8, FaultKind.DISK_FULL to 3,
        FaultKind.LOCKED to 3, FaultKind.CORRUPT to 6,
      ),
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
  var finalChecks: Int = 0
    private set
  var filesHashed: Long = 0
    private set
  var bytesHashed: Long = 0
    private set
  var mediaTotal: Int = 0
    private set
  var phoneEventsFired: Int = 0
    private set
  val stepKinds: MutableMap<String, Int> = HashMap()
  val events: MutableMap<String, Int> = HashMap()
  val itemStates: MutableMap<String, Int> = HashMap()

  fun call(primitive: Primitive) = synchronized(lock) { calls.merge(primitive, 1, Int::plus) }

  fun fired(fault: FiredFault) = synchronized(lock) { cells.merge(Triple(fault.primitive, fault.moment, fault.kind), 1, Int::plus) }

  fun round() = synchronized(lock) { rounds++ }

  fun step(kind: String) = synchronized(lock) {
    steps++
    stepKinds.merge(kind, 1, Int::plus)
  }

  fun event(name: String) = synchronized(lock) { events.merge(name, 1, Int::plus) }

  fun restart() = synchronized(lock) { restarts++ }

  fun midCheck() = synchronized(lock) { midChecks++ }

  fun finalCheck() = synchronized(lock) { finalChecks++ }

  fun hashed(files: Int, bytes: Long) = synchronized(lock) {
    filesHashed += files
    bytesHashed += bytes
  }

  fun media(count: Int) = synchronized(lock) { mediaTotal += count }

  fun phoneEvents(count: Int) = synchronized(lock) { phoneEventsFired += count }

  fun item(state: String) = synchronized(lock) { itemStates.merge(state, 1, Int::plus) }

  /** Un passo armato: le chiamate che ha fatto e quanto lavoro c'era (media da caricare e elementi da riprendere), per tarare le stime. */
  fun work(nas: Int, store: Int, phone: Int, units: Int) = synchronized(lock) {
    armedSteps++
    sumNas += nas
    sumStore += store
    sumPhone += phone
    sumUnits += units
  }

  var armedSteps: Int = 0
    private set
  var sumNas: Long = 0
    private set
  var sumStore: Long = 0
    private set
  var sumPhone: Long = 0
    private set
  var sumUnits: Long = 0
    private set

  val totalFired: Int get() = synchronized(lock) { cells.values.sum() }

  fun firedBy(kind: FaultKind): Int = synchronized(lock) { cells.filterKeys { it.third == kind }.values.sum() }

  fun firedBy(moment: Moment): Int = synchronized(lock) { cells.filterKeys { it.second == moment }.values.sum() }

  fun firedAt(primitive: Primitive, moment: Moment): Int =
    synchronized(lock) { cells.filterKeys { it.first == primitive && it.second == moment }.values.sum() }

  fun firedAt(primitive: Primitive): Int = synchronized(lock) { cells.filterKeys { it.first == primitive }.values.sum() }

  fun callsOf(primitive: Primitive): Int = synchronized(lock) { calls[primitive] ?: 0 }

  /** Le morti del processo cadute: il numero dei riavvii veri. */
  val deaths: Int get() = firedBy(FaultKind.KILL)

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
