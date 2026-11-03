package dev.pampa.mycloudgallery.benchmark

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor
import android.content.res.Resources
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import org.json.JSONObject

/**
 * Le cose di `WidgetBenchmark` (Fase 10, P9; `docs/fase10-progetto.md` §5.4, scenario `widget`): i nomi che l'app deve rispettare, il pilota del widget (comandi di shell
 * al ricevitore della variante di misura), le metriche.
 *
 * **Cosa c'e' nell'app (variante `benchmarkRelease`, `app/src/benchmarkRelease`)**:
 *  - le sezioni di traccia [TRACE_UPDATE] e [TRACE_BLANK] dentro `WidgetEngine` (un giro di aggiornamento; lo svuotamento dopo un "Nascondi");
 *  - il **seme del widget** (`BenchWidgetServices`: le porte di `feature-widget` sopra la Libreria da 100 mila righe; finche' `WidgetGraph` di P13 non e' su `main` il widget
 *    vero non disegna) e il **pilota** (`BenchWidgetDriver`, `BenchWidgetReceiver`): lega un widget grande, chiede un giro, uno svuotamento, i giri di continuo.
 */
object WidgetBenchTarget {

  /** Le sezioni di traccia del motore (`AvvioTrace.WIDGET_UPDATE` e `WIDGET_BLANK` nell'app). */
  const val TRACE_UPDATE: String = "MCG:widget.update"
  const val TRACE_BLANK: String = "MCG:widget.blank"

  /** Il ricevitore dei comandi e il prefisso delle azioni (`BenchWidgetDriver.ACTION_PREFIX`, `BenchWidgetReceiver`). */
  const val RECEIVER: String = "dev.pampa.mycloudgallery.bench.BenchWidgetReceiver"
  const val ACTION_PREFIX: String = "dev.pampa.mycloudgallery.bench.WIDGET_"

  /** Lo stato del pilota accanto al marcatore del dataset (`BenchWidgetDriver.STATUS`): solo numeri. */
  const val STATUS: String = "bench-widget.json"

  /** Un telefono e' sotto i 600 dp di larghezza minima; un tablet no (la soglia di Android per "tablet", come `GalleryPaneRules`). */
  const val TABLET_MIN_DP: Int = 600

  /** Il dispositivo e' un tablet (la soglia assoluta del widget e' 600 ms invece di 400, §5.4). Si legge dalla configurazione di sistema, non dallo schermo emulato. */
  fun isTablet(): Boolean = Resources.getSystem().configuration.smallestScreenWidthDp >= TABLET_MIN_DP
}

/** Lo stato dei comandi di un'iterazione del widget (consegnato alle metriche proprie): il giro e' finito, il widget era legato, i giri di continuo giravano. */
object WidgetBenchState {
  @Volatile var bound: Boolean = false
  @Volatile var allRoundsFinished: Boolean = true
  @Volatile var refreshing: Boolean = false
  @Volatile var lastStatus: JSONObject? = null

  fun reset() {
    bound = false
    allRoundsFinished = true
    refreshing = false
    lastStatus = null
  }
}

/**
 * Il pilota del widget visto dal collaudo: un comando e' un broadcast di shell (`am broadcast`, che aspetta la fine del ricevitore) e il risultato e' lo stato che l'app scrive
 * accanto al marcatore. **Il widget lo lega il collaudo** ([bindWidget]): legare un widget vuole `BIND_APPWIDGET` (di sistema e della shell), che un'app non ha; il processo di
 * prova lo prende adottando l'identita' della shell (`UiAutomation`), alloca l'id con un `AppWidgetHost` suo e lo lega al ricevitore dell'app. Il widget sta nel sistema, ospitato
 * dall'APK di collaudo: sparisce da solo quando AGP lo disinstalla alla fine delle prove, e [cleanup] lo toglie prima.
 */
object WidgetDriver {

  private val pkg: String get() = BenchSession.targetPackage

  /** Il ricevitore del widget nell'app (`GalleryWidgetReceiver`): il nome della classe e' quello del modulo, il pacchetto e' quello della variante di misura. */
  private const val PROVIDER_CLASS = "dev.pampa.mycloudgallery.feature.widget.glance.GalleryWidgetReceiver"

  /** La misura grande (>= 220 x 220 dp, `WidgetSizing`): sette immagini, il caso piu' pesante. */
  private const val LARGE_DP = 250

