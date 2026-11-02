package dev.pampa.mycloudgallery.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/**
 * Le cose di `PanesBenchmark` (Fase 10, P9; `docs/fase10-progetto.md` §5.4, scenario `pannelli`): la finestra larga, i gesti sui pannelli, le misure.
 *
 * I pannelli (P5) e le due finestre (P4) esistono da una finestra **estesa** (840 dp) e il viewer affiancato da una **ampia** (1200 dp): sul Tab S9 e' l'orizzontale (1280 dp) o
 * DeX. **Sull'emulatore** la finestra si emula con `wm size 2560x1600` e `wm density 320` (1280 x 800 dp), e si rimette a fine prova ([BenchSettings.restoreAll]); sul tablet vero
 * non si cambia lo schermo (serve la larghezza, se no la prova si salta con il motivo); su un telefono vero le prove di questa classe si saltano.
 */
object PanesBenchTarget {

  /** Lo schermo emulato: 2560 x 1600 px a 320 dpi = 1280 x 800 dp, come il Tab S9 in orizzontale. */
  const val WIDE_WIDTH_PX: Int = 2560
  const val WIDE_HEIGHT_PX: Int = 1600
  const val DENSITY_DPI: Int = 320

  /** La larghezza minima del viewer affiancato (`GalleryPaneRules`, D13 (5)). */
  const val WIDE_MIN_DP: Int = 1200

  /** I tre ridimensionamenti che attraversano le soglie di 1200 e 840 dp (a 320 dpi: 1280, 960, 720 dp di larghezza), con la stessa altezza di proporzione. */
  val CROSSING: List<Pair<Int, Int>> = listOf(2560 to 1600, 1920 to 1200, 1440 to 900, 1920 to 1200, 2560 to 1600)
}

/** La finestra larga: dove l'app ha i pannelli. */
object BenchWindow {

  /** La larghezza della finestra in dp, da `wm size` e `wm density` (con l'override se c'e'): vale anche per un tablet vero. */
  fun widthDp(device: UiDevice): Int {
    val density = Regex("(?:Override|Physical) density:\\s*(\\d+)").findAll(BenchSettings.shellRaw("wm density")).map { it.groupValues[1].toInt() }.lastOrNull() ?: return 0
    val size = Regex("(?:Override|Physical) size:\\s*(\\d+)x(\\d+)").findAll(BenchSettings.shellRaw("wm size")).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.lastOrNull()
    // La larghezza del display com'e' ora (la rotazione conta): UiDevice la da' in pixel; con il valore di `wm` ne portiamo i dp.
    val widthPx = if (size != null && device.displayWidth == size.first) size.first else device.displayWidth
    return (widthPx * 160f / density).toInt()
  }

  /**
   * Porta lo schermo alla finestra larga o dice perche' non si puo' (`null` = pronta). Sull'emulatore (o con `benchAllowScreen=true`) cambia `wm size` e `wm density`
   * (rimessi da [BenchSettings.restoreAll]); su un tablet vero, senza il permesso, prova la rotazione in orizzontale; su un telefono vero non fa niente e dice di saltare.
   */
  fun ensureWide(device: UiDevice): String? {
    if (widthDp(device) >= PanesBenchTarget.WIDE_MIN_DP) return null
    if (BenchSettings.canChangeScreen) {
      BenchSettings.screen(PanesBenchTarget.WIDE_WIDTH_PX, PanesBenchTarget.WIDE_HEIGHT_PX, PanesBenchTarget.DENSITY_DPI)
    } else if (WidgetBenchTarget.isTablet()) {
      device.setOrientationLeft()
    } else {
      return "Le finestre e i pannelli sono del tablet: questo e' un telefono (${widthDp(device)} dp)."
    }
    device.waitForIdle()
    Thread.sleep(SETTLE_MS)
    val dp = widthDp(device)
    return if (dp >= PanesBenchTarget.WIDE_MIN_DP) null else "La finestra e' larga $dp dp: per i pannelli ne servono ${PanesBenchTarget.WIDE_MIN_DP} (Tab S9 in orizzontale o in DeX)."
  }

