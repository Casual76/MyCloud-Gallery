package dev.pampa.mycloudgallery.core.rules.run

import dev.pampa.mycloudgallery.core.rules.ActivityRulesEngine
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.Pace
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.RunScope
import dev.pampa.mycloudgallery.core.rules.SystemStateProvider
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficLedger
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficNetwork
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficSource
import dev.pampa.mycloudgallery.core.rules.traffic.saturatingAdd
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

// I LAVORI A TAPPE (docs/fase4-progetto.md §2.8, D22; docs/android.md §1.3 punto 1). Nessun job lungo: ogni tappa e' un
// job WorkManager che elabora uno o piu' lotti con un punto di ripresa durevole e finisce entro ~8 minuti (margine sui 10
// garantiti). Le tappe si accodano a catena finche' il motore delle regole dice "continua". Qui c'e' il protocollo, non
// il lavoro: il lavoro lo scrive chi esegue (scansione, sincronizzazione dell'indice, preparazione...) e lo lega ad
// WorkManager in `:app` (P11).
//
// Il protocollo, per ogni tappa:
//
//   1. evaluate(request)                       → Wait: la tappa non parte ([StageOutcome.Waiting])
//   2. carica il punto di ripresa              → riprende da li' ([StageStats.resumed])
//   3. lotto: il lavoro vero, che ogni tanto chiede [StageScope.proceed] (fermati a meta' lotto, se serve), registra il
//      traffico APPENA e' passato ([StageScope.record], con la rete davvero usata) e salva il punto di ripresa APPENA ha
//      finito un pezzo ([StageScope.commit])
//   4. evaluate(request, running = true)       → Stop: salva ed esci ([StageOutcome.Stopped])
//   5. tempo della tappa finito, tetto di giro o tetto mensile esauriti → [StageOutcome.Yielded] con altro lavoro da fare
//   6. altrimenti si torna al punto 3 (a ritmo ridotto se il telefono e' caldo)
//
// Perche' "subito": se il processo muore (Android lo puo' fare in qualunque momento), il traffico gia' passato conta nei
// tetti dei dati e il punto di ripresa dice dove si riparte. L'ordine e' sempre "prima il registro del traffico, poi il
// punto di ripresa": se si muore fra i due si conta di piu' e si rifa' un pezzo (ogni passo dei lavori e' idempotente,
// §4.3), mai il contrario.

/** I parametri di una tappa. */
data class StageConfig(
  /** Quanto dura al massimo una tappa (D22: 8 minuti, margine sui 10 garantiti da JobScheduler). */
  val stageDuration: Duration = DEFAULT_STAGE_DURATION,
  /** Ogni quanto [StageScope.proceed] rivaluta davvero le regole (rilettura di impostazioni, contatori e stato). */
  val recheckEvery: Duration = 5.seconds,
  /** La pausa fra due lotti quando si va piano ([Pace.slow]: telefono caldo, o app in uso). */
  val reducedPaceDelay: Duration = 2.seconds,
) {
  init {
    require(stageDuration.isPositive()) { "Una tappa deve durare." }
    require(!recheckEvery.isNegative() && !reducedPaceDelay.isNegative()) { "Durate negative." }
  }

  companion object {
    val DEFAULT_STAGE_DURATION: Duration = 8.minutes
  }
}

/**
 * Dove si salva il punto di ripresa di un lavoro: durevole (database o DataStore), uno per lavoro. Un solo valore: il
 * punto da cui riprendere, nella forma che serve al lavoro ([C]: una cartella, un indice di unita', un cursore).
 */
interface CheckpointStore<C : Any> {
  /** Il punto salvato, o null se il lavoro e' da cominciare. */
  suspend fun load(): C?

  suspend fun save(checkpoint: C)

  /** Il lavoro e' finito: niente da riprendere. */
  suspend fun clear()
}

/**
 * Le regole come le vede una tappa: il verdetto e l'alimentazione. In produzione e' [EngineStageRules] sopra
 * [ActivityRulesEngine]; nei test c'e' una sceneggiatura (`run/testing`).
 */
