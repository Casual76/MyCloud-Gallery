package dev.pampa.mycloudgallery.work

import android.app.Application
import android.content.Context
import android.net.NetworkCapabilities
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
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.rules.ConfirmationKind
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.ThermalStatus
import dev.pampa.mycloudgallery.core.rules.TimeWindow
import dev.pampa.mycloudgallery.core.rules.WaitReason
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **BK-2** (verifica funzionale): "Proteggi subito le foto nuove" (D34) per una foto scattata **fuori casa**. Il lavoro rapido gira, le regole
 * dicono "non sei sul Wi-Fi di casa" e la tappa finisce: nessun altro evento lo faceva ripartire al rientro (la lettura del telefono non ha
 * niente di nuovo, il giro periodico e' fra ore e con Notte parte di notte). Adesso si rimette in coda su una rete non a consumo: WorkManager
 * lo avvia quando il Wi-Fi c'e', senza polling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackupSchedulingTest {

  private val context: Application = ApplicationProvider.getApplicationContext()
  private val workManager: WorkManager get() = WorkManager.getInstance(context)

  /** La sonda di FAB-3: che cosa trovano le regole a ogni giro del lavoro rapido, e quanti giri ha fatto. */
  private object Probe {
    const val CAP = 25
    val runs = AtomicInteger()

    /** La sonda prende il posto di `QuickPhotoWorker` (gli altri test della classe non fanno girare niente). */
    @Volatile var active: Boolean = false

    /** Che cosa dicono le regole a ogni giro: un'attesa, o null = "puoi andare" (la causa e' sparita). */
    @Volatile var wait: WaitReason? = null

    fun start(reason: WaitReason?) {
      runs.set(0)
      wait = reason
      active = true
    }
  }

  /**
   * Il lavoro di prova al posto di `QuickPhotoWorker`: come `BackupWorker.doWork` davanti a `BackupFollowUp.Wait`, chiama l'`afterWait` **vero** con il
   * numero del tentativo dei suoi input e finisce con successo. Oltre [Probe.CAP] giri si ferma (la prova non gira all'infinito per dire di no).
   */
  private class WaitProbeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
      val n = Probe.runs.incrementAndGet()
      val wait = Probe.wait
      if (wait != null && n < Probe.CAP) {
        BackupWorkScheduling.afterWait(applicationContext, BackupWorkScheduling.Kind.QUICK, wait, attempt = inputData.getInt(RuleWaits.KEY_ATTEMPT, 0))
      }
      return Result.success()
    }
  }

  @Before
  fun setUp() {
    val factory = object : WorkerFactory() {
      override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
        if (workerClassName == QuickPhotoWorker::class.java.name && Probe.active) WaitProbeWorker(appContext, workerParameters) else null
    }
    Probe.active = false
    WorkManagerTestInitHelper.initializeTestWorkManager(
      context,
      Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build(),
    )
  }

  /**
   * Android, per la sonda: a casa sul Wi-Fi, **non in carica**, con la batteria che per Android non e' scarica (sopra il 15%). Ogni lavoro in coda i cui
   * vincoli sono soddisfatti in quella situazione (la rete, "batteria non scarica"; non la carica) parte; il tempo non passa (un ritardo non scade).
   * Si ripete finche' qualcosa parte. Rende quanti lavori restano in coda ad aspettare.
   */
  private fun letAndroidRunWhatItCan(): List<WorkInfo> {
    val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
    repeat(Probe.CAP * 2) {
      val before = Probe.runs.get()
      for (work in waiting(BackupWorkScheduling.QUICK_WORK)) {
        if (!work.constraints.requiresCharging()) driver.setAllConstraintsMet(work.id)
      }
      if (Probe.runs.get() == before) return waiting(BackupWorkScheduling.QUICK_WORK)
    }
    return waiting(BackupWorkScheduling.QUICK_WORK)
  }

  /**
   * **FAB-3** della review (maggiore), la sonda del critico: con il risparmio energetico acceso, o con la batteria fra il 16% e il 19%, il lavoro rapido
   * si rimetteva in coda **senza ritardo** con il solo vincolo "batteria non scarica", che Android da' per soddisfatto: ripartiva subito, le regole
   * dicevano di nuovo "aspetta", e cosi' a ciclo continuo (25 giri su 25 nella sonda). Adesso: **un giro solo**, e il lavoro dopo aspetta davvero
   * (un ritardo, o la carica).
   */
  @Test
  fun fab3_unAttesaCheNonCambiaFaUnGiroSolo_NonUnCicloContinuo() {
    val now = LocalDateTime.now()
    val waits = listOf(
      WaitReason.PowerSaver,
      WaitReason.BatteryLow(17, 20),
      WaitReason.BatteryLow(19, 20),
      WaitReason.NeedsPower,
      WaitReason.Thermal(ThermalStatus.SEVERE),
      WaitReason.OutsideWindow(TimeWindow(LocalTime.of(23, 0), LocalTime.of(7, 0)), now.plusHours(5)),
    )
    for (wait in waits) {
      workManager.cancelAllWork().result.get()
      Probe.start(wait)

      BackupWorkScheduling.quickWork(context)
      val left = letAndroidRunWhatItCan()

      assertWithMessage("$wait: giri del lavoro").that(Probe.runs.get()).isEqualTo(1)
      // Il lavoro dopo c'e' (le foto ripartono quando la causa sparisce) e aspetta davvero: un ritardo, e la carica dove e' lei la causa.
      val next = left.single()
      assertWithMessage("$wait: un ritardo").that(next.initialDelayMillis).isAtLeast(RuleWaits.MIN_RETRY.inWholeMilliseconds)
      assertWithMessage("$wait: mai il vincolo che Android da' gia' per soddisfatto").that(next.constraints.requiresBatteryNotLow()).isFalse()
      val wantsPower = wait == WaitReason.NeedsPower || wait is WaitReason.BatteryLow
      assertWithMessage("$wait: la carica").that(next.constraints.requiresCharging()).isEqualTo(wantsPower)
    }
  }

  /**
   * FAB-3: e quando il ritardo scade con la causa ancora li' (il risparmio energetico resta acceso per ore)? Un giro per scadenza, con il ritardo delle foto
   * appena scattate fermo a un quarto d'ora: mai due giri di fila, e spento il risparmio energetico le foto partono entro un quarto d'ora.
   */
  @Test
  fun fab3_aOgniScadenzaUnGiroSolo_EIlBackupRapidoRiprovaOgniQuartoDOra() {
    Probe.start(WaitReason.PowerSaver)
    val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
    BackupWorkScheduling.quickWork(context)
    letAndroidRunWhatItCan()

    for (round in 2..5) {
      val next = waiting(BackupWorkScheduling.QUICK_WORK).single()
      assertWithMessage("giro $round: ritardo").that(next.initialDelayMillis).isEqualTo(RuleWaits.PROMPT_CAP.inWholeMilliseconds)
      // Il quarto d'ora passa: il lavoro gira una volta, trova ancora il risparmio energetico e si rimette ad aspettare.
      driver.setInitialDelayMet(next.id)
      letAndroidRunWhatItCan()
      assertWithMessage("giro $round").that(Probe.runs.get()).isEqualTo(round)
    }

    // La causa sparisce: al giro dopo le regole lasciano passare e non resta niente in coda.
    val last = waiting(BackupWorkScheduling.QUICK_WORK).single()
    Probe.wait = null
    driver.setInitialDelayMet(last.id)
    letAndroidRunWhatItCan()
    assertThat(Probe.runs.get()).isEqualTo(6)
    assertThat(waiting(BackupWorkScheduling.QUICK_WORK)).isEmpty()
  }

  private fun waiting(name: String) = workManager.getWorkInfosForUniqueWork(name).get().filter { it.state == WorkInfo.State.ENQUEUED }

  @Test
  fun bk2_laFotoScattataFuoriCasaAspettaIlWifiENonLaProssimaNotte() {
    val awayFromHome = listOf(
      WaitReason.NetworkNotAllowed(NetworkClass.MOBILE),
      WaitReason.NetworkNotAllowed(NetworkClass.EXTERNAL_WIFI),
      WaitReason.Offline,
      WaitReason.NetworkNotTrusted,
      WaitReason.Roaming,
    )
    for (wait in awayFromHome) {
      workManager.cancelAllWork().result.get()

      BackupWorkScheduling.afterWait(context, BackupWorkScheduling.Kind.QUICK, wait)

      val work = waiting(BackupWorkScheduling.QUICK_WORK).single()
      val network = checkNotNull(work.constraints.requiredNetworkRequest) { "$wait: nessun vincolo di rete" }
      assertWithMessage("$wait: rete non a consumo").that(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).isTrue()
      assertWithMessage("$wait: mai i dati mobili").that(network.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).isFalse()
      assertWithMessage("$wait: un Wi-Fi o un cavo").that(network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).isTrue()
      // Come tutti i lavori (RE-11): una rete locale senza internet la soddisfa.
      assertWithMessage("$wait: senza INTERNET").that(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).isFalse()
      assertWithMessage("$wait: senza VALIDATED").that(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).isFalse()
      // Su un Wi-Fi che non e' casa le regole dicono di nuovo no: la prova non si ripete a ciclo continuo.
      assertWithMessage("$wait: un ritardo").that(work.initialDelayMillis).isAtLeast(10 * 60_000L)
    }
  }

  @Test
  fun gliAltriLavoriEGliAltriMotiviNonAspettanoUnaReteNientePolling() {
    // Il NAS irraggiungibile a casa, il congelamento, le altre attese: nessun lavoro dopo (e' la regola di sempre).
    for (wait in listOf(WaitReason.NasUnreachable, WaitReason.ProfileInactive, WaitReason.Frozen(FreezeReason.VANISHED))) {
      BackupWorkScheduling.afterWait(context, BackupWorkScheduling.Kind.QUICK, wait)
      assertWithMessage("$wait").that(waiting(BackupWorkScheduling.QUICK_WORK)).isEmpty()
    }
    // Il lavoro delle foto, dei video e delle verifiche aspetta il suo giro: la rete non gli programma altro.
    for (kind in listOf(BackupWorkScheduling.Kind.PHOTOS, BackupWorkScheduling.Kind.VIDEOS, BackupWorkScheduling.Kind.SAFETY)) {
      BackupWorkScheduling.afterWait(context, kind, WaitReason.NetworkNotAllowed(NetworkClass.MOBILE))
      assertWithMessage("$kind").that(waiting(kind.workName)).isEmpty()
    }
  }

  private val incomingNetworkWaits = listOf(
    WaitReason.NetworkNotAllowed(NetworkClass.MOBILE),
    WaitReason.Offline,
    WaitReason.NetworkNotTrusted,
    WaitReason.Roaming,
    WaitReason.NasUnreachable,
  )

  /**
   * IN-5 della verifica funzionale 5B: "Salva sul NAS" e' un gesto dell'utente. Un file ricevuto mentre si e' fuori casa, o col NAS spento, aspettava
   * per sempre (nessun evento lo faceva ripartire se non la prossima lettura del telefono o il periodico di 6 ore). Come "Proteggi subito" (BK-2):
   * il lavoro si rimette in coda su una rete non a consumo.
   *
   * Campo, gruppo A (D5, O-B19): **senza ritardo**. Prima la ripresa aspettava un quarto d'ora fisso anche col Wi-Fi tornato dopo due minuti (sul campo
   * il file e' salito 13 minuti dopo il ritorno della rete): adesso WorkManager la avvia appena c'e' un Wi-Fi, e la ripresa aspetta la sessione (D-12).
   */
  @Test
  fun in5_d5_unFileRicevutoFuoriCasaOColNasSpentoRiparteAppenaTornaIlWifi() {
    for (wait in incomingNetworkWaits) {
      workManager.cancelAllWork().result.get()

      BackupWorkScheduling.afterWait(context, BackupWorkScheduling.Kind.INCOMING, wait)

      val work = waiting(BackupWorkScheduling.INCOMING_RETRY_WORK).single()
      val network = checkNotNull(work.constraints.requiredNetworkRequest) { "$wait: nessun vincolo di rete" }
      assertWithMessage("$wait: rete non a consumo").that(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).isTrue()
      assertWithMessage("$wait: mai i dati mobili").that(network.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).isFalse()
      assertWithMessage("$wait: nessun ritardo").that(work.initialDelayMillis).isEqualTo(0L)
    }
  }

  /**
   * D5: la ripresa che trova di nuovo la rete o il NAS che non va (a casa col NAS spento, un Wi-Fi che non e' casa) riprova dopo un quarto d'ora: niente
   * ciclo continuo. E un file salvato mentre una ripresa aspetta parte subito con il suo lavoro, non resta dietro.
   */
  @Test
  fun d5_laRipresaCheNonTrovaIlNasRiprovaDopoUnQuartoDOraEIFileNuoviNonAspettano() {
    for (wait in incomingNetworkWaits) {
      workManager.cancelAllWork().result.get()
      BackupWorkScheduling.incomingAfterWait(context, wait, resumed = true)
      val work = waiting(BackupWorkScheduling.INCOMING_RETRY_WORK).single()
      assertWithMessage("$wait: un ritardo").that(work.initialDelayMillis).isAtLeast(10 * 60_000L)
    }

    // Un "Salva sul NAS" nuovo mentre la ripresa aspetta il suo quarto d'ora: il suo lavoro c'e', subito.
    BackupWorkScheduling.incoming(context)
    val fresh = workManager.getWorkInfosForUniqueWork(BackupWorkScheduling.INCOMING_WORK).get().single()
    assertThat(fresh.initialDelayMillis).isEqualTo(0L)
    assertThat(waiting(BackupWorkScheduling.INCOMING_RETRY_WORK)).hasSize(1)
  }

  @Test
  fun in5_ilCongelamentoEGliAltriMotiviNonProgrammanoNienteNiente_polling() {
    for (wait in listOf(WaitReason.ProfileInactive, WaitReason.Frozen(FreezeReason.VANISHED))) {
      BackupWorkScheduling.afterWait(context, BackupWorkScheduling.Kind.INCOMING, wait)
      assertWithMessage("$wait").that(waiting(BackupWorkScheduling.INCOMING_WORK)).isEmpty()
    }
  }

  /**
   * D-6 delle prove sul campo: il lavoro dell'utente che non ritrova la rete passa a una ripresa che parte **appena c'e' un Wi-Fi** (nessun
   * ritardo, nessun polling), con il trigger del tocco ("Inizia ora" resta il primo backup). Mai sui dati mobili.
   */
  @Test
  fun d6_ilLavoroDellUtenteSenzaReteRiparteColPrimoWifi() {
    BackupWorkScheduling.userResume(context, BackupTrigger.FIRST_BACKUP)

    val work = waiting(BackupWorkScheduling.USER_RESUME_WORK).single()
    val network = checkNotNull(work.constraints.requiredNetworkRequest) { "nessun vincolo di rete" }
    assertThat(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).isTrue()
    assertThat(network.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).isFalse()
    assertThat(network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).isTrue()
    assertThat(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).isFalse()
    assertThat(work.initialDelayMillis).isEqualTo(0L)
    val request = workManager.getWorkInfosForUniqueWork(BackupWorkScheduling.USER_RESUME_WORK).get().single()
    assertThat(request.tags).contains(BackupWorkScheduling.TAG)
    // Una ripresa gia' in attesa basta: un secondo arrivo non ne aggiunge un'altra.
    BackupWorkScheduling.userResume(context, BackupTrigger.USER_NOW)
    assertThat(waiting(BackupWorkScheduling.USER_RESUME_WORK)).hasSize(1)
  }

  /** D-6: la ripresa che trova un Wi-Fi che non e' casa (o il NAS spento) riprova su un Wi-Fi fra un quarto d'ora; il caldo non programma niente. */
  @Test
  fun d6_laRipresaFuoriCasaRiprovaSulWifiIlRestoNo() {
    for (wait in listOf(WaitReason.NetworkNotTrusted, WaitReason.NasUnreachable, WaitReason.Offline, WaitReason.ConfirmationRequired(setOf(ConfirmationKind.MOBILE_DATA)))) {
      workManager.cancelAllWork().result.get()
      BackupWorkScheduling.afterUserWait(context, BackupTrigger.USER_NOW, wait)
      val work = waiting(BackupWorkScheduling.USER_RESUME_WORK).single()
      assertWithMessage("$wait: un ritardo").that(work.initialDelayMillis).isAtLeast(10 * 60_000L)
      assertWithMessage("$wait: mai i dati mobili").that(checkNotNull(work.constraints.requiredNetworkRequest).hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).isFalse()
    }
    workManager.cancelAllWork().result.get()
    BackupWorkScheduling.afterUserWait(context, BackupTrigger.USER_NOW, WaitReason.Thermal(ThermalStatus.CRITICAL))
    assertThat(waiting(BackupWorkScheduling.USER_RESUME_WORK)).isEmpty()
  }

  @Test
  fun lAttesaDellaCaricaResta_UnVincoloEsatto() {
    BackupWorkScheduling.afterWait(context, BackupWorkScheduling.Kind.QUICK, WaitReason.NeedsPower)
    val work = waiting(BackupWorkScheduling.QUICK_WORK).single()
    assertThat(work.constraints.requiresCharging()).isTrue()
    // FAB-3: mai senza ritardo (prima 0).
    assertThat(work.initialDelayMillis).isAtLeast(RuleWaits.MIN_RETRY.inWholeMilliseconds)
  }

  /** FAB-3: gli altri lavori del backup (foto, video, verifiche) col risparmio energetico acceso: il ritardo cresce fino a un'ora, con il tentativo negli input. */
  @Test
  fun fab3_gliAltriLavoriDelBackupColRisparmioEnergeticoRiprovanoSemprePiuDiRado() {
    for (kind in listOf(BackupWorkScheduling.Kind.PHOTOS, BackupWorkScheduling.Kind.VIDEOS, BackupWorkScheduling.Kind.SAFETY)) {
      val delays = ArrayList<Long>()
      var attempt = 0
      repeat(4) {
        workManager.cancelAllWork().result.get()
        BackupWorkScheduling.afterWait(context, kind, WaitReason.PowerSaver, attempt)
        val work = waiting(kind.workName).single()
        assertWithMessage("$kind").that(work.constraints.requiresBatteryNotLow()).isFalse()
        delays += work.initialDelayMillis
        attempt = RuleWaits.followUpOf(WaitReason.PowerSaver, LocalDateTime.now(), attempt)!!.attempt
      }
      assertWithMessage("$kind").that(delays).containsExactly(15 * 60_000L, 30 * 60_000L, 60 * 60_000L, 60 * 60_000L).inOrder()
    }
  }
}
