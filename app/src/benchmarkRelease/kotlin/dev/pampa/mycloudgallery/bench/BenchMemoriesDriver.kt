package dev.pampa.mycloudgallery.bench

import android.content.Context
import android.os.SystemClock
import dev.pampa.mycloudgallery.core.memories.contract.MemoryClock
import dev.pampa.mycloudgallery.core.memories.contract.RefreshBudgets
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReason
import dev.pampa.mycloudgallery.core.memories.plan.IndexedPlanner
import dev.pampa.mycloudgallery.core.memories.plan.MemoryEngine
import dev.pampa.mycloudgallery.core.memories.plan.people.PeoplePlanner
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryShelfStore
import dev.pampa.mycloudgallery.memories.MemoriesGraph
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Il pilota della generazione dei ricordi per il benchmark (Fase 9, P12; `docs/fase9-progetto.md` §5.4: "generazione su 100 mila" e "con la generazione in
 * corso"). Esiste **solo** in `src/benchmarkRelease` e si attiva solo con un file accanto al marcatore del dataset (l'app guarda se il file esiste):
 *
 *  - [GENERATE_MARKER]: a ogni avvio, appena i ricordi sono montati e la Libreria ha la sua istantanea, **un giro con il budget notturno** (il passo pieno,
 *    sezione di traccia `MCG:memories.generate`) e subito dopo **uno leggero** (`MCG:memories.generate.light`), poi scrive lo stato [STATUS] con i due tempi;
 *  - [LOAD_MARKER]: lo stesso giro **di continuo**, su thread a bassa priorita' e con una pausa fra un giro e l'altro, finche' il processo vive: e' la
 *    "generazione in corso" sotto cui le guardie di scorrimento della Libreria non devono peggiorare. Lo stato dice `calcoloInCorso` dopo il primo giro.
 *
 * **Un motore suo**, non quello dell'app: le stesse porte vere ([MemoriesGraph] le tiene in `MemoriesLive`: istantanea visibile, scelte, persone, viaggi,
 * ritagli, scelta delle foto) ma uno scaffale in memoria **vuoto** a ogni giro. Cosi' ogni giro e' una generazione completa da zero (tabella a colonne di
 * 100 mila celle, candidati, scelta delle foto), mai quella di un giorno che ha gia' lo scaffale pieno, e lo scaffale seminato dell'app non si tocca
 * (il motore dell'app apre i ricordi della prova di apertura). Nessuna scrittura dei dati dell'utente: lo store personale della variante e' in memoria.
 *
 * Mai dati nello stato: solo numeri. Niente rete.
 */
internal object BenchMemoriesDriver {

  /** Con questo file accanto al marcatore l'app fa un giro pieno e uno leggero a ogni avvio e scrive [STATUS]. */
  const val GENERATE_MARKER: String = "bench-library.memories"

  /** Con questo file i giri si ripetono di continuo, a bassa priorita' (la generazione "in corso"). */
  const val LOAD_MARKER: String = "bench-library.memories.load"

  const val STATUS: String = "bench-memories.json"

  private const val TAG = "BenchMemories"
  private const val SNAPSHOT_TIMEOUT_MS = 120_000L
  private const val MOUNT_TIMEOUT_MS = 60_000L
  private const val PAUSE_BETWEEN_ROUNDS_MS = 400L

  /** Chiamato dal grafo dei ricordi appena nato (dentro `Application.onCreate`): niente I/O qui, un thread e basta. */
  fun install(context: Context, memories: MemoriesGraph) {
    val thread = Thread({ runIfRequested(context, memories) }, "bench-memories")
    thread.isDaemon = true
    thread.priority = Thread.MIN_PRIORITY
    thread.start()
  }

  private fun runIfRequested(context: Context, memories: MemoriesGraph) {
    val dir = try {
      context.getExternalFilesDir(null)
    } catch (e: Exception) {
      null
    } ?: return
    val load = File(dir, LOAD_MARKER).exists()
    val generate = File(dir, GENERATE_MARKER).exists()
    if (!load && !generate) return
    val status = File(dir, STATUS)
    write(status, JSONObject().put("stato", "in_corso"))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    scope.launch {
      try {
        drive(memories, status, load)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        write(status, JSONObject().put("stato", "errore").put("errore", e.javaClass.simpleName))
      }
    }
  }

  private suspend fun drive(memories: MemoriesGraph, status: File, load: Boolean) {
    val snapshot = withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { memories.visibility.snapshot.first { it != null } }
    if (snapshot == null) {
      write(status, JSONObject().put("stato", "errore").put("errore", "la Libreria non ha l'istantanea"))
      return
    }
    // I ricordi si montano da soli (galleria partita, dopo il primo disegno): qui si aspetta, senza montarli a forza.
    val live = withTimeoutOrNull(MOUNT_TIMEOUT_MS) { memories.live.filterNotNull().first() } ?: memories.ensureLive()
    if (live == null) {
      write(status, JSONObject().put("stato", "errore").put("errore", "i ricordi non sono montati"))
      return
    }
    val clock = object : MemoryClock {
      override fun today(): LocalDate = memories.today()
      override fun nowLocal(): LocalDateTime = LocalDateTime.now()
      override fun monotonicMs(): Long = SystemClock.elapsedRealtime()
    }
    // Il giro si fa su un thread a bassa priorita' solo in "generazione in corso"; altrimenti sul pool di serie, come la notte.
    val lowPriority = if (load) Executors.newFixedThreadPool(2) { r -> Thread(r, "bench-memories-work").apply { priority = Thread.MIN_PRIORITY; isDaemon = true } } else null
    var rounds = 0
    try {
      do {
        // Un motore per giro, con il suo scope: i flussi del motore (scaffale, scelte) partono subito e non devono accumularsi da un giro all'altro.
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = MemoryEngine(
          library = memories.visibility,
          personal = live.personal,
          store = InMemoryShelfStore(),
          clock = clock,
          scope = engineScope,
          planner = IndexedPlanner(people = PeoplePlanner()),
          trips = live.trips,
          people = live.people,
          cutouts = live.cutouts,
          dispatcher = lowPriority?.asCoroutineDispatcher() ?: Dispatchers.Default,
          choice = live.choice,
        )
        val fullStart = SystemClock.elapsedRealtime()
        val full = engine.refresh(RefreshReason.NIGHTLY, RefreshBudgets.NIGHTLY_SECONDS.seconds)
        val fullMs = SystemClock.elapsedRealtime() - fullStart
        val lightStart = SystemClock.elapsedRealtime()
        val light = engine.refresh(RefreshReason.LIGHT, RefreshBudgets.LIGHT_SECONDS.seconds)
        val lightMs = SystemClock.elapsedRealtime() - lightStart
        val onShelf = engine.current()?.memories?.size ?: 0
        engineScope.cancel()
        rounds++
        // Solo numeri: i millisecondi dei due giri, i ricordi nuovi del pieno e se il budget e' bastato.
        write(
          status,
          JSONObject()
            .put("stato", "pronto")
            .put("giri", rounds)
            .put("generazioneMs", fullMs)
            .put("passoLeggeroMs", lightMs)
            .put("ricordiNuovi", full.published)
            .put("incompleto", full.incomplete || light.incomplete)
            .put("ricordiNelloScaffale", onShelf)
            .put("calcoloInCorso", load)
            .also { json -> full.dropped.forEach { (reason, count) -> json.put("esclusi.${reason.code}", count) } },
        )
        if (load) delay(PAUSE_BETWEEN_ROUNDS_MS)
      } while (load)
    } finally {
      lowPriority?.shutdown()
    }
  }

  /** Scrive lo stato in modo atomico: il collaudo non deve mai leggerne meta'. */
  private fun write(file: File, json: JSONObject) {
    try {
      val tmp = File(file.parentFile, file.name + ".tmp")
      tmp.writeText(json.toString())
      if (!tmp.renameTo(file)) {
        file.delete()
        tmp.renameTo(file)
      }
    } catch (e: Exception) {
      android.util.Log.w(TAG, "stato non scritto: ${e.javaClass.simpleName}")
    }
  }
}