interface StageRules {
  suspend fun decide(request: WorkRequest, running: Boolean): RuleDecision

  /** L'alimentazione e' collegata adesso (in carica i limiti dei bucket di Android non valgono, android.md 1.1). */
  suspend fun isPluggedIn(): Boolean
}

/** Il motore vero come [StageRules]. [stateProvider] deve essere lo stesso del motore. */
class EngineStageRules(
  private val engine: ActivityRulesEngine,
  private val stateProvider: SystemStateProvider,
) : StageRules {
  override suspend fun decide(request: WorkRequest, running: Boolean): RuleDecision = engine.evaluate(request, running)

  override suspend fun isPluggedIn(): Boolean = stateProvider.current().power.pluggedIn
}

/** Perche' [StageScope.proceed] ha detto di fermarsi. */
sealed interface StageHalt {
  /** Il tempo della tappa e' finito. */
  data object TimeUp : StageHalt

  /** Il tetto di giro (es. 50 MB di rilevamento fuori casa, [DetectionCap]) e' esaurito. */
  data object RoundCap : StageHalt

  /** I byte che restavano nel tetto mensile sono finiti e le regole non hanno (ancora) detto Stop. */
  data object ByteBudget : StageHalt

  /** Le regole hanno detto di fermarsi. */
  data class Rules(val stop: RuleDecision.Stop) : StageHalt
}

/**
 * Cio' che un lotto vede della tappa. Un lotto lungo chiama [proceed] fra un elemento e l'altro: cosi' puo' fermarsi
 * **a meta'** (stop delle regole, tempo, tetti) lasciando il punto di ripresa all'ultimo elemento finito.
 */
interface StageScope<C : Any> {
  val request: WorkRequest

  /** L'ultimo verdetto `Run` (aggiornato a ogni rivalutazione). */
  val run: RuleDecision.Run

  /** La rete sulla quale le regole hanno detto Run. */
  val network: NetworkClass get() = run.network

  /** [RunScope.LOCAL_BUFFER_ONLY]: niente rete, solo cio' che c'e' gia' in locale. */
  val runScope: RunScope get() = run.scope

  /** [Pace.REDUCED] (telefono caldo) o [Pace.APP_IN_USE] (app in primo piano): pause fra i lotti ([Pace.slow]). */
  val pace: Pace get() = run.pace

  /**
   * Quanti byte si possono ancora muovere in questa tappa: il piu' basso fra i byte che restano nel tetto mensile
   * (E-B10) e quelli che restano nel tetto di giro. `null` = nessun limite. Serve a dimensionare le letture.
   */
  val byteBudget: Long?

  /** I byte registrati in questa tappa. */
  val stageBytes: Long

  /** I byte registrati nel giro (le tappe gia' fatte del giro, se passate a [StagedRun.run], piu' questa). */
  val roundBytes: Long

  /** Perche' [proceed] ha detto di no, o null. */
  val halt: StageHalt?

  /**
   * Si puo' continuare? Falso se il tempo della tappa e' finito, un tetto e' esaurito o le regole hanno detto Stop.
   * Dopo il primo falso resta falso. Le regole si rivalutano (running = true) al massimo ogni
   * [StageConfig.recheckEvery]; subito se il tetto mensile e' finito. Propaga l'annullamento della coroutine.
   */
  suspend fun proceed(): Boolean

  /**
   * Registra **subito** nel contatore del traffico [bytes] passati sulla rete [network] (la rete davvero usata dal
   * socket, E-R12; se null quella dell'ultimo verdetto). Va chiamata per ogni trasferimento, non a fine lavoro.
   */
  suspend fun record(bytes: Long, network: NetworkClass? = null)

  /** Salva **subito** il punto di ripresa: da qui, se il processo muore, si riparte. Dopo [record], mai prima. */
  suspend fun commit(checkpoint: C)
}

