package dev.pampa.mycloudgallery.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiStatus
import dev.pampa.mycloudgallery.core.ai.contract.AiWaitReason
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisWait
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.ConfirmationKind
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.ThermalStatus
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import dev.pampa.mycloudgallery.core.rules.run.StageStats
import dev.pampa.mycloudgallery.core.search.contract.SearchCoverage
import kotlin.time.Duration
import org.junit.Test

/**
 * Pacchetto XF, XS-7: il motivo di una pausa e' quello della **richiesta davvero eseguita** e il residuo scende mentre la tappa gira. Con XS-3: una
 * richiesta dell'utente non confermata che chiede conferma dice "serve il Wi-Fi", non "in coda". Ogni prova nomina la mutazione che la fa cadere.
 */
class AnalysisPauseXfTest {

  // ---- Il residuo -------------------------------------------------------------------------------------------------------------

  /** "Restano N" non resta fermo durante una tappa lunga: scende con il contatore dei media finiti, senza ricontare il database. Mutazione: nessun calo. */
  @Test
  fun ilResiduoScendeMentreLaTappaGira() {
    val tracker = RemainingTracker()
    tracker.set(10)
    tracker.onFinished(1)
    assertThat(tracker.state.value).isEqualTo(9)
    tracker.onFinished(2)
    assertThat(tracker.state.value).isEqualTo(8)
    // Lo stesso contatore due volte non conta due volte.
    tracker.onFinished(2)
    assertThat(tracker.state.value).isEqualTo(8)
  }

  /** Chi guarda puo' saltare emissioni (un `StateFlow` ne unisce piu' d'una): il contatore e' monotono e non perde nessun completamento. Mutazione: un media per emissione. */
  @Test
  fun ilResiduoNonPerdeICompletamentiSeIlFlussoSaltaEmissioni() {
    val tracker = RemainingTracker()
    tracker.set(10)
    tracker.onFinished(1)
    // Tre media finiti fra una lettura e l'altra, un'emissione sola.
    tracker.onFinished(4)
    assertThat(tracker.state.value).isEqualTo(6)
  }

  @Test
  fun ilResiduoNonScendeSottoZeroENonSiInventa() {
    val tracker = RemainingTracker()
    // Non ancora letto: resta "non lo so", mai un numero finto.
    tracker.onFinished(1)
    assertThat(tracker.state.value).isNull()
    tracker.set(1)
    tracker.onFinished(2)
    tracker.onFinished(5)
    assertThat(tracker.state.value).isEqualTo(0)
  }

  @Test
  fun laRilletturaVeraRimetteIlNumeroGiustoELaPrimaOsservazioneNonConta() {
    val tracker = RemainingTracker()
    tracker.set(50)
    // Un contatore gia' avviato (media finiti in una tappa di prima): la prima osservazione non e' lavoro finito adesso.
    tracker.observed(7)
    tracker.onFinished(7)
    assertThat(tracker.state.value).isEqualTo(50)
    tracker.onFinished(8)
    assertThat(tracker.state.value).isEqualTo(49)
    // A fine tappa il numero vero sostituisce la stima.
    tracker.set(30)
    assertThat(tracker.state.value).isEqualTo(30)
  }

  // ---- Quale richiesta decide il motivo ---------------------------------------------------------------------------------------

  private val stats = StageStats(batches = 0, bytes = 0, roundBytes = 0, elapsed = Duration.ZERO, resumed = false)

  private val user = WorkRequest.UserInitiated(BackgroundActivity.AI_COMPUTE, confirmed = false, writesToNas = true)

  /** Una tappa ferma ricorda la richiesta con cui girava (utente o background); una tappa finita no. Mutazione: valutare sempre il background. */
  @Test
  fun ilMotivoVieneDallaRichiestaDellaTappaFerma() {
    val stopped = StageOutcome.Waiting(WaitReason.Thermal(ThermalStatus.SEVERE), emptyList(), stats)
    assertThat(AnalysisRequests.effective(AnalysisRequests.pausedAfter(stopped, user))).isEqualTo(user)
    // Senza tappe ferme, o dopo una tappa finita, vale il lavoro in background.
    assertThat(AnalysisRequests.effective(null)).isEqualTo(AnalysisRequests.background())
    assertThat(AnalysisRequests.pausedAfter(StageOutcome.Finished(stats), user)).isNull()
    assertThat(AnalysisRequests.background()).isInstanceOf(WorkRequest.Background::class.java)
  }

  // ---- Il riduttore: conferma richiesta = serve il Wi-Fi -----------------------------------------------------------------------

  private fun facts(decision: RuleDecision?, status: AiStatus = AiStatus.Waiting(AiWaitReason.RULES), remaining: Int? = 10) =
    AnalysisStatusReducers.Facts(
      indexer = true,
      status = status,
      remaining = remaining,
      coverage = SearchCoverage.Partial(covered = 40, total = 100, reasons = emptyMap()),
      decision = decision,
    )

  @Test
  fun unaConfermaPerDatiMobiliDicePausaWifiNonInCoda() {
    val mobile = RuleDecision.NeedsConfirmation(setOf(ConfirmationKind.MOBILE_DATA), estimate = null)
    assertThat(AnalysisStatusReducers.reduce(facts(mobile)))
      .isEqualTo(LibraryAnalysisStatus.Paused(LibraryAnalysisWait.WIFI, remaining = 10, ready = 40, total = 100))
    val cap = RuleDecision.NeedsConfirmation(setOf(ConfirmationKind.OVER_CAP), estimate = null)
    assertThat(AnalysisStatusReducers.reduce(facts(cap)))
      .isEqualTo(LibraryAnalysisStatus.Paused(LibraryAnalysisWait.DATA_CAP, remaining = 10, ready = 40, total = 100))
    // Se non resta niente da fare la pausa non c'e': tutto pronto.
    assertThat(AnalysisStatusReducers.reduce(facts(mobile, remaining = 0))).isEqualTo(LibraryAnalysisStatus.AllReady)
  }
}
