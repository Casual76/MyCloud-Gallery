package dev.pampa.mycloudgallery.core.albums.chaos

import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nasops.contract.MoveCompletionSink
import dev.pampa.mycloudgallery.core.nasops.contract.NasFileRef
import dev.pampa.mycloudgallery.core.nasops.contract.OpId
import dev.pampa.mycloudgallery.core.nasops.contract.OpsStore
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.EnumMap
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

// I COLPI (docs/prova-500.md Parte E). Un colpo cade a un punto preciso ([Surface]) alla n-esima chiamata, PRIMA che la chiamata abbia effetto o
// DOPO (l'effetto c'e', la risposta si perde). Le chiamate si contano solo a colpi armati: il mondo iniziale e le verifiche, che il banco fa per
// conto suo, non contano e non vengono mai colpite. La morte del processo e' un `Error` ([ProcessDeath]): dopo, ogni chiamata fatta dai vecchi
// oggetti si ferma con la stessa morte finche' il banco non ricostruisce il processo ([revive]).

/** L'esecutore dei colpi di un giro. Un solo thread: il banco gira in `runBlocking`. */
class ShareChaosInjector(val stats: ShareChaosStats = ShareChaosStats()) {

  private class Rule(val blow: Blow) {
    var used = false
  }

  private val rules = ArrayList<Rule>()
  private val counts = EnumMap<Surface, Int>(Surface::class.java)
  private val diaryQueue = ArrayDeque<Blow>()

  /** I colpi scattati nel giro corrente, in ordine. */
  val fired: MutableList<FiredBlow> = ArrayList()

  var armed: Boolean = false
    private set

  var dead: ProcessDeath? = null
    private set

  /** Arma i colpi di un passo: le chiamate si contano da adesso. I colpi del diario della condivisione li prende il banco con [nextDiaryBlow]. */
  fun arm(blows: List<Blow>) {
    disarm()
    for (blow in blows) {
      stats.planned(blow)
      if (blow.surface == Surface.SHARE_DIARY) diaryQueue += blow else rules += Rule(blow)
    }
    armed = true
  }

  /**
   * Aggiunge colpi a un passo gia' armato, contando da adesso: quelli che cadono sul lavoro gia' in corso (l'utente ha toccato, i trasferimenti
   * sono partiti). Il numero della chiamata e' relativo a questo momento; nel racconto compare quello assoluto.
   */
  fun armLater(blows: List<Blow>) {
    for (blow in blows) {
      stats.planned(blow)
      if (blow.surface == Surface.SHARE_DIARY) diaryQueue += blow else rules += Rule(blow.copy(nth = blow.nth + (counts[blow.surface] ?: 0)))
    }
    armed = true
  }

  /** Toglie i colpi non scattati e ferma il conteggio: quel che resta del passo gira senza guasti. */
  fun disarm() {
    for ((surface, n) in counts) stats.called(surface, n)
    counts.clear()
    rules.clear()
    diaryQueue.clear()
    armed = false
  }

  fun nextDiaryBlow(): Blow? = diaryQueue.removeFirstOrNull()

  fun hasDiaryBlows(): Boolean = diaryQueue.isNotEmpty()

  /** Il diario della condivisione e' "morto" a un salvataggio: il banco lo registra qui, come ogni altra morte. */
  fun diaryDied(blow: Blow) {
    markFired(blow)
    dead = ProcessDeath(blow)
  }

  /** Il processo nuovo: gli oggetti ricostruiti lavorano di nuovo. */
  fun revive() {
    dead = null
  }

  fun checkAlive() {
    dead?.let { throw it }
  }

  /** Il processo muore qui (e lo dice a chi sta sotto): non ritorna. */
  fun die(blow: Blow): Nothing {
    markFired(blow)
    val death = ProcessDeath(blow)
    dead = death
    throw death
  }

  /**
   * Il processo muore, ma chi lo dice non puo' lanciare la morte (e' dentro la sessione SMB, che classifica ogni eccezione): la segna e basta. Da
   * qui ogni chiamata dei vecchi oggetti si ferma; chi guida il giro vede [dead] appena la chiamata torna.
   */
  fun kill(blow: Blow, detail: String = "") {
    markFired(blow, detail = detail)
    dead = ProcessDeath(blow)
  }

  /** Registra un colpo non mortale scattato. */
  fun markFired(blow: Blow, applied: Boolean = true, detail: String = "") {
    fired += FiredBlow(blow, applied, detail)
    stats.fired(blow)
  }

  // --- Il conteggio e le regole ---------------------------------------------------------------------------------------------------

  /** Conta una chiamata a [surface] (se armato) e restituisce il suo numero (0 se non si conta). */
  fun enter(surface: Surface): Int {
    if (!armed) return 0
    val n = (counts[surface] ?: 0) + 1
    counts[surface] = n
    return n
  }

  /** Il colpo che cade alla chiamata [n] di [surface] nel momento [moment], se c'e' (una volta sola). */
  fun due(surface: Surface, n: Int, moment: Moment, deathOnly: Boolean = false): Blow? {
    if (n == 0) return null
    val rule = rules.firstOrNull {
      !it.used && it.blow.surface == surface && it.blow.nth == n && it.blow.moment == moment && !(surface == Surface.NAS && it.blow.kind == ShareFaultKind.NO_SPACE) &&
        !(deathOnly && it.blow.kind != ShareFaultKind.KILL)
    } ?: return null
    rule.used = true
    return rule.blow
  }