  private const val HOST_ID = 0x4D43

  private var host: AppWidgetHost? = null
  private var boundId: Int = 0

  /**
   * Lega un widget grande al ricevitore dell'app (una volta per corsa: il widget resta nel sistema mentre le prove uccidono l'app). `null` se e' legato, altrimenti il motivo
   * (la prova si salta). Si adotta il solo permesso `BIND_APPWIDGET` della shell e lo si lascia subito.
   */
  fun bindWidget(): String? {
    if (boundId > 0) return null
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.context
    val automation = instrumentation.getUiAutomation(Configurator.getInstance().uiAutomationFlags)
    val manager = AppWidgetManager.getInstance(context)
    val widgetHost = AppWidgetHost(context, HOST_ID).also { host = it }
    val options = Bundle().apply {
      putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, LARGE_DP)
      putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, LARGE_DP)
      putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, LARGE_DP)
      putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, LARGE_DP)
    }
    automation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
    try {
      val id = widgetHost.allocateAppWidgetId()
      val bound = manager.bindAppWidgetIdIfAllowed(id, ComponentName(pkg, PROVIDER_CLASS), options)
      if (!bound) {
        runCatching { widgetHost.deleteAppWidgetId(id) }
        return "Il sistema non lega il widget (bindAppWidgetIdIfAllowed ha detto no): vedi WidgetDriver.bindWidget."
      }
      boundId = id
    } catch (e: RuntimeException) {
      return "Il widget non si lega: ${e.javaClass.simpleName} (BIND_APPWIDGET non adottato dalla shell?)."
    } finally {
      automation.dropShellPermissionIdentity()
    }
    return null
  }

  /**
   * Manda [command] (`PREPARE`, `UPDATE`, `BLANK`, `LOAD_START`, `LOAD_STOP`, `CLEANUP`) e restituisce lo stato scritto dall'app, o `null` se l'app non lo scrive (la variante
   * non ha il pilota). Lo stato e' tolto prima del comando: un file di prima non vale.
   */
  fun command(command: String): JSONObject? {
    BenchAppFiles.remove(WidgetBenchTarget.STATUS)
    BenchSettings.shellRaw("am broadcast -n $pkg/${WidgetBenchTarget.RECEIVER} -a ${WidgetBenchTarget.ACTION_PREFIX}$command")
    val status = BenchAppFiles.awaitStatus(WidgetBenchTarget.STATUS, COMMAND_TIMEOUT_MS) { it.optString("comando") == command }
    WidgetBenchState.lastStatus = status
    return status
  }

  /** Lega il seme dell'app, trova il widget e aspetta il primo disegno; `null` col motivo scritto se il pilota non c'e' o il widget non c'e' (la prova si salta). */
  fun prepare(): String? {
    bindWidget()?.let { return it }
    val missing = "L'app non scrive ${WidgetBenchTarget.STATUS}: il pilota del widget non c'e' nella variante di misura (BenchWidgetDriver)."
    var status = command("PREPARE") ?: return missing
    if (status.optString("errore").contains("non e' legato")) {
      // Il sistema ha tolto il widget (l'app e' stata reinstallata o i dati cancellati): si lega di nuovo, una volta.
      boundId = 0
      bindWidget()?.let { return it }
      status = command("PREPARE") ?: return missing
    }
    if (status.optString("stato") != "pronto") return "Il widget non e' pronto: ${status.optString("errore").ifEmpty { status.optString("stato") }}."
    WidgetBenchState.bound = status.optInt("widgetLegato") == 1
    return null
  }

  /** Un comando di un giro (`UPDATE`, `BLANK`): vero se l'app ha detto che il giro e' finito (se no, la misura di questa iterazione non vale). */
  fun round(command: String): Boolean {
    val finished = command(command)?.let { it.optString("stato") == "pronto" && it.optInt("giroFinito") == 1 } == true
    if (!finished) WidgetBenchState.allRoundsFinished = false
    return finished
  }

  /** I giri di continuo ("l'aggiornamento del widget in corso"); vero se stavano girando davvero. */
  fun startLoad(): Boolean {
    val running = command("LOAD_START")?.let { it.optString("stato") == "pronto" && it.optInt("aggiornamentoInCorso") == 1 } == true
    WidgetBenchState.refreshing = running
    return running
  }

  fun stopLoad() {
    command("LOAD_STOP")
    WidgetBenchState.refreshing = false
  }

  /** Ferma i giri e toglie il fornitore dell'app, poi il widget dal sistema (la variante torna com'era). Mai un'eccezione: e' il lavoro di fine prova. */
  fun cleanup() {
    runCatching { command("CLEANUP") }
    if (boundId > 0) runCatching { host?.deleteAppWidgetId(boundId) }
    boundId = 0
  }

  private const val COMMAND_TIMEOUT_MS = 150_000L
}

