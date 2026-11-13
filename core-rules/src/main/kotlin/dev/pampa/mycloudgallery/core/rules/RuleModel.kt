package dev.pampa.mycloudgallery.core.rules

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/**
 * Una fascia oraria giornaliera, anche a cavallo della mezzanotte (23:00-07:00).
 *
 * L'inizio e' incluso, la fine e' esclusa: con 23:00-07:00 le 23:00:00 sono dentro e le 07:00:00 sono
 * fuori. Inizio e fine uguali non sono ammessi (sarebbe ambiguo tra "mai" e "sempre"): "a qualunque
 * ora" si esprime con `window = null` in [ActivityRule].
 */
data class TimeWindow(val start: LocalTime, val end: LocalTime) {

  init {
    require(start != end) { "Una fascia oraria non puo' avere inizio e fine uguali." }
  }

  /** Vero se la fascia passa per la mezzanotte (es. 23:00-07:00). */
  val crossesMidnight: Boolean get() = end < start

  fun contains(time: LocalTime): Boolean =
    if (crossesMidnight) time >= start || time < end else time >= start && time < end

  /** Durata della fascia in un giorno. */
  val length: Duration
    get() =
      if (crossesMidnight) Duration.ofHours(24) - Duration.between(end, start)
      else Duration.between(start, end)

  /** La prossima volta, dopo [now], in cui la fascia si apre. */
  fun nextStart(now: LocalDateTime): LocalDateTime {
    val today = now.toLocalDate().atTime(start)
    return if (today.isAfter(now)) today else today.plusDays(1)
  }

  /** "23:00-07:00" (con il trattino lungo, per l'utente). */
  override fun toString(): String = "${start.hhmm()}–${end.hhmm()}"
}

internal fun LocalTime.hhmm(): String = String.format(Locale.ROOT, "%02d:%02d", hour, minute)

/**
 * Le reti su cui un'attivita' puo' girare (piano §2 e §3).
 *
 * `ROAMING` non e' in nessun preset: si abilita solo con un interruttore esplicito (proposta 2).
 */
enum class AllowedNetwork {
  /** Il Wi-Fi di casa (riconosciuto con piu' indizi, senza posizione). */
  HOME_WIFI,

  /** Qualunque altro Wi-Fi, raggiunto attraverso la VPN. */
  ANY_WIFI_VPN,

  /** Dati mobili (anche un Wi-Fi a consumo conta come dati mobili, E-R13). */
  MOBILE_DATA,

  /** Dati mobili in roaming. */
  ROAMING,
}

/** Con quale alimentazione un'attivita' puo' girare. */
enum class PowerPolicy {
  /** Solo con l'alimentazione collegata (`EXTRA_PLUGGED`), anche se la carica e' sospesa. */
  PLUGGED_ONLY,

  /** Anche a batteria, purche' sopra la soglia dell'utente. */
  BATTERY_ALLOWED,
}

/**
 * Le regole di UNA attivita'.
 *
 * @property window fascia in cui puo' girare; null = a qualunque ora.
 * @property networks reti ammesse (vuoto = mai).
 * @property enabled falso = attivita' spenta dall'utente (o non applicabile, es. anteprime HQ con
 *   qualita' non "Alta").
 */
data class ActivityRule(
  val window: TimeWindow?,
  val power: PowerPolicy,
  val networks: Set<AllowedNetwork>,
  val enabled: Boolean = true,
)

/** Tetti mensili di traffico, separati per dati mobili e Wi-Fi. `null` = nessun tetto. */
data class MonthlyCaps(val mobileBytes: Long?, val wifiBytes: Long?) {
  init {
    require(mobileBytes == null || mobileBytes >= 0) { "Il tetto non puo' essere negativo." }
    require(wifiBytes == null || wifiBytes >= 0) { "Il tetto non puo' essere negativo." }
  }

  companion object {
    val NONE = MonthlyCaps(mobileBytes = null, wifiBytes = null)
  }
}