  /** Rimette cio' che le prove hanno cambiato: lo schermo (`wm`) e la rotazione. */
  fun restore(device: UiDevice) {
    runCatching { device.unfreezeRotation() }
    BenchSettings.restoreAll()
  }

  private const val SETTLE_MS = 1_200L
}

/** I gesti sul viewer affiancato e sulla Libreria nel pannello. Le etichette sono quelle di `feature-viewer` e del design system: **se cambiano si cambiano qui**. */
object PanesUi {

  private val pkg: String get() = BenchSession.targetPackage

  /** La voce di menu che affianca il viewer (`viewer_action_dock`) e quella che torna a schermo intero (`viewer_action_undock`). */
  private const val DOCK = "Affianca alla Libreria"
  private const val UNDOCK = "Schermo intero"

  /** Il pulsante del menu del viewer (`topbar_more`). */
  private val MORE: BySelector get() = By.pkg(pkg).desc("Altre azioni")

  /** Le bolle della mappa e dove stanno, e se c'e' una cella di zona: per il messaggio di una prova che si ferma (solo numeri). */
  fun describeBubbles(device: UiDevice): String = runCatching {
    MapUi.bubbles(device).joinToString("; ") { "${MapUi.countOf(it)}@${it.visibleBounds.toShortString()}" } + " | cella di zona: ${device.hasObject(MapUi.ZONE_CELL)}"
  }.getOrDefault("nessuna lettura")

  /** La posizione nel viewer ("3 di 100.000", `viewer_position`): cambia quando cambia la foto. */
  private val POSITION: BySelector get() = By.pkg(pkg).desc(java.util.regex.Pattern.compile("^[0-9.]+ di [0-9.]+$"))

  private const val MENU_TIMEOUT_MS = 4_000L
  private const val DOCK_TIMEOUT_MS = 6_000L
  private const val SWAP_TIMEOUT_MS = 6_000L
  private const val STALE_RETRIES = 3
  private const val SAFE_TOP = 0.20
  private const val SAFE_BOTTOM_MARGIN = 0.18

  /** La posizione del viewer adesso ("49 di 5.000"), o `null` se il viewer non c'e'. */
  fun position(device: UiDevice): String? = try {
    // L'albero di UiAutomation si rilegge: con il viewer affiancato la posizione cambia senza che parta un evento che la cache veda.
    BenchUi.refreshAccessibilityTree()
    device.findObject(POSITION)?.contentDescription
  } catch (e: StaleObjectException) {
    null
  }

  /**
   * Apre il viewer sulla cella centrale e lo affianca alla Libreria dal suo menu. Vero se alla fine la Libreria e il viewer si vedono **insieme** (cella visibile con il
   * viewer aperto): la scelta si ricorda per profilo, quindi una volta fatta il viewer si apre gia' affiancato.
   */
  fun dock(device: UiDevice): Boolean {
    if (!BenchUi.viewerOpen(device)) {
      val cell = BenchUi.centerCell(device)
      lastTapped = runCatching { cell?.visibleCenter?.let { it.x to it.y } }.getOrNull()
      if (!BenchUi.tapCell(device, cell)) return false
      if (!BenchUi.awaitViewer(device)) return false
      device.waitForIdle()
    }
    if (!docked(device)) {
      if (!BenchUi.awaitFresh(device, MORE, MENU_TIMEOUT_MS)) return false
      device.findObject(MORE)?.click()
      if (!BenchUi.awaitFresh(device, By.text(DOCK), MENU_TIMEOUT_MS)) return false
      device.findObject(By.text(DOCK))?.click()
    }
    val end = System.currentTimeMillis() + DOCK_TIMEOUT_MS
    while (System.currentTimeMillis() < end) {
      BenchUi.refreshAccessibilityTree()
      if (docked(device)) {
        device.waitForIdle()
        return true
      }
      Thread.sleep(POLL_MS)
    }
    return false
  }

