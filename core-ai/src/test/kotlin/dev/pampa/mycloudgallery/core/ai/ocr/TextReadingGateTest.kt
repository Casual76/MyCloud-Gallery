package dev.pampa.mycloudgallery.core.ai.ocr

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiDiag
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.OcrImage
import dev.pampa.mycloudgallery.core.ai.contract.OcrResult
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingState
import dev.pampa.mycloudgallery.core.ai.testing.AiTestInputs
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fase 6 (P4, D41, E-A18, E-A19; "Fatto quando": con la lettura spenta nessuna classe di ML Kit si carica e nulla si pianifica, allo
 * spegnimento lavori di DataTransport annullati e coda tolta al riavvio). Qui il cancello e la pulizia con un sistema finto, sulla JVM;
 * il processo vero (classi caricate, JobScheduler) lo prova `TextReadingOffDeviceTest` / `TextReadingShutdownDeviceTest`.
 */
class TextReadingGateTest {

  private val on = TextReadingState.OnForAccount(thisDevice = true)

  /** Un motore che fa da testimone: se qualcuno lo costruisce da spento, il test lo vede. */
  private class WitnessReader : ReleasableTextReader {
    var reads = 0
    var released = 0

    override suspend fun read(image: OcrImage): OcrResult {
      reads++
      return OcrResult(emptyList(), dense = false)
    }

    override fun release() {
      released++
    }
  }

  private class Gate(var state: TextReadingState) {
    var constructed = 0
    val reader = WitnessReader()
    val gated = GatedTextReader({ state }) {
      constructed++
      reader
    }
  }

  // --- Il cancello --------------------------------------------------------------------------------------------------------------

  @Test
  fun daSpentiIlMotoreNonSiCostruisceNeSiChiama() = runTest {
    val gate = Gate(TextReadingState.Off)
    repeat(3) {
      val thrown = runCatching { gate.gated.read(AiTestInputs.ocr("scontrino")) }.exceptionOrNull()
      assertThat((thrown as AiEngineException).error).isEqualTo(AiEngineError.TEXT_READING_OFF)
    }
    assertThat(gate.constructed).isEqualTo(0)
    assertThat(gate.reader.reads).isEqualTo(0)
    assertThat(gate.gated.isEngineCreated).isFalse()
    gate.gated.release() // rilasciare cio' che non c'e': niente, e il motore resta non costruito
    assertThat(gate.constructed).isEqualTo(0)
  }

  @Test
  fun quandoQuestoDispositivoESclusoEComeSpento() = runTest {
    val gate = Gate(TextReadingState.OnForAccount(thisDevice = false))
    assertThat(runCatching { gate.gated.read(AiTestInputs.ocr("x")) }.exceptionOrNull()).isInstanceOf(AiEngineException::class.java)
    assertThat(gate.constructed).isEqualTo(0)
  }

  @Test
  fun unaLetturaCheHaPassatoIlPrimoControlloMaTrovaLoSpegnimentoAlLucchettoNonCostruisceIlMotore() = runTest {
    // R7: il cancello si guarda due volte, la seconda sotto il lucchetto del rilascio. Qui lo stato e' "attivo" alla prima lettura e
    // "spento" alla seconda (lo spegnimento e' arrivato nel mezzo): il motore non si costruisce.
    var calls = 0
    var constructed = 0
    val gated = GatedTextReader({ if (++calls == 1) on else TextReadingState.Disabling }) {
      constructed++
      WitnessReader()
    }
    val thrown = runCatching { gated.read(AiTestInputs.ocr("a")) }.exceptionOrNull()
    assertThat((thrown as AiEngineException).error).isEqualTo(AiEngineError.TEXT_READING_OFF)
    assertThat(constructed).isEqualTo(0)
    assertThat(gated.isEngineCreated).isFalse()
  }

