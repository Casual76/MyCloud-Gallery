package dev.pampa.mycloudgallery.core.rules.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.pampa.mycloudgallery.core.common.files.QuietWrites
import dev.pampa.mycloudgallery.core.rules.ActivityRule
import dev.pampa.mycloudgallery.core.rules.AllowedNetwork
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.MonthlyCaps
import dev.pampa.mycloudgallery.core.rules.NightWindows
import dev.pampa.mycloudgallery.core.rules.PowerPolicy
import dev.pampa.mycloudgallery.core.rules.Preset
import dev.pampa.mycloudgallery.core.rules.PresetCatalog
import dev.pampa.mycloudgallery.core.rules.RuleSet
import dev.pampa.mycloudgallery.core.rules.TimeWindow
import dev.pampa.mycloudgallery.core.rules.traffic.BillingCycle
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Le scelte dell'utente sulle regole (cosa si salva): il preset, la fascia notte, la soglia della
 * batteria, i tetti, e per "Personalizzato" le regole di ogni attivita'. [toRuleSet] le trasforma
 * nelle [RuleSet] che il valutatore usa.
 *
 * @property capsOverride tetti scelti dall'utente; null = quelli del preset.
 * @property disabledActivities attivita' spente (vale con qualunque preset: es. anteprime HQ quando la
 *   qualita' non e' "Alta").
 * @property allowRoaming l'interruttore esplicito del roaming (proposta 2): aggiunge `ROAMING` alle reti
 *   di ogni attivita'. Spento in tutti i preset.
 * @property customRules regole di "Personalizzato"; le attivita' mancanti hanno quelle del preset Notte.
 * @property customHeavyOnlyAtHome "pesanti solo sul Wi-Fi di casa" per "Personalizzato".
 * @property billingCycleStartDay giorno di inizio del mese dei contatori (1..28).
 * @property protectNewPhotos "Proteggi subito le foto nuove" (Fase 5, D34, approvata con Q7 (a)): con **qualunque** preset una foto
 *   appena scattata si carica appena c'e' il Wi-Fi di casa e la batteria e' sopra la soglia, anche fuori dalla fascia notte. Solo foto
 *   della fotocamera, mai video, mai dati mobili ([quickPhotosRuleSet]). Acceso di default.
 */
data class RuleSettings(
  val preset: Preset = Preset.NIGHT,
  val nightWindow: TimeWindow = NightWindows.DEFAULT,
  val batteryThresholdPercent: Int = RuleSet.DEFAULT_BATTERY_THRESHOLD_PERCENT,
  val capsOverride: MonthlyCaps? = null,
  val disabledActivities: Set<BackgroundActivity> = emptySet(),
  val allowRoaming: Boolean = false,
  val pauseOnPowerSaver: Boolean = true,
  val customRules: Map<BackgroundActivity, ActivityRule> = emptyMap(),
  val customHeavyOnlyAtHome: Boolean = true,
  val billingCycleStartDay: Int = 1,
  val protectNewPhotos: Boolean = true,
) {
  init {
    require(batteryThresholdPercent in 0..100) { "La soglia della batteria e' una percentuale." }
    require(billingCycleStartDay in 1..BillingCycle.MAX_START_DAY) {
      "Il giorno di inizio del ciclo va da 1 a ${BillingCycle.MAX_START_DAY}."
    }
  }

  fun toRuleSet(): RuleSet {
    val base = PresetCatalog.ruleSet(preset, nightWindow)
    val withCustom =
      if (preset == Preset.CUSTOM) {
        base.copy(
          rules = base.rules + customRules,
          heavyOnlyAtHome = customHeavyOnlyAtHome,
        )
      } else {
        base
      }
    return withCustom.copy(
      rules =
        withCustom.rules.mapValues { (activity, rule) ->
          rule.copy(
            enabled = rule.enabled && activity !in disabledActivities,
            networks = if (allowRoaming) rule.networks + AllowedNetwork.ROAMING else rule.networks,
          )
        },
      caps = capsOverride ?: withCustom.caps,
      batteryThresholdPercent = batteryThresholdPercent,
      pauseOnPowerSaver = pauseOnPowerSaver,
    )
  }
}

/** Dove vivono le [RuleSettings]. */
interface RuleSettingsStore {

  fun observe(): Flow<RuleSettings>