/**
 * Tutte le regole attive: una [ActivityRule] per ognuna delle sei attivita' piu' le impostazioni
 * comuni.
 *
 * @property batteryThresholdPercent sotto questa soglia, a batteria, le attivita' in background di [batteryThresholdActivities]
 *   si fermano (piano §2: 20%, modificabile). Con l'alimentazione collegata non si applica.
 * @property batteryThresholdActivities le attivita' a cui la soglia si applica. Di serie **tutte** (come prima che la soglia si potesse
 *   scegliere per gruppo): l'utente puo' escludere il backup o l'analisi e le attivita' che servono a loro seguono ([BatteryGroups]).
 *   "Fai ora", il contenuto visibile e l'indice con l'app aperta non guardano mai la soglia.
 * @property heavyOnlyAtHome le attivita' [ActivityWeight.HEAVY] girano solo sul Wi-Fi di casa
 *   (proposta 1). Il preset Massimo la spegne.
 * @property pauseOnPowerSaver con il risparmio energetico attivo il background sta fermo (E-P10).
 *   Si puo' spegnere se si scopre che il risparmio Samsung resta acceso anche in carica (T-22).
 */
data class RuleSet(
  val rules: Map<BackgroundActivity, ActivityRule>,
  val caps: MonthlyCaps,
  val batteryThresholdPercent: Int = DEFAULT_BATTERY_THRESHOLD_PERCENT,
  val heavyOnlyAtHome: Boolean = true,
  val pauseOnPowerSaver: Boolean = true,
  val batteryThresholdActivities: Set<BackgroundActivity> = BackgroundActivity.entries.toSet(),
) {
  init {
    require(BackgroundActivity.entries.all { it in rules }) { "Manca la regola di una attivita'." }
    require(batteryThresholdPercent in 0..100) { "La soglia della batteria e' una percentuale." }
  }

  fun ruleFor(activity: BackgroundActivity): ActivityRule = rules.getValue(activity)

  /** Vero se, a batteria e sotto [batteryThresholdPercent], [activity] deve aspettare. */
  fun batteryThresholdApplies(activity: BackgroundActivity): Boolean = activity in batteryThresholdActivities

  companion object {
    const val DEFAULT_BATTERY_THRESHOLD_PERCENT = 20
  }
}

/**
 * I due gruppi a cui l'utente puo' togliere la soglia della batteria (Pacchetto B, seconda beta): il **backup** e l'**analisi delle foto**.
 *
 * | Attivita' | La soglia la ferma se... | Perche' |
 * |---|---|---|
 * | [BACKUP] (`PHOTO_BACKUP`, `VIDEO_BACKUP`) | l'interruttore del backup e' acceso | il caricamento sul NAS e la sua verifica |
 * | [ANALYSIS] (`AI_COMPUTE`) | l'interruttore dell'analisi e' acceso | vettori, lettura del testo e volti girano tutti dentro il Calcolo AI |
 * | [SHARED] (`INDEX_SYNC`, `PREPARATION`) | **tutti e due** sono accesi | servono sia al backup sia all'analisi (indice condiviso, scansione, miniature e anteprime): se anche uno solo e' spento il gruppo escluso ha cio' che gli serve |
 * | [OPTIONAL] (`HQ_PREVIEWS`) | **almeno uno** e' acceso | non serve a nessuno dei due: segue il livello finche' la soglia vale per qualcosa |
 *
 * Tutti e due accesi = tutte e sei (come prima). Tutti e due spenti = la soglia non ferma niente.
 */
object BatteryGroups {
  val BACKUP: Set<BackgroundActivity> = setOf(BackgroundActivity.PHOTO_BACKUP, BackgroundActivity.VIDEO_BACKUP)
  val ANALYSIS: Set<BackgroundActivity> = setOf(BackgroundActivity.AI_COMPUTE)

  /** Indice condiviso e preparazione: servono a tutti e due i gruppi (il primo backup aspetta la scansione e le anteprime, l'analisi le anteprime e l'indice). */
  val SHARED: Set<BackgroundActivity> = setOf(BackgroundActivity.INDEX_SYNC, BackgroundActivity.PREPARATION)

  /** Le anteprime ad alta qualita' per l'offline: non servono ne' al backup ne' all'analisi. */
  val OPTIONAL: Set<BackgroundActivity> = setOf(BackgroundActivity.HQ_PREVIEWS)

  /** Le attivita' a cui la soglia si applica, dati i due interruttori (tabella sopra). Tutti e due accesi = tutte (come prima); tutti e due spenti = nessuna. */
  fun activitiesFor(forBackup: Boolean, forAnalysis: Boolean): Set<BackgroundActivity> = buildSet {
    if (forBackup) addAll(BACKUP)
    if (forAnalysis) addAll(ANALYSIS)
    if (forBackup && forAnalysis) addAll(SHARED)
    if (forBackup || forAnalysis) addAll(OPTIONAL)
  }
}