  @Test
  fun unaLetturaInCorsoDopoIlRilascioNonRiapreIlMotoreVecchio_ELaProssimaNeCostruisceUnoNuovo() = runTest {
    val readers = mutableListOf<WitnessReader>()
    var state: TextReadingState = on
    val gated = GatedTextReader({ state }) { WitnessReader().also { readers += it } }
    gated.read(AiTestInputs.ocr("a"))
    gated.release()
    assertThat(readers.single().released).isEqualTo(1)
    state = TextReadingState.Disabling
    assertThat(runCatching { gated.read(AiTestInputs.ocr("b")) }.exceptionOrNull()).isInstanceOf(AiEngineException::class.java)
    assertThat(readers).hasSize(1)
    state = on
    gated.read(AiTestInputs.ocr("c"))
    assertThat(readers).hasSize(2)
  }

  @Test
  fun lostatoDiSpegnimentoInCorsoEComeSpento() = runTest {
    val gate = Gate(TextReadingState.Disabling)
    assertThat(runCatching { gate.gated.read(AiTestInputs.ocr("x")) }.exceptionOrNull()).isInstanceOf(AiEngineException::class.java)
    assertThat(gate.constructed).isEqualTo(0)
  }

  @Test
  fun daAttiviIlMotoreSiCostruisceUnaVoltaSola() = runTest {
    val gate = Gate(on)
    gate.gated.read(AiTestInputs.ocr("a"))
    gate.gated.read(AiTestInputs.ocr("b"))
    assertThat(gate.constructed).isEqualTo(1)
    assertThat(gate.reader.reads).isEqualTo(2)
  }

  @Test
  fun lospegnimentoVaSubitoEIlMotoreSiRilascia() = runTest {
    val gate = Gate(on)
    gate.gated.read(AiTestInputs.ocr("a"))
    gate.state = TextReadingState.Disabling
    assertThat(runCatching { gate.gated.read(AiTestInputs.ocr("b")) }.exceptionOrNull()).isInstanceOf(AiEngineException::class.java)
    gate.gated.release()
    assertThat(gate.reader.released).isEqualTo(1)
    assertThat(gate.gated.isEngineCreated).isFalse()
    gate.gated.release()
    assertThat(gate.reader.released).isEqualTo(1) // idempotente
    // Riacceso: un motore nuovo.
    gate.state = on
    gate.gated.read(AiTestInputs.ocr("c"))
    assertThat(gate.constructed).isEqualTo(2)
  }

  // --- La pulizia ---------------------------------------------------------------------------------------------------------------

  private class FakeSystem : TraceSystem {
    val jobs = mutableListOf<Pair<Int, String>>()
    var queue = false
    var prefs = false
    var failCancel = false
    var failList = false
    val cancelled = mutableListOf<Int>()
    val events = mutableListOf<String>()

    override fun pendingJobs(): List<Pair<Int, String>> {
      if (failList) throw SecurityException("no")
      return jobs.toList()
    }

    override fun cancelJob(id: Int) {
      if (failCancel) throw IllegalStateException("no")
      jobs.removeAll { it.first == id }
      cancelled += id
      events += "cancel:$id"
    }

    override fun databaseExists(name: String): Boolean = name == MlKitTraces.DATATRANSPORT_QUEUE_DATABASE && queue

    override fun deleteDatabase(name: String): Boolean {
      val existed = databaseExists(name)
      if (name == MlKitTraces.DATATRANSPORT_QUEUE_DATABASE) queue = false
      events += "db:$name"
      return existed
    }

    override fun preferencesExist(name: String): Boolean = name == MlKitTraces.MLKIT_PREFERENCES && prefs

    override fun deletePreferences(name: String): Boolean {
      val existed = preferencesExist(name)
      if (name == MlKitTraces.MLKIT_PREFERENCES) prefs = false
      events += "prefs:$name"
      return existed
    }

    /** Il JobService di DataTransport: acceso di serie (come nel manifest). */
    var serviceOn = true
    var failService = false

    override fun serviceEnabled(className: String): Boolean = serviceOn

    override fun setServiceEnabled(className: String, enabled: Boolean) {
      if (failService) throw SecurityException("no")
      serviceOn = enabled
      events += "service:$className:$enabled"
    }
  }

  private val dtJob = MlKitTraces.DATATRANSPORT_JOB_SERVICE

