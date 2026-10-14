package dev.pampa.mycloudgallery.core.rules

import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.rules.traffic.BillingCycle
import dev.pampa.mycloudgallery.core.rules.traffic.CycleUsage
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficKey
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficNetwork
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficSource
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

const val GB = 1_000_000_000L
const val MB = 1_000_000L

/** Martedi' 13/10/2026 all'ora indicata. */
fun at(hour: Int, minute: Int = 0, second: Int = 0, day: Int = 13): LocalDateTime =
  LocalDateTime.of(2026, 10, day, hour, minute, second)

// --- Reti tipiche -------------------------------------------------------------------------------------

val HOME_WIFI_NET = NetworkState(NetworkTransport.WIFI, isHome = true)
val EXTERNAL_WIFI_VPN_NET = NetworkState(NetworkTransport.WIFI, viaVpn = true)
val METERED_WIFI_VPN_NET = NetworkState(NetworkTransport.WIFI, viaVpn = true, metered = true)
val MOBILE_VPN_NET = NetworkState(NetworkTransport.CELLULAR, viaVpn = true, metered = true)
val ROAMING_VPN_NET = NetworkState(NetworkTransport.CELLULAR, viaVpn = true, metered = true, roaming = true)

/** Uno stato di default "tutto a posto": le 02:00, in carica all'80%, Wi-Fi di casa, NAS acceso. */
fun state(
  now: LocalDateTime = at(2),
  plugged: Boolean = true,
  level: Int = 80,
  chargingPaused: Boolean = false,
  powerSave: Boolean = false,
  thermal: ThermalStatus = ThermalStatus.NONE,
  headroom: Float? = null,
  // Di default, se non e' in carica, lo e' da un'ora: un distacco "appena avvenuto" si prova a parte.
  unpluggedFor: java.time.Duration? = null,
  timeSinceSevere: java.time.Duration? = null,
  network: NetworkState = HOME_WIFI_NET,
  nas: Boolean = true,
  roles: DeviceRoles = DeviceRoles(indexer = true, preparer = true),
  limits: AndroidLimits = AndroidLimits(),
  foreground: Boolean = false,
  profileActive: Boolean = true,
  frozen: FreezeReason? = null,
): SystemState =
  SystemState(
    now = now,
    power =
      PowerState(
        pluggedIn = plugged,
        levelPercent = level,
        chargingPaused = chargingPaused,
        powerSaveMode = powerSave,
        thermalStatus = thermal,
        thermalHeadroom = headroom,
        unpluggedFor =
          unpluggedFor ?: if (plugged) java.time.Duration.ZERO else java.time.Duration.ofHours(1),
        timeSinceThermalSevere = timeSinceSevere,
      ),
    network = network,
    nasReachable = nas,
    roles = roles,
    androidLimits = limits,
    appInForeground = foreground,
    profileActive = profileActive,
    nasFrozen = frozen,
  )

/** I consumi del mese di ottobre 2026: [mobile] e [wifi] byte, tutti attribuiti al backup foto. */
fun usage(mobile: Long = 0, wifi: Long = 0): CycleUsage {
  val bytes = HashMap<TrafficKey, Long>()
  if (mobile > 0) bytes[TrafficKey(TrafficSource.PHOTO_BACKUP, TrafficNetwork.MOBILE)] = mobile
  if (wifi > 0) bytes[TrafficKey(TrafficSource.PHOTO_BACKUP, TrafficNetwork.HOME_WIFI)] = wifi
  return CycleUsage(BillingCycle(YearMonth.of(2026, 10)), bytes)
}

fun ruleSetOf(preset: Preset, night: TimeWindow = NightWindows.DEFAULT): RuleSet =
  PresetCatalog.ruleSet(preset, night)

/** Valuta un lavoro in background ([writesToNas]: scrive sul NAS, quindi lo ferma il congelamento, D10). */
fun RuleEvaluator.background(
  activity: BackgroundActivity,
  rules: RuleSet,
  state: SystemState,
  usage: CycleUsage = usage(),
  running: Boolean = false,
  writesToNas: Boolean = false,
): RuleDecision = evaluate(WorkRequest.Background(activity, writesToNas), rules, state, usage, running)

/** Un orologio che si sposta a mano, per i test dei contatori e del cambio mese. */
class MutableClock(
  private var instant: Instant,
  private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {

  constructor(dateTime: LocalDateTime) : this(dateTime.toInstant(ZoneOffset.UTC))

  fun set(dateTime: LocalDateTime) {
    instant = dateTime.toInstant(ZoneOffset.UTC)
  }

  fun advanceSeconds(seconds: Long) {
    instant = instant.plusSeconds(seconds)
  }

  override fun getZone(): ZoneId = zone

  override fun withZone(zone: ZoneId): Clock = MutableClock(instant, zone)

  override fun instant(): Instant = instant
}
