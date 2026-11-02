package dev.pampa.mycloudgallery.widget

import android.content.Context
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.feature.settings.widget.WidgetSettings
import dev.pampa.mycloudgallery.feature.widget.host.WidgetHost
import dev.pampa.mycloudgallery.feature.widget.host.WidgetPlaceNames
import dev.pampa.mycloudgallery.feature.widget.refresh.GlanceWidgetRefresher
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.memories.MemoriesGraph
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// IL LEGAME DEL WIDGET (Fase 10, P13, docs/fase10-progetto.md §1.4 "WidgetGraph", §2.1 "Quando si ridisegna"). Chi cambia qualcosa che il widget mostra lo dice al
// ridisegno, uno solo per processo: un "Nascondi" qui (`GalleryGraph`), un nascosto arrivato da un altro dispositivo (il giro dei dati personali e l'istantanea), lo
// scaffale dei ricordi (ricordo nuovo, scartato, potato), il blocco dell'app, la modalita' scura, le scelte di Impostazioni -> Widget. Nessun I/O qui: solo iscrizioni.

object WidgetWiring {

  /** Il grafo del widget sopra la galleria e i ricordi di questo processo. */
  fun build(
    context: Context,
    gallery: GalleryGraph,
    memories: MemoriesGraph,
    lock: AppLockController,
    settings: () -> WidgetSettings,
    stoppedByRelease: () -> Boolean,
    releaseStopped: Flow<Boolean> = emptyFlow(),
  ): WidgetGraph {
    val counters = WidgetCounters()
    return WidgetGraph.create(
      context = context,
      feed = gallery.widgetFeed,
      visible = gallery.visibleMedia,
      mask = gallery.hiddenMask,
      memories = ShelfWidgetMemories(memories),
      thumbs = LocalWidgetThumbs({ id -> gallery.repository.mediaRefOf(id) }, gallery.thumbnailSource, counters),
      lock = SnapshotWidgetLock(lock.settings, gallery.visibleMedia.snapshot, counters),
      prefs = SettingsWidgetPrefs(settings),
      places = WidgetPlaceNames { id -> gallery.places.labelOfId(id) },
      awaitShelf = { budgetMs -> memories.awaitLive(budgetMs.coerceAtLeast(1).milliseconds) },
      stoppedByRelease = stoppedByRelease,
      counters = counters,
      releaseStopped = releaseStopped,
    )
  }

  /**
   * All'avvio del processo (`MyCloudGalleryApp.onCreate`): il fornitore in `WidgetHost` (prima che un ricevitore o un lavoro chiedano le porte), il ridisegno vero
   * e le iscrizioni. [glance] e' il `GlanceWidgetRefresher` del processo (anche `reschedulePeriodic`, che non e' nel contratto).
   */
  @OptIn(FlowPreview::class)
  fun install(
    app: Context,
    widget: WidgetGraph,
    gallery: GalleryGraph,
    memories: MemoriesGraph,
    lock: AppLockController,
    settings: () -> WidgetSettings,
    scope: CoroutineScope,
    glance: GlanceWidgetRefresher = GlanceWidgetRefresher(app),
  ) {
    widget.refresher = CountingWidgetRefresher(glance, widget.ledger, widget.counters, { widget.slots().size }, scope)
    WidgetHost.install(widget.provider)
    widget.glance = glance
    followHidden(widget, gallery, scope)
    // Lo scaffale dei ricordi: un ricordo che esce (scartato, "Mai", potato) svuota i widget che lo mostrano (E-W19); un ricordo nuovo di oggi fa un giro.
    scope.launch {
      quietly {
        var before: List<MemoryId>? = null
        memories.memories.shelf.map { shelf -> shelf.items.map { it.id } }.distinctUntilChanged().collect { now ->
          val previous = before
          before = now
          if (previous != null) {
            for (gone in previous - now.toSet()) widget.refresher.memoryDropped(gone)
            if (now.any { it !in previous }) widget.refresher.shelfChanged()
          }
        }
      }
    }
    // Il blocco dell'app acceso o spento (Q3 (c)), anche quando le impostazioni si leggono la prima volta (prima valeva "acceso").
    scope.launch { quietly { lock.settings.map { it?.isEnabled }.distinctUntilChanged().drop(1).collect { widget.refresher.lockChanged() } } }
    // Le scelte di Impostazioni -> Widget: il contenuto fa un giro, la frequenza rimette in coda il lavoro (solo se c'e' un widget).
    // Le preferenze si aprono qui, in sottofondo (mai sul thread principale all'avvio).
    scope.launch { quietly { settings().content.drop(1).collect { widget.contentChanged() } } }
    scope.launch { quietly { settings().period.drop(1).collect { if (widget.slots().isNotEmpty()) glance.reschedulePeriodic() } } }
    widget.followAppearance(app.applicationContext)
    followRelease(widget, scope)
  }

