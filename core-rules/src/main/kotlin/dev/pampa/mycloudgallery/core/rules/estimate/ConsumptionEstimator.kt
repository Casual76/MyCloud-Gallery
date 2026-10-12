package dev.pampa.mycloudgallery.core.rules.estimate

import dev.pampa.mycloudgallery.core.rules.ActivityRule
import dev.pampa.mycloudgallery.core.rules.ActivityWeight
import dev.pampa.mycloudgallery.core.rules.AllowedNetwork
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.ByteFormat
import dev.pampa.mycloudgallery.core.rules.PowerPolicy
import dev.pampa.mycloudgallery.core.rules.RuleSet
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

/**
 * Lavoro di una attivita': quanti elementi e quanti byte di TRAFFICO verso il NAS comporta.
 *
 * Per il Calcolo AI i byte sono quelli delle anteprime di analisi da scaricare (~150 KB l'una,
 * [DeviceMeasures.analysisPreviewBytes]); il tempo si calcola sugli elementi.
 */
data class Workload(val items: Long, val bytes: Long) {
  init {
    require(items >= 0 && bytes >= 0) { "Un lavoro non puo' essere negativo." }
  }

  companion object {
    val NONE = Workload(0, 0)
  }
}

/**
 * La dimensione della libreria che aspetta di essere lavorata ([pending], l'arretrato di adesso) e il
 * flusso ordinario di media nuovi ([monthlyNew], per i dati e la batteria "a regime").
 */
data class LibraryBacklog(
  val pending: Map<BackgroundActivity, Workload> = emptyMap(),
  val monthlyNew: Map<BackgroundActivity, Workload> = emptyMap(),
) {
  fun pending(activity: BackgroundActivity): Workload = pending[activity] ?: Workload.NONE

  fun monthlyNew(activity: BackgroundActivity): Workload = monthlyNew[activity] ?: Workload.NONE
}

/**
 * Le misure del dispositivo che alimentano le stime. I valori di default sono quelli della Fase 0 o,
 * dove la Fase 0 non ha misurato, ipotesi dichiarate: tutti sostituibili passando un'istanza diversa
 * (es. dopo una settimana reale, piano 4.6).
 *
 * | Costante | Valore | Fonte |
 * |---|---|---|
 * | [smbWriteBytesPerSecond] | 9,0 MB/s | docs/inventario-nas.md §4, S25, scrittura con cifratura (tablet 7,3) |
 * | [smbReadBytesPerSecond] | 16,0 MB/s | idem, lettura cifrata con 4 flussi (S25 16,3, tablet 15,8) |
 * | [aiItemsPerSecond] | 2,8 foto/s | docs/decisioni.md, GPU, con decodifica dagli originali (700 foto) |
 * | [aiBatteryPercentPerThousandItems] | 4 % | idem, ~4% di batteria ogni 1000 foto su GPU |
 * | [transferBatteryPercentPerGigabyte] | 0,5 % | IPOTESI (non misurata in Fase 0): Wi-Fi attivo + cifratura SMB + hash |
 * | [mobileEnergyFactor] | 2,0 | IPOTESI: la radio cellulare costa ~2x il Wi-Fi a parita' di byte |
 * | [analysisPreviewBytes] | 150 KB | docs/decisioni.md, anteprima di analisi ~768 px (E-I11) |
 */
data class DeviceMeasures(
  val smbWriteBytesPerSecond: Double = 9.0e6,
  val smbReadBytesPerSecond: Double = 16.0e6,
  val aiItemsPerSecond: Double = 2.8,
  val aiBatteryPercentPerThousandItems: Double = 4.0,
  val transferBatteryPercentPerGigabyte: Double = 0.5,
  val mobileEnergyFactor: Double = 2.0,
  val analysisPreviewBytes: Long = 150_000L,
) {
  init {
    require(smbWriteBytesPerSecond > 0 && smbReadBytesPerSecond > 0 && aiItemsPerSecond > 0) {
      "Le velocita' misurate devono essere positive."
    }
  }

  companion object {
    /** Le misure della Fase 0, sul Galaxy S25. */
    val PHASE_0 = DeviceMeasures()
  }
}

/**
 * Come il dispositivo e' usato in una giornata tipo: serve a sapere quante ore al giorno ogni regola
 * lascia davvero libere. Sono ipotesi (nessuna misura in Fase 0): sostituibili con le abitudini
 * osservate dall'app.
 *
 * @property pluggedHoursPerDay ore al giorno con l'alimentazione collegata (la notte).
 * @property homeWifiHoursPerDay ore al giorno sul Wi-Fi di casa.
 * @property externalWifiHoursPerDay ore al giorno su un altro Wi-Fi (via VPN).
 * @property mobileHoursPerDay ore al giorno sui dati mobili.
 * @property efficiency quota del tempo "utile" che e' davvero lavoro (risvegli del NAS, ripartenze,
 *   interruzioni, riavvii notturni: E-P13).
 */
