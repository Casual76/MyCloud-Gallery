package dev.pampa.mycloudgallery.core.imaging.scheduler

import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * L'esito di un caricamento, per il pianificatore: serve solo a decidere se e quando riprovare.
 *
 * [DEFERRED] (Fase 4, P7): non c'e' niente da dare **adesso** e non per un errore (il contenuto del NAS non e'
 * preparato e non e' visibile, quindi l'originale non si legge). Non e' un fallimento (non consuma i tentativi) e non si
 * riprova da solo: si riprova alla prossima finestra, quando la richiesta puo' essere diventata visibile.
 */
internal enum class LoadResult { LOADED, FAILED, DEFERRED }

/** Dopo quanti tentativi e con quali attese si riprova una miniatura che non e' arrivata (file corrotto, E-M10). */
data class RetryPolicy(
  val maxAttempts: Int = 3,
  val backoffMs: List<Long> = listOf(1_000L, 15_000L),
) {
  init {
    require(maxAttempts >= 1 && backoffMs.isNotEmpty()) { "Politica di nuovi tentativi non valida." }
  }

  /** L'attesa prima del tentativo successivo, dopo [attempts] tentativi falliti. */
  fun delayAfter(attempts: Int): Long = backoffMs[(attempts - 1).coerceIn(0, backoffMs.lastIndex)]
}

/** I contatori del pianificatore (D5, E-V5): solo numeri. */
internal data class SchedulerCounters(
  val started: Long = 0,
  /** Lavori partiti e poi annullati perche' la richiesta e' uscita dalla finestra. */
  val cancelled: Long = 0,
  val failed: Long = 0,
  /** Lavori in volo adesso (compresi quelli annullati che si stanno ancora chiudendo: il tetto vale per tutti). */
  val inFlight: Int = 0,
  /** Il massimo di lavori in volo osservato. */
  val peakInFlight: Int = 0,
)

/**
 * Il pianificatore delle miniature (D5, `docs/fase3-progetto.md` §1.2): decide **quali** richieste girano, **in che
 * ordine** e **quante insieme**, e annulla cio' che l'utente non vede piu'. Non sa niente di bitmap, cache o
 * Android: riceve la finestra ([update]) e un `execute` che fa il lavoro vero. Per questo si prova interamente con il
 * tempo virtuale (`TestScope`).
 *
 * Regole:
 * - **Finestra.** [update] sostituisce l'insieme voluto: prima le richieste *visibili* (nell'ordine ricevuto), poi
 *   quelle *in arrivo* ([LookAhead] le ordina nella direzione dello scorrimento). Una chiave presente in entrambe e'
 *   visibile.
 * - **Annullamento.** Un lavoro in volo la cui chiave e' uscita dalla finestra si annulla subito (il `Job`; chi
 *   esegue lo traduce in `CancellationSignal`); una richiesta in coda che esce dalla finestra non parte mai.
 * - **Concorrenza.** Al massimo [maxConcurrent] lavori insieme, compresi quelli annullati che si stanno chiudendo
 *   (il collo di bottiglia e' il MediaProvider: il tetto e' anche una contropressione). Con piu' di uno, **uno slot
 *   e' riservato** alle richieste visibili: quelle in arrivo ne usano al massimo `maxConcurrent - 1`, cosi' una
 *   cella visibile non aspetta mai dietro a lavoro speculativo.
 * - **Priorita'.** Si parte sempre dalla prima richiesta visibile non ancora in cache; le richieste in arrivo
 *   partono solo quando non ce n'e' una visibile in attesa.
 * - **Fallimenti.** Una miniatura che non arriva si riprova con [RetryPolicy] (attese crescenti, poi basta): la cella
 *   resta sul segnaposto e il pianificatore non martella un file corrotto a ogni scorrimento. Il fallimento e' per
 *   chiave: una versione nuova del file ha una chiave nuova e riparte.
 *
 * Thread-safety: [update] arriva dal thread principale, i completamenti dai thread di lavoro; lo stato sta sotto un
 * lock e nessun lavoro parte mentre lo si tiene.
 */
