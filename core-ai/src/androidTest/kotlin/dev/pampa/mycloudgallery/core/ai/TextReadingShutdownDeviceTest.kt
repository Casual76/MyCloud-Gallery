package dev.pampa.mycloudgallery.core.ai

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.mycloudgallery.core.ai.contract.AiDiag
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
import dev.pampa.mycloudgallery.core.ai.ocr.DataTransportCleanup
import dev.pampa.mycloudgallery.core.ai.ocr.GatedTextReader
import dev.pampa.mycloudgallery.core.ai.ocr.MlKitBootstrap
import dev.pampa.mycloudgallery.core.ai.ocr.MlKitTextReader
import dev.pampa.mycloudgallery.core.ai.ocr.MlKitTraces
import dev.pampa.mycloudgallery.core.ai.ocr.TextReadingShutdown
import dev.pampa.mycloudgallery.core.ai.runtime.ModelInputs
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.model.OcrSource
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 6 (P4, D41, E-A18, F56, F69; "Fatto quando": allo spegnimento lavori di DataTransport annullati e coda tolta al riavvio).
 *
 * Con ML Kit VERO che gira (la lettura e' "attiva" per la durata del test):
 *  1. si legge una pagina: ML Kit parte, e il test **osserva** cosa lascia sul dispositivo (database, preferenze, lavori di
 *     DataTransport): i nomi e i conteggi vanno nel log `P4Shutdown` e in `docs/decisioni.md`;
 *  2. lo spegnimento (`onTurnedOff`): il motore e' rilasciato e i lavori pianificati di DataTransport sono annullati, **tutti**; la coda
 *     di eventi resta (ML Kit e' partito in questo processo: DataTransport la tiene aperta);
 *  3. il "primo avvio dopo" (`atStartup` con la lettura spenta): la coda e l'identificativo per installazione di ML Kit sono tolti;
 *  4. a fine corsa nessun lavoro di DataTransport, nessuna coda, nessun file di preferenze di ML Kit, e `TEXT_READING_OFF_CLEANUP` e'
 *     arrivato alla Diagnostica.
 *
 * Se ML Kit non lascia da solo una coda o un lavoro (non ha avuto occasione di loggare), il test li **pianta** con i nomi veri della
 * libreria (database `com.google.android.datatransport.events`, lavoro del servizio `JobInfoSchedulerService`): il meccanismo di
 * pulizia si prova lo stesso, e il log dice quali erano naturali e quali piantati.
 */
@RunWith(AndroidJUnit4::class)
class TextReadingShutdownDeviceTest {

  private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val cleanup get() = DataTransportCleanup(context)

  @Before
  fun partenzaPulita() {
    cleanup.cancelScheduledJobs()
    cleanup.removeQueue()
    cleanup.removeInstallId()
  }

  @After
  fun finePulita() {
    cleanup.cancelScheduledJobs()
    cleanup.removeQueue()
    cleanup.removeInstallId()
  }

  @Test
  fun alloSpegnimentoILavoriSiAnnullanoEAlRiavvioLaCodaSiTolgono() = runBlocking<Unit> {
    val state = TextReadingState.OnForAccount(thisDevice = true)
    val diags = mutableListOf<DiagCode>()
    val bootstrap = MlKitBootstrap(context) { state }
    val gated = GatedTextReader({ state }) { MlKitTextReader(bootstrap) { diags += it } } // come create(), ma con il cancello del test

    // 1. ML Kit gira.
    val page = OcrFixtures.page(1024, 768, 28f, maxLines = 6)
    val image = ModelInputs.forOcr(OcrFixtures.jpeg90(page.bitmap), OcrSource.ANALYSIS)!!
    page.bitmap.recycle()
    assertTrue(gated.read(image).lines.isNotEmpty())
    assertTrue(bootstrap.isInitialized)

    // Cosa ha lasciato ML Kit (DataTransport logga in modo asincrono: si aspetta un po').
    val deadline = System.nanoTime() + 20_000_000_000L
    while (System.nanoTime() < deadline && !(cleanup.queueExists() || cleanup.scheduledJobs() > 0)) Thread.sleep(250)
    val natural = Observed(context)
    Log.i(TAG, "lasciato da ML Kit dopo una lettura: $natural")

    // Se ML Kit non ha lasciato niente (nessun evento in coda, nessun lavoro), si piantano gli oggetti con i nomi veri della libreria.
    val plantedQueue = !cleanup.queueExists()
    if (plantedQueue) {
      context.openOrCreateDatabase(MlKitTraces.DATATRANSPORT_QUEUE_DATABASE, Context.MODE_PRIVATE, null).use {
        it.execSQL("CREATE TABLE IF NOT EXISTS events (_id INTEGER PRIMARY KEY, payload BLOB)")
        it.execSQL("INSERT INTO events (payload) VALUES (x'00')")
      }
    }
    val scheduler = context.getSystemService(JobScheduler::class.java)
    val plantedJob = cleanup.scheduledJobs() == 0
    if (plantedJob) {
      val job = JobInfo.Builder(JOB_ID, ComponentName(context.packageName, MlKitTraces.DATATRANSPORT_JOB_SERVICE))
        .setMinimumLatency(60 * 60 * 1000L)
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        .build()
      assertEquals(JobScheduler.RESULT_SUCCESS, scheduler.schedule(job))
    }
    assertTrue("deve esserci un lavoro di DataTransport pianificato prima dello spegnimento", cleanup.scheduledJobs() >= 1)
    assertTrue("deve esserci una coda prima dello spegnimento", cleanup.queueExists())
    // Un lavoro che non e' di DataTransport non si tocca.
    val other = JobInfo.Builder(JOB_ID + 1, ComponentName(context.packageName, OTHER_SERVICE)).setMinimumLatency(60 * 60 * 1000L).build()
    val otherScheduled = runCatching { scheduler.schedule(other) }.getOrNull() == JobScheduler.RESULT_SUCCESS
    Log.i(TAG, "piantati: coda=$plantedQueue lavoro=$plantedJob, lavoro d'altri=$otherScheduled")

    // 2. Spegnimento.
    val shutdown = TextReadingShutdown(cleanup, { gated.release() }, { bootstrap.isInitialized }) { diags += it }
    val atOff = shutdown.onTurnedOff()
    Log.i(TAG, "allo spegnimento: $atOff")
    assertFalse("il motore deve essere rilasciato", gated.isEngineCreated)
    assertEquals("nessun lavoro di DataTransport dopo lo spegnimento", 0, cleanup.scheduledJobs())
    assertEquals(
      "nemmeno secondo JobScheduler",
      0,
      scheduler.allPendingJobs.count { it.service.className.startsWith("com.google.android.datatransport.") },
    )
    if (otherScheduled) assertTrue("il lavoro d'altri non si tocca", scheduler.getPendingJob(JOB_ID + 1) != null)
    assertTrue("la coda resta finche' ML Kit e' vivo in questo processo", cleanup.queueExists())

    // 3. Il primo avvio dopo, con la lettura spenta, prima di qualunque altra cosa.
    val atStart = TextReadingShutdown(cleanup, {}, { false }) { diags += it }.atStartup(TextReadingState.Off)
    Log.i(TAG, "al riavvio: $atStart")
    assertTrue("la coda va tolta al riavvio", atStart.queueRemoved)

    // 4. A fine corsa non resta niente.
    assertEquals(0, cleanup.scheduledJobs())
    assertFalse(cleanup.queueExists())
    assertFalse("l'identificativo per installazione di ML Kit va tolto", File(context.dataDir, "shared_prefs/${MlKitTraces.MLKIT_PREFERENCES}.xml").exists())
    assertTrue("TEXT_READING_OFF_CLEANUP non e' arrivato alla Diagnostica: $diags", AiDiag.TEXT_READING_OFF_CLEANUP in diags)
    scheduler.cancel(JOB_ID + 1)
  }

  @Test
  fun unAvvioPulitoNonTrovaNienteEUnaPuliziaRipetutaEInnocua() {
    val first = TextReadingShutdown(cleanup, {}, { false }).atStartup(TextReadingState.Off)
    val second = TextReadingShutdown(cleanup, {}, { false }).atStartup(TextReadingState.Off)
    assertFalse(first.anythingRemoved)
    assertFalse(second.anythingRemoved)
  }

  @Test
  fun iNomiDelLavoroEDelServizioSonoNelManifestFusoDellApk() {
    val pm = context.packageManager
    val info = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SERVICES or android.content.pm.PackageManager.GET_RECEIVERS)
    val services = info.services.orEmpty().map { it.name }
    val receivers = info.receivers.orEmpty().map { it.name }
    Log.i(TAG, "manifest fuso: servizi ${services.filter { it.startsWith("com.google") }} ricevitori ${receivers.filter { it.startsWith("com.google") }}")
    assertTrue(MlKitTraces.DATATRANSPORT_JOB_SERVICE in services)
    assertTrue(MlKitTraces.DATATRANSPORT_BACKEND_DISCOVERY in services)
    assertTrue(MlKitTraces.DATATRANSPORT_ALARM_RECEIVER in receivers)
    // Tutti non esportati e inerti.
    val exported = info.services.orEmpty().filter { it.name.startsWith("com.google.") && it.exported }.map { it.name }
    assertTrue("servizi di Google esportati: $exported", exported.isEmpty())
  }

  /** Cosa c'e' sul dispositivo dopo una lettura: solo nomi e conteggi. */
  private class Observed(context: Context) {
    val databases: List<String> = context.databaseList().filter { !it.endsWith("-journal") && !it.endsWith("-wal") && !it.endsWith("-shm") }.sorted()
    val preferences: List<String> = File(context.dataDir, "shared_prefs").list().orEmpty().sorted()
    val jobs: List<String> = context.getSystemService(JobScheduler::class.java).allPendingJobs.map { "${it.id}:${it.service.className.substringAfterLast('.')}" }
    val noBackup: List<String> = context.noBackupFilesDir.list().orEmpty().sorted()
    val files: List<String> = context.filesDir.list().orEmpty().filter { !it.startsWith("p4-") }.sorted()

    override fun toString(): String = "databases=$databases shared_prefs=$preferences jobs=$jobs no_backup=$noBackup files=$files"
  }

  private companion object {
    const val TAG = "P4Shutdown"
    const val JOB_ID = 424_242
    const val OTHER_SERVICE = "dev.pampa.mycloudgallery.core.ai.OtherProbeJobService"
  }
}

/** Un JobService qualunque, solo per avere un lavoro "d'altri" da non toccare. Non fa mai niente. */
class OtherProbeJobService : android.app.job.JobService() {
  override fun onStartJob(params: android.app.job.JobParameters?): Boolean = false

  override fun onStopJob(params: android.app.job.JobParameters?): Boolean = false
}
