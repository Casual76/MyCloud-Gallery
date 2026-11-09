package dev.pampa.mycloudgallery.work

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.rules.CapKind
import dev.pampa.mycloudgallery.core.rules.DeviceRole
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.ThermalStatus
import dev.pampa.mycloudgallery.core.rules.TimeWindow
import dev.pampa.mycloudgallery.core.rules.WaitReason
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import org.junit.Test

/**
 * FAB-3 e FAB-5 della review: la regola **unica** di che cosa fare dopo un'attesa delle regole ([RuleWaits.followUpOf]), per il backup, il calcolo
 * AI e i volti. Mai una coda senza ritardo; un vincolo di WorkManager solo dove misura la causa (la carica); per il risparmio energetico e il caldo
 * un ritardo che cresce fino a un tetto; per tutto il resto niente (nessun polling). Pura: niente WorkManager, niente Android.
 */
class RuleWaitsTest {

  private val now = LocalDateTime.of(2026, 10, 16, 12, 0)
  private val window = TimeWindow(LocalTime.of(23, 0), LocalTime.of(7, 0))

  private val everyWait: List<WaitReason> = listOf(
    WaitReason.ProfileInactive, WaitReason.RoleDisabled(DeviceRole.INDEXER), WaitReason.ActivityDisabled, WaitReason.Frozen(FreezeReason.VANISHED),
    WaitReason.AppNotVisible, WaitReason.Roaming, WaitReason.Offline, WaitReason.NetworkNotTrusted, WaitReason.PoorNetwork,
    WaitReason.OutsideWindow(window, now.plusHours(11)), WaitReason.NeedsPower, WaitReason.BatteryLow(17, 20), WaitReason.PowerSaver,
    WaitReason.Thermal(ThermalStatus.SEVERE), WaitReason.NetworkNotAllowed(NetworkClass.MOBILE), WaitReason.HeavyNeedsHomeWifi,
    WaitReason.CapReached(CapKind.MOBILE, 2, 1), WaitReason.AndroidLimit(null), WaitReason.NasUnreachable, WaitReason.ConfirmationRequired(emptySet()),
  )

  @Test
  fun nessunaAttesaSiRimetteInCodaSenzaRitardo() {
    for (wait in everyWait) {
      for (attempt in listOf(0, 1, 5, 1_000)) {
        for (cap in listOf(RuleWaits.MAX_RETRY, RuleWaits.PROMPT_CAP)) {
          val follow = RuleWaits.followUpOf(wait, now, attempt, cap) ?: continue
          assertWithMessage("$wait, tentativo $attempt").that(follow.delay).isAtLeast(RuleWaits.MIN_RETRY)
        }
      }
    }
  }

  @Test
  fun laFasciaELaCaricaHannoIlLoroVincoloEsatto() {
    // 11 ore alla fascia, piu' il margine: l'ora del telefono e quella di WorkManager non sono mai identiche.
    val night = RuleWaits.followUpOf(WaitReason.OutsideWindow(window, now.plusHours(11)), now)!!
    assertThat(night.delay).isEqualTo(11.hours + RuleWaits.MIN_RETRY)
    assertThat(night.charging).isFalse()
    // Una fascia gia' iniziata (orologi che non coincidono): non si va indietro nel tempo.
    assertThat(RuleWaits.followUpOf(WaitReason.OutsideWindow(window, now.minusHours(1)), now)!!.delay).isEqualTo(RuleWaits.MIN_RETRY)

    val power = RuleWaits.followUpOf(WaitReason.NeedsPower, now)!!
    assertThat(power.charging).isTrue()
    assertThat(power.delay).isEqualTo(RuleWaits.MIN_RETRY)
    // I vincoli esatti non contano i tentativi: il lavoro dopo riparte da zero.
    assertThat(night.attempt).isEqualTo(0)
    assertThat(power.attempt).isEqualTo(0)
    assertThat(RuleWaits.followUpOf(WaitReason.NeedsPower, now, attempt = 7)).isEqualTo(power)
  }

  @Test
  fun fab3_laBatteriaSottoSogliaAspettaLaCarica_nonIlVincoloBatteriaNonScaricaDiAndroid() {
    // La soglia delle regole e' il 20%, quella di Android il 15%: fra il 16% e il 19% "batteria non scarica" e' gia' vero e il lavoro ripartiva
    // subito, a ciclo continuo. La regola vale solo a batteria, e la batteria risale solo in carica: il vincolo esatto e' la carica.
    for (level in listOf(3, 15, 16, 17, 19)) {
      val follow = RuleWaits.followUpOf(WaitReason.BatteryLow(level, 20), now)!!
      assertWithMessage("$level%").that(follow.charging).isTrue()
      assertWithMessage("$level%").that(follow.delay).isEqualTo(RuleWaits.MIN_RETRY)
    }
  }

