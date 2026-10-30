package dev.pampa.mycloudgallery.debugtools.probes

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import java.io.File

/**
 * Gli scenari che il banco delle persone sul telefono (`tools/chaos/s25.ps1 -Mode faces`, F103) e la ricetta forzata (F109) chiedono all'app di debug.
 * [code] e' il nome per `adb` (`--es scenario <nome>`): `f103-compute`, `f103-erase` e `f103-status` sono quelli che lo script gia' manda, `f109-recipe` e' nuovo.
 */
internal enum class FieldProbeFacesScenario(val code: String) {
  /**
   * "Analizza ora" sull'area di prova, per la strada vera del lavoro (`FacesStages`, regole di `AI_COMPUTE`) e poi il raggruppamento notturno; `phase=computed` a
   * calcolo e raggruppamento finiti. **Non attiva Persone**: l'attivazione e' un consenso dell'utente (D3) e lo da' solo la pagina di attivazione; senza, lo stato
   * dice `phase=needs_activation`.
   */
  COMPUTE("f103-compute"),

  /**
   * Riprende e segue fino in fondo "Disattiva e cancella": `phase=erased` a cancellazione locale e sul NAS finite. **Non la avvia**: la conferma e' del foglio
   * "Disattiva e cancella" (la sua impronta la costruisce solo quel foglio); senza una cancellazione cominciata lo stato dice `phase=needs_erase`.
   */
  ERASE("f103-erase"),

  /** Riscrive soltanto il file di stato (nessun lavoro). */
  STATUS("f103-status"),

  /**
   * F109: dal prossimo avvio del processo il calcolo dei volti usa la **ricetta 2** (`DeveloperTools.facesRecipe`): ogni media si rifa' e il corpo nuovo vince. Scrive
   * solo il segno; senza questo scenario (o tolto il file `files/prove/f109-recipe.txt`) la ricetta e' quella di serie.
   */
  RECIPE("f109-recipe"),
  ;

  companion object {
    /** Lo scenario di nome [code], o null se non esiste (meglio niente che indovinare). */
    fun of(code: String?): FieldProbeFacesScenario? = entries.firstOrNull { it.code == code?.trim()?.lowercase() }
  }
}

/** Il file di stato dello scenario (`files/prove/f8-status.txt`, righe `chiave=valore`): il contratto con `s25.ps1`. Solo numeri e parole fisse, mai un percorso o un nome. */
internal object FieldProbeFacesStatus {
  const val DIR: String = "prove"
  const val FILE: String = "f8-status.txt"

  /** Le chiavi, nell'ordine in cui le legge lo script. */
  val KEYS: List<String> = listOf("phase", "state", "generation_present", "local_done", "keys", "faces_db", "erase_done")

  /** Le fasi che lo scenario scrive: `computed` e `erased` sono quelle che lo script aspetta, le altre dicono perche' non ci si arriva. */
  val PHASES: Set<String> = setOf(
    "idle", "computing", "computed", "needs_activation", "waiting", "needs_erase", "erasing", "erase_waiting", "erased", "not_ready", "recipe",
  )

  fun file(filesDir: File): File = File(File(filesDir, DIR), FILE)

  fun render(values: Map<String, String>): String = KEYS.filter { it in values }.joinToString("\n", postfix = "\n") { "$it=${values.getValue(it)}" }

  fun write(filesDir: File, values: Map<String, String>) {
    file(filesDir).parentFile?.mkdirs()
    StateFiles.writeText(file(filesDir), render(values))
  }

  /** Il file di adesso (vuoto se non c'e' o non si legge). */
  fun read(filesDir: File): Map<String, String> {
    val text = try {
      StateFiles.readText(file(filesDir))
    } catch (_: java.io.IOException) {
      null
    }
    return text.orEmpty().lineSequence().mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
  }
}

/** F109: la ricetta forzata. Il segno e' un numero in `files/prove/f109-recipe.txt`; lo legge `DeveloperTools.facesRecipe` quando si montano le persone. */
internal object FieldProbeFacesRecipe {
  const val FILE: String = "f109-recipe.txt"
  const val FORCED: Int = 2