/**
 * Le sezioni di traccia del widget di un'iterazione: i giri di aggiornamento (`MCG:widget.update`) e gli svuotamenti (`MCG:widget.blank`).
 *  - `widgetUpdateColdMs`: il **primo** giro del processo (Glance si compone la prima volta: informativo, con la sua regressione);
 *  - `widgetUpdateMs`: il piu' lungo dei giri **dopo il primo** (processo caldo): la misura del §5.4;
 *  - `widgetBlankMs`: il piu' lungo svuotamento (D5: 100 ms); `widgetUpdates` e `widgetBlanks` quante sezioni c'erano; `tracePacketsLost` i pacchetti persi dal buffer di Perfetto;
 *  - `widgetValid`: 1 se il widget era legato, i giri sono finiti e la traccia ha tutte le sezioni (almeno [MIN_UPDATES] aggiornamenti e [MIN_BLANKS] svuotamenti).
 * Senza nessuna sezione: `widgetSectionMissing` = 1 (l'app non le emette, o il buffer di Perfetto ha perso pacchetti).
 */
@OptIn(ExperimentalMetricApi::class)
class WidgetSectionsMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> {
    val updates = durations(traceSession, WidgetBenchTarget.TRACE_UPDATE)
    val blanks = durations(traceSession, WidgetBenchTarget.TRACE_BLANK)
    val out = ArrayList<Metric.Measurement>()
    out += Metric.Measurement("widgetUpdates", updates.size.toDouble())
    out += Metric.Measurement("widgetBlanks", blanks.size.toDouble())
    out += Metric.Measurement("tracePacketsLost", packetsLost(traceSession))
    if (updates.isEmpty() && blanks.isEmpty()) out += Metric.Measurement("widgetSectionMissing", 1.0)
    if (updates.isNotEmpty()) out += Metric.Measurement("widgetUpdateColdMs", updates.first())
    if (updates.size > 1) out += Metric.Measurement("widgetUpdateMs", updates.drop(1).max())
    if (blanks.isNotEmpty()) out += Metric.Measurement("widgetBlankMs", blanks.max())
    val valid = WidgetBenchState.bound && WidgetBenchState.allRoundsFinished && updates.size >= MIN_UPDATES && blanks.size >= MIN_BLANKS
    out += Metric.Measurement("widgetValid", if (valid) 1.0 else 0.0)
    return out
  }

  private fun durations(session: TraceProcessor.Session, name: String): List<Double> =
    session.query("SELECT dur FROM slice WHERE name = '$name' AND dur > 0 ORDER BY ts").map { it.long("dur") / FrameQueries.NANOS_PER_MILLI }.toList()

  companion object {
    /** Un giro freddo e almeno uno caldo (`WidgetBenchmark` ne fa quattro piu' i due che seguono gli svuotamenti). */
    const val MIN_UPDATES = 2

    const val MIN_BLANKS = 1
  }
}

/** I giri del widget lavoravano mentre si misurava (1) o no (0): la validita' della guardia "scorrimento con un aggiornamento del widget in corso". */
@OptIn(ExperimentalMetricApi::class)
class WidgetRefreshingMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> =
    listOf(
      Metric.Measurement("widgetRefreshing", if (WidgetBenchState.refreshing) 1.0 else 0.0),
      // I giri che l'app ha fatto durante lo scorrimento, dalla traccia: informativo (se 0, il lavoro non e' arrivato nel buffer di Perfetto).
      Metric.Measurement("widgetUpdatesDuringScroll", traceSession.query("SELECT COUNT(*) AS n FROM slice WHERE name = '${WidgetBenchTarget.TRACE_UPDATE}' AND dur > 0").firstOrNull()?.long("n")?.toDouble() ?: 0.0),
    )
}
