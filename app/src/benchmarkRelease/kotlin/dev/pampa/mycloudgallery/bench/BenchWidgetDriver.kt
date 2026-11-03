package dev.pampa.mycloudgallery.bench

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.feature.widget.glance.GalleryWidgetReceiver
import dev.pampa.mycloudgallery.feature.widget.host.WidgetHost
import dev.pampa.mycloudgallery.feature.widget.host.WidgetServices
import dev.pampa.mycloudgallery.feature.widget.refresh.GlanceWidgetRefresher
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Il pilota del widget per il benchmark (Fase 10, P9; `docs/fase10-progetto.md` §5.4, scenario `widget`). Esiste **solo** in `src/benchmarkRelease`. Il collaudo lo comanda con
 * un broadcast di shell a [BenchWidgetReceiver] (esportato, protetto dal solo permesso `DUMP` come il `ProfileInstallReceiver`: lo puo' mandare solo `adb`) e legge lo
 * stato [STATUS] accanto al marcatore del dataset. **Niente gira all'avvio**: tutto parte dal primo comando.
 *
 * | Comando (azione `...bench.WIDGET_<NOME>`) | Cosa fa |
 * |---|---|
 * | `PREPARE` | aspetta le porte vere del widget (`WidgetHost`, legate da `WidgetGraph` di P13), trova il widget grande legato dal collaudo, aspetta il primo disegno |
 * | `UPDATE` | un giro di aggiornamento (`GlanceWidgetRefresher.shelfChanged`): aspetta che il registro avanzi, poi qualche istante perche' la consegna sia nella traccia |
 * | `BLANK` | un "Nascondi" **di prova** di cio' che il widget mostra (`hiddenChanged`, **non nasconde niente nella Libreria**): lo svuotamento e il giro che segue |
 * | `LOAD_START` / `LOAD_STOP` | i giri di continuo (uno alla volta, con una pausa): "l'aggiornamento del widget in corso" sotto cui lo scorrimento non deve peggiorare |
 * | `CLEANUP` | ferma i giri |
 *
 * **Il widget lo lega il collaudo**, non l'app: un'app non puo' legare un widget senza il permesso `BIND_APPWIDGET` (solo di sistema e della shell), e `cmd appwidget` non ha
 * comandi su questa piattaforma. Il collaudo (`WidgetDriver.bindWidget`, nel processo di prova) adotta l'identita' della shell per quel permesso, alloca l'id con un
 * `AppWidgetHost` suo e lo lega al nostro ricevitore con la misura grande (250 x 250 dp); qui [ensureWidget] lo ritrova per il suo ricevitore. Nessun launcher: il disegno
 * arriva comunque ad `AppWidgetManager` (il costo del giro e' lo stesso; il lavoro del launcher che lo mostra e' un altro processo). **Le porte sono quelle vere** di
 * `WidgetGraph` (P13) sulla Libreria del dataset di misura: nessun seme di prova.
 *
 * Mai dati nello stato: solo numeri.
 */
internal object BenchWidgetDriver {

  const val STATUS: String = "bench-widget.json"
  const val ACTION_PREFIX: String = "dev.pampa.mycloudgallery.bench.WIDGET_"

  private const val TAG = "BenchWidget"
  private const val PORTS_TIMEOUT_MS = 120_000L
  private const val PORTS_STEP_MS = 1_500L
  private const val DRAWN_TIMEOUT_MS = 30_000L
  private const val ROUND_TIMEOUT_MS = 20_000L
  private const val POLL_MS = 5L
  private const val SETTLE_MS = 350L
  private const val LOAD_PAUSE_MS = 300L

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @Volatile
  private var refresher: GlanceWidgetRefresher? = null

  @Volatile
  private var loadJob: Job? = null

  private var rounds = 0
  private var blanks = 0
  private var lastRoundMs = 0L

  /** Esegue [command] in background e chiama [done] a lavoro finito (il ricevitore chiude il suo `goAsync`). */
  fun run(context: Context, command: String, done: () -> Unit) {
    val app = context.applicationContext
    appContext = app
    scope.launch {
      try {
        val status = try {
          execute(app, command)
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: Throwable) {
          JSONObject().put("stato", "errore").put("errore", e.javaClass.simpleName)
        }
        write(app, status.put("comando", command))
      } finally {
        done()
      }
    }
  }

  private suspend fun execute(context: Context, command: String): JSONObject = when (command) {
    "PREPARE" -> prepare(context)
    "UPDATE" -> update()
    "BLANK" -> blank()
    "LOAD_START" -> loadStart()
    "LOAD_STOP" -> loadStop()
    "CLEANUP" -> cleanup()
    else -> JSONObject().put("stato", "errore").put("errore", "comando sconosciuto")
  }

  private suspend fun prepare(context: Context): JSONObject {
    // Le porte vere: a processo nuovo il fornitore monta istantanea, maschera e scaffale e le da' appena sono pronte (E-W4); qui si aspetta fino a che ci sono.
    val seed = withTimeoutOrNull(PORTS_TIMEOUT_MS) {
      var ports = WidgetHost.services(PORTS_STEP_MS)
      while (ports == null) {
        delay(PORTS_STEP_MS)
        ports = WidgetHost.services(PORTS_STEP_MS)
      }
      ports
    } ?: return error("le porte del widget non sono pronte (WidgetGraph di P13: Libreria senza istantanea?)")
    val refresh = refresher ?: GlanceWidgetRefresher(context).also { refresher = it }
    val slot = ensureWidget(context) ?: return error("il widget non e' legato (lo lega il collaudo con WidgetDriver.bindWidget)")
    // Il primo disegno: l'aggiornamento che parte dal legame, o uno chiesto qui se non e' partito (le porte non c'erano ancora).
    val drawn = withTimeoutOrNull(DRAWN_TIMEOUT_MS) {
      var asked = 0L
      while (seed.ledger.epochOf(slot) <= 0L) {
        if (SystemClock.elapsedRealtime() - asked > 3_000L) {
          refresh.shelfChanged()
          asked = SystemClock.elapsedRealtime()
        }
        delay(50)
      }
      true
    } == true
    delay(SETTLE_MS)
    return JSONObject().put("stato", if (drawn) "pronto" else "errore").put("widgetLegato", 1)
      .also { if (!drawn) it.put("errore", "il widget non ha disegnato") }
  }

  private suspend fun update(): JSONObject {
    val (seed, refresh, slot) = ready() ?: return error("non pronto: manca PREPARE")
    val round = awaitProgress(seed, slot, minSteps = 1) { refresh.shelfChanged() }
    delay(SETTLE_MS)
    rounds++
    lastRoundMs = round
    return JSONObject().put("stato", "pronto").put("giri", rounds).put("ultimoGiroMs", round).put("giroFinito", if (round >= 0) 1 else 0)
  }

  private suspend fun blank(): JSONObject {
    val (seed, refresh, slot) = ready() ?: return error("non pronto: manca PREPARE")
    val shown = seed.ledger.cell(slot, 0)?.media ?: return error("il widget non mostra nessuna foto")
    // Lo svuotamento scrive il registro una volta, il giro che segue un'altra: si aspettano tutte e due.
    val round = awaitProgress(seed, slot, minSteps = 2) { refresh.hiddenChanged(setOf(shown)) }
    delay(SETTLE_MS)
    blanks++
    return JSONObject().put("stato", "pronto").put("svuotamenti", blanks).put("ultimoSvuotamentoGiroMs", round).put("giroFinito", if (round >= 0) 1 else 0)
  }

  private suspend fun loadStart(): JSONObject {
    val (seed, refresh, slot) = ready() ?: return error("non pronto: manca PREPARE")
    loadJob?.cancelAndJoin()
    val before = seed.ledger.epochOf(slot)
    loadJob = scope.launch {
      while (isActive) {
        // Un giro alla volta: il prossimo parte dopo il precedente e una pausa, mai una coda che cresce.
        awaitProgress(seed, slot, minSteps = 1) { refresh.shelfChanged() }
        delay(LOAD_PAUSE_MS)
      }
    }
    // Il primo giro deve essere fatto, perche' "in corso" voglia dire che sta lavorando davvero.
    val started = withTimeoutOrNull(ROUND_TIMEOUT_MS) {
      while (seed.ledger.epochOf(slot) <= before) delay(20)
      true
    } == true
    return JSONObject().put("stato", "pronto").put("aggiornamentoInCorso", if (started && loadJob?.isActive == true) 1 else 0)
  }

  private suspend fun loadStop(): JSONObject {
    loadJob?.cancelAndJoin()
    loadJob = null
    return JSONObject().put("stato", "pronto").put("aggiornamentoInCorso", 0)
  }

  private suspend fun cleanup(): JSONObject {
    loadStop()
    return JSONObject().put("stato", "pulito")
  }

  /** Il widget grande che il collaudo ha legato al nostro ricevitore; `null` se non ce n'e' nessuno. */
  private fun ensureWidget(context: Context): WidgetSlot? {
    val manager = AppWidgetManager.getInstance(context)
    val id = manager.getAppWidgetIds(ComponentName(context, GalleryWidgetReceiver::class.java)).firstOrNull { it > 0 } ?: return null
    return WidgetSlot(id)
  }

  private data class Ready(val seed: WidgetServices, val refresher: GlanceWidgetRefresher, val slot: WidgetSlot)

  /** Il contesto dell'app, quello dell'ultimo comando. */
  @Volatile
  private var appContext: Context? = null

  private suspend fun ready(): Ready? {
    val seed = WidgetHost.services(0) ?: return null
    val refresh = refresher ?: return null
    val context = appContext ?: return null
    val id = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, GalleryWidgetReceiver::class.java)).firstOrNull { it > 0 } ?: return null
    return Ready(seed, refresh, WidgetSlot(id))
  }

  /**
   * Lancia [trigger] e aspetta che il registro del widget avanzi almeno di [minSteps] epoche (ogni disegno e ogni svuotamento ne scrivono una). Ritorna i millisecondi
   * visti da qui (informativi: il numero vero e' la sezione di traccia), o -1 se non e' finito entro [ROUND_TIMEOUT_MS].
   */
  private suspend fun awaitProgress(seed: WidgetServices, slot: WidgetSlot, minSteps: Int, trigger: () -> Unit): Long {
    val before = seed.ledger.epochOf(slot)
    val start = SystemClock.elapsedRealtime()
    trigger()
    val done = withTimeoutOrNull(ROUND_TIMEOUT_MS) {
      while (seed.ledger.epochOf(slot) < before + minSteps) delay(POLL_MS)
      true
    } == true
    return if (done) SystemClock.elapsedRealtime() - start else -1L
  }

  private fun error(why: String): JSONObject = JSONObject().put("stato", "errore").put("errore", why)

  /** Scrive lo stato in modo atomico accanto al marcatore del dataset: il collaudo non deve mai leggerne meta'. */
  private fun write(context: Context, json: JSONObject) {
    val dir = try {
      context.getExternalFilesDir(null)
    } catch (e: Exception) {
      null
    } ?: return
    try {
      val file = File(dir, STATUS)
      val tmp = File(dir, "$STATUS.tmp")
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

/** Il ricevitore dei comandi del collaudo (vedi [BenchWidgetDriver]): esportato, protetto da `DUMP` nel manifest della variante di misura. */
class BenchWidgetReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val command = intent.action?.takeIf { it.startsWith(BenchWidgetDriver.ACTION_PREFIX) }?.removePrefix(BenchWidgetDriver.ACTION_PREFIX) ?: return
    val pending = goAsync()
    BenchWidgetDriver.run(context, command) { pending.finish() }
  }
}