  fun file(filesDir: File): File = File(File(filesDir, FieldProbeFacesStatus.DIR), FILE)

  /** La ricetta forzata, o null (niente file, o un valore che non e' una ricetta valida: 1..255, e piu' di quella di serie). */
  fun read(filesDir: File): Int? {
    val text = try {
      StateFiles.readText(file(filesDir))
    } catch (_: java.io.IOException) {
      null
    }
    return text?.trim()?.toIntOrNull()?.takeIf { it in 2..255 }
  }

  fun force(filesDir: File) {
    file(filesDir).parentFile?.mkdirs()
    StateFiles.writeText(file(filesDir), "$FORCED\n")
  }
}

/**
 * SOLO build debug (DR-2 della verifica funzionale della Fase 8): il broadcast `adb shell am broadcast -a dev.pampa.mycloudgallery.debug.action.FACES_SCENARIO -p
 * dev.pampa.mycloudgallery.debug --es scenario f103-compute`. Come "Fai ora i lavori periodici" ([FieldProbePeriodicJobs]) non scavalca niente: accoda **un lavoro**
 * ([FieldProbeFacesScenarioWorker]) che fa quello che farebbe un tocco sull'app, passando dagli stessi cancelli (le regole, il cancello D34, la recinzione D33 delle
 * share di prova). Un lavoro unico per scenario: due richieste di fila non ne fanno partire due insieme.
 */
internal object FieldProbeFacesScenarios {

  /** L'azione del broadcast: l'unica che il ricevitore ascolta. */
  const val ACTION: String = "dev.pampa.mycloudgallery.debug.action.FACES_SCENARIO"

  const val EXTRA_SCENARIO: String = "scenario"
  const val KEY_SCENARIO: String = "scenario"
  const val TAG: String = "field-probe-faces"

  fun workName(scenario: FieldProbeFacesScenario): String = "field-probe-faces-${scenario.code}"

  /** Accoda lo scenario: lavoro unico (`KEEP`), senza vincoli (le regole le rivede dentro). Restituisce il nome del lavoro. */
  fun enqueue(context: Context, scenario: FieldProbeFacesScenario): String {
    val request = OneTimeWorkRequestBuilder<FieldProbeFacesScenarioWorker>()
      .setInputData(workDataOf(KEY_SCENARIO to scenario.code))
      .setConstraints(Constraints.NONE)
      .addTag(TAG)
      .build()
    val name = workName(scenario)
    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
    GalleryLog.i(TAG) { "scenario dei volti accodato: ${scenario.code}" }
    return name
  }
}

/**
 * SOLO debug: il ricevitore di [FieldProbeFacesScenarios]. Dichiarato nel manifest di debug **con il permesso `android.permission.DUMP`** (lo possiede la shell di
 * `adb`, nessuna app normale) e **ascolta una sola azione**: ogni altra azione, e ogni scenario che non esiste, non fa niente. Risponde (broadcast ordinato, `am` lo
 * stampa) con il nome del lavoro accodato, o con un errore. Non tocca mai le share: il lavoro che accoda passa dai cancelli di sempre.
 */
class FieldProbeFacesScenarioReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != FieldProbeFacesScenarios.ACTION) return
    val scenario = FieldProbeFacesScenario.of(intent.getStringExtra(FieldProbeFacesScenarios.EXTRA_SCENARIO))
    if (scenario == null) {
      if (isOrderedBroadcast) {
        setResult(RESULT_BAD_SCENARIO, "scenario sconosciuto: usa ${FieldProbeFacesScenario.entries.joinToString(",") { it.code }}", null)
      }
      return
    }
    val name = FieldProbeFacesScenarios.enqueue(context, scenario)
    if (isOrderedBroadcast) setResult(Activity.RESULT_OK, name, null)
  }

  companion object {
    /** Uno scenario che non esiste. */
    const val RESULT_BAD_SCENARIO: Int = 2
  }
}