  suspend fun current(): RuleSettings

  /** Sostituisce tutte le impostazioni. */
  suspend fun save(settings: RuleSettings)

  /** Legge, modifica e salva in modo atomico. */
  suspend fun update(transform: (RuleSettings) -> RuleSettings)
}

/**
 * [RuleSettingsStore] su DataStore Preferences.
 *
 * La lettura e' difensiva: un valore sconosciuto o corrotto (un preset che non esiste piu', una fascia
 * illeggibile) non fa cadere nulla, ripiega sul default di quel campo.
 */
class DataStoreRuleSettingsStore(private val dataStore: DataStore<Preferences>) : RuleSettingsStore {

  override fun observe(): Flow<RuleSettings> = dataStore.data.map(::read)

  override suspend fun current(): RuleSettings = read(dataStore.data.first())

  // OB-4 (review generale): con la memoria piena `DataStore.edit` lancia `IOException`, e chiamata dal `viewModelScope` delle Impostazioni chiudeva l'app. Ora la scrittura che non
  // riesce non salva e non lancia (`QuietWrites`): `observe()` segue il file, quindi la scelta resta quella di prima.
  override suspend fun save(settings: RuleSettings) {
    QuietWrites.attempt("regole delle attivita'") { dataStore.edit { write(it, settings) } }
  }

  override suspend fun update(transform: (RuleSettings) -> RuleSettings) {
    QuietWrites.attempt("regole delle attivita'") { dataStore.edit { prefs -> write(prefs, transform(read(prefs))) } }
  }

  private fun read(prefs: Preferences): RuleSettings {
    val defaults = RuleSettings()
    val night = readWindow(prefs[NIGHT_START], prefs[NIGHT_END]) ?: defaults.nightWindow
    val capsStored = prefs[CAPS_OVERRIDE] == true
    return RuleSettings(
      preset = prefs[PRESET]?.let { name -> Preset.entries.firstOrNull { it.name == name } } ?: defaults.preset,
      nightWindow = night,
      batteryThresholdPercent =
        prefs[BATTERY_THRESHOLD]?.takeIf { it in 0..100 } ?: defaults.batteryThresholdPercent,
      capsOverride =
        if (capsStored) {
          MonthlyCaps(
            mobileBytes = prefs[CAP_MOBILE]?.takeIf { it >= 0 },
            wifiBytes = prefs[CAP_WIFI]?.takeIf { it >= 0 },
          )
        } else {
          null
        },
      disabledActivities = prefs[DISABLED]?.let(::decodeActivities) ?: defaults.disabledActivities,
      allowRoaming = prefs[ALLOW_ROAMING] ?: defaults.allowRoaming,
      pauseOnPowerSaver = prefs[PAUSE_ON_POWER_SAVER] ?: defaults.pauseOnPowerSaver,
      customRules =
        BackgroundActivity.entries
          .mapNotNull { activity ->
            prefs[ruleKey(activity)]?.let(::decodeRule)?.let { activity to it }
          }
          .toMap(),
      customHeavyOnlyAtHome = prefs[CUSTOM_HEAVY_AT_HOME] ?: defaults.customHeavyOnlyAtHome,
      billingCycleStartDay =
        prefs[CYCLE_START_DAY]?.takeIf { it in 1..BillingCycle.MAX_START_DAY }
          ?: defaults.billingCycleStartDay,
      protectNewPhotos = prefs[PROTECT_NEW_PHOTOS] ?: defaults.protectNewPhotos,
    )
  }

  private fun write(prefs: MutablePreferences, s: RuleSettings) {
    prefs[PRESET] = s.preset.name
    prefs[NIGHT_START] = minutes(s.nightWindow.start)
    prefs[NIGHT_END] = minutes(s.nightWindow.end)
    prefs[BATTERY_THRESHOLD] = s.batteryThresholdPercent
    val caps = s.capsOverride
    if (caps == null) {
      prefs[CAPS_OVERRIDE] = false
      prefs.remove(CAP_MOBILE)
      prefs.remove(CAP_WIFI)
    } else {
      prefs[CAPS_OVERRIDE] = true
      prefs[CAP_MOBILE] = caps.mobileBytes ?: NO_CAP
      prefs[CAP_WIFI] = caps.wifiBytes ?: NO_CAP
    }
    prefs[DISABLED] = s.disabledActivities.joinToString(",") { it.name }
    prefs[ALLOW_ROAMING] = s.allowRoaming
    prefs[PAUSE_ON_POWER_SAVER] = s.pauseOnPowerSaver
    prefs[CUSTOM_HEAVY_AT_HOME] = s.customHeavyOnlyAtHome
    prefs[CYCLE_START_DAY] = s.billingCycleStartDay
    prefs[PROTECT_NEW_PHOTOS] = s.protectNewPhotos
    BackgroundActivity.entries.forEach { activity ->
      val rule = s.customRules[activity]
      if (rule == null) prefs.remove(ruleKey(activity)) else prefs[ruleKey(activity)] = encodeRule(rule)
    }
  }

