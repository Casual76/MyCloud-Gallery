package dev.pampa.mycloudgallery.core.rules

import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.rules.traffic.CycleUsage
import java.time.Duration

/** Cosa si chiede al motore. */
sealed interface WorkRequest {

  /**
   * Il lavoro **scrive sul NAS** (pubblicazione dell'indice, prenotazioni, compattazione, pulizie, operazioni in coda,
   * backup): con una share congelata per cambiamenti di massa (D10, [SystemState.nasFrozen]) non parte e dice
   * [WaitReason.Frozen]. Falso per tutto cio' che legge soltanto (la scansione, lo scaricamento di segmenti e miniature):
   * la scansione deve poter girare per accorgersi che la situazione e' tornata normale.
   */
  val writesToNas: Boolean get() = false

  /**
   * Un lavoro in background (pianificato). Se e' [BackgroundActivity.INDEX_SYNC] e l'app e' in primo
   * piano, vale la regola della proposta 3: sempre consentito, tranne tetti e roaming.
   */
  data class Background(val activity: BackgroundActivity, override val writesToNas: Boolean = false) : WorkRequest

  /**
   * "Fai ora" (piano §3, proposta 10): lavoro avviato dall'utente. Scavalca fascia, alimentazione,
   * reti ammesse, risparmio energetico e quote di Android, ma su dati mobili / roaming / oltre il tetto
   * chiede conferma, mostrando la stima.
   *
   * @property estimatedBytes traffico previsto, per la stima nella conferma e per capire se supera il tetto.
   * @property confirmed l'utente ha gia' confermato.
   */
  data class UserInitiated(
    val activity: BackgroundActivity,
    val estimatedBytes: Long? = null,
    val confirmed: Boolean = false,
    override val writesToNas: Boolean = false,
  ) : WorkRequest

  /** Miniature e anteprime di cio' che e' sullo schermo (solo con l'app in primo piano). */
  data object VisibleContent : WorkRequest
}

/**
 * Soglie del valutatore. I valori sono quelli di docs/android.md (2.3) e sono tutti sostituibili.
 *
 * @property suspendAt da questo stato termico in su il background si ferma.
 * @property hardStopAt da qui in su si ferma TUTTO, anche "Fai ora" e il contenuto visibile: e' la
 *   sicurezza del dispositivo, non una preferenza.
 * @property headroomReduce con `getThermalHeadroom` oltre questo valore si va al ritmo ridotto.
 * @property headroomSuspend con `getThermalHeadroom` oltre questo valore il background si ferma.
 * @property thermalCooldown dopo uno stato severo si riparte solo dopo questo tempo (isteresi).
 * @property unplugGrace un lavoro in corso tollera un distacco dell'alimentazione piu' breve di cosi'
 *   (falsi distacchi di qualche secondo visti sull'S25).
 */
data class EvaluatorConfig(
  val suspendAt: ThermalStatus = ThermalStatus.SEVERE,
  val hardStopAt: ThermalStatus = ThermalStatus.CRITICAL,
  val headroomReduce: Float = 0.85f,
  val headroomSuspend: Float = 0.95f,
  val thermalCooldown: Duration = Duration.ofMinutes(5),
  val unplugGrace: Duration = Duration.ofSeconds(15),
) {
  /** Il telefono e' in stato termico severo adesso (per stato o per headroom). */
  fun isSevere(power: PowerState): Boolean =
    power.thermalStatus >= suspendAt || (power.thermalHeadroom ?: 0f) >= headroomSuspend
}

/**
 * Il valutatore: funzione pura da (richiesta, regole, stato del sistema, consumi del mese) a un verdetto
 * con il motivo leggibile. Non legge l'orologio, non tocca Android, non ha stato: ogni test costruisce
 * lo stato che vuole.
 *
 * L'ordine dei motivi e' quello della priorita' con cui si mostrano all'utente; [RuleDecision.Wait.reason]
 * e' il primo, gli altri stanno in `alsoBlockedBy` (per la Diagnostica):
 *
 *   profilo, ruolo, attivita' spenta, NAS congelato (solo per chi scrive sul NAS), roaming, offline, rete non
 *   riconosciuta, rete scadente, fascia oraria, alimentazione, batteria, risparmio energetico, temperatura, rete non
 *   ammessa, attivita' pesante, tetto, limiti di Android, NAS.
 */
