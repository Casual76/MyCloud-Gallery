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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

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
 * **XS-5**: qui non si toglie nessuna richiesta: la risoluzione delle miniature non decide cosa e' "fatto" (lo decide il ciclo di lavoro con
 * il criterio del calcolo, [AiUrgentSweep]).
 *
 * Il nastro si ricalcola **solo mentre qualcuno lo guarda** (la pagina aperta): a pagina chiusa non costa niente, nemmeno le letture del
 * filtro finale.
 */
class AppAnalysisLive(
  engineLive: StateFlow<AiLive>,
  private val visible: VisibleMedia,
  private val mask: HiddenMask,
  private val cells: CellKeys,
  private val facts: ContentFacts,
  scope: CoroutineScope,
  /** Gli eventi che cambiano cio' che la Libreria mostra **senza** cambiare l'istantanea (un "Nascondi" appena fatto, una revoca): rifanno il nastro. */
  changes: Flow<Unit> = emptyFlow(),
) : AnalysisLiveSource {

  override val live: StateFlow<AnalysisFlow> =
    combine(engineLive, visible.snapshot, changes.onStart { emit(Unit) }) { engine, snapshot, _ -> resolve(engine, snapshot) }
      .stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MILLIS), AnalysisFlow.EMPTY)

  private suspend fun resolve(engine: AiLive, snapshot: TimelineSnapshot?): AnalysisFlow {
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

  private companion object {
    /** La pagina che si chiude e si riapre subito non rifa' tutto da capo. */
    const val STOP_AFTER_MILLIS: Long = 5_000L
  }
}