  @Test
  fun iNomiSonoQuelliDelManifestFusoEDellaLibreria() {
    // Il manifest fuso della release elenca esattamente questi tre componenti di DataTransport (gradle/verify-release-manifest.gradle).
    assertThat(MlKitTraces.DATATRANSPORT_JOB_SERVICE).isEqualTo("com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService")
    assertThat(MlKitTraces.DATATRANSPORT_ALARM_RECEIVER)
      .isEqualTo("com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver")
    assertThat(MlKitTraces.DATATRANSPORT_BACKEND_DISCOVERY).isEqualTo("com.google.android.datatransport.runtime.backends.TransportBackendDiscovery")
    assertThat(MlKitTraces.DATATRANSPORT_QUEUE_DATABASE).isEqualTo("com.google.android.datatransport.events")
    assertThat(MlKitTraces.MLKIT_PREFERENCES).isEqualTo("com.google.mlkit.internal")
  }

  @Test
  fun siAnnullanoSoloIlavoriDiDataTransport() {
    val system = FakeSystem()
    system.jobs += 101 to dtJob
    system.jobs += 102 to "androidx.work.impl.background.systemjob.SystemJobService"
    system.jobs += 103 to dtJob
    system.jobs += 104 to "dev.pampa.mycloudgallery.app.work.SomethingJobService"
    val cleanup = DataTransportCleanup(system)
    assertThat(cleanup.scheduledJobs()).isEqualTo(2)
    assertThat(cleanup.cancelScheduledJobs()).isEqualTo(2)
    assertThat(system.cancelled).containsExactly(101, 103)
    assertThat(system.jobs.map { it.first }).containsExactly(102, 104) // WorkManager e gli altri lavori restano
    assertThat(cleanup.cancelScheduledJobs()).isEqualTo(0) // idempotente
  }

  @Test
  fun unGuastoDelSistemaNonFermaLaPulizia() {
    val system = FakeSystem()
    system.jobs += 1 to dtJob
    system.failCancel = true
    val cleanup = DataTransportCleanup(system)
    assertThat(cleanup.cancelScheduledJobs()).isEqualTo(0)
    system.failCancel = false
    system.failList = true
    assertThat(cleanup.cancelScheduledJobs()).isEqualTo(0)
    assertThat(cleanup.scheduledJobs()).isEqualTo(0)
  }

  @Test
  fun alloSpegnimentoSiRilasciaSiAnnullanoILavoriELaCodaSiTogliSeMlKitNonEPartito() {
    val system = FakeSystem()
    system.jobs += 7 to dtJob
    system.queue = true
    system.prefs = true
    val diags = mutableListOf<DiagCode>()
    var released = 0
    val shutdown = TextReadingShutdown(DataTransportCleanup(system), { released++; system.events += "release" }, { false }, { diags += it })
    val report = shutdown.onTurnedOff()
    assertThat(released).isEqualTo(1)
    assertThat(report).isEqualTo(CleanupReport(jobsCancelled = 1, queueRemoved = true, installIdRemoved = true))
    assertThat(system.jobs).isEmpty()
    assertThat(system.queue).isFalse()
    assertThat(diags).containsExactly(AiDiag.TEXT_READING_OFF_CLEANUP)
    // Il motore si rilascia PRIMA di toccare i lavori.
    assertThat(system.events.first()).isEqualTo("release")
  }

  @Test
  fun alloSpegnimentoIlServizioDiDataTransportSiSpegnePrimaDiAnnullareILavori() {
    // R7: un evento scritto da una lettura gia' partita non puo' ripianificare un lavoro, perche' il servizio che lo eseguirebbe e' spento.
    val system = FakeSystem()
    system.jobs += 7 to dtJob
    TextReadingShutdown(DataTransportCleanup(system), {}, { true }).onTurnedOff()
    assertThat(system.serviceOn).isFalse()
    val order = system.events.filter { it.startsWith("service:") || it.startsWith("cancel:") }
    assertThat(order).containsExactly("service:$dtJob:false", "cancel:7").inOrder()
    // Idempotente: spegnerlo di nuovo non lo tocca.
    TextReadingShutdown(DataTransportCleanup(system), {}, { true }).onTurnedOff()
    assertThat(system.events.count { it.startsWith("service:") }).isEqualTo(1)
  }

