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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
    val verdict = withTimeout(Attese.TETTO) { ai.decision.first { it is RuleDecision.NeedsConfirmation } }
    assertThat(verdict).isInstanceOf(RuleDecision.NeedsConfirmation::class.java)
    assertThat(asked.last()).isEqualTo(user)

    // Una tappa finita non lascia niente di fermo: torna a valere il lavoro in background.
    ai.noteStage(StageOutcome.Finished(stats), user)
    withTimeout(Attese.TETTO) {
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
    // X-R1: gli eventi dell'ambiente si ascoltano solo mentre qualcuno guarda lo stato dell'analisi (come fa la nuvola o la pagina).
    val watcherScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
    val watcher = watcherScope.launch { ai.analysis.collect {} }

    // Una richiesta dell'utente e' ferma per le regole (stato generico "regole"): il motivo e' quello del verdetto di adesso.
    ai.noteStage(confirmation(), user)
    withTimeout(Attese.TETTO) { ai.decision.first { it == RuleDecision.Wait(WaitReason.NeedsPower) } }

    // Cambia l'ambiente (il telefono e' in carica ma ora e' la batteria a mancare): nessuna emissione del motore, solo l'evento.
    verdict = RuleDecision.Wait(WaitReason.BatteryLow(10, 20))
    triggers.emit(Unit)
    val updated = withTimeout(Attese.TETTO) { ai.decision.first { it is RuleDecision.Wait && it.reason is WaitReason.BatteryLow } }
    assertThat(updated).isInstanceOf(RuleDecision.Wait::class.java)
    watcher.cancel()
    watcherScope.cancel()
  }

  /**
   * X-R1: gli eventi dell'ambiente (ascoltatori di sistema: caldo, rete, carica) si ascoltano **solo mentre qualcuno guarda** lo stato dell'analisi, non
   * alla costruzione del grafo. Prima partivano subito: sotto Robolectric `addThermalStatusListener` non si registra e l'eccezione restava in uno scope
   * senza gestore, pagata dalla prima prova con `runTest` che girava dopo (`EntryHardeningTest`). Mutazione: ascoltare alla costruzione.
   */
  @Test
  fun gliEventiDellAmbienteSiAscoltanoSoloMentreQualcunoGuarda() = runBlocking<Unit> {
    val subscribed = java.util.concurrent.atomic.AtomicInteger()
    val active = java.util.concurrent.atomic.AtomicInteger()
    rig.decideOverride = { RuleDecision.Wait(WaitReason.PowerSaver) }
    rig.triggersOverride = kotlinx.coroutines.flow.flow {
      subscribed.incrementAndGet()
      active.incrementAndGet()
      try {
        kotlinx.coroutines.awaitCancellation()
      } finally {
        active.decrementAndGet()
      }
    }
    val device = rig.founder()
    val ai = device.stack.ai

    // Nessuno guarda: nessun ascoltatore, anche dopo un po'.
    kotlinx.coroutines.delay(500)
    assertThat(subscribed.get()).isEqualTo(0)

    // Qualcuno guarda (la nuvola, la pagina): si ascolta; smette di guardare: si smette.
    val watcherScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
    val watcher = watcherScope.launch { ai.analysis.collect {} }
    withTimeout(Attese.TETTO) { while (active.get() < 1) kotlinx.coroutines.delay(20) }
    assertThat(subscribed.get()).isEqualTo(1)
    watcher.cancel()
    withTimeout(Attese.TETTO) { while (active.get() > 0) kotlinx.coroutines.delay(20) }
    watcherScope.cancel()
  }

  /**
   * XS3-3: un errore degli ascoltatori di sistema (la registrazione rifiutata) **non esce senza gestore** e non e' ingoiato: una riga nel log, l'ascolto si ferma, e un nuovo
   * sguardo riprova. Si ascolta l'`uncaughtExceptionHandler` del thread: un'eccezione che esce da uno scope del grafo senza gestore ci passa. Mutazione: togliere il `catch`
   * dei trigger in `AiGraph` (l'eccezione risale al `launch`).
   */
  @Test
  fun unErroreDegliEventiDellAmbienteNonEsceSenzaGestoreERiprovaAlProssimoSguardo() = runBlocking<Unit> {
    val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    val watcherScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
    try {
      val attempts = java.util.concurrent.atomic.AtomicInteger()
      rig.decideOverride = { RuleDecision.Wait(WaitReason.PowerSaver) }
      rig.triggersOverride = kotlinx.coroutines.flow.flow {
        attempts.incrementAndGet()
        throw IllegalStateException("registrazione rifiutata")
      }
      val device = rig.founder()
      val first = watcherScope.launch { device.stack.ai.analysis.collect {} }
      withTimeout(Attese.TETTO) { while (attempts.get() < 1) kotlinx.coroutines.delay(20) }
      kotlinx.coroutines.delay(500)
      assertThat(uncaught).isEmpty()
      first.cancel()
      kotlinx.coroutines.delay(100)
      // Un nuovo sguardo riprova (l'ascolto non resta rotto per sempre) e l'errore e' di nuovo confinato.
      val again = watcherScope.launch { device.stack.ai.analysis.collect {} }
      withTimeout(Attese.TETTO) { while (attempts.get() < 2) kotlinx.coroutines.delay(20) }
      kotlinx.coroutines.delay(500)
      assertThat(uncaught).isEmpty()
      again.cancel()
    } finally {
      watcherScope.cancel()
      Thread.setDefaultUncaughtExceptionHandler(previous)
    }
  }
}
