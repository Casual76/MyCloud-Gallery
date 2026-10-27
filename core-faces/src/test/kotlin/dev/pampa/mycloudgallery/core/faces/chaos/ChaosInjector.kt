package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.faces.erase.Death

// I COLPI (docs/prova-500.md Parte F). Un colpo cade a un punto preciso ([Surface]) di un dispositivo alla n-esima chiamata, PRIMA che la chiamata
// abbia effetto o DOPO (l'effetto c'e', la risposta si perde). Le chiamate si contano solo a colpi armati: il mondo iniziale e le verifiche, che il
// banco fa per conto suo, non contano e non vengono mai colpite. La morte del processo e' un `Error` (`Death`): dopo, ogni chiamata fatta dai vecchi
// oggetti di quel dispositivo si ferma con la stessa morte finche' il banco non ricostruisce il processo ([revive]).

/** Le superfici dove un salvataggio che fallisce e' un errore del database (non di un file). */
private val DATABASE_SURFACES: Set<Surface> = setOf(Surface.DB_BODY, Surface.DB_GROUPING, Surface.DB_PEOPLE, Surface.DB_UNITS, Surface.OUTBOX, Surface.REPLICA)

/** L'esecutore dei colpi di un giro. Un solo thread: il banco gira in `runBlocking`. */
internal class ChaosInjector(val stats: ChaosStats = ChaosStats()) {

  private class Rule(val blow: Blow) {
    var used = false
  }

  private val rules = ArrayList<Rule>()
  private val counts = HashMap<Pair<Int, Surface>, Int>()
  private val dead = HashSet<Int>()

  /** I colpi scattati nel giro corrente, in ordine. */
  val fired: MutableList<FiredBlow> = ArrayList()

  var armed: Boolean = false
    private set

  /** Chi esegue un cambio di generazione ([FaultKind.FLIP]); lo mette il banco. */
  var flipper: (suspend (Blow) -> Unit)? = null

  /** Quando uno stallo scatta: il tempo che passa prima che il codice si accorga che il NAS non risponde (lo mette il banco). */
  var onStall: () -> Unit = {}

  /** Arma i colpi di un passo: le chiamate si contano da adesso. */
  fun arm(blows: List<Blow>) {
    disarm()
    for (blow in blows) {
      stats.planned(blow)
      rules += Rule(blow)
    }
    armed = true
  }

  /** Toglie i colpi non scattati e ferma il conteggio: quel che resta del passo gira senza guasti. */
  fun disarm() {
    for ((key, n) in counts) stats.called(key.second, n)
    counts.clear()
    rules.clear()
    armed = false
  }

  /** Il processo nuovo del dispositivo [device]: gli oggetti ricostruiti lavorano di nuovo. */
  fun revive(device: Int) {
    dead -= device
  }

  fun isDead(device: Int): Boolean = device in dead

  fun anyDead(): Boolean = dead.isNotEmpty()

  fun checkAlive(device: Int) {
    if (device in dead) throw Death()
  }

  /** Il processo di [device] muore qui (e lo dice a chi sta sotto): non ritorna. */
  fun die(blow: Blow): Nothing {
    markFired(blow)
    dead += blow.device
    throw Death()
  }

  /**
   * Il processo muore, ma chi lo dice non puo' lanciare la morte (e' dentro la sessione SMB, che classifica ogni eccezione): la segna e basta.
   * Da qui ogni chiamata dei vecchi oggetti si ferma; chi guida il giro vede [isDead] appena la chiamata torna.
   */
  fun kill(blow: Blow, detail: String = "") {
    markFired(blow, detail)
    dead += blow.device
  }

  /** Registra un colpo non mortale scattato. */
  fun markFired(blow: Blow, detail: String = "") {
    fired += FiredBlow(blow, detail)
    stats.fired(blow)
  }

  /** Conta una chiamata a [surface] di [device] (se armato) e restituisce il suo numero (0 se non si conta). */
  fun enter(device: Int, surface: Surface): Int {
    if (!armed) return 0
    val key = device to surface
    val n = (counts[key] ?: 0) + 1
    counts[key] = n
    return n
  }

  /** Il colpo che cade alla chiamata [n] di [surface] nel momento [moment], se c'e' (una volta sola). Lo spazio finito aspetta una chiamata che occupa spazio. */
  fun due(device: Int, surface: Surface, n: Int, moment: Moment, allocates: Boolean): Blow? {
    if (n == 0) return null
    val rule = rules.firstOrNull {
      val blow = it.blow
      !it.used && blow.device == device && blow.surface == surface && blow.moment == moment && blow.kind != FaultKind.FLIP &&
        (if (blow.kind == FaultKind.NO_SPACE) allocates && blow.nth <= n else blow.nth == n)
    } ?: return null
    rule.used = true
    return rule.blow
  }

  /** Il cambio di generazione che cade alla chiamata [n] di [surface] (prima della chiamata), se c'e'. */
  fun dueFlip(device: Int, surface: Surface, n: Int): Blow? {
    if (n == 0) return null
    val rule = rules.firstOrNull {
      !it.used && it.blow.kind == FaultKind.FLIP && it.blow.device == device && it.blow.surface == surface && it.blow.nth == n
    } ?: return null
    rule.used = true
    return rule.blow
  }

  /** Il colpo [blow]: la morte o un guasto passeggero. */
  fun realize(blow: Blow): Nothing {
    if (blow.kind == FaultKind.KILL) die(blow)
    markFired(blow)
    if (blow.kind == FaultKind.STALL) onStall()
    if (blow.kind == FaultKind.IO_FAIL && blow.surface in DATABASE_SURFACES) throw ChaosDbFault(blow)
    throw ChaosFault(blow)
  }

  /** Prima di una chiamata: controlla che il processo sia vivo, la conta e applica il colpo "prima". Restituisce il numero della chiamata. */
  fun begin(device: Int, surface: Surface, allocates: Boolean): Int {
    checkAlive(device)
    val n = enter(device, surface)
    due(device, surface, n, Moment.BEFORE, allocates)?.let { realize(it) }
    return n
  }

  /** Dopo una chiamata riuscita: il colpo "dopo" (l'effetto c'e', la risposta no). */
  fun end(device: Int, surface: Surface, n: Int, allocates: Boolean) {
    checkAlive(device)
    due(device, surface, n, Moment.AFTER, allocates)?.let { realize(it) }
  }

  /** Una chiamata bloccante (un file system, un salvataggio): i colpi prima e dopo. */
  inline fun <T> around(device: Int, surface: Surface, allocates: Boolean = false, block: () -> T): T {
    val n = begin(device, surface, allocates)
    val result = block()
    end(device, surface, n, allocates)
    return result
  }

  /** Come [around], ma con i cambi di generazione: prima della chiamata il banco puo' far cambiare lo stato (una cancellazione, un'esclusione). */
  suspend inline fun <T> aroundSuspending(device: Int, surface: Surface, allocates: Boolean = false, block: () -> T): T {
    checkAlive(device)
    val n = enter(device, surface)
    dueFlip(device, surface, n)?.let { blow ->
      markFired(blow)
      flipper?.invoke(blow)
    }
    due(device, surface, n, Moment.BEFORE, allocates)?.let { realize(it) }
    val result = block()
    end(device, surface, n, allocates)
    return result
  }
}
