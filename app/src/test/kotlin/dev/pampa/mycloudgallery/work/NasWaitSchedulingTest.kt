package dev.pampa.mycloudgallery.work

import android.app.Application
import android.content.Context
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.ThermalStatus
import dev.pampa.mycloudgallery.core.rules.TimeWindow
import dev.pampa.mycloudgallery.core.rules.WaitReason
import dev.pampa.mycloudgallery.core.rules.run.NextStage
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import dev.pampa.mycloudgallery.core.rules.run.StageStats
import dev.pampa.mycloudgallery.core.rules.run.YieldReason
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **Fase I (12/11, punto 7)**: un lavoro del NAS fermato da una regola (il caldo, la carica, la fascia, il risparmio energetico) si riprogramma con la
 * regola unica di [RuleWaits.followUpOf], come il backup, il calcolo AI e i volti. Prima aspettava il giro periodico: sul telefono dell'utente Preparazione e
 * scansione sono rimaste a +3 h 40 mentre l'app diceva «riprende da solo».
 *
 * Il **WorkManager vero di test** (esecutore sincrono, come `AiWorkSchedulingTest`): la semantica dei nomi unici e delle politiche e' quella vera. Il lavoro di
 * prova prende il posto di un lavoro del NAS e, come `NasWorker`, chiede la ripresa con la funzione vera ([NasWorkScheduling.afterWait]) e i suoi input.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NasWaitSchedulingTest {

  private val context: Application = ApplicationProvider.getApplicationContext()
  private val workManager: WorkManager get() = WorkManager.getInstance(context)
  private val now: LocalDateTime = NOW

  /** Che cosa fa il lavoro di prova quando gira: l'attesa che trova. */
  private object Script {
    var wait: WaitReason? = null
    val attempts = mutableListOf<Int>()

    fun reset() {
      wait = null
      attempts.clear()
    }
  }

  /** Il lavoro di prova: come `NasWorker` dopo una tappa in attesa, chiede la ripresa con la funzione vera e i suoi input. */
  private class FakeNasWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
      Script.attempts += inputData.getInt(RuleWaits.KEY_ATTEMPT, 0)
      Script.wait?.let { NasWorkScheduling.afterWait(applicationContext, NAME, FakeNasWorker::class.java, it, inputData, NOW) }
      return Result.success()
    }
  }

  @Before
  fun setUp() {
    Script.reset()
    val factory = object : WorkerFactory() {
      override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
        if (workerClassName == FakeNasWorker::class.java.name) FakeNasWorker(appContext, workerParameters) else null
    }
    WorkManagerTestInitHelper.initializeTestWorkManager(
      context,
      Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build(),
    )
  }

  private fun infos(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(NAME).get()

  private fun pending(): List<WorkInfo> = infos().filter { !it.state.isFinished }

  /** Una chiamata da un lavoro che non e' la ripresa (il periodico, la tappa dopo, «verifica adesso»): input senza tentativo. */
  private fun fromFreshRun(wait: WaitReason, input: Data = Data.EMPTY): Boolean =
    NasWorkScheduling.afterWait(context, NAME, FakeNasWorker::class.java, wait, input, now)

  /** Fa girare la ripresa in coda (il suo ritardo e i suoi vincoli sono soddisfatti): il lavoro di prova la esegue. */
  private fun runPending() {
    val id: UUID = pending().single { it.state == WorkInfo.State.ENQUEUED }.id
    val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
    driver.setAllConstraintsMet(id)
    driver.setInitialDelayMet(id)
  }

  // --- Il ritardo giusto ----------------------------------------------------------------------------------------------------------------

  @Test
  fun unaAttesaPerIlCaldoProgrammaLaRipresaFraCinqueMinuti() {
    assertThat(fromFreshRun(WaitReason.Thermal(ThermalStatus.SEVERE))).isTrue()
    val resume = pending().single()
    assertThat(resume.initialDelayMillis).isEqualTo(5.minutes.inWholeMilliseconds)
    assertThat(resume.tags).contains(NasWorkScheduling.TAG)
    assertThat(resume.tags).contains(FakeNasWorker::class.java.name)
  }

  @Test
  fun fuoriFasciaLaRipresaEAllInizioDellaFascia() {
    val window = TimeWindow(LocalTime.of(1, 0), LocalTime.of(6, 0))
    fromFreshRun(WaitReason.OutsideWindow(window, now.plusHours(10)))
    assertThat(pending().single().initialDelayMillis).isEqualTo((10.hours + RuleWaits.MIN_RETRY).inWholeMilliseconds)
  }

  /** Guardia «vincolo non piu' largo» (mutazione M7: i vincoli della regola senza la rete dei lavori del NAS). */
  @Test
  fun laRipresaChiedeLaStessaReteDelPeriodicoEInPiuLaCaricaSeLaRegolaLaChiede() {
    fromFreshRun(WaitReason.NeedsPower)
    val charging = pending().single()
    assertThat(charging.initialDelayMillis).isEqualTo(RuleWaits.MIN_RETRY.inWholeMilliseconds)
    assertThat(charging.constraints.requiresCharging()).isTrue()
    val request = charging.constraints.requiredNetworkRequest
    assertWithMessage("la rete dei lavori del NAS").that(request).isNotNull()
    assertThat(request!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).isFalse()

    // (Una ripresa c'e' gia' e un produttore esterno non la tocca: si toglie per provare l'altra.)
    workManager.cancelUniqueWork(NAME).result.get()
    fromFreshRun(WaitReason.Thermal(ThermalStatus.SEVERE))
    val hot = pending().single()
    assertThat(hot.constraints.requiresCharging()).isFalse()
    assertThat(hot.constraints.requiredNetworkRequest).isNotNull()
  }

  // --- Le attese che non sono una regola da aspettare ------------------------------------------------------------------------------------

  /** Guardia «un'attesa che non e' una regola non entra» (mutazione M6: una ripresa anche quando la regola unica non ne chiede una). */
  @Test
  fun ilNasSpentoLaReteIlCongelamentoNonProgrammanoRiprese() {
    for (wait in listOf(
      WaitReason.NasUnreachable,
      WaitReason.Offline,
      WaitReason.NetworkNotAllowed(NetworkClass.MOBILE),
      WaitReason.Frozen(FreezeReason.VANISHED),
      WaitReason.ProfileInactive,
    )) {
      assertWithMessage("$wait").that(fromFreshRun(wait)).isFalse()
    }
    assertThat(infos()).isEmpty()
  }

  // --- La scala cresce e ha un tetto ------------------------------------------------------------------------------------------------------

  /** Guardie «cresce» e «tetto» (mutazioni M1: il tentativo degli input ignorato; M2: un tetto di 24 ore al posto di quello della regola). */
  @Test
  fun laRipresaCheAspettaAncoraRaddoppiaFinoAlTettoDiUnOra() {
    Script.wait = WaitReason.Thermal(ThermalStatus.SEVERE)
    fromFreshRun(WaitReason.Thermal(ThermalStatus.SEVERE))
    val delays = mutableListOf(pending().single().initialDelayMillis)
    repeat(6) {
      runPending()
      delays += pending().single { it.state == WorkInfo.State.ENQUEUED }.initialDelayMillis
    }
    assertThat(delays.map { it / 60_000 }).containsExactly(5L, 10L, 20L, 40L, 60L, 60L, 60L).inOrder()
    assertThat(Script.attempts).containsExactly(1, 2, 3, 4, 5, 6).inOrder()
    // Mai piu' di una ripresa in coda, anche dopo sei giri.
    assertThat(pending()).hasSize(1)
  }

  // --- Una sola ripresa per lavoro, e la scala riparte da capo ----------------------------------------------------------------------------

  /**
   * Guardia «una sola, e nessuno la cancella» (BS-2): un produttore esterno (il periodico, «verifica adesso») che trova di nuovo il caldo **non tocca** la ripresa
   * gia' in coda: resta quella, con la sua scadenza (qui al quarto gradino, 40 minuti), e non se ne aggiunge un'altra. Si rinuncia all'azzeramento della scala:
   * il tetto di un'ora resta. Cadrebbe con `APPEND_OR_REPLACE` per gli esterni (le riprese si accumulano) e con `REPLACE` (la ripresa cambia, a 5 minuti).
   */
  @Test
  fun unProduttoreEsternoNonToccaLaRipresaInCoda() {
    fromFreshRun(WaitReason.Thermal(ThermalStatus.SEVERE), Data.Builder().putInt(RuleWaits.KEY_ATTEMPT, 3).build())
    val queued = pending().single()
    assertThat(queued.initialDelayMillis).isEqualTo(40.minutes.inWholeMilliseconds)
    fromFreshRun(WaitReason.Thermal(ThermalStatus.SEVERE))
    val left = pending().single()
    assertThat(left.id).isEqualTo(queued.id)
    assertThat(left.initialDelayMillis).isEqualTo(40.minutes.inWholeMilliseconds)
  }

  /**
   * Guardia «non cancella la ripresa che gira» (BS-2), con una ripresa **nello stato RUNNING del database di WorkManager** (lo stato che WorkManager le da'
   * mentre gira): un altro lavoro trova il caldo e chiede la ripresa. Con `KEEP` non c'e' nessuna lettura dello stato prima dell'accodamento, quindi nessuna
   * finestra fra una lettura e la transizione ENQUEUED -> RUNNING; e WorkManager tiene la ripresa che gira: resta RUNNING, l'unica, mai CANCELLED. Cadrebbe
   * con `REPLACE` per gli esterni (la cancella e la cancella dal database).
   *
   * Perimetro: e' lo stato del database, non un lavoro che esegue davvero codice in parallelo (l'esecutore di prova e' sincrono e l'accodamento chiesto da
   * dentro un lavoro gira solo dopo la sua fine: allora la ripresa e' finita e `KEEP` ne accoda giustamente una nuova).
   */
  @Test
  fun unaRipresaCheStaGirandoNonVieneCancellataDaUnAltroLavoro() {
    fromFreshRun(WaitReason.Thermal(ThermalStatus.SEVERE))
    val resume = pending().single().id
    WorkManagerImpl.getInstance(context).workDatabase.workSpecDao().setState(WorkInfo.State.RUNNING, resume.toString())
    fromFreshRun(WaitReason.Thermal(ThermalStatus.SEVERE))
    assertThat(workManager.getWorkInfoById(resume).get()?.state).isEqualTo(WorkInfo.State.RUNNING)
    assertThat(pending().map { it.id }).containsExactly(resume)
  }

  /** Una ripresa gia' finita non conta: il periodico che trova di nuovo il caldo ne mette in coda una nuova (una sola). */
  @Test
  fun dopoUnaRipresaFinitaUnaNuova() {
    fromFreshRun(WaitReason.Thermal(ThermalStatus.SEVERE))
    runPending()
    assertThat(pending()).isEmpty()
    fromFreshRun(WaitReason.Thermal(ThermalStatus.SEVERE))
    assertThat(pending()).hasSize(1)
  }

  @Test
  fun laPoliticaDellaRipresa() {
    assertThat(NasWorkScheduling.waitPolicy(fromResume = true)).isEqualTo(ExistingWorkPolicy.APPEND_OR_REPLACE)
    assertThat(NasWorkScheduling.waitPolicy(fromResume = false)).isEqualTo(ExistingWorkPolicy.KEEP)
  }

  // --- Che cosa segue una tappa ---------------------------------------------------------------------------------------------------------

  /** Un `Yielded` continua come prima (la tappa dopo); un'attesa o una fermata delle regole chiedono la ripresa; il resto niente. */
  @Test
  fun dopoUnaTappa() {
    val stats = StageStats(batches = 0, bytes = 0L, roundBytes = 0L, elapsed = kotlin.time.Duration.ZERO, resumed = false)
    val hot = WaitReason.Thermal(ThermalStatus.SEVERE)
    assertThat(NasFollowUp.of(StageOutcome.Yielded(YieldReason.TIME_SLICE, NextStage.WHEN_ALLOWED, stats))).isEqualTo(NasFollowUp.Continue)
    assertThat(NasFollowUp.of(StageOutcome.Waiting(hot, emptyList(), stats))).isEqualTo(NasFollowUp.Wait(hot))
    assertThat(NasFollowUp.of(StageOutcome.Stopped(hot, emptyList(), stats))).isEqualTo(NasFollowUp.Wait(hot))
    assertThat(NasFollowUp.of(StageOutcome.Finished(stats))).isEqualTo(NasFollowUp.None)
    assertThat(NasFollowUp.of(null)).isEqualTo(NasFollowUp.None)
  }

  /** Ogni lavoro del NAS ha la sua ripresa, con un nome che non e' quello del periodico ne' quello della tappa dopo. */
  @Test
  fun leRipreseHannoNomiPropri() {
    val waits = listOf(
      NasWorkScheduling.SCAN_WAIT_WORK, NasWorkScheduling.SYNC_WAIT_WORK, NasWorkScheduling.PREP_WAIT_WORK, NasWorkScheduling.HQ_WAIT_WORK,
      NasWorkScheduling.TRASH_PURGE_WAIT_WORK, NasWorkScheduling.MAINTENANCE_WAIT_WORK, NasWorkScheduling.PERSONAL_WAIT_WORK,
    )
    val others = listOf(
      NasWorkScheduling.SCAN_WORK, NasWorkScheduling.SCAN_NOW_WORK, NasWorkScheduling.SYNC_WORK, NasWorkScheduling.SYNC_NOW_WORK,
      NasWorkScheduling.PREP_WORK, NasWorkScheduling.PREP_NOW_WORK, NasWorkScheduling.HQ_WORK, NasWorkScheduling.HQ_NOW_WORK,
      NasWorkScheduling.TRASH_PURGE_WORK, NasWorkScheduling.TRASH_PURGE_NOW_WORK, NasWorkScheduling.MAINTENANCE_WORK,
      NasWorkScheduling.MAINTENANCE_NOW_WORK, NasWorkScheduling.PERSONAL_WORK, NasWorkScheduling.PERSONAL_NOW_WORK, NasWorkScheduling.SUMMARY_WORK,
    )
    assertThat(waits.toSet()).hasSize(waits.size)
    assertThat(waits.intersect(others.toSet())).isEmpty()
  }

  private companion object {
    const val NAME = "nas-prova-wait"
    val NOW: LocalDateTime = LocalDateTime.of(2026, 11, 12, 15, 0)
  }
}
