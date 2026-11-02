package dev.pampa.mycloudgallery.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentCallbacks
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.contract.WidgetFeed
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.widget.contract.WidgetClock
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetLedger
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMemories
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlanner
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPrefs
import dev.pampa.mycloudgallery.core.widget.contract.WidgetRefresher
import dev.pampa.mycloudgallery.core.widget.contract.WidgetRules
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTapRequest
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTarget
import dev.pampa.mycloudgallery.core.widget.contract.WidgetLock
import dev.pampa.mycloudgallery.core.widget.ledger.FileLedgerStorage
import dev.pampa.mycloudgallery.core.widget.ledger.FileWidgetLedger
import dev.pampa.mycloudgallery.core.widget.plan.DefaultWidgetPlanner
import dev.pampa.mycloudgallery.core.widget.tap.WidgetTap
import dev.pampa.mycloudgallery.feature.widget.frame.WidgetSizing
import dev.pampa.mycloudgallery.feature.widget.glance.GalleryWidgetReceiver
import dev.pampa.mycloudgallery.feature.widget.host.WidgetAppearance
import dev.pampa.mycloudgallery.feature.widget.host.WidgetPlaceNames
import dev.pampa.mycloudgallery.feature.widget.host.WidgetServices
import dev.pampa.mycloudgallery.feature.widget.host.WidgetServicesProvider
import dev.pampa.mycloudgallery.feature.widget.host.WidgetThumbnails
import dev.pampa.mycloudgallery.feature.widget.work.WidgetWork
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

// IL WIDGET NELL'APP (Fase 10, P13; docs/fase10-progetto.md §1.4 "WidgetGraph", §2.1, §2.2, D3-D9, D42; "Cosa serve a P13" di P2 e P3 in docs/decisioni.md). Nasce
// **senza I/O** con il processo e mette in `WidgetHost` il fornitore delle porte vere: pianificatore sopra la Libreria (un solo `DefaultWidgetPlanner`, cursore ed
// epoche del processo), registro su file in `noBackupFilesDir/widget/`, miniature locali, blocco, preferenze, orologio. Il ridisegno (`GlanceWidgetRefresher`) lo
// costruisce chi monta (`WidgetWiring`), qui arriva gia' fatto.

/** Le porte del giro (§2.1) di questo processo. */
class WidgetPorts(
  override val planner: WidgetPlanner,
  override val ledger: WidgetLedger,
  override val thumbnails: WidgetThumbnails,
  override val prefs: WidgetPrefs,
  override val clock: WidgetClock,
  override val places: WidgetPlaceNames,
  override val appearance: WidgetAppearance,
) : WidgetServices

/**
 * Il grafo del widget. [provider] va in `WidgetHost` all'avvio del processo; [refresher] e' quello che `:app` da' a chi cambia cio' che il widget mostra (nascosti,
 * scaffale, blocco, aspetto, scelte, azzeramento). [watch] e' il giro dei dati personali (un nascosto arrivato da un altro dispositivo). [resolve] e' il tocco, dopo il
 * blocco dell'app.
 *
 * **Fallire chiusi** (D5, E-W4): finche' l'istantanea della Libreria non c'e', o dopo l'azzeramento, o con la versione fermata dal manifest (`Stopped`, E-REL6), il
 * fornitore risponde null e il widget non disegna niente di nuovo (resta quello che il launcher ha: era verificato, o gia' svuotato).
 */
