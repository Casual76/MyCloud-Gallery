package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.ai.contract.AiLive
import dev.pampa.mycloudgallery.core.database.CellContent
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.search.index.CellKeys
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisFlow
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisLiveSource
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisThumb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import dev.pampa.mycloudgallery.core.common.log.GalleryLog

/**
 * Il **cancello di riservatezza** della pagina "Analisi delle foto" (Pacchetto X, corretto da XS-1 del Pacchetto XF): una chiave del
 * motore diventa un'anteprima solo se il suo media e' una cella che la Libreria mostra **adesso** — e' nell'istantanea **autorizzata** (quella
 * che la Libreria disegna: `visibleTimeline`, che con l'accesso parziale o revocato, o finche' la selezione non e' letta, **non c'e'**) e la
 * chiave e' davvero fra quelle della cella ([CellContent.keys]: le sue e quelle dei membri del gruppo). Altrimenti niente posizione: la
 * pagina disegna un segnaposto neutro (si conta nel numero, non si mostra). Nel dubbio (chiave mai elencata in questo processo, cella non
 * ancora letta, un membro che non e' la copertina del gruppo, nessuna vista) non si mostra: chiuso, mai aperto per sbaglio.
 *
 * Questo e' solo il primo passo. Il **filtro finale** (D22) lo applica [AppAnalysisLive] con le porte condivise [VisibleMedia] e [HiddenMask],
 * le stesse delle altre pagine, subito prima di costruire un'anteprima.
 */
object AnalysisGate {

  /** La posizione della cella di [key] in [snapshot], o null se il media non si puo' mostrare in questo momento. */
  fun positionOf(
    key: ContentKey,
    fact: ContentFact?,
    snapshot: TimelineSnapshot?,
    cellOf: (MediaId) -> CellContent?,
  ): Int? {
    val snap = snapshot ?: return null
    val id = fact?.mediaId ?: return null
    val at = snap.indexOf(id)
    if (at < 0) return null
    val cell = cellOf(id) ?: return null
    return if (key in cell.keys) at else null
  }
}

/**
 * **XS3-2**: chi possiede la visibilita' (la Libreria: un "Nascondi", un cestino, un permesso che cambia) segnala il cambio con un **callback**, non con un flusso.
 * Il callback viene chiamato **prima di tornare a chi cambia**, da qualunque thread; [AppAnalysisLive] lo usa per invalidare il valore condiviso nello stesso
 * istante (alza la generazione e scrive subito lo stato neutro), senza passare da code, `combine`, ne' dall'attesa della cancellazione di una risoluzione in corso.
 */
fun interface VisibilitySignal {
  /** Registra [onChange]; il [AutoCloseable] restituito la toglie. */
  fun register(onChange: () -> Unit): AutoCloseable

  /** Controllo diretto del proprietario, anche mentre i flussi derivati sono indietro. */
  fun canResolve(): Boolean = true

  companion object {
    /** Nessun segnale (le prove, o chi non ne ha): resta l'istantanea autorizzata, che e' osservata comunque. */
    val None: VisibilitySignal = VisibilitySignal { AutoCloseable { } }
  }
}