  /**
   * Il viewer e la Libreria sono sullo schermo **insieme**: il viewer sta a destra (il 60%: il suo bordo sinistro e' dopo il 30% della finestra) e le celle si vedono.
   * La sola presenza delle celle non basta: a viewer a schermo intero l'albero di accessibilita' puo' ancora elencarle dietro di lui.
   */
  fun docked(device: UiDevice): Boolean = try {
    BenchUi.refreshAccessibilityTree()
    val viewer = device.findObject(POSITION)
    viewer != null && viewer.visibleBounds.left > device.displayWidth * DOCKED_LEFT_MIN && device.hasObject(BenchUi.CELL)
  } catch (e: StaleObjectException) {
    false
  }

  /** Rimette il viewer a schermo intero (la scelta "Affianca" si ricorda per profilo: una prova non la lascia). Best effort: mai un'eccezione. */
  fun undock(device: UiDevice) {
    runCatching {
      if (position(device) == null) {
        // Il viewer non e' aperto: lo si apre per leggere la scelta.
        if (!BenchUi.tapCell(device, BenchUi.centerCell(device)) || !BenchUi.awaitViewer(device)) return
      }
      if (!BenchUi.awaitFresh(device, MORE, MENU_TIMEOUT_MS)) return
      device.findObject(MORE)?.click()
      if (BenchUi.awaitFresh(device, By.text(UNDOCK), MENU_TIMEOUT_MS)) device.findObject(By.text(UNDOCK))?.click() else BenchUi.back(device)
      device.waitForIdle()
      BenchUi.closeViewer(device)
    }
  }

  /** La griglia della Libreria nel pannello: la lista che scorre piu' a sinistra con il centro nei primi 45% dello schermo (il viewer al 60% ha un pager piu' grande). */
  fun libraryPane(device: UiDevice): UiObject2? = leftPane(device)

  /** La lista che scorre nel pannello di sinistra (la griglia della Libreria, l'elenco delle Raccolte): quella piu' grande fra le liste con il centro nei primi 45% della finestra. */
  fun leftPane(device: UiDevice): UiObject2? {
    val end = System.currentTimeMillis() + PANE_TIMEOUT_MS
    do {
      BenchUi.refreshAccessibilityTree()
      val found = device.findObjects(By.pkg(pkg).scrollable(true))
        .filter { runCatching { it.visibleBounds.centerX() < device.displayWidth * PANE_CENTER_MAX }.getOrDefault(false) }
        .maxByOrNull { it.visibleBounds.let { b -> b.width().toLong() * b.height() } }
      if (found != null) return found
      Thread.sleep(POLL_MS)
    } while (System.currentTimeMillis() < end)
    return null
  }

  /** Perche' non c'e' un pannello a sinistra: le liste che scorrono e dove stanno (solo numeri), per il messaggio di chi si ferma. */
  private fun describePanes(device: UiDevice): String = runCatching {
    device.findObjects(By.pkg(pkg).scrollable(true)).joinToString("; ") { it.visibleBounds.toShortString() } + " su uno schermo di ${device.displayWidth}x${device.displayHeight}"
  }.getOrDefault("nessuna lettura")