  /**
   * La versione fermata dal manifest (`ALL`, R1): all'ingresso nello stato i widget si svuotano ("Apri MyCloud Gallery"), all'uscita (stato normale o sola lettura) si
   * ridisegnano. Si agisce solo a un **cambio** di stato: il valore di partenza "non fermata" non fa fare un giro in piu' all'avvio.
   */
  fun followRelease(widget: WidgetGraph, scope: CoroutineScope) {
    scope.launch {
      quietly {
        var before = false
        widget.releaseStopped.distinctUntilChanged().collect { now ->
          if (now != before) {
            before = now
            widget.releaseChanged(now)
          }
        }
      }
    }
  }

  /**
   * I nascosti verso il widget (D5, §2.1): un "Nascondi" fatto qui svuota subito i widget che mostrano quei media; un nascosto arrivato da un altro dispositivo si vede a
   * ogni giro della sincronizzazione dei dati personali e a ogni istantanea nuova (dopo la calma): [WidgetGraph.watch] confronta i media mostrati con la visibilita' di
   * adesso. `WidgetPersonalRoundAppTest` lo prova sul grafo vero con due dispositivi.
   */
  @OptIn(FlowPreview::class)
  fun followHidden(widget: WidgetGraph, gallery: GalleryGraph, scope: CoroutineScope) {
    gallery.hiddenIdListeners += { ids, hidden -> if (hidden && ids.isNotEmpty()) widget.refresher.hiddenChanged(ids) }
    scope.launch { quietly { gallery.visibleMedia.snapshot.map { it?.seq }.distinctUntilChanged().drop(1).debounce(SNAPSHOT_QUIET).collect { widget.watch() } } }
    gallery.nas?.organizer?.let { organizer -> scope.launch { quietly { organizer.lastPersonal.drop(1).collect { widget.watch() } } } }
  }

  /**
   * Dopo il primo disegno utile: se ci sono widget sulla Home, il lavoro periodico c'e' (WG-1: l'azzeramento lo cancella e il ricevitore non rinasce se i widget restano; WG-2: alla
   * frequenza scelta, `KEEP`) e un giro li ridisegna (pubblica il contenuto vero e compone in anticipo lo svuotamento, nota di P3). Con il widget fermo (un azzeramento partito
   * dopo l'avvio della galleria) non fa niente: il lavoro non deve rinascere sotto i dati che si cancellano.
   */
  fun publishAtStart(widget: WidgetGraph) {
    if (widget.isStopped || widget.slots().isEmpty()) return
    widget.ensureWork()
    widget.refresher.shelfChanged()
  }

  private suspend fun quietly(block: suspend () -> Unit) {
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "iscrizione del widget non riuscita (${e.javaClass.simpleName})" }
    }
  }

  private const val TAG = "WidgetWiring"

  /** Le istantanee arrivano a raffiche durante la lettura del telefono e la sincronizzazione: il controllo dei mostrati dopo la calma. */
  private val SNAPSHOT_QUIET = 1.seconds
}