/** Cosa riferisce un lotto. */
data class BatchResult<C : Any>(
  /** Il punto di ripresa dopo il lotto; null = invariato da ultimo [StageScope.commit]. */
  val checkpoint: C? = null,
  /** C'e' altro lavoro dopo questo lotto. Falso = il lavoro e' finito. */
  val hasMore: Boolean,
)

/**
 * Un lotto: un pezzo di lavoro di durata limitata (un'unita' di 200 media, qualche cartella di scansione, una testa).
 * [from] e' l'ultimo punto di ripresa salvato (null = da cominciare). Deve essere idempotente: puo' essere rifatto da
 * [from] dopo un crash.
 */
fun interface StageBatch<C : Any> {
  suspend fun run(scope: StageScope<C>, from: C?): BatchResult<C>
}

/** Perche' una tappa e' finita lasciando altro lavoro da fare. */
enum class YieldReason {
  /** 8 minuti: la prossima tappa riprende dal punto salvato. */
  TIME_SLICE,

  /** Il tetto di giro e' esaurito (es. 50 MB di rilevamento fuori casa): il resto al giro dopo o a casa. */
  ROUND_BYTE_CAP,

  /** Finiti i byte che restavano nel tetto mensile. */
  BYTE_BUDGET,
}

/** Quando conviene far partire la tappa dopo. */
enum class NextStage {
  /** In carica i limiti dei bucket non valgono: la catena puo' continuare subito. */
  IMMEDIATELY,

  /** A batteria (bucket Rare e Restricted: 10 minuti per finestra) o dopo un tetto: quando WorkManager lo consente. */
  WHEN_ALLOWED,
}

/** Numeri di una tappa (per la Diagnostica e per i test; mai nomi o percorsi). */
data class StageStats(
  val batches: Int,
  /** I byte registrati in questa tappa. */
  val bytes: Long,
  /** I byte del giro fin qui (da passare alla tappa dopo). */
  val roundBytes: Long,
  val elapsed: Duration,
  /** La tappa e' partita da un punto di ripresa salvato. */
  val resumed: Boolean,
)

/** Com'e' finita una tappa. */
sealed interface StageOutcome {
  val stats: StageStats

  /** Il lavoro e' finito: il punto di ripresa e' stato cancellato. */
  data class Finished(override val stats: StageStats) : StageOutcome

  /** La tappa e' finita per tempo o per un tetto e c'e' altro da fare: il punto di ripresa e' salvato. */
  data class Yielded(val why: YieldReason, val next: NextStage, override val stats: StageStats) : StageOutcome

  /** Le regole hanno detto Stop a lavoro iniziato: il punto di ripresa e' salvato. */
  data class Stopped(val reason: WaitReason, val alsoBlockedBy: List<WaitReason>, override val stats: StageStats) :
    StageOutcome

  /** Le regole non lasciano partire: nessun lotto eseguito. */
  data class Waiting(val reason: WaitReason, val alsoBlockedBy: List<WaitReason>, override val stats: StageStats) :
    StageOutcome

  /** Una richiesta dell'utente ("Fai ora") chiede conferma (dati mobili, roaming, tetto): nessun lotto eseguito. */
  data class NeedsConfirmation(val decision: RuleDecision.NeedsConfirmation, override val stats: StageStats) : StageOutcome
}

/**
 * Una tappa di un lavoro lungo, secondo il protocollo descritto sopra.
 *
 * @param request la richiesta alle regole; di norma `WorkRequest.Background(attivita')`, con `writesToNas = true` per i
 *   lavori che scrivono sul NAS (D10: con una share congelata la tappa non parte e dice `WaitReason.Frozen`).
 * @param timeSource orologio **monotono** per la durata della tappa (mai l'ora del telefono, D32).
 * @param roundCap il tetto di giro per rete (es. `DetectionCap::forNetwork`); null = nessun tetto di giro.
 */
