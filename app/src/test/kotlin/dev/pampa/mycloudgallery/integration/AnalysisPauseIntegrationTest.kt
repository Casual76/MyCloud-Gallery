package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.rules.BackgroundActivity
import dev.pampa.mycloudgallery.core.rules.ConfirmationKind
import dev.pampa.mycloudgallery.core.rules.RuleDecision
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.WorkRequest
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import dev.pampa.mycloudgallery.core.rules.run.StageStats
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * XS-7 (Pacchetto XF), sul grafo vero (`AiGraph` montato da `AiWiring`, regole e verdetti scelti dalla prova): il motivo di una pausa e' quello della
 * **richiesta davvero eseguita** (utente o background), non quello del lavoro in background di sempre; e si **aggiorna** quando cambiano ambiente o
 * impostazioni, senza che il motore emetta niente. Mutazioni eseguite (vedi il resoconto): valutare sempre il background (cade
 * `ilMotivoEQuelloDellaRichiestaUtenteNonDelBackground`), non rileggere agli eventi (cade `uncambioDiAmbienteAggiornaIlMotivoSenzaChiedereIlMotore`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class AnalysisPauseIntegrationTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private lateinit var rig: AiRig
  private val stats = StageStats(batches = 0, bytes = 0, roundBytes = 0, elapsed = Duration.ZERO, resumed = false)

  @Before
  fun setUp() {
    rig = AiRig(app, temp.root)
  }

  @After
  fun tearDown() {
    rig.close()
  }

  private val user = WorkRequest.UserInitiated(BackgroundActivity.AI_COMPUTE, confirmed = false, writesToNas = true)
  private val background = WorkRequest.Background(BackgroundActivity.AI_COMPUTE, writesToNas = true)

  private fun confirmation() =
    StageOutcome.NeedsConfirmation(RuleDecision.NeedsConfirmation(setOf(ConfirmationKind.MOBILE_DATA), estimate = null), stats)

  @Test
  fun ilMotivoEQuelloDellaRichiestaUtenteNonDelBackground() = runBlocking<Unit> {
    // Per la richiesta dell'utente le regole chiedono una conferma (dati mobili); per il lavoro in background dicono "fuori dalla fascia".
    val asked = java.util.concurrent.CopyOnWriteArrayList<WorkRequest>()
    rig.decideOverride = { request ->
      asked += request
      when (request) {
        is WorkRequest.UserInitiated -> RuleDecision.NeedsConfirmation(setOf(ConfirmationKind.MOBILE_DATA), estimate = null)
        else -> RuleDecision.Wait(WaitReason.PowerSaver)
      }
    }
    rig.triggersOverride = MutableSharedFlow()
    val device = rig.founder()
    val ai = device.stack.ai

    // La tappa fermata era una richiesta dell'utente: il motivo vero e' il suo ("serve il Wi-Fi"), non "risparmio energetico" del background.
    ai.noteStage(confirmation(), user)
    val verdict = withTimeout(10_000) { ai.decision.first { it is RuleDecision.NeedsConfirmation } }
    assertThat(verdict).isInstanceOf(RuleDecision.NeedsConfirmation::class.java)
    assertThat(asked.last()).isEqualTo(user)

    // Una tappa finita non lascia niente di fermo: torna a valere il lavoro in background.
    ai.noteStage(StageOutcome.Finished(stats), user)
    withTimeout(10_000) {
      while (asked.last() !is WorkRequest.Background) kotlinx.coroutines.delay(50)
    }
    assertThat(asked.last()).isInstanceOf(WorkRequest.Background::class.java)
  }

  @Test
  fun unCambioDiAmbienteAggiornaIlMotivoSenzaChiedereIlMotore() = runBlocking<Unit> {
    var verdict: RuleDecision = RuleDecision.Wait(WaitReason.NeedsPower)
    rig.decideOverride = { verdict }
    val triggers = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    rig.triggersOverride = triggers
    val device = rig.founder()
    val ai = device.stack.ai

    // Una richiesta dell'utente e' ferma per le regole (stato generico "regole"): il motivo e' quello del verdetto di adesso.
    ai.noteStage(confirmation(), user)
    withTimeout(10_000) { ai.decision.first { it == RuleDecision.Wait(WaitReason.NeedsPower) } }

    // Cambia l'ambiente (il telefono e' in carica ma ora e' la batteria a mancare): nessuna emissione del motore, solo l'evento.
    verdict = RuleDecision.Wait(WaitReason.BatteryLow(10, 20))
    triggers.emit(Unit)
    val updated = withTimeout(10_000) { ai.decision.first { it is RuleDecision.Wait && it.reason is WaitReason.BatteryLow } }
    assertThat(updated).isInstanceOf(RuleDecision.Wait::class.java)
  }
}