class RuleEvaluator(private val config: EvaluatorConfig = EvaluatorConfig()) {

  /**
   * @param running il lavoro e' GIA' in corso: i "non puo' girare" diventano [RuleDecision.Stop]
   *   ("fermati": salva il punto di ripresa ed esci) e un breve distacco dell'alimentazione si tollera.
   * @param usage i consumi del ciclo in corso, per i tetti.
   */
  fun evaluate(
    request: WorkRequest,
    rules: RuleSet,
    state: SystemState,
    usage: CycleUsage,
    running: Boolean = false,
  ): RuleDecision {
    val network = NetworkClassifier.classify(state.network)
    // D10: solo i lavori che scrivono sul NAS vedono il congelamento.
    val frozen = if (request.writesToNas) state.nasFrozen else null
    val decision =
      when (request) {
        is WorkRequest.Background ->
          if (request.activity == BackgroundActivity.INDEX_SYNC && state.appInForeground) {
            foreground(request.activity, rules, state, usage, network, frozen)
          } else {
            background(request.activity, rules, state, usage, network, running, frozen)
          }
        is WorkRequest.UserInitiated -> userInitiated(request, rules, state, usage, network, frozen)
        WorkRequest.VisibleContent ->
          if (state.appInForeground) foreground(null, rules, state, usage, network, frozen = null)
          else RuleDecision.Wait(WaitReason.AppNotVisible)
      }
    return if (running) decision.asStop() else decision
  }

  // --- Lavoro in background -------------------------------------------------------------------------

  private fun background(
    activity: BackgroundActivity,
    rules: RuleSet,
    state: SystemState,
    usage: CycleUsage,
    network: NetworkClass,
    running: Boolean,
    frozen: FreezeReason?,
  ): RuleDecision {
    val rule = rules.ruleFor(activity)
    val power = state.power
    val blockers = ArrayList<WaitReason>()

    if (!state.profileActive) blockers += WaitReason.ProfileInactive
    activity.requiredRole?.let { if (!state.roles.has(it)) blockers += WaitReason.RoleDisabled(it) }
    if (!rule.enabled) blockers += WaitReason.ActivityDisabled
    if (frozen != null) blockers += WaitReason.Frozen(frozen)

    val roamingBlocked = network == NetworkClass.ROAMING && AllowedNetwork.ROAMING !in rule.networks
    if (roamingBlocked) blockers += WaitReason.Roaming
    if (network == NetworkClass.OFFLINE) blockers += WaitReason.Offline
    if (network == NetworkClass.UNTRUSTED) blockers += WaitReason.NetworkNotTrusted
    // Rete scadente (E-R4): solo il lavoro automatico si ferma; "Fai ora" e il contenuto visibile no.
    if (network.carriesTraffic && state.network.poor) blockers += WaitReason.PoorNetwork

    rule.window?.let { window ->
      if (!window.contains(state.now.toLocalTime())) {
        blockers += WaitReason.OutsideWindow(window, window.nextStart(state.now))
      }
    }

    // "In carica" = alimentazione collegata, anche con la carica sospesa. Un lavoro in corso tollera
    // un distacco brevissimo (falsi distacchi di qualche secondo).
    val plugged = power.pluggedIn || (running && power.unpluggedFor <= config.unplugGrace)
    if (rule.power == PowerPolicy.PLUGGED_ONLY && !plugged) blockers += WaitReason.NeedsPower
    if (!plugged && power.levelPercent < rules.batteryThresholdPercent) {
      blockers += WaitReason.BatteryLow(power.levelPercent, rules.batteryThresholdPercent)
    }
    if (rules.pauseOnPowerSaver && power.powerSaveMode) blockers += WaitReason.PowerSaver

    val thermalReason = thermalBlock(power)
    if (thermalReason != null) blockers += thermalReason

    var cap: CapStatus? = null
    val usesNetwork = network.carriesTraffic && !roamingBlocked
    if (usesNetwork) {
      if (network.asAllowedNetwork() !in rule.networks) blockers += WaitReason.NetworkNotAllowed(network)
      if (activity.weight == ActivityWeight.HEAVY &&
        rules.heavyOnlyAtHome &&
        network != NetworkClass.HOME_WIFI
      ) {
        blockers += WaitReason.HeavyNeedsHomeWifi
      }
      cap = capStatus(network, rules, usage)
      if (cap != null && cap.reached) blockers += cap.toReason()
    }

    if (state.androidLimits.blocks(activity)) {
      val budget = if (state.androidLimits.jobQuotaExhausted) null else activity.budget
      blockers += WaitReason.AndroidLimit(budget)
    }
    if (usesNetwork && !state.nasReachable) blockers += WaitReason.NasUnreachable

    val pace = paceFor(activity, power)
    return when {
      blockers.isEmpty() ->
        RuleDecision.Run(RunScope.FULL, pace, network, remaining(cap))
      // Notte senza NAS (E-A6): il Calcolo AI elabora il buffer che ha e poi si ferma.
      activity.canRunOnLocalBuffer && blockers.all { it.isNetworkRelated } ->
        RuleDecision.Run(RunScope.LOCAL_BUFFER_ONLY, pace, network, remainingBytes = null)
      else -> RuleDecision.Wait(blockers.first(), blockers.drop(1))
    }
  }

