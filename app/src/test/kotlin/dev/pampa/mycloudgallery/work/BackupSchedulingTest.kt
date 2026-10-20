package dev.pampa.mycloudgallery.work

import android.app.Application
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.rules.ConfirmationKind
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.ThermalStatus
import dev.pampa.mycloudgallery.core.rules.WaitReason
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

  @Before
  fun setUp() {
    WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
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

  /**
   * IN-5 della verifica funzionale 5B: "Salva sul NAS" e' un gesto dell'utente. Un file ricevuto mentre si e' fuori casa, o col NAS spento, aspettava
   * per sempre (nessun evento lo faceva ripartire se non la prossima lettura del telefono o il periodico di 6 ore). Come "Proteggi subito" (BK-2):
   * il lavoro si rimette in coda su una rete non a consumo.
   */
  @Test
  fun in5_unFileRicevutoFuoriCasaOColNasSpentoAspettaUnaReteENonLaProssimaNotte() {
    val waits = listOf(
      WaitReason.NetworkNotAllowed(NetworkClass.MOBILE),
      WaitReason.Offline,
      WaitReason.NetworkNotTrusted,
      WaitReason.Roaming,
      WaitReason.NasUnreachable,
    )
    for (wait in waits) {
      workManager.cancelAllWork().result.get()

      BackupWorkScheduling.afterWait(context, BackupWorkScheduling.Kind.INCOMING, wait)

      val work = waiting(BackupWorkScheduling.INCOMING_WORK).single()
      val network = checkNotNull(work.constraints.requiredNetworkRequest) { "$wait: nessun vincolo di rete" }
      assertWithMessage("$wait: rete non a consumo").that(network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).isTrue()
      assertWithMessage("$wait: mai i dati mobili").that(network.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).isFalse()
      assertWithMessage("$wait: un ritardo").that(work.initialDelayMillis).isAtLeast(10 * 60_000L)
    }
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
  }
}
