package dev.pampa.mycloudgallery.work

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.ConfirmationKind
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.ThermalStatus
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.run.NextStage
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import dev.pampa.mycloudgallery.core.rules.run.StageStats
import dev.pampa.mycloudgallery.core.rules.run.YieldReason
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import org.junit.Test

/**
 * XS-3 (Pacchetto XF): **"Analizza adesso" non consuma senza consenso e non diventa un giro su tutto il catalogo.** La richiesta alle regole e'
 * dell'utente ma **non confermata**: dati mobili, roaming e tetto superato chiedono conferma, e nessun foglio si apre; la tappa riprogramma da sola
 * sul Wi-Fi. "Indicizza ora" resta confermata (e' un tasto con il suo nome). Mutazione: `confirmed = true` nel ramo "solo i chiesti" fa cadere la
 * prima prova (un tocco su una foto tornerebbe a poter consumare dati mobili e superare il tetto).
 */
class AskedStageTest {

  private val stats = StageStats(batches = 0, bytes = 0, roundBytes = 0, elapsed = Duration.ZERO, resumed = false)

  @Test
  fun unToccoSuUnaFotoEUnaRichiestaDellUtenteNonConfermata() {
    val asked = AiStages.requestOf(user = true, only = true)
    assertThat(asked).isInstanceOf(WorkRequest.UserInitiated::class.java)
    asked as WorkRequest.UserInitiated
    assertThat(asked.confirmed).isFalse()
    assertThat(asked.activity).isEqualTo(BackgroundActivity.AI_COMPUTE)
    assertThat(asked.writesToNas).isTrue()
  }

  @Test
  fun indicizzaOraResteConfermataEIlBackgroundNonELUtente() {
    val now = AiStages.requestOf(user = true, only = false) as WorkRequest.UserInitiated
    assertThat(now.confirmed).isTrue()
    assertThat(AiStages.requestOf(user = false, only = false)).isInstanceOf(WorkRequest.Background::class.java)
    // "Solo i chiesti" vince su `user`: anche con il flag dell'utente la richiesta resta non confermata.
    assertThat((AiStages.requestOf(user = false, only = true) as WorkRequest.UserInitiated).confirmed).isFalse()
  }

  /** Una conferma richiesta non apre nessun foglio: la tappa riparte da sola sul Wi-Fi (la capsula dice "Parte sul Wi-Fi"). Mutazione: nessun seguito. */
  @Test
  fun unaConfermaRichiestaRiparteSulWifiSenzaFoglio() {
    val mobile = StageOutcome.NeedsConfirmation(RuleDecision.NeedsConfirmation(setOf(ConfirmationKind.MOBILE_DATA), estimate = null), stats)
    assertThat(askedFollowUp(mobile, askedSinceStart = false)).isEqualTo(AskedFollowUp.OnUnmetered)
  }

  @Test
  fun unaTappaFinitaPerTempoSiRiaccodaEUnaAttesaDelleRegoleRiprogramma() {
    val yielded = StageOutcome.Yielded(YieldReason.TIME_SLICE, NextStage.IMMEDIATELY, stats)
    assertThat(askedFollowUp(yielded, askedSinceStart = false)).isEqualTo(AskedFollowUp.Again)
    val hot = WaitReason.Thermal(ThermalStatus.SEVERE)
    assertThat(askedFollowUp(StageOutcome.Waiting(hot, emptyList(), stats), askedSinceStart = false)).isEqualTo(AskedFollowUp.AfterWait(hot))
    assertThat(askedFollowUp(StageOutcome.Stopped(hot, emptyList(), stats), askedSinceStart = false)).isEqualTo(AskedFollowUp.AfterWait(hot))
  }

  /** Una tappa finita non lascia niente, tranne una richiesta arrivata dopo l'ultima lettura: un giro in piu', per i soli chiesti. */
  @Test
  fun unaTappaFinitaLasciaSoloUnaRichiestaArrivataDopo() {
    val finished = StageOutcome.Finished(stats)
    assertThat(askedFollowUp(finished, askedSinceStart = false)).isEqualTo(AskedFollowUp.Done)
    assertThat(askedFollowUp(finished, askedSinceStart = true)).isEqualTo(AskedFollowUp.Again)
    assertThat(askedFollowUp(null, askedSinceStart = true)).isEqualTo(AskedFollowUp.Done)
  }

  // ---- XS2-3: il tetto raggiunto e la fine della catena -----------------------------------------------------------------------------

  private fun confirmation(vararg kinds: ConfirmationKind) =
    StageOutcome.NeedsConfirmation(RuleDecision.NeedsConfirmation(kinds.toSet(), estimate = null), stats)