  // --- App in primo piano ---------------------------------------------------------------------------

  /**
   * Sync dell'indice e miniature visibili con l'app aperta (proposta 3): fascia, alimentazione, batteria,
   * risparmio energetico, reti ammesse, "pesante" e limiti di Android NON si applicano. Restano
   * raggiungibilita', roaming, tetti e la sicurezza termica.
   */
  private fun foreground(
    activity: BackgroundActivity?,
    rules: RuleSet,
    state: SystemState,
    usage: CycleUsage,
    network: NetworkClass,
    frozen: FreezeReason?,
  ): RuleDecision {
    val rule = activity?.let { rules.ruleFor(it) }
    val blockers = ArrayList<WaitReason>()

    if (!state.profileActive) blockers += WaitReason.ProfileInactive
    if (frozen != null) blockers += WaitReason.Frozen(frozen)
    val roamingBlocked =
      network == NetworkClass.ROAMING && (rule == null || AllowedNetwork.ROAMING !in rule.networks)
    if (roamingBlocked) blockers += WaitReason.Roaming
    if (network == NetworkClass.OFFLINE) blockers += WaitReason.Offline
    if (network == NetworkClass.UNTRUSTED) blockers += WaitReason.NetworkNotTrusted
    if (state.power.thermalStatus >= config.hardStopAt) {
      blockers += WaitReason.Thermal(state.power.thermalStatus)
    }

    var cap: CapStatus? = null
    val usesNetwork = network.carriesTraffic && !roamingBlocked
    if (usesNetwork) {
      cap = capStatus(network, rules, usage)
      if (cap != null && cap.reached) blockers += cap.toReason()
      if (!state.nasReachable) blockers += WaitReason.NasUnreachable
    }

    return if (blockers.isEmpty()) {
      RuleDecision.Run(RunScope.FULL, Pace.NORMAL, network, remaining(cap))
    } else {
      RuleDecision.Wait(blockers.first(), blockers.drop(1))
    }
  }

  // --- "Fai ora" ------------------------------------------------------------------------------------