/**
 * Il nastro della pagina "Analisi delle foto": le chiavi del motore ([AiLive]) risolte in anteprime mostrabili ([AnalysisGate] e il filtro
 * finale dei Nascosti), con le stesse richieste di miniatura di Cerca (stessa cache, solo cio' che e' gia' sul dispositivo: D28, D33). Poche
 * decine di voci al massimo, ricalcolate solo quando il motore avanza, la Libreria cambia o cambia cio' che e' nascosto.
 *
 * **XS-1**: la sorgente e' la vista **autorizzata** della Libreria ([visible]: `visibleTimeline`, non l'istantanea di base, che con un accesso
 * ridotto conserva elementi che la Libreria non mostra), si osserva il suo cambiare (permesso, selezione, nascondi) e prima di ogni anteprima
 * passa il **filtro finale condiviso**: [VisibleMedia.visible] e [HiddenMask.hiddenAmong], che falliscono chiusi. Nessun `Shown`, nessun
 * ThumbHash, nessun riferimento per un media che la Libreria esclude. Questo file e' una superficie del registro dei Nascosti
 * (`HiddenSurfaces`, riga `analysis-page`).
 *
 * **XS2-1, la riservatezza non sopravvive alla superficie** (e **XS3-2**, l'invalidazione e' sincrona):
 * - **il valore condiviso e' di questa classe** ([shared], un `MutableStateFlow` scritto **solo sotto lucchetto**), non il risultato di una pipeline: quando chi possiede
 *   la visibilita' segnala il cambio ([VisibilitySignal], o l'istantanea autorizzata che cambia: la raccolta e' `Unconfined`, parte dentro la scrittura di chi la cambia)
 *   il callback **alza la generazione e scrive subito lo stato neutro** nel valore condiviso. Chi legge `live.value` o rientra entro lo stop non vede piu' nessuna
 *   `Shown` della generazione vecchia, **anche con una risoluzione lenta che non coopera** (bloccata su un latch, non su una sospensione): la risoluzione in corso non
 *   serve ad aspettarla;
 * - **una risoluzione scrive solo se, sotto lo stesso lucchetto, la generazione e l'istantanea sono ancora le sue**: una risoluzione partita prima di una revoca
 *   e finita dopo butta il suo risultato, qualunque sia il tempo che ha impiegato;
 * - **nessun replay**: senza osservatori per [stopTimeoutMillis] il produttore si ferma e il valore condiviso torna **subito** al vuoto ([AnalysisFlow.EMPTY]);
 *   chi rientra dopo una revoca, un "Nascondi" o il cestino fatti a flusso fermo non riceve mai l'ultimo valore vecchio. Il solo avanzare del motore non alza la
 *   generazione (nessun lampeggio durante il lavoro) e [AnalysisFlow.finished] non si azzera a una generazione nuova;
 * - **ordine delle emissioni**: vuoto -> neutro (appena si comincia a guardare) -> risolto.
 *
 * **XS3-3**: un errore del produttore (la registrazione del segnale, la lettura) non esce da qui come eccezione senza gestore e non e' ingoiato: si **chiude** (stato
 * neutro) e si scrive nel log di sistema (solo il tipo).
 *
 * **XS-5**: qui non si toglie nessuna richiesta: la risoluzione delle miniature non decide cosa e' "fatto" (lo decide il ciclo di lavoro con
 * il criterio del calcolo, [AiUrgentSweep]).
 *
 * Il nastro si ricalcola **solo mentre qualcuno lo guarda**; il produttore resta attivo ancora [stopTimeoutMillis] dopo l'ultimo osservatore (una pagina che si chiude
 * e si riapre subito non rifa' tutto da capo), poi si ferma e si azzera. Nota: la cache calda del caricatore di miniature (`PipelineThumbnailLoader`) non e' di questa classe.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
class AppAnalysisLive(
  private val engineLive: StateFlow<AiLive>,
  private val visible: VisibleMedia,
  private val mask: HiddenMask,
  private val cells: CellKeys,
  private val facts: ContentFacts,
  private val scope: CoroutineScope,
  /** Chi possiede la visibilita' segnala qui un "Nascondi" appena fatto, una revoca, il cestino. */
  private val signal: VisibilitySignal = VisibilitySignal.None,
  private val stopTimeoutMillis: Long = STOP_AFTER_MILLIS,
) : AnalysisLiveSource {

  /** Il valore condiviso: si scrive solo sotto [lock] (generazione e valore stanno sempre insieme). */
  private val shared = MutableStateFlow(AnalysisFlow.EMPTY)

  private val lock = Any()

  // Tutto cio' che segue si legge e si scrive solo sotto [lock].
  private var generation = 0L
  private var running = false
  private var observers = 0
  private var producer: Job? = null
  private var stopper: Job? = null
  private var lastSnapshot: TimelineSnapshot? = null
  private var snapshotKnown = false

  /** Una nuova risoluzione da fare (conflata: ne basta una). */
  private val retrigger = Channel<Unit>(Channel.CONFLATED)

  /** Lo stato condiviso, con il conto di chi lo guarda: il primo che arriva fa partire il produttore, l'ultimo che se ne va lo ferma dopo [stopTimeoutMillis]. */
  override val live: StateFlow<AnalysisFlow> = object : StateFlow<AnalysisFlow> by shared {
    override suspend fun collect(collector: FlowCollector<AnalysisFlow>): Nothing {
      attach()
      try {
        shared.collect(collector)
      } finally {
        detach()
      }
    }
  }

  private fun attach() {
    synchronized(lock) {
      observers++
      stopper?.cancel()
      stopper = null
      if (!running) startLocked()
    }
  }

  private fun detach() {
    synchronized(lock) {
      observers--
      if (observers == 0 && running) {
        stopper?.cancel()
        stopper = scope.launch {
          delay(stopTimeoutMillis)
          synchronized(lock) { if (observers == 0) stopLocked() }
        }
      }
    }
  }

  /** Si comincia a guardare: la generazione sale e il valore condiviso diventa subito neutro (con i posti del motore), poi parte il produttore. */
  private fun startLocked() {
    running = true
    generation++
    lastSnapshot = null
    snapshotKnown = false
    shared.value = neutral(engineLive.value, generation)
    producer = scope.launch { produce() }
  }

  /** Nessuno guarda piu': il produttore si ferma e il valore condiviso torna al vuoto (nessun replay); una risoluzione ancora in corso non scrivera' piu'. */
  private fun stopLocked() {
    running = false
    producer?.cancel()
    producer = null
    stopper = null
    generation++
    shared.value = AnalysisFlow.EMPTY
  }

  private suspend fun produce() {
    try {
      val registration = signal.register { onVisibilityChanged() }
      try {
        coroutineScope {
          // Unconfined: la reazione parte dentro la scrittura di chi cambia l'istantanea, non dopo una coda.
          launch(Dispatchers.Unconfined) { visible.snapshot.collect { onSnapshot(it) } }
          launch(Dispatchers.Unconfined) { engineLive.collect { retrigger.trySend(Unit) } }
          retrigger.receiveAsFlow().collectLatest { resolveNow() }
        }
      } finally {
        registration.close()
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      failClosed(e)
    }
  }

  /** XS3-3: un errore del produttore chiude il nastro (neutro) e lascia una riga nel log (solo il tipo); mai un'eccezione senza gestore. */
  private fun failClosed(e: Exception) {
    GalleryLog.e(TAG, e) { "nastro dell'analisi: errore (${e.javaClass.simpleName}), chiuso" }
    synchronized(lock) {
      if (!running) return
      generation++
      shared.value = neutral(engineLive.value, generation)
    }
  }

  /** XS3-2: il proprietario della visibilita' ha cambiato qualcosa: **subito** generazione nuova e stato neutro, poi si ricalcola. */
  private fun onVisibilityChanged() {
    synchronized(lock) {
      if (!running) return
      generation++
      shared.value = neutral(engineLive.value, generation)
    }
    retrigger.trySend(Unit)
  }

  /**
   * L'istantanea autorizzata e' cambiata. La generazione sale quando compare o sparisce, e quando una nuova istantanea non ha piu' un media che il nastro stava
   * mostrando; il solo avanzare del motore, o un'istantanea con in piu' altri media, no. Le risoluzioni partite sulla vecchia istantanea non scrivono piu' ([resolveNow]).
   */
  private fun onSnapshot(snapshot: TimelineSnapshot?) {
    synchronized(lock) {
      if (!running) return
      val bump = snapshotKnown && (
        (snapshot == null) != (lastSnapshot == null) ||
          (snapshot != null && snapshot !== lastSnapshot && shownIds(shared.value).any { snapshot.indexOf(it) < 0 })
        )
      snapshotKnown = true
      lastSnapshot = snapshot
      if (bump) {
        generation++
        shared.value = neutral(engineLive.value, generation)
      }
    }
    retrigger.trySend(Unit)
  }

  private fun shownIds(flow: AnalysisFlow): Set<MediaId> =
    (listOfNotNull(flow.current) + flow.recent + flow.next).filterIsInstance<AnalysisThumb.Shown>().mapTo(HashSet()) { it.id }

  /** Lo stesso numero di posti dello stato del motore, tutti neutri: nessun byte, nessun ThumbHash, nessun riferimento. */
  private fun neutral(engine: AiLive, number: Long) = AnalysisFlow(
    current = engine.current?.let { AnalysisThumb.Quiet },
    recent = engine.recent.map { AnalysisThumb.Quiet },
    next = engine.next.map { AnalysisThumb.Quiet },
    visibilityGeneration = number,
    finished = engine.finished,
  )

  /**
   * Una risoluzione: legge, **poi scrive solo se sotto il lucchetto** la generazione e l'istantanea sono ancora quelle con cui e' partita. Una lettura lenta o non
   * cooperativa non ritarda la neutralizzazione (gia' scritta da chi ha segnalato il cambio) e il suo risultato vecchio non arriva mai al valore condiviso: **questa e' l'unica
   * guardia** (non c'e' un `ensureActive` ridondante prima), cosi' una prova che la toglie cade davvero.
   */
  private suspend fun resolveNow() {
    val start = synchronized(lock) { if (running && snapshotKnown && signal.canResolve()) Start(generation, lastSnapshot, engineLive.value) else null } ?: return
    val result = resolve(start.engine, start.snapshot, start.generation)
    synchronized(lock) {
      if (running && signal.canResolve() && generation == start.generation &&
        lastSnapshot === start.snapshot && visible.snapshot.value === start.snapshot) shared.value = result
    }
  }

  private class Start(val generation: Long, val snapshot: TimelineSnapshot?, val engine: AiLive)

  private suspend fun resolve(engine: AiLive, snapshot: TimelineSnapshot?, number: Long): AnalysisFlow {
    val columnar = snapshot as? ColumnarTimelineSnapshot
    val keys = LinkedHashSet<ContentKey>()
    engine.current?.let { keys += it }
    keys += engine.recent
    keys += engine.next
    // 1. Il cancello: la posizione nell'istantanea autorizzata.
    val spots = HashMap<ContentKey, Int>()
    if (columnar != null) {
      for (key in keys) AnalysisGate.positionOf(key, facts[key], columnar) { cells[it] }?.let { spots[key] = it }
    }
    // 2. Il filtro finale (D22): quali di questi media si possono mostrare adesso. Un errore vale "nessuno" (chiuso).
    val ids = spots.values.mapTo(LinkedHashSet()) { columnar!!.idAt(it) }
    val allowed = if (ids.isEmpty()) emptySet() else finalFilter(ids)
    fun thumb(key: ContentKey): AnalysisThumb {
      val at = spots[key] ?: return AnalysisThumb.Quiet
      val snap = columnar ?: return AnalysisThumb.Quiet
      val id = snap.idAt(at)
      if (id !in allowed) return AnalysisThumb.Quiet
      val request = snap.thumbRefAt(at)?.let { ThumbRequest(ThumbKey(id, snap.versionKeyAt(at), ThumbBucket.M), it) }
      return AnalysisThumb.Shown(id, request, snap.thumbHashAt(at), video = snap.kindAt(at) == MediaKind.VIDEO)
    }
    return AnalysisFlow(
      current = engine.current?.let { thumb(it) },
      recent = engine.recent.map { thumb(it) },
      next = engine.next.map { thumb(it) },
      visibilityGeneration = number,
      finished = engine.finished,
    )
  }

  /** `VisibleMedia` poi `HiddenMask`, come ogni superficie (il progetto vuole il filtro finale anche dopo la vista); chiuso se qualcosa fallisce. */
  private suspend fun finalFilter(ids: Set<MediaId>): Set<MediaId> = try {
    visible.visible(ids) - mask.hiddenAmong(ids)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    emptySet()
  }

  companion object {
    /** La pagina che si chiude e si riapre subito non rifa' tutto da capo. */
    const val STOP_AFTER_MILLIS: Long = 5_000L

    private const val TAG = "AnalysisLive"
  }
}
