package dev.pampa.mycloudgallery.core.ai

import android.app.Service
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
import dev.pampa.mycloudgallery.core.ai.ocr.DataTransportCleanup
import dev.pampa.mycloudgallery.core.ai.ocr.GatedTextReader
import dev.pampa.mycloudgallery.core.ai.ocr.MlKitBootstrap
import dev.pampa.mycloudgallery.core.ai.ocr.MlKitTextReader
import dev.pampa.mycloudgallery.core.ai.ocr.TextReadingShutdown
import dev.pampa.mycloudgallery.core.ai.testing.AiTestInputs
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 6 (P4, D41, risposta Q4; "Fatto quando": con la lettura spenta nessuna classe di ML Kit si carica e nulla si pianifica).
 *
 * Il motore OCR dietro il cancello, nel wiring che usera' l'app (`GatedTextReader(stato) { MlKitTextReader(MlKitBootstrap(...)) }`),
 * con la lettura SPENTA, in un **processo nuovo** (`OffProbeService`, `:mlkitoff`): il processo dei test strumentati e' uno solo per
 * tutte le classi e, se un test di OCR e' girato prima, ML Kit e' gia' caricato. Nel processo nuovo:
 *  1. nessuna classe di ML Kit, di DataTransport, delle API interne di Google o di Firebase Components e' caricata, ne' prima ne' dopo
 *     una lettura rifiutata (si leggono dall'istantanea dell'heap, non con `findLoadedClass`, che le caricherebbe lui);
 *  2. `MlKitInitProvider` non e' fra i provider installati (e' tolto dal manifest fuso);
 *  3. nessun lavoro di DataTransport e' pianificato in JobScheduler, ne' dopo la pulizia di avvio;
 *  4. il motore dietro il cancello non e' mai stato costruito;
 *  5. controllo positivo: le nostre classi del cancello SONO fra quelle caricate (il rilevatore vede qualcosa).
 */
@RunWith(AndroidJUnit4::class)
class TextReadingOffDeviceTest {

  private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun conLaLetturaSpentaNessunaClasseDiMlKitSiCaricaENullaSiPianifica() {
    val result = runInFreshProcess()
    assertEquals("il processo di prova e' crollato: ${result.opt("crash")}", false, result.has("crash"))
    val report = result.toString(2)
    android.util.Log.i("P4Off", report)

    assertEquals("classi vietate GIA' caricate all'avvio del processo nuovo: $report", 0, (result["baselineWatched"] as JSONArray).length())
    assertTrue("controllo positivo: la classe del cancello deve risultare caricata ($report)", result.getBoolean("gateLoaded"))
    assertEquals("la lettura rifiutata deve dare TEXT_READING_OFF: $report", "TEXT_READING_OFF", result.getString("refusal"))
    assertFalse("il motore dietro il cancello e' stato costruito da spento: $report", result.getBoolean("engineCreated"))
    assertEquals("classi vietate caricate dopo una lettura rifiutata: $report", 0, (result["afterWatched"] as JSONArray).length())
    assertFalse("MlKitInitProvider installato: $report", result.getBoolean("initProviderInstalled"))
    assertEquals("lavori di DataTransport pianificati: $report", 0, result.getInt("dataTransportJobs"))
    assertEquals("lavori di DataTransport dopo la pulizia di avvio: $report", 0, result.getInt("dataTransportJobsAfterCleanup"))
    assertFalse("coda di DataTransport presente: $report", result.getBoolean("queueExists"))
    assertTrue("il processo di prova non e' quello dei test", result.getInt("pid") != Process.myPid())
  }

  /** Avvia `OffProbeService` nel suo processo, aspetta il resultato scritto su file (al massimo 90 s) e scollega. */
  private fun runInFreshProcess(): JSONObject {
    val file = File(context.filesDir, OffProbeService.RESULT)
    file.delete()
    val connected = java.util.concurrent.CountDownLatch(1)
    val connection = object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName, service: IBinder) = connected.countDown()