  // --- Codifica ---------------------------------------------------------------------------------------

  private fun minutes(time: LocalTime) = time.hour * 60 + time.minute

  private fun readWindow(start: Int?, end: Int?): TimeWindow? {
    if (start == null || end == null) return null
    if (start !in 0 until MINUTES_PER_DAY || end !in 0 until MINUTES_PER_DAY || start == end) return null
    return TimeWindow(LocalTime.of(start / 60, start % 60), LocalTime.of(end / 60, end % 60))
  }

  private fun decodeActivities(text: String): Set<BackgroundActivity> =
    text.split(',').mapNotNull { name -> BackgroundActivity.entries.firstOrNull { it.name == name } }.toSet()

  /** `enabled=1|power=PLUGGED_ONLY|nets=HOME_WIFI,ANY_WIFI_VPN|win=1380-420` (fascia in minuti, o `-`). */
  private fun encodeRule(rule: ActivityRule): String {
    val window = rule.window?.let { "${minutes(it.start)}-${minutes(it.end)}" } ?: "-"
    return "enabled=${if (rule.enabled) 1 else 0}|power=${rule.power.name}|" +
      "nets=${rule.networks.joinToString(",") { it.name }}|win=$window"
  }

  private fun decodeRule(text: String): ActivityRule? {
    val fields = text.split('|').mapNotNull { part ->
      val index = part.indexOf('=')
      if (index <= 0) null else part.substring(0, index) to part.substring(index + 1)
    }.toMap()
    val power = PowerPolicy.entries.firstOrNull { it.name == fields["power"] } ?: return null
    val networks = fields["nets"]
      ?.split(',')
      ?.mapNotNull { name -> AllowedNetwork.entries.firstOrNull { it.name == name } }
      ?.toSet() ?: return null
    val windowText = fields["win"] ?: return null
    val window =
      if (windowText == "-") {
        null
      } else {
        val parts = windowText.split('-')
        if (parts.size != 2) return null
        readWindow(parts[0].toIntOrNull(), parts[1].toIntOrNull()) ?: return null
      }
    return ActivityRule(window, power, networks, enabled = fields["enabled"] != "0")
  }

  private fun ruleKey(activity: BackgroundActivity) = stringPreferencesKey("rule.custom.${activity.name}")

  private companion object {
    const val MINUTES_PER_DAY = 24 * 60
    const val NO_CAP = -1L

    val PRESET = stringPreferencesKey("rule.preset")
    val NIGHT_START = intPreferencesKey("rule.night.start")
    val NIGHT_END = intPreferencesKey("rule.night.end")
    val BATTERY_THRESHOLD = intPreferencesKey("rule.battery.threshold")
    val CAPS_OVERRIDE = booleanPreferencesKey("rule.caps.override")
    val CAP_MOBILE = longPreferencesKey("rule.caps.mobile")
    val CAP_WIFI = longPreferencesKey("rule.caps.wifi")
    val DISABLED = stringPreferencesKey("rule.disabled")
    val ALLOW_ROAMING = booleanPreferencesKey("rule.roaming.allow")
    val PAUSE_ON_POWER_SAVER = booleanPreferencesKey("rule.powersaver.pause")
    val CUSTOM_HEAVY_AT_HOME = booleanPreferencesKey("rule.custom.heavy_home_only")
    val CYCLE_START_DAY = intPreferencesKey("rule.cycle.startday")
    val PROTECT_NEW_PHOTOS = booleanPreferencesKey("rule.backup.protect_new_photos")
  }
}
