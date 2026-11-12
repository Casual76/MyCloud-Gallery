package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.ai.contract.AiLive
import dev.pampa.mycloudgallery.core.database.CellContent
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.search.index.CellKeys
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisFlow
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisLiveSource
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisThumb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Il **cancello di riservatezza** della pagina "Analisi delle foto" (Pacchetto X): una chiave del motore diventa un'anteprima solo se
 * il suo media e' una cella che la Libreria mostra **adesso** — e' nell'istantanea corrente e la chiave e' davvero fra quelle della
 * cella ([CellContent.keys]: le sue e quelle dei membri del gruppo). Altrimenti niente posizione: la pagina disegna un segnaposto
 * neutro (si conta nel numero, non si mostra). E' lo stesso principio delle altre pagine (il volto di Cerca porta le sue origini e si
 * ritira se nessuna e' piu' visibile): qui il controllo si rifa' a ogni cambio di istantanea, cosi' un media nascosto, protetto,
 * cestinato o revocato **mentre la pagina e' aperta** sparisce dal nastro. Nel dubbio (chiave mai elencata in questo processo, cella
 * non ancora letta, un membro che non e' la copertina del gruppo) non si mostra: chiuso, mai aperto per sbaglio.
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
 * Il nastro della pagina "Analisi delle foto": le chiavi del motore ([AiLive]) risolte in anteprime mostrabili ([AnalysisGate]),
 * con le stesse richieste di miniatura di Cerca (stessa cache, solo cio' che e' gia' sul dispositivo: D28, D33). Poche decine di
 * voci al massimo, ricalcolate solo quando il motore avanza o la Libreria cambia: nessun interrogare il database a ogni fotogramma.
 * Le richieste gia' soddisfatte (calcolate da un altro dispositivo e arrivate con la replica) escono dal registro delle urgenze.
 */
class AppAnalysisLive(
  private val ai: AiGraph,
  snapshot: StateFlow<TimelineSnapshot?>,
  private val cells: CellKeys,
  private val facts: ContentFacts,
  private val hasVectors: (ContentKey) -> Boolean,
  scope: CoroutineScope,
) : AnalysisLiveSource {

  override val live: StateFlow<AnalysisFlow> = combine(ai.live, snapshot, ai.urgent.state) { live, snap, _ ->
    forgetDone()
    AnalysisFlow(
      current = live.current?.let { thumb(it, snap) },
      recent = live.recent.map { thumb(it, snap) },
      next = live.next.map { thumb(it, snap) },
    )
  }.stateIn(scope, SharingStarted.Eagerly, AnalysisFlow.EMPTY)

  /** Un'urgenza il cui vettore e' gia' qui (fatto da un altro dispositivo) non deve restare in testa per sempre. */
  private fun forgetDone() {
    val done = ai.urgent.state.value.filter { hasVectors(it) }
    if (done.isNotEmpty()) ai.urgent.remove(done)
  }

  private fun thumb(key: ContentKey, snap: TimelineSnapshot?): AnalysisThumb {
    val at = AnalysisGate.positionOf(key, facts[key], snap) { cells[it] } ?: return AnalysisThumb.Quiet
    val columnar = snap as? ColumnarTimelineSnapshot ?: return AnalysisThumb.Quiet
    val id = columnar.idAt(at)
    val request = columnar.thumbRefAt(at)?.let { ThumbRequest(ThumbKey(id, columnar.versionKeyAt(at), ThumbBucket.M), it) }
    return AnalysisThumb.Shown(id, request, columnar.thumbHashAt(at), video = columnar.kindAt(at) == MediaKind.VIDEO)
  }
}