  /**
   * La riga "Mappa" della sezione Luoghi nelle Raccolte **a due pannelli**: l'elenco e' quello di sinistra (a destra c'e' il dettaglio, piu' grande: `MapUi.mapRow` scorrerebbe
   * quello). Si scorre l'elenco finche' la riga compare sopra la pillola; `null` se non c'e' dopo qualche scorrimento.
   */
  fun mapRow(device: UiDevice): UiObject2? {
    val row = By.pkg(pkg).text("Mappa")
    device.wait(Until.findObject(row), ROW_TIMEOUT_MS)
    repeat(ROW_SCROLLS) {
      val found = device.findObject(row)
      if (found != null && runCatching { found.visibleBounds.bottom < device.displayHeight * ROW_SAFE_BOTTOM }.getOrDefault(false)) return found
      val list = leftPane(device) ?: return null
      val w = list.visibleBounds.width()
      list.setGestureMargins(w / MARGIN_DIVISOR, (device.displayHeight * SAFE_TOP).toInt(), w / MARGIN_DIVISOR, (device.displayHeight * SAFE_BOTTOM_MARGIN).toInt())
      list.scroll(Direction.DOWN, ROW_SCROLL_PERCENT)
      device.waitForIdle()
    }
    return device.findObject(row)
  }

  /** Scorre la griglia del pannello con fling (come `BenchUi.fling`, ma sul pannello a sinistra e non sulla lista piu' grande dello schermo). */
  fun fling(device: UiDevice, towardsOlder: Boolean, times: Int) {
    val direction = if (towardsOlder) Direction.UP else Direction.DOWN
    repeat(times) {
      BenchUi.ensureForeground(device)
      val pane = checkNotNull(leftPane(device)) { "La griglia del pannello non c'e' per lo scorrimento (liste: ${describePanes(device)})." }
      pane.setGestureMargins(pane.visibleBounds.width() / MARGIN_DIVISOR, (device.displayHeight * SAFE_TOP).toInt(), pane.visibleBounds.width() / MARGIN_DIVISOR, (device.displayHeight * SAFE_BOTTOM_MARGIN).toInt())
      try {
        pane.fling(direction)
      } catch (stale: StaleObjectException) {
        checkNotNull(leftPane(device)) { "La griglia del pannello non c'e' per lo scorrimento (liste: ${describePanes(device)})." }.fling(direction)
      }
    }
  }

  /**
   * Cambia foto toccando una cella del pannello a sinistra (non quella che il viewer mostra) e restituisce i millisecondi fino a che la posizione del viewer cambia; `null` se
   * non cambia. Un tocco sulla cella piu' vicina al centro **del pannello**, lontana dalla barra in alto e dalla pillola in basso.
   */
  fun swapPhoto(device: UiDevice): Double? {
    BenchUi.ensureForeground(device)
    // Le cause di un tocco che non parte: il blocco misurato non avrebbe nessun fotogramma e Macrobenchmark direbbe solo "0 risultati".
    val before = checkNotNull(position(device)) { "Il viewer affiancato non mostra la posizione (\"N di M\"): non e' aperto." }
    val target = checkNotNull(pickCell(device)) { "Nessuna cella da toccare nel pannello (liste: ${describePanes(device)})." }
    lastTapped = target
    val start = System.nanoTime()
    device.click(target.first, target.second)
    val changed = device.wait(Until.hasObject(By.pkg(pkg).desc(java.util.regex.Pattern.compile("^(?!${java.util.regex.Pattern.quote(before)}$)[0-9.]+ di [0-9.]+$"))), SWAP_TIMEOUT_MS) == true
    return if (changed) (System.nanoTime() - start) / NANOS_PER_MILLI else null
  }

  /** Il centro dell'ultima cella toccata (quella che il viewer mostra): la prossima e' un'altra. */
  @Volatile
  private var lastTapped: Pair<Int, Int>? = null