  private fun chain(wait: WaitReason, cap: Duration, rounds: Int): List<Duration> {
    val delays = ArrayList<Duration>()
    var attempt = 0
    repeat(rounds) {
      val follow = RuleWaits.followUpOf(wait, now, attempt, cap)!!
      delays += follow.delay
      attempt = follow.attempt
    }
    return delays
  }

  @Test
  fun fab3_fab5_ilRisparmioEnergeticoNonHaUnVincolo_HaUnRitardoCheCresceFinoAUnOra() {
    val first = RuleWaits.followUpOf(WaitReason.PowerSaver, now)!!
    // Nessun vincolo di WorkManager misura il risparmio energetico: ne' la carica ne' "batteria non scarica" (i vincoli veri: LanConstraintsTest).
    assertThat(first.charging).isFalse()
    assertThat(first.attempt).isEqualTo(1)

    assertThat(chain(WaitReason.PowerSaver, RuleWaits.MAX_RETRY, 6)).containsExactly(15.minutes, 30.minutes, 1.hours, 1.hours, 1.hours, 1.hours).inOrder()
    // In dodici ore di risparmio energetico acceso: una dozzina di risvegli, non settecento (uno al minuto) ne' un ciclo continuo.
    val wakeups = chain(WaitReason.PowerSaver, RuleWaits.MAX_RETRY, 200).runningFold(Duration.ZERO) { sum, d -> sum + d }.count { it > Duration.ZERO && it <= 12.hours }
    assertThat(wakeups).isAtMost(13)
  }

  @Test
  fun fab3_cioCheLUtenteAspettaPrestoRiprovaAlPiuOgniQuartoDOra() {
    // "Proteggi subito" e "Salva sul NAS": spento il risparmio energetico ripartono entro un quarto d'ora, non dopo ore.
    assertThat(chain(WaitReason.PowerSaver, RuleWaits.PROMPT_CAP, 5).toSet()).containsExactly(15.minutes)
    assertThat(chain(WaitReason.Thermal(ThermalStatus.SEVERE), RuleWaits.PROMPT_CAP, 5)).containsExactly(5.minutes, 10.minutes, 15.minutes, 15.minutes, 15.minutes).inOrder()
  }

  @Test
  fun colCaldoSiRiprovaDopoCinqueMinutiEPoiSemprePiuDiRado() {
    assertThat(chain(WaitReason.Thermal(ThermalStatus.SEVERE), RuleWaits.MAX_RETRY, 6))
      .containsExactly(5.minutes, 10.minutes, 20.minutes, 40.minutes, 1.hours, 1.hours).inOrder()
  }

  @Test
  fun ilContatoreDeiTentativiNonCresceAllInfinito() {
    var attempt = 0
    repeat(10_000) { attempt = RuleWaits.followUpOf(WaitReason.PowerSaver, now, attempt)!!.attempt }
    assertThat(attempt).isAtMost(32)
    // Un valore assurdo negli input del lavoro (un database di WorkManager di un'altra versione) non rompe niente.
    assertThat(RuleWaits.followUpOf(WaitReason.PowerSaver, now, attempt = -5)!!.delay).isEqualTo(15.minutes)
    assertThat(RuleWaits.followUpOf(WaitReason.PowerSaver, now, attempt = Int.MAX_VALUE)!!.delay).isEqualTo(1.hours)
  }

  @Test
  fun laReteIlNasIlProfiloEIlRestoNonProgrammanoNiente_nientePolling() {
    val none = listOf(
      WaitReason.Offline, WaitReason.NasUnreachable, WaitReason.Roaming, WaitReason.ProfileInactive, WaitReason.HeavyNeedsHomeWifi,
      WaitReason.NetworkNotAllowed(NetworkClass.MOBILE), WaitReason.NetworkNotTrusted, WaitReason.PoorNetwork, WaitReason.ActivityDisabled,
      WaitReason.Frozen(FreezeReason.CHANGED), WaitReason.AndroidLimit(null), WaitReason.CapReached(CapKind.WIFI, 2, 1), WaitReason.RoleDisabled(DeviceRole.PREPARER),
    )
    for (wait in none) assertWithMessage("$wait").that(RuleWaits.followUpOf(wait, now)).isNull()
  }
}