      override fun onServiceDisconnected(name: ComponentName) = Unit
    }
    val bound = context.bindService(Intent(context, OffProbeService::class.java), connection, Context.BIND_AUTO_CREATE)
    assertTrue("bindService", bound)
    try {
      assertTrue("il servizio non si e' collegato", connected.await(30, java.util.concurrent.TimeUnit.SECONDS))
      val deadline = System.nanoTime() + 90_000_000_000L
      while (!file.exists() && System.nanoTime() < deadline) Thread.sleep(200)
      assertTrue("nessun risultato dal processo di prova", file.exists())
      return JSONObject(file.readText())
    } finally {
      context.unbindService(connection)
      file.delete()
    }
  }
}

/** Il servizio che gira nel processo nuovo. Una sola volta per processo: un secondo collegamento riuserebbe un processo non piu' pulito. */
class OffProbeService : Service() {

  private val binder = Binder()

  override fun onBind(intent: Intent?): IBinder {
    Thread({
      val json = try {
        OffProbe.run(applicationContext)
      } catch (t: Throwable) {
        JSONObject().put("crash", t.javaClass.name + ": " + t.message)
      }
      val tmp = File(filesDir, RESULT + ".tmp")
      tmp.writeText(json.toString())
      tmp.renameTo(File(filesDir, RESULT))
    }, "off-probe").start()
    return binder
  }

  companion object {
    const val RESULT = "p4-off-probe.json"
  }
}

/** La prova nel processo nuovo. */
internal object OffProbe {

  fun run(context: Context): JSONObject {
    val out = JSONObject().put("pid", Process.myPid())

    // 1. Prima di toccare qualunque cosa nostra: cosa c'e' gia'?
    out.put("baselineWatched", JSONArray(LoadedClasses.watched(LoadedClasses.names(context))))

    // 2. Il wiring dell'app (`GatedTextReader.create`): il motore vero si costruisce SOLO dentro la lambda, che da spenti non viene mai chiamata.
    val state: () -> TextReadingState = { TextReadingState.Off }
    val gated = GatedTextReader.create(context, state)
    val image = AiTestInputs.ocr("scontrino")
    val refusal = runBlocking { runCatching { gated.read(image) }.exceptionOrNull() }
    out.put("refusal", (refusal as? AiEngineException)?.error?.name ?: refusal?.javaClass?.name ?: "nessun errore")
    out.put("engineCreated", gated.isEngineCreated)

    // 3. La pulizia di avvio con la lettura spenta (quella che l'app fa prima di tutto).
    val cleanup = DataTransportCleanup(context)
    out.put("dataTransportJobs", cleanup.scheduledJobs())
    TextReadingShutdown.create(context, gated).atStartup(TextReadingState.Off)
    out.put("dataTransportJobsAfterCleanup", cleanup.scheduledJobs())
    out.put("queueExists", cleanup.queueExists())

    // 4. Dopo tutto questo: le classi.
    val after = LoadedClasses.names(context)
    out.put("afterWatched", JSONArray(LoadedClasses.watched(after)))
    out.put("gateLoaded", "dev.pampa.mycloudgallery.core.ai.ocr.GatedTextReader" in after)

    // 5. Il provider che avvierebbe ML Kit da solo non c'e'.
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS)
    out.put("initProviderInstalled", info.providers.orEmpty().any { it.name.endsWith(".MlKitInitProvider") })

    // Informazione per P11 (non asserita): costruire il motore vero DA SPENTI, senza la lambda del cancello, carica classi di ML Kit?
    // Va per ultima: dopo questo il processo non e' piu' pulito.
    MlKitTextReader(MlKitBootstrap(context, state))
    out.put("eagerConstructionWatched", JSONArray(LoadedClasses.watched(LoadedClasses.names(context))))

    // Controllo di sistema: JobScheduler dice la stessa cosa.
    val pending = context.getSystemService(JobScheduler::class.java).allPendingJobs.count { it.service.className.startsWith("com.google.android.datatransport.") }
    out.put("jobSchedulerDataTransport", pending)
    return out
  }
}