  /** Come [due] ma per un colpo che aspetta una chiamata che occupa spazio (lo spazio finisce scrivendo, non cancellando): scatta alla prima da [n] in poi. */
  fun dueWriting(surface: Surface, n: Int, moment: Moment, allocates: Boolean): Blow? {
    if (n == 0 || !allocates) return null
    val rule = rules.firstOrNull {
      !it.used && it.blow.surface == surface && it.blow.nth <= n && it.blow.moment == moment && it.blow.kind == ShareFaultKind.NO_SPACE
    } ?: return null
    rule.used = true
    return rule.blow
  }

  /** Il colpo [blow] su una porta: la morte o un guasto passeggero. */
  fun realize(blow: Blow): Nothing {
    if (blow.kind == ShareFaultKind.KILL) die(blow)
    markFired(blow)
    throw ChaosFault(blow)
  }

  /**
   * Una chiamata a una porta: i colpi PRIMA e DOPO dell'n-esima. [refuse]: cosa risponde la porta a "spazio finito" (un rifiuto, non
   * un'eccezione); senza, lo spazio finito e' una connessione caduta. [deathOnly]: su questa chiamata cade solo la morte del processo (un difetto
   * noto del codice sotto prova che il banco evita, [KnownDefect]).
   */
  suspend fun <T> around(surface: Surface, refuse: (() -> T)? = null, deathOnly: Boolean = false, block: suspend () -> T): T {
    checkAlive()
    val n = enter(surface)
    due(surface, n, Moment.BEFORE, deathOnly)?.let { blow ->
      if (blow.kind == ShareFaultKind.NO_SPACE && refuse != null) {
        markFired(blow)
        return refuse()
      }
      realize(if (blow.kind == ShareFaultKind.NO_SPACE) blow.copy(kind = ShareFaultKind.DROP) else blow)
    }
    val result = block()
    checkAlive()
    due(surface, n, Moment.AFTER, deathOnly)?.let { realize(if (it.kind == ShareFaultKind.NO_SPACE) it.copy(kind = ShareFaultKind.DROP) else it) }
    return result
  }
}

/**
 * Il diario di core-nasops con la morte del processo a ogni salvataggio, prima o dopo che la scrittura sia arrivata sul disco (come i decoratori
 * del banco di P15). Le letture non si colpiscono. Dopo la morte ogni scrittura si ferma con la stessa morte.
 *
 * E' un proxy dinamico sull'interfaccia, non una classe che la implementa: `OpsStore.create` prende il piano accettato di core-nasops, un tipo di conferma
 * che core-albums non nomina mai (Phase7ArchitectureTest), nemmeno in un test. Il diario del banco e' `InMemoryOpsStore`, che non sospende mai: ogni
 * scrittura torna con il suo risultato e il colpo "dopo" cade subito dopo.
 */
object DyingOpsStore {

  /** Le scritture del diario: gli unici punti dove cade una morte. */
  private val WRITES = setOf("create", "updateItem", "setOpState", "recordTrash", "markTrash", "trashEntered", "saveTrashManifest")

  fun wrap(inner: OpsStore, injector: ShareChaosInjector): OpsStore {
    val handler = InvocationHandler { _, method, args ->
      val arguments: Array<Any?> = args ?: emptyArray()
      if (method.name !in WRITES) return@InvocationHandler invoke(method, inner, arguments)
      injector.checkAlive()
      val n = injector.enter(Surface.OPS_STORE)
      injector.due(Surface.OPS_STORE, n, Moment.BEFORE)?.let { injector.realize(it) }
      val result = invoke(method, inner, arguments)
      check(result !== COROUTINE_SUSPENDED) { "il diario del banco non sospende mai" }
      injector.checkAlive()
      injector.due(Surface.OPS_STORE, n, Moment.AFTER)?.let { injector.realize(it) }
      result
    }
    return Proxy.newProxyInstance(OpsStore::class.java.classLoader, arrayOf(OpsStore::class.java), handler) as OpsStore
  }

  private fun invoke(method: Method, target: Any, args: Array<Any?>): Any? =
    try {
      method.invoke(target, *args)
    } catch (e: InvocationTargetException) {
      throw e.targetException
    }
}

/**
 * La porta di completamento (D29) con i colpi: la morte prima del collegamento (il catalogo non sa niente), la morte dopo (il catalogo sa, il
 * motore no: alla ripresa la porta si richiama e deve essere idempotente), o un errore del catalogo (il motore ferma l'elemento in `PUBLISHED`
 * con la sorgente al suo posto). In piu' guarda, a ogni chiamata, che la sorgente sia ancora al suo posto: **il catalogo deve conoscere la
 * destinazione prima che la sorgente vada nel cestino** (invariante 3, "nessuna perdita").
 */
class ChaosCompletionSink(
  private val inner: MoveCompletionSink,
  private val injector: ShareChaosInjector,
  private val observer: ShareChaosObserver?,
) : MoveCompletionSink {
  override suspend fun published(op: OpId, index: Int, source: NasFileRef, dest: NasFileRef, stamp: NasStamp, sha256: Sha256) {
    observer?.onCompletion(source, dest)
    injector.around<Unit>(Surface.COMPLETION) { inner.published(op, index, source, dest, stamp, sha256) }
  }
}