class WidgetGraph(
  private val context: Context,
  val ledger: WidgetLedger,
  val planner: WidgetPlanner,
  private val thumbs: LocalWidgetThumbs,
  val prefs: WidgetPrefs,
  val lock: WidgetLock,
  private val snapshot: StateFlow<*>,
  private val visible: VisibleMedia,
  private val mask: HiddenMask,
  private val places: WidgetPlaceNames,
  private val awaitShelf: suspend (budgetMs: Long) -> Unit,
  private val stoppedByRelease: () -> Boolean,
  val counters: WidgetCounters,
  val clock: WidgetClock = SystemWidgetClock,
) {
  private val stopped = AtomicBoolean(false)

  @Volatile
  var refresher: WidgetRefresher = dev.pampa.mycloudgallery.core.widget.noop.NoWidgetRefresher
    internal set

  val services: WidgetServices = WidgetPorts(
    planner = planner,
    ledger = ledger,
    thumbnails = thumbs,
    prefs = prefs,
    clock = clock,
    places = places,
    // Il tema dell'app e' quello di serie (`MyCloudGalleryTheme()` senza impostazioni): l'Ametista che segue il sistema. Quando ci saranno le impostazioni d'aspetto
    // (fase grafica) si leggono qui, con `appearanceChanged()` a ogni cambio.
    appearance = WidgetAppearance { EngineSettings() },
  )

  /** Il fornitore del processo (§2.1 passo 2): l'orologio subito, le porte quando l'istantanea c'e' (al piu' [budgetMs]). */
  val provider: WidgetServicesProvider = object : WidgetServicesProvider {
    override val clock: WidgetClock get() = this@WidgetGraph.clock

    override suspend fun await(budgetMs: Long): WidgetServices? = awaitServices(budgetMs)
  }

  private suspend fun awaitServices(budgetMs: Long): WidgetServices? {
    if (stopped.get() || stoppedByRelease()) return null
    val started = clock.monotonicMs()
    if (snapshot.value == null) {
      if (budgetMs <= 0) return null
      withTimeoutOrNull(budgetMs) { snapshot.filterNotNull().first() } ?: return null
    }
    // Lo scaffale (il ricordo di oggi): si monta se si puo' nel tempo che resta; se no il pianificatore passa alle foto (mai un ricordo non verificato).
    val left = budgetMs - (clock.monotonicMs() - started)
    if (left > 0) {
      try {
        withTimeoutOrNull(left) { awaitShelf(left) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "scaffale per il widget non montato (${e.javaClass.simpleName})" }
      }
    }
    return if (stopped.get() || stoppedByRelease()) null else services
  }

  /** I nostri widget sulla Home (nessuno = nessun lavoro). Mai un'eccezione: senza `AppWidgetManager` nessuno. */
  fun slots(): Set<WidgetSlot> = try {
    AppWidgetManager.getInstance(context)?.getAppWidgetIds(ComponentName(context, GalleryWidgetReceiver::class.java))
      ?.filter { it > 0 }?.mapTo(LinkedHashSet()) { WidgetSlot(it) }.orEmpty()
  } catch (e: RuntimeException) {
    emptySet()
  }

  /** Quanti widget per misura (Diagnostica), con la stessa regola del ridisegno (`WidgetSizing`). */
  fun sizes(): Map<WidgetSize, Int> {
    val manager = try {
      AppWidgetManager.getInstance(context)
    } catch (e: RuntimeException) {
      null
    } ?: return emptyMap()
    val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
    return slots().groupingBy { slot ->
      val options = manager.getAppWidgetOptions(slot.appWidgetId)
      val (w, h) = WidgetSizing.fromOptions(
        minWidthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0,
        maxWidthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0) ?: 0,
        minHeightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0,
        maxHeightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0,
        portrait = portrait,
      )
      WidgetSizing.classify(w, h)
    }.eachCount()
  }

  /** Il file del registro: la sua ora e' quella dell'ultimo disegno o svuotamento (sopravvive al processo, a differenza dei contatori). */
  val ledgerFile: File = File(File(context.noBackupFilesDir, DIR), FileLedgerStorage.FILE_NAME)

  /**
   * Il tocco, **dopo** il blocco dell'app (D9, E-W7, E-W8): sul registro locale, con la visibilita' di adesso. Un widget non nostro, numeri che il registro non ha o un
   * errore = la Libreria (`WidgetTaps.resolve` fallisce chiuso).
   */
  suspend fun resolve(tap: WidgetTapRequest): WidgetTarget = WidgetTap.resolve(tap, slots(), ledger, visible, mask)

  /**
   * Il giro dei dati personali (§2.1 "Nascosto arrivato da un altro dispositivo", D5): i media che i widget mostrano adesso (dal registro) e che ora sono nascosti o non piu'
   * visibili si dicono a [refresher] (svuotamento subito, poi ridisegno). Un errore di lettura della visibilita' = tutti quelli mostrati (fallire chiusi). Al piu' 7 celle per
   * widget: costa poco, e si puo' chiamare a ogni cambio dell'istantanea e a ogni giro della sincronizzazione personale.
   */
  suspend fun watch(): Set<MediaId> {
    if (stopped.get()) return emptySet()
    val shown = LinkedHashSet<MediaId>()
    for (slot in ledger.slots()) {
      for (index in 0 until WidgetRules.MAX_IMAGES) ledger.cell(slot, index)?.let { shown += it.media }
    }
    if (shown.isEmpty()) return emptySet()
    val gone = try {
      mask.hiddenAmongOrThrow(shown) + (shown - visible.visibleOrThrow(shown))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      shown
    }
    if (gone.isNotEmpty()) refresher.hiddenChanged(gone)
    return gone
  }

  /** Il ridisegno di Glance (per `appForegrounded` e `reschedulePeriodic`, che non sono nel contratto); null nei test. */
  @Volatile
  internal var glance: dev.pampa.mycloudgallery.feature.widget.refresh.GlanceWidgetRefresher? = null

  /** L'app e' andata in secondo piano (l'ultima finestra si e' fermata): dopo 2 s di calma, al piu' ogni 15 minuti, un giro (la Home torna visibile). */
  fun appBackgrounded() {
    if (!stopped.get()) refresher.appBackgrounded()
  }

  /** L'app e' tornata davanti: l'attesa del giro "in secondo piano" cade. */
  fun appForegrounded() {
    glance?.appForegrounded()
  }

  /** La scelta di cosa mostrare e' cambiata (Q1): un giro. */
  fun contentChanged() = refresher.shelfChanged()

  /** Segue la modalita' scura del sistema (E-W10): un giro quando cambia (mai al primo valore). */
  fun followAppearance(app: Context) {
    var night = app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    app.registerComponentCallbacks(
      object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
          val now = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
          if (now != night) {
            night = now
            refresher.appearanceChanged()
          }
        }

        @Deprecated("Deprecato in Android, ma resta nel contratto di ComponentCallbacks.")
        override fun onLowMemory() = Unit
      },
    )
  }

  /**
   * L'azzeramento (D42), **per primo**: tutti i widget a "Configura MyCloud Gallery" senza immagini (il disegno e' consegnato quando ritorna), poi il lavoro periodico
   * annullato, il registro tolto e i contatori a zero. Da qui il fornitore risponde null: niente si ridisegna piu' in questo processo.
   */
  suspend fun stop() {
    if (stopped.get()) return
    try {
      refresher.blankAll(WidgetEmpty.NOT_CONFIGURED)
    } finally {
      stopped.set(true)
      runCatching { WidgetWork.cancel(context) }
      ledger.clear()
      counters.reset()
    }
  }

  val isStopped: Boolean get() = stopped.get()

  companion object {
    private const val TAG = "WidgetGraph"

    /** La cartella del registro in `noBackupFilesDir` (l'azzeramento la toglie anche come file). */
    const val DIR = "widget"

    /** Costruisce il grafo sopra le porte della galleria e dei ricordi (nessun I/O). */
    fun create(
      context: Context,
      feed: WidgetFeed,
      visible: VisibleMedia,
      mask: HiddenMask,
      memories: WidgetMemories,
      thumbs: LocalWidgetThumbs,
      lock: WidgetLock,
      prefs: WidgetPrefs,
      places: WidgetPlaceNames,
      awaitShelf: suspend (Long) -> Unit,
      stoppedByRelease: () -> Boolean,
      counters: WidgetCounters,
      clock: WidgetClock = SystemWidgetClock,
      ledger: WidgetLedger = FileWidgetLedger(File(context.noBackupFilesDir, DIR)),
    ): WidgetGraph {
      val planner = CountingWidgetPlanner(DefaultWidgetPlanner(feed, memories, visible, mask, thumbs, lock, prefs, clock, ledger), counters)
      return WidgetGraph(
        context.applicationContext, ledger, planner, thumbs, prefs, lock, visible.snapshot, visible, mask, places, awaitShelf, stoppedByRelease, counters, clock,
      )
    }
  }
}