data class UsageProfile(
  val pluggedHoursPerDay: Double = 8.0,
  val homeWifiHoursPerDay: Double = 14.0,
  val externalWifiHoursPerDay: Double = 1.0,
  val mobileHoursPerDay: Double = 8.0,
  val efficiency: Double = 0.8,
) {
  init {
    require(efficiency in 0.0..1.0) { "L'efficienza e' una frazione." }
  }
}

/** Le stime di una singola attivita'. */
data class ActivityEstimate(
  /** Ore di lavoro vero che servono per smaltire l'arretrato (rete e dispositivo). */
  val workHours: Double,
  /** Ore al giorno in cui le regole la lasciano girare. */
  val usableHoursPerDay: Double,
  /** Energia dell'arretrato, in % di batteria. */
  val pendingEnergyPercent: Double,
  /** Quota dell'arretrato che si smaltisce in una notte (0..1). */
  val fractionPerNight: Double,
  /** Giorni per finire da sola (null = mai con queste regole). */
  val daysToFinish: Double?,
  val enabled: Boolean,
)

/**
 * Le tre stime del piano (§4.6), piu' qualche dettaglio.
 *
 * @property batteryPercentPerNight energia spesa in una notte mentre c'e' arretrato, in % della batteria.
 *   Con alimentazione collegata e' coperta dal caricabatterie: guarda [batteryDrainPercentPerNight].
 * @property batteryDrainPercentPerNight la parte che pesa sulla batteria: quella delle attivita' che
 *   possono girare anche a batteria. Con il preset Notte e' zero.
 * @property steadyBatteryPercentPerDay energia al giorno a regime (solo media nuovi). Obiettivo del
 *   piano §4: sotto il 2% con il preset Notte.
 * @property mobileBytesPerMonth dati mobili al mese a regime, entro il tetto.
 * @property wifiBytesPerMonth dati Wi-Fi al mese a regime.
 * @property mobileBytesFirstMonth dati mobili nel primo mese, arretrato compreso, entro il tetto.
 * @property daysToFinish giorni per smaltire tutto l'arretrato; null = non finisce mai con queste regole.
 */
data class ConsumptionEstimate(
  val perActivity: Map<BackgroundActivity, ActivityEstimate>,
  val batteryPercentPerNight: Double,
  val batteryDrainPercentPerNight: Double,
  val steadyBatteryPercentPerDay: Double,
  val mobileBytesPerMonth: Long,
  val wifiBytesPerMonth: Long,
  val mobileBytesFirstMonth: Long,
  val daysToFinish: Double?,
)

/** L'interfaccia dello stimatore: dimensioni della libreria + misure del dispositivo -> consumi. */
interface ConsumptionEstimator {
  fun estimate(
    rules: RuleSet,
    backlog: LibraryBacklog,
    measures: DeviceMeasures = DeviceMeasures.PHASE_0,
    profile: UsageProfile = UsageProfile(),
  ): ConsumptionEstimate
}

/**
 * Lo stimatore. Formule (documentate anche in docs/regole-attivita.md):
 *
 *  - **Tempo di lavoro** dell'arretrato: backup = byte / velocita' di scrittura SMB; sync indice,
 *    preparazione e anteprime HQ = byte / velocita' di lettura SMB; Calcolo AI = elementi / foto al secondo.
 *  - **Ore utili al giorno** di una attivita' = min(ore della fascia, ore di carica se serve la carica,
 *    ore delle reti ammesse) x efficienza. Il Calcolo AI non e' limitato dalla rete (lavora in locale).
 *    Le attivita' pesanti, con "pesanti solo a casa", hanno solo il Wi-Fi di casa.
 *  - **Tempo per finire**: le attivita' di rete si dividono la stessa banda (il collo di bottiglia e' il
 *    NAS), quindi i loro tempi si sommano: giorni = somma dei tempi / ore utili; il Calcolo AI e' in
 *    parallelo. Totale = il maggiore dei due. E' una stima prudente.
 *  - **Energia**: trasferimento = GB x %/GB (x [DeviceMeasures.mobileEnergyFactor] per la quota che passa
 *    sui dati mobili); Calcolo AI = migliaia di foto x %/1000.
 *  - **Dati al mese**: i media nuovi del mese, ripartiti fra le reti ammesse in proporzione alle ore
 *    giornaliere su ciascuna; la quota mobile e' limitata dal tetto.
 */
class DefaultConsumptionEstimator : ConsumptionEstimator {