class StagedRun<C : Any>(
  private val request: WorkRequest,
  private val rules: StageRules,
  private val ledger: TrafficLedger,
  private val checkpoints: CheckpointStore<C>,
  private val timeSource: TimeSource = TimeSource.Monotonic,
  private val config: StageConfig = StageConfig(),
  private val roundCap: ((NetworkClass) -> Long?)? = null,
) {

  private val source: TrafficSource =
    when (request) {
      is WorkRequest.Background -> TrafficSource.of(request.activity)
      is WorkRequest.UserInitiated -> TrafficSource.of(request.activity)
      WorkRequest.VisibleContent -> TrafficSource.VISIBLE_CONTENT
    }

  /**
   * Esegue una tappa. Si ferma, in quest'ordine di priorita': quando il lavoro e' finito ([BatchResult.hasMore] falso),
   * quando le regole dicono Stop, quando finisce il tempo o un tetto. Un'eccezione del lotto (o l'annullamento della
   * coroutine) la attraversa: il traffico registrato e il punto salvato restano, la tappa dopo riprende.
   *
   * @param roundBytesBefore i byte gia' letti nello stesso giro dalle tappe precedenti ([StageStats.roundBytes]).
   */
  suspend fun run(batch: StageBatch<C>, roundBytesBefore: Long = 0L): StageOutcome {
    require(roundBytesBefore >= 0) { "I byte di giro non sono negativi." }
    val started = timeSource.markNow()

    val first =
      when (val decision = rules.decide(request, running = false)) {
        is RuleDecision.Run -> decision
        is RuleDecision.Wait -> return StageOutcome.Waiting(decision.reason, decision.alsoBlockedBy, emptyStats(started, roundBytesBefore))
        // Con running = false il valutatore non dice Stop; se un'altra implementazione lo facesse, non si parte.
        is RuleDecision.Stop -> return StageOutcome.Waiting(decision.reason, decision.alsoBlockedBy, emptyStats(started, roundBytesBefore))
        is RuleDecision.NeedsConfirmation -> return StageOutcome.NeedsConfirmation(decision, emptyStats(started, roundBytesBefore))
      }

    val saved = checkpoints.load()
    val scope = Scope(first, started, roundBytesBefore, saved)
    var batches = 0

    // Appena valutato: niente rivalutazione, solo tempo e tetti (un giro gia' esaurito non parte nemmeno).
    if (!scope.check(force = false, fresh = true)) return scope.outcome(batches, resumed = saved != null)

    while (true) {
      val result = batch.run(scope, scope.latest)
      batches++
      result.checkpoint?.let { if (it != scope.latest) scope.commit(it) }
      if (!result.hasMore) {
        checkpoints.clear()
        return StageOutcome.Finished(scope.stats(batches, resumed = saved != null))
      }
      // Dopo ogni lotto: evaluate(running = true), tempo e tetti (anche se il lotto non ha mai chiamato proceed()).
      if (!scope.check(force = true)) return scope.outcome(batches, resumed = saved != null)
      if (scope.pace.slow && config.reducedPaceDelay.isPositive()) delay(config.reducedPaceDelay)
    }
  }

  private fun emptyStats(started: TimeMark, roundBytes: Long) =
    StageStats(batches = 0, bytes = 0L, roundBytes = roundBytes, elapsed = started.elapsedNow(), resumed = false)

  private inner class Scope(
    first: RuleDecision.Run,
    private val started: TimeMark,
    roundBytesBefore: Long,
    initial: C?,
  ) : StageScope<C> {

    override val request: WorkRequest get() = this@StagedRun.request
    override var run: RuleDecision.Run = first
      private set
    override var stageBytes: Long = 0L
      private set
    override var roundBytes: Long = roundBytesBefore
      private set
    override var halt: StageHalt? = null
      private set

    /** L'ultimo punto di ripresa noto (caricato, salvato da [commit] o cancellato a fine lavoro). */
    var latest: C? = initial
      private set

    private var evaluatedAt: TimeMark = timeSource.markNow()
    private var bytesSinceEvaluation: Long = 0L

    override val byteBudget: Long? get() = lowest(monthlyLeft(), roundLeft())

    private fun monthlyLeft(): Long? = run.remainingBytes?.let { (it - bytesSinceEvaluation).coerceAtLeast(0L) }

    private fun roundLeft(): Long? = roundCap?.invoke(run.network)?.let { (it - roundBytes).coerceAtLeast(0L) }

    override suspend fun proceed(): Boolean = check(force = false)

    /**
     * Il controllo di [StageScope.proceed]. [force]: rivaluta le regole comunque (dopo ogni lotto). [fresh]: le regole sono
     * state appena valutate, non serve rifarlo.
     */
    suspend fun check(force: Boolean, fresh: Boolean = false): Boolean {
      if (halt != null) return false
      currentCoroutineContext().ensureActive()
      if (started.elapsedNow() >= config.stageDuration) return stop(StageHalt.TimeUp)
      if (roundLeft() == 0L) return stop(StageHalt.RoundCap)
      if (fresh) return if (monthlyLeft() == 0L) stop(StageHalt.ByteBudget) else true

      val due = force || monthlyLeft() == 0L || evaluatedAt.elapsedNow() >= config.recheckEvery
      if (due) {
        when (val decision = rules.decide(request, running = true)) {
          is RuleDecision.Run -> {
            run = decision
            bytesSinceEvaluation = 0L
            evaluatedAt = timeSource.markNow()
          }
          is RuleDecision.Stop -> return stop(StageHalt.Rules(decision))
          // Con running = true il valutatore dice Stop; altre implementazioni: si tratta come Stop.
          is RuleDecision.Wait -> return stop(StageHalt.Rules(RuleDecision.Stop(decision.reason, decision.alsoBlockedBy)))
          is RuleDecision.NeedsConfirmation ->
            return stop(StageHalt.Rules(RuleDecision.Stop(WaitReason.ConfirmationRequired(decision.kinds))))
        }
        // Un verdetto Run che lascia zero byte non puo' far lavorare nessuno (e un lotto in ciclo non uscirebbe mai).
        if (roundLeft() == 0L) return stop(StageHalt.RoundCap)
        if (monthlyLeft() == 0L) return stop(StageHalt.ByteBudget)
      }
      return true
    }

    private fun stop(reason: StageHalt): Boolean {
      halt = reason
      return false
    }

    override suspend fun record(bytes: Long, network: NetworkClass?) {
      if (bytes <= 0L) return
      // Una rete che non porta traffico (offline, non riconosciuta) non ha un contatore: nulla da registrare.
      val used = TrafficNetwork.of(network ?: run.network) ?: return
      ledger.record(source, used, bytes)
      stageBytes = saturatingAdd(stageBytes, bytes)
      roundBytes = saturatingAdd(roundBytes, bytes)
      bytesSinceEvaluation = saturatingAdd(bytesSinceEvaluation, bytes)
    }

    override suspend fun commit(checkpoint: C) {
      checkpoints.save(checkpoint)
      latest = checkpoint
    }

    fun stats(batches: Int, resumed: Boolean) =
      StageStats(batches, stageBytes, roundBytes, started.elapsedNow(), resumed)

    /** L'esito di una tappa fermata da [halt]. */
    suspend fun outcome(batches: Int, resumed: Boolean): StageOutcome {
      val stats = stats(batches, resumed)
      return when (val reason = checkNotNull(halt) { "outcome() solo dopo un halt" }) {
        StageHalt.TimeUp ->
          StageOutcome.Yielded(
            YieldReason.TIME_SLICE,
            if (rules.isPluggedIn()) NextStage.IMMEDIATELY else NextStage.WHEN_ALLOWED,
            stats,
          )
        StageHalt.RoundCap -> StageOutcome.Yielded(YieldReason.ROUND_BYTE_CAP, NextStage.WHEN_ALLOWED, stats)
        StageHalt.ByteBudget -> StageOutcome.Yielded(YieldReason.BYTE_BUDGET, NextStage.WHEN_ALLOWED, stats)
        is StageHalt.Rules -> StageOutcome.Stopped(reason.stop.reason, reason.stop.alsoBlockedBy, stats)
      }
    }
  }
}
