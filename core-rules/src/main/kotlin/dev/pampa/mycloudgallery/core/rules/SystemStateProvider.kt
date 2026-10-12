package dev.pampa.mycloudgallery.core.rules

import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

/** Chi fornisce lo [SystemState] al motore. */
interface SystemStateProvider {

  /** Lo stato di questo momento. */
  fun current(): SystemState

  /**
   * Lo stato, riemesso a ogni cambio rilevante (alimentazione, rete, temperatura, NAS, ruoli, app in
   * primo piano...). Il passare del tempo da solo NON emette: le soglie orarie (fascia, cambio mese,
   * isteresi) si rivalutano con un timer, vedi `ActivityRulesEngine.observeAll`.
   */
  fun observe(): Flow<SystemState>
}

/**
 * Le letture del dispositivo: alimentazione, batteria, temperatura e rete. L'unica implementazione vera
 * e' `AndroidDeviceStateSource` (package `platform`); nei test c'e' [FakeDeviceStateSource].
 */
interface DeviceStateSource {
  fun read(): DeviceReading

  /** Un impulso a ogni possibile cambio (le letture vere si rifanno con [read]). */
  fun changes(): Flow<Unit>
}

/** Cosa legge il dispositivo. [NetworkState.isHome] lo mette il provider, non Android. */
data class DeviceReading(val power: PowerState, val network: NetworkState)

class FakeDeviceStateSource(initial: DeviceReading) : DeviceStateSource {
  val reading: MutableStateFlow<DeviceReading> = MutableStateFlow(initial)

  override fun read(): DeviceReading = reading.value

  override fun changes(): Flow<Unit> = reading.map { }
}

/**
 * Ricorda da quanto l'alimentazione e' scollegata e da quanto il telefono e' stato in stato termico
 * severo, per l'isteresi e per la tolleranza ai falsi distacchi. Tutto il resto del motore e' senza
 * stato.
 */
internal class PowerHistoryTracker(private val config: EvaluatorConfig) {
  private var unpluggedSinceMillis: Long? = null
  private var lastSevereMillis: Long? = null

  @Synchronized
  fun enrich(power: PowerState, nowMillis: Long): PowerState {
    if (power.pluggedIn) {
      unpluggedSinceMillis = null
    } else if (unpluggedSinceMillis == null) {
      unpluggedSinceMillis = nowMillis
    }
    if (config.isSevere(power)) lastSevereMillis = nowMillis
    return power.copy(
      unpluggedFor = unpluggedSinceMillis?.let { Duration.ofMillis(nowMillis - it) } ?: Duration.ZERO,
      timeSinceThermalSevere = lastSevereMillis?.let { Duration.ofMillis(nowMillis - it) },
    )
  }
}

/**
 * Lo [SystemStateProvider] vero: unisce le letture del dispositivo ([DeviceStateSource]) ai segnali
 * delle altre fasi (casa, NAS, ruoli, limiti, app in primo piano).
 */
class ComposedSystemStateProvider(
  private val device: DeviceStateSource,
  private val home: HomeNetworkSignal,
  private val nas: NasReachabilitySignal,
  private val roles: DeviceRolesSignal,
  private val limits: AndroidLimitsSignal,
  private val app: AppContextSignal,
  private val clock: Clock = SystemClockFollowingZone,
  config: EvaluatorConfig = EvaluatorConfig(),
  private val quality: NetworkQualitySignal = NoNetworkQualityInfo,
) : SystemStateProvider {

  private val tracker = PowerHistoryTracker(config)

  override fun current(): SystemState {
    val reading = device.read()
    val appContext = app.context.value
    return SystemState(
      now = LocalDateTime.now(clock),
      power = tracker.enrich(reading.power, clock.millis()),
      network =
        reading.network.copy(isHome = home.isAtHome.value, poor = reading.network.poor || quality.isPoor.value),
      nasReachable = nas.isReachable.value,
      roles = roles.roles.value,
      androidLimits = limits.limits.value,
      appInForeground = appContext.inForeground,
      profileActive = appContext.profileActive,
    )
  }

  override fun observe(): Flow<SystemState> =
    merge(
        device.changes(),
        home.isAtHome.map { },
        nas.isReachable.map { },
        roles.roles.map { },
        limits.limits.map { },
        app.context.map { },
        quality.isPoor.map { },
      )
      .onStart { emit(Unit) }
      .conflate()
      .map { current() }
      .distinctUntilChanged { old, new -> old.withoutClockFields() == new.withoutClockFields() }
}

/** Lo stato senza i campi che cambiano solo con il passare del tempo. */
internal fun SystemState.withoutClockFields(): SystemState =
  copy(
    now = LocalDateTime.MIN,
    power = power.copy(unpluggedFor = Duration.ZERO, timeSinceThermalSevere = null),
  )