  @Test
  fun alloStartConLaLetturaAttivaIlServizioSpentoDaUnProcessoPrimaSiRiaccende_ConLetturaSpentaRestaSpento() {
    val system = FakeSystem()
    system.serviceOn = false
    TextReadingShutdown(DataTransportCleanup(system), {}, { false }).atStartup(TextReadingState.Off)
    assertThat(system.serviceOn).isFalse()
    TextReadingShutdown(DataTransportCleanup(system), {}, { false }).atStartup(TextReadingState.AwaitingNotice(1))
    assertThat(system.serviceOn).isFalse() // in attesa dell'avviso non e' attiva: ML Kit non parte
    TextReadingShutdown(DataTransportCleanup(system), {}, { false }).atStartup(on)
    assertThat(system.serviceOn).isTrue()
  }

  @Test
  fun unGuastoDelServizioNonFermaLaPulizia() {
    val system = FakeSystem()
    system.failService = true
    system.jobs += 7 to dtJob
    system.queue = true
    val report = TextReadingShutdown(DataTransportCleanup(system), {}, { false }).onTurnedOff()
    assertThat(report.jobsCancelled).isEqualTo(1)
    assertThat(report.queueRemoved).isTrue()
  }

  @Test
  fun seMlKitGiraNelProcessoLaCodaSiLasciaAlProssimoAvvio() {
    val system = FakeSystem()
    system.jobs += 7 to dtJob
    system.queue = true
    system.prefs = true
    val shutdown = TextReadingShutdown(DataTransportCleanup(system), {}, { true })
    val report = shutdown.onTurnedOff()
    assertThat(report).isEqualTo(CleanupReport(jobsCancelled = 1, queueRemoved = false, installIdRemoved = false))
    assertThat(system.queue).isTrue() // DataTransport la tiene aperta: la toglie l'avvio dopo
    assertThat(system.events).doesNotContain("db:${MlKitTraces.DATATRANSPORT_QUEUE_DATABASE}")
    // Al primo avvio dopo, con la lettura spenta, prima di tutto:
    val restart = TextReadingShutdown(DataTransportCleanup(system), {}, { false })
    val atStart = restart.atStartup(TextReadingState.Off)
    assertThat(atStart.queueRemoved).isTrue()
    assertThat(atStart.installIdRemoved).isTrue()
    assertThat(system.queue).isFalse()
  }

  @Test
  fun alloStartConLaLetturaAttivaNonSiToccaNiente() {
    val system = FakeSystem()
    system.jobs += 7 to dtJob
    system.queue = true
    val diags = mutableListOf<DiagCode>()
    val report = TextReadingShutdown(DataTransportCleanup(system), {}, { false }, { diags += it }).atStartup(on)
    assertThat(report).isEqualTo(CleanupReport.NOTHING)
    assertThat(system.jobs).hasSize(1)
    assertThat(system.queue).isTrue()
    assertThat(system.events).isEmpty()
    assertThat(diags).isEmpty()
  }

  @Test
  fun alloStartConDispositivoEsclusoOSpegnimentoInCorsoSiPulisce() {
    for (state in listOf(TextReadingState.OnForAccount(thisDevice = false), TextReadingState.Disabling, TextReadingState.Off)) {
      val system = FakeSystem()
      system.jobs += 7 to dtJob
      system.queue = true
      val report = TextReadingShutdown(DataTransportCleanup(system), {}, { false }).atStartup(state)
      assertThat(report.jobsCancelled).isEqualTo(1)
      assertThat(report.queueRemoved).isTrue()
    }
  }

  @Test
  fun unAvvioPulitoNonLasciaTracciaNellaDiagnostica() {
    val system = FakeSystem()
    val diags = mutableListOf<DiagCode>()
    repeat(3) { TextReadingShutdown(DataTransportCleanup(system), {}, { false }, { diags += it }).atStartup(TextReadingState.Off) }
    assertThat(diags).isEmpty() // niente da togliere: nessuna voce (altrimenti ogni avvio ne lascerebbe una)
  }
}
