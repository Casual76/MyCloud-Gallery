package dev.pampa.mycloudgallery.benchmark

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Il **servizio di accessibilita' di prova** del benchmark (Fase 10, P9; `docs/fase10-progetto.md` §5.4, scenario `accessibilita`): simula TalkBack. E' un servizio vero,
 * dichiarato **solo nel modulo di collaudo** (`:benchmark`, che non entra mai nell'APK dell'app) e acceso con `settings put secure enabled_accessibility_services` solo per la
 * durata delle prove (`BenchSettings.accessibilityService`, rimesso come prima).
 *
 * Perche' un servizio nostro e non TalkBack: sull'emulatore AOSP non c'e' TalkBack e il collaudo non deve dipendere da una voce; quello che pesa sull'app non e' la voce ma il
 * **lettore dell'albero** (Compose costruisce i nodi di accessibilita' a ogni richiesta del servizio) e gli eventi di scorrimento e di contenuto. Il servizio e' dichiarato
 * `isAccessibilityTool="true"`: come TalkBack e' uno **strumento**, quindi la finestra dell'app (`accessibilityDataSensitive`, P8) gli da' l'albero, e il descrittore di
 * `core-describe` (`A11yActivity`) lo conta come "strumento attivo" (dal legame di P13: senza, `warm` non parte).
 *
 * **Cosa fa** (piu' severo di TalkBack, che legge il nodo a fuoco e i vicini): a ogni evento di scorrimento o di contenuto dell'app, **al piu' ogni [WALK_INTERVAL_MS]**, percorre
 * tutto l'albero della finestra attiva e legge testo e descrizione di ogni nodo. Conta nodi ed eventi in [BenchA11yStats] (dentro lo stesso processo del collaudo: nessun file)
 * perche' la prova sappia che il servizio ha letto davvero (validita'), e quante volte il sistema l'ha scollegato (UiAutomation puo' sospendere i servizi: vedi [BenchA11yStats]).
 * Non scrive niente, non salva niente, non manda niente: solo numeri.
 */
class BenchA11yService : AccessibilityService() {

  private val handler = Handler(Looper.getMainLooper())
  private var walkScheduled = false

  private val walk = Runnable {
    walkScheduled = false
    walkActiveWindow()
  }

  override fun onServiceConnected() {
    super.onServiceConnected()
    BenchA11yStats.connections.incrementAndGet()
    BenchA11yStats.connected = true
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent) {
    val type = event.eventType
    val interesting = type == AccessibilityEvent.TYPE_VIEW_SCROLLED || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
      type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_VIEW_FOCUSED
    BenchA11yStats.events.incrementAndGet()
    if (!interesting || event.packageName?.toString() != BenchA11yStats.targetPackage) return
    if (!walkScheduled) {
      walkScheduled = true
      handler.postDelayed(walk, WALK_INTERVAL_MS)
    }
  }

  override fun onInterrupt() = Unit

  override fun onUnbind(intent: android.content.Intent?): Boolean {
    BenchA11yStats.connected = false
    BenchA11yStats.disconnections.incrementAndGet()
    handler.removeCallbacks(walk)
    walkScheduled = false
    return super.onUnbind(intent)
  }

  /** Percorre tutto l'albero della finestra attiva (come un lettore che lo rilegge): testo e descrizione di ogni nodo. */
  private fun walkActiveWindow() {
    val root = try {
      rootInActiveWindow
    } catch (e: RuntimeException) {
      null
    } ?: return
    val started = System.nanoTime()
    var count = 0
    var text = 0
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(root)
    while (queue.isNotEmpty() && count < MAX_NODES) {
      val node = queue.removeFirst()
      count++
      if (!node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty()) text++
      for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
    }
    BenchA11yStats.walks.incrementAndGet()
    BenchA11yStats.nodes.addAndGet(count.toLong())
    BenchA11yStats.nodesWithText.addAndGet(text.toLong())
    BenchA11yStats.walkNanos.addAndGet(System.nanoTime() - started)
  }

  private companion object {
    /** Come TalkBack, che non legge ogni fotogramma: una lettura ogni 100 ms al piu'. */
    const val WALK_INTERVAL_MS = 100L

    /** Un tetto per non girare all'infinito su un albero rovinato. */
    const val MAX_NODES = 5_000
  }
}

/**
 * Cosa ha fatto il servizio di prova, **dentro il processo di collaudo** (il servizio sta nel suo APK): letture dell'albero, nodi letti, eventi visti, collegamenti e
 * scollegamenti. UiAutomation sospende gli altri servizi di accessibilita' quando si collega senza `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` (che
 * `AccessibilityScrollBenchmark` imposta); se un'altra chiamata (Macrobenchmark usa la sua UiAutomation) lo sospende lo stesso, `disconnections` lo dice e la prova non vale.
 */
object BenchA11yStats {
  val walks = AtomicInteger()
  val nodes = AtomicLong()
  val nodesWithText = AtomicLong()
  val walkNanos = AtomicLong()
  val events = AtomicInteger()
  val connections = AtomicInteger()
  val disconnections = AtomicInteger()

  @Volatile var connected: Boolean = false

  /** Il pacchetto dell'app sotto misura, messo dalla prova (il servizio guarda solo i suoi eventi). */
  @Volatile var targetPackage: String = ""

  fun reset() {
    walks.set(0)
    nodes.set(0)
    nodesWithText.set(0)
    walkNanos.set(0)
    events.set(0)
    connections.set(0)
    disconnections.set(0)
  }
}
