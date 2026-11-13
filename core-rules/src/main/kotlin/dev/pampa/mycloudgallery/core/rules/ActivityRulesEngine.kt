package dev.pampa.mycloudgallery.core.rules

import dev.pampa.mycloudgallery.core.rules.settings.RuleSettingsStore
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficLedger
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Il motore delle regole (piano 1.6): l'UNICO punto che decide se un'attivita' puo' girare adesso.
 *
 * Unisce le impostazioni dell'utente ([RuleSettingsStore]), i consumi del mese ([TrafficLedger]) e lo
 * stato del sistema ([SystemStateProvider]) e li passa al [RuleEvaluator], che e' una funzione pura.
 * Nessuna UI e nessun lavoro vero: solo decisioni.
 *
 * Come lo usa chi esegue i lavori (Fase 6.2 e seguenti):
 *  1. prima di partire: `evaluate(WorkRequest.Background(att))`; se `Run` parte, se `Wait` no;
 *  2. a ogni lotto: `evaluate(request, running = true)`; se `Stop` salva il punto di ripresa ed esce;
 *  3. dopo ogni trasferimento: `ledger.record(...)` con la rete EFFETTIVAMENTE usata.
 */
class ActivityRulesEngine(
  private val settings: RuleSettingsStore,
  private val ledger: TrafficLedger,
  private val stateProvider: SystemStateProvider,
  private val evaluator: RuleEvaluator = RuleEvaluator(),
) {

  fun canMeasureDevice(): Boolean = evaluator.canMeasureDevice(stateProvider.current().power)

  private val lastBatteryStop = MutableStateFlow<Int?>(null)

  /**
   * **Il livello che ha fermato un lavoro per la batteria** nell'ultimo verdetto di questo motore (pacchetto B, BU-7): il numero che la pagina dice accanto a «Batteria sotto il…».
   * Viene dal **verdetto** (`WaitReason.BatteryLow` porta gia' livello e soglia), non dall'impostazione di adesso: subito dopo che l'utente ha cambiato il livello, e finche' il lavoro non e'
   * rivalutato, la frase dice ancora il livello che l'ha fermato e non un numero nuovo accanto a un fermo che non gli corrisponde. Null finche' nessun lavoro e' stato fermato per la batteria:
   * allora le pagine dicono la frase senza numero.
   */
  val batteryStopThreshold: StateFlow<Int?> get() = lastBatteryStop

  private fun noteBattery(decision: RuleDecision) {
    val reasons = when (decision) {
      is RuleDecision.Wait -> listOf(decision.reason) + decision.alsoBlockedBy
      is RuleDecision.Stop -> listOf(decision.reason) + decision.alsoBlockedBy
      else -> return
    }
    reasons.firstNotNullOfOrNull { it as? WaitReason.BatteryLow }?.let { lastBatteryStop.value = it.thresholdPercent }
  }

  suspend fun evaluate(request: WorkRequest, running: Boolean = false): RuleDecision =
    evaluator.evaluate(
      request = request,
      rules = settings.current().toRuleSet(),
      state = stateProvider.current(),
      usage = ledger.currentUsage(),
      running = running,
    ).also(::noteBattery)

  /** Il verdetto di tutte e sei le attivita' in background, per la Diagnostica e per la UI. */
  suspend fun evaluateAll(): Map<BackgroundActivity, RuleDecision> {
    val rules = settings.current().toRuleSet()
    val state = stateProvider.current()
    val usage = ledger.currentUsage()
    return BackgroundActivity.entries.associateWith {
      evaluator.evaluate(WorkRequest.Background(it), rules, state, usage).also(::noteBattery)
    }
  }

  /**
   * I verdetti, riemessi quando cambia qualcosa: impostazioni, consumi, stato del sistema, e ogni
   * [tick] (il passare del tempo da solo apre e chiude le fasce, cambia il mese e finisce l'isteresi
   * termica senza nessun altro evento). Emette solo se almeno un verdetto e' cambiato.
   */
  fun observeAll(tick: Duration = Duration.ofMinutes(1)): Flow<Map<BackgroundActivity, RuleDecision>> {
    require(!tick.isZero && !tick.isNegative) { "Il timer deve avere una durata positiva." }
    val ticks = flow {
      while (true) {
        emit(Unit)
        delay(tick.toMillis())
      }
    }
    return merge(
        settings.observe().map { },
        ledger.observeCurrentUsage().map { },
        stateProvider.observe().map { },
        ticks,
      )
      .conflate()
      .map { evaluateAll() }
      .distinctUntilChanged()
  }
}