  private fun userInitiated(
    request: WorkRequest.UserInitiated,
    rules: RuleSet,
    state: SystemState,
    usage: CycleUsage,
    network: NetworkClass,
    frozen: FreezeReason?,
  ): RuleDecision {
    val activity = request.activity
    val blockers = ArrayList<WaitReason>()

    activity.requiredRole?.let { if (!state.roles.has(it)) blockers += WaitReason.RoleDisabled(it) }
    // D10: neanche un "Fai ora" scrive sul NAS finche' la share e' congelata (come la sicurezza termica, non e' una preferenza).
    if (frozen != null) blockers += WaitReason.Frozen(frozen)
    if (network == NetworkClass.OFFLINE) blockers += WaitReason.Offline
    if (network == NetworkClass.UNTRUSTED) blockers += WaitReason.NetworkNotTrusted
    if (state.power.thermalStatus >= config.hardStopAt) {
      blockers += WaitReason.Thermal(state.power.thermalStatus)
    }
    if (network.carriesTraffic && !state.nasReachable) blockers += WaitReason.NasUnreachable

    if (blockers.isNotEmpty()) {
      return if (activity.canRunOnLocalBuffer && blockers.all { it.isNetworkRelated }) {
        RuleDecision.Run(RunScope.LOCAL_BUFFER_ONLY, Pace.NORMAL, network, remainingBytes = null)
      } else {
        RuleDecision.Wait(blockers.first(), blockers.drop(1))
      }
    }

    // Qui la rete porta traffico. Cio' che costa soldi o supera il tetto chiede conferma.
    val cap = capStatus(network, rules, usage)
    val kinds = LinkedHashSet<ConfirmationKind>()
    when (network) {
      NetworkClass.ROAMING -> kinds += ConfirmationKind.ROAMING
      NetworkClass.MOBILE -> kinds += ConfirmationKind.MOBILE_DATA
      else -> Unit
    }
    val estimated = request.estimatedBytes
    if (cap != null && (cap.reached || (estimated != null && estimated > cap.remaining))) {
      kinds += ConfirmationKind.OVER_CAP
    }

    if (kinds.isNotEmpty() && !request.confirmed) {
      return RuleDecision.NeedsConfirmation(
        kinds = kinds,
        estimate =
          TrafficEstimate(
            bytes = estimated,
            monthlyUsedBytes = if (network.isMobileLike()) usage.mobileBytes else usage.wifiBytes,
            monthlyCapBytes = cap?.cap,
          ),
      )
    }
    // Confermato oltre il tetto: il tetto non ferma piu' questo lavoro.
    val remaining = if (ConfirmationKind.OVER_CAP in kinds) null else remaining(cap)
    return RuleDecision.Run(RunScope.FULL, Pace.NORMAL, network, remaining)
  }

  // --- Pezzi comuni ---------------------------------------------------------------------------------

  private fun RuleDecision.asStop(): RuleDecision =
    when (this) {
      is RuleDecision.Wait -> RuleDecision.Stop(reason, alsoBlockedBy)
      is RuleDecision.NeedsConfirmation ->
        RuleDecision.Stop(WaitReason.ConfirmationRequired(kinds))
      else -> this
    }

  /** Il motivo termico per fermare il background (stato severo, headroom alto o isteresi in corso). */
  private fun thermalBlock(power: PowerState): WaitReason.Thermal? {
    val cooling = power.timeSinceThermalSevere?.let { it < config.thermalCooldown } ?: false
    return if (config.isSevere(power) || cooling) WaitReason.Thermal(power.thermalStatus) else null
  }

  /** Con il telefono caldo (LIGHT/MODERATE o headroom alto) le attivita' a CPU vanno piano. */
  private fun paceFor(activity: BackgroundActivity, power: PowerState): Pace {
    val warm =
      power.thermalStatus >= ThermalStatus.LIGHT || (power.thermalHeadroom ?: 0f) >= config.headroomReduce
    return if (activity.cpuIntensive && warm) Pace.REDUCED else Pace.NORMAL
  }

  private fun NetworkClass.isMobileLike() = this == NetworkClass.MOBILE || this == NetworkClass.ROAMING

  private fun capStatus(network: NetworkClass, rules: RuleSet, usage: CycleUsage): CapStatus? =
    when (network) {
      NetworkClass.MOBILE,
      NetworkClass.ROAMING ->
        rules.caps.mobileBytes?.let { CapStatus(CapKind.MOBILE, usage.mobileBytes, it) }
      NetworkClass.HOME_WIFI,
      NetworkClass.EXTERNAL_WIFI ->
        rules.caps.wifiBytes?.let { CapStatus(CapKind.WIFI, usage.wifiBytes, it) }
      NetworkClass.OFFLINE,
      NetworkClass.UNTRUSTED -> null
    }

  private fun remaining(cap: CapStatus?): Long? = cap?.remaining

  /** Consumi del ciclo contro il tetto della rete in uso. */
  private class CapStatus(val kind: CapKind, val used: Long, val cap: Long) {
    val reached: Boolean get() = used >= cap
    val remaining: Long get() = (cap - used).coerceAtLeast(0L)

    fun toReason() = WaitReason.CapReached(kind, used, cap)
  }
}
