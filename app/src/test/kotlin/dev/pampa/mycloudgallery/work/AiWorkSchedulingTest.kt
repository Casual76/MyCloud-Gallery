package dev.pampa.mycloudgallery.work

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.rules.ThermalStatus
import dev.pampa.mycloudgallery.core.rules.TimeWindow
import dev.pampa.mycloudgallery.core.rules.WaitReason
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **IX-1** (verifica funzionale della Fase 6): il calcolo si fermava dopo una tappa da 8 minuti. La tappa dopo si chiedeva con `KEEP` sullo stesso
 * nome del lavoro **ancora in corso**: per WorkManager un lavoro in corso e' "non finito", e `KEEP` buttava la richiesta. "Indicizza ora" analizzava
 * circa 1.300 foto e si fermava; un'attesa delle regole (fuori fascia, non in carica) non riprogrammava niente.
 *
 * I test usano il **WorkManager vero di test** (`WorkManagerTestInitHelper`, esecutore sincrono): i fake dei test d'integrazione non riproducevano la
 * semantica di `KEEP` e per questo il difetto non si era mai visto. Un lavoro di prova prende il posto di `AiComputeWorker` (la fabbrica) e, come il
 * vero, chiede la tappa dopo con le stesse funzioni di [AiWorkScheduling].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AiWorkSchedulingTest {

  private val context: Application = ApplicationProvider.getApplicationContext()
  private val workManager: WorkManager get() = WorkManager.getInstance(context)

  /** Quante tappe sono girate, e quante ne restano da fare: ogni tappa finita per tempo chiede la prossima, come `AiComputeWorker`. */
  private object Script {
    val ran = AtomicInteger()
    val users = mutableListOf<Boolean>()
    var stagesToRun = 0
    var duringRun: (() -> Unit)? = null

    fun reset(stages: Int) {
      ran.set(0)
      users.clear()
      stagesToRun = stages
      duringRun = null
    }
  }

  /** Il lavoro di prova: una tappa; se ne restano, chiede la prossima con la funzione vera (`continueCompute`). */
  private class FakeComputeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
      val user = inputData.getBoolean(AiWorkScheduling.KEY_USER, false)
      val n = Script.ran.incrementAndGet()
      Script.users += user
      Script.duringRun?.also { Script.duringRun = null }?.invoke()
      if (n < Script.stagesToRun) AiWorkScheduling.continueCompute(applicationContext, user)
      return Result.success()
    }
  }

  @Before
  fun setUp() {
    val factory = object : WorkerFactory() {
      override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
        if (workerClassName == AiComputeWorker::class.java.name) FakeComputeWorker(appContext, workerParameters) else null
    }
    WorkManagerTestInitHelper.initializeTestWorkManager(
      context,
      Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build(),
    )
  }

  @Test fun cr7_ilRiavvioAutomaticoNonSostituisceLaRichiestaEsplicitaInCoda() {
    AiWorkScheduling.fetchNow(context, ModelTrigger.ROLE_CHANGED)
    val before = workManager.getWorkInfosForUniqueWork(AiWorkScheduling.FETCH_NOW_WORK).get().single()
    AiWorkScheduling.fetchNow(context, ModelTrigger.RULES)
    AiWorkScheduling.fetchNow(context, ModelTrigger.ROLE_CHANGED, automatic = true)
    val after = workManager.getWorkInfosForUniqueWork(AiWorkScheduling.FETCH_NOW_WORK).get().single()
    assertThat(after.id).isEqualTo(before.id)
    assertThat(after.state).isEqualTo(WorkInfo.State.ENQUEUED)
  }

  @Test
  fun ix1_indicizzaOraConLavoroPerTreTappeFaGirareTreTappe() {
    Script.reset(stages = 3)

    AiWorkScheduling.indexNow(context)

    assertThat(Script.ran.get()).isEqualTo(3)
    // La catena resta una richiesta dell'utente (nome e vincoli di "Indicizza ora") fino all'ultima tappa.
    assertThat(Script.users).containsExactly(true, true, true)
    val infos = workManager.getWorkInfosForUniqueWork(AiWorkScheduling.COMPUTE_NOW_WORK).get()
    assertThat(infos.count { it.state == WorkInfo.State.SUCCEEDED }).isEqualTo(3)
  }

  @Test
  fun ix1_ilGiroDelleNovitaContinuaSulSuoNomeSenzaAssorbireIndicizzaOra() {
    Script.reset(stages = 3)

    AiWorkScheduling.afterNews(context)

    assertThat(Script.ran.get()).isEqualTo(3)
    assertThat(Script.users).containsExactly(false, false, false)
    // Le tappe dopo di un giro non richiesto stanno nella loro catena, mai in quella di "Indicizza ora".
    assertThat(workManager.getWorkInfosForUniqueWork(AiWorkScheduling.COMPUTE_NOW_WORK).get()).isEmpty()
    assertThat(workManager.getWorkInfosForUniqueWork(AiWorkScheduling.COMPUTE_NEXT_WORK).get().count { it.state == WorkInfo.State.SUCCEEDED }).isEqualTo(2)
  }

  @Test
  fun ix1_unTocco_DiIndicizzaOraMentreUnGiroDelleNovitaGiraNonViene_Assorbito() {
    // Il giro delle novita' chiede "Indicizza ora" mentre gira (l'utente tocca): con due nomi diversi la richiesta dell'utente parte; con il
    // nome condiviso di prima, `KEEP` la buttava perche' il giro in corso e' "non finito".
    Script.reset(stages = 1)
    Script.duringRun = { AiWorkScheduling.indexNow(context) }

    AiWorkScheduling.afterNews(context)

    assertThat(Script.users).containsExactly(false, true)
  }

  // --- L'attesa delle regole riprogramma ---------------------------------------------------------------------------------------------

  private val night = LocalDateTime.of(2026, 10, 22, 14, 0)

  @Test
  fun ix1_fuoriDallaFasciaIlLavoroRipartiAllInizioDellaFascia() {
    val follow = RuleWaits.followUpOf(WaitReason.OutsideWindow(TimeWindow(LocalTime.MIDNIGHT, LocalTime.of(7, 0)), night.plusHours(10)), night)!!
    // Dieci ore piu' il margine: la fascia comincia alle 00:00.
    assertThat(follow.delay).isEqualTo((10 * 60).minutes + RuleWaits.MIN_RETRY)
    assertThat(follow.charging).isFalse()
  }

  @Test
  fun ix1_senzaCaricaIlLavoroAspettaLaCaricaSenzaPolling() {
    val follow = RuleWaits.followUpOf(WaitReason.NeedsPower, night)!!
    assertThat(follow.charging).isTrue()
    assertThat(follow.delay).isEqualTo(RuleWaits.MIN_RETRY)
    // FAB-5: anche la batteria sotto soglia aspetta la carica (la regola vale solo a batteria, e la batteria risale solo in carica). Prima chiedeva
    // "batteria non scarica", che per Android e' gia' vero fra il 16% e il 19%: un risveglio al minuto.
    val batt = RuleWaits.followUpOf(WaitReason.BatteryLow(17, 20), night)!!
    assertThat(batt.charging).isTrue()
    assertThat(batt.constraints().requiresBatteryNotLow()).isFalse()
  }

  /**
   * FAB-5 della review: col risparmio energetico acceso il calcolo chiedeva il vincolo "batteria non scarica" (gia' soddisfatto) e un minuto di ritardo:
   * un risveglio al minuto, per ore. Adesso nessun vincolo finto e un ritardo che cresce fino a un'ora, con il numero del tentativo negli input del lavoro.
   */
  @Test
  fun fab5_colRisparmioEnergeticoIlCalcoloNonSiSvegliaOgniMinuto_IlRitardoCresce() {
    var attempt = 0
    val delays = ArrayList<Long>()
    repeat(4) {
      workManager.cancelAllWork().result.get()
      AiWorkScheduling.afterWait(context, user = false, wait = WaitReason.PowerSaver, attempt = attempt)
      val queued = workManager.getWorkInfosForUniqueWork(AiWorkScheduling.COMPUTE_NEXT_WORK).get().single { it.state == WorkInfo.State.ENQUEUED }
      assertThat(queued.constraints.requiresBatteryNotLow()).isFalse()
      assertThat(queued.constraints.requiresCharging()).isFalse()
      delays += queued.initialDelayMillis
      attempt = RuleWaits.followUpOf(WaitReason.PowerSaver, night, attempt)!!.attempt
    }
    assertThat(delays).containsExactly(15 * 60_000L, 30 * 60_000L, 60 * 60_000L, 60 * 60_000L).inOrder()
  }

  /** FAB-5: il lavoro vero rilegge il numero del tentativo dai suoi input e lo passa alla regola: la catena delle attese cresce da sola. */
  @Test
  fun fab5_ilNumeroDelTentativoViaggiaNegliInputDelLavoro() {
    AiWorkScheduling.afterWait(context, user = false, wait = WaitReason.PowerSaver, attempt = 2)
    val queued = workManager.getWorkInfosForUniqueWork(AiWorkScheduling.COMPUTE_NEXT_WORK).get().single()
    assertThat(queued.initialDelayMillis).isEqualTo(60 * 60_000L)
    // Con la carica (un vincolo esatto) il contatore torna a zero: la prossima attesa senza vincolo riparte dal primo ritardo.
    assertThat(RuleWaits.followUpOf(WaitReason.NeedsPower, night, attempt = 3)!!.attempt).isEqualTo(0)
  }

  @Test
  fun ix1_colCaldoSiRiprovaDopoCinqueMinuti() {
    val follow = RuleWaits.followUpOf(WaitReason.Thermal(ThermalStatus.SEVERE), night)!!
    assertThat(follow.delay).isEqualTo(5.minutes)
  }

  @Test
  fun ix1_ilNasSpentoIlProfiloInattivoEIlResto_NonProgrammanoNiente() {
    for (wait in listOf(WaitReason.NasUnreachable, WaitReason.ProfileInactive, WaitReason.ActivityDisabled, WaitReason.Offline)) {
      assertWithMessage("$wait").that(RuleWaits.followUpOf(wait, night)).isNull()
    }
  }

  @Test
  fun ix1_unaAttesaMettePoiInCodaIlLavoroConIlVincoloEsatto() {
    // Non parte subito (la carica manca): resta in coda con il vincolo della carica.
    AiWorkScheduling.afterWait(context, user = false, wait = WaitReason.NeedsPower)

    val queued = workManager.getWorkInfosForUniqueWork(AiWorkScheduling.COMPUTE_NEXT_WORK).get().single()
    assertThat(queued.state).isEqualTo(WorkInfo.State.ENQUEUED)
    assertThat(queued.constraints.requiresCharging()).isTrue()
    assertThat(queued.initialDelayMillis).isAtLeast(RuleWaits.MIN_RETRY.inWholeMilliseconds)
  }

  // --- MO-1/R11/MO-9: lo scaricamento accelerato ------------------------------------------------------------------------------------

  @Test
  fun mo1_scaricaOraEUnLavoroPerModelloEUnRichiestaDelCambioDiRuoloSiNeCoalescono() {
    AiWorkScheduling.fetchNow(context, ModelTrigger.USER_NOW, ModelCatalog.QUERY_270M.id)
    AiWorkScheduling.fetchNow(context, ModelTrigger.USER_NOW, ModelCatalog.INDEX_440M.id)

    val q = workManager.getWorkInfosForUniqueWork("${AiWorkScheduling.FETCH_NOW_WORK}-${ModelCatalog.QUERY_270M.id.value}").get()
    val i = workManager.getWorkInfosForUniqueWork("${AiWorkScheduling.FETCH_NOW_WORK}-${ModelCatalog.INDEX_440M.id.value}").get()
    assertWithMessage("una richiesta per il 270M").that(q).hasSize(1)
    assertWithMessage("e una per il 440M: non si buttano a vicenda").that(i).hasSize(1)
  }

  @Test
  fun r11_ilCambioDiRuoloAspettaUnaReteNonAConsumo_ScaricaOraUnaReteQualunque() {
    AiWorkScheduling.fetchNow(context, ModelTrigger.ROLE_CHANGED)
    AiWorkScheduling.fetchNow(context, ModelTrigger.USER_NOW, ModelCatalog.QUERY_270M.id)

    val role = workManager.getWorkInfosForUniqueWork(AiWorkScheduling.FETCH_NOW_WORK).get().single()
    val user = workManager.getWorkInfosForUniqueWork("${AiWorkScheduling.FETCH_NOW_WORK}-${ModelCatalog.QUERY_270M.id.value}").get().single()
    val roleNet = checkNotNull(role.constraints.requiredNetworkRequest)
    assertThat(roleNet.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).isTrue()
    // "Scarica ora": la rete la giudica il cancello di rete dei modelli (dati mobili con la conferma, mai roaming), il vincolo non la esclude.
    val userNet = checkNotNull(user.constraints.requiredNetworkRequest)
    assertThat(userNet.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).isFalse()
  }
}