  override fun estimate(
    rules: RuleSet,
    backlog: LibraryBacklog,
    measures: DeviceMeasures,
    profile: UsageProfile,
  ): ConsumptionEstimate {
    val rows = BackgroundActivity.entries.map { analyze(it, rules, backlog, measures, profile) }

    // Le attivita' di rete condividono la banda: i loro tempi si sommano.
    val networkRows = rows.filter { it.activity != BackgroundActivity.AI_COMPUTE && it.enabled }
    val networkTotalSeconds = networkRows.sumOf { it.workSeconds }

    val estimates = HashMap<BackgroundActivity, ActivityEstimate>()
    var energyPerNight = 0.0
    var drainPerNight = 0.0
    var daysToFinish: Double? = 0.0

    for (row in rows) {
      val usableSeconds = row.usableHours * SECONDS_PER_HOUR
      val pool = if (row.activity == BackgroundActivity.AI_COMPUTE) row.workSeconds else networkTotalSeconds
      val fraction =
        when {
          !row.enabled || row.workSeconds <= 0.0 -> 0.0
          pool <= 0.0 -> 0.0
          else -> min(1.0, usableSeconds / pool)
        }
      val days =
        when {
          !row.enabled || row.workSeconds <= 0.0 -> 0.0
          usableSeconds <= 0.0 -> null
          else -> pool / usableSeconds
        }
      energyPerNight += fraction * row.pendingEnergy
      if (row.rule.power == PowerPolicy.BATTERY_ALLOWED) drainPerNight += fraction * row.pendingEnergy

      daysToFinish = if (days == null || daysToFinish == null) null else maxOf(daysToFinish, days)
      estimates[row.activity] =
        ActivityEstimate(
          workHours = row.workSeconds / SECONDS_PER_HOUR,
          usableHoursPerDay = row.usableHours,
          pendingEnergyPercent = row.pendingEnergy,
          fractionPerNight = fraction,
          daysToFinish = days,
          enabled = row.enabled,
        )
    }

    val enabledRows = rows.filter { it.enabled }
    val steady = enabledRows.sumOf { it.monthlyEnergy } / DAYS_PER_MONTH

    // Dati mensili a regime e nel primo mese.
    var mobileSteady = 0.0
    var mobileFirst = 0.0
    var allSteady = 0.0
    for (row in enabledRows) {
      allSteady += row.monthlyBytes
      mobileSteady += row.monthlyBytes * row.mobileShare
      mobileFirst += (row.monthlyBytes + row.pendingBytes) * row.mobileShare
    }
    val mobileCap = rules.caps.mobileBytes?.toDouble()
    val mobileMonthly = if (mobileCap != null) min(mobileSteady, mobileCap) else mobileSteady
    val mobileFirstMonth = if (mobileCap != null) min(mobileFirst, mobileCap) else mobileFirst
    var wifiMonthly = (allSteady - mobileMonthly).coerceAtLeast(0.0)
    rules.caps.wifiBytes?.let { wifiMonthly = min(wifiMonthly, it.toDouble()) }

    return ConsumptionEstimate(
      perActivity = estimates,
      batteryPercentPerNight = energyPerNight,
      batteryDrainPercentPerNight = drainPerNight,
      steadyBatteryPercentPerDay = steady,
      mobileBytesPerMonth = mobileMonthly.toLong(),
      wifiBytesPerMonth = wifiMonthly.toLong(),
      mobileBytesFirstMonth = mobileFirstMonth.toLong(),
      daysToFinish = daysToFinish,
    )
  }

  /** Tutti i numeri intermedi di una attivita'. */
  private class Row(
    val activity: BackgroundActivity,
    val rule: ActivityRule,
    val enabled: Boolean,
    val workSeconds: Double,
    val usableHours: Double,
    val pendingEnergy: Double,
    val monthlyEnergy: Double,
    val pendingBytes: Double,
    val monthlyBytes: Double,
    val mobileShare: Double,
  )

