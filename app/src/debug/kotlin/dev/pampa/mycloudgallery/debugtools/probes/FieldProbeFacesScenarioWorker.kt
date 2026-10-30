package dev.pampa.mycloudgallery.debugtools.probes

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.StepState
import dev.pampa.mycloudgallery.core.rules.run.StageOutcome
import dev.pampa.mycloudgallery.core.security.faces.FacesKeyAliases
import dev.pampa.mycloudgallery.core.faces.contract.EraseStep
import dev.pampa.mycloudgallery.faces.FacesGraph
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.graph.AppGraph
import dev.pampa.mycloudgallery.work.FacesStages
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** SOLO debug: cio' che lo scenario prende dal grafo (Hilt non inietta un `CoroutineWorker` senza `@HiltWorker`: si chiede all'applicazione, come `BackupEntryPoint`). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface FieldProbeFacesEntryPoint {
  fun gallery(): GalleryGraph

  fun graph(): AppGraph

  fun faces(): FacesGraph
}

/**
 * SOLO debug: il lavoro che esegue uno scenario dei volti ([FieldProbeFacesScenario]) e ne scrive l'esito nel file di stato. Ripartibile: se il processo muore (il
 * banco lo fa apposta con `kill -9`), WorkManager lo rilancia e lo scenario riprende da dove la strada vera dei volti si e' fermata (le tappe e il diario sono
 * idempotenti), senza rifare un media.
 */
class FieldProbeFacesScenarioWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    val scenario = FieldProbeFacesScenario.of(inputData.getString(FieldProbeFacesScenarios.KEY_SCENARIO)) ?: return Result.success()
    val entry = EntryPointAccessors.fromApplication(applicationContext, FieldProbeFacesEntryPoint::class.java)
    val run = FieldProbeFacesScenarioRun(entry.gallery(), entry.graph(), entry.faces(), applicationContext.filesDir, applicationContext.noBackupFilesDir)
    try {
      run.run(scenario)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Mai il messaggio (puo' avere percorsi): il tipo dell'errore e basta. Lo stato dice che non e' arrivato in fondo.
      GalleryLog.w(TAG) { "scenario dei volti non riuscito: ${e.javaClass.simpleName}" }
    }
    return Result.success()
  }

  private companion object {
    const val TAG = "FieldProbeFaces"
  }
}

/**
 * Lo scenario, senza WorkManager (si prova sulla JVM). Fa solo cio' che farebbe l'app con un tocco, per la strada dei lavori: il calcolo con le tappe
 * ([FacesStages]), il raggruppamento notturno ([FacesGraph.nightly]), la ripresa della cancellazione ([FacesGraph.eraseRound]). Nessun consenso e nessuna conferma
 * di cancellazione: li costruiscono solo la pagina di attivazione e il foglio, e le regole di architettura lo vietano a ogni altro sorgente.
 */