  /**
   * A tetto raggiunto su una rete gia' buona il Wi-Fi non cambia niente: **niente seguito senza ritardo**, un ritardo che cresce da 30 minuti a un'ora (il reset del
   * ciclo o un'impostazione cambiata si vedono in un'ora al piu'). Mutazione: trattare il tetto come il cambio di rete (`OnUnmetered`).
   */
  @Test
  fun alTettoRaggiuntoSuUnaReteBuonaIlSeguitoHaUnRitardoCheCresce() {
    val first = askedFollowUp(confirmation(ConfirmationKind.OVER_CAP), askedSinceStart = false) as AskedFollowUp.AfterCap
    assertThat(first.delay).isEqualTo(RuleWaits.OVER_CAP_RETRY)
    val second = askedFollowUp(confirmation(ConfirmationKind.OVER_CAP), askedSinceStart = false, attempt = first.attempt) as AskedFollowUp.AfterCap
    assertThat(second.delay).isGreaterThan(first.delay)
    // Oltre, il ritardo resta al tetto (un'ora) e non scende mai sotto il minimo.
    var attempt = second.attempt
    repeat(20) {
      val next = askedFollowUp(confirmation(ConfirmationKind.OVER_CAP), askedSinceStart = false, attempt = attempt) as AskedFollowUp.AfterCap
      assertThat(next.delay).isAtMost(RuleWaits.MAX_RETRY)
      assertThat(next.delay).isAtLeast(RuleWaits.OVER_CAP_RETRY)
      attempt = next.attempt
    }
  }

  /** Il numero di successori e' **limitato**: fra una richiesta e la sua scadenza a 24 ore la catena fa al piu' una trentina di riprese. */
  @Test
  fun laCatenaATettoRaggiuntoHaUnNumeroLimitatoDiSuccessori() {
    var elapsed = Duration.ZERO
    var attempt = 0
    var successors = 0
    while (elapsed < 24.hours) {
      val next = askedFollowUp(confirmation(ConfirmationKind.OVER_CAP), askedSinceStart = false, attempt = attempt) as AskedFollowUp.AfterCap
      elapsed += next.delay
      attempt = next.attempt
      successors++
    }
    assertThat(successors).isAtMost(26)
  }

  /** Con i dati mobili (anche col tetto raggiunto) o il roaming si aspetta la rete non a consumo: li' il tetto non c'entra e si puo' partire. */
  @Test
  fun conIDatiMobiliOIlRoamingSiAspettaLaReteNonAConsumo() {
    assertThat(askedFollowUp(confirmation(ConfirmationKind.MOBILE_DATA, ConfirmationKind.OVER_CAP), false)).isEqualTo(AskedFollowUp.OnUnmetered)
    assertThat(askedFollowUp(confirmation(ConfirmationKind.ROAMING, ConfirmationKind.MOBILE_DATA), false)).isEqualTo(AskedFollowUp.OnUnmetered)
  }

  /** Il lavoro di "Analizza adesso" senza richieste valide finisce subito (anche prima del cancello iniziale); gli altri lavori non finiscono mai qui. */
  @Test
  fun unaCatenaChiestaSenzaRichiesteValideFinisceSubito() {
    assertThat(askedChainEnded(only = true, hasRequests = false)).isTrue()
    assertThat(askedChainEnded(only = true, hasRequests = true)).isFalse()
    // Il giro di fondo e "Indicizza ora" non dipendono dalle richieste.
    assertThat(askedChainEnded(only = false, hasRequests = false)).isFalse()
    assertThat(askedChainEnded(only = false, hasRequests = true)).isFalse()
  }

  /**
   * La catena **finisce** se non restano richieste valide (scadute da 24 ore, finite, cancellate), qualunque sia l'esito, anche quando il cancello delle regole ha
   * fermato la tappa prima di partire. Mutazione: ignorare `hasRequests`.
   */
  @Test
  fun senzaRichiesteValideLaCatenaFinisce() {
    val outcomes = listOf(
      StageOutcome.Yielded(YieldReason.TIME_SLICE, NextStage.IMMEDIATELY, stats),
      StageOutcome.Waiting(WaitReason.Thermal(ThermalStatus.SEVERE), emptyList(), stats),
      StageOutcome.Stopped(WaitReason.Thermal(ThermalStatus.SEVERE), emptyList(), stats),
      confirmation(ConfirmationKind.MOBILE_DATA),
      confirmation(ConfirmationKind.OVER_CAP),
      StageOutcome.Finished(stats),
    )
    for (outcome in outcomes) {
      assertThat(askedFollowUp(outcome, askedSinceStart = true, hasRequests = false)).isEqualTo(AskedFollowUp.Done)
    }
    // E con richieste valide gli stessi esiti non finiscono.
    assertThat(askedFollowUp(outcomes.first(), askedSinceStart = false, hasRequests = true)).isEqualTo(AskedFollowUp.Again)
  }
}
