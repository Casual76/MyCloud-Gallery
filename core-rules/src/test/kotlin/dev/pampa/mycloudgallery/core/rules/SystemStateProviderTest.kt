package dev.pampa.mycloudgallery.core.rules

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SystemStateProviderTest {

  private val clock = MutableClock(at(2))
  private val device =
    FakeDeviceStateSource(DeviceReading(PowerState(pluggedIn = true, levelPercent = 80), HOME_WIFI_NET.copy(isHome = false)))
  private val home = FakeHomeNetworkSignal(initial = true)
  private val nas = FakeNasReachabilitySignal(initial = true)
  private val roles = FakeDeviceRolesSignal(DeviceRoles(indexer = true))
  private val limits = FakeAndroidLimitsSignal()
  private val app = FakeAppContextSignal()
  private val provider = ComposedSystemStateProvider(device, home, nas, roles, limits, app, clock)

  private fun power(
    plugged: Boolean = true,
    level: Int = 80,
    thermal: ThermalStatus = ThermalStatus.NONE,
    headroom: Float? = null,
  ) = PowerState(pluggedIn = plugged, levelPercent = level, thermalStatus = thermal, thermalHeadroom = headroom)

  @Test
  fun uniscePiuFontiInUnSoloStato() {
    nas.isReachable.value = false
    app.context.value = AppContext(inForeground = true, profileActive = false)
    limits.limits.value = AndroidLimits(dataSyncBudgetExhausted = true)

    val state = provider.current()

    assertThat(state.now).isEqualTo(at(2))
    assertThat(state.power.pluggedIn).isTrue()
    assertThat(state.network.isHome).isTrue() // il segnale "casa" lo mette il provider
    assertThat(state.network.transport).isEqualTo(NetworkTransport.WIFI)
    assertThat(state.nasReachable).isFalse()
    assertThat(state.roles).isEqualTo(DeviceRoles(indexer = true))
    assertThat(state.androidLimits.dataSyncBudgetExhausted).isTrue()
    assertThat(state.appInForeground).isTrue()
    assertThat(state.profileActive).isFalse()
  }

  @Test
  fun lOraEQuellaDellOrologio() {
    clock.set(at(23, 59, 59))
    assertThat(provider.current().now).isEqualTo(at(23, 59, 59))
    clock.set(at(0, 0, 0, day = 14))
    assertThat(provider.current().now).isEqualTo(at(0, 0, 0, day = 14))
  }

  @Test
  fun daQuantoESCollegatoLAlimentazione_siRicordaTraUnaLetturaEL_altra() {
    device.reading.value = device.reading.value.copy(power = power(plugged = true))
    assertThat(provider.current().power.unpluggedFor).isEqualTo(Duration.ZERO)

    device.reading.value = device.reading.value.copy(power = power(plugged = false))
    assertThat(provider.current().power.unpluggedFor).isEqualTo(Duration.ZERO) // appena scollegato
    clock.advanceSeconds(7)
    assertThat(provider.current().power.unpluggedFor).isEqualTo(Duration.ofSeconds(7))
    clock.advanceSeconds(60)
    assertThat(provider.current().power.unpluggedFor).isEqualTo(Duration.ofSeconds(67))

    // Ricollegato: il conto riparte da zero la volta dopo.
    device.reading.value = device.reading.value.copy(power = power(plugged = true))
    assertThat(provider.current().power.unpluggedFor).isEqualTo(Duration.ZERO)
    device.reading.value = device.reading.value.copy(power = power(plugged = false))
    clock.advanceSeconds(3)
    provider.current()
    clock.advanceSeconds(2)
    assertThat(provider.current().power.unpluggedFor).isEqualTo(Duration.ofSeconds(2))
  }

  @Test
  fun unFalsoDistaccoDi5Secondi_nonFermaIlLavoro_unoVeroSi() {
    val evaluator = RuleEvaluator()
    val night = ruleSetOf(Preset.NIGHT)

    // Il telefono e' collegato, poi per 5 secondi risulta scollegato (visto sull'S25), poi torna.
    device.reading.value = device.reading.value.copy(power = power(plugged = true))
    provider.current()
    device.reading.value = device.reading.value.copy(power = power(plugged = false))
    provider.current()
    clock.advanceSeconds(5)
    val glitch = provider.current()
    assertThat(evaluator.background(BackgroundActivity.AI_COMPUTE, night, glitch, running = true).isRun).isTrue()

    // Ancora scollegato dopo un minuto: si ferma.
    clock.advanceSeconds(60)
    val unplugged = provider.current()
    assertThat(evaluator.background(BackgroundActivity.AI_COMPUTE, night, unplugged, running = true))
      .isInstanceOf(RuleDecision.Stop::class.java)
  }

  @Test
  fun isteresiTermica_siRicordaLUltimoStatoSevero_eLaRipartenzaAspetta() {
    val evaluator = RuleEvaluator()
    val night = ruleSetOf(Preset.NIGHT)

    device.reading.value = device.reading.value.copy(power = power(thermal = ThermalStatus.SEVERE))
    assertThat(provider.current().power.timeSinceThermalSevere).isEqualTo(Duration.ZERO)

    // Il telefono si raffredda subito, ma non si riparte subito.
    device.reading.value = device.reading.value.copy(power = power(thermal = ThermalStatus.NONE))
    clock.advanceSeconds(120)
    val cooling = provider.current()
    assertThat(cooling.power.timeSinceThermalSevere).isEqualTo(Duration.ofSeconds(120))
    assertThat(evaluator.background(BackgroundActivity.AI_COMPUTE, night, cooling))
      .isEqualTo(RuleDecision.Wait(WaitReason.Thermal(ThermalStatus.NONE)))

    // Dopo cinque minuti di calma riparte.
    clock.advanceSeconds(200)
    assertThat(evaluator.background(BackgroundActivity.AI_COMPUTE, night, provider.current()).isRun).isTrue()
  }

  @Test
  fun isteresiTermica_ancheConLHeadroomAlto() {
    device.reading.value = device.reading.value.copy(power = power(headroom = 0.97f))
    provider.current()
    device.reading.value = device.reading.value.copy(power = power(headroom = 0.5f))
    clock.advanceSeconds(30)

    assertThat(provider.current().power.timeSinceThermalSevere).isEqualTo(Duration.ofSeconds(30))
  }

  @Test
  fun senzaMaiStatoSevero_nienteIsteresi() {
    assertThat(provider.current().power.timeSinceThermalSevere).isNull()
  }

  @Test
  fun laQualitaDellaRete_vieneDalSegnaleDellaFase2() {
    val quality = FakeNetworkQualitySignal()
    val withQuality = ComposedSystemStateProvider(device, home, nas, roles, limits, app, clock, quality = quality)

    assertThat(withQuality.current().network.poor).isFalse()
    quality.isPoor.value = true
    assertThat(withQuality.current().network.poor).isTrue()
    // Senza il segnale (prima della Fase 2) la rete si considera buona.
    assertThat(provider.current().network.poor).isFalse()
  }

  @Test
  fun osservare_emetteAlloStartEAOgniCambioRilevante() = runTest {
    provider.observe().test {
      val first = awaitItem()
      assertThat(first.network.isHome).isTrue()

      home.isAtHome.value = false
      assertThat(awaitItem().network.isHome).isFalse()

      nas.isReachable.value = false
      assertThat(awaitItem().nasReachable).isFalse()

      device.reading.value = device.reading.value.copy(network = MOBILE_VPN_NET)
      assertThat(awaitItem().network.transport).isEqualTo(NetworkTransport.CELLULAR)

      roles.roles.value = DeviceRoles(indexer = true, preparer = true)
      assertThat(awaitItem().roles.preparer).isTrue()

      limits.limits.value = AndroidLimits(jobQuotaExhausted = true)
      assertThat(awaitItem().androidLimits.jobQuotaExhausted).isTrue()

      app.context.value = AppContext(inForeground = true)
      assertThat(awaitItem().appInForeground).isTrue()

      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun osservare_ilSoloPassareDelTempoNonEmette() = runTest {
    provider.observe().test {
      awaitItem()

      clock.advanceSeconds(3600)
      device.reading.value = device.reading.value // stesso valore: nessun cambio

      expectNoEvents()
      cancelAndIgnoreRemainingEvents()
    }
  }
}