internal class FieldProbeFacesScenarioRun(
  private val gallery: GalleryGraph,
  private val graph: AppGraph,
  private val faces: FacesGraph,
  private val filesDir: File,
  private val noBackup: File,
  /** Quante chiavi dei volti ci sono nel Keystore (`mcg-faces-*`); sulla JVM non c'e' un Keystore: zero. */
  private val keyCount: () -> Int = ::keystoreFaceKeys,
  /** Per le prove: la pausa fra due giri della cancellazione. */
  private val pause: suspend (Duration) -> Unit = { delay(it) },
) {

  suspend fun run(scenario: FieldProbeFacesScenario) {
    when (scenario) {
      FieldProbeFacesScenario.STATUS -> writeStatus(FieldProbeFacesStatus.read(filesDir)["phase"] ?: "idle")
      FieldProbeFacesScenario.RECIPE -> {
        FieldProbeFacesRecipe.force(filesDir)
        writeStatus("recipe")
      }
      FieldProbeFacesScenario.COMPUTE -> compute()
      FieldProbeFacesScenario.ERASE -> erase()
    }
  }

  /** "Analizza ora", fino in fondo, e il raggruppamento. */
  private suspend fun compute() {
    if (!gallery.awaitReady(READY_WAIT)) return writeStatus("not_ready")
    val state = faces.ensureLive()?.switch?.currentState()
    if (state == null || !state.activeHere) return writeStatus("needs_activation")
    writeStatus("computing")
    val stages = FacesStages(graph, faces, noBackup)
    var stage = 0
    while (stage++ < MAX_STAGES) {
      val outcome = gallery.runIfActive {
        gallery.nas?.start()
        stages.compute(user = true)
      } ?: return writeStatus("not_ready")
      when (outcome) {
        is StageOutcome.Finished -> {
          // A calcolo finito, il raggruppamento notturno (nascite sotto soglia, unioni): la sua strada e' quella di sempre, con il suo tempo. Prima un giro dell'indice
          // come quelli veri (dati personali, poi volti): con lo stato fresco questo telefono e' il custode e raggruppa davvero, non in provvisorio.
          syncRound()
          gallery.runIfActive { faces.nightly(FacesStages.FACES_NIGHTLY_BUDGET) }
          return writeStatus("computed")
        }
        is StageOutcome.Yielded -> Unit
        else -> return writeStatus("waiting")
      }
    }
    writeStatus("computing")
  }

  /** La cancellazione cominciata dal foglio, ripresa e seguita fino al diario `DONE`. */
  private suspend fun erase() {
    if (!gallery.awaitReady(READY_WAIT)) return writeStatus("not_ready")
    val live = faces.ensureLive() ?: return writeStatus("not_ready")
    if (live.eraser.status.value == null) return writeStatus("needs_erase")
    writeStatus("erasing")
    var round = 0
    while (round++ < MAX_ROUNDS) {
      val next = gallery.runIfActive { gallery.nas?.start(); faces.eraseRound(ROUND_BUDGET) }
      if (live.eraser.status.value?.step == EraseStep.DONE) return writeStatus("erased")
      // La voce «spenta» esce con i dati personali e le conferme degli altri dispositivi si leggono nel giro dell'indice, come nel lavoro vero.
      syncRound()
      if (live.eraser.status.value?.step == EraseStep.DONE) return writeStatus("erased")
      // Senza un "riprova fra" aspetta altri dispositivi o il cestino di rete: i giri dell'indice la riprendono, non questo scenario.
      if (next == null) return writeStatus("erase_waiting")
      pause(next.coerceAtMost(MAX_PAUSE))
    }
    writeStatus("erasing")
  }

  /** Un giro dell'indice come `NasStages.faces`: i dati personali (la generazione e' fresca), poi i volti (replica, pubblicazione, pulizia, ripresa della cancellazione). */
  private suspend fun syncRound() {
    gallery.runIfActive {
      gallery.nas?.let { nas -> nas.organizer.syncPersonal(ROUND_BUDGET) }
      faces.syncRound(ROUND_BUDGET)
    }
  }

  /** Il file di stato di adesso, con la fase [phase]: stato di Persone, cancellazione, chiavi e database sul disco (solo numeri). */
  suspend fun writeStatus(phase: String) {
    require(phase in FieldProbeFacesStatus.PHASES) { "Fase sconosciuta." }
    val live = faces.live.value
    val state = live?.switch?.currentState()
    val erase = live?.eraser?.status?.value
    val values = linkedMapOf(
      "phase" to phase,
      "state" to when (state) {
        is FacesState.On -> "on"
        is FacesState.Erasing -> "erasing"
        is FacesState.AwaitingNotice -> "awaiting_notice"
        FacesState.NeedsUpdate -> "needs_update"
        FacesState.Off, null -> "off"
      },
      "generation_present" to (if (state?.generationOrNull != null) "1" else "0"),
      "local_done" to (if (erase?.local == StepState.DONE) "1" else "0"),
      "keys" to keyCount().toString(),
      "faces_db" to facesDatabases().toString(),
      "erase_done" to (if (erase?.step == EraseStep.DONE) "1" else "0"),
    )
    FieldProbeFacesStatus.write(filesDir, values)
  }

  /** I database dei volti sul disco (`faces-*.db`, senza i compagni `-wal`, `-shm`, `-journal`), nella loro cartella di `no_backup`. */
  private fun facesDatabases(): Int = File(noBackup, FACES_DIR).listFiles { f -> f.name.startsWith("faces-") && f.name.endsWith(".db") }?.size ?: 0

  companion object {
    private const val FACES_DIR = "faces"
    private const val MAX_STAGES = 200
    private const val MAX_ROUNDS = 30
    private val READY_WAIT = 20.seconds
    private val ROUND_BUDGET = 3.minutes
    private val MAX_PAUSE = 30.seconds

    /** Le chiavi `mcg-faces-*` nel Keystore di Android; zero dove non c'e' (JVM) o non si legge. */
    fun keystoreFaceKeys(): Int = try {
      val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
      store.aliases().toList().count { it.startsWith(FacesKeyAliases.PREFIX) }
    } catch (_: Exception) {
      0
    }
  }
}