  private fun analyze(
    activity: BackgroundActivity,
    rules: RuleSet,
    backlog: LibraryBacklog,
    measures: DeviceMeasures,
    profile: UsageProfile,
  ): Row {
    val rule = rules.ruleFor(activity)
    val pending = backlog.pending(activity)
    val monthly = backlog.monthlyNew(activity)

    // Reti davvero disponibili: le pesanti, con la regola "solo a casa", hanno solo il Wi-Fi di casa.
    val networks =
      if (activity.weight == ActivityWeight.HEAVY && rules.heavyOnlyAtHome) {
        rule.networks.intersect(setOf(AllowedNetwork.HOME_WIFI))
      } else {
        rule.networks
      }
    val hoursByNetwork = networks.associateWith { hoursOn(it, profile) }
    val networkHours = min(HOURS_PER_DAY, hoursByNetwork.values.sum())
    val mobileShare =
      if (AllowedNetwork.MOBILE_DATA in networks && hoursByNetwork.values.sum() > 0.0) {
        profile.mobileHoursPerDay / hoursByNetwork.values.sum()
      } else {
        0.0
      }

    val windowHours = rule.window?.length?.toMinutes()?.div(60.0) ?: HOURS_PER_DAY
    val powerHours =
      if (rule.power == PowerPolicy.PLUGGED_ONLY) profile.pluggedHoursPerDay else HOURS_PER_DAY
    val limits = mutableListOf(windowHours, powerHours)
    // Il Calcolo AI lavora in locale: la rete serve solo a scaricare le anteprime.
    if (!activity.canRunOnLocalBuffer) limits += networkHours
    val usable = if (rule.enabled) limits.min().coerceIn(0.0, HOURS_PER_DAY) * profile.efficiency else 0.0

    return Row(
      activity = activity,
      rule = rule,
      enabled = rule.enabled,
      workSeconds = workSeconds(activity, pending, measures),
      usableHours = usable,
      pendingEnergy = energy(activity, pending, measures, mobileShare),
      monthlyEnergy = energy(activity, monthly, measures, mobileShare),
      pendingBytes = pending.bytes.toDouble(),
      monthlyBytes = monthly.bytes.toDouble(),
      mobileShare = mobileShare,
    )
  }

  private fun hoursOn(network: AllowedNetwork, profile: UsageProfile): Double =
    when (network) {
      AllowedNetwork.HOME_WIFI -> profile.homeWifiHoursPerDay
      AllowedNetwork.ANY_WIFI_VPN -> profile.externalWifiHoursPerDay
      AllowedNetwork.MOBILE_DATA -> profile.mobileHoursPerDay
      AllowedNetwork.ROAMING -> 0.0 // mai pianificato: il roaming e' un'eccezione, non una risorsa
    }

  private fun workSeconds(activity: BackgroundActivity, work: Workload, measures: DeviceMeasures): Double =
    when (activity) {
      BackgroundActivity.AI_COMPUTE -> work.items / measures.aiItemsPerSecond
      BackgroundActivity.PHOTO_BACKUP,
      BackgroundActivity.VIDEO_BACKUP -> work.bytes / measures.smbWriteBytesPerSecond
      BackgroundActivity.INDEX_SYNC,
      BackgroundActivity.PREPARATION,
      BackgroundActivity.HQ_PREVIEWS -> work.bytes / measures.smbReadBytesPerSecond
    }

  private fun energy(
    activity: BackgroundActivity,
    work: Workload,
    measures: DeviceMeasures,
    mobileShare: Double,
  ): Double =
    if (activity == BackgroundActivity.AI_COMPUTE) {
      // Le anteprime scaricate per l'analisi pesano molto meno del calcolo: si trascurano.
      work.items / 1000.0 * measures.aiBatteryPercentPerThousandItems
    } else {
      val factor = 1.0 + mobileShare * (measures.mobileEnergyFactor - 1.0)
      work.bytes / BYTES_PER_GB * measures.transferBatteryPercentPerGigabyte * factor
    }

  private companion object {
    const val SECONDS_PER_HOUR = 3600.0
    const val HOURS_PER_DAY = 24.0
    const val DAYS_PER_MONTH = 30.0
    const val BYTES_PER_GB = 1.0e9
  }
}

/** Il riepilogo di una stima in una frase, per i preset (italiano). */
object EstimateText {

  fun describe(estimate: ConsumptionEstimate): String {
    val battery = percent(estimate.batteryPercentPerNight)
    val drain = percent(estimate.batteryDrainPercentPerNight)
    val batteryPart =
      if (estimate.batteryDrainPercentPerNight < 0.05) {
        "Circa $battery% di energia per notte (tutta dalla presa)"
      } else {
        "Circa $battery% di energia per notte ($drain% dalla batteria)"
      }
    val data = ByteFormat.size(estimate.mobileBytesPerMonth)
    val time =
      when (val days = estimate.daysToFinish) {
        null -> "con queste regole il lavoro arretrato non finisce mai"
        else ->
          when {
            days <= 0.0 -> "nessun lavoro arretrato"
            ceil(days) <= 1.0 -> "arretrato smaltito entro un giorno"
            else -> "arretrato smaltito in circa ${ceil(days).toInt()} giorni"
          }
      }
    return "$batteryPart, $data di dati mobili al mese, $time."
  }

  private fun percent(value: Double): String =
    String.format(Locale.ITALIAN, "%.1f", value).removeSuffix(",0")
}
