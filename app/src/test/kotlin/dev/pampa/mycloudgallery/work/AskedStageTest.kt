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
}
