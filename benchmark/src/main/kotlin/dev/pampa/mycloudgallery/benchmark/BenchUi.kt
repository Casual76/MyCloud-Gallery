package dev.pampa.mycloudgallery.benchmark

import android.util.Log
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/** I quattro livelli della griglia (§2.4) con il nome che il segmento dei livelli porta in basso. */
enum class BenchLevel(val label: String) {
  YEARS("Anni"),
  MONTHS("Mesi"),
  DAYS("Giorni"),
  ALL("Tutte"),
}

/** I due stili della griglia (D4): quadrata e "proporzioni originali" (righe giustificate). */
enum class BenchStyle(val label: String) {
  SQUARE("Quadrata"),
  JUSTIFIED("Proporzioni originali"),
}

/**
 * Le etichette dell'interfaccia di cui gli scenari si fidano, **in un posto solo**. Sono quelle del §2.4 e delle stringhe
 * dello stub di P0 (`strings_grid.xml`); P7 le conferma o le cambia a M1 e qui si aggiornano. Gli scenari che non le
 * trovano si SALTANO con il motivo (non falliscono): meglio nessun numero di un numero sul livello sbagliato.
 */
object BenchLabels {

  /** La descrizione di una cella: "Foto, 12 marzo 2024", "Video, ...", "Foto in movimento, ..." (`grid_cell_*`). */
  val CELL: Pattern = Pattern.compile("^(Foto|Video|Foto in movimento),.*", Pattern.DOTALL)

  /** Il pulsante della barra che apre il menu dello stile (D4) e il filtro (D16): se ne prova uno per volta. */
  val STYLE_MENU_BUTTONS: List<String> = listOf("Stile", "Vista", "Opzioni", "Altro")
}

/** Selettori e gesti sulla Libreria, per tutti gli scenari. Niente di questo conosce il codice dell'app: solo l'albero di accessibilita'. */
object BenchUi {

  private const val TAG = "MCG-Bench"

  val CELL: BySelector = By.desc(BenchLabels.CELL)

  fun waitForLibrary(device: UiDevice, timeoutMs: Long): Boolean = device.wait(Until.hasObject(CELL), timeoutMs) == true

  fun cells(device: UiDevice): List<UiObject2> = device.findObjects(CELL)

  /** La lista che scorre: fra gli elementi scorrevoli, il piu' grande (la griglia riempie lo schermo). */
  fun grid(device: UiDevice): UiObject2? =
    device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.let { b -> b.width().toLong() * b.height() } }

  /**
   * Scorre la griglia con fling. La Libreria si apre in fondo, sui piu' recenti (D1): [towardsOlder] porta indietro nel
   * tempo (il dito scende). Il margine lascia fuori i bordi: a destra c'e' lo scrubber, in basso il gesto di sistema.
   */
  fun fling(device: UiDevice, towardsOlder: Boolean, times: Int) {
    val grid = grid(device) ?: return
    grid.setGestureMargin(device.displayWidth / MARGIN_DIVISOR)
    repeat(times) { grid.fling(if (towardsOlder) Direction.DOWN else Direction.UP) }
  }

  /** Un pinch al centro della griglia: [close] avvicina le dita (livello piu' fitto, verso Anni), altrimenti le allontana. */
  fun pinch(device: UiDevice, close: Boolean, percent: Float = 0.6f, speed: Int = 1_200) {
    val grid = grid(device) ?: return
    grid.setGestureMargin(device.displayWidth / MARGIN_DIVISOR)
    if (close) grid.pinchClose(percent, speed) else grid.pinchOpen(percent, speed)
  }

  /**
   * Porta la griglia sul livello [level]: prima con il segmento dei livelli per nome; se non c'e', con i pinch (a fondo
   * verso Anni, poi un passo per livello). Il secondo percorso non si puo' verificare da qui: lo dice il log. `false` se
   * la griglia non c'e'.
   */
  fun selectLevel(device: UiDevice, level: BenchLevel): Boolean {
    if (grid(device) == null) return false
    val segment = device.findObject(By.text(level.label)) ?: device.findObject(By.desc(level.label))
    if (segment != null) {
      segment.click()
      device.waitForIdle()
      return true
    }
    repeat(LEVELS_TO_SPAN) { pinch(device, close = true) }
    repeat(level.ordinal) { pinch(device, close = false) }
    device.waitForIdle()
    Log.w(TAG, "segmento '${level.label}' non trovato: livello portato con i pinch, non verificato")
    return true
  }

  /** Porta la griglia sullo stile [style] dal menu della barra. `false` se il menu o la voce non si trovano. */
  fun selectStyle(device: UiDevice, style: BenchStyle): Boolean {
    for (candidate in BenchLabels.STYLE_MENU_BUTTONS) {
      val button = device.findObject(By.desc(candidate)) ?: device.findObject(By.text(candidate)) ?: continue
      button.click()
      val item = device.wait(Until.findObject(By.text(style.label)), MENU_TIMEOUT_MS)
      if (item != null) {
        item.click()
        device.waitForIdle()
        return true
      }
      device.pressBack()
    }
    return false
  }

  /** La cella piu' vicina al centro dello schermo, lontana dalla barra in alto e dalla pillola in basso. */
  fun centerCell(device: UiDevice): UiObject2? {
    val w = device.displayWidth
    val h = device.displayHeight
    val cx = w / 2.0
    val cy = h / 2.0
    return cells(device)
      .filter { c -> c.visibleBounds.let { it.width() > 0 && it.top > h * SAFE_TOP && it.bottom < h * SAFE_BOTTOM } }
      .minByOrNull { c -> c.visibleBounds.let { Math.hypot(it.centerX() - cx, it.centerY() - cy) } }
  }

  /** L'ultima cella in fondo (la piu' recente, D1) che sta sopra la pillola: a cui il dataset fa puntare il soggetto dello zoom. */
  fun newestCell(device: UiDevice): UiObject2? {
    val h = device.displayHeight
    return cells(device)
      .filter { c -> c.visibleBounds.let { it.width() > 0 && it.top > h * SAFE_TOP && it.bottom < h * SAFE_BOTTOM } }
      .maxWithOrNull(compareBy({ it.visibleBounds.top }, { it.visibleBounds.left }))
  }

  /** Doppio tocco al centro dello schermo (zoom del viewer). */
  fun doubleTapCenter(device: UiDevice) {
    val x = device.displayWidth / 2
    val y = device.displayHeight / 2
    device.click(x, y)
    Thread.sleep(DOUBLE_TAP_GAP_MS)
    device.click(x, y)
  }

  /** Un trascinamento dal centro verso uno degli angoli (`dx`, `dy` in -1, 0, 1): il pan del viewer da ingrandito. */
  fun panFromCenter(device: UiDevice, dx: Int, dy: Int) {
    val w = device.displayWidth
    val h = device.displayHeight
    val sx = w / 2 - dx * w / 4
    val sy = h / 2 - dy * h / 4
    device.swipe(sx, sy, sx + dx * w / 2, sy + dy * h / 2, PAN_STEPS)
  }

  private const val MARGIN_DIVISOR = 8
  private const val LEVELS_TO_SPAN = 4
  private const val MENU_TIMEOUT_MS = 2_000L
  private const val SAFE_TOP = 0.20
  private const val SAFE_BOTTOM = 0.82
  private const val DOUBLE_TAP_GAP_MS = 60L
  private const val PAN_STEPS = 20
}
