package dev.pampa.mycloudgallery.benchmark

import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/** I quattro livelli della griglia (§2.4) con il nome della loro voce nel menu "Vista" (G2: i segmenti non ci sono piu'). */
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
 * Le etichette dell'interfaccia di cui gli scenari si fidano, **in un posto solo**: le descrizioni delle celle
 * (`strings_library.xml`, `grid_kind_*`), i nomi dei livelli (`level_*` del design system) e il menu della vista
 * (`library_view_menu`, `library_style_*`). Verificati sulla Libreria vera dell'S25 e dell'emulatore il 13/10, e sulla
 * Libreria della G2 (`docs/grafica-progetto.md` §12) sull'emulatore il 14/10. Se cambiano, si cambiano qui.
 */
object BenchLabels {

  /**
   * La descrizione di una cella: "Foto, 12 marzo 2024", "Video, ...", "Foto in movimento, ...", "Foto RAW, ...",
   * "Immagine animata, ..." (`grid_kind_*`). Una cella della Libreria mista comincia allo stesso modo: la presenza ("solo
   * sul NAS", G2) sta in fondo.
   */
  val CELL: Pattern = Pattern.compile("^(Foto RAW|Foto in movimento|Foto|Video|Immagine animata),.*", Pattern.DOTALL)

  /**
   * Il pulsante della barra che apre il menu della vista, con lo stile (D4) e il filtro (D16): "Vista"
   * (`library_view_menu`, confermato sulla Libreria vera il 13/10). Una lista per poterne provare piu' d'uno.
   */
  val STYLE_MENU_BUTTONS: List<String> = listOf("Vista")

  // --- La chrome della G2 (docs/grafica-progetto.md §12) ------------------------------------------------------------------
  // I segmenti dei livelli non ci sono piu': i livelli sono le prime quattro voci del menu "Vista", nella capsula dei
  // comandi in alto. Ogni selettore ha una catena di alternative, dalla piu' stabile alla piu' fragile: il tag di test della
  // chrome (la Libreria mette `testTagsAsResourceId` alla radice) -> la descrizione o il testo. Cosa serve all'app:
  // docs/bench/README.md §6-ter.

  /** Il tag di test del menu della vista (`galleryChromeTag("vista")`, prefisso `chrome-`; in G1 era `g1-chrome-`). */
  const val TAG_VIEW_MENU: String = "chrome-vista"
}

/** Selettori e gesti sulla Libreria, per tutti gli scenari. Niente di questo conosce il codice dell'app: solo l'albero di accessibilita'. */
object BenchUi {

  private const val TAG = "MCG-Bench"

  val CELL: BySelector = By.desc(BenchLabels.CELL)

  private val SCROLLABLE: BySelector = By.scrollable(true)

  fun waitForLibrary(device: UiDevice, timeoutMs: Long): Boolean = device.wait(Until.hasObject(CELL), timeoutMs) == true

  fun cells(device: UiDevice): List<UiObject2> = device.findObjects(CELL)

  /** La lista che scorre: fra gli elementi scorrevoli, il piu' grande (la griglia riempie lo schermo). */
  fun grid(device: UiDevice): UiObject2? =
    device.findObjects(SCROLLABLE).maxByOrNull { it.visibleBounds.let { b -> b.width().toLong() * b.height() } }

  /**
   * La griglia, aspettandola fino a [timeoutMs]. Serve dopo ogni pop-up: mentre il menu della vista e' aperto **e mentre
   * si richiude** (l'animazione d'uscita), il `FluidGlassModalHost` nasconde la pagina sotto all'accessibilita'
   * (`fluidGlassModalObscured`), e UiAutomator non vede ne' la lista ne' le celle. Sull'S25 `waitForIdle` torna prima
   * che l'uscita finisca: era il "La griglia non c'e'" delle otto prove di scorrimento (misura del 13/10).
   */
  fun awaitGrid(device: UiDevice, timeoutMs: Long = GRID_TIMEOUT_MS): UiObject2? {
    ensureForeground(device)
    device.wait(Until.hasObject(GRID_IN_APP), timeoutMs)
    return grid(device)
  }