internal class ThumbnailScheduler(
  private val scope: CoroutineScope,
  private val maxConcurrent: Int,
  private val clockMs: () -> Long,
  /** Vero se la miniatura e' gia' in cache (e la tocca, cosi' la finestra protegge cio' che serve). */
  private val isCached: (ThumbKey) -> Boolean,
  /**
   * Falso quando la cache non puo' accogliere altro per quel tipo di richiesta (vero = visibile): non parte altro
   * lavoro finche' la finestra non cambia. Senza questo, una finestra piu' grande del budget girerebbe all'infinito.
   */
  private val hasRoom: (forVisible: Boolean) -> Boolean = { true },
  private val execute: suspend (ThumbRequest) -> LoadResult,
  private val retry: RetryPolicy = RetryPolicy(),
  /** Chiamata (fuori dal lock) quando i contatori cambiano: il caricatore ripubblica le statistiche. */
  private val onChanged: () -> Unit = {},
) {

  init {
    require(maxConcurrent >= 1) { "Serve almeno un lavoro alla volta." }
  }

  private enum class Priority { VISIBLE, AHEAD }

  private class Wanted(val request: ThumbRequest, val priority: Priority)

  private class Running(val job: Job, var priority: Priority) {
    var cancelRequested: Boolean = false

    @Volatile
    var result: LoadResult = LoadResult.FAILED
  }

  private class Failure(val attempts: Int, val lastAtMs: Long)

  private val lock = Any()
  private var wanted: LinkedHashMap<ThumbKey, Wanted> = LinkedHashMap()
  private val running = HashMap<ThumbKey, Running>()
  private val failures = object : LinkedHashMap<ThumbKey, Failure>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ThumbKey, Failure>?): Boolean = size > MAX_FAILURES
  }

  /** Le richieste che hanno risposto [LoadResult.DEFERRED]: si saltano finche' la finestra non cambia. */
  private val deferred = HashSet<ThumbKey>()
  private var started = 0L
  private var cancelled = 0L
  private var failed = 0L
  private var peak = 0

  /**
   * La finestra nuova: [visible] sullo schermo, [ahead] in arrivo, entrambe nell'ordine di importanza. Annulla cio'
   * che e' uscito e fa partire cio' che manca, fino al tetto.
   */
  fun update(visible: List<ThumbRequest>, ahead: List<ThumbRequest>) {
    val toCancel = ArrayList<Job>()
    val toStart = synchronized(lock) {
      val next = LinkedHashMap<ThumbKey, Wanted>(visible.size + ahead.size)
      for (request in visible) {
        if (next.size >= MAX_VISIBLE) break
        next.putIfAbsent(request.key, Wanted(request, Priority.VISIBLE))
      }
      var aheadCount = 0
      for (request in ahead) {
        if (aheadCount >= MAX_AHEAD) break
        if (next.putIfAbsent(request.key, Wanted(request, Priority.AHEAD)) == null) aheadCount++
      }
      wanted = next
      deferred.clear()
      for ((key, run) in running) {
        val w = next[key]
        if (w == null) {
          if (!run.cancelRequested) {
            run.cancelRequested = true
            cancelled++
            toCancel += run.job
          }
        } else {
          run.priority = w.priority
        }
      }
      collectStartable()
    }
    // Fuori dal lock: annullare un lavoro che non e' ancora partito lo completa subito, e il suo gestore di
    // completamento rientra nello stato mentre lo si sta iterando.
    toCancel.forEach { it.cancel(CancellationException("Fuori dalla finestra.")) }
    toStart.forEach { it.start() }
    onChanged()
  }

  /**
   * Dimentica i fallimenti (dopo un `trim`, o quando la memoria e' tornata): le richieste in finestra riprovano. Con
   * [restart] falso non fa partire niente subito (l'app e' in secondo piano): le richieste riprovano alla prossima
   * finestra.
   */
  fun clearFailures(restart: Boolean = true) {
    val toStart = synchronized(lock) {
      failures.clear()
      deferred.clear()
      if (restart) collectStartable() else emptyList()
    }
    toStart.forEach { it.start() }
  }

  /** Riapplica la finestra corrente (dopo che la cache ha perso delle voci): fa ripartire cio' che manca. */
  fun repump() {
    val toStart = synchronized(lock) { collectStartable() }
    toStart.forEach { it.start() }
  }

  /** Vero se la miniatura di [key] ha esaurito i tentativi (la cella resta sul segnaposto). */
  fun hasGivenUp(key: ThumbKey): Boolean = synchronized(lock) {
    val failure = failures[key]
    failure != null && failure.attempts >= retry.maxAttempts
  }

  /**
   * Avvio istantaneo: vero se per [key] c'e' gia' stata una risposta che non e' una miniatura (un tentativo fallito, o "non adesso"): la cella
   * mostra il segnaposto finche' non riprova. Serve al segno del primo disegno utile, che non aspetta un nuovo tentativo di un secondo.
   */
  fun answeredWithout(key: ThumbKey): Boolean = synchronized(lock) { key in failures || key in deferred }

  fun counters(): SchedulerCounters = synchronized(lock) {
    SchedulerCounters(started, cancelled, failed, running.size, peak)
  }

  /** Annulla tutto (alla chiusura del caricatore). */
  fun cancelAll() {
    val toCancel = ArrayList<Job>()
    synchronized(lock) {
      wanted = LinkedHashMap()
      for (run in running.values) {
        if (!run.cancelRequested) {
          run.cancelRequested = true
          cancelled++
        }
        toCancel += run.job
      }
    }
    toCancel.forEach { it.cancel(CancellationException("Pianificatore chiuso.")) }
  }

  // --- Interni (sempre sotto lock) -------------------------------------------------------------------------

  /** Sceglie le richieste da avviare e le registra; i lavori partono dopo, fuori dal lock. */
  private fun collectStartable(): List<Job> {
    // Un ambito chiuso completa subito ogni lavoro nuovo, e il suo gestore tornerebbe qui: niente da avviare.
    if (!scope.isActive) return emptyList()
    val slots = maxConcurrent - running.size
    if (slots <= 0 || wanted.isEmpty()) return emptyList()
    val aheadCap = if (maxConcurrent > 1) maxConcurrent - 1 else 1
    var runningAhead = running.values.count { it.priority == Priority.AHEAD && !it.cancelRequested }
    var free = slots
    val toStart = ArrayList<Job>(free)
    val now = clockMs()
    for (w in wanted.values) {
      if (free <= 0) break
      val key = w.request.key
      if (running.containsKey(key)) continue
      if (isCached(key)) continue
      if (key in deferred) continue
      if (isBlocked(key, now)) continue
      // Niente posto per questa richiesta: ne' per le successive (le visibili vengono prima delle in arrivo).
      if (!hasRoom(w.priority == Priority.VISIBLE)) break
      if (w.priority == Priority.AHEAD) {
        if (runningAhead >= aheadCap) break
        // Con un solo slot le richieste in arrivo partono solo se nessuna visibile e' in attesa: l'ordine
        // della finestra lo garantisce (le visibili vengono prima).
        runningAhead++
      }
      toStart += register(w)
      free--
    }
    return toStart
  }

  private fun isBlocked(key: ThumbKey, now: Long): Boolean {
    val failure = failures[key] ?: return false
    if (failure.attempts >= retry.maxAttempts) return true
    return now - failure.lastAtMs < retry.delayAfter(failure.attempts)
  }

  private fun register(w: Wanted): Job {
    val key = w.request.key
    lateinit var run: Running
    val job = scope.launch(start = CoroutineStart.LAZY) {
      try {
        run.result = execute(w.request)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Throwable) {
        // Un errore di una miniatura non deve fermare le altre ne' il resto dell'app: e' un fallimento da riprovare.
        run.result = LoadResult.FAILED
      }
    }
    run = Running(job, w.priority)
    running[key] = run
    started++
    if (running.size > peak) peak = running.size
    job.invokeOnCompletion { cause -> finished(key, run, cause) }
    return job
  }

  private fun finished(key: ThumbKey, run: Running, cause: Throwable?) {
    var retryInMs = -1L
    val toStart = synchronized(lock) {
      if (running[key] === run) running.remove(key)
      val wasCancelled = cause is CancellationException
      if (!wasCancelled) {
        if (cause == null && run.result == LoadResult.LOADED) {
          failures.remove(key)
        } else if (cause == null && run.result == LoadResult.DEFERRED) {
          // Non e' un errore: non consuma i tentativi. Si riprova a ogni finestra, non da solo (niente giri a vuoto).
          deferred += key
        } else {
          failed++
          val attempts = (failures[key]?.attempts ?: 0) + 1
          failures[key] = Failure(attempts, clockMs())
          if (attempts < retry.maxAttempts && wanted.containsKey(key)) retryInMs = retry.delayAfter(attempts)
        }
      }
      collectStartable()
    }
    toStart.forEach { it.start() }
    if (retryInMs >= 0) scheduleRetry(retryInMs)
    onChanged()
  }

  /** Un solo risveglio dopo l'attesa: la finestra non cambia, ma il nuovo tentativo deve partire lo stesso. */
  private fun scheduleRetry(afterMs: Long) {
    scope.launch {
      delay(afterMs)
      val toStart = synchronized(lock) { collectStartable() }
      toStart.forEach { it.start() }
    }
  }

  private companion object {
    const val MAX_VISIBLE = 512
    const val MAX_AHEAD = 256
    const val MAX_FAILURES = 1024
  }
}