  /** Il centro di una cella del pannello diversa da quella toccata prima (e quindi da quella mostrata); le celle si rileggono se la lista si ricompone sotto. */
  private fun pickCell(device: UiDevice): Pair<Int, Int>? {
    val h = device.displayHeight
    repeat(STALE_RETRIES) {
      device.waitForIdle()
      try {
        val cells = device.findObjects(BenchUi.CELL).map { it.visibleBounds }.filter { b ->
          b.width() > 0 && b.top > h * SAFE_TOP && b.bottom < h * (1 - SAFE_BOTTOM_MARGIN) && b.centerX() < device.displayWidth * PANE_CENTER_MAX
        }
        val ordered = cells.sortedBy { Math.hypot(it.centerX() - device.displayWidth * PANE_CENTER, it.centerY() - h / 2.0) }
        val last = lastTapped
        val pick = ordered.firstOrNull { b -> last == null || Math.hypot((b.centerX() - last.first).toDouble(), (b.centerY() - last.second).toDouble()) > b.width() / 2.0 }
        if (pick != null) return pick.centerX() to pick.centerY()
      } catch (stale: StaleObjectException) {
        // la lista si e' rifatta mentre la si leggeva: si rilegge
      }
    }
    return null
  }

  private const val PANE_CENTER_MAX = 0.45

  /** Il viewer affiancato comincia al 40% della finestra: sopra il 30% sta a destra. */
  private const val DOCKED_LEFT_MIN = 0.30
  private const val PANE_TIMEOUT_MS = 3_000L
  private const val ROW_TIMEOUT_MS = 1_500L
  private const val ROW_SCROLLS = 8
  private const val ROW_SAFE_BOTTOM = 0.82
  private const val ROW_SCROLL_PERCENT = 0.6f

  /** Il centro del pannello della Libreria: il 40% della finestra, centrato al 20%. */
  private const val PANE_CENTER = 0.20
  private const val MARGIN_DIVISOR = 8
  private const val POLL_MS = 150L
  private const val NANOS_PER_MILLI = 1_000_000.0
}

/** Lo stato delle prove dei pannelli di un'iterazione, consegnato alle metriche proprie. */
object PanesBenchState {
  @Volatile var docked: Boolean = false
  @Volatile var wide: Boolean = false
  @Volatile var viewerSwapMs: Double? = null
  @Volatile var windows: Int = 0

  fun reset() {
    docked = false
    wide = false
    viewerSwapMs = null
    windows = 0
  }
}

/** Dal tocco su una cella del pannello alla foto nuova nel viewer affiancato (`viewerSwapMs`), misurato da UiAutomator. */
@OptIn(ExperimentalMetricApi::class)
class ViewerSwapMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> =
    listOf(PanesBenchState.viewerSwapMs?.let { Metric.Measurement("viewerSwapMs", it) } ?: Metric.Measurement("viewerSwapMissing", 1.0))
}

/** Com'era la finestra: `panesWide` (1 se larga come richiesto), `panesDocked` (1 se il viewer era affiancato) e `panesWindows` (quante finestre dell'app erano aperte). */
@OptIn(ExperimentalMetricApi::class)
class PanesStateMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> =
    listOf(
      Metric.Measurement("panesWide", if (PanesBenchState.wide) 1.0 else 0.0),
      Metric.Measurement("panesDocked", if (PanesBenchState.docked) 1.0 else 0.0),
      Metric.Measurement("panesWindows", PanesBenchState.windows.toDouble()),
    )
}

/** La PSS dell'app con due finestre (`windowsPssKb`, con heap Java, nativo e grafica informativi), campionata da [BenchMemory.sample]. */
@OptIn(ExperimentalMetricApi::class)
class WindowsMemoryMetric : TraceMetric() {

  override fun getMeasurements(captureInfo: Metric.CaptureInfo, traceSession: TraceProcessor.Session): List<Metric.Measurement> =
    listOfNotNull(
      BenchMemory.pssKb?.let { Metric.Measurement("windowsPssKb", it) },
      BenchMemory.javaHeapKb?.let { Metric.Measurement("windowsJavaHeapKb", it) },
      BenchMemory.nativeHeapKb?.let { Metric.Measurement("windowsNativeHeapKb", it) },
      BenchMemory.graphicsKb?.let { Metric.Measurement("windowsGraphicsKb", it) },
    )
}