  /**
   * L'app sotto misura e' in primo piano; se no la prova si ferma. Ogni gesto e ogni Indietro passano di qui: su un
   * dispositivo condiviso (il 13/10 un'altra sessione ha aperto la sua app sull'S25 nel mezzo di una prova) un fling o
   * un tocco sarebbe finito su un'altra app, e la misura avrebbe contato i fotogrammi di nessuno.
   */
  fun ensureForeground(device: UiDevice) {
    val target = BenchSession.targetPackage
    if (device.currentPackageName == target) return
    device.wait(Until.hasObject(By.pkg(target).depth(0)), FOREGROUND_TIMEOUT_MS)
    check(device.currentPackageName == target) {
      "L'app sotto misura non e' in primo piano (c'e' ${device.currentPackageName}): nessun gesto parte su un'altra app."
    }
  }

  /**
   * Svuota la cache dell'albero di accessibilita' di UiAutomation (Android 14+). Il pop-up dell'engine entra sfumando
   * con l'alfa del layer, che Compose legge nel disegno: quando arriva a 1 non parte nessun evento di accessibilita', e
   * la cache del test continua a vedere le voci come trasparenti (invisibili), mentre un `uiautomator dump` nuovo le
   * vede. Era il "menu aperto ma 'Quadrata' non c'e'" sull'emulatore e sull'S25.
   */
  fun refreshAccessibilityTree() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      InstrumentationRegistry.getInstrumentation().getUiAutomation(Configurator.getInstance().uiAutomationFlags).clearCache()
    }
  }

  /** Il viewer e' aperto: il pager porta la posizione ("3 di 100.000", `viewer_position`) come descrizione. */
  fun viewerOpen(device: UiDevice): Boolean {
    refreshAccessibilityTree()
    return device.hasObject(VIEWER)
  }

  /** Aspetta il viewer dopo il tocco su una cella. */
  fun awaitViewer(device: UiDevice, timeoutMs: Long = VIEWER_TIMEOUT_MS): Boolean = awaitFresh(device, VIEWER, timeoutMs)

  /**
   * Aspetta [selector] rileggendo l'albero a ogni giro (vedi [refreshAccessibilityTree]): per cio' che compare
   * sfumando (pop-up, fogli, il viewer), che la cache di UiAutomation puo' continuare a vedere trasparente.
   */
  fun awaitFresh(device: UiDevice, selector: BySelector, timeoutMs: Long): Boolean {
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < timeoutMs) {
      refreshAccessibilityTree()
      if (device.wait(Until.hasObject(selector), MENU_POLL_MS) == true) return true
    }
    return false
  }

  /** Chiude il viewer con Indietro, **solo se e' aperto**: a viewer gia' chiuso, Indietro uscirebbe dall'app. */
  fun closeViewer(device: UiDevice) {
    if (!viewerOpen(device)) return
    back(device)
    device.wait(Until.gone(VIEWER), VIEWER_TIMEOUT_MS)
  }

  /** Indietro, solo con l'app sotto misura in primo piano. */
  fun back(device: UiDevice) {
    ensureForeground(device)
    device.pressBack()
  }

  /**
   * Scorre la griglia con fling. La Libreria si apre in fondo, sui piu' recenti (D1): [towardsOlder] porta indietro nel
   * tempo, verso l'alto della lista. Il margine lascia fuori i bordi: a destra c'e' lo scrubber, in basso il gesto di sistema.
   *
   * La direzione di `UiObject2.fling` e' quella in cui **scorre il contenuto** (il dito va al contrario): `UP` mostra le
   * righe sopra, cioe' le foto piu' vecchie. Prima era `DOWN`, e da fermi in fondo i primi fling non spostavano niente.
   * La griglia si ricerca a ogni fling: il nodo di accessibilita' della lista si rifa' mentre scorre (StaleObjectException
   * sull'S25 al livello Tutte).
   */
  fun fling(device: UiDevice, towardsOlder: Boolean, times: Int) {
    val direction = if (towardsOlder) Direction.UP else Direction.DOWN
    repeat(times) {
      // Senza la griglia il gesto non c'e' e la misura conterebbe i fotogrammi di un'app ferma: meglio fallire.
      val grid = checkNotNull(awaitGrid(device)) { "La griglia non c'e' per lo scorrimento." }
      grid.setSafeMargins(device)
      try {
        grid.fling(direction)
      } catch (stale: StaleObjectException) {
        Log.w(TAG, "griglia rifatta durante il fling: si riprova una volta")
        checkNotNull(awaitGrid(device)) { "La griglia non c'e' per lo scorrimento." }.apply {
          setSafeMargins(device)
          fling(direction)
        }
      }
    }
  }

  /**
   * I margini dei gesti sulla griglia, che riempie lo schermo: fuori la barra di stato e la chrome della Libreria in alto
   * (un fling verso i piu' vecchi parte in alto e scende: da 1/8 dell'altezza tirava giu' la tendina delle notifiche
   * sull'emulatore), la pillola e il gesto di sistema in basso, lo scrubber a destra.
   */
  private fun UiObject2.setSafeMargins(device: UiDevice) {
    val w = device.displayWidth
    val h = device.displayHeight
    setGestureMargins(w / MARGIN_DIVISOR, (h * SAFE_TOP).toInt(), w / MARGIN_DIVISOR, (h * (1 - SAFE_BOTTOM)).toInt())
  }

  /** Un pinch al centro della griglia: [close] avvicina le dita (livello piu' fitto, verso Anni), altrimenti le allontana. */
  fun pinch(device: UiDevice, close: Boolean, percent: Float = 0.6f, speed: Int = 1_200) {
    // Come per il fling: un pinch che non parte lascerebbe misurare un'app ferma (un'iterazione da 73 fotogrammi invece
    // di 830 sull'S25, 13/10).
    val grid = checkNotNull(awaitGrid(device)) { "La griglia non c'e' per il pinch." }
    grid.setSafeMargins(device)
    if (close) grid.pinchClose(percent, speed) else grid.pinchOpen(percent, speed)
  }

  /**
   * Porta la griglia sul livello [level]. Catena di alternative (vedi [BenchLabels]):
   * 1. le prime voci del menu "Vista" (G2: i livelli stanno li', scelta 4 dell'utente);
   * 2. solo se il menu non si apre o non ha la voce, i pinch (a fondo verso Anni, poi un passo per livello), che da qui
   *    non si possono verificare: lo dice il log. `false` se la griglia non c'e'.
   */
  fun selectLevel(device: UiDevice, level: BenchLevel): Boolean {
    if (awaitGrid(device) == null) return false
    if (selectLevelFromMenu(device, level)) return true
    repeat(LEVELS_TO_SPAN) { pinch(device, close = true) }
    repeat(level.ordinal) { pinch(device, close = false) }
    device.waitForIdle()
    Log.w(TAG, "menu dei livelli non trovato: livello '${level.label}' portato con i pinch, non verificato")
    return true
  }

  /** Il pulsante che apre il menu della vista: il tag della chrome, poi la descrizione "Vista", poi il testo. */
  private fun viewMenuTrigger(device: UiDevice): UiObject2? {
    val pkg = BenchSession.targetPackage
    device.findObject(By.res(BenchLabels.TAG_VIEW_MENU))?.let { return it }
    for (candidate in BenchLabels.STYLE_MENU_BUTTONS) {
      device.findObject(By.pkg(pkg).desc(candidate))?.let { return it }
      device.findObject(By.pkg(pkg).text(candidate))?.let { return it }
    }
    return null
  }

  /**
   * Il livello dal menu "Vista": se la voce non c'e' (il tocco non ha aperto il menu, o si e' aperto un altro pop-up) il
   * menu si chiude e si riprova. `false` se dopo [MENU_ATTEMPTS] il livello non e' stato scelto.
   */
  private fun selectLevelFromMenu(device: UiDevice, level: BenchLevel): Boolean {
    repeat(MENU_ATTEMPTS) {
      ensureForeground(device)
      val button = viewMenuTrigger(device) ?: return@repeat
      button.click()
      val item = awaitMenuItem(device, level.label) { device.hasObject(STYLE_ITEMS) }
      if (item != null) {
        item.click()
        device.wait(Until.hasObject(GRID_IN_APP), MENU_TIMEOUT_MS)
        device.waitForIdle()
        // Il cambio di livello si anima (la scala del pinch sul layer): si misura da fermi, a livello assestato.
        Thread.sleep(LEVEL_SETTLE_MS)
        return awaitGrid(device) != null
      }
      if (!device.hasObject(GRID_IN_APP)) {
        // Il menu e' aperto ma la voce non c'e': lo si chiude.
        back(device)
        device.wait(Until.hasObject(GRID_IN_APP), MENU_TIMEOUT_MS)
      }
    }
    return false
  }

  /**
   * Porta la griglia sullo stile [style] dal menu della vista. `false` se il menu o la voce non si trovano.
   *
   * Il primo pop-up dopo un avvio a freddo puo' metterci piu' di un secondo a comparire (si compilano le pipeline del
   * vetro): mentre si apre, la pagina e' gia' nascosta all'accessibilita' e la voce non c'e' ancora, quindi si aspetta
   * finche' la griglia resta nascosta. Indietro solo se il menu e' davvero aperto (griglia nascosta): a menu chiuso,
   * Indietro chiudeva l'app (S25, 13/10) e il resto della prova cercava la griglia sulla schermata Home.
   */
  fun selectStyle(device: UiDevice, style: BenchStyle): Boolean {
    if (awaitGrid(device) == null) return false
    device.waitForIdle()
    repeat(MENU_ATTEMPTS) {
      ensureForeground(device)
      val button = viewMenuTrigger(device) ?: return@repeat
      button.click()
      val item = awaitMenuItem(device, style.label) { device.hasObject(STYLE_ITEMS) }
      if (item != null) {
        item.click()
        // La voce sparisce con il menu; poi la pagina torna visibile all'accessibilita' (vedi [awaitGrid]).
        device.wait(Until.gone(STYLE_ITEMS), MENU_TIMEOUT_MS)
        device.waitForIdle()
        return awaitGrid(device) != null
      }
      if (!device.hasObject(GRID_IN_APP)) {
        // Il menu e' aperto (la pagina sotto e' nascosta) ma la voce non c'e': lo si chiude.
        back(device)
        device.wait(Until.hasObject(GRID_IN_APP), MENU_TIMEOUT_MS)
      }
    }
    return false
  }

  /**
   * La voce [label] del menu appena toccato: si aspetta finche' compare o finche' la griglia torna visibile senza che
   * [menuIsOpen] dica che il menu si sta aprendo (il tocco non l'ha aperto).
   */
  private fun awaitMenuItem(device: UiDevice, label: String, menuIsOpen: () -> Boolean): UiObject2? {
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < MENU_OPEN_TIMEOUT_MS) {
      refreshAccessibilityTree()
      device.wait(Until.findObject(By.text(label)), MENU_POLL_MS)?.let { return it }
      // Griglia ancora visibile e nessuna voce dopo un po': il tocco non ha aperto il menu.
      val notOpening = device.hasObject(GRID_IN_APP) && !menuIsOpen()
      if (notOpening && System.currentTimeMillis() - start > MENU_GRACE_MS) return null
    }
    return null
  }

  /**
   * La cella piu' vicina al centro dello schermo, lontana dalla chrome in alto e dalla pillola in basso. Le celle si
   * rileggono se la lista si e' mossa sotto (dopo un pinch o un fling le righe si ricompongono: StaleObjectException).
   */
  fun centerCell(device: UiDevice): UiObject2? {
    ensureForeground(device)
    val w = device.displayWidth
    val h = device.displayHeight
    val cx = w / 2.0
    val cy = h / 2.0
    return withFreshCells(device) { cells ->
      cells
        .map { it to it.visibleBounds }
        .filter { (_, b) -> b.width() > 0 && b.top > h * SAFE_TOP && b.bottom < h * SAFE_BOTTOM }
        .minByOrNull { (_, b) -> Math.hypot(b.centerX() - cx, b.centerY() - cy) }
        ?.first
    }
  }

  /** L'ultima cella in fondo (la piu' recente, D1) che sta sopra la pillola: a cui il dataset fa puntare il soggetto dello zoom. */
  fun newestCell(device: UiDevice): UiObject2? {
    ensureForeground(device)
    val h = device.displayHeight
    return withFreshCells(device) { cells ->
      cells
        .map { it to it.visibleBounds }
        .filter { (_, b) -> b.width() > 0 && b.top > h * SAFE_TOP && b.bottom < h * SAFE_BOTTOM }
        .maxWithOrNull(compareBy({ it.second.top }, { it.second.left }))
        ?.first
    }
  }

  /** Tocca la cella [cell] (letta con [centerCell] o [newestCell]) nel suo centro: niente oggetto da rinfrescare. `false` se e' sparita. */
  fun tapCell(device: UiDevice, cell: UiObject2?): Boolean {
    val center = try {
      cell?.visibleCenter
    } catch (stale: StaleObjectException) {
      null
    } ?: return false
    ensureForeground(device)
    device.click(center.x, center.y)
    return true
  }

  private fun <T> withFreshCells(device: UiDevice, pick: (List<UiObject2>) -> T): T? {
    repeat(STALE_RETRIES) {
      device.waitForIdle()
      try {
        return pick(cells(device))
      } catch (stale: StaleObjectException) {
        Log.w(TAG, "celle rifatte mentre le si leggeva: si rileggono")
      }
    }
    return null
  }

  /** Doppio tocco al centro dello schermo (zoom del viewer). */
  fun doubleTapCenter(device: UiDevice) {
    ensureForeground(device)
    val x = device.displayWidth / 2
    val y = device.displayHeight / 2
    device.click(x, y)
    Thread.sleep(DOUBLE_TAP_GAP_MS)
    device.click(x, y)
  }

  /** Un trascinamento dal centro verso uno degli angoli (`dx`, `dy` in -1, 0, 1): il pan del viewer da ingrandito. */
  fun panFromCenter(device: UiDevice, dx: Int, dy: Int) {
    ensureForeground(device)
    val w = device.displayWidth
    val h = device.displayHeight
    val sx = w / 2 - dx * w / 4
    val sy = h / 2 - dy * h / 4
    device.swipe(sx, sy, sx + dx * w / 2, sy + dy * h / 2, PAN_STEPS)
  }

  private const val MARGIN_DIVISOR = 8
  private const val LEVELS_TO_SPAN = 4
  private const val MENU_TIMEOUT_MS = 2_000L
  private const val GRID_TIMEOUT_MS = 5_000L
  private const val FOREGROUND_TIMEOUT_MS = 3_000L
  private const val MENU_ATTEMPTS = 2
  private const val STALE_RETRIES = 3
  private const val VIEWER_TIMEOUT_MS = 5_000L

  private val VIEWER: BySelector get() = By.pkg(BenchSession.targetPackage).desc(Pattern.compile("^[0-9.]+ di [0-9.]+$"))

  private const val MENU_OPEN_TIMEOUT_MS = 8_000L
  private const val MENU_POLL_MS = 500L
  private const val MENU_GRACE_MS = 3_000L

  /** La lista dell'app sotto misura: nascosta all'accessibilita' mentre un pop-up e' aperto. */
  private val GRID_IN_APP: BySelector get() = By.pkg(BenchSession.targetPackage).scrollable(true)

  /** Le due voci dello stile: ci sono solo nel menu della vista, quindi dicono se il menu e' aperto. */
  private val STYLE_ITEMS: BySelector = By.text(Pattern.compile("^(${BenchStyle.SQUARE.label}|${BenchStyle.JUSTIFIED.label})$"))
  private const val LEVEL_SETTLE_MS = 700L
  private const val SAFE_TOP = 0.20
  private const val SAFE_BOTTOM = 0.82
  private const val DOUBLE_TAP_GAP_MS = 60L
  private const val PAN_STEPS = 20
}
